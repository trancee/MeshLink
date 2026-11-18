# Lessons

Read this before any radio, device-lab, crypto-choice, gate or proof work (see [`AGENTS.md`](../../AGENTS.md)). Each entry gives the concept, where the evidence lives, commands that worked, root causes found and ideas already rejected. Decisions themselves live in the ADRs; this page only records what was learned getting there.

## Device lab

**Concept:** radio behavior counts only when measured on real phones (Constitution T9); simulators never count. The lab is a set of Android phones on a remote adb server and iPhones on the Mac. The inventory is in `../android-devices/devices.md`, outside the repository.

**Code map:** `tools/lab/lab.py` builds the lab apps in `labapp/`, installs and launches them, pulls results into `build/lab/<run>/` and writes evidence records; its [README](../../tools/lab/README.md) has the local config format and the commands. The scenarios are Kotlin in `labapp/shared/`. The bake-off that proved these commands is `prototypes/bearer-bakeoff/` on throwaway branch `prototype/bearer-bakeoff`.

**Verified commands** (2026-11-01):

| Task | Command |
|---|---|
| Android, remote adb | `ADB_SERVER_SOCKET=tcp:adb-server:5037 adb devices -l` |
| Android, launch with arguments | `adb shell am start -S -n <pkg>/.MainActivity --es args k=v,k=v` |
| Android, pull app files | `adb exec-out run-as <pkg> cat files/results/<run>.jsonl` |
| iOS 17+, launch | `xcrun devicectl device process launch --terminate-existing --device <id> <bundle> k=v` |
| iOS 17+, pull app files | `xcrun devicectl device copy from --domain-type appDataContainer --domain-identifier <bundle> …` |
| iOS 15/16, launch | `idevicedebug -u <udid> --detach run <bundle> k=v` (attached mode fails on the SE 2016) |
| iOS 15/16, pull app files | `afcclient -u <udid> --container <bundle>`, then `get -f <path> <dest>`. Without `-f` it refuses to overwrite and still exits 0. |
| Run a bake-off phase | `cd prototypes/bearer-bakeoff && python3 host/matrix.py run <phase> --skip redmi11 oppo` |

**Root causes found:**

