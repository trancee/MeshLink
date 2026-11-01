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

Other roles: `role=hub-central` (max concurrent connections; run it against several `role=peripheral` devices with `solo`), `role=dual` (both roles at once, for crossed connections and the keep-one rule) and `role=hold`.
`role=hub-central` and `role=dual` compile, but neither has been run on a device yet.

Every record is one JSON line: `{run_id, commit, date, central, peripheral, test, bearer, phy, mtu, attempt, outcome, timings, bytes, error}`.

## Orchestration per device class (as verified)

| Device class | Install | Launch with arguments | Pull results |
|---|---|---|---|
| Android (remote adb, `ADB_SERVER_SOCKET=tcp:adb-server:5037`) | `adb install -r -g` plus `pm grant` ✅ | `am start -S -n ch.trancee.lab.host/.MainActivity --es args k=v,k=v` ✅ | `adb exec-out run-as ch.trancee.lab.host cat files/results/<run>.jsonl` ✅ (logcat tag `BAKEOFF` too) |
| iOS 17+ (iPhone 15, SE 2020, 12 mini) | `xcrun devicectl device install app` ✅ | `xcrun devicectl device process launch --terminate-existing <bundle> k=v …` ⚠️ reaches the launch, but iOS refuses until the developer is trusted | `devicectl device copy from --domain-type appDataContainer` (unverified: no file exists yet) |
| iOS 15/16 (SE 2016, iPhone X) | `ideviceinstaller -i` ✅ | `idevicedebug -u <udid> run <bundle> k=v …` ⚠️ DDI/debugserver work with Xcode 16.4; launch is refused until trusted | `afcclient --container <bundle>`: listing ✅, `get` unverified |

## Signing (free personal teams, automatic signing, `-allowProvisioningUpdates`)

| Team | Bundle ID | iPhones in its profile | Installed with this team |
|---|---|---|---|
| apple-team-1 | `ch.trancee.lab.host` | iPhone 15, SE 2020, 12 mini | iPhone 15, SE 2020, 12 mini |
| apple-team-2 | `ch.trancee.lab.host.b` | SE 2016, iPhone X, iPhone 15 | SE 2016, iPhone X |

Free teams cannot register the same App ID, so team B uses the `.b` suffix.

## Human-in-the-loop steps

1. On each iPhone: Settings → General → VPN & Device Management → trust "Apple Development: owner". Team B's certificate may show a different name.
2. At the first launch on each iPhone, tap **Allow** on the Bluetooth permission prompt.

## Deviations from the agreed design

- JDK 21 daemon, because JDK 25 is absent.
- Team B uses a suffixed bundle ID (`ch.trancee.lab.host.b`).
- The Android central requests `CONNECTION_PRIORITY_HIGH` after connecting.
- Android scans are paced at least 6.5 s apart, to stay under the 5-scans-per-30 s limit.
- `connectGatt` always initiates on 1M|2M. A 2M-only initiating mask fails with status 147, because the legacy advert is on 1M. `phy=` then controls `setPreferredPhy`.
- Each suite test has a 30 s deadline. A stalled test is recorded as a `fail` under its own name, and the suite then aborts.
