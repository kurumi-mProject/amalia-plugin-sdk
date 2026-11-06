# Package archive verification and staging (format v1)

This is a pure JVM implementation of the verification/staging portion of
`docs/plugin-platform/03-RUNTIME-AND-PACKAGES.md`. It uses `kotlinx-serialization-json`
without generated serializers, Android APIs or the platform protocol module.
It never loads code, interprets declarations, activates, installs, resolves dependencies,
checks ABI compatibility, performs migrations, replaces an existing package or grants access.

## Exact archive format

A ZIP contains regular file entries only, using STORED or DEFLATED compression.
Do not emit explicit directory records. The two reserved root names are:

* `platform.json`: required, strictly valid UTF-8 JSON (no BOM).
* `package.sig`: optional detached signature envelope, strictly valid UTF-8 JSON.

All other entries are payload. Parent directories are created by the stager.
ZIP symlink/Unix mode metadata is never restored or followed: every accepted entry becomes
an ordinary file. No nested archive is unpacked or activated. External ZIP attributes,
comments and extra fields convey no package semantics.

Paths are case-sensitive, relative, ASCII and use `/`. Allowed characters are
`A-Z a-z 0-9 . _ - /`. Empty segments, `.`/`..`, trailing dots, Windows device-name
segments, absolute paths, drive prefixes, backslashes, duplicate names, case aliases and
file/parent collisions are rejected. Reserved filenames must use the exact lowercase spelling.
Default path limits are 240 characters and 16 segments.

The manifest has exactly five required fields and one optional field. Unknown fields,
nulls, non-string scalar values, duplicate JSON members (including escaped aliases), arrays
and extra nesting are rejected. This deliberately small schema is not the full future manifest.

```json
{
  "packageId": "org.example.weather",
  "version": "1.0.0",
  "publisher": "org.example",
  "runtime": "declarations",
  "sha256": {
    "data/empty.json": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
}
```

Here `data/empty.json` is a zero-byte file; the extension has no semantic effect.
An empty inventory and no payload files is also valid.

* `packageId` and `publisher`: at most 200 characters, lowercase alphanumeric segments
  separated by `.` or `-`, with at least one separator. Pattern:
  `[a-z0-9]+(?:[.-][a-z0-9]+)+`.
* `version`: opaque, case-sensitive token, not a SemVer compatibility claim;
  `[A-Za-z0-9][A-Za-z0-9._+-]{0,127}`.
* `runtime`: `[a-z][a-z0-9.-]{0,63}`. Only `declarations` is classified as declaration-only.
  Every other value is code-bearing for signature policy, including unknown future runtimes.
  Recognition here does not mean the host supports that runtime.
* `entryPoint`: omitted for `declarations`; required for every other runtime.
  It is a symbolic identifier (maximum 512 characters), matching
  `[A-Za-z_][A-Za-z0-9_.$]*(?:::[A-Za-z_][A-Za-z0-9_]*)?`.
  It is not executed or resolved by this module.
* `sha256`: object from exact payload path to 64 lowercase hex SHA-256 characters.
  It must cover **every** file except `platform.json` and `package.sig`, with no extras.
  Hashes are over uncompressed raw file bytes.

## Publisher signatures and trust

`package.sig` has exactly these three string fields:

```json
{
  "algorithm": "SHA256withECDSA",
  "keyId": "release-2026",
  "signature": "BASE64_OF_DER_ECDSA_SIGNATURE"
}
```

The signature is SHA256withECDSA using **NIST P-256 / secp256r1**, over the exact
raw bytes of `platform.json`, including whitespace and its final newline if present.
No canonicalization is performed. ECDSA signature bytes use ASN.1 DER (not P1363),
8–72 bytes, encoded as canonical padded standard Base64 without whitespace.
`keyId` is a host pin identifier matching `[A-Za-z0-9._-]{1,128}`.

Create a signature, for example, with
`openssl dgst -sha256 -sign publisher-private.pem -out manifest.der platform.json`,
then put the Base64 of `manifest.der` into the envelope. Do not put the private key
or `manifest.der` in the package unless deliberately treating them as inventoried payload.
Generate host public-key DER with
`openssl pkey -pubin -in publisher-public.pem -outform DER -out publisher-public.der`.

The host supplies `PinnedPublishers(mapOf(publisher to mapOf(keyId to publicKey)))`.
`PinnedPublishers.p256PublicKey(der)` decodes an X.509 SubjectPublicKeyInfo public key.
Pins are snapshotted and validated as P-256; no key from an archive is trusted.
Key provisioning, rotation, revocation and publisher ownership policy belong to the host.
SHA256withECDSA avoids relying on Ed25519 availability on Android API 26.

