# BLE bearer bake-off: evidence

- **Dates:** 2026-11-01 12:00 to 2026-11-02 10:52 CET. The full matrix ran 2026-11-01 13:11–23:44. Follow-ups ran 2026-11-02 00:05–01:37 and 09:23–09:38. The iOS inbound-link sweep (step 6) ran 2026-11-02 10:02–10:52.
- **Ticket:** `.scratch/meshlink-v1/issues/12-bearer-phy-bakeoff.md`
- **Decision it feeds:** [ADR 0009](../../adr/0009-ble-bearers-and-phy-policy.md)
- **Library rules derived from it:** [library-rules.md](library-rules.md)
- **Code:** throwaway prototype on branch `prototype/bearer-bakeoff`, under `prototypes/bearer-bakeoff/`. It is never merged.

All numbers here were observed on real phones. Simulators were not used. Anything not observed is marked **unverified**.

## Evidence labels

| Label | Meaning |
|---|---|
| **[RUN]** | Measured in the named runs. Raw JSONL (gzipped) is in [`raw/`](raw/). |
| **[LOG]** | Seen in a logcat, syslog or `bluetoothd` capture. The captures are not in this folder (see [Diagnostic captures](#diagnostic-captures)). |
| **[LOG, not retained]** | Seen in a live log during the session. The capture was not saved. |
| **[UNVERIFIED]** | A likely explanation that no run or log proves. |

## 1. Question

Which BLE bearer and PHY policy should MeshLink use?

- Is GATT enough (notifications from the peripheral, write-without-response from the central)?
- Is an L2CAP connection-oriented channel (CoC) worth adding as an upgrade?
- Does LE 2M PHY help on Android, or does it add failures?
- How long does a dummy handshake take to deliver? This covers classical Noise XX, post-quantum XXhfs, and a first message of each kind.
- How many links can one phone hold, per platform and role?
- How often do two phones connect to each other at the same time, and does the keep-one rule resolve it?

## 2. Setup

### Phones

All phones ran in the foreground with the screen on. The app kept the screen awake: `FLAG_KEEP_SCREEN_ON` on Android, `isIdleTimerDisabled` on iOS. No phone was paired or bonded. No device setting was changed.

| Name | Model | SoC | OS / API | 2M PHY | Host transport | Status |
|---|---|---|---|---|---|---|
| Pixel 6 | Google Pixel 6 | Google Tensor (gs101) | Android 17 / 37 | yes | remote adb over TCP | used |
| Nothing 2 | Nothing Phone (2) A065 | Snapdragon 8+ Gen 1 | Android 16 / 36 | yes | remote adb, USB | used |
| S10e | Samsung Galaxy S10e SM-G970U1 | Snapdragon 855 | Android 12 / 31 | yes | remote adb, USB | used |
| Mi 8 | Xiaomi Mi 8 | Snapdragon 845 | Android 10 / 29 | yes | remote adb, USB | used |
| XCover 4 | Samsung Galaxy XCover 4 SM-G390F | Exynos 7570, armeabi-v7a only | Android 9 / 28 | no | remote adb, USB | used, GATT only |
| P10 lite | Huawei P10 lite WAS-LX1A | Kirin 658 | Android 8.0 / 26 | no | remote adb, USB | used, GATT only |
| OPPO | OPPO CPH2385 | Helio G35 | Android 14 / 34 | unknown | remote adb over Wi-Fi | excluded: unreachable |
| Redmi 11 | Xiaomi Redmi Note 11 | Snapdragon 680 | Android 13 / 33 | unknown | remote adb over TCP | excluded: install refused |
| iPhone 15 | iPhone15,4 | A16 | iOS 17.6.1 | yes | USB, `devicectl` | used |
| SE 2020 | iPhone12,8 | A13 | iOS 18.7.8 | yes | Wi-Fi, `devicectl` | used |
| 12 mini | iPhone13,1 | A14 | iOS 27.0.1 | yes | USB, `devicectl` | used |
| iPhone X | iPhone10,6 | A11 | iOS 16.7.16 | yes | USB, libimobiledevice | used |
| SE 2016 | iPhone8,4 | A9 | iOS 15.8.8 | no | USB, libimobiledevice | used |

- **Android 2M:** from `BluetoothAdapter.isLe2MPhySupported()`, recorded in each run.
- **iPhone 2M:** iOS has no API for this. It is inferred from the PHY that Android centrals read: every iPhone gave `tx=2M,rx=2M` except the SE 2016, which gave `tx=1M,rx=1M`.
- The SoC column comes from the lab inventory (`android-devices/devices.md`) and Apple's model identifiers. It is not read from the phones.
- **Not recorded:** Bluetooth controller firmware and Android build fingerprints. The OS versions come from the `reporter` field of each JSONL record.

### Software

- **Prototype:** Kotlin Multiplatform.
  - **Android:** `ch.trancee.lab.host` built for arm64-v8a and armeabi-v7a, minSdk 26, compileSdk 37.
  - **iOS:** CoreBluetooth code written in Kotlin/Native, inside a SwiftUI shell.
  - **Signing:** iOS 15.0 deployment target, free personal teams apple-team-1 and apple-team-2. Team B uses bundle id `ch.trancee.lab.host.b`.
- **Toolchain:** Kotlin 2.4.21, Gradle 9.7.1, AGP 9.3.3, kotlinx-coroutines 1.11.0 and Xcode 16.4.
  - The Gradle daemon and JVM toolchain ran on JDK 21, not the JDK 25 daemon from ADR 0005. This does not affect radio results.
- **Host:** Python 3 stdlib scripts `host/bakeoff.py`, `host/matrix.py` and `host/summarize.py`.
- **GATT service:** `6e4b0001-8f3c-4d2a-9b61-2c7a5d3e9f10`, with four characteristics:
  - control and node-ID read;
  - data notify;
  - data write-without-response;
  - PSM read.
- **Advertisement:** a legacy, connectable advertisement on 1M that carries only the 128-bit service UUID.
- **Framing:**
  - On GATT, each ATT value has a 1-byte sequence header.
  - On CoC, each message has a u8 type and a u32 length prefix.
- **CoC:** insecure (unbonded) only. Android uses `listenUsingInsecureL2capChannel` and `createInsecureL2capChannel`. iOS uses `publishL2CAPChannel(withEncryption: false)`. The PSM is dynamic and read over GATT on every connection.
- **Dummy handshakes:** random bytes, no crypto, sized as in [ADR 0002](../../adr/0002-noise-suites-and-handshake-patterns.md).

  | Test | Message sizes (bytes) |
  |---|---|
  | `handshake_xx_classical` | 32, 96, 64 |
  | `handshake_xxhfs_pq` | 1,216, 1,200, 64 |
  | `first_message_classical` | 160 |
  | `first_message_pq` | 2,480 |

- **Throughput tests:** 64 KiB, sent in 16 KiB messages.
- **Echo test:** 20 round trips with a 64-byte payload.
- **Handshake tests:** each repeats 5 times.

### Commits

Each run's `run.json` and every JSONL record carry `commit`. That is the prototype branch `HEAD` when the run was launched. The app installed on a phone can be older: it was rebuilt only when the app changed.

| Phase | Host `HEAD` in `run.json` | App build that matters |
|---|---|---|
| Smokes, 133/147 mitigation, XCover 4 stall | `2c777d3`, `82a7e15`, `03afe42`, `be5c761` | as recorded |
| Steps 1–5 | `9495595` … `424b149` | `be5c761` (README of the prototype) |
| Step 5b | `31ac3f7`, `78ebef1` | `31ac3f7` busy-retry for every Android GATT operation |
| Step 5c, diag B1/B2 | `78ebef1` | `0748cd2` skips PHY commands without 2M; `78ebef1` keeps close causes |
| Step 5d | `9d97545` | `9d97545` pairs every `startScan` with `stopScan` |
| Step 6 | `0220549`, `5d549db` | Android `0220549` (hold and survey modes); iOS unchanged since diag B2 (`78ebef1`) |

Prototype commits on `prototype/bearer-bakeoff`, oldest first:

`053b41d a1c1bd9 44276bc 3ad28b2 7892420 2c777d3 82a7e15 03afe42 be5c761 9495595 c2bf790 1a78570 aee8dee f4454e4 424b149 af65deb 3877359 31ac3f7 9f60ad3 0748cd2 78ebef1 fe28db4 9d97545 46e67d8 0220549 5d549db`

## 3. Method

The matrix driver `host/matrix.py` ran every step. It skipped completed runs, so it could resume.

Each run went through the same steps:

1. Stop the app on both phones.
2. Start the peripheral, wait, then start the central with the run's arguments.
3. Wait for the central's `done` record.
4. Pull both JSONL files.

- **Android:** `am start --es args`, then `run-as … cat`.
- **iOS 17+:** `devicectl device process launch`, then `devicectl device copy from`.
- **iOS 15/16:** `idevicedebug --detach run`, then `afcclient --container … get -f`.

Each suite test has a 30 s deadline. A stalled test is a `fail` under its own name, and the suite then aborts.

| Step | Run ids | What | n and knobs |
|---|---|---|---|
| Smokes | `s0-*`, `s1-*`, `i1-*`, `i1b-*`, `t4-*`, `auth-*` | Build, install, orchestration and first numbers | n=1–5. Superseded by the matrix. |
| 133/147 | `m147-*` | First-connect failures, Pixel 6 ↔ S10e | n=20, `plan=connect`, 1M. Baseline vs `connect_delay_ms=500` vs `connect_retries=3`. |
| XCover 4 stall | `x4-*` | XCover 4 peripheral stops receiving writes | n=1–4 suites, 1M. `write_ack_every=16`, `pace_ms=20`, `req_mtu=185`, `max_chunk=182`, combined. |
| 1 | `m1-*` | GATT and CoC connect, every Android ↔ iPhone pair in both directions, plus 3 iPhone ↔ iPhone pairs | n=50, `plan=connect`, `bearers=gatt+coc`, `phy=2m`, `connect_retries=3` |
| 2 | `m2-*` | Full suite on the same pairs | n=5, `plan=suite`, `bearers=gatt+coc`, `phy=2m`, `connect_retries=3` |
| 3 | `m3-*` | 1M vs 2M, Pixel 6 ↔ S10e and Pixel 6 ↔ Nothing 2 | Per PHY: `plan=connect` n=20 with no retries, and `plan=suite` n=5 with `connect_retries=3` |
| 4 | `m4-hub-*`, `m4-phub-*`, `m4-dual-*` | Max links as central (hub) and peripheral (phub). Crossed connections (dual). | Hub: link one peer at a time until 3 adds fail in a row; 8-byte echo on every held link after each add, then 6 × 5 s. Dual: 20 rounds of 20 s. `phy=2m`. |
| 5 | `m5-*` | Legacy phones (P10 lite, XCover 4) against Pixel 6 and iPhone 15, both roles | `plan=connect` n=20 and `plan=suite` n=5, GATT only, no retries |
| 5b | `m5b-*` | Step 5 rerun after the busy-retry fix | as step 5, plus `connect_retries=3` |
| 5c | `m5c-*` | Legacy centrals after the PHY-skip fix | as 5b, legacy phone as central only |
| 5d | `m5d-*` | XCover 4 after a reboot and the `stopScan` fix | as 5b, XCover 4 in both roles. `dumpsys bluetooth_manager` before and after, and a 30 s sampler. |
| Diag B1 | `diag-b1*` | iPhone-central GATT link closes | `plan=suite` n=3–4, with logcat and `bluetoothd` capture |
| Diag B2 | `diag-b2*` | iPhone X peripheral visibility | Scan surveys from iPhone 15 and SE 2020, with and without a service filter, while the iPhone X held 0 or 4 links |
| 6 | `m6-inbound-*` | iOS inbound-link sweep: links held and visibility per k, on SE 2016, iPhone 15, SE 2020, 12 mini and iPhone X | k = 1…6 Android centrals, `role=hold`, `connect_retries=3`; 30 s filtered + 20 s unfiltered probes per k. See [Step 6](#step-6-ios-inbound-link-sweep). |

**Knobs:**

- `connect_retries=N` retries a GATT connect that ends with status 133 or 147 before the link is up. The backoff is 500 ms × retry number. Each retry leaves a `retryN_<status>` timing mark.
- `phy=` sets `setPreferredPhy` after connecting. Connects always initiate on the 1M|2M mask.
- The Android central requests `CONNECTION_PRIORITY_HIGH`.
- Android scans are spaced at least 6.5 s apart, to stay under the limit of 5 scans per 30 s.

**Metrics:**

- **`setup_ms`** is from scan start to the first application byte, excluding the scan-pacing wait. It includes retries.
- **CoC `setup_ms`** is the extra time to open the CoC after GATT is up.
- **Throughput** is application bytes per second (kB/s = 1,000 bytes/s).
- **Echo** is the median round trip of 20 echoes.
- **Handshake** times are the median of 5 transcripts.

[`summary/`](summary/) holds `summarize.py` output per phase. It groups records by central, peripheral, requested PHY, variant, test and bearer. It reports ok/total, first-try successes, and the median, p90, min and max of the headline metric.

## 4. Pre-registered CoC criterion and result

The criterion was fixed before the runs:

> CoC is an optional upgrade only if insecure CoC reaches ≥95% connect success on every Android 10+ ↔ iOS pair in both directions, AND gives either ≥1.5× GATT throughput or a clearly lower round trip.

The Android 10+ phones were Pixel 6, Nothing 2, S10e and Mi 8. They were paired with all 5 iPhones in both directions in steps 1 and 2.

| Part | Result | Evidence |
|---|---|---|
| ≥95% CoC success, counted over connections where GATT succeeded | **Met.** Every pair reached 98–100%; the worst was 49/50, and with an Android central it was 100%. | [RUN] `m1-*` |
| ≥95% CoC success, counted over all attempts | **Not met** on 4 Android-central pairs, because the GATT connect failed first. The worst was Mi 8 → SE 2016 at 20/50. CoC never failed after GATT was up on those pairs. | [RUN] `m1-mi8-*` |
| ≥1.5× throughput | **Not met.** CoC was 0.92–1.37× GATT in each direction (median of 5 per pair). | [RUN] `m2-*` |
| Clearly lower round trip | **Partly.** About 10–20 ms lower with Mi 8, Nothing 2 or S10e as central (for example S10e → iPhone 15: 33 ms GATT vs 23 ms CoC). No difference with Pixel 6 as central. None with any iPhone as central (60 ms both). | [RUN] `m2-*` |

The criterion was **not met as written**. The user still chose CoC as a default-on upgrade after GATT, with fallback to GATT (ADR 0009).

Android ↔ iOS insecure CoC interop worked in both directions on every tested pair. CoC failures after GATT was up:

- `LinkClosedException: l2cap-central:<uuid>:<psm> closed`, with an iPhone central. 1 of 50 on each of iPhone X → 12 mini, iPhone X → Pixel 6, iPhone X → S10e, 12 mini → iPhone X, iPhone 15 → Mi 8 and iPhone 15 → S10e. [RUN] `m1-*`
- `IllegalStateException: l2cap open failed NSError CBErrorDomain:0 Unknown error.`, 1 on iPhone X → Mi 8. [RUN] `m2-ipx-mi8-t01210932`
- `IOException: read failed, socket might closed or timeout, read ret: -1`, 1 of 5 on S10e → Pixel 6 at 1M. [RUN] `m3-1m-suite-s10e-pixel6-t01213319`

## 5. Results

### Step 1: connect (n=50 per pair)

- An iPhone central has no retry knob. Its first try is its only try.
- The Android central's ok/n includes up to 3 retries.
- The CoC column counts CoC opens over connections where GATT had succeeded.
- PHY is what the Android central read after `setPreferredPhy(2M)`; iOS cannot read it.
- The 8 OPPO runs are excluded here (see [Excluded devices](#6-excluded-devices)).

| Central → peripheral | GATT ok/n | First try | GATT setup ms (median / p90) | CoC ok/n | CoC setup ms (median / p90) | MTU | PHY |
|---|---|---|---|---|---|---|---|
| 12 mini → Mi 8 | 50/50 | 50 | 1917 / 3373 | 50/50 | 207 / 267 | 515 | n/a |
| 12 mini → Nothing 2 | 50/50 | 50 | 1799 / 3044 | 50/50 | 177 / 207 | 515 | n/a |
| 12 mini → Pixel 6 | 50/50 | 50 | 1950 / 2714 | 50/50 | 178 / 208 | 515 | n/a |
| 12 mini → S10e | 50/50 | 50 | 2458 / 4453 | 50/50 | 177 / 207 | 515 | n/a |
| 12 mini → iPhone X | 50/50 | 50 | 1693 / 1784 | 49/50 | 177 / 179 | 515 | n/a |
| Mi 8 → 12 mini | 38/50 | 17 | 7014 / 9809 | 38/38 | 287 / 438 | 517 | tx=2M,rx=2M |
| Mi 8 → SE 2016 | 20/50 | 8 | 9114 / 11991 | 20/20 | 340 / 468 | 517 | tx=1M,rx=1M |
| Mi 8 → SE 2020 | 40/50 | 14 | 7202 / 9902 | 40/40 | 280 / 368 | 517 | tx=2M,rx=2M |
| Mi 8 → iPhone 15 | 46/50 | 30 | 6530 / 10872 | 46/46 | 275 / 408 | 517 | tx=2M,rx=2M |
| Mi 8 → iPhone X | 25/50 | 9 | 8819 / 11698 | 25/25 | 270 / 416 | 517 | tx=2M,rx=2M |
| Nothing 2 → 12 mini | 48/50 | 34 | 2502 / 4408 | 48/48 | 171 / 189 | 517 | tx=2M,rx=2M |
| Nothing 2 → SE 2016 | 49/50 | 39 | 1758 / 2490 | 49/49 | 173 / 211 | 517 | tx=1M,rx=1M |
| Nothing 2 → SE 2020 | 43/50 | 24 | 2502 / 5624 | 43/43 | 169 / 182 | 517 | tx=2M,rx=2M |
| Nothing 2 → iPhone 15 | 47/50 | 28 | 2374 / 4352 | 47/47 | 170 / 186 | 517 | tx=2M,rx=2M |
| Nothing 2 → iPhone X | 46/50 | 21 | 2922 / 5968 | 46/46 | 156 / 183 | 517 | tx=2M,rx=2M |
| Pixel 6 → 12 mini | 48/50 | 37 | 2261 / 2997 | 48/48 | 251 / 308 | 517 | tx=2M,rx=2M |
| Pixel 6 → SE 2016 | 48/50 | 44 | 2284 / 2464 | 48/48 | 192 / 280 | 517 | tx=1M,rx=1M |
| Pixel 6 → SE 2020 | 45/50 | 43 | 2229 / 2381 | 45/45 | 258 / 264 | 517 | tx=2M,rx=2M |
| Pixel 6 → iPhone 15 | 46/50 | 44 | 2211 / 2464 | 46/46 | 257 / 295 | 517 | tx=2M,rx=2M |
| Pixel 6 → iPhone X | 50/50 | 47 | 2299 / 2500 | 50/50 | 334 / 367 | 517 | tx=2M,rx=2M |
| S10e → 12 mini | 46/50 | 21 | 3232 / 4869 | 46/46 | 290 / 338 | 517 | tx=2M,rx=2M |
| S10e → SE 2016 | 48/50 | 27 | 3213 / 5302 | 48/48 | 171 / 211 | 517 | tx=1M,rx=1M |
| S10e → SE 2020 | 49/50 | 30 | 2775 / 4979 | 49/49 | 287 / 333 | 517 | tx=2M,rx=2M |
| S10e → iPhone 15 | 49/50 | 39 | 2601 / 3607 | 49/49 | 287 / 335 | 517 | tx=2M,rx=2M |
| S10e → iPhone X | 44/50 | 18 | 3672 / 5503 | 44/44 | 383 / 482 | 517 | tx=2M,rx=2M |
| SE 2016 → Mi 8 | 50/50 | 50 | 2216 / 4340 | 50/50 | 207 / 267 | 185 | n/a |
| SE 2016 → Nothing 2 | 50/50 | 50 | 1681 / 2927 | 50/50 | 178 / 207 | 185 | n/a |
| SE 2016 → Pixel 6 | 50/50 | 50 | 1309 / 1646 | 50/50 | 177 / 208 | 185 | n/a |
| SE 2016 → S10e | 50/50 | 50 | 2234 / 3806 | 50/50 | 178 / 208 | 185 | n/a |
| SE 2016 → iPhone 15 | 50/50 | 50 | 1334 / 2136 | 50/50 | 177 / 208 | 185 | n/a |
| SE 2020 → Mi 8 | 50/50 | 50 | 2293 / 3943 | 50/50 | 207 / 267 | 515 | n/a |
| SE 2020 → Nothing 2 | 50/50 | 50 | 2173 / 3823 | 50/50 | 177 / 178 | 515 | n/a |
| SE 2020 → Pixel 6 | 50/50 | 50 | 1813 / 2202 | 50/50 | 177 / 207 | 515 | n/a |
| SE 2020 → S10e | 50/50 | 50 | 2399 / 3313 | 50/50 | 177 / 178 | 515 | n/a |
| SE 2020 → iPhone 15 | 50/50 | 50 | 1723 / 2143 | 50/50 | 178 / 207 | 515 | n/a |
| iPhone 15 → Mi 8 | 50/50 | 50 | 2219 / 4274 | 49/50 | 208 / 298 | 515 | n/a |
| iPhone 15 → Nothing 2 | 50/50 | 50 | 1995 / 3015 | 50/50 | 178 / 180 | 515 | n/a |
| iPhone 15 → Pixel 6 | 50/50 | 50 | 1696 / 2266 | 50/50 | 179 / 208 | 515 | n/a |
| iPhone 15 → S10e | 50/50 | 50 | 2326 / 2895 | 49/50 | 179 / 180 | 515 | n/a |
| iPhone 15 → SE 2016 | 50/50 | 50 | 1351 / 1786 | 50/50 | 180 / 235 | 185 | n/a |
| iPhone 15 → SE 2020 | 50/50 | 50 | 1666 / 2056 | 50/50 | 178 / 179 | 515 | n/a |
| iPhone X → 12 mini | 50/50 | 50 | 1589 / 1664 | 49/50 | 177 / 180 | 527 | n/a |
| iPhone X → Mi 8 | 50/50 | 50 | 2492 / 4666 | 50/50 | 207 / 267 | 517 | n/a |
| iPhone X → Nothing 2 | 50/50 | 50 | 2417 / 4616 | 50/50 | 206 / 237 | 517 | n/a |
| iPhone X → Pixel 6 | 50/50 | 50 | 1646 / 1965 | 49/50 | 177 / 177 | 517 | n/a |
| iPhone X → S10e | 50/50 | 50 | 2913 / 4379 | 49/50 | 177 / 207 | 517 | n/a |

Android-central GATT failures after retries, steps 1 and 2 (1,100 attempts, 962 ok, 634 on the first try):

| Error | Count | Centrals |
|---|---|---|
| `IllegalStateException: disconnected status=133` | 56 | Mi 8 24, Nothing 2 18, S10e 14 |
| `IllegalStateException: scan timeout` | 34 | Mi 8 34 |
| `IllegalStateException: scan failed 2` | 32 | Mi 8 32 |
| `IllegalStateException: service missing` | 13 | Pixel 6 11, S10e 1, Nothing 2 1 |
| `LinkClosedException: disconnected status=147` | 3 | Pixel 6 3 |

- The retries absorbed 637 status-133 and 30 status-147 first-try failures.
- The Mi 8 `scan failed 2` errors match the scanner-registration exhaustion that was later proven on the XCover 4. **[UNVERIFIED]** for the Mi 8: its registrations were never dumped.

### Step 2: suite (n=5 per pair, `phy=2m`)

Throughput is in kB/s and times are in ms. All values are medians.

- p→c is peripheral to central (GATT notify, or CoC).
- c→p is central to peripheral (GATT write-without-response, or CoC).
- "(k/n)" means only k of n attempts reached that test.
- One run is missing from this table: Mi 8 → iPhone X failed all 5 connects (`scan failed 2`, `scan timeout`).

| Pair | Connect ok/n | Notify kB/s | CoC p→c kB/s | WwR kB/s | CoC c→p kB/s | Echo GATT ms | Echo CoC ms | XX GATT ms | XXhfs GATT ms | XX CoC ms | XXhfs CoC ms | Msg1 classic GATT ms | Msg1 PQ GATT ms |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 12 mini → Mi 8 | 5/5 | 22.3 | 24.8 | 26.0 | 32.0 | 60 | 60 | 135 (2/5) | 210 | 134 | 178 | 60 | 136 |
| 12 mini → Nothing 2 | 5/5 | 23.5 | 25.7 | 28.8 | 31.7 | 60 | 60 | 120 | 180 | 120 | 150 | 60 | 148 |
| 12 mini → Pixel 6 | 5/5 | 25.4 | 24.6 | 29.6 | 32.7 | 60 | 60 | 120 | 180 | 120 | 150 | 60 | 120 |
| 12 mini → S10e | 5/5 | 19.7 | 21.6 | 19.3 | 22.3 | 60 | 60 | 120 | 210 | 120 | 180 | 60 | 151 |
| 12 mini → iPhone X | 5/5 | 25.1 | 24.6 | 23.0 | 22.1 | 60 | 60 | 120 | 180 | 120 | 151 | 60 | 122 |
| Mi 8 → 12 mini | 5/5 | 43.2 | 54.6 | 36.4 | 46.3 | 43 | 30 | 74 | 177 | 62 | 104 | 49 | 103 |
| Mi 8 → SE 2016 | 3/5 | 15.2 | 14.7 | 14.8 | 14.9 | 47 | 44 | 105 | 374 | 103 | 258 | 46 | 251 |
| Mi 8 → SE 2020 | 5/5 | 31.4 | 42.9 | 31.6 | 37.6 | 46 | 44 | 89 | 225 | 87 | 118 | 47 | 141 |
| Mi 8 → iPhone 15 | 3/5 | 43.2 | 48.8 | 40.3 | 49.9 | 60 | 30 | 88 | 151 | 60 | 105 | 68 | 92 |
| Nothing 2 → 12 mini | 5/5 | 47.7 | 54.8 | 44.2 | 50.5 | 45 | 32 | 88 | 124 | 73 | 101 | 37 | 87 |
| Nothing 2 → SE 2016 | 5/5 | 15.0 | 15.8 | 15.2 | 16.4 | 51 | 39 | 86 | 243 | 76 | 211 | 44 | 194 |
| Nothing 2 → SE 2020 | 5/5 | 49.0 | 57.1 | 46.1 | 54.0 | 44 | 30 | 79 | 125 | 63 | 91 | 42 | 77 |
| Nothing 2 → iPhone 15 | 5/5 | 49.1 | 56.4 | 47.0 | 54.0 | 44 | 32 | 80 | 122 | 73 | 103 | 39 | 78 |
| Nothing 2 → iPhone X | 3/5 | 64.9 | 77.8 | 59.7 | 57.4 | 45 | 32 | 91 | 111 | 90 | 106 | 42 | 74 |
| Pixel 6 → 12 mini | 5/5 | 51.7 | 61.1 | 50.4 | 53.3 | 30 | 30 | 62 | 132 | 61 | 104 | 31 | 89 |
| Pixel 6 → SE 2016 | 5/5 | 15.4 | 15.3 | 14.9 | 15.1 | 33 | 30 | 77 | 226 | 90 | 197 | 32 | 194 |
| Pixel 6 → SE 2020 | 5/5 | 53.4 | 62.7 | 55.1 | 59.1 | 30 | 30 | 64 | 134 | 60 | 93 | 31 | 79 |
| Pixel 6 → iPhone 15 | 5/5 | 52.9 | 63.0 | 55.1 | 57.4 | 30 | 30 | 62 | 107 | 60 | 90 | 30 | 79 |
| Pixel 6 → iPhone X | 4/5 | 72.1 | 81.3 | 79.0 | 82.3 | 31 | 30 | 61 | 91 | 62 | 81 | 30 | 62 |
| S10e → 12 mini | 4/5 | 54.8 | 60.2 | 51.9 | 57.7 | 33 | 23 | 65 | 106 | 60 | 90 | 33 | 74 |
| S10e → SE 2016 | 5/5 | 16.0 | 15.9 | 16.0 | 17.4 | 44 | 33 | 72 | 208 | 67 | 198 | 31 | 179 |
| S10e → SE 2020 | 5/5 | 60.9 | 71.4 | 64.4 | 68.9 | 34 | 23 | 57 | 101 | 47 | 68 | 24 | 69 |
| S10e → iPhone 15 | 5/5 | 57.8 | 67.6 | 64.2 | 62.3 | 33 | 23 | 59 | 101 | 48 | 90 | 32 | 66 |
| S10e → iPhone X | 5/5 | 56.5 | 62.4 | 56.4 | 63.6 | 35 | 29 | 55 | 103 | 50 | 102 | 29 | 66 |
| SE 2016 → Mi 8 | 5/5 | 6.2 | 6.5 | 6.4 | 6.1 | 62 | 75 | 147 (2/5) | 690 | 163 | 465 | 75 | 570 |
| SE 2016 → Nothing 2 | 5/5 | 6.7 | 6.7 | 7.0 | 6.8 | 60 | 60 | 120 (1/5) | 450 | 120 | 420 | 60 | 389 |
| SE 2016 → Pixel 6 | 5/5 | 7.3 | 7.0 | 7.3 | 7.0 | 60 | 60 | 148 (1/5) | 420 | 120 | 450 | 60 | 360 |
| SE 2016 → S10e | 5/5 | 6.7 | 6.5 | 6.6 | 6.1 | 60 | 60 | 146 (1/5) | 450 | 120 | 478 | 60 | 449 |
| SE 2016 → iPhone 15 | 5/5 | 6.5 | 6.4 | 6.4 | 6.8 | 60 | 60 | 120 | 508 | 120 | 480 | 60 | 420 |
| SE 2020 → Mi 8 | 5/5 | 24.6 | 25.7 | 25.4 | 30.4 | 60 | 60 | 150 (3/5) | 239 | 122 | 149 | 87 | 148 |
| SE 2020 → Nothing 2 | 5/5 | 26.3 | 26.3 | 26.0 | 28.4 | 60 | 60 | 120 | 210 | 120 | 150 | 60 | 124 |
| SE 2020 → Pixel 6 | 5/5 | 31.2 | 31.7 | 31.2 | 35.9 | 60 | 60 | 120 | 180 | 120 | 120 | 60 | 120 |
| SE 2020 → S10e | 5/5 | 25.1 | 26.3 | 27.0 | 27.7 | 60 | 60 | 120 | 181 | 120 | 150 | 60 | 149 |
| SE 2020 → iPhone 15 | 5/5 | 27.0 | 27.6 | 27.0 | 29.9 | 60 | 60 | 120 | 181 | 120 | 179 | 60 | 149 |
| iPhone 15 → Mi 8 | 5/5 | 24.5 | 23.5 | 26.7 | 28.8 | 60 | 60 | 150 (4/5) | 210 | 120 | 165 | 75 | 178 |
| iPhone 15 → Nothing 2 | 5/5 | 26.9 | 28.4 | 28.0 | 32.1 | 60 | 60 | 120 | 180 | 120 | 150 | 60 | 120 |
| iPhone 15 → Pixel 6 | 5/5 | 26.3 | 28.0 | 29.6 | 32.2 | 60 | 60 | 120 | 180 | 120 | 149 | 60 | 120 |
| iPhone 15 → S10e | 5/5 | 27.3 | 28.4 | 30.0 | 32.1 | 60 | 60 | 120 | 180 | 120 | 148 | 60 | 120 |
| iPhone 15 → SE 2016 | 5/5 | 5.5 | 5.7 | 6.0 | 5.8 | 61 | 62 | 147 | 539 | 122 | 481 | 60 | 481 |
| iPhone 15 → SE 2020 | 5/5 | 24.8 | 26.3 | 27.3 | 26.6 | 60 | 60 | 120 | 180 | 120 | 149 | 60 | 123 |
| iPhone X → 12 mini | 5/5 | 27.3 | 33.1 | 27.3 | 29.6 | 60 | 60 | 120 | 180 | 120 | 120 | 60 | 150 |
| iPhone X → Mi 8 | 4/5 | 22.1 | 27.6 | 23.5 | 30.4 | 75 | 60 | 180 (2/4) | 255 | 120 | 147 | 74 | 165 |
| iPhone X → Nothing 2 | 5/5 | 23.8 | 28.0 | 24.8 | 30.8 | 60 | 60 | 120 | 181 | 120 | 150 | 60 | 120 |
| iPhone X → Pixel 6 | 5/5 | 29.9 | 35.3 | 30.4 | 34.8 | 60 | 60 | 120 | 180 | 120 | 120 | 60 | 120 |
| iPhone X → S10e | 5/5 | 22.5 | 26.9 | 22.1 | 26.4 | 60 | 60 | 120 | 207 | 120 | 150 | 60 | 150 |

Readings:

- **Android central ↔ iPhone 15, SE 2020, 12 mini or iPhone X:**
  - GATT carried 31–79 kB/s per direction (Mi 8 lowest, 31–43 kB/s);
  - CoC carried 38–82 kB/s.
- **iPhone central:**
  - GATT carried 19–31 kB/s per direction;
  - CoC carried 22–36 kB/s.
- **SE 2016:** 5.5–17 kB/s in every role. Its ATT MTU is 185 and it has no 2M.
- **Handshakes over GATT:**
  - With an Android central and a 2M peer, XX took 55–91 ms and XXhfs 91–225 ms.
  - With an iPhone central, XX took 120 ms and XXhfs 180–255 ms.
  - With the SE 2016 at either end, XXhfs took 208–690 ms.
- **The PQ first message** (2,480 B vs 160 B) added about 30–120 ms with a 2M peer. With the SE 2016 it added about 150–500 ms.
- **iPhone central suites:**
  - `handshake_xx_classical` sometimes failed with `LinkClosedException: gatt-central:<uuid> closed` from attempt 2 on.
  - The SE 2016 against Android peripherals was worst, at 1/5 to 2/5. iPhone 15 → Mi 8 reached 4/5.
  - The suite then aborted, which is why `l2cap_connect` shows `timeout after failed` in those runs.
  - The cause is in [B1](#b1-iphone-central-gatt-link-closes).

### Step 3: 1M vs 2M (Android ↔ Android)

Suite runs (n=5, `connect_retries=3`):

| Pair (PHY) | Connect ok/n | Notify kB/s | CoC p→c kB/s | WwR kB/s | CoC c→p kB/s | Echo GATT ms | Echo CoC ms | XXhfs GATT ms | XXhfs CoC ms |
|---|---|---|---|---|---|---|---|---|---|
| Nothing 2 → Pixel 6 (1m) | 5/5 | 44.5 | 55.6 | 44.9 | 52.7 | 46 | 32 | 133 | 105 |
| Nothing 2 → Pixel 6 (2m) | 5/5 | 70.5 | 80.4 | 74.6 | 89.4 | 43 | 31 | 106 | 107 |
| Pixel 6 → Nothing 2 (1m) | 5/5 | 49.9 | 57.5 | 76.9 | 82.2 | 32 | 30 | 129 | 103 |
| Pixel 6 → Nothing 2 (2m) | 5/5 | 71.0 | 77.8 | 82.7 | 96.7 | 30 | 30 | 134 | 104 |
| Pixel 6 → S10e (1m) | 5/5 | 43.9 | 52.5 | 41.4 | 51.2 | 31 | 30 | 136 | 119 |
| Pixel 6 → S10e (2m) | 5/5 | 66.8 | 83.8 | 61.3 | 76.5 | 31 | 30 | 145 | 104 |
| S10e → Pixel 6 (1m) | 5/5 | 44.4 | 46.0 | 42.9 | 45.7 | 36 | 25 | 117 | 112 |
| S10e → Pixel 6 (2m) | 5/5 | 67.3 | 77.9 | 77.1 | 87.0 | 35 | 27 | 113 | 79 |

Connect runs without retries (n=20 per PHY, `plan=connect`):

| Central → peripheral | 1M ok/20 | 2M ok/20 | Errors |
|---|---|---|---|
| Pixel 6 → S10e | 9 | 16 | status=147, service missing |
| S10e → Pixel 6 | 13 | 14 | status=133 (2M: also one `disconnected status=0`) |
| Pixel 6 → Nothing 2 | 10 | 8 | status=147 |
| Nothing 2 → Pixel 6 | 15 | 12 | status=133 |
| Total | 47/80 | 50/80 | |

- **Throughput:** 2M gave 1.4–1.9× GATT and CoC throughput on Pixel 6 ↔ S10e and Nothing 2 → Pixel 6. Pixel 6 → Nothing 2 gained only 1.08–1.42×, because its 1M run already reported `tx=2M`: the 1M request was not fully honored.
- **Failures:** 2M did not add connect failures (47/80 vs 50/80 without retries). With retries, every pair reached 5/5 on both PHYs.

### 133/147 mitigation (n=20, 1M, 2026-11-01 12:43–12:53)

| Run | Direction | Variant | GATT ok/20 | First try | Errors |
|---|---|---|---|---|---|
| `m147-px-s10e-base` | Pixel 6 → S10e | baseline | 9 | 9 | 11 × status 147 |
| `m147-px-s10e-delay` | Pixel 6 → S10e | `connect_delay_ms=500` | 8 | 8 | 12 × status 147 |
| `m147-px-s10e-retry` | Pixel 6 → S10e | `connect_retries=3` | 19 | 13 | 1 × status 147 |
| `m147-s10e-px-base` | S10e → Pixel 6 | baseline | 14 | 14 | 6 × status 133 |
| `m147-s10e-px-retry` | S10e → Pixel 6 | `connect_retries=3` | 20 | 16 | — |

Retrying with a 500 ms × n backoff fixed most failures. A fixed delay between scan stop and `connectGatt` did not help. Every later matrix run used `connect_retries=3`.

### Step 4: crossed connections and the keep-one rule

Both phones ran `role=dual`: they advertised, scanned and connected to each other at the same time. Each node has a random 8-byte node ID, exchanged over the control characteristic. When two links exist between the same pair, both sides keep the link whose central has the lower node ID and drop the other.

| Run | Pair | Rounds | Crossed rounds | Outcome |
|---|---|---|---|---|
| `m4-dual-ip15-se2020-t01225653` | iPhone 15 ↔ SE 2020 | 20 | 20 | Rule applied correctly every time; 1 link left each round |
| `m4-dual-pixel6-s10e-t01224940` | Pixel 6 ↔ S10e | 20 | 4 | Rule applied correctly every time. The other 16 rounds ended with one link, because the second connect failed with 133/147. |
| `m4-dual-pixel6-ip15-t01224216` | Pixel 6 ↔ iPhone 15 | 20 | 0 | The iPhone 15 central discovered the Pixel 6 but timed out connecting while the Pixel 6 → iPhone 15 link was up |

- There were 0 rule violations in 24 crossed rounds. [RUN]
- The median time to detect a crossed link was 299 ms. [RUN]
- iOS ↔ iOS crossed in every round; Android ↔ iOS never did in these 20 rounds.

### Step 4: maximum concurrent links

**Central (hub):** the hub links one peer at a time until 3 adds in a row fail. After each add, and 6 times 5 s apart at the end, it sends an 8-byte echo on every held link. A link that fails the echo is dropped and counted.

| Hub | Role | Max held | Healthy at end (echo) | First add failure | Run |
|---|---|---|---|---|---|
| Pixel 6 (Android 17, API 37) | central | 8 | 8 of 8 | `timeout` | `m4-hub-pixel6-t01220051` |
| S10e (Android 12, API 31) | central | 7 | 7 of 7 | `disconnected status=133`, then `timeout` | `m4-hub-s10e-t01220443` |
| iPhone 15 (iOS 17.6.1) | central | 9 | 8 of 9 | `timeout`. The XCover 4 link failed `echo timeout`. | `m4-hub-ip15-t01220736` |
| iPhone X (iOS 16.7.16) | central | 8 | 8 of 8 | `scan timeout` | `m4-hub-ipx-t01221034` |
| Pixel 6 | peripheral | 8 | 8 held | — | `m4-phub-pixel6-t01221316` |
| iPhone 15 | peripheral | 4 at once | Links closed by the iPhone peripheral (`LinkClosedException … closed`; the SE 2016 also saw `CBErrorDomain:6`) | — | `m4-phub-ip15-t01222247` |
| iPhone X | peripheral | 4 | 4 held. Later centrals could not discover it (`scan timeout`). | — | `m4-phub-ipx-t01223218` |

- **All central maxima are lower bounds.** The hubs ran out of reachable peers.
  - Each hub run had at most 10 other phones.
  - In some runs the XCover 4 peripheral could not start (`openGattServer null after 4 retries`).
  - The Mi 8 peripheral failed to start in 3 of 4 hub runs with `IOException: Error: -1`. **[UNVERIFIED]** that this came from the CoC server socket.
- **The iOS peripheral limit is not a lower bound in the same sense.** Both iPhones stopped at 4 inbound links while more centrals were trying. See [B2](#b2-iphone-x-peripheral-not-discoverable) for the iPhone X. [Step 6](#step-6-ios-inbound-link-sweep) remeasured the peripheral role on all 5 iPhones: iOS 15 and 16 stop being visible at 4 links, while iOS 17, 18 and 27 held 6 healthy links and stayed visible.
- **iOS central coverage:** iOS 17 (iPhone 15) and iOS 16 (iPhone X), as requested. iOS 15 (SE 2016), iOS 18 (SE 2020) and iOS 27 (12 mini) were not measured as hubs.
- **Android central coverage:** one API ≤ 34 phone (S10e, API 31) and one API 35+ phone (Pixel 6, API 37).

### Legacy phones: P10 lite (API 26) and XCover 4 (API 28)

Neither phone supports 2M, and Android 8–9 have no CoC API, so these runs are GATT only. Each cell is GATT connects ok/total. `conn` is n=20 with `plan=connect`; `suite` is n=5 with `plan=suite`. "First" counts connects that needed no retry.

| Central → peripheral | m5 (no retries) | m5b (`connect_retries=3`, busy retry) | m5c (+ PHY skip) | m5d (reboot + `stopScan` fix) |
|---|---|---|---|---|
| P10 lite → Pixel 6 | conn 0/20, suite 0/5 | conn 0/20, suite 0/5 | **conn 16/20 (first 7), suite 5/5** | — |
| P10 lite → iPhone 15 | conn 0/20, suite 0/5 | conn 0/20, suite 0/5 | **conn 17/20 (first 7), suite 5/5** | — |
| Pixel 6 → P10 lite | conn 8/20, suite 4/5 | conn 15/20, suite 5/5 | — | — |
| iPhone 15 → P10 lite | conn 20/20, suite 5/5 (1/5 suites completed) | conn 20/20, suite 5/5 (1/5 suites completed) | — | — |
| XCover 4 → Pixel 6 | conn 2/20, suite 1/5 | conn 0/20, suite 0/5 | conn 0/20, suite 0/5 | **conn 20/20 (first 9), suite 5/5** |
| XCover 4 → iPhone 15 | conn 0/20, suite 0/5 | conn 0/20, suite 0/5 | conn 0/20, suite 0/5 | **conn 12/20 (first 2), suite 4/5** |
| Pixel 6 → XCover 4 | conn 0/20, suite 0/5 | conn 0/20, suite 1/5 | — | **conn 20/20 (first 10), suite 4/5** |
| iPhone 15 → XCover 4 | conn 2/20, suite 0/5 | conn 0/20, suite 0/5 | — | **conn 20/20, suite 5/5** |

Errors per phase:

- **m5:**
  - P10 lite central: `cccd not started` and `disconnected status=133`.
  - XCover 4 central: `scan failed 2`.
  - Pixel 6 → XCover 4: `scan timeout`, `status=147`.
  - iPhone 15 → XCover 4: `scan timeout`, `timeout after connected`.
- **m5b:**
  - P10 lite central: `cccd not started after 80 busy retries`.
  - XCover 4 central: `scan failed 2` in all 50.
  - XCover 4 peripheral: `scan timeout`, because it never advertised.
- **m5c:** the remaining P10 lite errors were `timeout after control_read` (3), `service missing` (2), `timeout after discovered` (1) and `status=133` (1).
- **m5d:**
  - XCover 4 → iPhone 15: 7 × `disconnected status=133`, 1 × `LinkClosedException: disconnected status=62` and 1 × `service missing`, after retries.
  - Pixel 6 → XCover 4: 1 × `disconnected status=147`.

Suite medians after the fixes (m5c, m5d; GATT, 1M):

| Pair | Connect ok/n | Notify kB/s | WwR kB/s | Echo GATT ms | XX GATT ms | XXhfs GATT ms | Msg1 classic GATT ms | Msg1 PQ GATT ms |
|---|---|---|---|---|---|---|---|---|
| P10 lite → Pixel 6 | 21/25 | 11.7 | 6.0 | 74 (4/5) | 249 | 718 | 150 | 860 |
| P10 lite → iPhone 15 | 22/25 | 13.9 | 8.3 | 72 | 105 | 357 | 45 | 345 |
| Pixel 6 → XCover 4 | 24/25 | 34.3 | 34.4 | 46 | 92 | 139 | 45 | 90 |
| XCover 4 → Pixel 6 | 25/25 | 32.1 | 29.9 | 30 | 73 | 144 | 30 | 91 |
| XCover 4 → iPhone 15 | 16/25 | 19.4 | 15.5 | 43 | 75 | 193 | 30 | 208 |
| iPhone 15 → XCover 4 | 25/25 | 10.0 | 12.0 | 60 | 120 | 330 | 60 | 242 |

#### Root cause: P10 lite

- Our `readPhy()` call crashed `com.android.bluetooth`. Logcat showed `F/bt_stack [FATAL:gatt_api.cc(699)] Assert failed: len == 5. Received bad response length: 1` in `read_phy_cb`, then SIGABRT. [LOG, not retained]
- The app then got `DeadObjectException`, which surfaced as `cccd not started` and as status 133.
- That crash appeared 19 and 16 times per 10-minute logcat window before the fix, and 0 times after. [LOG, not retained]
- Fix `0748cd2`: skip `setPreferredPhy`/`readPhy` when `isLe2MPhySupported()` is false.
- The remaining iPhone 15 → P10 lite suite failures are B1.

#### Root cause: XCover 4

- **Leaked scanner registrations.** After steps 5 and 5b, `dumpsys bluetooth_manager` listed 22 `ch.trancee.lab.host` scanner registrations (appIf 9 and 11–31). [LOG] `xcover4-btm-post-m5b.txt`
  - New scans failed after exactly 2.0 s with `scan failed 2` (app registration failed).
  - The stack finished the registration about 7 s later (`Context not found for ID 31`). [LOG, not retained]
  - The Google Play services scan-client count stayed constant (3 ongoing, 4 clients), so it was not the cause. [LOG] `xcover4-scan-samples.tsv`
- **The fix:**
  - The user rebooted the phone.
  - Fix `9d97545` pairs every `startScan` with `stopScan` in a `finally`, covering result, failure, timeout and cancellation.
  - Afterward the Scanner Map `Registered AppSize` was 4 before and 4 after the run, with 0 lab entries. The 30 s sampler read 4/0 in 35 samples and 5/1 in one, during an active scan. [LOG] `xcover4-btm-pre-m5d.txt`, `xcover4-btm-post-m5d.txt`, `xcover4-scan-samples-m5d.tsv`
- **`openGattServer` returned null** in every XCover 4 peripheral run before the reboot. `onServerRegistered` arrived about 17 s late, after the app's 10 s wait. [LOG] `x4-diag-*/xcover4-logcat.txt`
  - After the reboot it succeeded on the first try in all 8 m5d runs. [RUN] `m5d-*`
  - That the leaked registrations caused it is likely but **[UNVERIFIED]**: the reboot cleared the state before it could be tested in isolation.
- **The XCover 4 write stall:** the central's write-without-response callbacks completed, but the XCover 4 app saw fewer than 128 `onCharacteristicWriteRequest` calls and then none. [RUN] `x4-diag-*`
  - Write-with-response every 16 writes, `pace_ms=20`, `req_mtu=185`, `max_chunk=182`, and the combination, did not fix it. [RUN] `x4-mit*`
  - After the reboot, write-without-response reached 34.4 kB/s Pixel 6 → XCover 4 with no stall. [RUN] `m5d-suite-pixel6-xcover4-t02092310`
  - That the stall shared the stack-congestion cause is **[UNVERIFIED]**.
- **Remaining risk:** the stack can finish a scanner registration after it has reported failure to the app. Library code can't prevent that leak. This was not seen after the fix, but the run lasted only 15 minutes.

**Verdict:** both phones are usable for GATT at 1M after the prototype fixes. They match the "allowed, not fully supported" tier of ADR 0009. The weakest direction is XCover 4 central → iPhone 15, at 16/25.

### B1: iPhone-central GATT link closes

**Symptom:**

- With an iPhone central, suite attempt 2 and later failed `handshake_xx_classical` with `LinkClosedException: gatt-central:<uuid> closed`.
- The close came about 22 s after notify throughput finished.
- Attempt 1 always passed.

**Reproduced:**

| Run | Result |
|---|---|
| `diag-b1-ip15-p10lite` | 2/4 |
| `diag-b1c-ip15-p10lite` | 3/4 |
| `diag-b1d-se2016-pixel6` | 3/4 |
| `diag-b1-ip15-pixel6` (control with faster throughput) | 4/4 |

**Sequence in every failing attempt:** [LOG] `diag-b1b/`, `diag-b1c/`, `diag-b1d/`

1. On a reconnect, iOS `bluetoothd` reads the peer's GAP Device Name about 12–13 s after connecting (`Reading GAP name characteristic value`). On the first connect it reads it right away, which is why failures start at attempt 2. The read lands in the notify-throughput burst.
2. The Android peripheral stack drops the ATT response, because the L2CAP fixed channel is congested.
   - Android 8 logged `L2CAP - CID: 0x0004 cannot send, already congested` and `failed to write data to L2CAP`.
   - The Pixel 6 logged `attp_send_msg_to_l2cap: failed to write data to L2CAP`, with `xmit_hold_q` at 180/200.
3. Exactly 30 s after the read, `bluetoothd` logs `Timeout waiting for ATT response, forcing a disconnect`, with iOS reason 722.
   - Android sees HCI reason 0x13.
   - The app's `didDisconnectPeripheral` gets `error == nil`, or CBError 0.

**Ruled out:** a supervision timeout, an iOS idle disconnect and a stale link from the previous attempt.

Pacing on Android `onNotificationSent` does not prevent the congestion, because that callback fires when a notification is queued, not when it is sent.

### B2: iPhone X peripheral not discoverable

**Symptom:** in `m4-phub-ipx`, iPhone centrals could not find the iPhone X peripheral.

- The iPhone centrals came last in the start order. Every central that started after the iPhone X had 4 inbound links timed out on scan, including the P10 lite and XCover 4. The failure is not specific to iPhone centrals. [RUN] `m4-phub-ipx-t01223218`, `diag-b2-phub-ipx`
- **With 0 links held,** the iPhone 15 and SE 2020 discovered the iPhone X both with and without a service-UUID scan filter. The 128-bit UUID is in the primary service-UUID list, not the overflow area. [RUN] `diag-b2-ip15-survey-f0`, `-f1`, `diag-b2-se2020-survey-f0`, `-f1`
- **With 4 Android links held,**
  - `bluetoothd` on the iPhone X still reported `Advertising(2)`, with the connectable instance at 30 ms carrying our UUID. [LOG] `diag-b2/ipx-syslog.txt`
  - An unfiltered iPhone 15 survey saw 38 advertisers but not the iPhone X. [RUN] `diag-b2-ip15-survey-held4`
- **After the links closed,** it reappeared. [RUN] `diag-b2-ip15-survey-after`
- **With 3 links held,** a 4th central still connected. [RUN] `m4-phub-ipx-t01223218`
- **Best explanation:** the iPhone X controller cannot schedule advertising next to 4 peripheral-role connections. **[UNVERIFIED]**
- The iPhone 15 peripheral also stopped at 4 inbound links in step 4, but closed links rather than going invisible. Step 6 did not reproduce that.
- [Step 6](#step-6-ios-inbound-link-sweep) shows the same invisibility on the SE 2016 (iOS 15), but not on iOS 17, 18 or 27.

### Step 6: iOS inbound-link sweep

**Question:** is "invisible at 4 inbound links" specific to the iPhone X, or does it hold on every iPhone? The answer sets the iOS peripheral-role cap per iOS version.

**Date:** 2026-11-02, 10:02–10:52 CET, about 50 min of device time.

**Method** (`python3 host/bakeoff.py inbound`):

- The hub iPhone runs `role=peripheral` and advertises our service.
- Android centrals join one at a time with `role=hold`, `scan_timeout=20` and `connect_retries=3`. Each holds its link and sends a 64-byte echo every 2 s. The order was Nothing 2, S10e, XCover 4, P10 lite, Mi 8, then Pixel 6.
- If a central fails to join, the next candidate tries the same k. The sweep stops after 2 failed candidates.
- At each k:
  - **Links healthy:** the echo count of every held link rose during the probe window, and no link ended.
  - **Discovery probe:** a separate iPhone and the Pixel 6 scan in parallel. First a 30 s scan filtered by our service UUID, then a 20 s unfiltered scan. Neither scanner holds a link to the hub.
  - **Time to first sighting** is measured from scan start. An iOS RSSI of 127 means "not available".
  - **Closes:** any link the hub closes, with its error.
- **Scanners:** the iPhone X for the SE 2016 hub; the SE 2016 for every other hub. The Pixel 6 was the Android scanner up to k = 5. At k = 6 it became the 6th central, so k = 6 has no Android probe. No other Android 10+ phone was free.
- **Builds:** Android app `0220549`. The iOS app was not rebuilt; it is the diag-B2 build with the survey mode from `78ebef1`. Host sweep code `5d549db` (the runs before 10:43 used the same code, uncommitted on top of `0220549`).

**Results** [RUN] (full tables with RSSI: [`summary/summary-m6.md`](summary/summary-m6.md)):

| Hub | iOS | k = 1 | k = 2 | k = 3 | k = 4 | k = 5 | k = 6 | Run |
|---|---|---|---|---|---|---|---|---|
| SE 2016 | 15.8.8 | 1/1, seen | 2/2, seen | 3/3, seen | 4/4, **not seen** | no 5th link | — | `m6-inbound-se2016-r2` |
| iPhone X (control) | 16.7.16 | 1/1, seen | 2/2, seen | 3/3, seen | 4/4, **not seen** | no 5th link | — | `m6-inbound-ipx` |
| iPhone 15 | 17.6.1 | 1/1, seen | 2/2, seen | 3/3, seen | 4/4, seen | 5/5, seen | 6/6, seen | `m6-inbound-ip15` |
| SE 2020 | 18.7.8 | 1/1, seen | 2/2, seen | 3/3, seen | 4/4, seen | 5/5, seen | 6/6, seen | `m6-inbound-se2020` |
| 12 mini | 27.0.1 | 1/1, seen | 2/2, seen | 3/3, seen | 4/4, seen | 5/5, seen | 6/6, seen | `m6-inbound-ip12mini` |

Cells give healthy links / held links, then whether the hub was seen. "Seen" means every completed probe (iPhone and Android, filtered and unfiltered) found the hub. "Not seen" means every probe completed and none found it.

- **Time to first sighting,** when seen: 15–150 ms on every probe. Scanners saw the hub in the first scan window or not at all.
- **Closes:** no hub closed a link in any of the 5 sweeps.
- **SE 2016 and iPhone X at k = 4:** all 4 links stayed healthy, but no scanner saw the hub, filtered or unfiltered. Both k = 5 candidates then failed: the Mi 8 with `hold: no link after 5 attempts` (scan timeouts and `scan failed 2`), the Pixel 6 with `IllegalStateException: scan timeout`. This repeats B2.
- **iOS 17, 18 and 27 at k = 6:** all 6 links healthy and the hub still visible. 6 is a lower bound; there were no more free centrals. The 6th central needed 42 s (iPhone 15) and 58 s (SE 2020) to join, versus 5.6 s on the 12 mini. **[UNVERIFIED]** why.
- **Incomplete probe:** in `m6-inbound-se2016-r2-k2`, the iPhone X unfiltered probe wrote no `done` record. It is reported as incomplete, not as "not seen". The other 3 probes at that k saw the hub.
- **Superseded first SE 2016 sweep** (`m6-inbound-se2016`, status `diagnostic`): k = 1 and 2 were healthy and visible. At k = 3 the Mi 8 failed to join (status 133 five times), and the Nothing 2 link closed (`LinkClosedException … closed: unsubscribed`, Android status 8). That sweep counted a failed central as held and had no retries. `m6-inbound-se2016-r2` replaced it.

**Conclusion:**

| iOS major | Phones | Largest k with all links healthy and the hub visible | Measured headroom |
|---|---|---|---|
| 15 | SE 2016 | 3 | 3 |
| 16 | iPhone X | 3 | 3 |
| 17 | iPhone 15 | ≥ 6 (lower bound) | 6 |
| 18 | SE 2020 | ≥ 6 (lower bound) | 6 |
| 27 | 12 mini | ≥ 6 (lower bound) | 6 |

- **Accepted for v1 (2026-11-02):** cap 3 inbound links on every iOS version (ADR 0009). The routing simulation passed with 3 and gained nothing at 4; the step 4 closes below are unexplained. The 6 links measured on iOS 17+ are recorded as headroom for a later release.
- iOS 15 and 16 agree: at 4 inbound links the phone stops being discoverable, while the links stay up. Cap them at 3, so the phone stays visible for new neighbors.
- iOS 17+ showed no limit up to 6, the most centrals we had. A cap of 6 is measured on iOS 17, 18 and 27. Higher is **[UNVERIFIED]**.
- One phone per iOS version was measured. The split could be due to the iOS version or to the hardware (A9/A11 vs A13+). The data can't separate them. **[UNVERIFIED]**
- **Conflict with step 4:** in `m4-phub-ip15-t01222247` the iPhone 15 closed links at 3–4 inbound links. Step 6 held 6 on the same phone without a close. Differences: step 4 included iPhone centrals (iOS ↔ iOS links), the centrals arrived about 10 s apart, and it ran the older app build `be5c761`. The cause of the step 4 closes is **[UNVERIFIED]**.

## 6. Excluded devices

### OPPO CPH2385: unreachable

- **Result:** 8 step-1 runs on 2026-11-01, 15:56–18:19 CET. 0 of 400 GATT connect attempts succeeded. Every attempt failed with `IllegalStateException: scan timeout`. [RUN]
  - The OPPO was central in `m1-oppo-ip15`, `m1-oppo-se2020`, `m1-oppo-ip12mini` and `m1-oppo-se2016`.
  - The OPPO was peripheral in `m1-ip15-oppo`, `m1-se2020-oppo`, `m1-ip12mini-oppo` and `m1-se2016-oppo`.
- **Counts:** the user's exclusion note said "0 of 323 attempts" over 7 runs. That was the count at the time; the 8th run, `m1-se2016-oppo-t01180152`, finished afterward with 50 more failures.
- **`INVALID-m1-oppo-ipx-t01181937-aborted-oppo-excluded`** was stopped within seconds when the OPPO was excluded. It has no results.
- **What was checked:**
  - Bluetooth was on (`bluetooth_on=1`, `state: ON`).
  - The screen was awake and our activity was top-resumed.
  - `dumpsys bluetooth_manager` showed our legacy connectable advertisement with the service UUID.
  - The controller reports `mMaxScanFilters: 0`, which means no hardware filters.
  - Our app's filtered scans: 200 started, 0 results.
  - Other apps on the same phone got results: Google Play services 7,510, `ch.trancee.cairn.android` 679.
  - No iPhone ever discovered the OPPO's advertisement.
- **Unverified explanations:**
  1. The phone was out of radio range. It was attached over Wi-Fi adb (`oppo-wifi:5555`), not the USB hub next to the other phones.
  2. A software scan-filter problem on a controller with no hardware filters.
- The user approved the exclusion (`--skip redmi11 oppo`). There was no rerun.

### Xiaomi Redmi Note 11: install refused

`adb install` failed with `INSTALL_FAILED_USER_RESTRICTED`. MIUI requires an on-device confirmation or *Install via USB*, which is a human step. The phone was skipped (`--skip redmi11`) and has no runs.

## 7. Limitations and unverified items

- **Bonded (secure) CoC is untested.** The harness supports it (`bearers=coc_secure`), but no phone was bonded.
- **Coded PHY is untested.** Only 1M and 2M were compared, on 4 Android ↔ Android pairs.
- **Concurrency limits are lower bounds** for every central, limited by reachable peers.
  - iOS central was measured on iOS 17 and 16 only.
  - iOS peripheral was measured on every iOS version in step 6, one phone each, with Android centrals only. Above 6 inbound links is untested.
- **The scanner-registration race:** the Android stack can finish a registration after it reported failure, and the app can't release it. Seen on the XCover 4. **[UNVERIFIED]** on other phones, including the Mi 8 `scan failed 2`.
- **Unverified causes:**
  - the 4-link invisibility on iOS 15 and 16 (B2, step 6), and whether it follows the iOS version or the hardware;
  - the iPhone 15 link closes at 4 inbound links in step 4, not reproduced in step 6;
  - the XCover 4 `openGattServer` null;
  - the XCover 4 write stall;
  - the OPPO;
  - the Mi 8 peripheral `IOException: Error: -1`.
- **Logs not retained:** the P10 lite `readPhy` stack abort and the XCover 4 late registration (`Context not found for ID 31`) were seen live, but those log lines were not saved.
- **Coverage:**
  - Each pair ran in one location, one session, with a few phones idle nearby.
  - Android ↔ Android was measured only for Pixel 6 ↔ S10e and Pixel 6 ↔ Nothing 2 (steps 3–4).
  - iPhone ↔ iPhone was measured only for iPhone 15 ↔ SE 2020, iPhone 15 ↔ SE 2016 and iPhone X ↔ 12 mini.
  - Android 15+ peripherals and iOS 26+ centrals are covered by one phone each.
- **Sample sizes:** n=5 for throughput is small. The tables give medians, and p90 is in `summary/`.
- **Clock:** `setup_ms` and RTT are single-device monotonic times. No cross-device clock sync was used.
- **Background, power and range** were not measured. Everything ran in the foreground at short range.
- **Load on the host:** a routing simulator ran on the host Mac during m5d. It took CPU only, not radio, and the phones do their own timing.

## 8. Raw data

- [`raw/`](raw/) holds one directory per run, 275 in all: every run's JSONL as pulled from the phones, plus `run.json` where the run had one. Step 6 directories also hold `sweep.json` (base) and `step.json` (per k). Each JSONL file is gzipped (`gzip -9n`, about 4 MB in all; about 23 MB uncompressed). Read one with `gzip -dc <file>.jsonl.gz`. The `bytes` column of `raw/MANIFEST.csv` gives uncompressed sizes.
- [`raw/MANIFEST.csv`](raw/MANIFEST.csv) lists, per run: phase, status, host commit, launch time, devices, arguments, files and bytes.
- **Statuses:**
  - `valid`: matrix steps and follow-ups.
  - `smoke`: build and orchestration smokes, superseded by the matrix.
  - `diagnostic`: the 133/147, XCover 4 stall and B1/B2 investigations, and the superseded first step-6 sweep `m6-inbound-se2016`.
  - `device-unreachable`: the 8 OPPO runs. They are kept as evidence of the failure.
  - `invalid`: `INVALID-m1-oppo-ipx-…`, `INVALID-m1-pixel6-se2016-…` (attached `idevicedebug` killed the app; rerun as `m1-pixel6-se2016-t01153739`) and three `i1-pixel6-ip12mini-INVALID-*` smokes (concurrent orchestrators, stale device file). Ignore them for results.
- **Record format:** `{run_id, commit, date, role, reporter{model, os, platform}, central, peripheral, test, bearer, phy, mtu, attempt, outcome, timings, bytes, error, …}`.
- The raw files are byte-for-byte copies. `summarize.py` never changes them.

### Diagnostic captures

These captures, about 277 MB, are **not** in this folder. They stay in `prototypes/bearer-bakeoff/host/results/` on branch `prototype/bearer-bakeoff`. They are gitignored, so they exist only on the lab Mac.

| Path | Contents |
|---|---|
| `diag-b1/` | `s10e-logcat.txt`, `se2016-syslog.txt` (15 MB) |
| `diag-b1b/` | `ip15-syslog.txt`, `p10lite-logcat.txt`, `pixel6-logcat.txt` (14 MB) |
| `diag-b1c/` | `ip15-bluetoothd.txt`, `p10lite-logcat.txt` (6 MB) |
| `diag-b1d/` | `pixel6-logcat.txt`, `se2016-bluetoothd.txt` (3 MB) |
| `diag-b2/` | `ipx-syslog.txt`, `phub.log` (220 MB) |
| `diag-m5d/` | `xcover4-logcat.txt`, `matrix-step5d.log` (11 MB) |
| `x4-diag-1/`, `x4-diag-2/` | `pixel6-logcat.txt`, `xcover4-logcat.txt` (1.7 MB) |
| `xcover4-btm-pre.txt`, `xcover4-btm-post-m5b.txt`, `xcover4-btm-pre-m5d.txt`, `xcover4-btm-post-m5d.txt` | `dumpsys bluetooth_manager` snapshots |
| `xcover4-scan-samples.tsv`, `xcover4-scan-samples-m5d.tsv` | Scanner-registration sampler. Columns: time, registered app size, lab entries, regular scan clients, GMS ongoing scans, lab pid. |
| `*/orchestrator.log`, `matrix.log`, `matrix-driver.log` | Orchestrator and matrix-driver logs |
| `grant/` | XCUITest `grant-ios` logs per iPhone |
| `install-*.log`, `build-ios-*.log` | Install and build logs |

## 9. How to reproduce

The iOS profiles from the free teams expire after 7 days (around 2026-11-07). Rerunning on iPhones after that needs re-signing.

```sh
git worktree add ../MeshLink-bakeoff prototype/bearer-bakeoff
cd ../MeshLink-bakeoff/prototypes/bearer-bakeoff
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ADB_SERVER_SOCKET=tcp:adb-server:5037      # remote adb server of the lab

python3 host/bakeoff.py build android ios
python3 host/bakeoff.py install pixel6 nothing2 s10e mi8 p10lite xcover4 ip15 se2020 ip12mini ipx se2016
python3 host/bakeoff.py grant-ios ip15 se2020 ip12mini ipx se2016   # accepts the Bluetooth alert by exact label

# Phases: step1-connect step2-suite step3-phy step4-multi step5-legacy step5b-legacy step5c-legacy-central step5d-xcover4
python3 host/matrix.py list step1-connect --skip redmi11 oppo
python3 host/matrix.py run  step1-connect --skip redmi11 oppo

# One summary per phase. A pair's rows merge across every directory passed.
cd host/results && python3 ../summarize.py m1-*-t* --markdown summary-m1.md --csv summary-m1.csv

# Step 6: one inbound-link sweep per hub, then the per-hub tables.
cd ../.. && python3 host/bakeoff.py inbound --hub ip15 --centrals nothing2 s10e xcover4 p10lite mi8 pixel6 --ios-scanner se2016 --android-scanner pixel6
cd host/results && python3 ../summarize_inbound.py m6-inbound-ip15 > summary-m6.md
```

- The full matrix took about 10.5 h.
- Steps 5b–5d need the fixed app builds listed in [Commits](#commits).
- A single pair can run with `python3 host/bakeoff.py pair --central <a> --peripheral <b> --run <id> n=… bearers=… phy=… plan=…`.
- Run one BLE pair at a time. A concurrent advertiser contaminated `m5b-suite-p10lite-pixel6` attempts 1–3.

## 10. Evidence record (Constitution T9)

| Field | Value |
|---|---|
| Devices and OS | Pixel 6 Android 17 (API 37); Nothing Phone (2) Android 16 (36); Galaxy S10e Android 12 (31); Mi 8 Android 10 (29); Galaxy XCover 4 Android 9 (28); P10 lite Android 8.0 (26); iPhone 15 iOS 17.6.1; iPhone SE 2020 iOS 18.7.8; iPhone 12 mini iOS 27.0.1; iPhone X iOS 16.7.16; iPhone SE 2016 iOS 15.8.8. Excluded: OPPO CPH2385 Android 14 (34), unreachable; Redmi Note 11 Android 13 (33), install refused. |
| Firmware | Not recorded (Bluetooth controller firmware and Android build fingerprints). |
| Covered platform pairs | Android 10+ central → iPhone (Pixel 6, Nothing 2, S10e, Mi 8 × all 5 iPhones); iPhone central → Android 10+ (all 5 iPhones × Pixel 6, Nothing 2, S10e, Mi 8); iPhone ↔ iPhone (iPhone 15 ↔ SE 2020 both ways, iPhone 15 ↔ SE 2016 both ways, iPhone X ↔ 12 mini both ways); Android ↔ Android (Pixel 6 ↔ S10e, Pixel 6 ↔ Nothing 2, both ways); Android 8/9 ↔ Pixel 6 and iPhone 15, both ways. Hubs: Pixel 6, S10e, iPhone 15 and iPhone X as central; Pixel 6, iPhone 15 and iPhone X as peripheral. Inbound-link sweep (step 6): SE 2016, iPhone X, iPhone 15, SE 2020 and 12 mini as peripheral, up to 6 Android centrals. |
| Not covered | Bonded CoC, Coded PHY, background operation, Android ↔ Android beyond the pairs above, CoC on Android 8/9 (no API), OPPO, Redmi. |
| Commit | Prototype branch `prototype/bearer-bakeoff`. Per-run host `HEAD` in `run.json` and in every record. App builds `be5c761` (steps 1–5), `31ac3f7`/`0748cd2` (5b/5c), `9d97545` (5d), Android `0220549` with iOS `78ebef1` (6). |
| Method | Python orchestrator `host/matrix.py` over remote adb, `devicectl` and libimobiledevice; foreground apps, screen on, no bonding; see [Method](#3-method). |
| Raw results | [`raw/`](raw/) (JSONL and `run.json`), [`summary/`](summary/) (per-phase tables); diagnostic captures on `prototype/bearer-bakeoff` (see [Diagnostic captures](#diagnostic-captures)). |
| Date | 2026-11-01 12:00 – 2026-11-02 10:52 CET |
