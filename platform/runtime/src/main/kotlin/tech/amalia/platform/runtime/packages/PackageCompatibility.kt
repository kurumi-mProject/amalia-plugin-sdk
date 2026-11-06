package tech.amalia.platform.runtime.packages

import java.util.Collections
import org.amalia.platform.protocol.*
import org.amalia.platform.runtime.*

/** Numeric ranges are explicit; the package's identity version remains an opaque token. */
data class PackageCompatibility(val protocol: VersionRange, val runtimeVersion: VersionRange, val abi: String)
data class PackageOffer(val providerId: String, val contracts: List<ContractOffer>)
data class PackageDataDeclaration(
    val schema: String,
    val readableSchemas: Set<String>,
    val migrations: List<PackageDataMigration> = emptyList(),
)
data class PackageDataMigration(val from: String, val to: String, val operations: List<PackageDataOperation>)
/** Declarative operations only; no package callbacks, classes, expressions or external IO. */
data class PackageDataOperation(
    val namespace: String, val operation: String, val key: String, val destination: String? = null,
    val value: String? = null,
)

/**
 * Trusted host input. candidates must describe ONLY verified, currently usable host/other-package
 * providers and their requirements, not catalog entries. Recomputed before each activation. It
 * must include affected consumers so removing an offer cannot silently break a dependent package.
 * These callbacks must not reenter installer/coordinator. Nothing here grants runtime access.
 */
class PackageHostPolicy(
    val protocolVersion: Version,
    runtimeVersions: Map<String, Version>,
    abiByRuntime: Map<String, String>,
    val profiles: PackageProfileStore,
    val data: PackageDataStore,
    val candidates: () -> List<ActivationCandidate>,
    val deviceId: String,
    val allowLegacyDeclarations: Boolean = false,
) {
    val runtimeVersions: Map<String, Version> = Collections.unmodifiableMap(runtimeVersions.toMap())
    val abiByRuntime: Map<String, String> = Collections.unmodifiableMap(abiByRuntime.toMap())
    init { require(deviceId.isNotBlank()) }

    internal fun preflight(manifest: PackageManifest, profile: SelectionProfile): ActivationPlan {
        val runtimeVersion = runtimeVersions[manifest.runtime] ?: error("Host has no runtime compatibility policy")
        val compatibility = manifest.compatibility
        if (compatibility == null) {
            check(manifest.isDeclarationOnly && allowLegacyDeclarations) { "Explicit compatibility metadata required" }
        } else {
            check(protocolVersion in compatibility.protocol) { "Bootstrap protocol incompatible" }
            check(runtimeVersion in compatibility.runtimeVersion) { "Runtime version incompatible" }
            check(abiByRuntime[manifest.runtime] == compatibility.abi) { "Host ABI incompatible" }
        }
        val supplied = candidates()
        check(supplied.size <= 1024 && supplied.map { it.packageId }.distinct().size == supplied.size) {
            "Invalid host dependency inventory"
        }
        val inventory = supplied.filter { it.packageId != manifest.packageId }
        check(inventory.none { it.packageId in manifest.conflicts }) { "Conflicting package selected" }
        val offers = manifest.offers.map { offer ->
            ProviderDescriptor(offer.providerId, manifest.publisher, manifest.packageId,
                Version.parse(manifest.version), deviceId, 0, offer.providerId, offer.contracts)
        }
        val plan = DependencyResolver.resolve(inventory + ActivationCandidate(manifest.packageId, offers,
            manifest.dependencies), profile)
        check(plan.canActivate) { "Dependency preflight blocked: ${plan.blocking.map { it.code }.distinct()}" }
        return plan
    }
}
