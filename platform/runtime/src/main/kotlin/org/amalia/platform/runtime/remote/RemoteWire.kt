package org.amalia.platform.runtime.remote

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Fixed caps are validated before allocation; time caps are whole-operation wall deadlines. */
data class RemoteLimits(
    val maxFrameBytes: Int = 256 * 1024,
    val maxJsonDepth: Int = 32,
    val maxSessions: Int = 8,
    val maxRequestsPerSession: Int = 256,
    val connectMillis: Int = 5_000,
    val handshakeMillis: Int = 5_000,
    val requestMillis: Int = 30_000,
    val sessionMillis: Int = 300_000,
) {
    init {
        require(maxFrameBytes in 256..1_048_576 && maxJsonDepth in 1..64)
        require(maxSessions in 1..64 && maxRequestsPerSession in 1..4096)
        require(listOf(connectMillis, handshakeMillis, requestMillis, sessionMillis).all { it in 1..3_600_000 })
    }
}

/** Versioned frame: four-byte big-endian byte count, then strict UTF-8 JSON.
 * There is deliberately no remotely supplied caller/grant/session identity.
 */
internal object RemoteWire {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        encodeDefaults = true
    }
    fun <T> read(input: DataInputStream, serializer: KSerializer<T>, limits: RemoteLimits): T {
        val size = input.readInt()
        require(size in 1..limits.maxFrameBytes) { "Frame length rejected" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        checkJson(text, limits.maxJsonDepth)
        return json.decodeFromString(serializer, text)
    }
    fun <T> encode(serializer: KSerializer<T>, value: T, limits: RemoteLimits): ByteArray {
        val text = json.encodeToString(serializer, value)
        require(text.length <= limits.maxFrameBytes) { "Response too large" }
        checkJson(text, limits.maxJsonDepth)
        return text.toByteArray(Charsets.UTF_8).also { require(it.size <= limits.maxFrameBytes) }
    }
    fun write(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    // Scan before recursive serialization parsing. Reject duplicate (including escaped) keys,
    // trailing values, bad structure and excessive nesting. Serializer validates primitive syntax.
    private fun checkJson(text: String, maxDepth: Int) {
        var at = 0
        fun whitespace() { while (at < text.length && text[at] in " \r\n\t") at++ }
        fun string(): String {
            val start = at
            require(at < text.length && text[at++] == '"')
            var escaped = false
            while (at < text.length) {
                val c = text[at++]
                if (!escaped && c == '"') return json.decodeFromString(String.serializer(), text.substring(start, at))
                if (!escaped && c == '\\') escaped = true else escaped = false
            }
            error("Unterminated JSON string")
        }
        fun value(depth: Int) {
            require(depth <= maxDepth) { "JSON depth exceeded" }
            whitespace()
            require(at < text.length)
            when (text[at]) {
                '{', '[' -> {
                    val objectValue = text[at++] == '{'
                    val end = if (objectValue) '}' else ']'
                    val keys = HashSet<String>()
                    whitespace()
                    if (at < text.length && text[at] == end) { at++; return }
                    while (true) {
                        whitespace()
                        if (objectValue) {
                            require(keys.add(string())) { "Duplicate JSON key" }
                            whitespace()
                            require(at < text.length && text[at++] == ':')
                        }
                        value(depth + 1)
                        whitespace()
                        require(at < text.length)
                        val separator = text[at++]
                        if (separator == end) break
                        require(separator == ',')
                    }
                }
                '"' -> { string() }
                else -> {
                    val start = at
                    while (at < text.length && text[at] !in ",]} \r\n\t") at++
                    require(at > start)
                    val token = text.substring(start, at)
                    require(token == "true" || token == "false" || token == "null" ||
                        token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                }
            }
        }
        value(1)
        whitespace()
        require(at == text.length) { "Trailing JSON data" }
    }
}
