# Apply the PHY policy, advertising and scanning rules

Status: done
Type: AFK
Blocked by: 17, 27

## Behavior

Split on 2026-11-18 (Q1): this ticket covers the core, the `RadioPort` contract and `VirtualAir`; ticket 32a applies the rules in the Android adapter and ticket 32b in the iOS adapter. The rules below are the whole policy; the criteria say which ticket proves each.

The Android and iOS adapters apply the radio rules that ADR 0009 and ADR 0010 fix, and each direct `Peer` gets its `proximity`. The core decides; the adapters carry it out through `RadioPort`.

- **PHY (ADR 0009 decision 3):** connect with the 1M|2M initiating mask, never 2M only, then request 2M. Keep 1M when the peer can't do 2M. Send PHY commands (`setPreferredPhy`, `readPhy`) only when the phone reports 2M support, so `p10lite` gets none. No Coded PHY. The `PHY_2M` advertisement flag reflects local 2M support.
- **Rotation on the platform (ADR 0010 decision 3):** on Android, each epoch from ticket 27 starts a new `AdvertisingSet`, or stop/start on phones without the API, never a data update. On iOS, the same period by stop/start.
- **Background iPhones (ADR 0010 decision 5):** a backgrounded iPhone advertises only the MESH bit in Apple's overflow area. Scanners treat such a sighting as a candidate, connect, read the `ADVERTISEMENT` capability TLV for the identifier, and mark stale addresses.
- **Scanning (ADR 0010 decision 6 and energy amendment):** Android scans in start/stop windows with a period of at least 6 s, at most 5 starts per 30 s. An unfiltered scan restarts at least every 4 min, and screen-off scans use hardware filters. Every `startScan` has a matching `stopScan` on every path. iOS background scans restart every 10 s.
- **Support tiers (ADR 0009 decision 9):** Android 8 and 9 run GATT only, 1M only, with no CoC.
- **Proximity (ADR 0007 decision 10):** this ticket owns `Peer.proximity` for direct peers. The core smooths RSSI samples reported through `RadioPort` with an EWMA, α = 0.25 (`smoothed = 0.25 × sample + 0.75 × smoothed`, the first sample taken as is). Buckets: `Immediate` at ≥ −55 dBm, `Near` at ≥ −75 dBm, otherwise `Far`; `Unknown` when the latest sample is older than 30 s. Proximity is never a distance and never goes on the wire. A dated ADR 0007 amendment records the α, thresholds and 30 s staleness; ticket 33 checks the buckets on lab pairs and may adjust them through that amendment.
- **Deferred beyond 0.1.0:** extended advertising and the iOS Live Activity hook, by the [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md#amendments) and [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments) amendments (2026-11-13, deferred for 0.1.0). This ticket implements neither, and adds no toggle for them.

## Spec and ADR references

- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 3, 8 and 9; [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 1, 3, 5–7 and amendments; [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decision 8.
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md#amendments) and [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments) amendments (2026-11-13, deferred for 0.1.0): extended advertising (ADR 0010 decision 2) and the Live Activity hook are out of 0.1.0.
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md#decision) decision 10 (`Peer.proximity`: smoothed RSSI, bucket, measured time; never a distance, never on the wire); this ticket amends it with the thresholds.
- [discovery.md, Payload layout](../../../docs/spec/discovery.md#payload-layout), [Capability record](../../../docs/spec/discovery.md#capability-record) and [Informative: scanning and peer choice](../../../docs/spec/discovery.md#informative-scanning-and-peer-choice).
- [Library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md) R4–R6, R10, R11 and R19; [discovery evidence](../../../docs/evidence/2026-11-discovery/README.md) (background overflow bit).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 3 and 13.

## Acceptance criteria

- [ ] ~~Robolectric tests show the 1M|2M connect mask, a 2M request after connect, and no PHY command on a device that reports no 2M support.~~ Moved to ticket 32a (Q1).
- [ ] ~~Robolectric tests show a new `AdvertisingSet` per epoch, and the stop/start path on devices without the API.~~ Moved to ticket 32a (Q1); the stop/start path is dropped, because `AdvertisingSet` is API 26, the minSdk (Q7).
- [ ] ~~Robolectric tests show scan windows with a period of at least 6 s, never more than 5 starts in any 30 s, an unfiltered restart within 4 min, hardware filters when the screen is off, and a `stopScan` on every error, timeout and cancellation path.~~ Moved to ticket 32a (Q1, Q8).
- [ ] ~~iOS tests with fakes show stop/start rotation, the 10 s background scan restart, and overflow-bit sightings leading to a capability read.~~ Moved to ticket 32b (Q1).
- [x] `RadioPort` contract: `Sighting.payload` is null for a sighting that matched only the MESH overflow bit (Q3), `CentralLink.readRssi()` reads the link's RSSI (Q5), and the radio reports whether it supports the 2M PHY. `VirtualAir` implements all three, and the platform adapters compile against them.
- [x] Seeded `VirtualAir` tests show the core scanning in start/stop windows of 4 s on and 4 s off, the low-class `Balanced` schedule until ticket 36 (Q2), so at most 4 starts in any 30 s, and stopping the scan on every exit.
- [x] A seeded `VirtualAir` test shows a background-iPhone sighting resolving to its identifier through the `ADVERTISEMENT` TLV, and a stale address marked: an identifier-less candidate whose connect fails, that serves no capability or no `ADVERTISEMENT`, or whose advertisement is not this mesh's is not dialed again, and is forgotten after 10 min without a sighting (Q4).
- [x] The `PHY_2M` flag follows the radio's 2M support, and the `PHY_2M` flag cases in `advertisement.json` pass in the one runner.
- [x] `Peer.proximity` for each direct peer holds the EWMA-smoothed RSSI (α = 0.25), its bucket and the time it was measured (Q6). Samples come from `readRssi` every 10 s on links this node opened as central, and from sightings matched to a linked peer by handle or resolvable tag (Q5). Seeded `VirtualAir` tests cover each bucket edge (−55 and −56 dBm, −75 and −76 dBm), smoothing of a step change, `Unknown` after 30 s without a sample~~, and no proximity for routed peers~~ (moved to ticket 38, which adds routed peers). No frame or advertisement carries it.
- [x] A dated ADR 0007 amendment (version bump) records α = 0.25, the −55/−75 dBm thresholds and the 30 s staleness; `python3 tools/check_adrs.py` passes.
- [x] No extended-advertising code path, Live Activity hook or toggle for either exists.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
python3 tools/check_adrs.py
```

## Docs to update

- [ADR 0007](../../../docs/adr/0007-public-api-shape.md): dated amendment with the proximity smoothing, thresholds and staleness (version bump).
- `CHANGELOG.md`: `Peer.proximity` entry.
- KDoc on `Peer.proximity` and its bucket type: the thresholds, and that it is not a distance.

## Devices needed

None for this ticket. Ticket 33 proves PHY, rotation and the proximity buckets on phones.

## Outcome

- **Owner decisions (2026-11-18):** Q1 split into 32 (core, contract, `VirtualAir`), 32a (Android) and 32b (iOS). Q2 the core scans in windows of 4 s on and 4 s off. Q3 `Sighting.payload` is nullable. Q4 stale addresses are not dialed again and are forgotten after 10 min unseen. Q5 samples come from `readRssi` every 10 s and from matched sightings. Q6 `Peer.proximity` is a `Proximity` with `rssi`, `bucket` and `measuredAt`. Q7 no stop/start rotation fallback. Q8 Android always uses two hardware filters (ticket 32a). Q9 iOS never sets `PHY_2M`. Recorded in the ADR 0007 and ADR 0010 amendments of 2026-11-18.
- **Contract:** `Sighting.payload` is `ByteArray?`; `RadioPort.supportsLe2MPhy` and `CentralLink.readRssi()` are abstract, so every adapter states them. The Android and iOS radios report no 2M support and fail `readRssi()` with `UNSUPPORTED` until 32a and 32b. On iOS a link's requests moved to `AppleRequests` and on Android the capability read moved onto the discovered attributes, keeping both central links within detekt's function limit.
- **Core:** `Scanner` runs the windows, sets `PHY_2M` from the radio, keeps identifier-less candidates, and vets them through `Links` after the capability read; `Proximities` smooths the samples and republishes peers on each reading, bucket change and expiry.
- **Tests:** `BackgroundDiscoveryTest` (7), `ProximitiesTest` (10) and `ProximityTest` (2) are new; `DiscoveryTest` (+4), `VirtualAirTest` (+4), `AndroidRadioCentralTest` (+1), `AppleRadioCentralTest` (+1) and `UnsupportedRadioPortTest` cover the rest. The `PHY_2M` cases of `advertisement.json` pass in `VectorSets`. No extended-advertising or Live Activity code exists.
- **Unverified:** radio behavior on phones and the bucket thresholds' fit, left to ticket 33; the adapters' `readRssi`, 2M report, overflow-bit sightings, rotation and scan rules, left to 32a and 32b.
