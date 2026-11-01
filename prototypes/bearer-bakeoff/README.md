# PROTOTYPE: BLE bearer bake-off (throwaway, never merged)

This is a **throwaway prototype**. It lives only on branch `prototype/bearer-bakeoff` and is never merged to `main`.
It answers issue 12 (`.scratch/meshlink-v1/issues/12-bearer-phy-bakeoff.md`):

- Is GATT (notify plus write-without-response) enough, or is an L2CAP CoC upgrade worth it on real Android and iOS devices?
- Does requesting LE 2M PHY help on Android, or does it add failures?
- How long does a classical Noise XX handshake take to deliver over BLE, compared with a PQ XXhfs handshake? Both use dummy bytes sized per ADR 0002.

Nothing here is production code. There are no tests, no polish and no real crypto.

## Layout

| Path | Contents |
|---|---|
| `shared/src/commonMain` | Protocol, framing (GATT 1-byte sequence header; CoC u8 type + u32 length), central suite, responder, keep-one rule, JSONL recorder |
| `shared/src/iosMain` | Kotlin/Native CoreBluetooth drivers: `IosCentral`, `IosPeripheral`, `StreamByteLink` (CBL2CAPChannel streams), `BakeoffIos` entry point |
| `androidApp` | Android app (`ch.trancee.lab.host`): `AndroidCentral`, `AndroidPeripheral`, `MainActivity` (status text) |
| `iosApp` | SwiftUI shell (`project.yml` for xcodegen) that embeds the `Bakeoff` framework and passes launch arguments through |
| `host/bakeoff.py` | Host orchestrator (Python 3 stdlib only): build, install, launch, pull, summarise |
| `host/results/` | Raw JSONL per run (gitignored) |

GATT service `6e4b0001-8f3c-4d2a-9b61-2c7a5d3e9f10` (see `Protocol.kt`) has four characteristics: control/node-ID read, data notify, data write-without-response, and PSM read. The PSM is read over GATT on every connection.

## Build

The ADR 0005 toolchain applies, with one deviation: JDK 25 is not installed on this Mac, so the Gradle daemon runs on JDK 21.

```sh
cd prototypes/bearer-bakeoff
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
python3 host/bakeoff.py build android ios   # gradle assembleDebug + linkReleaseFrameworkIosArm64 + xcodegen + xcodebuild per team
python3 host/bakeoff.py install pixel6 s10e ip15 se2016   # any device names from DEVICES in bakeoff.py
```

## Run

```sh
python3 host/bakeoff.py pair --central pixel6 --peripheral s10e --run s1-px-s10e \
    --timeout 400 n=5 bearers=gatt+coc phy=2m plan=suite
```

The orchestrator:

1. Stops both apps.
2. Starts the peripheral (`role=peripheral`), waits `--settle` seconds, then starts the central.
3. Polls the central's JSONL for a `done` record.
4. Pulls both files to `host/results/<run>/` and prints a summary.

Central arguments:

| Argument | Values |
|---|---|
| `n` | Number of attempts |
| `bearers` | `gatt`, `coc`, `coc_secure`; separate several with `+`, not commas |
| `phy` | `default`, `1m` or `2m` |
| `plan` | `connect` (setup time and failure rate only) or `suite` |

Multi-device runs. All devices share one run id, so the run tag in the control characteristic matches:

```sh
python3 host/bakeoff.py hub  --hub pixel6 --peers s10e ip15 --run h1 --timeout 900 phy=2m   # central-role max concurrency (role=hub-central vs role=peripheral)
python3 host/bakeoff.py phub --hub pixel6 --peers s10e ip15 --run p1 --timeout 600 phy=2m   # peripheral-role max concurrency (role=peripheral vs role=hold)
python3 host/bakeoff.py dual pixel6 ip15 --run d1 --timeout 700 n=20 round=20 phy=2m        # crossed connections + keep-one rule (role=dual on both)
python3 host/bakeoff.py stop                 # stop the lab app on every device (Android force-stop, devicectl terminate, idevicedebug kill)
python3 host/bakeoff.py grant-ios ip15 ipx   # XCUITest runner: accepts the Bluetooth alert by exact label (Allow/OK/Erlauben)
```

Mitigation knobs, all opt-in and Android central only (recorded in `run.json` and shown as `variant` by `summarize.py`):

