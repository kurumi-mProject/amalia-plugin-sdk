# Durable generic workflow store

`DurableWorkflowStore` implements the local persistence primitives from
`docs/plugin-platform/04-TASKS-DATA-AND-EXECUTION.md`. It is pure JVM (Java 11+ APIs),
uses kotlinx.serialization JSON 1.9.0, and imports no Android or protocol types.
There is no workflow interpreter, scheduler, network sender, schema engine or domain language.

## Opening and execution

Create a private directory on a local filesystem, then call
`DurableWorkflowStore.open(directory, QuestionAnswerValidator { schema, answer -> ... })`.
The directory must already exist. Keep limits stable between opens; smaller limits can reject
existing data. The supplied wall clock defaults to `System.currentTimeMillis`.
All public instance operations are synchronized, including reads, callbacks and fsync.
Only one open instance per canonical directory is allowed within this classloader/process.
Close releases that registration; it does not delete data or force a snapshot.
Do not access the same directory from another process/classloader or modify its contents externally.
There is no cross-process lock or distributed transaction.

Create a `TaskRecord` with initial metadata, immutable version references and input.
The store rejects injected execution state, questions, effects, lease or completion.
Acquire a lease using the current revision and provider generation, save an execution
checkpoint with `RUNNING`, and use the returned revision for the next mutation.
Every mutation is a task-level compare-and-set. Every persisted mutation, including lease
changes and recovery, increments the revision. Reads and returned records are detached
JSON copies, so mutating a returned collection does not change retained state.
Callers must not concurrently mutate arguments while a call is in progress.

`checkpoint` stores opaque JSON and a generic state; it does not decide which business
steps are valid. `replaceProvider` requires a paused task, a strictly higher generation,
no prepared/in-flight/unknown effects, and an explicit migrated checkpoint.
Compatibility and authorization must be checked by the caller.

## Commit and recovery guarantees

Each transaction contains the complete new record, including its checkpoint and outbox.
The append-only journal consists of sequential, zero-padded `.entry` files. Each document
has a SHA-256 checksum; its payload includes the previous entry's payload hash, format
version, sequence and record. The writer serializes to a unique temporary file in the
same directory, calls `FileDescriptor.sync()`, atomically renames it into place, then
forces the directory channel. A successful return means all those operations completed.
There is no fallback to a non-atomic move or to an unforced directory. The filesystem,
OS, storage and JVM must actually honor atomic rename, file fsync and directory fsync.
Unsupported implementations fail with `IO_FAILURE`.

A crash before rename leaves an ignored `.tmp`; a published entry is replayed as a whole.
Replay checks checksums, hash links, sequence continuity, revisions and record bounds;
it never skips a malformed committed entry. Corruption blocks opening the entire store.
Checksums detect accidental damage, not malicious rewriting. Removal of an entire final
journal suffix cannot be detected without an independent trusted high-water mark or backup.
The journal is the authority. A valid or corrupt snapshot never substitutes for missing history.

`snapshot()` atomically publishes a checked `snapshot.json` containing the sequence,
journal hash and current records. Replay always rebuilds from the full journal, so an
unreadable/invalid/oversized snapshot is ignored. Snapshots are backup/projection artifacts,
not a replay accelerator or compaction mechanism. Preserve the full journal in backups.
Stop writers for a consistent directory backup; copying a changing directory is not supported.
Temporary files are ignored and may be removed only while the store is closed.

Any publication failure poisons the instance. No later read or mutation is allowed until
close/reopen. An error can occur after rename and therefore does **not** mean the mutation
was rolled back. Reopen and inspect the durable record instead of blindly resending work.
Recovery forces the directory before exposing recovered state.

On **every** open, all persisted leases are invalidated. Each affected task is durably
revised before open returns. Every `DISPATCHED` effect becomes `UNKNOWN`; a nonterminal
affected task becomes `RECONCILING`. Paused tasks with a dispatched effect also become
`RECONCILING`; this does not dispatch anything. Terminal states remain terminal. Recovery
may itself fail if storage/quota cannot admit these records; no partly recovered instance
is returned. A subsequent open continues from the durable journal.

## Leases and fencing

Acquisition increments the persisted per-task fencing counter and binds the lease to the
provider generation, owner and wall-clock expiry. A current unexpired lease prevents
another acquisition. Execution mutations require expected revision, exact fencing token,
matching generation and `now < expiresAtMillis`. Release and pause clear the lease;
resuming requires a fresh acquisition. There is no implicit renewal; release/reacquire
uses a higher token. Tokens are task-scoped and are never reset by provider replacement.

Wall-clock rollback can prolong a live lease and forward jumps expire it early. This
implementation deliberately uses wall-clock expiry, not monotonic duration guarantees.
Restart invalidation prevents reuse of persisted leases after a clock/reset boundary.
Successful authorization happens at the operation's lease check; a long disk write may
finish after expiry, but the monitor prevents another acquisition during that operation.
External services must check fencing themselves to reject stale sends. A local lease
cannot prevent an old worker from sending a previously authorized external request.

## Effects and completion

