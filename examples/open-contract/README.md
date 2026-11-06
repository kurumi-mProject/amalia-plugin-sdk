# External contract author example

A standalone Kotlin/JVM application using the public protocol and runtime. Requires
JDK 17 and access to Maven Central/Gradle Plugin Portal on the first run. No Android
SDK or app module is involved. All assertions live in `main`; there are no test files.

From the repository root:

```sh
./gradlew -p examples/open-contract :run
```

From this directory:

```sh
../../gradlew -p . :run
```

Path reference: `../../gradlew -p examples/open-contract :run` combines
an example-relative wrapper path with a repository-relative project path. Use the
working-directory-specific commands above.

## Author workflow

Read `src/main/kotlin/org/example/counter/Main.kt`:

1. `CounterContract` defines an external namespaced domain, its version policy, and
   strict JSON input/output schemas. No host domain classes or SDK changes are needed.
2. `ContractPublisher` publishes that descriptor through `PluginContext`.
3. `CounterProvider`, owned by a different publisher, registers an implementation of
   the offered contract. Its provider metadata uses the host-assigned activation
   generation and identity. The counter starts fresh on every activation.
4. `CounterConsumer` receives only an activation-bound `ProtocolClient`, discovers
   an explicitly selected provider, and sends ordinary JSON protocol requests.
5. `main` is the trusted host: it assigns demo identities, activates the plugins,
   grants the consumer one operation on one provider, and later revokes that grant.
   Real identities must come from verified installation metadata, not plugin input.

The demo requires these observations, failing the run if any changes:

- Discovery works before a grant, but invocation returns `ACCESS_DENIED`.
- A grant permits incrementing the initial counter from `0` to `2`.
- Invalid input is rejected as `INVALID_INPUT`; an unsupported contract version is
  rejected as `UNSUPPORTED_VERSION` and excluded from version-filtered discovery.
  Neither rejection dispatches the provider handler.
- A separate host-bound caller does not inherit the consumer's grant.
- Closing the provider activation removes it from discovery and existing client
  calls return `PROVIDER_UNAVAILABLE`, before the stop hook runs.
- Reactivating the same provider identity/ID creates a higher generation. The same
  consumer and grant now reach a replacement initialized to `100`, returning `102`.
- Revoking the grant denies further calls without invoking the replacement handler.

Expected final line:

```text
All external contract, authority, schema, version and replacement checks passed.
```

The host fences activation with `close()` and calls `stop()` for plugin cleanup.
The example uses the same `PluginEntrypoint`, `PluginContext`, descriptors and
request/result types available to other authors. Only the host wiring imports
`CoreRuntime` and `RuntimeGrant`. Same-process plugins are not a sandbox, and this
example does not load package archives or claim isolation. The counter is ephemeral;
its write operation has no deduplication or durable retry guarantee.

## Standalone build wiring

`settings.gradle.kts` includes `:protocol` and `:runtime` directly from
`../../platform/protocol` and `../../platform/runtime`; the root project is the
example application. The root is named `amalia-platform` to select the runtime's existing
standalone dependency mapping. `build.gradle.kts` belongs to the root application; every included project has
a distinct directory. These are project dependencies, so no local
Maven publication or included-build substitution is required.