| Knob | Effect |
|---|---|
| `connect_retries=N` | Retry a GATT connect that ends `disconnected status=133/147` before connecting, with backoff `500 ms × retry`; each retry leaves a `retryN_<status>` timing mark |
| `connect_delay_ms=N` | Wait between scan stop and `connectGatt` |
| `write_ack_every=N` | Every Nth data write uses write-with-response |
| `pace_ms=N` | Sleep after every data write |
| `max_chunk=N` | Cap the GATT value size |
| `req_mtu=N` | `requestMtu(N)` (no effect on Android 14+, which always negotiates 517) |

Matrix and summary:

```sh
python3 host/matrix.py list step1-connect --skip redmi11       # done/todo per run (resumable)
python3 host/matrix.py run  step1-connect --skip redmi11       # phases: smoke-ios step1-connect step2-suite step3-phy step4-multi step5-legacy
python3 host/summarize.py host/results/m1-* --markdown out.md --csv out.csv
```

Matrix run ids always get a `-t<ddHHMMSS>` suffix. Device result files are append-only per run id, so a reused id would inherit an old `done` record. `summarize.py` never modifies the raw JSONL. It groups rows by (central, peripheral, requested PHY, variant, test, bearer) and reports ok/total, first-try successes, and the median/p90/min/max of the headline metric. Setup time is `setup_ms = first_app_byte - scan_start`, which excludes the scan pacing wait. It also adds hub, phub and dual tables.

Every record is one JSON line: `{run_id, commit, date, central, peripheral, test, bearer, phy, mtu, attempt, outcome, timings, bytes, error}`.

## Orchestration per device class (as verified)

| Device class | Install | Launch with arguments | Pull results | Stop |
|---|---|---|---|---|
| Android (remote adb, `ADB_SERVER_SOCKET=tcp:adb-server:5037`) | `adb install -r -g` ✅. Redmi Note 11: `INSTALL_FAILED_USER_RESTRICTED` (needs the on-device MIUI confirmation / *Install via USB*) | `am start -S -n ch.trancee.lab.host/.MainActivity --es args k=v,k=v` ✅ | `adb exec-out run-as ch.trancee.lab.host cat files/results/<run>.jsonl` ✅ (logcat tag `BAKEOFF` too) | `am force-stop` ✅ |
| iOS 17+ (iPhone 15, SE 2020, 12 mini) | `xcrun devicectl device install app` ✅ (in-place update) | `xcrun devicectl device process launch --terminate-existing <bundle> k=v …` ✅ | `devicectl device copy from --domain-type appDataContainer --domain-identifier <bundle>` ✅ | `devicectl device info processes` + `process terminate --pid` ✅ |
| iOS 15/16 (SE 2016, iPhone X) | `ideviceinstaller -i` ✅ | `idevicedebug -u <udid> --detach run <bundle> k=v …` ✅ (Xcode 16.4 DDI). Attached mode fails on the SE 2016: debugserver reports "Thread stopped" right after launch and idevicedebug kills the app | `afcclient -u <udid> --container <bundle>` with `get -f Documents/results/<run>.jsonl <dest>` ✅. Without `-f` it refuses to overwrite and still exits 0 | `idevicedebug -u <udid> kill <bundle>` ✅ |
| iOS permission (all) | — | `grant-ios`: `xcodebuild test-without-building -xctestrun … -destination id=<udid>` ✅ on iOS 15.8, 16.7, 17.6, 18.7 and 27.0. On iOS 27, `XCUIApplication.launch()` fails ("process identifier … could not be determined"); the host launches the app with `grant=1` and the runner attaches (`TEST_RUNNER_GRANT_ATTACH=1`) | log in `host/results/grant/` | — |

## Signing (free personal teams, automatic signing, `-allowProvisioningUpdates`)

| Team | Bundle ID | iPhones in its profile | Installed with this team |
|---|---|---|---|
| apple-team-1 | `ch.trancee.lab.host` | iPhone 15, SE 2020, 12 mini | iPhone 15, SE 2020, 12 mini |
| apple-team-2 | `ch.trancee.lab.host.b` | SE 2016, iPhone X, iPhone 15 | SE 2016, iPhone X |

Free teams cannot register the same App ID, so team B uses the `.b` suffix.

## Human-in-the-loop steps

1. On each iPhone: Settings → General → VPN & Device Management → trust "Apple Development: owner". Team B's certificate may show a different name.
2. At the first launch on each iPhone, tap **Allow** on the Bluetooth permission prompt, or run `grant-ios`. The tap path is unverified: every phone already had the permission, so no alert appeared. The app logs `ios_bt_authorization` as the first JSONL record.
3. Redmi Note 11: confirm the MIUI install prompt / enable *Install via USB*. Its runs are skipped until then (`--skip redmi11`).

