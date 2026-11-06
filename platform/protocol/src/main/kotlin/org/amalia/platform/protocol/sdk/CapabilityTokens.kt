package org.amalia.platform.protocol.sdk

/** Signed review tokens. Syntax is deliberately closed; new dangerous powers require SDK review. */
object CapabilityTokens {
    val simple = setOf("background.media", "background.short", "notifications", "scheduler", "secrets",
        "browser", "device.read", "device.volume", "device.vibrate", "ui")
    fun validate(token: String) {
        if (token.startsWith("network:")) requireNetworkDomain(token.removePrefix("network:"))
        else require(token in simple) { "Unknown capability: $token" }
    }
    fun from(capabilities: Set<Capability>): Set<String> = capabilities.flatMap { capability ->
        when (capability) {
            is Capability.Network -> capability.domains.map { "network:$it" }
            is Capability.Host -> listOf(capability.token)
        }
    }.toSet()
}
