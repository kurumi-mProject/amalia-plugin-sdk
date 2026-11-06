package org.example.independent

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amalia.platform.protocol.*

/** Compile-only SDK, no app/runtime dependency. Public zero-arg entrypoint for android-dex. */
class ClockPlugin : PluginEntrypoint {
    private val started = AtomicBoolean(false)
    private val registrations = mutableListOf<Registration>()

    override suspend fun start(context: PluginContext) {
        check(started.compareAndSet(false, true))
        val contract = ContractDescriptor(
            id = "org.example.independent.clock",
            version = Version(1),
            description = "Read this executor's current wall clock.",
            compatibility = "Version 1 returns Unix epoch milliseconds; not a trusted time authority.",
            operations = listOf(OperationDescriptor(
                id = "read",
                description = "Return the current executor clock without external effects.",
                input = JsonSchema.parse("""{"type":"object","properties":{},"additionalProperties":false}"""),
                output = JsonSchema.parse("""{"type":"object","properties":{"epochMillis":{"type":"integer"}},"required":["epochMillis"],"additionalProperties":false}"""),
                effect = OperationEffect.READ,
                idempotency = Idempotency.NONE,
            )),
        )
        registrations += context.registerContract(contract)
        registrations += context.registerProvider(ProviderDescriptor(
            id = "org.example.independent.clock.local",
            publisherId = context.identity.publisherId,
            packageId = context.identity.packageId,
            packageVersion = Version(1),
            deviceId = context.identity.deviceId,
            activationGeneration = context.activationGeneration,
            displayName = "Executor clock",
            contracts = listOf(ContractOffer(contract.id, contract.version)),
        ), object : OperationHandler {
            override suspend fun invoke(request: InvocationRequest, context: InvocationContext): InvocationResult {
                context.ensureActive()
                return InvocationResult.Success(buildJsonObject { put("epochMillis", System.currentTimeMillis()) })
            }
        })
    }

    override suspend fun stop() {
        registrations.asReversed().forEach { it.close() }
        registrations.clear()
    }
}
