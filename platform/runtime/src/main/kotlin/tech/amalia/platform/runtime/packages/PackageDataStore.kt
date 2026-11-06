package tech.amalia.platform.runtime.packages

import java.io.*
import java.nio.file.Path

/**
 * Bounded host-mediated KV namespaces (UTF-8 values), never arbitrary files or SharedPreferences.
 * One exclusive host owner; plugins must receive an owner-bound facade, never this administration.
 * Each atomic image contains data AND settings, rollback snapshot AND transaction intent.
 * Maximum 1024 keys/namespace, 16 KiB/value, 2 MiB combined live payload, one retained snapshot.
 */
class PackageDataStore(root: Path) {
    private val storage = PackageStateStorage(root)
    private data class Image(val schema: String?, val data: Map<String, String>, val settings: Map<String, String>)
    private data class Snapshot(val stageId: String, val image: Image)
    private data class Pending(val revision: Long, val targetStage: String, val before: Image, val previous: Snapshot?)
    private data class State(val image: Image, val snapshot: Snapshot?, val pending: Pending?)
    private val empty = Image(null, emptyMap(), emptyMap())

    /** Owner-bound facade. Host authorizer must check both operation grants and a current generation
     * lease, including during startup/health (coordinator.live is deliberately still empty then).
     * Revoke that lease before stop completes. Never authorize from claimed publisher alone.
     */
    fun namespace(packageId: String, namespace: String, authorize: (write: Boolean) -> Boolean): PackageNamespace {
        name(packageId)
        require(namespace in setOf("data", "settings"))
        return PackageNamespace(
            { key -> synchronized(this) {
                check(authorize(false)) { "Namespace access denied or generation revoked" }
                read(packageId, namespace, key)
            } },
            { key, value -> synchronized(this) {
                check(authorize(true)) { "Namespace access denied or generation revoked" }
                write(packageId, namespace, key, value)
            } },
        )
    }

    @Synchronized fun read(packageId: String, namespace: String, key: String): String? {
        validStateKey(key)
        return entriesFor(load(packageId).image, namespace)[key]
    }
    /** Caller must enforce fresh generation/owner authorization before every write. */
    @Synchronized fun write(packageId: String, namespace: String, key: String, value: String?) {
        validStateKey(key)
        require(value == null || value.toByteArray(Charsets.UTF_8).size <= 16384)
        val state = load(packageId)
        val entries = entriesFor(state.image, namespace).toMutableMap()
        if (value == null) entries.remove(key) else entries[key] = value
        val image = if (namespace == "data") state.image.copy(data = entries) else state.image.copy(settings = entries)
        validate(image)
        save(packageId, state.copy(image = image))
    }

    /** Must run with no package generation live, before any explicit reload. Does not execute code. */
    @Synchronized fun recover(packageId: String, journal: PackageActivation?) {
        val state = load(packageId)
        val pending = state.pending ?: return
        // Only an actual fenced confirmation may retain the migrated image. Failed/recovered begin
        // retains old.active, and therefore cannot masquerade as confirmation, including same-stage.
        val confirmed = journal != null && journal.revision >= pending.revision + 2 &&
            journal.active?.stageId == pending.targetStage && journal.pending == null
        save(packageId, if (confirmed) state.copy(pending = null)
            else State(pending.before, pending.previous, null))
    }

    @Synchronized internal fun preflight(packageId: String, declaration: PackageDataDeclaration?, rollbackStage: String? = null) {
        val state = load(packageId)
        check(state.pending == null) { "Recover package data before review" }
        if (rollbackStage != null) {
            val snapshot = state.snapshot ?: error("No retained data snapshot")
            check(snapshot.stageId == rollbackStage) { "Snapshot does not match rollback stage" }
            checkReadable(snapshot.image, declaration)
        } else migrate(state.image, declaration)
    }

    /** Called only after old generation withdrawal, before loading. Intent and image publish together. */
    @Synchronized internal fun begin(
        packageId: String, before: PackageActivation?, target: ActivationStageRef,
        declaration: PackageDataDeclaration?, rollbackStage: String? = null,
    ) {
        val state = load(packageId)
        check((before?.revision ?: 0) <= Long.MAX_VALUE - 2) { "Data transaction revision exhausted" }
        check(state.pending == null) { "Unresolved data transaction" }
        val next = if (rollbackStage == null) migrate(state.image, declaration) else {
            val snapshot = state.snapshot ?: error("No retained data snapshot")
            check(snapshot.stageId == rollbackStage)
            checkReadable(snapshot.image, declaration)
            snapshot.image
        }
        val snapshot = if (before?.active != null && before.active.stageId != target.stageId)
            Snapshot(before.active.stageId, state.image) else state.snapshot
        save(packageId, State(next, snapshot, Pending(before?.revision ?: 0, target.stageId, state.image, state.snapshot)))
    }
    @Synchronized internal fun commit(packageId: String) {
        val state = load(packageId); check(state.pending != null)
        save(packageId, state.copy(pending = null))
    }

