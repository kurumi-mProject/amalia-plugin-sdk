package org.amalia.platform.protocol.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.amalia.platform.protocol.*

@Serializable
data class StudioProject(
    val projectId: String,
    val revision: Long,
    val document: UiDocument,
    val dependencies: List<StudioDependency> = emptyList(),
    val requestedCapabilities: Set<String> = emptySet(),
) {
    init {
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}").matches(projectId))
        require(revision >= 0 && dependencies.size <= 64 && requestedCapabilities.size <= 64)
    }
}

@Serializable
data class StudioDependency(val id: String, val version: Version, val sha256: String) {
    init { require(id.isNotBlank() && id.length <= 128 && Regex("[a-f0-9]{64}").matches(sha256)) }
}

/** Immutable, content-addressed source. Host persistence must use compare-and-set revision. */
@Serializable
data class StudioSource(val resource: ResourceRef, val sha256: String) {
    init { require(Regex("[a-f0-9]{64}").matches(sha256)) }
}

@Serializable
data class BuildRequest(
    val projectId: String,
    val revision: Long,
    val source: StudioSource,
    val targetRuntime: String,
    val toolchain: String,
    val dependencies: List<StudioDependency>,
    val allowNetwork: Boolean = false,
    val maxDurationMillis: Long = 120_000,
    val maxOutputBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(projectId.isNotBlank() && revision >= 0)
        require(targetRuntime.isNotBlank() && toolchain.isNotBlank() && toolchain.length <= 128)
        require(dependencies.size <= 64 && maxDurationMillis in 1..600_000 && maxOutputBytes in 1..(64L * 1024 * 1024))
    }
}

@Serializable
data class BuildArtifact(
    val requestId: String,
    val sourceSha256: String,
    val archive: ResourceRef,
    val archiveSha256: String,
    val attestation: ResourceRef,
    val diagnostics: ResourceRef? = null,
) {
    init {
        require(requestId.isNotBlank())
        require(listOf(sourceSha256, archiveSha256).all { Regex("[a-f0-9]{64}").matches(it) })
        val size = archive.sizeBytes
        require(size != null && size in 1..(64L * 1024 * 1024))
        require(ResourceRight.READ in archive.rights && ResourceRight.READ in attestation.rights)
    }
}

/** Public build contract. Host-local declarative and external builders use the same wire schema. */
object StudioBuildContract {
    const val ID = "org.amalia.build"
    const val BUILD = "build"
    val version = Version(1)
    private val json = Json { encodeDefaults = true }
    val descriptor = ContractDescriptor(
        id = ID, version = version, description = "Build a pinned source into a verifiable staged package",
        operations = listOf(OperationDescriptor(
            id = BUILD, description = "Build within explicit resource and network limits",
            input = JsonSchema.parse("""{"type":"object","required":["projectId","revision","source","targetRuntime","toolchain","dependencies","allowNetwork","maxDurationMillis","maxOutputBytes"],"additionalProperties":false,"properties":{"projectId":{"type":"string","maxLength":128},"revision":{"type":"integer","minimum":0},"source":{"type":"object"},"targetRuntime":{"type":"string","maxLength":128},"toolchain":{"type":"string","maxLength":128},"dependencies":{"type":"array","maxItems":64},"allowNetwork":{"type":"boolean"},"maxDurationMillis":{"type":"integer","minimum":1,"maximum":600000},"maxOutputBytes":{"type":"integer","minimum":1,"maximum":67108864}}}"""),
            output = JsonSchema.parse("""{"type":"object","required":["requestId","sourceSha256","archive","archiveSha256","attestation"],"additionalProperties":false,"properties":{"requestId":{"type":"string","maxLength":128},"sourceSha256":{"type":"string","minLength":64,"maxLength":64},"archive":{"type":"object"},"archiveSha256":{"type":"string","minLength":64,"maxLength":64},"attestation":{"type":"object"},"diagnostics":{"type":["object","null"]}}}"""),
            effect = OperationEffect.WRITE, execution = ExecutionKind.TASK, cancellable = true,
        )),
    )
    fun input(request: BuildRequest): JsonElement = json.encodeToJsonElement(BuildRequest.serializer(), request)
    fun artifact(output: JsonElement): BuildArtifact {
        require(output.toString().length <= 32 * 1024) { "build.output_limit" }
        require(descriptor.operations.single().output.validate(output).isEmpty()) { "build.output_schema" }
        return json.decodeFromJsonElement(BuildArtifact.serializer(), output)
    }
}
