package org.amalia.platform.runtime.remote

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.amalia.platform.protocol.InvocationRequest
import org.amalia.platform.protocol.InvocationResponse
import org.amalia.platform.protocol.Protocol

/** Once any request write is attempted, missing/unverifiable response means UNKNOWN.
 * Preserve requestId for domain outcome verification; never automatically retry the effect.
 */
class RemoteUnknownOutcomeException(val requestId: String, cause: Exception) :
    IOException("Remote invocation outcome is unknown; verify before any retry", cause)

enum class RemoteClientState { READY, CLOSED, UNKNOWN }

/** Blocking single-flight client; use a host IO worker, never Android's main thread.
 * A client owns one authenticated TLS session. Reconnect is explicit through connect(); there is
 * no automatic retry, reconnect, downgrade or failover. close() may interrupt invoke from another thread.
 */
class RemoteInvocationClient private constructor(
    private val socket: SSLSocket,
    private val trust: PeerTrust,
    private val peer: PeerFingerprint,
    private val limits: RemoteLimits,
    private val timer: java.util.concurrent.ScheduledThreadPoolExecutor,
) : AutoCloseable {
    private val status = AtomicReference(RemoteClientState.READY)
    val state: RemoteClientState get() = status.get()
    private val callLock = Any()
    private val seen = HashSet<String>()
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)

    fun invoke(request: InvocationRequest): InvocationResponse = synchronized(callLock) {
        check(state == RemoteClientState.READY && !socket.isClosed) { "Session unavailable" }
        require(request.protocolVersion in Protocol.supportedVersions)
        require(request.requestId.length <= 128 && request.requestId !in seen) { "Repeated/invalid request ID" }
        check(seen.size < limits.maxRequestsPerSession) { "Session request limit reached" }
        val bytes = RemoteWire.encode(InvocationRequest.serializer(), request, limits)
        var writeAttempted = false
        val deadline = timer.closeAfter(socket, limits.requestMillis)
        try {
            trust.requireApproved(peer)
            check(!socket.isClosed) { "Session closed before send" }
            seen.add(request.requestId)
            writeAttempted = true
            RemoteWire.write(output, bytes)
            val response = RemoteWire.read(input, InvocationResponse.serializer(), limits)
            check(response.requestId == request.requestId) { "Response correlation mismatch" }
            trust.requireApproved(peer)
            response
        } catch (failure: Exception) {
            if (writeAttempted) status.set(RemoteClientState.UNKNOWN)
            close()
            if (writeAttempted) throw RemoteUnknownOutcomeException(request.requestId, failure)
            throw failure
        } finally { deadline.cancel(false) }
    }

    override fun close() {
        status.compareAndSet(RemoteClientState.READY, RemoteClientState.CLOSED)
        runCatching { socket.close() }
        timer.shutdownNow()
        trust.untrack(socket)
    }

    companion object {
        /** Address is only a route. expectedPeer must come from explicit host pairing, never discovery.
         * The host SSLContext supplies its client key and a validating, explicitly approved trust store.
         * No hostname identity is inferred: exact leaf pinning is the endpoint identity mechanism.
         * Supply a resolved address if DNS work must also be covered by the host's deadline.
         */
        fun connect(
            context: SSLContext,
            address: InetSocketAddress,
            expectedPeer: PeerFingerprint,
            trust: PeerTrust,
            limits: RemoteLimits = RemoteLimits(),
        ): RemoteInvocationClient {
            require(!address.isUnresolved) { "Host must resolve address under its own DNS deadline" }
            trust.requireApproved(expectedPeer)
            val socket = context.socketFactory.createSocket() as SSLSocket
            val timer = remoteTimer("amalia-remote-client-deadlines")
            try {
                val connection = timer.closeAfter(socket, limits.connectMillis)
                try { socket.connect(address, limits.connectMillis) } finally { connection.cancel(false) }
                socket.configureRemote(client = true, timeoutMillis = limits.handshakeMillis)
                val handshake = timer.closeAfter(socket, limits.handshakeMillis)
                try {
                    socket.startHandshake()
                    check(socket.authenticatedFingerprint() == expectedPeer) { "Server certificate pin mismatch" }
                    trust.track(socket, expectedPeer)
                } finally { handshake.cancel(false) }
                socket.soTimeout = limits.requestMillis
                val client = RemoteInvocationClient(socket, trust, expectedPeer, limits, timer)
                timer.schedule({ client.close() }, limits.sessionMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                return client
            } catch (failure: Exception) {
                trust.untrack(socket)
                runCatching { socket.close() }
                timer.shutdownNow()
                throw failure
            }
        }
    }
}
