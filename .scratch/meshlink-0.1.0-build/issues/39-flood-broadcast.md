# Flood and sign mesh broadcasts

Status: ready-for-agent
Type: AFK
Blocked by: 21, 36

## Behavior

`broadcast(payload, hopLimit)` sends a mesh broadcast that every reachable node delivers once, in all three profiles. In `Classical` and `PostQuantum` the broadcast is signed, and every secured node verifies the signature before it delivers or relays, so a forged or altered broadcast goes nowhere. This ticket floods in every density class; ticket 40 switches the medium and high classes to Plumtree.

- **Frame:** `BROADCAST` (`0x80`) with the 18 B flood header.
- **`Unsecured` container:** kind `0x04`: the claimed sender NodeId and the payload, unpadded.
- **Secured container (D-40):** kind `0x05`: the origin's 32 B Ed25519 identity key, `u16` payload length 0–1,024, the payload, and a 64 B Ed25519 signature.
- **Signing input:** `DS("broadcast") ‖ frame[0] ‖ frame[2 .. length − 65]`, so relays may lower `hop_limit` without breaking the signature.
- **Sender identity:** the receiving app gets the origin's NodeId, `H("node-id", origin_key)`. The identity key and NodeId come from the identity and certificate machinery of ticket 21; no certificate travels in the frame (D-40 rejected carrying it).
- **Receivers:** a secured node MUST verify before delivering and MUST NOT deliver or relay a broadcast of any other kind. It verifies before relaying, too, as the guarantees page promises. An `Unsecured` node accepts only kind `0x04`.
- **Confidentiality:** none end to end. In a gated mesh, only members see broadcasts because links are encrypted.
- **Flood forwarding:** drop a `message_id` already seen until its expiry; deliver any new, valid broadcast; forward on every other link if the decremented hop limit is at least 1.
- **Cap:** a payload above 1,024 B returns `Rejected(TooLarge)`. Lowering the cap by config is ticket 53.
- **Status:** `Queued`, then `Sent`, then `Expired` at its lifetime. No receipts.
- **Not here:** Plumtree (ticket 40), holding broadcasts (ticket 49), catch-up (ticket 41) and the broadcast-origination toggle (ticket 53).

If the claimed session finds this too big for one PR, it is split before any PR (slicing rule 4) into "Flood `Unsecured` mesh broadcasts" (frame, flood header, kind `0x04`, forwarding, cap, status) and "Sign mesh broadcasts in secured profiles" (kind `0x05`, signing, verification before delivery and relay, tier 2 set), the second blocked by the first.

## Spec and ADR references

- [routing.md](../../../docs/spec/routing.md): [Routing headers](../../../docs/spec/routing.md#routing-headers) (flood header), [Flood forwarding](../../../docs/spec/routing.md#flood-forwarding), [BROADCAST](../../../docs/spec/routing.md#broadcast), [Size classes](../../../docs/spec/routing.md#size-classes).
- [end-to-end.md, Containers](../../../docs/spec/end-to-end.md#containers) (kind `0x04` in `Unsecured`, kind `0x05` per frame in secured profiles).
- [D-29](../../../docs/spec/decisions.md#d-29-duplicate-suppression), [D-40](../../../docs/spec/decisions.md#d-40-broadcast-containers-and-signing).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decisions 7 and 8; [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 3; [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md) decisions 1 and 7; [ADR 0015](../../../docs/adr/0015-message-size-and-compression.md) decisions 2 and 3; [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 1.
- [Security profile guarantees](../../../docs/reference/security-profile-guarantees.md), rows "Mesh broadcast origin authenticity" and "Mesh broadcast confidentiality".
- [Vectors README, Tier 2: pending](../../../docs/spec/vectors/README.md#tier-2-pending) ("Signed broadcasts").

## Acceptance criteria

- [ ] The flood header (unless ticket 38 merged it) and `BROADCAST` codecs pass every matching `routed-frames.json` vector; the kind `0x04` and `0x05` containers decode as specified, and the `broadcast-signing-input` derivation passes `derivations.json`. Each new decoder has a deterministic mutation test.
- [ ] The tier 2 "Signed broadcasts" set passes, including hop-limit changes keeping the signature valid. Ticket 20's Rust reference generates it if still pending.
- [ ] Seeded `VirtualAir` tests, 12 nodes, in `Unsecured`, `Classical` and `PostQuantum`: one broadcast reaches every connected node exactly once, intact, with the origin's NodeId, and no node forwards a `message_id` twice.
- [ ] A seeded test shows `hopLimit` 2 reaching exactly the nodes within 2 hops.
- [ ] A seeded gated-mesh test shows a non-member sees no broadcast content.
- [ ] A 1,024 B payload is sent; a 1,025 B payload returns `Rejected(TooLarge)` with no frame on air.
- [ ] Status goes `Queued` → `Sent` → `Expired` at the lifetime, in virtual time.
- [ ] T10, secured: a flipped payload byte, a flipped signature byte, a swapped `origin_key`, a payload length over 1,024, and kinds `0x04` or `0x01` are neither delivered nor relayed, on the first relay.
- [ ] T10, all profiles: `hop_limit` 0, expired frames, payload length above 1,024 and truncated frames are dropped without state change; an `Unsecured` node drops kind `0x05`.
- [ ] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `broadcast`, `BroadcastOptions` and the received broadcast's sender: the 1 KiB cap, and signing and verification in the secured profiles.
- [Vectors README](../../../docs/spec/vectors/README.md): mark the "Signed broadcasts" set as checked in Kotlin.
- [What each observer learns](../../../docs/reference/observer-exposure.md) only if the tested exposure (content and signing identity) differs from the page.
- `CHANGELOG.md`: `broadcast` entry.

## Devices needed

None. Host and simulator only.