1. `prepareEffect` durably saves `PREPARED` intent together with the checkpoint. The
   caller supplies the operation, recipient, request hash and idempotency key, scoped
   to its task/step/logical generation. IDs and keys cannot be reused within a task,
   including with a different hash. The store does not calculate a request hash.
2. `dispatchEffect` requires `RUNNING` and a valid lease and saves `DISPATCHED`.
   Send only after this call returns successfully. Repeating dispatch is rejected.
3. `resolveEffect` accepts `CONFIRMED`, `REJECTED` or `UNKNOWN` from `DISPATCHED`
   (or reconciliation from `UNKNOWN`). Confirmed/rejected outcomes require nonempty
   receipt and evidence objects. Unknown outcomes can retain partial receipt/evidence.
   `UNKNOWN` cannot return to prepared/dispatched. No automatic resend exists.

Unknown effects block new preparation/dispatch and moving out of reconciliation.
Reconciliation must establish an explicit fact; the caller decides how to obtain evidence.
Any compensation or replacement intent is a separate authorized effect. Keys are retained
for the entire task lifetime; remote key-retention/idempotency promises are external.
There is no exactly-once external execution guarantee, including the gap after dispatch
commit and before actual send. A restart in that gap conservatively yields `UNKNOWN`.

Pause/cancel prohibit new effects but do not undo prior sends. A lease can be acquired
on a paused/terminal task only if an in-flight/unknown effect needs reconciliation;
its resolution records facts without reviving a cancelled task. Old leases and old
provider generations cannot publish results. A late callback therefore needs a fresh
reconciler lease, rather than bypassing fencing. `complete` accepts only `SUCCEEDED`
or `FAILED`, requires a valid lease and nonempty completion evidence, and rejects open
questions or any effect not confirmed/rejected. Evidence presence is checked, not its
truth. Cancellation records intent and needs no success evidence.

## Questions

`ask` persists a question and `WAITING_INPUT` in the same transaction. Its taskRevision
must match the expected pre-write revision. Question IDs are never reused, versions
must be positive, and schemas remain opaque `JsonObject`s. Schemas and answer values
are size/depth/node bounded; protocol JSON schema classes are not required.

`answer` checks task revision, question version, open status and deadline, then invokes
the supplied validator with detached schema/value objects under the store monitor.
Only the first valid answer is persisted. Invalid answers and validator exceptions leave
state unchanged. Deadline is checked again after validation. Author/channel/key/consent
are recorded, and received time is assigned by the store, ignoring the caller's timestamp.
The validator establishes schema admissibility; identity, authorization, consent semantics
and schema safety are the caller's responsibility. Keep it bounded and side-effect free.
Callback reentry into the store (also from the clock) is rejected. The store cannot enforce
a CPU/time limit on an arbitrary in-process callback.

An exact retransmission of the accepted key, author, channel, value and consent returns
the current record even with the old task revision; the received timestamp is excluded
from this comparison. Another key/value or a different question version is rejected.
All other writes require the current revision. There is no question replacement or
automatic expiration branch: expired unanswered questions remain pending until the
caller cancels or handles them in an external policy. Pause preserves questions; resume
returns to waiting input if any remain open. Multiple open questions keep the task waiting
until all have answers. Question versions and stored step binding prevent ID reuse, while
the caller must ensure its interpreter does not abandon/reinterpret a pending step.

## Bounds, errors and limits

`WorkflowStoreLimits` bounds tasks, retained questions/effects, identifier lengths, JSON
UTF-8 bytes/depth/nodes, serialized record bytes, total current record bytes, committed
journal bytes/entries, and lease duration. Defaults are 1,000 tasks; 128 questions and
512 effects per task; 512 identifier characters; 256 KiB/depth 32/16,384 nodes per JSON
object; 2 MiB per record; 64 MiB current records; 512 MiB/100,000 journal entries; and
5 minutes per lease. Journal/snapshot envelope limits allow up to 8x payload allowance
for JSON escaping. Limits cover retained/serialized data, not a hard process heap cap;
arguments already exist in memory and JSON serialization/copies allocate additional memory.
Replay reads one bounded entry at a time. Total record accounting and copies are linear
in retained data; this is intended for modest single-process workloads.

History is not pruned. There is no deletion, garbage collection, quota reservation for
future receipts, retention expiry or compaction. Stop accepting work before exhausting
quotas/disk and retain room for recovery/receipts. At the limit, opening can fail if
recovery needs journal space. External backup/export/migration tooling is required for
long-lived installations; never delete committed entries to free space.

Failures use `WorkflowStoreException` with a fixed `Code` (revision conflict, stale lease,
invalid answer, evidence required, limits, corrupt store, I/O, etc.). Messages contain
only the code; causes/payloads are not attached. The implementation emits no logs.
Stored JSON is plaintext: use a private access-controlled directory and secret references.
This is not an encryption, sandbox, permissions, secret-redaction or authenticity boundary.
VM-fatal failures such as out-of-memory cannot be converted reliably to structured errors.

The wider document also proposes children, inbox, artifact storage, schedules, budgets,
permissions, UI and package migration. Those are intentionally outside this store's API.
No tests are added by this implementation; fault-injection and filesystem-specific power-loss
validation remain necessary before relying on it in production.
