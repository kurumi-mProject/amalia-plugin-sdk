package org.amalia.platform.runtime.remote

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.amalia.platform.protocol.CallerIdentity
import org.amalia.platform.protocol.InvocationRequest
import org.amalia.platform.protocol.InvocationResponse
import org.amalia.platform.protocol.Protocol
import org.amalia.platform.protocol.ProtocolClient

/** The trusted host creates this from its own identity registry and clientFor(caller).
 * authorize must fail closed, recheck CURRENT caller grants/policy, and restrict exported operations.
 * It must not infer authority from request input, extensions or discovery names.
 * Runtime authorization still applies; this callback never installs grants.
 */
data class RemoteCallerBinding(
    val caller: CallerIdentity,
    val client: ProtocolClient,
    val authorize: (CallerIdentity, InvocationRequest) -> Boolean,
)

/** Minimal invocation-only transport. The SSLContext must contain host identity/key managers and
 * explicit host-approved X509 trust managers validating peer certificates (never trust-all).
 * In addition to that TLS validation PeerTrust pins exact approved leaf certificates.
 * Host callbacks are trusted bounded code; this class is not an in-process sandbox.
 */
class RemoteInvocationServer(
    context: SSLContext,
    bindAddress: InetSocketAddress,
    private val trust: PeerTrust,
    private val bindCaller: (PeerFingerprint) -> RemoteCallerBinding?,
    private val limits: RemoteLimits = RemoteLimits(),
    private val onSessionFailure: (Exception) -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val sockets = HashSet<SSLSocket>()
    private val timer = remoteTimer("amalia-remote-server-deadlines")
    private val workers = ThreadPoolExecutor(
        limits.maxSessions, limits.maxSessions, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(limits.maxSessions),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "amalia-remote-session").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val listener = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
        try {
            needClientAuth = true
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
                .also { require(it.isNotEmpty()) }.toTypedArray()
            bind(bindAddress, limits.maxSessions)
        } catch (failure: Exception) {
            close()
            workers.shutdownNow()
            timer.shutdownNow()
            throw failure
        }
    }
    val localAddress: InetSocketAddress get() = listener.localSocketAddress as InetSocketAddress
    private val acceptor = Thread({ acceptLoop() }, "amalia-remote-accept").apply { isDaemon = true; start() }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try { listener.accept() as SSLSocket } catch (failure: Exception) {
                if (!closed.get()) report(failure)
                close()
                return
            }
            val admitted = synchronized(sockets) {
                if (closed.get() || sockets.size >= limits.maxSessions) false else sockets.add(socket)
            }
            if (!admitted) { runCatching { socket.close() }; continue }
            try {
                // Deadline includes time spent waiting for a session worker.
                val lifetime = timer.closeAfter(socket, limits.sessionMillis)
                workers.execute {
                    try { serve(socket) } catch (failure: Exception) { report(failure) }
                    finally {
                        lifetime.cancel(false)
                        runCatching { socket.close() }
                        trust.untrack(socket)
                        synchronized(sockets) { sockets.remove(socket) }
                    }
                }
            } catch (failure: Exception) {
                synchronized(sockets) { sockets.remove(socket) }
                runCatching { socket.close() }
                report(failure)
            }
        }
    }

    private fun serve(socket: SSLSocket) {
        socket.configureRemote(client = false, timeoutMillis = limits.handshakeMillis)
        val handshake = timer.closeAfter(socket, limits.handshakeMillis)
        val fingerprint = try {
            socket.startHandshake()
            socket.authenticatedFingerprint().also { trust.track(socket, it) }
        } finally { handshake.cancel(false) }
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)
        val seen = HashSet<String>()
        repeat(limits.maxRequestsPerSession) {
            val deadline = timer.closeAfter(socket, limits.requestMillis)
            try {
                socket.soTimeout = limits.requestMillis
                val request = RemoteWire.read(input, InvocationRequest.serializer(), limits)
                require(request.requestId.length <= 128 && seen.add(request.requestId)) { "Repeated/invalid request ID" }
                require(request.protocolVersion in Protocol.supportedVersions) { "Unsupported protocol" }
                trust.requireApproved(fingerprint)
                // Resolve fresh every time. No remotely supplied identity enters this boundary.
                val binding = bindCaller(fingerprint) ?: throw SecurityException("Peer has no caller binding")
                check(binding.authorize(binding.caller, request)) { "Remote caller policy denied" }
                trust.requireApproved(fingerprint)
                check(!socket.isClosed && !closed.get()) { "Session closed before dispatch" }
                val response = runBlocking {
                    withTimeout(limits.requestMillis.toLong()) { binding.client.invoke(request) }
                }
                check(response.requestId == request.requestId) { "Runtime returned mismatched request ID" }
                trust.requireApproved(fingerprint)
                RemoteWire.write(output, RemoteWire.encode(InvocationResponse.serializer(), response, limits))
            } finally { deadline.cancel(false) }
        }
    }

    private fun report(failure: Exception) {
        // A broken host observer must not kill admission/cleanup. Never forward payloads to logs here.
        runCatching { onSessionFailure(failure) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        timer.shutdownNow()
    }
}
