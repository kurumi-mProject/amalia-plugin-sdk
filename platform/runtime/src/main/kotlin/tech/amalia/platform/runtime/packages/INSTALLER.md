> Item 3 update: [README.md — Structured metadata and host transactions](README.md#structured-metadata-and-host-transactions-item-3)
> supersedes this original v1 description. Supply `hostPolicy` to the source-compatible installer
> constructor. Null policy fails closed for review/activation. Dependency/ABI preflight, persistent
> choices, data/settings migrations and reviewed snapshot rollback are now integrated.

# Host package installer

`PackageInstaller` is trusted host administration connecting import, review and explicit
activation. Construct it with the same `StagedPackageStore`, `ActivationJournal` and
`PackageActivationCoordinator`, plus an explicit host `allowedRuntimeIds` set. Use one installer
as exclusive administrator: do not mutate its journal, coordinator or completed stages through
other paths. The set is snapshotted and should be a subset of the trusted loader's supported IDs.
An empty set permits importing but rejects every activation review. Runtime ID membership is the
v1 compatibility preflight, not an ABI or dependency compatibility guarantee.

Keep this service, coordinator, loader, journal and filesystem paths outside plugin contexts.
The host supplies pins, runtime loader, readiness probe and finite cleanup/health timeouts as
specified in [COORDINATOR.md](COORDINATOR.md). Constructors do not load selected packages.

## Explicit flow

```kotlin
val imported = installer.stage(input) // caller owns and closes input
val proposal = installer.review(imported.stageId)
// Host presents/assesses this exact proposal and obtains explicit approval separately.
// Only the approved host action calls:
val confirmed = installer.activate(proposal)
```

`stage(InputStream)` runs on `Dispatchers.IO`, copies, verifies and extracts through the existing
store, and never calls a loader, changes selection or grants anything. Importing a package does
not imply approval. Each call creates an independent private stage; staging does not replace live
code. See [README.md](README.md) for exact v1 schema, signature policy and limits.

`review(stageId)` re-verifies with current store pins/limits without starting an attempt. The
immutable `PackageInstallReview` has no public constructor or generated `copy` and carries a
private installer identity. It exposes:

- `candidate`: host-generated stage ID, package ID, opaque version, runtime, manifest SHA-256 and
  explicit pinned-signature or unverified publisher identity.
- `archiveSha256`: SHA-256 of the entire retained ZIP, in addition to the manifest hash and its
  verified payload inventory. A byte change including signature or ZIP metadata requires review
  again, even if the logical manifest is unchanged.
- `current`: complete historical journal state, or null for a new package. This includes retained
  active/previous stages, disable/failure state and pending attempt metadata.
- `expectedRevision`: current revision, or zero for a new package.
- `replaces`: the current enabled historical selection. Null does not mean no retained history:
  a disabled record may still have `current.active`. This field never asserts live availability.

The caller must pass the exact proposal to `activate(review)` only after explicit host approval.
Calling this method is that trusted caller's consent assertion. This is a host integration contract,
not proof that a person saw a UI, not a consent UI implementation, and not a package permission
grant. A package cannot supply its own review or approve itself through this API. Code already
trusted inside the host remains responsible for honoring this contract.

Immediately before delegation, activation re-verifies the retained archive, hashes its current
bytes and checks runtime policy, publisher continuity, full candidate metadata, historical state
and expected revision against the approved proposal. A stale or changed proposal is rejected;
obtain and approve a new review. Reviews from another installer instance are rejected. A successful
activation advances the journal revision, so that proposal becomes stale. There is no persistent
approval token and no general-purpose approval cache.

The publisher preflight mirrors `ActivationJournal`: publisher names must match, and an
authenticated owner cannot be replaced by an unsigned candidate. It permits rotation between
currently pinned keys of the same publisher and permits an unsigned claim to become authenticated
for that same name; it does not claim the original unsigned name was authenticated. The journal's
`begin` performs the authoritative final publisher/revision check. The coordinator also independently
checks exact loader runtime support and unsigned-declarations policy before beginning. All these
preflight checks occur before withdrawal of an old live activation.

Once begun, the coordinator owns withdrawal, loading, health checks, fenced confirmation and
failure cleanup. Cancellation and startup/health failures are not successful installations and
may leave historical selection without a live provider. No automatic previous-provider reload
occurs. See [ACTIVATION.md](ACTIVATION.md) and [COORDINATOR.md](COORDINATOR.md) for journal
I/O uncertainty, failure fences and publication boundaries.

## History, resume and shutdown

`historicalPackages()` returns durable journal records; `live` is the coordinator's `StateFlow`
of process-confirmed activations. Keep these separate in host status displays. Historical selection
is not evidence that code is running. Live state starts empty on process restart and is not continuous
health monitoring or an atomic snapshot with a separately read historical list.

To resume a selected package, the host explicitly calls `reviewSelected(packageId)`, obtains
approval, then calls `resumeSelected(review)`. This verifies the exact enabled historical selection
and uses the same byte/revision checks as activation, creating a new generation and health probe.
There is no batch resume or boot reload. Disabled packages have no enabled selection: enabling one
requires an explicitly approved `review(stageId)` / `activate(review)` flow.

`disable(packageId, expectedRevision)` delegates durable disable and live withdrawal/cleanup to the
coordinator. A stale revision is rejected before withdrawal. An indeterminate journal write still
withdraws live code; reopen/replay a poisoned journal as described in its documentation.

`shutdown()` suspends until coordinator cleanup has been attempted, rejects subsequent installer
operations and is idempotent. It leaves durable selection intact. Call it before closing the host
runtime and journal, which remain host-owned. Cleanup errors propagate; the coordinator has already
attempted all owned activations and is closed. Shutdown does not import, activate or resume anything.

## Serialization and boundaries

A coroutine mutex serializes all installer operations, including callbacks reached through the
coordinator, and shutdown. Do not reenter from loader/probe/stop callbacks. Operations run on IO;
shutdown uses `NonCancellable` so caller cancellation cannot skip cleanup. Other operations retain
caller cancellation. Stream copying and ZIP hashing check cancellation between reads; synchronous
store verification/extraction and blocked `InputStream.read` cannot be forcibly interrupted. A
cancellation after a committed stage may leave that complete stage without delivering its return
value. It remains inert. Coordinator cleanup preserves activation cancellation and attaches cleanup
errors; cancelling a waiting operation does not authorize a later background activation.

The store root and ancestors, retained archives and extracted content must remain private and
immutable, including between review verification/hash and coordinator verification/begin. No mutex
can fence an external filesystem writer. Archive re-verification does not rehash extracted content;
this service inherits that store contract and is not a tamper-proof filesystem. Recreate a stage
from the archive if content immutability cannot be assured.

No implicit grants, publisher-based access bypass, resolver input, dependency manifest fields,
contract/ABI requirements, migration, data snapshot, automatic rollback or restoration of external
effects are introduced. The v1 manifest has no dependency/ABI inventory to resolve. Version strings
remain opaque. Pinned signatures establish identity under host policy, not safety or sandboxing;
unsigned declarations require the loader's explicit restricted-data policy. The host must authorize
runtime operations separately, using the fresh activation identity.

No tests, builds or compilers were run for this addition, as requested. Host integration and target
filesystem verification remain necessary.