## Deviations from the agreed design

- JDK 21 daemon, because JDK 25 is absent.
- Team B uses a suffixed bundle ID (`ch.trancee.lab.host.b`).
- The Android central requests `CONNECTION_PRIORITY_HIGH` after connecting.
- Android scans are paced at least 6.5 s apart, to stay under the 5-scans-per-30 s limit.
- `connectGatt` always initiates on 1M|2M. A 2M-only initiating mask fails with status 147, because the legacy advert is on 1M. `phy=` then controls `setPreferredPhy`.
- Each suite test has a 30 s deadline. A stalled test is recorded as a `fail` under its own name, and the suite then aborts.

## Unreachable device: OPPO CPH2385 (Android 14, API 34, Helio G35)

- **Result:** 8 step-1 runs on 2026-11-01, 15:56–18:19 CET, with 0 of 400 GATT connect attempts succeeding. Every attempt failed with `IllegalStateException: scan timeout`, in both roles, against the iPhone 15, SE 2020, 12 mini and SE 2016.
  - The OPPO was central in `m1-oppo-ip15`, `m1-oppo-se2020`, `m1-oppo-ip12mini` and `m1-oppo-se2016`.
  - The OPPO was peripheral in `m1-ip15-oppo`, `m1-se2020-oppo`, `m1-ip12mini-oppo` and `m1-se2016-oppo`.
  - The raw JSONL is kept.
- **What was checked:**
  - Bluetooth is on (`bluetooth_on=1`, `state: ON`), the screen is awake and our activity is top-resumed.
  - `dumpsys bluetooth_manager` shows our legacy, connectable advert with the service UUID.
  - The controller reports `mMaxScanFilters: 0` (no hardware filters).
  - Our app's filtered scans: 200 started, 0 results. Other apps on the same phone do get results (Google Play services 7510, `ch.trancee.cairn.android` 679).
  - No iPhone ever discovered the OPPO's advert.
- **Unverified explanations:**
  1. The phone is out of radio range. It is attached over Wi-Fi adb (`oppo-wifi:5555`), not the USB hub next to the other phones.
  2. A software scan-filter problem on this controller, which has no hardware filters.
- **Handling:** The user approved excluding the OPPO from the remaining runs (`--skip redmi11 oppo`); there will be no rerun. The run `m1-oppo-ipx` was aborted within seconds of starting, before any result was produced (`INVALID-m1-oppo-ipx-…`).

## Findings while building (not measurements)

- **K/N `NSLog` crash.** Passing a Kotlin `String` through `NSLog("%@", …)` varargs crashed with `SIGTRAP`. The `nsLog()` helper formats in Kotlin and escapes `%`.
- **iOS peripheral, link reuse.** An unbonded Android central that reconnects gets no new `didSubscribe`. The control read (offset 0) therefore replaces a missing, closed or already-used server link (`reopened_on_control_read` event).
- **iOS central latency quantisation.** iOS centrals use a connection interval of about 30 ms, which apps cannot set. With an iPhone central, RTT quantises to 60/120 ms.
- **XCover 4 (API 28) c→p stall.** The central completes every write-without-response callback, but the XCover 4 app gets fewer than 128 `onCharacteristicWriteRequest` calls and then none. Write-with-response every 16 writes, `pace_ms=20` and `max_chunk=182` (alone and combined) did not fix it. The XCover 4 stack is congested: `onServerRegistered` arrives about 17 s late, which makes `openGattServer` return null after its 10 s wait (the app now retries), and Google Play services runs a continuous 25 % duty scan with more than 1.1 M results.
- **133/147 connect failures** (n=20, `plan=connect`, 1M, 2026-11-01):

  | Run | Direction | Variant | Result |
  |---|---|---|---|
  | `m147-px-s10e-base` | Pixel 6 → S10e | baseline | 9/20 (11× status 147) |
  | `m147-px-s10e-delay` | Pixel 6 → S10e | `connect_delay_ms=500` | 8/20 |
  | `m147-px-s10e-retry` | Pixel 6 → S10e | `connect_retries=3` | 19/20 (13 first try) |
  | `m147-s10e-px-base` | S10e → Pixel 6 | baseline | 14/20 (6× status 133) |
  | `m147-s10e-px-retry` | S10e → Pixel 6 | `connect_retries=3` | 20/20 (16 first try) |

  Retrying with backoff fixes these failures; a pre-connect delay does not. The matrix uses `connect_retries=3`.

## Full matrix (2026-11-01, 13:11–23:46 CET)

