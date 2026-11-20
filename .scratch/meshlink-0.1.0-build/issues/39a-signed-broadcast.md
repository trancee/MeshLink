# Sign mesh broadcasts in secured profiles

Status: done
Type: AFK
Blocked by: 39

## Behavior

Split from ticket 39 (Q1). In `Classical` and `PostQuantum`, `broadcast` signs each mesh broadcast, and every secured node verifies the signature before it delivers or relays one, so a forged or altered broadcast goes nowhere. Ticket 39 floods and decodes; this ticket adds the signature.

- **Secured container (D-40):** kind `0x05`: the origin's 32 B Ed25519 identity key, `u16` payload length 0–1,024, the payload, and a 64 B Ed25519 signature. Ticket 39 decodes it.
- **Signing input:** `DS("broadcast") ‖ frame[0] ‖ frame[2 .. length − 65]`, so relays may lower `hop_limit` without breaking the signature.
- **Sender identity:** the receiving app gets the origin's NodeId, `H("node-id", origin_key)`. The identity key and NodeId come from the identity and certificate machinery of ticket 21; no certificate travels in the frame (D-40 rejected carrying it).
- **Receivers:** a secured node MUST verify before delivering and MUST NOT deliver or relay a broadcast of any other kind. It verifies before relaying, too, as the guarantees page promises.
- **Confidentiality:** none end to end. In a gated mesh, only members see broadcasts because links are encrypted.
- **Status:** as in ticket 39; a secured broadcast now leaves `Queued` like an `Unsecured` one.

## Spec and ADR references

- [routing.md, BROADCAST](../../../docs/spec/routing.md#broadcast).
- [end-to-end.md, Containers](../../../docs/spec/end-to-end.md#containers) (kind `0x05` per frame in secured profiles).
- [D-40](../../../docs/spec/decisions.md#d-40-broadcast-containers-and-signing).
- [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 1.
- [Security profile guarantees](../../../docs/reference/security-profile-guarantees.md), rows "Mesh broadcast origin authenticity" and "Mesh broadcast confidentiality".
- [Vectors README, Tier 2: pending](../../../docs/spec/vectors/README.md#tier-2-pending) ("Signed broadcasts").

## Acceptance criteria

- [x] The `broadcast-signing-input` derivation passes `derivations.json`.
- [x] The tier 2 "Signed broadcasts" set passes, including hop-limit changes keeping the signature valid. Ticket 20's Rust reference generates it if still pending.
- [x] Seeded `VirtualAir` tests, 12 nodes, in `Classical` and `PostQuantum`: one broadcast reaches every connected node exactly once, intact, with the origin's NodeId, and no node forwards a `message_id` twice.
- [x] A seeded gated-mesh test shows a non-member sees no broadcast content.
- [x] T10, secured: a flipped payload byte, a flipped signature byte, a swapped `origin_key`, a payload length over 1,024, and kinds `0x04` or `0x01` are neither delivered nor relayed, on the first relay.
- [x] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `broadcast` and `ReceivedMessage.sender`: signing and verification in the secured profiles.
- [Vectors README](../../../docs/spec/vectors/README.md): mark the "Signed broadcasts" set as checked in Kotlin.
- [What each observer learns](../../../docs/reference/observer-exposure.md) only if the tested exposure (content and signing identity) differs from the page.
- `CHANGELOG.md`: signed broadcasts entry.

## Devices needed

None. Host and simulator only.

## Outcome

- **Shipped in #97.** `Classical` and `PostQuantum` nodes sign each `BROADCAST` as kind `0x05` with their installation identity; every secured node verifies before it delivers or relays, and `ReceivedMessage.sender` is the NodeId of `origin_key`.
- **Order:** freshness before verification, so duplicates cost no signature check; a `message_id` is remembered only after the frame is carried, so a forgery can't shadow the genuine broadcast.
- **Code:** `BroadcastFrame.sign`, `verifiedOrigin`, `signingInput` and `Domain.BROADCAST` in core; the internal `BroadcastCodec`, used by `Floods` and `Broadcasts`; `UnsecuredEnd.broadcast` removed.
- **Vectors:** tier 2 `broadcast-signatures.json` (12 tests) from `tools/reference/src/broadcast.rs`, recomputed by `check_spec.py` and checked in Kotlin with the `broadcast-signing-input` derivation.
- **Docs:** the guarantees, observer-exposure and threat-model pages already matched the tested exposure; the public ABI didn't change.
