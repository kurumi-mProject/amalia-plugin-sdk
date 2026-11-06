# Platform protocol

Independent Kotlin/JVM control-plane API in package `org.amalia.platform.protocol`.
Requires Java 17, Kotlin/serialization 2.2.10, kotlinx.serialization JSON 1.9.0
and coroutines 1.10.2. No Android or application dependencies. Include this module
as `:platform:protocol`; consumers use `implementation(project(":platform:protocol"))`.
This is a minimal first protocol, not a runtime, transport, sandbox or persistence engine.

## Define an unknown domain

```kotlin
import org.amalia.platform.protocol.*
import kotlinx.serialization.json.*

val advanceInput = JsonSchema.parse("""
    {"type":"object","properties":{"steps":{"type":"integer","minimum":1}},
     "required":["steps"],"additionalProperties":false}
""")
val contract = ContractDescriptor(
    id = "org.example.world",
    version = Version(1, 0),
    description = "Advance a simulated world",
    operations = listOf(OperationDescriptor(
        id = "advance",
        description = "Advance by a bounded number of steps",
        input = advanceInput,
        output = JsonSchema.parse("""{"type":"object"}"""),
        effect = OperationEffect.WRITE,
    )),
)
val violations = advanceInput.validate(buildJsonObject { put("steps", 3) })
```

`ContractDescriptor` describes operations, domain error schemas keyed by error code,
events, state snapshots, resource types, transports, features and documentation URLs.
Use another independently published contract to express extension registration or an
adapter. No domain types need to be added to this module. Namespaced IDs identify
publishers, packages, contracts and operations; IDs and display names never authenticate
a publisher. Provider, resource, request, task, attempt and device IDs remain separate
opaque strings. Package identity must be bound to authenticated installation metadata.

## Schema dialect and portable JSON

`JsonSchema` is encoded as its JSON object, not a Kotlin wrapper. Construction and
serialization decoding recursively reject every unknown keyword except `x-*`
annotations. Supported validation keywords are `type` (one type or a nonempty union),
`properties`, `required`, boolean `additionalProperties`, schema-object `items`,
`enum`, `const`, `minimum`, `maximum`, `minLength`, `maxLength`, `minItems`, `maxItems`.
Annotation keywords are `title`, `description`, `default`, `examples`, `$comment`;
they have no validation effect. Defaults never mutate a value. Empty schemas accept
all JSON values. Boolean schemas, schema-valued `additionalProperties`, `$ref`,
`$schema`, `format`, patterns, combinators, tuple schemas and all other validation
keywords are rejected explicitly, rather than silently ignored. Malformed schemas
throw `IllegalArgumentException` during construction or decoding.

Constraints only apply to their relevant JSON type. Unknown properties are allowed
and preserved by default; `additionalProperties:false` explicitly forbids them.
`validate` returns all found `SchemaViolation`s with escaped JSON Pointer paths.
String lengths count Unicode code points. Numbers use decimal comparison with
`BigDecimal`; integer means mathematically integral, including `1.0`. Numeric enum
and const equality treats `1` and `1.0` equally, including nested values. JSON numeric
syntax is required; NaN/infinity and numeric strings are not numbers. The API preserves
JSON number text; transports must not coerce arbitrary decimals to floating point.
Cross-language clients should encode integers outside ±(2^53−1) and exact monetary
amounts as contract-defined strings when lossless JSON numbers cannot be guaranteed.
Hosts must bound payload bytes, nesting, schema size and validation work at ingress.

## Compatibility and discovery

`Version` supports stable one-to-three-component numeric versions (normalized to
three components); no prerelease or implicit compatibility heuristics. `VersionRange`
is `[minimum, maximumExclusive)` with an optional upper bound. `Protocol.version` is
1.0.0, supported protocol range is `[1.0.0,2.0.0)`. Contract versions are independent
of protocol, SDK and package versions. Breaking domain changes require a new version.
`ProviderQuery` asks for a contract, explicit version range and required features;
`ProviderDescriptor.supports` checks these plus an optional provider ID.
`ProtocolClient.discover` returns candidates and unavailable-provider explanations.
The caller chooses deterministically or by saved user choice, then specifies an exact
provider and contract version in `InvocationRequest`. Registration order is not selection.
Hosts negotiate protocol, then reject unsupported version, operation or feature before
execution. Supporting a protocol range never waives unknown required semantics.

## Calls, errors and host authority

