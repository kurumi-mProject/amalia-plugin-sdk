# Pinned mutual-TLS invocation transport

This package implements blocking JVM/Android JSSE sockets for the existing
`InvocationRequest` / `InvocationResponse` serializers. It is a minimal subset of
`docs/plugin-platform/05-DEVICES-AND-TRUST.md`, not the complete distributed platform.
It adds no dependencies, custom encryption, network enrollment endpoint or payload
caller field. Only invocation is exported; discovery, streaming, resources,
respond/cancel, delegation, sync and workflow reconciliation are not implemented.

## Host integration

1. Create a durable `PeerTrustStorage`. `load()` must return the current authoritative
   snapshot, or throw if it cannot verify it. `compareAndSet` must atomically and
   durably store exactly the supplied next revision only if the current revision
   matches. Start an empty store at revision zero. Protect the authoritative revision
   against rollback, including restore from backup. Do not implement it as an
   unguarded file write or last-write-wins sync. Use one `PeerTrust` owner per storage
   authority in a process; other writers must trigger `refresh()` on that owner.
2. Obtain the complete leaf-certificate SHA-256 fingerprint through an independently
   authenticated channel (for example, compare fingerprints on both trusted device
   screens under user control). `PeerFingerprint.of(cert)` computes the value;
   the canonical representation is 64 lowercase hex digits without separators.
   After explicit host approval, call `approveOutOfBand(approval, expectedRevision)`.
   The approval records owner, timestamp and evidence/method; these are local audit
   metadata, not claims accepted from the network. Do this independently on both
   endpoints. There is no trust-on-first-use. A fingerprint learned solely from
   discovery or the TLS connection is not proof of approval.
3. Build a host `SSLContext` using standard key/trust-manager factories. Supply the
   local private key/certificate chain and an **explicit host-approved certificate
   trust store**. Trust managers must validate peer chains; never supply a trust-all
   manager or rely on implicit platform roots as enrollment. The transport additionally
   requires an exact enrolled leaf certificate pin after handshake, including for
   TLS-resumed sessions. Both peers must present certificates valid for their role.
   Private keys belong in host-managed secure storage/Android Keystore where available.
   The transport cannot inspect an opaque SSLContext to prove its trust-manager policy;
   correct SSLContext construction is a trusted host obligation.
4. Start the server with a resolved bind address and a fingerprint resolver. The
   resolver must return only a locally registered `RemoteCallerBinding` containing
   `runtime.clientFor(caller)` for that exact caller. The runtime's grants remain
   authoritative. Do not use a broad administrative client or derive identity from
   `request.input`, `extensions`, device names, addresses or publisher strings.
5. The binding's `authorize(caller, request)` must check current host-approved export
   policy, caller grants, provider/contract/operation, expiry, audience and any required
   consent for this request. Default deny. It must restrict allowed operations even if
   the underlying client has additional grants. Bind only a caller whose grant policy
   has been explicitly approved for that peer. Identity/grant administration and policy
   revisions remain local; pairing itself never installs runtime grants. The resolver
   and policy are consulted anew on every request. Changes to the caller mapping that
   must terminate sessions should revoke the peer and require re-enrollment.
6. On the client, use `RemoteInvocationClient.connect(context, resolvedAddress,
   enrolledServerFingerprint, trust)`. Call `invoke(request)` on a host IO worker.
   A client permits one call at a time and exposes `state`; close it with host lifecycle.
   The server is AutoCloseable and starts listening at construction; `localAddress`
   exposes the actual bound port, including when port zero was requested.

Example server wiring (host-owned objects and approved grants already exist):

```kotlin
val server = RemoteInvocationServer(sslContext, bindAddress, peerTrust,
    bindCaller = { fingerprint ->
        callerRegistry[fingerprint]?.let { caller ->
            RemoteCallerBinding(caller, runtime.clientFor(caller)) { boundCaller, request ->
                exportPolicy.permits(boundCaller, request) // fresh, fail closed, host controlled
            }
        }
    },
    onSessionFailure = { failure -> hostAudit.recordTransportFailure(failure.javaClass.name) },
)
val client = RemoteInvocationClient.connect(clientSslContext, resolvedAddress, serverPin, clientTrust)
try {
    val response = client.invoke(request)
    // Process the existing InvocationResult; Accepted is not proof of completed effect.
} catch (unknown: RemoteUnknownOutcomeException) {
    // Record unknown.requestId; verify outcome with domain/provider state before any retry.
} finally {
    client.close()
}
// server.close() when host service/lifecycle ends.
```

## Wire and bounded behavior

