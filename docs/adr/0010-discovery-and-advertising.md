# 0010: Discovery, advertising and identifier rotation

Status: accepted (2026-11-02; ticket "How do nodes discover each other, and what goes in the advertisement?"). Version: 2.

## Context

Nodes find each other by BLE advertising and scanning before any link exists. The advertisement must work on both platforms in the foreground and background, carry enough to pick useful peers before connecting, and rotate so that a tracker (A5, ADR 0001) cannot follow a node. ADR 0008 fixed a 15 min rotation and left open how MeshLink stays in step with an address the operating system controls. ADR 0009 fixed the legacy 1M advertisement and the iPhone 3-link cap.

Sources:

- [Desk research](../research/2026-11-02-discovery-advertising.md): platform rules, AOSP address rotation (7–15 min), iOS background overflow area, Android 17 scan limits.
- [Device evidence](../evidence/2026-11-discovery/README.md), 2026-11-02 12:14–18:50 CET: five iPhones (iOS 15–27) and up to 22 Android phones (API 26–37). Throwaway branch `prototype/discovery`.

Measured:

| Question | Result |
|---|---|
| Android idle address rotation | Pixel 6 7.1–8.7 min; Samsung 14.1 min; XCover 4 15.0 min; P10 lite alternating 4.1 and 10.9 min |
| Android: new `AdvertisingSet` or stop/start | new address 264 / 264, in the same packet as the new identifier, 0 mixed sightings; dark 2.5–4.3 s |
| Android: data update or enable/disable on a live set | new address 0 / 72 |
| iOS 15–18: stop/start with a new UUID | new address 0 / 93; old and new identifiers share one address for a median of 5.3–7.5 min, up to 22.7 min |
| iOS idle rotation | 11.3–19.3 min; the iOS 15, 16 and 17 phones changed address within 5 s of each other every time (cause unverified) |
| Layout E on air | both UUIDs in the main packet on both platforms; Android 27 B, iOS 30 B including flags and TX power; no OS rejected `0x4D455348` |
| Backgrounded iPhone (iOS 15–27) | no UUID in the packet; only Apple's overflow bitmap (MESH bit set); the rotating identifier is unrecoverable |
| Finding a backgrounded iPhone | iOS foreground MESH scan 21 / 21; iOS background MESH scan 13 / 21; Android MESH filter 0; Android Apple 0x004C filter 0.80–0.97 recall |
| iOS background scanner re-reports after a scan restart | 118 / 122 restarts |
| After the app stops (iOS) | the last scan response (name or bitmap) stays on air for at least 26 min, across address changes |
| Extended advertising | received by extended-capable Android only; by no iPhone and no legacy-only Android |
| Coded PHY | 6 of 15 Android phones that report support received none |
| iOS 27 background scan with a Live Activity (n = 1 per cell) | unfiltered: 3,959 callbacks/min and recall 1.0, the same as foreground (16/min and 0.056 without); advertising still dropped to the overflow area about 33 s after backgrounding |
| Android 17 scan limits (Pixel 6, filtered, 2 h) | no downgrade or suspension |
| Unfiltered Android scan | moved to opportunistic after 5 min (logcat) |

## Decision

1. **Advertisement (layout E):** legacy, connectable advertising on 1M with exactly two service UUIDs and nothing else (no local name, no scan response data):
   - a fixed 32-bit service UUID `0x4D455348` ("MESH"), 128-bit form `4D455348-0000-1000-8000-00805F9B34FB`. The Bluetooth SIG has not assigned it. Using it is deliberate: it fits both platforms' budgets, and no OS rejected it.
   - one rotating 128-bit service UUID that carries 16 B of metadata:
     - a mesh tag, the same for all members in an epoch: from the mesh identifier (open meshes, `unsecured`) or the mesh key (gated meshes);
     - a resolvable tag in gated meshes (decision 4), random otherwise;
     - flags (capabilities);
     - random fill.

     Field sizes, derivations and test vectors belong to the wire-format work (protocol spec, C6/C7). The placeholder tested was 8 B tag, 4 B mesh tag, 1 B flags, 3 B random.
2. **Extended advertising:** optional, off by default. It adds nothing that every phone can receive. Coded PHY is out of v1.
3. **Rotation:** every 5 min, with each node on its own phase. One epoch drives the advertisement identifier and the routing tags (amends ADR 0008 decision 6; the routing simulation found 5 min within budget).
   - **Android:** each epoch starts a new `AdvertisingSet` (stop/start on phones without the API), never a data update. The new address and identifier then appear in the same packet. 5 min is shorter than the measured OS interval on Android 10+ phones.
   - **iOS:** the same 5 min period by stop/start. iOS keeps the address, and the app can neither change nor observe it, so rotations on iOS are linkable (decision 7).
4. **Recognizing a rotated peer:** peers are keyed by `NodeId` after the handshake (ADR 0007), so a rotation never creates a second peer.
   - **Gated meshes:** each node keeps a resolving key, rotated daily. It hands the key to a peer only inside the authenticated link handshake, and sends the next one over the link when it rotates. The resolvable tag is a keyed hash (libsodium, ADR 0004) of the resolving key and the epoch. Peers precompute the tags they expect for the current epoch ±1, so a sighting resolves by table lookup. Peers delete a resolving key when it expires.
   - **Open meshes and `unsecured`:** no resolving keys, because anyone can link and would then track passively. A node is recognized only after a handshake.
   - The bridge rule (ADR 0008) skips sightings that resolve to known peers.
