package org.example.counter

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.amalia.platform.protocol.*
import org.amalia.platform.runtime.CoreRuntime
import org.amalia.platform.runtime.RuntimeGrant

/** This domain is owned by an external author, not by the host or protocol SDK. */
private object CounterContract {
    const val publisher = "org.example.counter"
    const val id = "$publisher.counter"
    const val advance = "advance"
    val version = Version(1)
    val compatible = VersionRange(version, Version(2))
    val descriptor = ContractDescriptor(
        id = id,
        version = version,
        description = "An in-memory counter; each activation begins at its configured initial value.",
        compatibility = "Version 1 accepts increments from 1 through 10 and returns the new integer value.",
        operations = listOf(OperationDescriptor(
            id = advance,
            description = "Increment the counter. Calls are not deduplicated; do not retry blindly.",
            input = JsonSchema.parse("""
                {"type":"object","properties":{"amount":{"type":"integer","minimum":1,"maximum":10}},
                 "required":["amount"],"additionalProperties":false}
            """.trimIndent()),
            output = JsonSchema.parse("""
                {"type":"object","properties":{"value":{"type":"integer"}},
                 "required":["value"],"additionalProperties":false}
            """.trimIndent()),
            effect = OperationEffect.WRITE,
            idempotency = Idempotency.NONE,
        )),
    )
}

/** Only the contract publisher registers the descriptor; it need not provide an implementation. */
private class ContractPublisher : PluginEntrypoint {
    private var registration: Registration? = null
    override suspend fun start(context: PluginContext) {
        registration = context.registerContract(CounterContract.descriptor)
    }
    override suspend fun stop() {
        registration?.close()
        registration = null
    }
}

/** An independently owned provider implements the author's contract using only public protocol types. */
private class CounterProvider(private val initialValue: Int) : PluginEntrypoint {
    private var registration: Registration? = null
    var calls = 0
        private set
    var stopped = false
        private set

    override suspend fun start(context: PluginContext) {
        var value = BigDecimal(initialValue)
        val valueLock = Any()
        val descriptor = ProviderDescriptor(
            id = PROVIDER_ID,
            publisherId = context.identity.publisherId,
            packageId = context.identity.packageId,
            packageVersion = Version(1),
            deviceId = context.identity.deviceId,
            activationGeneration = context.activationGeneration,
            displayName = "External counter starting at $initialValue",
            contracts = listOf(ContractOffer(CounterContract.id, CounterContract.version)),
        )
        registration = context.registerProvider(descriptor, object : OperationHandler {
            override suspend fun invoke(request: InvocationRequest, context: InvocationContext): InvocationResult {
                context.ensureActive()
                // Authority comes from the host context, never from JSON input or an ID in the request.
                require(context.caller.packageId == CONSUMER_PACKAGE)
                require(context.effectiveGrants.isNotEmpty())
                // The schema accepts mathematically integral JSON such as 1.0 as well as 1.
                val amount = BigDecimal(request.input.jsonObject.getValue("amount").jsonPrimitive.content)
                return synchronized(valueLock) {
                    value += amount
                    calls++
                    InvocationResult.Success(buildJsonObject { put("value", JsonPrimitive(value)) })
                }
            }
        })
    }

    override suspend fun stop() {
        registration?.close()
        registration = null
        stopped = true
    }
}

/** No CoreRuntime or RuntimeGrant reaches this plugin: it gets an activation-bound client. */
private class CounterConsumer : PluginEntrypoint {
    private var boundClient: ProtocolClient? = null
    val client: ProtocolClient get() = requireNotNull(boundClient)
    private var sequence = 0

    override suspend fun start(context: PluginContext) { boundClient = context.client }
    override suspend fun stop() { boundClient = null }

    suspend fun discover(versions: VersionRange = CounterContract.compatible): List<ProviderDescriptor> =
        client.discover(ProviderQuery(CounterContract.id, versions, providerId = PROVIDER_ID)).available

    suspend fun advance(input: JsonElement = buildJsonObject { put("amount", 2) },
                        version: Version = CounterContract.version): InvocationResult {
        val request = InvocationRequest(
            requestId = "counter-${++sequence}",
            contractId = CounterContract.id,
            contractVersion = version,
            providerId = PROVIDER_ID,
            operationId = CounterContract.advance,
            input = input,
        )
        val response = client.invoke(request)
        require(response.requestId == request.requestId)
        return response.result
    }
}

private const val DEVICE = "example-jvm"
private const val PROVIDER_ID = "org.example.implementation.counter"
private const val CONSUMER_PACKAGE = "org.example.reader.client"

private fun identity(publisher: String, packageId: String) =
    CallerIdentity(publisher, packageId, "$packageId.instance", DEVICE)

private fun expectFailure(result: InvocationResult, code: String) {
    require(result is InvocationResult.Failure && result.error.code == code) {
        "Expected $code, received $result"
    }
    println("Verified $code")
}

