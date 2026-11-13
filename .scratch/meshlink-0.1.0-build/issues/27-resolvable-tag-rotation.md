# Rotate advertisements and recognize rotated peers

Status: ready-for-agent
Type: AFK
Blocked by: 24

## Behavior

Each node rotates its advertisement every epoch at its own random phase, and linked peers in a gated mesh still recognize it. Everything runs in the sans-IO core on `VirtualAir`; the platform advertising calls come in ticket 32.

- **Epochs (D-02):** epoch = `floor(unix / 300)` as `u32`. A node switches to the current global epoch at its own random instant within each epoch, drawn from the injected `Random`. Receivers accept e − 1, e and e + 1.
- **Per epoch:** a new advertisement identifier, the mesh tag for the new epoch, and the advertised tag below. If ticket 10 already rotates `Unsecured` identifiers, this ticket extends that code rather than adding a second path.
- **Tags:** a gated mesh advertises `resolvable_tag = trunc_8(H("resolvable-tag", u32(epoch); key = resolving_key))` (D-07). Open meshes and `Unsecured` advertise 8 random bytes, fresh each epoch.
- **Resolving keys (gated only):** each node holds a 32 B random key, replaced every 86,400 s. It sends the key in the `RESOLVING_KEY` handshake TLV (ticket 24 only parsed it). It sends the next key on every encrypted link with `RESOLVING_KEY_UPDATE` (`0x0E`, 37 B) at least 3,600 s before using it, and to new links in the handshake once it has one (D-41).
- **Receivers:** store each key with its `not_after`, precompute the tags for e − 1, e and e + 1, and delete the key at `not_after`. A sighting that matches a precomputed tag resolves to the known NodeId: no second peer, and no new dial for that sighting.
- **Open meshes and `Unsecured`:** no resolving keys. A rotated node is recognized only after a new handshake, and peers stay keyed by NodeId. An open-mesh node never sends `RESOLVING_KEY` and ignores both the TLV and the frame.

The `next_id` in `NEIGHBOR_LIST` belongs to ticket 35.

## Spec and ADR references

- [discovery.md, Mesh tag](../../../docs/spec/discovery.md#mesh-tag), [Resolvable tag](../../../docs/spec/discovery.md#resolvable-tag) and [Payload layout](../../../docs/spec/discovery.md#payload-layout).
- [link.md, Handshake payload](../../../docs/spec/link.md#handshake-payload) and [Resolving key update](../../../docs/spec/link.md#resolving-key-update).
- [D-02](../../../docs/spec/decisions.md#d-02-epoch-number-and-acceptance), [D-07](../../../docs/spec/decisions.md#d-07-resolvable-tag-input), [D-41](../../../docs/spec/decisions.md#d-41-resolving-key-update).
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 3, 4 and 7; [ADR 0007](../../../docs/adr/0007-public-api-shape.md) (peers keyed by NodeId).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 1, 2, 9 and 10.

## Acceptance criteria

- [ ] The `epoch`, `resolvable-tag` and `unsecured-tag` derivations in `derivations.json` and the `RESOLVING_KEY_UPDATE` cases in `link-frames.json` pass in the one runner. The new decoder has a deterministic mutation test.
- [ ] A seeded `VirtualAir` test over 24 virtual hours shows each node rotating once per 300 s at its own phase, never in lockstep with its peers.
- [ ] A seeded gated-mesh test shows a linked peer resolving every rotation of its neighbor without a new peer entry or a new dial, across a daily resolving-key change.
- [ ] A test shows the next key is sent at least 3,600 s before use, and that a peer linked after that moment gets it in the handshake.
- [ ] A test shows a key deleted at its `not_after` no longer resolves.
- [ ] A seeded open-mesh test shows no `RESOLVING_KEY` TLV or frame on air, random tags that differ each epoch, and recognition only after a handshake.
- [ ] T10: a `RESOLVING_KEY_UPDATE` on a cleartext link, with a wrong length, in an open mesh, or with a `not_after` in the past is dropped without state change. A tag from epoch e ± 2 does not resolve.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [What each observer learns](../../../docs/reference/observer-exposure.md): confirm the rotation and resolvable-tag rows match the behavior.

## Devices needed

None. `VirtualAir` only. Ticket 33 proves rotation on phones.
