package tech.amalia.platform.runtime.tasks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class TaskState { CREATED, READY, RUNNING, WAITING_INPUT, WAITING_DEPENDENCY, PAUSED, RECONCILING, SUCCEEDED, FAILED, CANCELLED }

@Serializable
enum class EffectState { PREPARED, DISPATCHED, CONFIRMED, REJECTED, UNKNOWN }

@Serializable
data class ProviderBinding(val id: String, val generation: Long)

@Serializable
data class TaskLease(val owner: String, val token: Long, val expiresAtMillis: Long, val providerGeneration: Long)

@Serializable
data class QuestionAnswer(
    val key: String,
    val author: String,
    val channel: String,
    val receivedAtMillis: Long,
    val value: JsonObject,
    val consentEvidence: JsonObject,
)

@Serializable
data class QuestionRecord(
    val id: String,
    val step: String,
    val version: Long,
    val taskRevision: Long,
    val recipient: String,
    val schema: JsonObject,
    val expiresAtMillis: Long?,
    val answer: QuestionAnswer? = null,
)

@Serializable
data class EffectRecord(
    val id: String,
    val step: String,
    val operation: String,
    val recipient: String,
    val requestHash: String,
    val idempotencyKey: String,
    val providerGeneration: Long,
    val state: EffectState = EffectState.PREPARED,
    val receipt: JsonObject? = null,
    val evidence: JsonObject? = null,
)

@Serializable
data class TaskRecord(
    val id: String,
    val owner: String,
    val scope: String,
    val initiator: String,
    val correlation: String,
    val definitionVersion: String,
    val executorPackageHash: String,
    val contractVersion: String,
    val provider: ProviderBinding,
    val input: JsonObject,
    val state: TaskState = TaskState.CREATED,
    val revision: Long = 0,
    val checkpoint: JsonObject = JsonObject(emptyMap()),
    val fence: Long = 0,
    val lease: TaskLease? = null,
    val questions: List<QuestionRecord> = emptyList(),
    val effects: List<EffectRecord> = emptyList(),
    val completionEvidence: JsonObject? = null,
)

/** Fixed messages intentionally exclude caller payloads, identifiers and underlying exception text. */
class WorkflowStoreException(val code: Code) : RuntimeException(code.name) {
    enum class Code {
        INVALID_INPUT, LIMIT_EXCEEDED, NOT_FOUND, ALREADY_EXISTS, REVISION_CONFLICT,
        INVALID_STATE, LEASE_CONFLICT, STALE_LEASE, GENERATION_CONFLICT,
        QUESTION_CLOSED, QUESTION_EXPIRED, INVALID_ANSWER, VALIDATOR_FAILED,
        EFFECT_CONFLICT, EVIDENCE_REQUIRED, CORRUPT_STORE, IO_FAILURE,
        STORE_CLOSED, STORE_ALREADY_OPEN, REENTRANT_CALL,
    }
}

/** All limits apply to retained data; nothing is silently pruned or retried. */
data class WorkflowStoreLimits(
    val maxTasks: Int = 1_000,
    val maxQuestionsPerTask: Int = 128,
    val maxEffectsPerTask: Int = 512,
    val maxIdentifierChars: Int = 512,
    val maxJsonBytes: Int = 262_144,
    val maxJsonDepth: Int = 32,
    val maxJsonNodes: Int = 16_384,
    val maxRecordBytes: Int = 2_097_152,
    val maxStoreBytes: Long = 67_108_864,
    val maxJournalBytes: Long = 536_870_912,
    val maxJournalEntries: Int = 100_000,
    val maxLeaseMillis: Long = 300_000,
)

/** Implemented by the caller's schema engine. No protocol or domain dependency is required. */
fun interface QuestionAnswerValidator {
    fun isValid(schema: JsonObject, answer: JsonObject): Boolean
}