private fun expectValue(result: InvocationResult, expected: Int) {
    require(result is InvocationResult.Success) { "Expected success, received $result" }
    val value = BigDecimal(result.output.jsonObject.getValue("value").jsonPrimitive.content)
    require(value.compareTo(BigDecimal(expected)) == 0) { "Expected $expected, received $value" }
    println("Counter value: $value")
}

/** Trusted host wiring: in a real host, identities come from verified installation metadata. */
fun main() = runBlocking {
    val resourceRoot = java.nio.file.Files.createTempDirectory("amalia-resource-example-")
    try {
        org.amalia.platform.runtime.resources.FileResourceStore(resourceRoot).use { store ->
            val owner = identity("org.example.reader", CONSUMER_PACKAGE)
            val other = identity("org.example.outsider", "org.example.outsider.client")
            val access = store.accessFor(owner)
            val data = "resource round trip".toByteArray()
            val ref = access.write(ResourceWriteRequest("text/plain", data.size.toLong()), kotlinx.coroutines.flow.flowOf(data))
            val restored = java.io.ByteArrayOutputStream()
            access.read(ref).collect { restored.write(it) }
            require(restored.toByteArray().contentEquals(data))
            var denied = false
            try { store.accessFor(other).read(ref).collect { } }
            catch (_: org.amalia.platform.runtime.resources.ResourceStoreException) { denied = true }
            require(denied) { "Resource reference must not confer authority" }
            access.delete(ref)
            println("Resource round trip and caller isolation passed.")
        }
    } finally {
        java.nio.file.Files.walk(resourceRoot).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) }
        }
    }
    CoreRuntime(DEVICE).use { host ->
        val publisher = host.activate(
            identity(CounterContract.publisher, "org.example.counter.contract"), ContractPublisher(),
        )
        val providerIdentity = identity("org.example.implementation", "org.example.implementation.counter")
        val firstPlugin = CounterProvider(initialValue = 0)
        val first = host.activate(providerIdentity, firstPlugin)
        val consumerIdentity = identity("org.example.reader", CONSUMER_PACKAGE)
        val consumer = CounterConsumer()
        val consumerActivation = host.activate(consumerIdentity, consumer)
        var replacement: CoreRuntime.Activation? = null
        try {
            val original = consumer.discover().single()
            require(original.activationGeneration == first.generation)
            // Discovery is not invocation authority. No grant yet, so no provider dispatch.
            expectFailure(consumer.advance(), ErrorCodes.ACCESS_DENIED)
            require(firstPlugin.calls == 0)

            val grantId = "host.counter.advance"
            host.grant(RuntimeGrant(
                id = grantId,
                caller = consumerIdentity,
                contractId = CounterContract.id,
                operations = setOf(CounterContract.advance),
                providerId = PROVIDER_ID,
                expiresAtEpochMillis = System.currentTimeMillis() + 300_000,
            ))
            expectValue(consumer.advance(), 2)
            expectFailure(consumer.advance(buildJsonObject { put("amount", "two") }), ErrorCodes.INVALID_INPUT)
            expectFailure(consumer.advance(version = Version(2)), ErrorCodes.UNSUPPORTED_VERSION)
            require(consumer.discover(VersionRange(Version(2), Version(3))).isEmpty())
            require(firstPlugin.calls == 1) { "Rejected calls must not reach the handler" }

            // A different caller does not inherit this consumer's host-issued grant.
            val outsider = host.clientFor(identity("org.example.outsider", "org.example.outsider.client"))
            expectFailure(outsider.invoke(InvocationRequest(
                requestId = "outsider", contractId = CounterContract.id,
                contractVersion = CounterContract.version, providerId = PROVIDER_ID,
                operationId = CounterContract.advance, input = buildJsonObject { put("amount", 2) },
            )).result, ErrorCodes.ACCESS_DENIED)

            // close() fences the old generation immediately, even before the stop hook runs.
            first.close()
            require(consumer.discover().isEmpty())
            expectFailure(consumer.advance(), ErrorCodes.PROVIDER_UNAVAILABLE)
            first.stop()
            require(firstPlugin.stopped)

            val secondPlugin = CounterProvider(initialValue = 100)
            val second = host.activate(providerIdentity, secondPlugin)
            replacement = second
            val updated = consumer.discover().single()
            require(updated.id == original.id && updated.activationGeneration > original.activationGeneration)
            // Same consumer, contract, provider ID and grant; a new activation supplies the output.
            expectValue(consumer.advance(), 102)
            require(firstPlugin.calls == 1 && secondPlugin.calls == 1)

            host.revoke(grantId)
            expectFailure(consumer.advance(), ErrorCodes.ACCESS_DENIED)
            require(secondPlugin.calls == 1)
            println("All external contract, authority, schema, version and replacement checks passed.")
        } finally {
            // stop() also closes host-owned registrations. Host shutdown is the final cleanup guard.
            consumerActivation.stop()
            replacement?.stop()
            if (!firstPlugin.stopped) first.stop()
            publisher.stop()
        }
    }
}
