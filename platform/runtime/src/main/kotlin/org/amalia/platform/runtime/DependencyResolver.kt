package org.amalia.platform.runtime

import org.amalia.platform.protocol.*

/** Host-verified candidates only. The resolver never authenticates metadata or executes code. */
data class ActivationCandidate(
    val packageId: String,
    val providers: List<ProviderDescriptor>,
    val requirements: List<ContractRequirement> = emptyList(),
) {
    init {
        require(packageId.isNotBlank())
        require(providers.all { it.packageId == packageId })
        require(requirements.map { it.id }.distinct().size == requirements.size)
    }
}

data class ContractRequirement(
    val id: String,
    val query: ProviderQuery,
    val optional: Boolean = false,
) { init { require(id.isNotBlank()) } }

data class ResolvedBinding(
    val consumerPackageId: String,
    val requirementId: String,
    val provider: ProviderDescriptor,
    val contractVersion: Version,
)

data class ResolutionIssue(
    val packageId: String,
    val requirementId: String?,
    val code: String,
    val candidates: List<String> = emptyList(),
)

data class ActivationPlan(
    val orderedPackages: List<String>,
    val bindings: List<ResolvedBinding>,
    val optionalUnavailable: List<ResolutionIssue>,
    val blocking: List<ResolutionIssue>,
) { val canActivate: Boolean get() = blocking.isEmpty() }

/** Deterministic planning with exact profile choices, no arbitrary provider fallback. */
object DependencyResolver {
    fun resolve(candidates: List<ActivationCandidate>, profile: SelectionProfile = SelectionProfile()): ActivationPlan {
        require(candidates.size <= 1024) { "Too many packages" }
        require(candidates.map { it.packageId }.distinct().size == candidates.size) { "Duplicate package ID" }
        val providers = candidates.flatMap { it.providers }
        require(providers.size <= 8192 && providers.map { it.id }.distinct().size == providers.size) { "Invalid provider inventory" }
        require(candidates.sumOf { it.requirements.size } <= 8192) { "Too many requirements" }
        val bindings = mutableListOf<ResolvedBinding>()
        val optional = mutableListOf<ResolutionIssue>()
        val blocking = mutableListOf<ResolutionIssue>()
        val edges = candidates.associate { it.packageId to sortedSetOf<String>() }
        for (candidate in candidates.sortedBy { it.packageId }) {
            for (requirement in candidate.requirements.sortedBy { it.id }) {
                val query = requirement.query
                val selected = query.providerId ?: profile.bindings[query.contractId]
                val matches = providers.filter { it.supports(query) && (selected == null || it.id == selected) }.sortedBy { it.id }
                if (matches.size != 1) {
                    val issue = ResolutionIssue(candidate.packageId, requirement.id,
                        if (matches.isEmpty()) "dependency.unavailable" else "dependency.ambiguous", matches.map { it.id })
                    if (requirement.optional) optional += issue else blocking += issue
                    continue
                }
                val provider = matches.single()
                val version = provider.contracts.filter {
                    it.contractId == query.contractId && it.version in query.versions && it.features.containsAll(query.requiredFeatures)
                }.maxOf { it.version }
                bindings += ResolvedBinding(candidate.packageId, requirement.id, provider, version)
                if (!requirement.optional && provider.packageId != candidate.packageId) {
                    edges.getValue(candidate.packageId).add(provider.packageId)
                }
            }
        }
        val remaining = edges.mapValues { it.value.toMutableSet() }.toMutableMap()
        val order = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filterValues { it.isEmpty() }.keys.sorted()
            if (ready.isEmpty()) {
                remaining.keys.sorted().forEach { blocking += ResolutionIssue(it, null, "dependency.start_cycle", remaining.getValue(it).sorted()) }
                break
            }
            order.addAll(ready)
            ready.forEach(remaining::remove)
            remaining.values.forEach { it.removeAll(ready.toSet()) }
        }
        // A partial order must never be mistaken for permission to start unaffected packages.
        return ActivationPlan(if (blocking.isEmpty()) order else emptyList(), bindings, optional, blocking)
    }
}