- **Status 133/147 on first connect:** common on every Android central. Retries fix it; a delay before connecting does not.
- **A 2M-only initiating mask fails with 147,** because the legacy advertisement is on 1M. Always initiate with 1M|2M.
- **`readPhy()` aborted the Bluetooth stack on the P10 lite (Android 8),** with `gatt_api.cc Assert failed: len == 5`. Its symptoms were `cccd not started` and 133, which looked like a broken phone. Never send PHY commands to phones without 2M support.
- **Leaked scanner registrations exhausted the XCover 4 (Android 9).** The symptoms were `scan failed 2` and, likely, `openGattServer` returning null. Pair every `startScan` with `stopScan` in a `finally`. Clearing an already leaked state needs a Bluetooth toggle or a reboot. The stack can still complete a registration after it reported failure.
- **iPhone central closes the link about 30 s after a GAP Device Name read is dropped.** iOS reads the name about 12–13 s into a reconnect. An Android peripheral that is flooding notifications drops the reply, and iOS disconnects with reason 722 and no app-visible error. `onNotificationSent` means queued, not sent.
- **iPhones on iOS 15 and 16 with 4 incoming links became invisible to all scanners** while still reporting themselves as advertising; iOS 17, 18 and 27 held 6 and stayed visible (step 6, one phone per version). The cause is unverified; v1 caps every iOS version at 3 (ADR 0009).
- **iPhone central round trips are quantized to 60/120 ms,** because iOS sets an interval of about 30 ms that apps can't change.
- **An unbonded Android central reconnecting to an iOS peripheral gets no new `didSubscribe`.** Detect the reconnect from the control read.
- **Kotlin/Native `NSLog("%@", kotlinString)` varargs crashed** with SIGTRAP. Format in Kotlin and escape `%`.
- **Private identifiers leaked into history.** Device serials, iOS UDIDs, Apple team IDs, LAN addresses, home paths and an email address were committed in lab scripts and evidence, and the history had to be rewritten before the first push (ticket 30). Record lab aliases only (`pixel6`, `ip15`, `adb-server`, `~/`); `tools/check_private.py` enforces this locally and in CI.
- **The first scrub missed gzipped evidence, and so did the check.** Both read only plain text; the `.gz` evidence still held UDIDs, team IDs, serials, a battery serial and the pre-scrub commit hashes in its `commit` fields. A second rewrite fixed contents and provenance (ticket 35). The check now decompresses `.gz` files, and `privacy.yml` scans every blob of every branch daily, because a workflow runs only on branches that contain it.
- **Scanner logs carry third-party Bluetooth addresses.** Static addresses of nearby devices can locate the lab through wardriving databases and may count as neighbors' personal data. Committed evidence holds lowercase keyed pseudonyms (HMAC-SHA256 with a key that's never committed, keeping the 2 address-type bits), in address fields and inside raw advertisement bytes. Capture scripts must pseudonymize when exporting; the check fails on uppercase addresses.
- **Lab scripts must resolve aliases locally.** After the scrub, committed scripts hold aliases, so `adb -s` and `devicectl --device` need a local, untracked alias-to-ID mapping. The tracked pre-push hook (`.githooks/pre-push`, enabled with `git config core.hooksPath .githooks`) scans every pushed commit's history, adding the serials from `../android-devices/devices.md` when that file exists. A hook can be skipped, so it complements the CI scan rather than replacing it.
- **The first lab run on iOS crashed when publishing the GATT service.** `CBMutableService` asserts `UUID.data.length == 2 || UUID.data.length == 16`, and GATT allows only 16- and 128-bit service UUIDs. MeshLink had passed the 32-bit MESH UUID. Advertising and scanning accept the 32-bit form, and `CBUUID` treats both forms as equal, so publishing the 128-bit form was enough on iOS. The service has since moved off the Base UUID altogether (Mi Note 3 entry above). Kotlin/Native turns the Objective-C exception into an abort, and the fake CoreBluetooth now asserts the same rule. Find the reason with `idevicesyslog -u <udid> -p <app>` (`Terminating app due to uncaught exception`); the crash report holds only the backtrace.
- **The Xiaomi Mi Note 3 (Android 9) GATT server shortens Base-UUID services to 32 bits on the wire** (ticket 17). ATT allows only 16- and 128-bit UUIDs in a Read By Group Type response, so every central dropped the MESH service and closed with `CAPABILITY_MISSING`. As a central the phone worked. AOSP 9–11 (`gatt_sr.cc` `gatt_build_primary_service_rsp`, `gatt_utils.cc` `gatt_build_uuid_to_stream`) widens 32-bit UUIDs correctly, so the defect is the vendor stack's; the phone's own `dumpsys bluetooth_manager` handle map looked complete. Find it in the **central's** logcat: `gatt_parse_uuid_from_cmd: DO NOT ALLOW 32 BITS UUID IN ATT PDU`, then `discover all service response parsing failure`. Fix: serve the GATT service under `4D455348-4D4C-4E4B-8000-000000000000`, which is off the Bluetooth Base UUID and so can't be shortened; advertising keeps the 32-bit MESH UUID (ADR 0010 amendment). **Rule for future work:** never serve a custom GATT service or characteristic on the Base UUID (`xxxxxxxx-0000-1000-8000-00805F9B34FB`); only SIG-assigned attributes such as the CCCD belong there. Suspect any OEM peripheral that a central can connect to but finds no services on, and read the central's log before the peripheral's.
- **Android 8.0 and 8.1 (API 26–27) truncate advertised 32-bit service UUIDs**, found on the Huawei P10 lite (ticket 17). It is not central-only, although an earlier version of this entry said so. It reports `isMultipleAdvertisementSupported` false, which in AOSP 8.0 means only fewer than 5 advertising instances (`MIN_ADVT_INSTANCES_FOR_MA`); its single legacy advertiser works, and the fleetcheck probe (`../android-devices/ble-check`) passed on it in both roles with a random 128-bit UUID. With `4D455348-0000-1000-8000-00805F9B34FB` a Mac CoreBluetooth dump received the service UUID `00005348`, and every start still reported success. The cause is AOSP, not Huawei: [AOSP 8.x `BluetoothUuid`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-8.1.0_r1/core/java/android/bluetooth/BluetoothUuid.java#227) masks with `0x0000FFFF00000000L` in `android-8.0.0_r1` and `android-8.1.0_r1`, so `uuidToBytes` zeroes the upper 16 bits of a 32-bit UUID, and `AdvertiseHelper` uses it for advertisements; `android-9.0.0_r1` masks with `0xFFFFFFFF00000000L`. 16- and 128-bit UUIDs and scanning are unaffected. The off-Base `4D455348-4D4C-4E4B-8000-000000000000` arrived intact. So as a MeshLink peripheral nobody finds it (run `rt-aa-7`), while as a central it links (run `rt-aa-8`). Layout E keeps the 32-bit UUID (owner's decision), so API 26–27 phones join as centrals only; ticket 66 stops them advertising. `../android-devices/devices.md` has the full probe record. **Rules for future work:** never gate on `isMultipleAdvertisementSupported`; only a null `getBluetoothLeAdvertiser()` with Bluetooth on means no advertising. When a phone advertises but nobody sees it, diff a scanner's view with the phone's advertiser on and off, try a random 128-bit UUID as a control, and don't trust "central-only" claims without such a test.
- **Android 11 and older need location, not Nearby Devices, permissions** (the P10 lite, Mi Note 3 and XCover 4 included). `lab.py grant` picks the set by API level (`android_permissions(api)`: `ACCESS_FINE_LOCATION` below API 31).
- **Android 9 can't toggle Bluetooth from the shell:** `cmd bluetooth_manager` doesn't exist and `service call` needs `BLUETOOTH_ADMIN`. Toggle by hand or reboot.
- **A LabHost left running keeps advertising MESH** and joins later runs (the `ip15` app from ticket 16 did). Stop the lab apps after each run (`lab.py stop --devices …`).
- **A Mac CoreBluetooth GATT probe is not evidence for Android peripherals.** It returned an Apple-like attribute database instead of the phone's. Use the Android central's logcat or an HCI snoop.
- **The iOS 17.6.1 grant runner lost `testmanagerd`** because the phone's automatic screen capture crashed. The scheme turns it off (`captureScreenshotsAutomatically: false`). A Bluetooth denial survives reinstalling; uninstall the app to reset it.
- **Device state can be changed from outside.** An external process switched the main worktree's branch during a run. Run long device jobs from a dedicated worktree and check the branch before committing.

**Rejected ideas:**

- A fixed delay before connecting, instead of retries.
- Paced writes, smaller chunks or periodic write-with-response for the XCover 4 write stall. They didn't help; the real cause was the leaked registrations.
- Assuming a failure is the phone's fault before ruling out the prototype. Both legacy phones were blamed first, and both were prototype bugs.

## Interop and crypto benchmark (iPhone SE 2016)

**Concept:** the slowest supported iPhone sets the bar for Swift interop and crypto cost. Evidence: [`docs/evidence/2026-11-se2016-bench/`](../evidence/2026-11-se2016-bench/README.md); code on throwaway branch `prototype/se2016-bench`.

**Signing:** the SE 2016 is not provisioned for `ch.trancee.lab.host` (team `apple-team-1`). Sign it with team `apple-team-2` and bundle `ch.trancee.lab.host.b`. The same applies to the iPhone X, because a free-team profile lists at most 3 devices. Read a team ID from the signing certificate's OU field (`security find-certificate -a -c "Apple Development: <account>" -p | openssl x509 -noout -subject`); never write it into the repository. After a profile is regenerated, every phone must trust the developer again (Settings › General › VPN & Device Management). Until then a launch fails with "profile has not been explicitly trusted" (`idevicedebug`) or CoreDevice error 10002 / FBSOpenApplicationServiceErrorDomain error 1 (`devicectl`).

**Root causes found:**

- **A sender that retries `QueueFull` in a loop looks like a memory leak.** 7.3 million retries allocated faster than the Kotlin/Native garbage collector freed, so memory grew about 1 GB; a forced collection reclaimed most of it. Bound the messages in flight instead.
- **Converting `Data` element by element is about 25 times slower** than one bulk copy (about 2.6 ms at 16 KB).
- **A static libsodium link still exports its symbols** from the framework (228 found with `nm`); hiding them needs an export list.
- **SKIE exports `messages` as an untyped async sequence** that still works with `for await`; leaving the loop cancels the Kotlin collector. Kotlin default arguments are not visible from Swift.

## Discovery measurements

**Code map:** discovery modes in the bake-off app on throwaway branch `prototype/discovery` (`prototypes/bearer-bakeoff/host/{d2,t1,t3,t4,t9,t11}.py`, `d4.sh`, `analyze_discovery.py`); pre-registration in `prototypes/discovery/README.md`. Evidence: `docs/evidence/2026-11-discovery/`. Decision: ADR 0010.

**Root causes found:**

- **An unfiltered Android scan goes opportunistic after 5 min** (`regularScanTimeout`), and the scanner then sees almost nothing. Restart unfiltered scans every 4 min or use filters.
- **iPhones keep their last app scan response on air after the app stops** (≥ 26 min, across address changes). Stale MESH bits contaminated the first background run. Scrub each iPhone (15 s of background advertising without MESH) before a background measurement, and attribute overflow sightings only to bitmaps new since the advertiser started.
- **`devicectl` refuses launches once an iPhone has locked itself** ("device has not been unlocked recently"). Disable auto-lock on lab iPhones before long runs; only a person can unlock.
- **The Android lab app truncated its JSONL on relaunch,** which lost whole cells. Logs must append.
- **`AdvertisingSet.getOwnAddress()` is not callable** by apps on Android 17 (`NoSuchMethodException`); measure addresses from a scanner instead.
- **Pixel 6 system updates install during runs.** Disable automatic updates before multi-hour runs.
- **Android 15 and later concatenate a company's manufacturer data** in `ScanRecord` (AOSP `android-15.0.0_r1`); Android 14 and earlier keep the last entry. Every `ScanFilter` is re-checked in software against that array, so a fixed-offset data mask misses a background iPhone whose Nearby Info precedes the overflow area. Filter on the company ID with empty (not null) data and parse the raw AD structures (ADR 0010, 2026-11-18 amendment). The field run's filtering phone ran Android 9 and could not show this.

**Rejected ideas:**

- Rotating on Android by data update or enable/disable: the address stays (0/72).
- Forcing a new iOS address by stop/start: never works (0/93).

## Energy measurements

**Concept:** energy counts only as power measured on a real phone, above a bracketing baseline. Evidence: [`docs/evidence/2026-11-energy/`](../evidence/2026-11-energy/README.md); decisions in the ADR 0008 and ADR 0010 amendments of 2026-11-08.

**Code map:** `prototypes/energy/` on throwaway branch `prototype/energy`. `host/energy.py` runs windows, cross-checks, overnight runs and iOS gauge sampling; `host/analyze.py` computes Δ and summaries. Pre-registration and amendments A1–A9 are in its `README.md`. The app side is an `energy=` mode in the bake-off app's foreground service.

**Verified commands** (2026-11-03 to 2026-11-07):

| Task | Command |
|---|---|
| Which Android phones have power rails | `adb shell dumpsys powerstats` and look for rail channels; only the Pixel 6 has them in the lab |
| Android rail trace | Perfetto `android.power` data source with `collect_power_rails: true` |
| iOS gauge over Wi-Fi | `idevicediagnostics -n -u <udid> ioregentry AppleSmartBattery`; on iOS 15 the same keys are under `AppleARMPMUCharger`; power = \|InstantAmperage\| × Voltage, updated every 20 s, invalid while plugged in |
| iOS 15/16 launch over Wi-Fi | `idevicedebug -n -u <udid> --detach run <bundle> k=v` |
| Xcode 27 next to 16.4 (Instruments only) | `xcodes install 27.2 --no-superuser --empty-trash --experimental-unxip` (keeps 16.4 selected); use it per command with `DEVELOPER_DIR=/Applications/Xcode-27.2.0.app/Contents/Developer xcrun xctrace …` |
| Power Profiler capture, iOS 26+ (2026-11-07, 12 mini) | after `sudo DEVELOPER_DIR=… xcodebuild -runFirstLaunch` and opening Xcode 27 once so the phone comes online: `xcrun xctrace record --template "Power Profiler" --device <udid> --time-limit 60s --output x.trace --launch -- <bundle> k=v`; `--attach` found no process by name or pid |
| Power Profiler phases, unplugged (2026-11-09, 12 mini) | record in the Instruments GUI first (Blank + Power Profiler, All Processes, Wi-Fi only), then `PP_DEVICE=<udid> python3 host/pp/drive.py SEED ACD 900` on `prototype/energy`; after stopping, **File > Save As** a new name, then `python3 host/pp/analyze.py <trace> results/pp/phases-SEED.jsonl` |
| iOS overnight (coulomb counting) | read `AppleRawCurrentCapacity` over USB right before unplugging and right after plugging in (`IsCharging` false) |

**Root causes found:**

- **Only Pixel 6 and later Pixels expose per-rail power (ODPM),** per the Android Studio Power Profiler docs; 32 other lab Android phones had no rail channels. Other phones give only `batterystats` controller time, which ranks but does not measure energy.
- **AOSP rejects more than 5 scan starts per 30 s.** A 2 s on / 2 s off schedule produced empty bursts; keep start/stop periods at 6 s or more.
- **Continuous filtered scans degraded after 2–5 min** with the screen off on the Pixel 6 (power and results dropped). The cause is unverified and conflicts with discovery T9 (2 h without a downgrade). Measure start/stop schedules, not continuous scans.
- **The app wake lock hides the cost of waking up.** Measured windows held one; only the overnight run without it shows Doze stretching the timer to 30 s gaps.
- **A locked iPhone stops answering lockdown over Wi-Fi** about 4 min after locking, so the gauge stream ends. Screen-off iOS runs need coulomb counting over USB.
- **iOS suspends the app about 30 s after locking** (`background_task_expired`) despite Bluetooth background modes, and Bluetooth state restoration can relaunch it later. Stop it with `devicectl` and remove its Bluetooth permission before a control night.
- **usbmuxd drops Wi-Fi iPhones** that are still on the network (ping and port 62078 answer). `sudo kill <usbmuxd pid>` restarts it through launchd; `launchctl kickstart` is blocked by SIP.
- **A newly paired iPhone does not appear over Wi-Fi** until lockdown `com.apple.mobile.wireless_lockdown` `EnableWifiConnections` is true: the Finder checkbox only takes effect after Apply, or set it with libimobiledevice `lockdownd_set_value` (`idevicesetvalue` is not installed).
- **Power Profiler has no Bluetooth lane.** Per-process lanes are CPU, display, GPU and networking (Wi-Fi and cellular bytes only); system power is battery %/hour and reads 0 while charging. Scanning and advertising left every app lane unchanged on the 12 mini (n = 1 per scenario). Bluetooth cost needs app-on versus app-off system power, unplugged.
- **iOS 27.0.1 Performance Trace saves nothing on the 12 mini.** Stopping logs `PerformanceTraceError Code=2` ("Failed to collect any trace files") after a sandbox denial of `hw.uuid`; check `idevicesyslog -n` for `PerformanceTraceModule` before trusting a detached run. Over Wi-Fi, `xctrace --instrument "Power Profiler" --all-processes` gives unplugged %/hr but disconnected after about 2 s (2 of 2); the full template aborts with All Processes. **What works:** the Instruments GUI over Wi-Fi only (Blank template, add the Power Profiler track or Record stays disabled, All Processes) held 24 min unplugged; drive phases with `devicectl device process launch` and compare per-phase medians, since system bursts of 10–100 s swamp means.
- **Unplugging starts an iOS system state.** The 12 mini drew about 20 %/hour for the first ~15 min after the recording started unplugged, against about 5.5 at rest. Use a 15 min warm-up and fix a block-invalid rule (any phase median above 2× the lowest idle median) before the run.
- **Sending the app to the Home screen costs display power.** On the OLED 12 mini the Home screen drew about 53 mW more than the app's own idle screen, enough to look like a Bluetooth cost. Compare a background condition with an idle app also sent to the Home screen, never with an idle app in front.
- **Background iOS scans are not measurable with Power Profiler.** Background advertising and filtered scanning ran (the app saw the peer, then iOS suspended it about 40 s later), but C − D was about 0 (2 of 4 blocks positive).
- **All Processes leaves the per-process table empty.** `ProcessSubsystemPowerImpact` has 0 rows in an All Processes recording, so display power cannot be split out; only `SystemPowerLevel` is usable.
- **Instruments keeps the open document.** After a long recording, a plain Save writes into the document that was open, replacing its earlier run (session 2's trace was lost this way); use Save As with a new name. "Modeling data" after stopping takes minutes; `xctrace export` fails until the save finishes.
- **Worn batteries switch off under load.** The SE 2016 died mid-repeat; check battery health before multi-hour gauge runs.

**Rejected ideas:**

- iOS screen-off foreground windows: locking suspends the app and ends the Wi-Fi gauge.
- The simulation's 0.6 × scan + 0.4 × advertising power index: advertising measured about 0, so the index overweighted it.

## Routing simulation

**Concept:** the throwaway discrete-event simulator models links, not radio packets. Its numbers are always labeled simulated and are recalibrated from device measurements.

**Code map:** `prototypes/routing-sim/` on throwaway branch `prototype/routing-sim`. The evidence is in `docs/evidence/2026-11-routing-simulation/`.

**Lessons:**

- Pre-registered decision criteria kept the comparison honest. The low-density criterion was amended once, openly, when not even flooding could meet it.
- Check the accepted design's measurement list before writing the ADR. Four pre-registered measurements were nearly skipped and had to be run as an extra stage.
- At N=1000, a full protocol set takes 16–18 min per run on an 8-core, 8 GB Mac. Scope those cells down in advance.
- Sybil (stage S, branch `prototype/sybil`): model identities as free (an evicted fake re-dials at once as someone new), or every eviction defense looks better than it is. Under that rule, scoring, probation and confirmed neighbor lists all failed at 30% fakes. The attacker's real cost is radios and link slots; test a realistic radio budget, not only shares.
- Usefulness scoring by relayed floods cannot see a black hole that relays broadcasts and drops unicast.
- New strategy flags default to off; verify with an exact replay of earlier stages (stage S replayed stage A, D `ios3` and E `rot` identically).

## Robolectric host tests

- The `meshlink.robolectric` plugin hands Robolectric the android-all jars through `robolectric-deps.properties`, so host tests never download. Proof of no network: run under `sandbox-exec` with a profile that denies outbound traffic except localhost, after `./gradlew --stop` and with `~/.m2/repository/org/robolectric/android-all-instrumented` moved aside:

  ```sh
  printf '(version 1)\n(allow default)\n(deny network-outbound)\n(allow network-outbound (remote ip "localhost:*"))\n(allow network-outbound (remote unix-socket))\n' > /tmp/nonet.sb
  sandbox-exec -f /tmp/nonet.sb ./gradlew --offline :meshlink:testAndroidHostTest --tests '*Robolectric*'
  ```

- Each SDK's android-all pin needs its own Gradle configuration. All pins share the module `org.robolectric:android-all-instrumented`, so one configuration resolves only the newest version and the older SDK fails with `no artifacts found for DependencyJar{...}`.
- Robolectric 4.18 on the JDK 21 test toolchain needs neither `--add-exports` nor `--add-opens` (ADR 0005, 2026-11-14 amendment).
- A JUnit `Timeout` rule runs each test on another thread, and the main looper can then not be driven (`Main looper can only be controlled from its thread in PAUSED mode`). Time adapter retries and budgets with a coroutine `delay` in the radio's scope and an injected `TimeSource`, and advance the `TestScope` scheduler instead (`BleScanner`, `GattServerHost`).

## Apple packaging

- Kotlin/Native links no framework, and KGP writes no iOS klib, for a module without Kotlin source: the link task is `NO-SOURCE` and publishing fails in `generateMetadataFileFor<Target>Publication` with `FileNotFoundException`. Never add a placeholder file; give the module real source or wait for the ticket that does (ticket 65).
- SKIE needs `skie { build { produceDistributableFramework() } }` for an XCFramework. Without it, `xcodebuild -create-xcframework` fails with "No 'swiftinterface' files found within …MeshLinkKit.swiftmodule".
- A Swift export list must match the module name anywhere in the mangled name, not only as a prefix. Extensions on Objective-C classes mangle as `$sSo<class>C…E` with the module name compressed (`$sSo11MLKMeshLinkC04MeshB3KitE5startyyYaKF`), so `_$s11MeshLinkKit*` dropped SKIE's `async` wrappers and the Swift consumer failed to link `start()`. Audit with `xcrun nm -gU <slice>` and skip the `I` (indirect) rows.
- SKIE 0.10.16 with Kotlin 2.4.21 exported a `StateFlow` property as raw `id<Kotlinx_coroutines_coreStateFlow>`, not as `SkieSwiftStateFlow`, although Flow interop is on by default and its runtime is present; a sealed class instead of a sealed interface as the element changes nothing. Root cause open (ticket 55).
- The old "assembling the XCFramework a second time fails" problem was an artifact of a module with no Swift API; with the lifecycle API, three consecutive `tools/swiftpm-consumer/test.sh` runs passed without cleaning (2026-11-14).
- On Linux CI, where the iOS targets are disabled, Dokka can't resolve library types (`StateFlow`, `CoroutineDispatcher`) in `iosMain` declarations, even private ones, and its zero-warning gate fails; macOS passes. Keep `iosMain` declarations to MeshLink's own common types, and put the logic in `commonMain`, where Kover measures it (T3 thin adapters). A cinterop adapter that the simulator tests must reach goes in `:meshlink`'s `iosAdaptersMain`, which Dokka suppresses; `suppressedFiles` doesn't help, because Dokka still analyzes the file.
- SwiftPM names the scheme of a package with tests `<Package>-Package` (check with `xcodebuild -list`). `swift test` can't run the consumer: it runs on macOS, and the XCFramework has only iOS slices.
- Xcode 27.2 `dsymutil` prints hundreds of "could not open … stdlib-per-file-cache" warnings while linking Kotlin/Native debug test binaries; Xcode 16.4 prints none. Harmless ([KT-90461](https://youtrack.jetbrains.com/issue/KT-90461)).
- Xcode 27.2's `ar` and `ranlib` silently drop ELF members. libsodium's Android configure picks them up unless `AR` and `RANLIB` name the NDK's `llvm-*` tools (`build-libsodium.sh` sets them). The symptom is `ld.lld: undefined symbol: sodium_init` in `buildSodiumJniAndroid*`. The build stamp ignores the host archiver and `--rerun-tasks` doesn't bypass the stamp, so an archive built under Xcode 16.4 hides the fault. To force a rebuild, delete `build/libsodium/out/<target>/.meshlink-libsodium-stamp` (2026-11-15).
- With the configuration cache on (Gradle 9.7.1), a task input set to `providers.gradleProperty(…).orElse(provider { throw … })` failed with the fallback's error although the property was set. Read a required Gradle property eagerly with `orNull ?: throw`.

## Kotlin quality gates

- Plain `./gradlew detekt` analyzes no Kotlin Multiplatform source set and passes vacuously. Only `check` runs the per-source-set tasks (`detektCommonTestSourceSet`, `detektAndroidMainSourceSet`, `detektIosMainSourceSet`, …); prove detekt with `check` (2026-11-15).
- ktlint (Spotless, no `.editorconfig`) allows 140 characters and rejoins wrapped lines up to that length, which detekt's `MaxLineLength` 120 then rejects; it also turns block bodies back into expression bodies. Shorten identifiers or extract locals instead of wrapping by hand.
- detekt's `TooManyFunctions` counts top-level functions per file, and members per object, class or interface. Split by cohesive family (as `SodiumCryptoProvider` delegates to `SodiumHashing`, `SodiumClassical`, `SodiumKems`), never suppress (Q2).
- Default methods in an interface compile to `$DefaultImpls` bridges that no test executes, so Kover drops below 100 % even when every implementation is covered. Keep interface members abstract (ADR 0004, 2026-11-15 amendment).

## Vector runner

- Code: `meshlink-crypto-sodium/src/commonTest/kotlin/ch/trancee/meshlink/vectors/` (runner, handlers, sets, skips), `build-logic/.../SpecVectorsPlugin.kt` (embedding and checksums), `third_party/vectors/` (vendored sets).
- Verified: `./gradlew :meshlink-crypto-sodium:testAndroidHostTest --tests '*VectorRunnerTest'` prints each file's counts.
- A KDoc that contains `/*`, such as a `*.json` glob after a slash, opens a nested Kotlin comment and breaks compilation of the whole file.
- To corrupt a layout vector in a T10 test, flip its last byte: the first byte is the frame type, which selects the handler, so flipping it moves the test into a skip.
- libsodium's ML-KEM-768 decapsulation doesn't run the FIPS 203 decapsulation-key hash check; Wycheproof's `InvalidDecapsulationKey` tests pass through it.

## Rust reference

- Code: `tools/reference/` (test-only crate, Rust 1.100.0 from its `rust-toolchain.toml`); writes the tier 2 files in `docs/spec/vectors/`.
- Verified: `cargo test --manifest-path tools/reference/Cargo.toml` (13 tests, including the committed-file staleness check); `cargo run --manifest-path tools/reference/Cargo.toml` regenerates.
- snow names the KEM by its Noise name, `MLKEM768`; `ML-KEM-768` fails to parse. A second Noise modifier needs a `+`: snow rejects `XXhfspsk3` with `UnsupportedModifier`; the name is `XXhfs+psk3` (Noise rev 34 section 8.1). ADR prose keeps `XXhfspsk3` as shorthand (D-45).
- snow's built-in ML-KEM draws from system randomness, and its resolver asks for the KEM once per `HandshakeState`. For fixed transcripts, resolve your own `Kem` that generates from a seed and encapsulates from a fixed message; `fixed_ephemeral_key_for_testing_only` covers only the X25519 ephemeral.
- With a psk, Noise's `e` token also calls `MixKey` (section 9.2), so message 1 of `XXhfs+psk3` carries two tags more than `XXhfs` (1248 B against 1216 B).
- rust-hpke's `XWing` needs both its `mlkem` and `x25519` features. In ml-kem 0.3.4, `hazmat` only un-hides docs; the seeded functions are public without it.
- Default clippy rejects a 4-tuple of test inputs (`type_complexity`) and functions with more than 7 arguments; bundle them into a struct instead of an `#[allow]`.

## Docs tooling

- `markdownlint-cli2 --fix` mangles URLs that contain `(` and rewrites table separators. Review its diff, never trust it blindly.
- lychee's `include_fragments` must be a string (`"full"`).
- silabs.com blocks all automated clients, and eprint.iacr.org answers only browser user agents. Both are excluded in `lychee.toml`.
