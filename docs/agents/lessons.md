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

## Routing simulation

**Concept:** the throwaway discrete-event simulator models links, not radio packets. Its numbers are always labeled simulated and are recalibrated from device measurements.

**Code map:** `prototypes/routing-sim/` on throwaway branch `prototype/routing-sim`. The evidence is in `docs/evidence/2026-11-routing-simulation/`.

**Lessons:**

- Pre-registered decision criteria kept the comparison honest. The low-density criterion was amended once, openly, when not even flooding could meet it.
- Check the accepted design's measurement list before writing the ADR. Four pre-registered measurements were nearly skipped and had to be run as an extra stage.
- At N=1000, a full protocol set takes 16–18 min per run on an 8-core, 8 GB Mac. Scope those cells down in advance.

## Docs tooling

- `markdownlint-cli2 --fix` mangles URLs that contain `(` and rewrites table separators. Review its diff, never trust it blindly.
- lychee's `include_fragments` must be a string (`"full"`).
- silabs.com blocks all automated clients, and eprint.iacr.org answers only browser user agents. Both are excluded in `lychee.toml`.
