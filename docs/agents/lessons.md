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