5. **Background iPhones:** a backgrounded iPhone advertises only the MESH bit in Apple's overflow bitmap.
   - iOS scanners find it with a MESH service scan.
   - Android scanners match the Apple manufacturer data (company `0x004C`, type `0x01`) and check the MESH bit. A screen-off scan uses this as a second hardware filter, with the bit as the mask.
   - Such a sighting carries no identifier. MeshLink connects and reads the current identifier from a GATT characteristic. This counts against the bridge rule's limit of one dial per 60 s.
   - A connect that fails, or a phone without the MeshLink service, marks that address as stale; MeshLink does not dial it again until its address changes. That covers other apps' UUIDs that hash to the same bit and iPhones whose app has stopped (decision 7).
   - Background ↔ background discovery between iPhones is a degraded mode: 62% of 40 s trials. Apps that need better keep MeshLink in the foreground.
6. **Scanning rules:**
   - Background iOS scanners restart the scan every 10 s, so the 10 s density window holds (ADR 0008).
   - Android screen-off scans always use hardware filters (MESH UUID, Apple overflow).
   - An unfiltered Android scan restarts at least every 4 min, because Android moves it to opportunistic mode after 5 min.
   - **iOS Live Activity (opt-in hook):** when the host app runs a Live Activity, MeshLink switches its background scan to foreground mode (unfiltered allowed, duplicates on). The app owns the activity, its widget extension and UI; MeshLink does not create one. A how-to guide documents the setup. On iOS 27 (one phone) this gave foreground-level scanning in the background; advertising still falls back to the overflow area after about 33 s. Behavior at the 8 h end and on iOS 26 is unverified.
   - The local density estimate counts distinct advertisement identifiers, plus distinct addresses of identifier-less MESH sightings, in the 10 s window. A node that rotates in the window counts twice for that window (about 3% at a 5 min epoch); the estimator's hysteresis absorbs it.
7. **Stated limitations** (threat model, ADR 0001 amendment):
   - **iOS rotations are linkable** by a nearby observer who keeps watching: each identifier change by the unchanged address, and each OS address change by the identifier. iOS gives apps no control. On Android 8 and 9 phones whose OS rotates faster than 5 min (P10 lite: 4.1 min), an address change can also fall inside an epoch.
   - **A stopped iOS app stays visible:** iOS keeps the last scan response, with the MESH bit, on air for at least 26 min. It leaks that MeshLink ran and links addresses. MeshLink cannot clear it after the app is killed and does not try to work around it.
   - **Gated-mesh recognition:** peers you have linked with can recognize you across rotations until your resolving key rotates, at most a day after your last link with them.

## Alternatives

- **Layout A** (one 128-bit mesh UUID plus 13 B of service data in the scan response; the desk-research recommendation): needs a scan response, which background and passive scanners miss, and iOS apps cannot set service data. Layout E (the project owner's proposal) fits both platforms in the main packet.
- **Two 128-bit UUIDs:** 40 B, over budget; iOS moved the second one to the overflow area.
- **Rotating by data update on Android:** keeps the address, so it is linkable for minutes (0 / 72 address changes).
- **Keeping 15 min** (ADR 0008): longer than Android's 7–9 min idle rotation, so the address would change mid-epoch.
- **Recognition by a tag from the mesh key:** anyone holding the mesh key, including after a leak, could follow every member passively, also in old recordings. Rejected for the per-node resolving key.
- **Resolving keys in open meshes:** anyone can link once and then track passively.
- **Clearing the stale iOS scan response** by briefly advertising another UUID: works only while the app runs, and adds a second UUID's footprint. Rejected.
- **Extended advertising by default:** iPhones and legacy-only phones receive none of it.

## Risks

- **Undocumented Apple behavior:** the overflow bitmap and the MESH bit position are not documented by Apple and could change in any iOS release.
- **Synchronized iPhone rotation:** three iPhones on iOS 15–17 rotated within 5 s of each other. If iOS rotates on a shared schedule, a tracker can predict address changes. Cause unverified.
- **Coverage:** iOS 27 is missing from the background-advertiser and extended-advertising tests; the Live Activity results come from one phone, without the 8 h end. Android restart periods between 3 and 7 min were not tested. No Doze, no overnight runs, one room.
- **Android 17 scan limits:** only the Pixel 6 runs Android 17, and only filtered scans were tested.
- **Google Play foreground-service policy** for phone-to-phone meshes is unverified (T12, a Play review, not a radio test).
- **The 16 B payload** was a placeholder; the real field layout is untested on air.

## Consequences

- The wire-format ticket defines the 16 B rotating UUID layout, the tag derivations and their vectors, and the GATT identifier characteristic.
- The protocol spec carries the resolving-key exchange inside the handshake payload and the key-rotation message over the link.
- The density estimator (ADR 0008) counts identifier-less sightings by address.
- The modules ticket maps discovery, background modes, the Live Activity hook and the extended-advertising toggle to config and presets.
- Diagnostics report background-degraded discovery, stale-address marks and scan restarts (ADR 0006 codes).

## Amendments

2026-11-02, wire format (ADR 0012):

- **Payload layout (decision 2):** the placeholder is replaced. Byte 0 holds a 3-bit layout version and 5 flag bits; bytes 1–4 the 32-bit mesh tag; bytes 5–12 the 64-bit resolvable tag (random in open and `unsecured` meshes); bytes 13–15 random. Only byte 0 is bit-packed. The new layout is still untested on air; the payload size and position are unchanged.
