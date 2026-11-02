# 0009: BLE bearers, PHY policy and link rules

Status: accepted (2026-11-02; ticket "Which BLE bearer(s) and PHY policy should MeshLink use?"). Version: 2 (amended 2026-11-02, ADR 0010).

## Context

Two phones can carry MeshLink frames over a GATT link (notifications plus write-without-response) or over an L2CAP connection-oriented channel (CoC). Both run on 1M or 2M PHY. The choice affects interoperability, setup time, throughput, latency and how much platform code MeshLink carries.

The device-lab bake-off answered this on real phones; the simulator never counts as radio evidence. Its numbers, method and raw data are in [the bake-off evidence](../evidence/2026-11-bearer-bakeoff/README.md), and the library rules it produced are in [library rules](../evidence/2026-11-bearer-bakeoff/library-rules.md). Dates: 2026-11-01 13:11 to 2026-11-02 09:38 CET.

- **Phones:** five iPhones (iOS 15.8 to 27.0) and seven Android phones (API 26 to 37).
  - Two Android phones were excluded: the OPPO CPH2385 was unreachable (0 of 400 scans), and the Redmi Note 11 refused installs.
- **Throwaway branch:** `prototype/bearer-bakeoff`.

Measured, medians unless noted:

| Measure | GATT | CoC |
|---|---|---|
| Opened, Android 10+ ↔ iPhone, both directions | 43–50 / 50 as Android central; 50 / 50 as iPhone central | 49–50 / 50 of connections where GATT had succeeded |
| Setup time | 1.3–3.7 s (GATT connect and discovery) | about 160–380 ms more, on top of GATT |
| Throughput, Android central | 47–79 KB/s | 53–82 KB/s (1.0–1.3×) |
| Throughput, iPhone central | 19–31 KB/s | 22–35 KB/s |
| 64 B echo, Android central | 30–45 ms | 23–32 ms |
| 64 B echo, iPhone central | 60 ms | 60 ms |
| Post-quantum handshake (XXhfs), Android central | 101–134 ms | 68–106 ms |

- **Pre-registered CoC criterion:** at least 95% CoC success on Android 10+ ↔ iOS, plus either at least 1.5× throughput or a lower round trip.
  - Success is met, counted over connections where GATT had succeeded.
  - Throughput is not met.
  - The round trip is lower only with an Android central.
  - The user chose to ship CoC anyway, as an upgrade (decision 1).
- **iPhone centrals:** iOS picks a connection interval of about 30 ms that apps cannot set, so round trips land on 60 or 120 ms.
- **2M vs 1M PHY** (Android ↔ Android): 2M gave 1.4–1.9× the throughput on GATT and CoC, with no extra connect failures.
- **Connect failures:** first connects often fail with status 133 or 147.
  - Up to 3 retries fixed most of them: Pixel 6 → S10e 9/20 → 19/20, S10e → Pixel 6 14/20 → 20/20.
  - A 500 ms delay before connecting did not help (8/20).
- **Crossed connections** (both phones connect to each other at the same time): the keep-one rule held in every crossed round, with 0 violations; detection took a median 299 ms.
- **Concurrent links:** Android central reached 7–8 links, Android peripheral 8 and iPhone central 8–9. These are lower bounds, limited by the phones available.
  - **iPhone incoming links, by iOS version** (step 6, one phone each): the SE 2016 (iOS 15) and iPhone X (iOS 16) held 3 incoming links and stayed visible. At 4 they kept the links but became invisible to every scanner, though iOS still reported advertising, and a 5th central could not connect. The iPhone 15 (iOS 17), SE 2020 (iOS 18) and 12 mini (iOS 27) held 6, the most centrals available, and stayed visible.
  - In step 4 the iPhone 15 closed incoming links at 3–4; step 6 did not reproduce it.
- **iPhone-central link closes:** on a reconnect, iOS reads the peer's GAP Device Name 12–13 s after connecting.
  - When the Android peripheral is busy sending a burst of notifications, its stack drops the reply (`failed to write data to L2CAP`).
  - iOS disconnects exactly 30 s later (iOS reason 722, HCI 0x13). The app sees a close with no error.
- **Android 8 and 9:** the P10 lite (API 26) and XCover 4 (API 28) first looked broken.
  - Both root causes were in the prototype. `readPhy()` aborted the P10 lite's Bluetooth stack. Scanner registrations leaked on the XCover 4 until none were left.
  - After the fixes, the P10 lite connected 16–17 of 20 times as central. The XCover 4 connected 24–25 of 25 in three directions, but only 16 of 25 as central to an iPhone 15.
  - Neither supports 2M. Android 8 and 9 have no CoC API: `createInsecureL2capChannel` arrived in API 29.

## Decision

1. **Bearers:** v1 ships both GATT and L2CAP CoC.
   - Every link starts on GATT. GATT carries discovery, capability exchange and the CoC channel number (PSM).
   - When both phones support CoC (Android 10+, iOS), the link upgrades to an unbonded CoC.
   - If the channel fails to open, or closes later, the link falls back to GATT without losing the session.
   - CoC is on by default; an app may disable it in config.
