package org.amalia.platform.runtime

import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import org.amalia.platform.protocol.*
import kotlinx.serialization.json.JsonElement

/** Bounded in-memory log, one ordering scope per instance. No silent cursor loss. */
class ReplayEvents(
    private val topic: EventDescriptor,
    val orderingScope: String,
    val capacity: Int = 128,
) : AutoCloseable {
    init { require(orderingScope.isNotBlank() && capacity in 1..65536) }
    private val lock = Any()
    private val epoch = UUID.randomUUID().toString()
    private val entries = ArrayDeque<StreamMessage.Event>()
    private var sequence = 0L
    private var closed = false
    private val changed = MutableStateFlow(0L)

    fun publish(payload: JsonElement): StreamMessage.Event = synchronized(lock) {
        check(!closed) { "Event log closed" }
        require(topic.payload.validate(payload).isEmpty()) { "Event violates schema" }
        check(sequence < Long.MAX_VALUE - 1) { "Event sequence exhausted" }
        val next = ++sequence
        val event = StreamMessage.Event(UUID.randomUUID().toString(), topic.id, payload, orderingScope, next, "$epoch:$next")
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(event)
        changed.value++
        event
    }

    /** Null cursor replays retained history. A stale/foreign/future cursor emits Closed with explicit gap details. */
    fun subscribe(cursor: String? = null): Flow<StreamMessage> = flow {
        var after = synchronized(lock) {
            if (cursor == null) (entries.peekFirst()?.sequence ?: (sequence + 1)) - 1
            else cursor.takeIf { it.startsWith("$epoch:") }?.substringAfterLast(':')?.toLongOrNull() ?: -1L
        }
        while (true) {
            val batch = synchronized(lock) {
                val oldest = entries.peekFirst()?.sequence ?: (sequence + 1)
                Batch(entries.filter { it.sequence > after }, changed.value, closed,
                    after < oldest - 1 || after > sequence, oldest, sequence)
            }
            if (batch.gap) {
                emit(StreamMessage.Closed(ProtocolError(
                    "runtime.replay_gap", LocalizedText("runtime.replay_gap", "Event history is unavailable; obtain a new snapshot"),
                    RetryAdvice.AFTER_INTERVENTION,
                    kotlinx.serialization.json.buildJsonObject {
                        put("oldestCursor", kotlinx.serialization.json.JsonPrimitive("$epoch:${batch.oldest - 1}"))
                        put("latestCursor", kotlinx.serialization.json.JsonPrimitive("$epoch:${batch.latest}"))
                    },
                )))
                return@flow
            }
            for (event in batch.events) { emit(event); after = event.sequence }
            if (batch.closed) { emit(StreamMessage.Closed()); return@flow }
            changed.first { it != batch.revision }
        }
    }

    /** This adapter rejects filters rather than pretending to implement their contract semantics. */
    fun handler(): StreamHandler = object : StreamHandler {
        override fun subscribe(request: SubscriptionRequest, context: InvocationContext): Flow<StreamMessage> = flow {
            require(request.kind == SubscriptionKind.EVENTS && request.topicId == topic.id)
            require(request.filter == kotlinx.serialization.json.JsonNull) { "Filters require a contract-specific adapter" }
            subscribe(request.cursor).collect { context.ensureActive(); emit(it) }
        }
    }

    override fun close() = synchronized(lock) { if (!closed) { closed = true; changed.value++ } }
    private data class Batch(val events: List<StreamMessage.Event>, val revision: Long, val closed: Boolean, val gap: Boolean, val oldest: Long, val latest: Long)
}
