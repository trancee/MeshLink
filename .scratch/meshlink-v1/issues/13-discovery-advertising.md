# How do nodes discover each other, and what goes in the advertisement?

Type: prototype
Status: resolved
Blocked by: 02, 08, 12

## Question

Prototype advertising/scanning: identifier format and rotation (tracking resistance), mesh membership filter, iOS background overflow-area behavior, Android↔iOS visibility, legacy vs extended advertising, duplicate-link resolution between two nodes. Decide the discovery design.

## Scope additions (from research, 2026-10-31)

- Can Android see an iOS app advertising in the background (overflow area)? Does an iOS 26 Live Activity help advertising or only scanning? Is 'both iPhones backgrounded' an accepted degraded mode?
- iPhone X / SE 2016 see no extended adverts; Coded PHY reception on iOS unknown.
- Can tiny presence/summary messages ride in advertising packets across platforms?
- Google Play: does a phone-to-phone mesh qualify for the connectedDevice foreground-service type?
- From the [threat model](08-threat-model.md): in the secured profiles, every on-air identifier (advert payload, readable GATT values, on-air routing tags) rotates in step with the BLE address and can't be linked across rotations. A gated mesh's on-air identifier is derived from the mesh key and rotates. The service UUID, and possibly an open mesh's identifier, may remain visible.
- From the [routing decision](14-routing-topology-simulation.md) (ADR 0008): advertisement identifiers and routing tags rotate every 15 min in step with the OS-controlled BLE address. Decide how MeshLink detects or matches that rotation on Android and iOS, and verify the 15 min figure on both. Per class, scan every 2 / 4 / 6 s with fast / fast / slow advertising. The density estimator counts distinct advertisement identifiers in a 10 s window, so identifiers must be distinguishable per node within a window. An iPhone at its incoming-link cap must stay discoverable, or the cap must be lower (ADR 0009 device sweep).
- From the [bearer decision](12-bearer-phy-bakeoff.md) (ADR 0009): an iPhone stays discoverable with at most 3 incoming links on iOS 15/16; iOS 17+ held 6. Investigate why the iPhone 15 closed incoming links at 3–4 in step 4 and whether the iOS 15/16 limit follows the OS or the hardware. Apply the scanner rules (paired start and stop, no leaked registrations; see library rules).

## Plan (accepted 2026-11-02)

1. **Desk research** from primary sources: iOS background advertising and the overflow area, iOS 26 Live Activities, BLE address rotation timing on Android and iOS, Google Play's connectedDevice foreground-service rules. Lands in `docs/research/2026-11-02-discovery-advertising.md`.
2. **Device prototype** extending `prototype/bearer-bakeoff`: advertisement format and rotating identifiers, foreground and background visibility across Android ↔ iOS, legacy vs extended advertising, detecting the BLE address change, and the unexplained iPhone 15 incoming-link closes.

**Accepted defaults:** both iPhones in the background not finding each other is a documented degraded mode unless research finds a workaround. The v1 advertisement fits legacy advertising (31 B); extended advertising is optional. Device steps run before the free Apple profiles expire (about 2026-11-07), or the phones are re-signed.

## Decisions so far (accepted 2026-11-02)

- **Advertisement layout (owner):** legacy advertising with a fixed 32-bit service UUID `0x4D455348` ("MESH") plus one rotating 128-bit service UUID carrying 16 B of metadata, on both platforms (Android 27 of 31 B; iOS 24 of 28 B). No local name, no scan response needed. `0x4D455348` is not assigned by the Bluetooth SIG; record that as a deliberate choice. Device verification in progress on `prototype/discovery`.
- **Recognizing a rotated peer:** a rotated advertisement identifier never creates a second `Peer`; peers are keyed by `NodeId` from the handshake (ADR 0007).
  - **Gated meshes:** each node has a resolving key, rotated daily, handed to a peer only inside the authenticated link handshake. Part of the rotating UUID is a tag computed from that key and the current epoch. Peers precompute expected tags per epoch, so a sighting resolves with a table lookup. Uses libsodium only.
  - **Open meshes and `unsecured`:** no resolving keys (anyone can link and would then track passively). A node is recognized only after a handshake.
  - The bridge rule (ADR 0008) skips identifiers that resolve to known peers.
- **Threat-model amendment (lands with this ticket's ADR):** in a gated mesh, peers you have linked with can recognize you across rotations until your resolving key rotates. A leaked mesh key alone does not let anyone track members passively.
- **Stated limitation:** if the BLE address and the advertisement identifier change at different moments, a nearby tracker can bridge the rotation. iOS gives apps no control over the address, so unlinkability there is weak in practice. D1 measures the size of the gap.
- **Pending device data:** Android rotates its address every 7–15 min (AOSP), so ADR 0008's 15 min identifier rotation cannot stay in step there. Decide the rotation period after D1. The density estimator may count a rotating node twice for up to one 10 s window per rotation (about 3% at 5 min); fold that into the estimator.

## Device verdict and decisions (accepted 2026-11-02, after the device runs)

Evidence: `docs/evidence/2026-11-discovery/README.md`; prototype branch `prototype/discovery` (never merged). Decision record: ADR 0010, with amendments to ADRs 0001, 0008 and 0009.

1. Rotate every 5 min; Android starts a new `AdvertisingSet` each epoch (address and identifier change in the same packet, 264/264). Routing tags use the same epoch (ADR 0008 amended from 15 min).
2. iOS uses the same period; iOS rotations are linkable by a continuous observer (0/93 address changes on restart). Stated in the threat model.
3. Backgrounded iPhones carry no identifier: connect and read it over GATT, counted against the bridge rule's 60 s limit. Background ↔ background iOS (62%) is a degraded mode.
4. The stale iOS scan response (≥ 26 min after stop) is documented; a failed connect marks the address stale. No workaround that advertises another UUID.
5. Background iOS scanners restart the scan every 10 s (118/122 re-reports).
6. No Coded PHY in v1; extended advertising optional and off by default.
7. iPhone 15 incoming-link closes: not reproduced (0 of 3); the `bluetoothd` idle-pipe lead stays for ticket 17 and later.
8. T1 and T4 reran on the 12 mini (iOS 27): same layout bytes as iOS 15–18. With a Live Activity, background scanning matched foreground (recall 1.0, unfiltered); advertising still fell back to the overflow area after about 33 s.
9. Live Activity: an opt-in hook plus a how-to guide in v1; the host app owns the activity.

## Answer

Recorded in [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md), with amendments to ADRs 0001, 0008 and 0009 and the evidence in [the discovery evidence folder](../../../docs/evidence/2026-11-discovery/README.md). The prototype stays on `prototype/discovery`.

- **Advertisement:** legacy 1M, a fixed 32-bit `0x4D455348` ("MESH") plus one rotating 128-bit UUID with 16 B of metadata (mesh tag, resolvable tag in gated meshes, flags, random). Extended advertising optional and off; no Coded PHY.
- **Rotation:** every 5 min, own phase per node; Android starts a new advertising set so address and identifier change together. iOS rotations are linkable by a continuous observer (stated limitation).
- **Recognition:** gated meshes use a per-node resolving key, rotated daily and shared only inside the handshake; open and `unsecured` meshes recognize peers only after a handshake.
- **Background:** backgrounded iPhones carry only the MESH overflow bit; MeshLink connects to read the identifier, rate-limited by the bridge rule. Background iOS scanners restart every 10 s. Live Activity is an opt-in hook.
- **Limitations:** the stale iOS scan response (≥ 26 min after stop), background ↔ background iOS at 62%, undocumented Apple overflow format.
