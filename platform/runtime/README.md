# Core runtime

`DependencyResolver.resolve(candidates, profile)` computes a deterministic activation order and exact contract-version bindings from host-verified provider inventories. Missing or ambiguous required providers and mandatory startup cycles block the entire plan. Explicit provider/profile choices never silently fall back. Optional unavailable bindings are reported separately and do not establish startup ordering. Planning grants no authority and does not load packages.

`org.amalia.platform.runtime` is the domain-neutral, in-memory implementation of the open `org.amalia.platform.protocol` interfaces. The host owns `CoreRuntime`; plugins receive a `PluginContext` and its caller-bound `ProtocolClient`. Package verification, persistence, transport authentication and UI belong to other layers.

## Host integration

1. Create `CoreRuntime(deviceId, limits, clock, resources, storage)`. The optional `resources` factory must return an access-checking `ResourceAccess` for the authenticated identity; the default denies resource operations.
2. Authenticate a package/instance and call `activate(identity, entrypoint)`. Do not construct identity from untrusted invocation fields. The host assigns monotonically increasing activation generations for this runtime instance.
3. Inside `PluginEntrypoint.start`, register contract descriptors, then provider descriptors and their `OperationHandler`, then any `StreamHandler`. Use `context.activationGeneration` in provider metadata. Newly declared contract IDs must start with the publisher ID plus `.`. Existing published contracts can be implemented without republishing them.
4. The startup callback completes before the runtime validates and publishes the entire registration set under one lock. Duplicate/conflicting IDs or unknown contracts abort startup. A failed or cancelled startup publishes no providers. Registrations are accepted only during staging; publish a changed set through a new activation.
5. Grant authority explicitly with `grant(RuntimeGrant(...))`. Grants match caller identity, contract, optional exact operation set, optional provider, and absolute expiry. There are no default grants, publisher bypasses or payload-supplied delegation. Event/state permissions use operation name `subscribe:<topicId>`.
6. Use `select(query, SelectionProfile(bindings))` to get `Selected`, `Unavailable`, or `Ambiguous`. An explicit query provider overrides the profile. A missing selected provider does not fall back. Several candidates without a binding remain ambiguous. Construct an `InvocationRequest` using the selected provider and an exact offered contract version.
7. Use `activation.client` for activation-owned clients. `clientFor(identity)` is a trusted host adapter with runtime lifetime; never expose the runtime factory/administrator to a plugin. `InvocationContext` is constructed by the runtime, separately from the request, with effective grants and generation.
8. `activation.close()` immediately withdraws providers, cancels managed work, closes owned subscriptions, and releases leases. `activation.stop()` additionally invokes the bounded stop hook. The host should call `stop()` when it needs orderly callback cleanup. `runtime.close()` cancels everything synchronously without executing plugin stop hooks.

The optional `storage(identity, runtimeGeneration)` factory supplies `PluginStorage` with only
`data`/`settings` string KV facades; its default denies all operations. Host code must bind the exact
authenticated package owner and separately decide read/write authority. `RuntimeGrant` authorizes
contract invocations, not package storage; do not invent an invocation grant for private KV.
The runtime wraps every read/write/delete with its lifecycle monitor and holds it through the
operation. Startup/health may use storage before providers are published; failed startup, close,
stop and runtime shutdown revoke it before cleanup or data reconciliation. Retained facades cannot
access a replacement generation. Call synchronous storage IO on a worker dispatcher. Factory and
facade callbacks must not reenter runtime administration or reverse the lock order: runtime,
package data store, activation journal. Bounded IO can delay withdrawal while an operation finishes.

`ManagedPluginContext` extends `PluginContext` with `scope`; entrypoints may cast to this interface to launch lifecycle-owned work. The host also has `activation.scope`. Do not detach work from that scope.

## Invocation behavior

Input and successful output are validated using the registered operation schema. Declared error detail schemas are validated as well; accepted tasks must match a task operation and provider. Required provider features and exact contract offers are checked before dispatch. Requests cannot supply caller context. Both handler entry and result delivery are fenced by current provider registration, grants and deadline. Closing an old registration cannot withdraw a replacement provider.

Calls have a per-provider concurrency ceiling and a host maximum duration, even when the request omits its deadline. Handler work runs on a provider-owned coroutine job; caller cancellation, disable and deadlines cancel it. A late result from a disabled generation is never delivered. Grant expiry/revocation is rechecked by `InvocationContext.ensureActive()` and before delivery. Revocation does not undo external effects; handlers should call `ensureActive()` before effects and in long loops. A grant revoked while the handler is suspended is denied on its next check or result delivery, rather than being treated as authority until completion.

Exceptions expose sanitized protocol errors. Deadline/cancellation/failure does not establish whether an external action occurred. Idempotency keys require the exact scope `<caller.instanceId>/<providerId>/<contractId>/<operationId>` and a future expiry. No duplicate-result store or exactly-once promise is implemented. `respond` and `cancel(TaskRef)` return explicit unsupported-operation failures; define contract operations or integrate a task service for those capabilities.

## Streams and replay

A provider can register a stream handler during startup. Bound subscriptions validate permissions, contract version, topic schema, message kind, topic ID, deadline and generation. Each subscription has a bounded channel; `SUSPEND` supplies backpressure and `FAIL` closes on overflow. Drop policies are rejected because this runtime has no contract declaration authorizing telemetry loss. Collections own their workers and release them on cancellation. Subscriptions have a host maximum lifetime even if the request omits expiry.

`ReplayEvents(eventDescriptor, orderingScope, capacity)` supplies a bounded event log and a `handler()` adapter. `publish(payload)` validates the schema and creates an event ID, monotonically increasing sequence and opaque epoch cursor. Null cursors replay retained history. Foreign, future and evicted cursors emit `StreamMessage.Closed` with code `runtime.replay_gap`, retry advice `AFTER_INTERVENTION`, and `oldestCursor`/`latestCursor` details. A slow subscriber that misses retention receives the same explicit gap. Reconnect then needs a contract-specific snapshot/resynchronization decision. The adapter rejects filters; implement their semantics in a custom handler. Logs have process lifetime and must be closed by their owner.

## Exclusive resources

After checking resource authorization, the trusted host calls `acquireLease(activation, providerId, resourceId, durationMillis)`. Ownership is derived from the live activation and its registered provider. An occupied resource returns null; there is no implicit priority/preemption. `runtime.leases.isValid(lease)` checks expiry and token, `renew` returns a replacement token, and `release` compares the entire lease. Delayed release/renew callbacks cannot release a newer owner or token. Disable releases all matching activation leases. Executors must check lease validity before using a resource and stop on loss; this API does not forcibly interrupt hardware or native code.

## Limits and boundaries

This is cooperative in-process execution, not a security sandbox or hard CPU/memory isolation. A non-cooperative handler may keep running after cancellation, but cannot publish a valid runtime result from an old generation. Descriptors are immutable by contract; pass stable collections rather than mutating a descriptor after registration. Contract metadata remains available after its publishing activation stops; provider availability does not. Runtime state, grants, generations, cursors and leases are not persisted. No archive installation, activation journal, transactional migrations, transport, task scheduler or domain policy is implied by this module.
