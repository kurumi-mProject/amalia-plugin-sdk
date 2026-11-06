package org.amalia.platform.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Host-issued authority, not a sandbox for trusted in-process plugins. Every call rechecks approval. */
interface HostFeatures {
    val notifications: NotificationAccess
    val secrets: SecretStore
    val browser: BrowserAccess
    val device: DeviceAccess
    val background: BackgroundAccess
    val scheduler: SchedulerAccess
}

object HostCapability {
    const val NOTIFICATIONS = "notifications"
    const val SECRETS = "secrets"
    /** Approval must represent a fresh host-observed user gesture, not a plugin assertion. */
    const val BROWSER = "browser"
    const val DEVICE_SNAPSHOT = "device.read"
    const val DEVICE_VOLUME = "device.volume"
    const val DEVICE_VIBRATE = "device.vibrate"
    const val BACKGROUND = "background.short"
    const val SCHEDULER = "scheduler"
}

data class HostNotification(val id: String, val title: String, val text: String) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(title.isNotBlank() && title.length <= 256 && text.length <= 4096)
    }
}
interface NotificationAccess {
    suspend fun notify(notification: HostNotification)
    suspend fun cancel(id: String)
}
interface SecretStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun delete(key: String)
}
interface BrowserAccess {
    /** HTTPS only; host approval must consume/verify a fresh user gesture on each invocation. */
    suspend fun openHttps(url: String)
}

data class DeviceSnapshot(
    val batteryFraction: Float?,
    val charging: Boolean,
    val networkConnected: Boolean,
    val networkValidated: Boolean,
    val networkMetered: Boolean,
    val mediaVolume: Int,
    val maxMediaVolume: Int,
)
interface DeviceAccess {
    suspend fun snapshot(): DeviceSnapshot
    /** Media stream only, absolute index; never adjusts ringer/DND or grants permissions. */
    suspend fun setMediaVolume(index: Int)
    suspend fun vibrate(durationMillis: Long)
}

@Serializable
data class HostOperation(val operationId: String, val input: JsonElement = JsonNull) {
    init {
        require(operationId.isNotBlank() && operationId.length <= 128)
        require(input.toString().toByteArray(Charsets.UTF_8).size <= 8192) { "Operation input exceeds limit" }
    }
}
enum class BackgroundState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }
interface BackgroundAccess {
    /** Activation-owned cooperative coroutine, at most 30s; no foreground service or process survival. */
    fun submit(operation: HostOperation, timeoutMillis: Long = 30_000): String
    fun cancel(id: String)
    fun state(id: String): BackgroundState?
}

@Serializable
data class HostSchedule(val id: String, val notBeforeEpochMillis: Long, val operation: HostOperation) {
    init { require(id.isNotBlank() && id.length <= 128); require(notBeforeEpochMillis >= 0) }
}
interface SchedulerAccess {
    /** Persisted one-shot, inexact, no deadline guarantee and no automatic retry.
     * Bound to the issuing identity AND generation. Host must explicitly restore that authority;
     * missing/revoked authority drops delivery, never implicitly resurrects a plugin.
     * Reusing an id replaces that activation's pending schedule. Payload is persisted, not secret.
     */
    suspend fun schedule(request: HostSchedule)
    suspend fun cancel(id: String)
    suspend fun pending(): List<HostSchedule>
}

object DeniedHostFeatures : HostFeatures {
    private fun denied(): Nothing = throw SecurityException("Host feature access has not been approved")
    override val notifications = object : NotificationAccess {
        override suspend fun notify(notification: HostNotification): Unit = denied()
        override suspend fun cancel(id: String): Unit = denied()
    }
    override val secrets = object : SecretStore {
        override suspend fun read(key: String): String? = denied()
        override suspend fun write(key: String, value: String): Unit = denied()
        override suspend fun delete(key: String): Unit = denied()
    }
    override val browser = object : BrowserAccess { override suspend fun openHttps(url: String): Unit = denied() }
    override val device = object : DeviceAccess {
        override suspend fun snapshot(): DeviceSnapshot = denied()
        override suspend fun setMediaVolume(index: Int): Unit = denied()
        override suspend fun vibrate(durationMillis: Long): Unit = denied()
    }
    override val background = object : BackgroundAccess {
        override fun submit(operation: HostOperation, timeoutMillis: Long): String = denied()
        override fun cancel(id: String): Unit = denied()
        override fun state(id: String): BackgroundState? = denied()
    }
    override val scheduler = object : SchedulerAccess {
        override suspend fun schedule(request: HostSchedule): Unit = denied()
        override suspend fun cancel(id: String): Unit = denied()
        override suspend fun pending(): List<HostSchedule> = denied()
    }
}
