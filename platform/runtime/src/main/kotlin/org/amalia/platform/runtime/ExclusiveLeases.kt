package org.amalia.platform.runtime

import java.util.UUID
import org.amalia.platform.protocol.CallerIdentity

/** First acquisition wins; no implicit preemption. Tokens fence stale release/renew callbacks. */
class ExclusiveLeases internal constructor(private val clock: () -> Long) : AutoCloseable {
    data class Lease internal constructor(
        val resourceId: String,
        val owner: CallerIdentity,
        val generation: Long,
        val providerId: String,
        val token: String,
        val expiresAtEpochMillis: Long,
    )
    private val held = mutableMapOf<String, Lease>()
    private var closed = false

    @Synchronized
    internal fun acquire(resourceId: String, owner: CallerIdentity, generation: Long, providerId: String, durationMillis: Long): Lease? {
        check(!closed)
        require(resourceId.isNotBlank() && providerId.isNotBlank() && durationMillis in 1..86_400_000)
        val existing = held[resourceId]
        if (existing != null && existing.expiresAtEpochMillis > clock()) return null
        return Lease(resourceId, owner, generation, providerId, UUID.randomUUID().toString(), clock() + durationMillis)
            .also { held[resourceId] = it }
    }

    @Synchronized
    fun isValid(lease: Lease): Boolean = !closed && held[lease.resourceId] == lease && lease.expiresAtEpochMillis > clock()

    @Synchronized
    fun release(lease: Lease): Boolean = held.remove(lease.resourceId, lease)

    @Synchronized
    fun renew(lease: Lease, durationMillis: Long): Lease? {
        require(durationMillis in 1..86_400_000)
        if (!isValid(lease)) return null
        // New token also fences a delayed callback holding the pre-renewal value.
        return lease.copy(token = UUID.randomUUID().toString(), expiresAtEpochMillis = clock() + durationMillis)
            .also { held[lease.resourceId] = it }
    }

    @Synchronized
    internal fun releaseOwner(owner: CallerIdentity, generation: Long, providerId: String? = null) {
        held.entries.removeAll { (_, lease) -> lease.owner == owner && lease.generation == generation &&
            (providerId == null || lease.providerId == providerId) }
    }

    @Synchronized
    override fun close() { closed = true; held.clear() }
}
