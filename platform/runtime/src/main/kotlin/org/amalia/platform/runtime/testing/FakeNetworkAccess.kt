package org.amalia.platform.runtime.testing

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.amalia.platform.protocol.NetworkAccess
import org.amalia.platform.protocol.NetworkResponse
import org.amalia.platform.protocol.sdk.Capability

/** No real IO, redirects, or implicit grants. Fixtures and domain approvals are host administration. */
class FakeNetworkAccess(
    grantedDomains: Set<String> = emptySet(),
    private val maxFixtures: Int = 64,
    private val maxResponseBytes: Int = 1024 * 1024,
    private val maxHeaderBytes: Int = 16 * 1024,
) : NetworkAccess {
    init { require(maxFixtures > 0 && maxResponseBytes > 0 && maxHeaderBytes > 0) }
    private val lock = Any()
    private val grants = mutableSetOf<String>()
    private val responses = mutableMapOf<String, NetworkResponse>()
    init { grantedDomains.forEach(::grant) }

    fun grant(domain: String) {
        Capability.Network(setOf(domain)) // Same strict DNS syntax as declarations, without granting declarations.
        synchronized(lock) { require(grants.size < 128 || domain in grants); grants += domain }
    }
    fun revoke(domain: String) { synchronized(lock) { grants -= domain } }
    private fun domain(url: String): String {
        require(url.length <= 8192) { "URL exceeds limit" }
        val uri = URI(url)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.fragment == null && uri.port in setOf(-1, 443)) {
            "Fake network only supports HTTPS without credentials or fragments"
        }
        return requireNotNull(uri.host).lowercase(Locale.ROOT)
    }
    fun respond(url: String, response: NetworkResponse) = synchronized(lock) {
        domain(url)
        require(response.status in 100..599)
        require(response.body.size <= maxResponseBytes) { "Response exceeds limit" }
        require(response.headers.entries.sumOf { entry ->
            entry.key.toByteArray().size.toLong() + entry.value.sumOf { it.toByteArray().size.toLong() }
        } <= maxHeaderBytes) { "Response headers exceed limit" }
        require(url in responses || responses.size < maxFixtures) { "Fixture limit exceeded" }
        responses[url] = copy(response)
    }
    override suspend fun get(url: String, headers: Map<String, String>): NetworkResponse {
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            val domain = domain(url)
            if (domain !in grants) throw SecurityException("Network domain has not been granted by fake host")
            require(headers.entries.sumOf { it.key.toByteArray().size.toLong() + it.value.toByteArray().size } <= maxHeaderBytes) {
                "Request headers exceed limit"
            }
            copy(checkNotNull(responses[url]) { "No fake network response configured for URL" })
        }
    }
    private fun copy(response: NetworkResponse) = response.copy(
        headers = response.headers.mapValues { it.value.toList() }, body = response.body.copyOf(),
    )
}