Code-bearing packages **must** have a valid signature using a pinned publisher/key pair.
Unsigned declarations return `PublisherIdentity.Unverified(claimedPublisher)` even if the
claimed name happens to have host pins. A present but malformed, invalid or unpinned
signature rejects **any** package, including declarations; it never downgrades to unsigned.
A verified result returns `PublisherIdentity.PinnedSignature(publisher, keyId)`.

The inventory alone only detects inconsistency. For unsigned packages this module makes
**no claim of manifest authenticity, publisher identity or independent content authenticity**:
an attacker can replace the manifest and its hashes together. A valid pinned signature
binds the manifest and inventory to that key, not to safety, authorization or compatibility.
A declarations label is a policy classification, not proof of harmless content. A host must
never execute bytes from an unsigned declaration package or treat embedded expressions as
arbitrary code; declaration interpretation needs its own schema and restricted evaluator.

## Limits and errors

`PackageLimits` defaults: 64 MiB archive, 2,048 entries, 32 MiB per file,
128 MiB total expanded content, 256 KiB manifest, 4 KiB signature envelope and
200:1 maximum per-entry expansion ratio. Metadata counts toward total and per-file limits.
Advertised sizes/ratios are checked before extraction; actual streamed bytes, CRC32,
actual per-file size and SHA-256 are checked while reading. No whole payload is held in memory.
The central-directory/file list and hashes are bounded by archive size and entry count.
ZIP parser/IO/validation failures throw `PackageVerificationException` (an `IOException`);
store filesystem errors may also surface as ordinary IO exceptions. Invalid host configuration
uses `IllegalArgumentException`. Failed verification returns no trusted result.
Limits bound sizes, not CPU time or blocked I/O; callers control input source and cancellation.

## API and transactional store

```kotlin
val publishers = PinnedPublishers(
    mapOf("org.example" to mapOf("release-2026" to PinnedPublishers.p256PublicKey(publicKeyDer)))
)
val verifier = PackageArchiveVerifier(publishers = publishers)
val inspected = verifier.verify(archivePath)
val store = StagedPackageStore(privateStoreRoot, verifier)
val result = store.stage(archivePath) // or stage(inputStream); caller owns inputStream
// result.verification.identity explicitly distinguishes signed from unsigned.
// result.contentDirectory holds files; result.directory/archive.zip retains the snapshot.
```

`verify(Path)` requires an immutable regular archive for the duration of the call; its result
is a point-in-time inspection, not an executable handle. `stage` first copies at most the archive
limit to a private snapshot, then extracts and verifies that snapshot in
`transactions/<random UUID>/content`. After successful verification, a single
`ATOMIC_MOVE` publishes the entire transaction to `staged/<UUID>`. It fails if atomic
rename is unsupported; there is no copy fallback. Unique stage IDs avoid version/publisher
path collisions or implicit replacement. A crash before rename leaves only an incomplete
transaction; a crash after rename leaves a complete stage. Atomic visibility is not an fsync
or power-loss durability guarantee. Failures attempt cleanup and preserve cleanup errors as
suppressed exceptions. No persistent verification result is trusted across restarts.

`stageIds()` enumerates completed stages, `stageDirectory(id)` locates one, and
`verifyStaged(id)` re-verifies its retained archive with current limits and pins. This does
**not** rehash its already-extracted `content` directory. That content must remain immutable
and app-private; recreate a fresh stage from the retained archive if that assumption changes.
The archive and extracted content intentionally cost additional disk space.
`discardIncompleteTransactions()` removes abandoned transactions; invoke it only under
exclusive host access, with no stage operation in progress. Cleanup never follows symlinks.

The root, its ancestor chain, transactions and committed stages must be on a trusted private
filesystem with no untrusted writers. The store rejects symlink roots/subdirectories but does
not defend against a privileged concurrent writer replacing their ancestors. It is not a
cross-process locking system, immutable filesystem or tamper-resistant database.
No files are made executable and no ZIP permissions are restored. Signatures must be rechecked
under current policy before later authorization; staging never authorizes loading, permission
grants, installation, replacement of another publisher, or Android store-policy compliance.

## Structured metadata and host transactions (item 3)

The description above of the original minimal schema applies to archive verification/staging.
The optional structured extensions below are now parsed by that verifier. Existing manifests still
parse; parsing alone never authorizes execution. The installer, coordinator and the state stores
now integrate compatibility, resolver planning, namespace migration and rollback. These additions
supersede the old statements in INSTALLER.md/COORDINATOR.md that these features are absent.