2. **CoC link security:** unbonded (insecure) CoC only. MeshLink's own encryption protects the data in the classical and post-quantum profiles (ADR 0002).
   - Bonding would ask the user to pair, which breaks joining a mesh, and bonded CoC is untested.
   - In the unsecured profile the bearer adds no protection (ADR 0001).
3. **PHY:** connect with the 1M|2M initiating mask, never 2M only, then request 2M. 1M stays when the peer can't do 2M.
   - Send PHY commands (`setPreferredPhy`, `readPhy`) only when the phone reports 2M support.
   - Coded PHY is out of scope for v1 because it is untested.
4. **Connect retries:** retry a GATT connect that ends with status 133 or 147 up to 3 times, with a backoff of 500 ms × n.
   - A close about 30 s after connect with no error counts as a transient link loss and goes through the same retries.
5. **Crossed connections:** a deterministic keep-one rule keeps exactly one link per pair of phones; both sides compute the same answer.
6. **Link limits:** an iPhone accepts at most 3 incoming (peripheral-role) links on every iOS version. It makes any further links as central.
   - iOS 15 and 16 need it to stay discoverable. iOS 17 and later measured 6; that headroom is kept for a later release, because the routing simulation found no loss at 3 and no gain at 4 (ADR 0008), and the iPhone 15 closes in step 4 are unexplained.
7. **Flow control:** the peripheral side paces notification bursts with credits or acknowledgments, so the platform's own requests still get through.
   - Android's `onNotificationSent` means queued, not sent.
   - Bulk data prefers CoC when it is up, because CoC has its own flow control.
8. **Platform rules:**
   - every `startScan` has a matching `stopScan` on every path, including errors, timeouts and cancellation;
   - every Android GATT operation retries when the stack reports it busy;
   - `openGattServer` retries;
   - the iOS peripheral reuses a link through the control read when a reconnecting central sends no new subscribe.

   The complete list, with evidence runs, is in [library rules](../evidence/2026-11-bearer-bakeoff/library-rules.md).
9. **Support tiers:** Android 10+ and iOS 15+ are fully supported. Android 8 and 9 are allowed but not fully supported.
   - They run GATT only.
   - Their known device problems are documented, and failures there are not release blockers.

## Alternatives

- **GATT only** (the first recommendation): one code path and simpler maintenance. Rejected by the user because CoC is the more modern bearer, lowers latency with Android centrals and speeds up the post-quantum handshake by about 30%.
- **CoC only, no GATT fallback:** Android 8 and 9 have no CoC API, and the iPhone central closed some CoC links (`LinkClosedException`).
- **Bonded CoC:** asks the user to pair and is untested; MeshLink's own encryption already protects the data.
- **2M-only initiating mask:** fails with status 147, because the legacy advertisement is on 1M.
- **A fixed delay before connecting instead of retries:** measured, no gain.
- **Raising the minimum to Android 10:** after the prototype fixes, both older phones worked in most directions, so the user kept Android 8+.

## Risks

- **Unverified causes:**
  - why iOS 15 and 16 phones holding 4 incoming links become invisible, and whether the iOS version or the hardware causes it (one phone per version);
  - why the iPhone 15 closed incoming links at 3–4 in step 4 (open question for the discovery ticket and device tests);
  - why the OPPO was unreachable;
  - whether the leaked scanner registrations also caused the XCover 4's `openGattServer` failures.
- **Limits are lower bounds:** concurrency was limited by the phones available, not measured to the platform maximum.
- **Scanner registration race:** the stack can finish a registration after it reported failure to the app. Library code can't fully prevent this leak.
- **Coverage gaps:** Android 15+ peripherals and iOS 26+ centrals are covered by one phone each.
- **Expiring lab signing:** the free Apple team profiles expire around 2026-11-07, after which device reruns need re-signing.
- **Toolchain deviation:** the prototype ran on JDK 21, not the JDK 25 daemon of ADR 0005. This doesn't affect radio results.

## Consequences

- The protocol framing is bearer-independent, so the session survives a CoC ↔ GATT switch. The protocol spec must define the capability exchange and the PSM announcement over GATT.
- Two transport implementations per platform need tests. Robolectric covers Android logic; radio behavior needs the device lab.
- The routing simulation is recalibrated with these numbers (ADR 0008).
- The discovery and advertising ticket inherits the iPhone 3-link cap, the 1M legacy advertisement and the scanner rules.

## Amendments

2026-11-02, discovery measurements (ADR 0010):

- **iPhone 15 incoming-link closes:** not reproduced with the current build. In 3 of 3 runs the iPhone 15 held 4 incoming links for 500 s with no close. The cap of 3 stays. Candidate causes, all unverified: the old build `be5c761`, the larger fleet in step 4, and `bluetoothd`'s "scalable pipes are idle" disconnect logic seen for iPhone centrals.
- **Coded PHY:** stays out of v1. 6 of 15 Android phones that report Coded support received no Coded advertisement.
- **Legacy 1M advertisement:** confirmed. No iPhone and no legacy-only Android phone received extended advertising.