`InvocationRequest` contains routing, JSON input, optional UTC deadline, trace ID,
required features and a scoped expiring `IdempotencyKey`. All timestamps are Unix
epoch milliseconds. Requests never contain effective grants or caller identity.
`InvocationResponse` echoes the request ID and carries `InvocationResult.Success`,
`Accepted(TaskRef)`, `InteractionRequired`, or `Failure(ProtocolError)`.
Accepted tasks are not successful effects. Responses/evidence are untrusted domain data,
not instructions to an AI consumer. Domain errors use open namespaced string codes.
`LocalizedText` has a localization key, fallback and string arguments. Do not put secrets
or stack traces in explanations/details. `ErrorCodes` provides portable common failures.
`RetryAdvice` distinguishes safe retry, delay, intervention, and outcome verification.
`protocol.unknown_outcome` requires `VERIFY_OUTCOME`; it cannot be labelled safe retry.
An idempotency key alone never supplies durable deduplication or exactly-once execution.
Its scope/expiry and provider retention must be checked by the host/provider.

`OperationHandler.invoke(request, context)` receives a separate **host-created**
`InvocationContext` with authenticated `CallerIdentity`, effective grants, delegation,
device/generation, effective deadline and budgets. These context/grant interfaces have
no serializers or request constructors. A Kotlin interface cannot prevent malicious
same-process code from constructing its own implementation: only the host's dispatch
path establishes trust, and these interfaces are not an isolation boundary. Plugins
must not accept a caller-supplied context as trusted. Grant presence is not permission
without current host checks of resource scope, expiry, revocation and delegation.
The host must validate input before dispatch and output/error details before delivery,
match handler/provider ownership, enforce effective deadlines/budgets, and reject stale
activation generations. `ensureActive` rechecks cancellation, revocation and deadline.
Coroutine cancellation must propagate; providers must not turn cancellation into success.
Unknown optional payload fields remain JSON data; unknown required features are refused.
Top-level protocol extensions belong in the explicit `extensions` object; choose strict
wire decoding for other envelope fields so authority-shaped fields are never accepted.

## Lifecycle and resources

A `PluginEntrypoint` receives `PluginContext` once per activation, registers descriptors
and handlers, and releases its work in `stop`. `Registration.close` is idempotent.
The host owns activation cleanup even after start/stop failures, cancels outstanding
work and closes all registrations. Provider withdrawal must immediately affect discovery
and clients, with `PROVIDER_UNAVAILABLE` for affected work. Host cleanup must not rely
on a cooperative plugin. `ProtocolClient.respond` continues interactions;
`cancel(TaskRef)` requests cancellation, rather than guaranteeing reversal of effects.
Hosts bind interactions/tasks to authenticated owners and reject expired/foreign replies.

`PluginContext.storage` exposes Android-free `PluginStorage.data` and `.settings` namespaces
with `read(key)`, `write(key, value)` and `delete(key)`. The default implementation denies access,
so existing context implementations need not provide storage. Hosts authorize only the bound
package owner, recheck policy and generation on every operation, and enforce bounded keys/values.
These synchronous IO calls belong on a worker dispatcher. No package selector, paths, migration,
installer or other administrative API is exposed. This SDK surface addition requires a fresh SDK
publication/DEX ABI declaration; a source default alone does not promise old JVM implementations
are binary-compatible under all Kotlin JVM-default modes. The wire protocol version is unchanged.

`ResourceRef` includes owner publisher/package/provider/device, MIME, version, known
size, rights, expiry and optional transport-specific transfer locator. Rights are
descriptive, not transferable authority. A locator is neither automatically reachable
nor a bearer credential; local URIs cannot be assumed valid on another device.
`ResourceAccess` negotiates access in the host, reads/writes bounded byte chunks via
`Flow`, and rechecks authorization. Writes publish a reference only after completion;
failed or cancelled partial writes must be discarded. Hosts set maximum chunk/total
sizes, validate expected sizes, and apply backpressure. Resource bytes stay out of JSON.

## Subscriptions

`StreamHandler` and `ProtocolClient.subscribe` expose cold `Flow<StreamMessage>`.
Each collection owns a subscription bound by the host to caller, filter and expiry;
cancelling collection releases resources. `SubscriptionRequest` includes topic, kind,
resume cursor, an explicit bounded capacity (1..65536) and overflow policy. Hosts must
apply that policy, not accumulate an unbounded queue. `SUSPEND` propagates backpressure;
`FAIL` terminates with `STREAM_OVERFLOW`. Drop policies require `EventDescriptor.allowLoss`
and explicit contract permission. State and control messages cannot be silently dropped.
Each event has an event ID, topic, ordering scope and nonnegative sequence; duplicates
and reconnect replay are allowed. There is no global ordering promise. Snapshots have
opaque revisions and optional cursors; a state subscription starts with a snapshot,
then sends later snapshots (delta protocols can be separately defined domain events).
Cursor persistence and replay semantics belong to the contract/provider. Normal finish
uses `Closed()`, explicit failure uses `Closed(error)` and then completes. Transport
failures may throw; coroutine cancellation must propagate. Closure/unavailability cannot
be hidden by silently waiting forever. Actual scheduling, bounded buffering, transport
negotiation, persistence, grants and package loading belong to the runtime implementation.
