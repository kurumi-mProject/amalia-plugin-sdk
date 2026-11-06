package org.amalia.platform.protocol

/** Host-owned HTTP transport. Declaring a capability does not authorize a request.
 * Hosts recheck approval on every call, bound response sizes and do not follow redirects implicitly.
 * Not an isolation boundary for trusted in-process code.
 */
interface NetworkAccess {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): NetworkResponse
}

data class NetworkResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)

object DeniedNetworkAccess : NetworkAccess {
    override suspend fun get(url: String, headers: Map<String, String>): NetworkResponse =
        throw SecurityException("Network access has not been approved by the host")
}
