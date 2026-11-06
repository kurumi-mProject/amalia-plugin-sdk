package org.amalia.platform.runtime.remote

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** SHA-256 of the complete DER leaf certificate, not a name, address or payload field. */
data class PeerFingerprint(val sha256: String) {
    init { require(sha256.matches(Regex("[0-9a-f]{64}"))) }
    companion object {
        fun of(certificate: X509Certificate): PeerFingerprint = PeerFingerprint(
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        )
    }
}

data class PeerApproval(
    val fingerprint: PeerFingerprint,
    val owner: String,
    val approvedAtEpochMillis: Long,
    val outOfBandEvidence: String,
) {
    init { require(owner.isNotBlank() && outOfBandEvidence.isNotBlank()); require(approvedAtEpochMillis > 0) }
}

data class PeerTrustSnapshot(val revision: Long, val approved: Map<PeerFingerprint, PeerApproval>) {
    init { require(revision >= 0); require(approved.all { (key, value) -> key == value.fingerprint }) }
}

/** Trusted host storage: atomic durable compare-and-set, monotonic revisions, rollback protection.
 * Throw on unavailable/uncertain persistence. One live PeerTrust owner per storage authority.
 */
interface PeerTrustStorage {
    fun load(): PeerTrustSnapshot
    fun compareAndSet(expectedRevision: Long, replacement: PeerTrustSnapshot): Boolean
}

/** No network enrollment or TOFU. Call approveOutOfBand only after a host-controlled explicit
 * user/admin approval of the full fingerprint obtained over a separate authenticated channel.
 */
class PeerTrust(private val storage: PeerTrustStorage) {
    private var snapshot = storage.load().let { it.copy(approved = it.approved.toMap()) }
    private val sockets = mutableMapOf<Socket, PeerFingerprint>()
    private val locallyRevoked = mutableSetOf<PeerFingerprint>()

    @Synchronized fun snapshot(): PeerTrustSnapshot = snapshot.copy(approved = snapshot.approved.toMap())

    @Synchronized fun approveOutOfBand(approval: PeerApproval, expectedRevision: Long) {
        replace(expectedRevision, snapshot.approved + (approval.fingerprint to approval))
        locallyRevoked.remove(approval.fingerprint)
    }

    @Synchronized fun revoke(fingerprint: PeerFingerprint, expectedRevision: Long) {
        // Deny locally even if durable persistence fails. Explicit approval clears this latch.
        locallyRevoked.add(fingerprint)
        closeWhere { it == fingerprint }
        replace(expectedRevision, snapshot.approved - fingerprint)
    }

    /** Import authoritative host changes, including revocation made elsewhere. Never roll back. */
    @Synchronized fun refresh() {
        try {
            val next = storage.load()
            check(next.revision >= snapshot.revision) { "Trust revision rollback" }
            check(next.revision != snapshot.revision || next == snapshot) { "Conflicting trust revision" }
            snapshot = next.copy(approved = next.approved.toMap())
            closeWhere { it !in snapshot.approved }
        } catch (failure: Exception) {
            closeWhere { true }
            throw failure
        }
    }

    @Synchronized internal fun track(socket: Socket, fingerprint: PeerFingerprint) {
        requireApproved(fingerprint)
        sockets[socket] = fingerprint
    }
    @Synchronized internal fun untrack(socket: Socket) { sockets.remove(socket) }
    @Synchronized internal fun requireApproved(fingerprint: PeerFingerprint) {
        // Consult durable authority at authentication and every dispatch/response boundary.
        refresh()
        check(fingerprint !in locallyRevoked && fingerprint in snapshot.approved) { "Peer is not approved" }
    }

    private fun replace(expectedRevision: Long, approvals: Map<PeerFingerprint, PeerApproval>) {
        try {
            check(snapshot.revision == expectedRevision) { "Stale trust revision" }
            check(expectedRevision < Long.MAX_VALUE) { "Trust revision exhausted" }
            val next = PeerTrustSnapshot(expectedRevision + 1, approvals.toMap())
            check(storage.compareAndSet(expectedRevision, next)) { "Trust storage conflict" }
            snapshot = next
        } catch (failure: Exception) {
            closeWhere { true }
            throw failure
        }
    }
    private fun closeWhere(predicate: (PeerFingerprint) -> Boolean) {
        val closing = sockets.filterValues(predicate).keys.toList()
        closing.forEach { socket ->
            sockets.remove(socket)
            runCatching { socket.close() } // Continue closing all revoked sockets if one close fails.
        }
    }
}