- **Driver:** `python3 host/matrix.py run <phase> --skip redmi11 oppo` for `step1-connect` (n=50), `step2-suite` (n=5), `step3-phy` (1M vs 2M, n=5), `step4-multi` (hubs, peripheral hubs, crossed duals) and `step5-legacy` (P10 lite, XCover 4).
- **Summaries:** `python3 host/summarize.py host/results/m1-*-t* --markdown … --csv …`. Run it once per phase, because a pair's rows merge across the dirs you pass.
- **Raw data:** the JSONL under `host/results/` is gitignored; it is meant to move to `docs/evidence/2026-11-bearer-bakeoff/` later. The app binaries were built from `be5c761`. Each record's `commit` is the host `HEAD` when the run was launched.
- **Branch switch:** at 15:32 an external `git checkout main` removed the tracked files, and 29 queued runs exited with code 2 and no data. The branch was restored and those runs were rerun. No data was lost.

### Observed (not yet a decision)

- **Insecure CoC between Android and iOS works in both directions on every pair.**
  - When GATT connected, the CoC opened 98–100 % of the time. The minimum was 49/50 per pair.
  - Failures:
    - iPhone central: `LinkClosedException … closed` (1 per affected pair).
    - iPhone X → Mi 8: `l2cap open failed NSError CBErrorDomain:0`.
  - CoC throughput was 1.0–1.3× GATT throughput, which is below the 1.5× criterion.
  - With an Android central, the CoC echo RTT was about 13 ms lower (about 31 vs 44 ms on Nothing, S10e and Mi 8). On Pixel 6 there was no difference. With an iPhone central, both were 60 ms.
- **Android 2M vs 1M** (Pixel 6 ↔ S10e and Pixel 6 ↔ Nothing, n=5):
  - 2M gave 1.4–1.9× GATT and CoC throughput on Pixel 6 ↔ S10e and Nothing → Pixel 6.
  - Pixel 6 → Nothing gained only 1.08–1.42×. In its 1M run the link reported `tx=2M`, so the 1M request was not fully honoured there.
  - 2M showed no extra connect failures.
- **Crossed connections and the keep-one rule** (n=20 rounds per pair):

  | Pair | Crossed rounds | Result |
  |---|---|---|
  | iPhone 15 ↔ SE 2020 | 20/20 | The keep-one rule (keep the central with the lower node ID) was applied correctly every time. |
  | Pixel 6 ↔ S10e | 4/20 | Applied correctly every time. The other rounds ended with one link, because the second connect failed with status 133/147. |
  | Pixel 6 ↔ iPhone 15 | 0/20 | The iPhone central discovered the Pixel but timed out while connecting, while the Pixel → iPhone link was up. |

  There were 0 rule violations. The median time to detect a crossed link was 299 ms.
- **Maximum concurrent connections** (all of these are lower bounds limited by how many peers were reachable; the peers included iPhones):

  | Hub and role | Max reached | First failure |
  |---|---|---|
  | Pixel 6 (Android API 37), central | 8 | `timeout` |
  | S10e (Android API 31), central | 7 | `disconnected status=133`, then `timeout` |
  | iPhone 15 (iOS 17.6.1), central | 9 | `timeout`. 8 of 9 links stayed healthy; the XCover 4 link failed with `echo timeout`. |
  | iPhone X (iOS 16.7.16), central | 8 | `scan timeout` |
  | Pixel 6, peripheral | 8 | — |
  | iPhone 15, peripheral | 4 at once | Links were closed by the iPhone peripheral (`LinkClosedException … closed`, and on the SE 2016 `CBErrorDomain:6`). |
  | iPhone X, peripheral | 4 | No iPhone central could discover it (`scan timeout`). The cause is unverified. |

- **Legacy phones:**
  - P10 lite (API 26) as central failed 0/25 against both peers, with `cccd not started` or `disconnected status=133`.
  - P10 lite as peripheral: iPhone 15 central 25/25, Pixel 6 central 12/25 (`status=147`).
  - XCover 4 (API 28): central 2/25 to Pixel 6 and 0/25 to iPhone 15, mostly `scan failed 2`. As peripheral: Pixel 6 central 0/25 (`scan timeout`, `status=147`), iPhone 15 central 2/25.
- **iPhone central suites:** in attempts 2 and later, `handshake_xx_classical` over GATT often failed with `LinkClosedException: gatt-central:<uuid> closed` about 22 s after notify throughput finished. The worst case was the SE 2016 against Android peripherals, at 1/5 to 2/5 OK. Attempt 1 always passed. The cause is unverified.