```json
{
  "compatibility": {
    "protocol": {"minimum": "1", "maximumExclusive": "2"},
    "runtimeVersion": {"minimum": "1", "maximumExclusive": "2"},
    "abi": "host-published-exact-abi-token"
  },
  "dependencies": [{
    "id": "weather", "contractId": "org.example.weather",
    "versions": {"minimum": "1", "maximumExclusive": "2"},
    "features": ["forecast"], "optional": false,
    "providerId": "org.example.weather.primary"
  }],
  "offers": [{
    "providerId": "org.example.consumer.primary",
    "contracts": [{"contractId": "org.example.consumer", "version": "1", "features": []}]
  }],
  "conflicts": ["org.example.conflicting-package"],
  "data": {
    "schema": "v2", "readableSchemas": ["v2"],
    "migrations": [{"from": "v1", "to": "v2", "operations": [
      {"namespace": "settings", "operation": "rename", "key": "old", "destination": "new"},
      {"namespace": "data", "operation": "set", "key": "format", "value": "2"}
    ]}]
  }
}
```

These are optional fields on the existing manifest, not a standalone manifest. Structured ranges
have inclusive minimum and optional exclusive maximum, using protocol `Version` numeric parsing.
`abi` is exact equality, with no inferred binary/Compose/compiler compatibility. Host must publish
an ABI token that accounts for its SDK/compiler/Compose/R8 policy. Offers require the package
identity version to also parse as a numeric protocol version; versions without offers remain opaque.
Dependencies have required id/contractId/versions; features, providerId and optional default absent.
All metadata objects reject unknown fields. Arrays are at most 128 elements, nesting at most 10,
JSON nodes at most 16384, object members at most 2048, and raw JSON at most 256 KiB (the supplied
manifest byte cap can further reduce this). Duplicate decoded JSON members, dependency/provider IDs,
contract/version pairs, set members and migration `from` schemas are rejected. Tokens are ASCII
`[A-Za-z0-9][A-Za-z0-9._:+/-]{0,199}`. Only optional booleans are scalar nonstrings; numbers and
nulls are rejected. Existing ZIP/inventory limits still apply. No migrations execute while parsing.

### Required host composition

Append `hostPolicy = PackageHostPolicy(...)` to the existing `PackageInstaller` constructor. Its
original four arguments and all UI-facing methods remain source compatible. Null policy permits
import/history/disable/shutdown, but rejects review/activation. Policy supplies:

* `protocolVersion`, exact runtime ID → numeric version, runtime ID → exact ABI token, device ID;
* `PackageProfileStore(existingPrivateProfileDirectory)` and
  `PackageDataStore(existingPrivateDataDirectory)`;
* `candidates: () -> List<ActivationCandidate>` containing verified currently usable host and
  other-package providers **and all affected consumer requirements**. Include builtin providers.
  Never use marketplace inventory or stages that are not usable. The candidate being replaced
  is removed by package ID and its manifest offers/requirements inserted before resolver execution;
* optional `allowLegacyDeclarations = true`. Default false rejects missing compatibility metadata.
  Even with true, code-bearing packages always need explicit compatibility metadata.

Preflight runs during review, refresh and again inside coordinator serialization before journal
begin or old-provider withdrawal. Missing required dependencies, ambiguity, required startup cycles,
conflicting package IDs, incompatible protocol/runtime/ABI or changed exact bindings reject before
withdrawal. Optional dependency failures remain visible in `review.activationPlan`. The resolver
plans contracts; it does not grant calls, install/start missing dependencies or execute an entire
activation plan. Host inventory must exclude unusable providers and must truthfully describe actual
loader registrations. Dynamic provider advertisements alone are not a verification source.
The host must also serialize external runtime/profile mutations with installer use; callbacks must
not reenter installer/coordinator. Constructors do not execute anything.

`chooseProfile(SelectionProfile, expectedRevision)` atomically persists explicit contract→provider
choices with CAS; `chosenProfile()` returns revision + bindings. Reviews pin that revision and plan,
and activation rechecks both. Profile editing is explicit and makes outstanding reviews stale.
Journal stage selections pin package bytes/versions; a newly staged/downloaded version never
changes a selected profile or running package automatically. This is one host profile, not a
multi-profile catalog. This implementation does not inject bindings into CoreRuntime automatically:
**host consumers must use the returned chosen profile when selecting providers**. A successful
single-package activation confirms that package's journal selection after health; no provider grant
or automatic multi-package ordering execution is implied by the plan.

