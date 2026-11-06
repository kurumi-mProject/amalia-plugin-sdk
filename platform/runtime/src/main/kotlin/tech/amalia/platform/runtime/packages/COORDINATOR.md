> Item 3 update: [README.md — Structured metadata and host transactions](README.md#structured-metadata-and-host-transactions-item-3)
> supersedes the original v1 activation entry-point and data-policy descriptions below. Activation
> now requires the installer's internal policy/data transaction. Direct coordinator activation
> signatures remain available but fail closed; use installer review/activate or reviewSelected/resumeSelected.
> Disable/close semantics remain as described.

# Host package activation coordinator

`PackageActivationCoordinator` connects `StagedPackageStore`, `ActivationJournal` and
`org.amalia.platform.runtime.CoreRuntime`. It is trusted host administration. Give plugins only
the runtime-created `PluginContext` and bound protocol clients. Never expose this coordinator,
the loader, journal, runtime administration, filesystem roots or host callbacks to plugins.
The coordinator owns loaded handles and runtime activations; the host owns and closes the
store's surrounding resources, journal and runtime separately.

## Host contract

Supply a trusted `RuntimeLoader`, a device ID matching the runtime, and an explicit suspending
`PackageHealthProbe`. There is no default readiness assertion. The loader declares exact
`supportedRuntimeIds`; unsupported IDs are rejected before begin/loading. Every code-bearing
runtime requires a current `PublisherIdentity.PinnedSignature`. Never choose a loader from
package-provided classes or treat an unknown runtime as a supported executable format.

The loader receives a `VerifiedStagedContext` only after verification and durable journal begin.
It contains verified metadata, private archive/content paths, the derived identity and journal
generation. It returns a fresh `LoadedPackageRuntime` with an entrypoint and a suspending
resource-close operation. Creation must not start registrations. On throw/cancellation before
returning a handle, the loader must release its own partially created resources. Its entrypoint
must not capture runtime administration. Closing a handle releases loader resources such as a
classloader or interpreter; plugin stop is called separately first.

`UnsignedDeclarationsPolicy.REJECT` rejects unsigned declarations. `DATA_ONLY` explicitly
accepts only restricted data interpretation. A declarations loader must never execute package
bytes, load embedded code or evaluate arbitrary expressions, even for signed declarations.
An unverified claimed publisher remains unverified; its identity fields are not proof of publisher
ownership and must not qualify for publisher-based grants. Signed code verification authenticates
a publisher, not code safety. This in-process API is not a sandbox.

Use the same store/policy for journal and coordinator. Keep the store root, ancestors, archive
and extracted content private and immutable for the entire loaded lifetime. `verifyStaged`
checks the retained archive with current pins/limits; it does **not** rehash extracted content.
If immutability cannot be guaranteed, recreate a fresh stage from the archive and verify it
before activation. The loader must consume only the verified stage, with no unverified fallback
paths, network code fetch or package-chosen external executable. No coordinator permission grant,
ABI check, dependency resolution, migration, data snapshot or binding change occurs.

Choose finite host health and cleanup timeouts. Probes should check actual provider readiness
and compatibility, using host-controlled policy and any separately authorized protocol calls.
The supplied probe context includes a generation-bound plugin client, which has no implicit
grants. Runtime and journal generations are distinct. Callback cancellation must be cooperative;
coroutine timeouts cannot forcibly terminate blocking native code or a hostile in-process plugin.
Do not reenter the coordinator from any loader, probe, stop or cleanup callback.

## Operations and observable state

- `installAndActivate(stageId, expectedRevision)` consumes an existing immutable stage. Use
  revision zero for a package with no journal state. Verification and loader policy checks happen
  before any old activation is touched. Journal `begin` persists `STARTING` before loader execution.
- After begin, the previous live activation is removed from `live`, synchronously closed to revoke
  registrations/work, and stopped with bounded cleanup before loading the replacement. Cleanup
  failures abort the new attempt. The journal may still select the old historical stage during
  startup or after failure, but it is **unavailable** here. `live: StateFlow<Map<...>>` is the
  process availability observation; journal selection alone never establishes liveness.
- The new `CallerIdentity` uses the verified publisher (or explicitly unverified declaration
  claim), verified package ID, host device ID, journal generation and fresh durable attempt UUID.
  Every attempt has a new instance ID, including restarts, so grants for an old instance do not
  automatically carry over. `CoreRuntime` allocates its own activation generation.
- The runtime starts and publishes registrations before the explicit health probe. Successful
  health and a cancellation/lifetime check precede fenced journal `confirm`. Only after confirm
  does the coordinator publish the candidate in `live`. Provider discovery directly through
  `CoreRuntime` can therefore see an unconfirmed candidate during its probe; hosts requiring
  readiness gating must route selection through coordinator availability. No atomic transaction
  spans runtime publication and durable journal confirmation.
- `activateSelected(packageId)` reads the enabled historical selection, re-verifies it, checks
  its exact stage reference and calls `begin` with the current revision. It always creates a new
  generation and probes again, even if that same stage was already confirmed or is currently live.
  Disabled packages have no selected stage and must be explicitly activated by stage ID instead.
- `disable(packageId, expectedRevision)` preflights revision, persists disable, removes availability,
  withdraws registrations and attempts stop/resource cleanup. If the disable write fails with an
  indeterminate outcome, live code is still withdrawn and the original error is thrown. Reopen a
  poisoned journal before further operations. A stale preflight revision does not stop live code.
- `close()` is a suspending, idempotent process cleanup operation. It withdraws all owned activations
  before waiting for their hooks, attempts cleanup for every handle and reports failures. It leaves
  durable selection intact. Call it before closing the host runtime and journal. It waits for any
  serialized operation already in progress; host callbacks must respect cancellation/time limits.

A single coroutine `Mutex` serializes all operations, including callbacks and close. Use one
coordinator as the exclusive activation administrator for these packages. Do not externally mutate
the journal or close/revoke its runtime activations while it is in use. `live` is a snapshot of
coordinator-confirmed availability, not continuous monitoring of crashed plugins or external runtime
shutdown; it is initially empty after every process restart.

## Failure and recovery

After a durable begin, failures withdraw the candidate, attempt bounded stop/loader cleanup and
call journal `fail` using the exact revision, generation and token returned by begin. The original
exception, including caller cancellation, is rethrown; cleanup and fail-write errors are suppressed
on it. Cleanup runs in `NonCancellable` so cancellation cannot skip withdrawal or the fail attempt.
A startup failure in `CoreRuntime.activate` already closes its registrations; the coordinator also
attempts the entrypoint stop hook and loader cleanup when no activation handle was returned.

No previous provider is automatically reloaded after failure. Historical selection is retained by
the journal, but restoring live service requires an explicit `activateSelected` and successful new
health probe. No data rollback or data-compatibility assertion is inferred. Journal rollback, if
separately authorized by the host with explicit data compatibility, still requires explicit reload
and health before service is live. This coordinator does not expose a rollback convenience API.

If confirmation fails, the candidate is withdrawn and fail is attempted. A journal write/fsync
failure can have an indeterminate commit outcome; fail may itself reject the fence or poisoned
journal. Preserve those errors and reopen/replay the journal. Do not claim atomic runtime/disk
rollback. A crash can likewise leave historical selection without any live process activation;
the journal recovers interrupted attempts and the host explicitly chooses what to reload.

Same-stage confirmation requires `ActivationJournal.confirmed()` to preserve `old.previous` when
`old.active == old.pending.stage`. The currently supplied implementation does so. An older journal
that unconditionally sets `previous = old.active` would create duplicate active/previous references
and reject restart confirmation; that journal must be enhanced separately. The coordinator never
bypasses confirmation, reuses an old attempt token or edits journal history to work around it.

No builds or tests were run for this change, as requested. Integration verification remains a host
task, especially cancellation boundaries, failed health, failed stop, same-stage restart and journal
I/O failure behavior on the target filesystem.
