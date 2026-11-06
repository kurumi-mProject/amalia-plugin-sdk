package org.amalia.platform.runtime

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.amalia.platform.protocol.*

/** Trusted host administration. Give plugins only PluginContext or a bound ProtocolClient. */
class CoreRuntime(
    private val deviceId: String,
    private val limits: RuntimeLimits = RuntimeLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val resources: (CallerIdentity) -> ResourceAccess = { DeniedResourceAccess },
    /** Trusted host factory, called before start with the authenticated owner and runtime generation.
     * Runtime serializes every returned namespace operation with withdrawal, including startup.
     * Factory/namespace callbacks must not reenter runtime administration or acquire locks in reverse.
     */
    private val storage: (CallerIdentity, Long) -> PluginStorage = { _, _ -> DeniedPluginStorage },
    /** Independently authorized host transport; a plugin declaration never grants network access. */
    private val network: (CallerIdentity, Long) -> NetworkAccess = { _, _ -> DeniedNetworkAccess },
    private val media: (CallerIdentity, Long) -> MediaAccess = { _, _ -> DeniedMediaAccess },
    private val services: (CallerIdentity, Long) -> HostFeatures = { _, _ -> DeniedHostFeatures },
    private val ui: (CallerIdentity, Long) -> org.amalia.platform.protocol.sdk.PluginUiAccess = { _, _ -> org.amalia.platform.protocol.sdk.DeniedPluginUiAccess },
    /** Must return immediately; schedule potentially blocking Android cleanup outside runtime locks. */
    private val withdrawn: (CallerIdentity, Long) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val lock = Any()
    private val root = SupervisorJob()
    private val generations = AtomicLong()
    private val contracts = mutableMapOf<Pair<String, Version>, ContractDescriptor>()
    private val providers = mutableMapOf<String, Provider>()
    private val activations = mutableMapOf<CallerIdentity, Activation>()
    private val grants = mutableMapOf<String, RuntimeGrant>()
    val leases = ExclusiveLeases(clock)

    /** Trusted host inventory for dependency planning/diagnostics; never grants invocation authority. */
    fun providerInventory(): List<ProviderDescriptor> = synchronized(lock) {
        providers.values.map { provider -> provider.descriptor.copy(
            contracts = provider.descriptor.contracts.map { it.copy(features = it.features.toSet()) },
            transports = provider.descriptor.transports.toSet(),
        ) }.sortedBy { it.id }
    }

    /** Immutable descriptor lookup for trusted catalog adapters; grants still apply to invocation. */
    fun contractDescriptor(id: String, version: Version): ContractDescriptor? = synchronized(lock) { contracts[id to version] }

    /** Trusted host resource-sharing hook. A stale descriptor never resolves to a replacement. */
    fun activationIdentity(descriptor: ProviderDescriptor): CallerIdentity? = synchronized(lock) {
        providers[descriptor.id]?.takeIf { it.descriptor == descriptor && it.owner.active && !it.owner.closed }
            ?.owner?.identity
    }

    internal class Provider(
        val descriptor: ProviderDescriptor,
        val handler: OperationHandler,
        val owner: Activation,
        val job: CompletableJob,
        var stream: StreamHandler? = null,
        var calls: Int = 0,
    )

    inner class Activation internal constructor(
        val identity: CallerIdentity,
        val generation: Long,
        private val entrypoint: PluginEntrypoint,
        internal val job: CompletableJob,
    ) : AutoCloseable {
        internal val stagedContracts = mutableMapOf<Pair<String, Version>, ContractDescriptor>()
        internal val stagedProviders = mutableMapOf<String, Provider>()
        internal val stagedStreams = mutableMapOf<String, StreamHandler>()
        internal var active = false
        internal var closed = false
        /** Work launched here is cancelled on disable, failed startup, or runtime shutdown. */
        val scope = CoroutineScope(Dispatchers.Default + job)
        val client: ProtocolClient = BoundClient(identity, this)
        private var storageAccess: PluginStorage = DeniedPluginStorage
        private var networkAccess: NetworkAccess = DeniedNetworkAccess
        private var mediaAccess: MediaAccess = DeniedMediaAccess
        private var hostFeatures: HostFeatures = DeniedHostFeatures
        private var uiAccess: org.amalia.platform.protocol.sdk.PluginUiAccess = org.amalia.platform.protocol.sdk.DeniedPluginUiAccess

        private fun bindStorage(delegate: PluginStorage): PluginStorage {
            fun namespace(select: () -> StorageNamespace): StorageNamespace = object : StorageNamespace {
                private fun <T> access(operation: (StorageNamespace) -> T): T = synchronized(lock) {
                    check(root.isActive && job.isActive && !closed && activations[identity] === this@Activation) {
                        "Package storage generation was revoked"
                    }
                    // Hold the lifecycle lock through IO, not just the authorization check. Lock order:
                    // runtime -> package data -> journal. close waits for in-flight operations to finish.
                    operation(select())
                }
                override fun read(key: String): String? = access { it.read(key) }
                override fun write(key: String, value: String) = access { it.write(key, value) }
                override fun delete(key: String) = access { it.delete(key) }
            }
            return object : PluginStorage {
                override val data = namespace { delegate.data }
                override val settings = namespace { delegate.settings }
            }
        }
        val context: ManagedPluginContext = object : ManagedPluginContext {
            override val scope get() = this@Activation.scope
            override val identity get() = this@Activation.identity
            override val activationGeneration get() = generation
            override val client get() = this@Activation.client
            override val resources: ResourceAccess = this@CoreRuntime.resources(identity)
            override val storage: PluginStorage get() = storageAccess
            override val media: MediaAccess get() = mediaAccess
            override val services: HostFeatures get() = hostFeatures
            override val ui: org.amalia.platform.protocol.sdk.PluginUiAccess get() = uiAccess
            override val network: NetworkAccess = object : NetworkAccess {
                override suspend fun get(url: String, headers: Map<String, String>): NetworkResponse {
                    synchronized(lock) {
                        check(root.isActive && job.isActive && !closed && activations[identity] === this@Activation) {
                            "Package network generation was revoked"
                        }
                    }
                    val request = scope.async {
                        networkAccess.get(url, headers.toMap())
                    }
                    return try {
                        val response = request.await()
                        synchronized(lock) {
                            check(root.isActive && job.isActive && !closed && activations[identity] === this@Activation) {
                                "Package network generation was revoked"
                            }
                        }
                        response
                    } finally {
                        request.cancel()
                    }
                }
            }
            override suspend fun registerContract(contract: ContractDescriptor): Registration = synchronized(lock) {
                check(!closed && !active) { "Registrations are accepted only during staging" }
                val key = contract.id to contract.version
                require(contract.id.startsWith(identity.publisherId + ".")) { "Contract must use the publisher namespace" }
                require(key !in stagedContracts) { "Duplicate staged contract" }
                stagedContracts[key] = contract
                registration { stagedContracts.remove(key, contract) }
            }
            override suspend fun registerProvider(provider: ProviderDescriptor, handler: OperationHandler): Registration = synchronized(lock) {
                check(!closed && !active) { "Registrations are accepted only during staging" }
                require(provider.publisherId == identity.publisherId && provider.packageId == identity.packageId &&
                    provider.deviceId == deviceId && provider.activationGeneration == generation) { "Provider ownership mismatch" }
                require(provider.id !in stagedProviders) { "Duplicate staged provider" }
                val record = Provider(provider, handler, this@Activation, SupervisorJob(job))
                stagedProviders[provider.id] = record
                registration {
                    stagedProviders.remove(provider.id, record)
                    if (providers[provider.id] === record) providers.remove(provider.id)
                    record.job.cancel()
                    leases.releaseOwner(identity, generation, provider.id)
                }
            }
            override suspend fun registerStreamProvider(providerId: String, handler: StreamHandler): Registration = synchronized(lock) {
                check(!closed && !active) { "Registrations are accepted only during staging" }
                require(providerId in stagedProviders && providerId !in stagedStreams)
                stagedStreams[providerId] = handler
                registration {
                    stagedStreams.remove(providerId, handler)
                    stagedProviders[providerId]?.takeIf { it.stream === handler }?.let {
                        // Removing a stream capability also fences already collecting callbacks.
                        if (providers[providerId] === it) providers.remove(providerId)
                        it.job.cancel()
                    }
                }
            }
        }

        internal suspend fun start() {
            try {
                storageAccess = bindStorage(this@CoreRuntime.storage(identity, generation))
                networkAccess = this@CoreRuntime.network(identity, generation)
                mediaAccess = this@CoreRuntime.media(identity, generation)
                hostFeatures = this@CoreRuntime.services(identity, generation)
                uiAccess = this@CoreRuntime.ui(identity, generation)
                val startJob = scope.async { withTimeout(limits.maxStartupMillis) { entrypoint.start(context) } }
                withTimeout(limits.maxStartupMillis) { startJob.await() }
                synchronized(lock) {
                    check(!closed && root.isActive && activations[identity] === this) { "Activation was revoked" }
                    stagedContracts.forEach { (key, value) ->
                        val existing = contracts[key]
                        require(existing == null || existing == value) { "Conflicting contract descriptor: $key" }
                    }
                    stagedProviders.values.forEach { provider ->
                        require(provider.descriptor.id !in providers) { "Provider ID already active" }
                        provider.descriptor.contracts.forEach { offer ->
                            val descriptor = stagedContracts[offer.contractId to offer.version] ?: contracts[offer.contractId to offer.version]
                            require(descriptor != null) { "Unknown contract ${offer.contractId}" }
                            require(offer.features.containsAll(descriptor.features)) { "Missing contract features" }
                        }
                        provider.stream = stagedStreams[provider.descriptor.id]
                    }
                    // Every check precedes every public mutation. No partially ACTIVE provider set.
                    contracts.putAll(stagedContracts)
                    providers.putAll(stagedProviders)
                    active = true
                }
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                active = false
                if (activations[identity] === this) activations.remove(identity)
                stagedProviders.values.forEach { if (providers[it.descriptor.id] === it) providers.remove(it.descriptor.id) }
                leases.releaseOwner(identity, generation)
                job.cancel(CancellationException("Activation disabled"))
                withdrawn(identity, generation)
            }
        }

        /** Cancellation/withdrawal happen first. Hook execution is best effort and bounded. */
        suspend fun stop() {
            close()
            val cleanupJob = SupervisorJob()
            val cleanup = CoroutineScope(Dispatchers.Default + cleanupJob).async { entrypoint.stop() }
            try { withTimeout(limits.maxStopMillis) { cleanup.await() } }
            finally { cleanupJob.cancel() }
        }
    }

    /** Caller identity must come from authenticated host/package verification, never decoded input. */
    suspend fun activate(identity: CallerIdentity, entrypoint: PluginEntrypoint): Activation {
        require(identity.deviceId == deviceId)
        val activation = synchronized(lock) {
            check(root.isActive) { "Runtime closed" }
            require(identity !in activations) { "Disable the existing activation first" }
            Activation(identity, generations.incrementAndGet(), entrypoint, SupervisorJob(root)).also { activations[identity] = it }
        }
        activation.start()
        return activation
    }

    /** Host authorizes exclusive resources before calling this method. Ownership is never caller supplied. */
    fun acquireLease(activation: Activation, providerId: String, resourceId: String, durationMillis: Long): ExclusiveLeases.Lease? = synchronized(lock) {
        check(root.isActive && activation.active && !activation.closed && activations[activation.identity] === activation)
        require(providers[providerId]?.owner === activation) { "Provider is not owned by this activation" }
        leases.acquire(resourceId, activation.identity, activation.generation, providerId, durationMillis)
    }

    private fun registration(remove: () -> Unit): Registration = object : Registration {
        private var closed = false
        override fun close() = synchronized(lock) { if (!closed) { closed = true; remove() } }
    }

    fun grant(grant: RuntimeGrant) = synchronized(lock) {
        check(root.isActive)
        require(grant.expiresAtEpochMillis > clock()) { "Grant expired" }
        grants[grant.id] = grant.copy(operations = grant.operations?.toSet())
    }
    fun revoke(grantId: String) { synchronized(lock) { grants.remove(grantId) } }

    /** Host-only clients have runtime lifetime; plugin clients are bound to their activation. */
    fun clientFor(caller: CallerIdentity): ProtocolClient = BoundClient(caller, null)

    fun select(query: ProviderQuery, profile: SelectionProfile = SelectionProfile()): ProviderSelection = synchronized(lock) {
        val chosen = query.providerId ?: profile.bindings[query.contractId]
        val candidates = providers.values.map { it.descriptor }.filter { it.supports(query) && (chosen == null || it.id == chosen) }
            .sortedBy { it.id }
        when (candidates.size) {
            0 -> ProviderSelection.Unavailable(if (chosen == null) "No compatible provider" else "Selected provider is unavailable or incompatible")
            1 -> ProviderSelection.Selected(candidates.single())
            else -> ProviderSelection.Ambiguous(candidates)
        }
    }

    private fun matching(caller: CallerIdentity, contract: String, operation: String, provider: String): Set<RuntimeGrant> =
        grants.values.filter { it.permits(caller, contract, operation, provider, clock()) }.toSet()

    private inner class BoundClient(val caller: CallerIdentity, val activation: Activation?) : ProtocolClient {
        fun alive() = root.isActive && (activation == null || !activation.closed && activation.active)
        override suspend fun discover(query: ProviderQuery): DiscoveryResult = synchronized(lock) {
            if (!alive()) DiscoveryResult(emptyList(), listOf(UnavailableProvider(null, runtimeError(ErrorCodes.PROVIDER_UNAVAILABLE, "Caller is disabled"))))
            else DiscoveryResult(providers.values.map { it.descriptor }.filter { it.supports(query) }.sortedBy { it.id })
        }

        override suspend fun invoke(request: InvocationRequest): InvocationResponse {
            fun reply(result: InvocationResult) = InvocationResponse(request.requestId, result)
            var rejection: InvocationResult? = null
            val pair = synchronized(lock) {
                val provider = providers[request.providerId]
                val descriptor = contracts[request.contractId to request.contractVersion]
                val operation = descriptor?.operations?.find { it.id == request.operationId }
                val code = when {
                    !alive() -> ErrorCodes.CANCELLED
                    request.protocolVersion !in Protocol.supportedVersions -> ErrorCodes.UNSUPPORTED_PROTOCOL
                    provider == null -> ErrorCodes.PROVIDER_UNAVAILABLE
                    request.extensions["amalia.expectedProviderGeneration"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() != provider.descriptor.activationGeneration
                    } == true -> ErrorCodes.PROVIDER_UNAVAILABLE
                    !provider.descriptor.supports(ProviderQuery(request.contractId, VersionRange(request.contractVersion), request.requiredFeatures, request.providerId)) ||
                        provider.descriptor.contracts.none { it.contractId == request.contractId && it.version == request.contractVersion } -> ErrorCodes.UNSUPPORTED_VERSION
                    operation == null -> ErrorCodes.UNSUPPORTED_OPERATION
                    provider.descriptor.contracts.none { it.contractId == request.contractId && it.version == request.contractVersion && it.features.containsAll(operation.requiredFeatures) } -> ErrorCodes.UNSUPPORTED_FEATURE
                    matching(caller, request.contractId, request.operationId, request.providerId).isEmpty() -> ErrorCodes.ACCESS_DENIED
                    operation.input.validate(request.input).isNotEmpty() -> ErrorCodes.INVALID_INPUT
                    provider.calls >= limits.maxConcurrentCallsPerProvider -> ErrorCodes.BUDGET_EXCEEDED
                    request.idempotency?.let { it.expiresAtEpochMillis <= clock() || it.scope != "${caller.instanceId}/${request.providerId}/${request.contractId}/${request.operationId}" } == true -> ErrorCodes.INVALID_INPUT
                    else -> null
                }
                if (code != null || provider == null || operation == null) {
                    rejection = failure(code ?: ErrorCodes.PROVIDER_UNAVAILABLE, "Invocation rejected before dispatch")
                    null
                } else { provider.calls++; provider to operation }
            } ?: return reply(requireNotNull(rejection))
            val (provider, operation) = pair
            val deadline = minOf(request.deadlineEpochMillis ?: Long.MAX_VALUE, clock() + limits.maxInvocationMillis)
            val callJob = SupervisorJob(provider.job)
            val callerHandle = activation?.job?.invokeOnCompletion { callJob.cancel() }
            val context = context(caller, provider, request.contractId, request.operationId, deadline, activation)
            try {
                val remaining = deadline - clock()
                if (remaining <= 0) return reply(failure(ErrorCodes.DEADLINE_EXCEEDED, "Invocation deadline expired"))
                val work = CoroutineScope(Dispatchers.Default + callJob).async {
                    context.ensureActive()
                    provider.handler.invoke(request.copy(deadlineEpochMillis = deadline), context).also { context.ensureActive() }
                }
                val result = withTimeout(remaining) { work.await() }
                val checked = synchronized(lock) {
                    when {
                        providers[request.providerId] !== provider || !alive() -> failure(ErrorCodes.PROVIDER_UNAVAILABLE, "Provider generation was withdrawn")
                        matching(caller, request.contractId, request.operationId, request.providerId).isEmpty() -> failure(ErrorCodes.ACCESS_DENIED, "Grant revoked")
                        clock() >= deadline -> failure(ErrorCodes.DEADLINE_EXCEEDED, "Invocation deadline expired")
                        result is InvocationResult.Success && operation.output.validate(result.output).isNotEmpty() -> failure(ErrorCodes.INVALID_OUTPUT, "Provider output violates the contract")
                        result is InvocationResult.Accepted && (operation.execution != ExecutionKind.TASK || result.task.providerId != provider.descriptor.id) -> failure(ErrorCodes.INVALID_OUTPUT, "Invalid accepted task")
                        result is InvocationResult.Failure && operation.errors[result.error.code]?.validate(result.error.details)?.isNotEmpty() == true -> failure(ErrorCodes.INVALID_OUTPUT, "Provider error violates the contract")
                        else -> result
                    }
                }
                return reply(checked)
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                return reply(failure(ErrorCodes.DEADLINE_EXCEEDED, "Invocation deadline expired; external effects may require verification"))
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                return reply(failure(ErrorCodes.CANCELLED, "Invocation was cancelled; external effects may require verification"))
            } catch (error: Exception) {
                return reply(failure(ErrorCodes.EXECUTION_FAILED, "Provider execution failed"))
            } finally {
                callJob.cancel()
                callerHandle?.dispose()
                synchronized(lock) { provider.calls-- }
            }
        }

        override fun subscribe(request: SubscriptionRequest): Flow<StreamMessage> = flow {
            val provider = synchronized(lock) { providers[request.providerId] }
            val descriptor = synchronized(lock) { contracts[request.contractId to request.contractVersion] }
            val schema = when (request.kind) {
                SubscriptionKind.EVENTS -> descriptor?.events?.find { it.id == request.topicId }?.payload
                SubscriptionKind.STATE -> descriptor?.states?.find { it.id == request.topicId }?.snapshot
            }
            val permission = "subscribe:${request.topicId}"
            val permitted = synchronized(lock) { alive() && matching(caller, request.contractId, permission, request.providerId).isNotEmpty() }
            val handler = provider?.stream
            if (!permitted || provider == null || schema == null || handler == null ||
                request.protocolVersion !in Protocol.supportedVersions || request.bufferCapacity > limits.maxStreamBuffer ||
                request.overflow != OverflowPolicy.SUSPEND && request.overflow != OverflowPolicy.FAIL ||
                provider.descriptor.contracts.none { it.contractId == request.contractId && it.version == request.contractVersion }) {
                emit(StreamMessage.Closed(runtimeError(ErrorCodes.ACCESS_DENIED, "Subscription unavailable or not authorized")))
                return@flow
            }
            val deadline = minOf(request.expiresAtEpochMillis ?: Long.MAX_VALUE, clock() + limits.maxSubscriptionMillis)
            if (deadline <= clock()) {
                emit(StreamMessage.Closed(runtimeError(ErrorCodes.DEADLINE_EXCEEDED, "Subscription expired")))
                return@flow
            }
            val context = context(caller, provider, request.contractId, permission, deadline, activation)
            // Provider-owned worker is cancelled by disable; channel capacity bounds slow consumers.
            val workerJob = SupervisorJob(provider.job)
            val callerHandle = activation?.job?.invokeOnCompletion { workerJob.cancel() }
            val channel = kotlinx.coroutines.channels.Channel<StreamMessage>(request.bufferCapacity)
            val worker = CoroutineScope(Dispatchers.Default + workerJob).launch {
                try {
                    withTimeout(deadline - clock()) {
                        context.ensureActive()
                        handler.subscribe(request, context).collect { message ->
                            context.ensureActive()
                            val valid = when (message) {
                                is StreamMessage.Event -> request.kind == SubscriptionKind.EVENTS && message.topicId == request.topicId && schema.validate(message.payload).isEmpty()
                                is StreamMessage.Snapshot -> request.kind == SubscriptionKind.STATE && message.topicId == request.topicId && schema.validate(message.value).isEmpty()
                                is StreamMessage.Closed -> true
                            }
                            check(valid) { "Invalid stream output" }
                            if (request.overflow == OverflowPolicy.FAIL) {
                                check(channel.trySend(message).isSuccess) { "Stream buffer overflow" }
                            } else channel.send(message)
                        }
                    }
                    channel.close()
                } catch (error: Throwable) { channel.close(error) }
            }
            worker.invokeOnCompletion { cause -> channel.close(cause) }
            try {
                for (message in channel) {
                    context.ensureActive()
                    emit(message)
                    if (message is StreamMessage.Closed) break
                }
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                emit(StreamMessage.Closed(runtimeError(ErrorCodes.CANCELLED, "Subscription closed")))
            } catch (error: Exception) {
                emit(StreamMessage.Closed(runtimeError(ErrorCodes.STREAM_OVERFLOW, "Stream failed or requires resynchronization")))
            } finally { callerHandle?.dispose(); workerJob.cancel(); channel.cancel() }
        }

        override suspend fun respond(interactionId: String, response: kotlinx.serialization.json.JsonElement): InvocationResult =
            failure(ErrorCodes.UNSUPPORTED_OPERATION, "Use a contract operation for interaction responses")
        override suspend fun cancel(task: TaskRef): InvocationResult =
            failure(ErrorCodes.UNSUPPORTED_OPERATION, "Use a contract operation for task cancellation")
    }

    private fun context(caller: CallerIdentity, provider: Provider, contract: String, operation: String, deadline: Long, callerActivation: Activation? = null): InvocationContext = object : InvocationContext {
        override val caller = caller
        override val effectiveGrants: Set<EffectiveGrant> get() = synchronized(lock) { matching(caller, contract, operation, provider.descriptor.id) }
        override val delegation: List<DelegationHop> = emptyList()
        override val deviceId get() = this@CoreRuntime.deviceId
        override val activationGeneration get() = provider.owner.generation
        override val deadlineEpochMillis get() = deadline
        override val budget: InvocationBudget = object : InvocationBudget {
            override val remainingCalls: Long? = null
            override val remainingBytes: Long? = null
            override val remainingExecutionMillis get() = (deadline - clock()).coerceAtLeast(0)
        }
        override suspend fun ensureActive() {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (!root.isActive || callerActivation?.let { it.closed || !it.active } == true ||
                    providers[provider.descriptor.id] !== provider || !provider.owner.active ||
                    clock() >= deadline || matching(caller, contract, operation, provider.descriptor.id).isEmpty())
                    throw CancellationException("Invocation authority or generation expired")
            }
        }
    }

    override fun close() = synchronized(lock) {
        activations.values.toList().forEach { it.close() }
        providers.clear()
        grants.clear()
        leases.close()
        root.cancel()
    }
}
