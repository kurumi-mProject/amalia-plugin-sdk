package org.amalia.platform.runtime.testing

import java.net.URI
import kotlinx.coroutines.*
import org.amalia.platform.protocol.*
import kotlinx.serialization.json.JsonElement

/** Explicitly fake: no Android IO/encryption/process persistence. Host administration grants authority.
 * Per-activation records and manual clock; due schedules dispatch only on advanceTo, background only
 * on runBackground. Recreate this fake to model a new generation; close cancels all pending work.
 */
class FakeHostFeatures(
    val identity: CallerIdentity = CallerIdentity("fake.publisher", "fake.package", "fake.instance", "fake.device"),
    val generation: Long = 1,
    startEpochMillis: Long = 0,
    private val dispatch: suspend (String, JsonElement) -> Unit = { _, _ -> },
) : HostFeatures, AutoCloseable {
    private val lock = Any()
    private val grants = mutableSetOf<String>()
    private var closed = false
    private var nextId = 0L
    private var now = startEpochMillis
    private var browserGestures = 0
    private val notices = linkedMapOf<String, HostNotification>()
    private val secretValues = mutableMapOf<String, String>()
    private val opened = mutableListOf<String>()
    private val vibrations = mutableListOf<Long>()
    private val work = linkedMapOf<String, Work>()
    private val schedules = linkedMapOf<String, HostSchedule>()
    private val deliveries = mutableListOf<HostOperation>()
    private var snapshotValue = DeviceSnapshot(1f, false, true, true, false, 5, 15)
    private data class Work(val operation: HostOperation, val timeout: Long, var state: BackgroundState, var job: Job? = null)
    init { require(generation >= 0 && startEpochMillis >= 0) }
    val nowEpochMillis: Long get() = synchronized(lock) { now }
    val notificationRecords: Map<String, HostNotification> get() = synchronized(lock) { notices.toMap() }
    val browserRecords: List<String> get() = synchronized(lock) { opened.toList() }
    val vibrationRecords: List<Long> get() = synchronized(lock) { vibrations.toList() }
    val dispatchRecords: List<HostOperation> get() = synchronized(lock) { deliveries.toList() }
    fun grant(capability: String) { synchronized(lock) { check(!closed); grants += capability } }
    fun revoke(capability: String) { synchronized(lock) { grants -= capability } }
    /** Fake host event, not something a plugin declaration can issue. One gesture permits one launch. */
    fun userGesture() { synchronized(lock) { check(!closed); browserGestures = 1 } }
    fun setSnapshot(snapshot: DeviceSnapshot) { synchronized(lock) {
        require(snapshot.maxMediaVolume >= 0 && snapshot.mediaVolume in 0..snapshot.maxMediaVolume)
        require(snapshot.batteryFraction == null || snapshot.batteryFraction in 0f..1f)
        snapshotValue = snapshot
    } }
    private fun checkAccess(capability: String) {
        check(!closed) { "Fake activation is closed" }
        if (capability !in grants) throw SecurityException("Fake host capability not granted: $capability")
    }
    private fun id(id: String) { require(id.isNotBlank() && id.length <= 128) }
    private fun secretKey(key: String) { require(key.isNotBlank() && key.toByteArray(Charsets.UTF_8).size <= 256) }
    override val notifications = object : NotificationAccess {
        override suspend fun notify(notification: HostNotification) { synchronized(lock) {
            checkAccess(HostCapability.NOTIFICATIONS)
            require(notification.id in notices || notices.size < 32)
            notices[notification.id] = notification
        } }
        override suspend fun cancel(id: String) { synchronized(lock) {
            checkAccess(HostCapability.NOTIFICATIONS); id(id); notices.remove(id)
        } }
    }
    override val secrets = object : SecretStore {
        override suspend fun read(key: String): String? = synchronized(lock) {
            checkAccess(HostCapability.SECRETS); secretKey(key); secretValues[key]
        }
        override suspend fun write(key: String, value: String) { synchronized(lock) {
            checkAccess(HostCapability.SECRETS); secretKey(key)
            require(value.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
            require(key in secretValues || secretValues.size < 64)
            secretValues[key] = value
        } }
        override suspend fun delete(key: String) { synchronized(lock) {
            checkAccess(HostCapability.SECRETS); secretKey(key); secretValues.remove(key)
        } }
    }
    override val browser = object : BrowserAccess {
        override suspend fun openHttps(url: String) { synchronized(lock) {
            require(url.length <= 8192)
            val uri = URI(url)
            require(uri.scheme == "https" && uri.rawUserInfo == null && !uri.host.isNullOrBlank() && uri.port in setOf(-1, 443))
            checkAccess(HostCapability.BROWSER)
            if (browserGestures == 0) throw SecurityException("Fake browser requires a host-observed user gesture")
            browserGestures--
            require(opened.size < 256) { "Fake browser record limit exceeded" }
            opened += url
        } }
    }
    override val device = object : DeviceAccess {
        override suspend fun snapshot(): DeviceSnapshot = synchronized(lock) { checkAccess(HostCapability.DEVICE_SNAPSHOT); snapshotValue }
        override suspend fun setMediaVolume(index: Int) { synchronized(lock) {
            checkAccess(HostCapability.DEVICE_VOLUME)
            require(index in 0..snapshotValue.maxMediaVolume)
            snapshotValue = snapshotValue.copy(mediaVolume = index)
        } }
        override suspend fun vibrate(durationMillis: Long) { synchronized(lock) {
            checkAccess(HostCapability.DEVICE_VIBRATE); require(durationMillis in 1..1000)
            require(vibrations.size < 256) { "Fake vibration record limit exceeded" }
            vibrations += durationMillis
        } }
    }
    override val background = object : BackgroundAccess {
        override fun submit(operation: HostOperation, timeoutMillis: Long): String = synchronized(lock) {
            checkAccess(HostCapability.BACKGROUND); require(timeoutMillis in 1..30_000)
            require(work.values.count { it.state in setOf(BackgroundState.QUEUED, BackgroundState.RUNNING) } < 8)
            while (work.size >= 64) work.remove(work.entries.first { it.value.state !in setOf(BackgroundState.QUEUED, BackgroundState.RUNNING) }.key)
            val id = "fake-background-${++nextId}"
            work[id] = Work(operation, timeoutMillis, BackgroundState.QUEUED)
            id
        }
        override fun cancel(id: String) { synchronized(lock) {
            checkAccess(HostCapability.BACKGROUND)
            work[id]?.let { if (it.state in setOf(BackgroundState.QUEUED, BackgroundState.RUNNING)) {
                it.state = BackgroundState.CANCELLED; it.job?.cancel()
            } }
        } }
        override fun state(id: String): BackgroundState? = synchronized(lock) { checkAccess(HostCapability.BACKGROUND); work[id]?.state }
    }
    /** Runs one queued operation in the caller's coroutine (real cooperative timeout, no OS work). */
    suspend fun runBackground(id: String) = coroutineScope {
        val ownerJob = currentCoroutineContext()[Job]
        val item = synchronized(lock) {
            checkAccess(HostCapability.BACKGROUND)
            val item = checkNotNull(work[id]) { "Unknown fake background id" }
            check(item.state == BackgroundState.QUEUED)
            item.state = BackgroundState.RUNNING
            item.job = ownerJob
            item
        }
        try {
            withTimeout(item.timeout) {
                record(item.operation)
                dispatch(item.operation.operationId, item.operation.input)
                synchronized(lock) { checkAccess(HostCapability.BACKGROUND) }
            }
            synchronized(lock) { if (item.state != BackgroundState.CANCELLED) item.state = BackgroundState.SUCCEEDED }
        } catch (error: TimeoutCancellationException) {
            synchronized(lock) { if (item.state != BackgroundState.CANCELLED) item.state = BackgroundState.TIMED_OUT }
        } catch (error: CancellationException) {
            synchronized(lock) { item.state = BackgroundState.CANCELLED }; throw error
        } catch (error: Exception) {
            synchronized(lock) { if (item.state != BackgroundState.CANCELLED) item.state = BackgroundState.FAILED }; throw error
        } finally { synchronized(lock) { item.job = null } }
    }
    override val scheduler = object : SchedulerAccess {
        override suspend fun schedule(request: HostSchedule) { synchronized(lock) {
            checkAccess(HostCapability.SCHEDULER)
            require(request.id in schedules || schedules.size < 32)
            schedules[request.id] = request
        } }
        override suspend fun cancel(id: String) { synchronized(lock) { checkAccess(HostCapability.SCHEDULER); id(id); schedules.remove(id) } }
        override suspend fun pending(): List<HostSchedule> = synchronized(lock) {
            checkAccess(HostCapability.SCHEDULER); schedules.values.sortedWith(compareBy({ it.notBeforeEpochMillis }, { it.id }))
        }
    }
    /** Snapshot due jobs once, remove before dispatch (no retry). Jobs created by callbacks wait for
     * the next explicit clock advance. Failures propagate; unattempted due jobs remain pending.
     */
    suspend fun advanceTo(epochMillis: Long) {
        val due = synchronized(lock) {
            checkAccess(HostCapability.SCHEDULER); require(epochMillis >= now); now = epochMillis
            schedules.values.filter { it.notBeforeEpochMillis <= now }.sortedWith(compareBy({ it.notBeforeEpochMillis }, { it.id }))
        }
        for (request in due) {
            currentCoroutineContext().ensureActive()
            val deliver = synchronized(lock) {
                checkAccess(HostCapability.SCHEDULER)
                if (schedules[request.id] != request) false else { schedules.remove(request.id); true }
            }
            if (deliver) {
                record(request.operation)
                withTimeout(30_000) { dispatch(request.operation.operationId, request.operation.input) }
                synchronized(lock) { checkAccess(HostCapability.SCHEDULER) }
            }
        }
    }
    private fun record(operation: HostOperation) { synchronized(lock) {
        require(deliveries.size < 256) { "Fake dispatch record limit exceeded" }; deliveries += operation
    } }
    override fun close() { synchronized(lock) {
        if (closed) return
        closed = true
        work.values.forEach { if (it.state in setOf(BackgroundState.QUEUED, BackgroundState.RUNNING)) {
            it.state = BackgroundState.CANCELLED; it.job?.cancel()
        } }
        schedules.clear(); secretValues.clear(); grants.clear(); browserGestures = 0
    } }
}