Each frame is a signed four-byte big-endian positive length followed by that many
UTF-8 bytes containing one existing invocation request/response JSON value. There
is no additional envelope. `InvocationRequest.protocolVersion` is required to fall
in `Protocol.supportedVersions` before dispatch; there is no automatic downgrade.
The existing invocation response echoes requestId. TLS authenticates and protects
all application bytes. The exact certificate pin, not the host name or IP address,
is endpoint identity; no separate HTTPS hostname matching is performed.

Defaults: 256 KiB/frame, JSON depth 32, eight server sessions, 256 requests/session,
five-second connect and handshake deadlines, 30-second whole request and
five-minute whole session deadlines. Configuration has finite upper limits. Each
server session dispatches sequentially. Excess connections close immediately with
no work queued beyond the session cap. Failed handshakes consume a session slot
only until their deadline. Socket close timers bound slow trickling reads and
blocked writes, not just individual read calls. Session expiry includes handshake
and worker wait. TLS 1.2/1.3 are the only enabled protocol versions.

Strict UTF-8, bounded pre-parse nesting/structure checking, decoded duplicate-key
rejection (including escaped keys), primitive syntax checking, and the existing
serialization APIs reject malformed/unknown fields. Schema validation and effective
grant checks still happen in the runtime. Incoming frame allocation is bounded;
host-provided responses must themselves be bounded before serialization, since
serialization allocates the response string before enforcing the wire cap.

Every accepted request ID is retained until the session closes; duplicate IDs
close the connection before a second dispatch. IDs are bounded to 128 characters.
At the request cap the session closes instead of forgetting replay history.
TLS protects across-session captured-byte replay, but a peer can intentionally
resubmit an application operation in a **new** session. This is not a durable inbox
or exactly-once guarantee. Domain/provider idempotency and durable outcome lookup
are required before intentional resubmission of effects.

All transport, policy, decoding or timeout failures close the session. There are
no retries, automatic failover or reconnects. Once any frame write is attempted,
any missing, invalid, mismatched or no-longer-trusted response raises
`RemoteUnknownOutcomeException` and leaves client state `UNKNOWN`, even if the
server might have rejected before dispatch. This conservatism prevents assuming
that partial socket delivery proves no effect. A valid response retains the runtime's
own result, including `protocol.unknown_outcome` / `VERIFY_OUTCOME` when applicable.

## Revocation and operational limits

`revoke(pin, expectedRevision)` closes all tracked sockets for that peer before
persisting the next revision. A local deny latch remains even on persistence failure;
only successful explicit approval clears it. Restart safety still requires durable
revocation to succeed and authoritative host storage to remain available. New authentication and every dispatch and response
boundary re-read authoritative storage. `refresh()` imports external revisions,
closes sockets for removed peers, and rejects rollback/conflicting revisions.
Storage uncertainty closes tracked sessions and fails closed at the next check.
Call `refresh()` immediately when an external revocation arrives; the transport
has no background cross-device policy feed. Keep revoked pins out of future
approvals unless a new explicit host action authorizes re-enrollment. Retain audit
and tombstones in host storage if the host needs a permanent revocation history.
Certificate replacement/rotation requires a new explicit approval and TLS trust
store update; there is no implicit key/name-based rotation or restoration.

Revocation cannot retract dispatched effects. A policy check and external effect
are not atomic: revocation can race a dispatch already admitted. The host must
also revoke runtime grants and arrange provider cancellation/fencing where needed.
Closing a socket does not prove cancellation of a running provider invocation.
Coroutine timeout requests cooperative cancellation; hostile or noncooperative
in-process provider code can occupy a worker indefinitely. Session/worker counts
remain capped, so this causes denial of service rather than unlimited new workers.
Use process isolation for hard execution termination. Trusted storage, resolver,
policy and audit callbacks must be bounded and nonblocking; JSSE key/trust managers
are likewise trusted code. Timers close sockets but cannot forcibly stop arbitrary
host callbacks, native TLS code or an already started external effect.

The server caps concurrent unauthenticated handshakes but has no per-IP rate limit
or firewall. The host owns exposure, ingress rate limits, bind scope, TLS cipher
policy, certificate issuance/expiry/PKI revocation, logging and secret handling.
Failure callbacks receive exceptions; do not log raw payloads or secrets. TLS
handshake/certificate validation uses standard JSSE, not a home-grown protocol.
The host owns Android INTERNET/network permissions, lifecycle/foreground-service
policy, DNS resolution deadlines, secure persistence and network reachability.
There is no discovery implementation, relay, background availability guarantee,
certificate provisioning UI or online trust synchronization service here.

No builds, compilers or tests were run for this addition, as requested. Integration
must validate JSSE/provider and Android behavior on the developer/CI environment.
