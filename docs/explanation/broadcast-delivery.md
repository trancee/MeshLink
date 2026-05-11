# Mesh-Wide Broadcast Delivery

**Audience:** an engineer extending, verifying, or operating the bounded mesh-wide broadcast surface in M002.

**After reading, you should be able to:** explain what the public broadcast API promises, what the runtime actually does for fan-out, which diagnostics and proof surfaces expose delivery/drop state, and where automated proof stops and manual/UAT begins.

## What S06 adds

S06 adds an explicit broadcast surface to the public SDK instead of overloading direct send semantics:

- `MeshLinkApi.broadcast(payload)` publishes one bounded application payload to the node's currently reachable mesh view.
- `MeshLinkApi.broadcastMessages` delivers inbound mesh-wide application broadcasts to the local consumer.
- `MeshLinkApi.send(...)` and `MeshLinkApi.messages` remain direct-message-only surfaces.

This keeps the public contract additive and observable. A consuming app can tell the difference between a direct encrypted send and a mesh-wide publish without parsing diagnostic prose.

## Delivery model

Broadcast is **sender-driven fan-out** over the existing encrypted direct and routed runtime. The sender reuses the same direct and routed delivery machinery that already exists for unicast, but wraps the application payload in a runtime-private broadcast envelope before encrypting each recipient copy.

That means S06 deliberately does **not** introduce:

- no shared group key
- a separate flooding transport
- a public "relay broadcast" API

Instead, the sender computes the current reachable recipients, encrypts one copy per recipient path, and dispatches those copies through the same trusted transport/runtime stack the SDK already uses.

## Public result contract

`broadcast(payload)` returns a typed `BroadcastSendResult`:

- `Published(attemptedRecipientCount, acceptedRecipientCount)` means at least one recipient copy was accepted for dispatch.
- `NotPublished(reason, attemptedRecipientCount, acceptedRecipientCount)` means no recipient copy was accepted.

The counts matter. They make partial fan-out visible without exposing per-recipient plaintext or transport-private internals.

## Delivery, duplicate, and drop visibility

S06 adds three broadcast-specific diagnostic families:

- `BROADCAST_PUBLISHED`
- `BROADCAST_DROPPED`
- `BROADCAST_DUPLICATE_DROPPED`

These diagnostics are structured payloads, not free-form text. They expose bounded state such as:

- redacted `originSuffix`
- redacted `messageSuffix`
- attempted versus accepted recipient counts
- duplicate-drop `stage`
- bounded drop `stage` and `reason`

The runtime suppresses repeated delivery of the same broadcast copy by tracking duplicates against the broadcast origin plus message id window. Recipients still receive the first valid copy on `broadcastMessages`, but later duplicates are dropped and surfaced as `BROADCAST_DUPLICATE_DROPPED` instead of creating a second app-visible delivery.

## Redaction contract

Broadcast proof surfaces stay redacted by design.

They must not expose:

- full peer ids
- raw message ids beyond suffixes
- plaintext payload bodies
- raw payload bytes
- ciphertext, nonce bytes, or packet captures in durable proof artifacts

Proof surfaces should show only suffix-only identifiers, byte counts, digest summaries, typed result labels, and structured diagnostic fields.

## Proof-surface parity

S06 keeps the same public contract across the proof surfaces:

- the JVM demo shows bounded broadcast publish summaries and bounded inbound broadcast logs
- the Android sample renders broadcast publish outcomes, inbound broadcasts, and duplicate/drop diagnostics with redacted fields only
- the iOS sample renders the same bounded proof shape: a broadcast action, a dedicated inbound broadcast section, and structured broadcast plus continuity diagnostics

In every client, inbound broadcasts stay off the direct-message surface. `Inbound messages` remains unicast-only; broadcast evidence belongs on the dedicated broadcast surface.

## Continuity and failure surfaces that remain relevant

S06 touches the same continuity diagnostic family that earlier M002 slices introduced. The proof clients still need to render these payloads structurally rather than collapsing them into generic fallback text:

- `REPLAY_REJECTED`
- `BLE_STACK_UNRESPONSIVE`
- `DELIVERY_TIMEOUT`
- `L2CAP_FALLBACK`
- `TRANSPORT_MODE_CHANGED`

This matters because broadcast delivery still rides on the existing runtime and transport. A proof client that hides these payloads behind generic fallback text would make broadcast regressions much harder to inspect honestly.

## Automated versus manual/UAT proof

`bash scripts/verify-m002-automated.sh` is the authoritative automated gate for S06. It verifies:

- broadcast API lifecycle behavior
- runtime fan-out and duplicate suppression
- public SDK proof behavior
- JVM demo proof behavior
- Android proof drift
- `ProofIOS` build drift
- runbook and explanation-doc drift

That automated gate is where duplicate suppression is proven precisely.

The **manual/UAT** closeout in [M002/S06 Mesh-Wide Broadcast Delivery Runbook](../runbooks/m002-s06-mesh-wide-broadcast-delivery.md) proves the real multi-device topology behavior that automation cannot honestly claim from CI alone:

- a three-node mesh-wide publish on physical devices
- app-visible fan-out on the proof surfaces
- a truthful fail-closed drop after topology teardown

Manual/UAT must not pretend to prove more than that.