    private fun migrate(original: Image, declaration: PackageDataDeclaration?): Image {
        if (declaration == null) {
            check(original.schema == null) { "Data declaration required for existing versioned data" }
            return original
        }
        if (original.schema == null) {
            check(original.data.isEmpty() && original.settings.isEmpty()) { "Unversioned data cannot be silently adopted" }
            return original.copy(schema = declaration.schema)
        }
        if (original.schema == declaration.schema) { checkReadable(original, declaration); return original }
        var image = original
        val visited = mutableSetOf<String>()
        repeat(128) {
            if (image.schema == declaration.schema) { checkReadable(image, declaration); return image }
            val schema = requireNotNull(image.schema)
            check(visited.add(schema)) { "Migration cycle" }
            val migration = declaration.migrations.singleOrNull { it.from == schema }
            if (migration == null) {
                // Reading an old schema alone does not rewrite its version or imply a migration.
                checkReadable(image, declaration)
                return image
            }
            migration.operations.forEach { operation ->
                val entries = entriesFor(image, operation.namespace).toMutableMap()
                when (operation.operation) {
                    "set" -> entries[operation.key] = requireNotNull(operation.value)
                    "delete" -> { check(entries.remove(operation.key) != null) { "Migration source missing" } }
                    "rename", "copy" -> {
                        val destination = requireNotNull(operation.destination)
                        check(destination !in entries) { "Migration destination exists" }
                        entries[destination] = entries[operation.key] ?: error("Migration source missing")
                        if (operation.operation == "rename") entries.remove(operation.key)
                    }
                    else -> error("Unsupported migration")
                }
                image = if (operation.namespace == "data") image.copy(data = entries) else image.copy(settings = entries)
                validate(image)
            }
            image = image.copy(schema = migration.to)
        }
        if (image.schema == declaration.schema) { checkReadable(image, declaration); return image }
        error("Migration chain exceeds limit")
    }
    private fun checkReadable(image: Image, declaration: PackageDataDeclaration?) {
        check(if (declaration == null) image.schema == null else image.schema in declaration.readableSchemas) {
            "Package cannot read data snapshot schema"
        }
    }
    private fun entriesFor(image: Image, namespace: String): Map<String, String> = when (namespace) {
        "data" -> image.data
        "settings" -> image.settings
        else -> error("Unknown package namespace")
    }
    private fun validate(image: Image) {
        check(image.data.size <= 1024 && image.settings.size <= 1024) { "Namespace key limit exceeded" }
        var total = 0L
        (image.data.entries.toList() + image.settings.entries.toList()).forEach { (key, value) ->
            validStateKey(key)
            val bytes = value.toByteArray(Charsets.UTF_8).size
            check(bytes <= 16384) { "Namespace value limit exceeded" }
            total += key.length + bytes
        }
        check(total <= 2 * 1024 * 1024) { "Package data quota exceeded" }
    }
    private fun name(packageId: String): String {
        require(packageId.length <= 200 && Regex("[a-z0-9]+(?:[.-][a-z0-9]+)+").matches(packageId))
        return "$packageId.data"
    }
    private fun load(packageId: String): State {
        val bytes = storage.read(name(packageId)) ?: return State(empty, null, null)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == 1) { "Unknown package data format" }
            val image = readImage(input)
            val snapshot = readSnapshot(input)
            val pending = if (boolean(input)) {
                val revision = input.readLong(); check(revision in 0..Long.MAX_VALUE - 2)
                val stage = input.readUTF(); stageId(stage)
                Pending(revision, stage, readImage(input), readSnapshot(input))
            } else null
            check(input.available() == 0)
            State(image, snapshot, pending)
        }
    }
    private fun save(packageId: String, state: State) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(1); writeImage(output, state.image); writeSnapshot(output, state.snapshot)
            output.writeBoolean(state.pending != null)
            state.pending?.let {
                output.writeLong(it.revision); output.writeUTF(it.targetStage)
                writeImage(output, it.before); writeSnapshot(output, it.previous)
            }
        }
        storage.write(name(packageId), bytes.toByteArray())
    }
    private fun readImage(input: DataInputStream): Image {
        val schema = input.readUTF().ifEmpty { null }; schema?.let(::validStateKey)
        fun entries(): Map<String, String> {
            val count = input.readInt(); check(count in 0..1024)
            val map = linkedMapOf<String, String>()
            repeat(count) { check(map.put(input.readUTF(), input.readUTF()) == null) { "Duplicate namespace key" } }
            return map
        }
        return Image(schema, entries(), entries()).also(::validate)
    }
    private fun writeImage(output: DataOutputStream, image: Image) {
        validate(image); output.writeUTF(image.schema ?: "")
        listOf(image.data, image.settings).forEach { map ->
            output.writeInt(map.size)
            map.toSortedMap().forEach { (key, value) -> output.writeUTF(key); output.writeUTF(value) }
        }
    }
    private fun readSnapshot(input: DataInputStream): Snapshot? = if (boolean(input)) {
        val stage = input.readUTF(); stageId(stage); Snapshot(stage, readImage(input))
    } else null
    private fun writeSnapshot(output: DataOutputStream, snapshot: Snapshot?) {
        output.writeBoolean(snapshot != null)
        snapshot?.let { output.writeUTF(it.stageId); writeImage(output, it.image) }
    }
    private fun boolean(input: DataInputStream): Boolean = when (input.readUnsignedByte()) {
        0 -> false; 1 -> true; else -> error("Invalid state boolean")
    }
    private fun stageId(stage: String) {
        check(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(stage))
    }
}

/** Contains no filesystem paths or administration; host-created authority is checked on every call. */
class PackageNamespace internal constructor(
    private val reader: (String) -> String?,
    private val writer: (String, String?) -> Unit,
) : org.amalia.platform.protocol.StorageNamespace {
    override fun read(key: String): String? = reader(key)
    override fun write(key: String, value: String) = writer(key, value)
    override fun delete(key: String) = writer(key, null)
}