Direct public coordinator activation methods retain their signatures but reject without the
installer's internal policy transaction; use `installer.review/activate` or `reviewSelected/
resumeSelected`. This prevents bypassing migration and compatibility preflight. Coordinator disable
and shutdown retain their existing behavior. The old low-level journal.rollback remains metadata
administration only and must not be used behind this installer's data lifecycle.

### Namespaces and migration transaction

PackageDataStore provides host-mediated string KV namespaces `data` and `settings`. It does not
migrate arbitrary files, SharedPreferences, FileResourceStore resources, grants, credentials or
external systems. Do not put secrets/system grants in synchronized package settings.
Host code may create `data.namespace(packageId, "settings", authorize)` and the equivalent data
facade for a package. Its host authorization callback is checked on every operation under the
store monitor. `PackageNamespace` implements the public Android-free `StorageNamespace` interface.
`CoreRuntime` injects `PluginContext.storage` through an optional owner/generation factory, denying
by default, and holds its lifecycle monitor through each namespace operation. Startup/health are
supported before coordinator.live appears; withdrawal revokes access before stop or migration.
PlatformHost binds only the exact journal startup identity (publisher/package/device and fresh
instance token), and rechecks that journal attempt or its confirmed selection on every call.
Its explicit private-owner policy allows reads and, by default, writes/deletes; the trusted host
may restrict mutations with `packageStorageWrites`. Contract invocation grants are unrelated to
this private KV authority. No administrative store, namespace root or installer reaches packages.
Lock order is runtime -> data store -> journal; callbacks must not reverse it. In-process DEX
remains trusted code with process privileges, not a filesystem security sandbox.

Each namespace has at most 1024 keys, each UTF-8 value at most 16 KiB; combined live keys/values
are at most 2 MiB. One bounded state image (at most 16 MiB with snapshot and transaction images)
is atomically renamed after file fsync, then directory fsync. Images have checksums, strict bounded
binary decoding and deterministic ordering. One host instance exclusively owns each directory;
these stores do not implement cross-process locking. Roots and ancestors must remain app-private,
symlink-free and free of other writers. No non-atomic rename fallback exists. Indeterminate writes
poison the store object: withdraw code, reopen stores/journal and recover before reload. Checksums
detect corruption, not malicious host filesystem rewriting. There is no unbounded snapshot history.

Migration preflight simulates bounded declarative operations on a copy. After durable journal begin,
the coordinator fully withdraws/stops the old generation. Only then does the store atomically publish
new data+settings, the old image snapshot and a pending transaction intent tied to target stage and
starting journal revision. The loader starts only after that publication. Every migration path is
explicit by `from` → `to`, at most 128 steps, with cycle detection. Supported operations are `set`
(with string value), `delete` (source required), `copy`/`rename` (source required, destination absent).
They apply only to the selected namespace; collisions, missing sources and quota violations reject.
A readable older schema can remain at its actual schema version; claiming readability never silently
rewrites schema. Empty unversioned storage may initialize to the declared schema; existing nonempty
unversioned storage requires a host-managed import and is rejected, not silently adopted. Packages
without data metadata may only use unversioned storage. Current schema must be in readableSchemas.

The candidate's start/health writes are included in the same live image, while the old snapshot
remains fixed. Health success and journal.confirm precede clearing the data transaction intent and
publishing coordinator.live. Startup/health failure withdraws/cleans the candidate before restoring
the exact old data AND settings. Cleanup failure is reported; the host must revoke generation leases
so late writes cannot corrupt restored data. No old package is automatically reloaded. A failed stop
before migration leaves data unchanged.

After process death, installer review reconciles data only when no package is live. A pending image
is retained only if journal state proves confirmation of that exact stage after the transaction's
revision (not merely historical selection of the same stage); otherwise the old image and old
rollback snapshot are restored together. A poisoned/uncertain journal must reopen successfully first.
No recovery code executes package bytes. Because this is a coordinated protocol across separate
files/runtime, not one ACID commit, an uncertain fsync is an error requiring reopen/reconciliation;
it must never be presented as successful live service.

### Explicit recovery rollback

Recovery host calls `reviewRollback(packageId)`, shows/approves that exact review, then calls
`rollback(review, dataCompatible = true)`. The assertion is mandatory in addition to metadata checks.
Review is action-bound and cannot be submitted through regular activate. Previous-stage bytes,
current publisher pins, runtime/ABI/protocol and dependency plan are reverified. A retained snapshot
must match that exact previous stage and its schema must be explicitly readable by the target
manifest (unversioned target accepts only unversioned snapshot). Missing snapshot or incompatible
schema rejects before withdrawal. Rollback never guesses that backwards code compatibility implies
data compatibility, and never selects the previous code against the newer image.

After withdrawal it transactionally restores the matching snapshot, loads a fresh generation and
requires the normal health probe before confirmation. Failed rollback restores the pre-rollback
current data/settings and old snapshot; no failed candidate is reported live. Successful rollback
keeps a snapshot of the version it replaced, permitting an explicitly reviewed reverse transition.
Snapshots cover these KV namespaces only. External effects, other databases/files and grants are
not restored. If a historical pre-item3 activation has no matching snapshot, this API refuses rollback.

No tests, builds, compilers or item8 acceptance were run for this change.
