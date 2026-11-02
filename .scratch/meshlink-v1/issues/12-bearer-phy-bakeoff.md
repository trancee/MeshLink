# Which BLE bearer(s) and PHY policy should MeshLink use?

Type: prototype
Status: resolved
Blocked by: 02, 06, 11

## Question

Device-lab bake-off: GATT (notify/write-without-response) vs L2CAP CoC, 1M vs 2M PHY, MTU/DLE, connection-setup time, throughput, latency, failure rates, across Android↔Android, Android↔iOS, iOS↔iOS and old/new devices. Decide primary bearer, optional upgrades and PHY policy.

## Scope additions (from research, 2026-10-31)

- Measure max concurrent connections per platform and role (iOS is undocumented; Android ~5–6 confirmed in source).
- Test insecure and secure (bonded) CoC across Android↔iOS, both directions.
- Crossed simultaneous connections: define a deterministic keep-one rule (Android 8/9 can't tell the two links apart).
- From the [suite decision](09-security-profile-suites.md): measure first-message latency (classical vs post-quantum, ≈+2.3 KB) and `XXhfs` link-establishment cost (≈2.5 KB) on real devices, plus effective GATT/CoC throughput, to replace the illustrative 10–50 KB/s range.
- From the [toolchain baseline](11-toolchain-baseline.md) (ADR 0005): build the bake-off app on the baseline tuple. Sign iPhones with the shared lab bundle ID `ch.trancee.lab.host` on the free teams, re-signing within 7 days. The simulator never counts as radio evidence.

## Bake-off verdict (accepted 2026-11-01)

The matrix ran on real devices on 2026-11-01, 13:11–23:46 CET. Raw data is in `prototypes/bearer-bakeoff/host/results/` on `prototype/bearer-bakeoff`.

- **Bearer:** both bearers ship in v1.
  - GATT always connects first and carries discovery and capability exchange.
  - When both phones support CoC (Android 10+, iOS), the link upgrades to unbonded L2CAP CoC and falls back to GATT if the channel fails or closes. MeshLink's own encryption protects the data.
  - CoC is on by default; an app can disable it.
  - Measured: CoC opened on 49–50 of 50 connections where GATT had succeeded, for every Android 10+ ↔ iPhone pair in both directions.
  - Gains are modest: throughput 1.0–1.3× GATT; 64 B echo about 13 ms faster with an Android central, no difference with an iPhone central; post-quantum handshake about 30% faster.
  - The user chose CoC despite missing the ≥1.5× throughput bar.
- **PHY:** prefer 2M, fall back to 1M. 2M gave 1.4–1.9× throughput with no extra connect failures. Coded PHY is untested.
- **Connect:** up to 3 retries (backoff 500 ms × n) for status 133/147, plus the deterministic keep-one rule for crossed connections (0 violations).
- **Android 8/9:** allowed; only Android 10+ is fully supported (Android 8/9 have no CoC API, so GATT only). The legacy rerun with retries and the busy-handling fix is pending.
- **Follow-ups before resolving:**
  - the legacy rerun;
  - investigate the iPhone-central GATT link closes (about 22 s after throughput, from the second attempt on);
  - investigate why the iPhone X peripheral is not discoverable by other iPhones;
  - a routing recheck with iPhones accepting at most 4 incoming links.
- **Then:** evidence folder `docs/evidence/2026-11-bearer-bakeoff/`, the bearer ADR and `docs/agents/lessons.md`.

## Follow-up verdicts (accepted 2026-11-02)

- **Legacy rerun:** after the scanner-leak fix (`9d97545`) the XCover 4 is usable (24–25 of 25 in three directions). The P10 lite crashes its Bluetooth stack on `readPhy`, so PHY commands are sent only when 2M is supported.
- **iPhone-central link closes:** iOS reads the GAP Device Name; the busy Android peripheral drops the reply, and iOS times out after 30 s (reason 722). Fix: peripheral flow control. A close about 30 s after connect with no error is transient.
- **iPhone incoming links (step 6):** iOS 15 and 16 become invisible at 4 incoming links; iOS 17, 18 and 27 held 6 and stayed visible. v1 caps every iOS version at 3; the iOS 17+ headroom is recorded for later. The iPhone 15 closes at 3–4 links in step 4 stay unexplained.
- **Routing recheck at cap 3:** passed 8 of 8 combinations (ADR 0008).
- **Raw data:** gzipped in the evidence folder (about 4 MB).

## Answer

Recorded in [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md), with the evidence in [the bearer bake-off folder](../../../docs/evidence/2026-11-bearer-bakeoff/README.md) and the rules in [library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md). The prototype stays on `prototype/bearer-bakeoff`.

- **Bearers:** GATT first, then an upgrade to unbonded L2CAP CoC (Android 10+ and iOS) with fallback to GATT. CoC is on by default; an app can disable it.
- **PHY:** connect with the 1M|2M mask, never 2M only; then request 2M. Send PHY commands only when 2M is supported.
- **Connect:** 3 retries with 500 ms × n backoff for status 133/147; the keep-one rule for crossed connections.
- **Link limits:** an iPhone accepts at most 3 incoming links.
- **Robustness:** peripheral flow control, busy-retry for every GATT operation, paired scanner start and stop, `openGattServer` retry.
- **Support tiers:** Android 10+ and iOS 15+ full; Android 8/9 GATT only, best effort.
- **Unverified:** secure (bonded) CoC, Coded PHY, platform concurrency maximums, the iOS 15/16 invisibility cause, the iPhone 15 closes, the OPPO.
