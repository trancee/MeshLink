# Lessons

Read this before any radio, device-lab, crypto-choice, gate or proof work (see [`AGENTS.md`](../../AGENTS.md)). Each entry gives the concept, where the evidence lives, commands that worked, root causes found and ideas already rejected. Decisions themselves live in the ADRs; this page only records what was learned getting there.

## Device lab

**Concept:** radio behavior counts only when measured on real phones (Constitution T9); simulators never count. The lab is a set of Android phones on a remote adb server and iPhones on the Mac. The inventory is in `../android-devices/devices.md`, outside the repository.

**Code map:** `prototypes/bearer-bakeoff/` on throwaway branch `prototype/bearer-bakeoff`. `host/bakeoff.py` builds, installs, launches and pulls results. `host/matrix.py` runs whole phases, and `host/summarize.py` summarizes them.

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
- **Device state can be changed from outside.** An external process switched the main worktree's branch during a run. Run long device jobs from a dedicated worktree and check the branch before committing.

**Rejected ideas:**

- A fixed delay before connecting, instead of retries.
- Paced writes, smaller chunks or periodic write-with-response for the XCover 4 write stall. They didn't help; the real cause was the leaked registrations.
- Assuming a failure is the phone's fault before ruling out the prototype. Both legacy phones were blamed first, and both were prototype bugs.

## Interop and crypto benchmark (iPhone SE 2016)

**Concept:** the slowest supported iPhone sets the bar for Swift interop and crypto cost. Evidence: [`docs/evidence/2026-11-se2016-bench/`](../evidence/2026-11-se2016-bench/README.md); code on throwaway branch `prototype/se2016-bench`.

**Signing:** the SE 2016 is not provisioned for `ch.trancee.lab.host` (team `apple-team-1`). Sign it with team `apple-team-2` and bundle `ch.trancee.lab.host.b`.

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

## Docs tooling

- `markdownlint-cli2 --fix` mangles URLs that contain `(` and rewrites table separators. Review its diff, never trust it blindly.
- lychee's `include_fragments` must be a string (`"full"`).
- silabs.com blocks all automated clients, and eprint.iacr.org answers only browser user agents. Both are excluded in `lychee.toml`.
