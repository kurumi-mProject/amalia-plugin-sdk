package org.amalia.platform.runtime.remote

import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

internal fun remoteTimer(name: String) = ScheduledThreadPoolExecutor(1) { task ->
    Thread(task, name).apply { isDaemon = true }
}.apply {
    removeOnCancelPolicy = true
    setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
}

internal fun ScheduledThreadPoolExecutor.closeAfter(socket: Socket, millis: Int) = schedule(
    { runCatching { socket.close() } }, millis.toLong(), TimeUnit.MILLISECONDS
)

internal fun SSLSocket.configureRemote(client: Boolean, timeoutMillis: Int) {
    useClientMode = client
    enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        .also { require(it.isNotEmpty()) { "TLS 1.2 or newer required" } }.toTypedArray()
    if (!client) needClientAuth = true
    soTimeout = timeoutMillis
}

internal fun SSLSocket.authenticatedFingerprint(): PeerFingerprint {
    val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
        ?: throw SecurityException("Peer X509 certificate required")
    leaf.checkValidity()
    return PeerFingerprint.of(leaf)
}
