# MeshLink Proof Android Fleet Report

Date: 2026-06-27

## Scope
Executed the Android proof-app fleet pass with the stricter sequence:

1. install APK
2. grant runtime permissions on-device
3. launch app
4. verify proof screen
5. send hello where the app exposed an enabled path

Verification used screenshots and UI dumps as the source of truth. `./tools/android app current` was not used for pass/fail classification because it was flaky during these runs.

Future reruns should also verify that both sender and passive can initiate a message when the UI exposes a send control. The runner force-stops both proof-app packages during cleanup after either success or failure.

Rerun note: I reran the proof app on `1f1dad34` (sender) and `R5CT83ACSJX` (passive) using the default library-owned MeshLink launch path and no transport override. The 30-second passive-ready window failed before discovery because Android passive transport did not start in time; a 60-second diagnostic retry showed the same missing-route boundary, so the updated bidirectional-send contract was not reached on this pair.

## Transport mode
Transport mode in the underlying direct-proof summaries is derived from nested `final.timings.transportMode` when the top-level field is absent. The evidence set includes both `GATT` fallback on the passive/proof-app side and `L2CAP` on the carried sender transport. Treat this as report-level transport evidence, not a promise that every row carried the same bearer.

## Fleet coverage
- Connected devices observed: 14
  - 11 USB devices
  - 3 network ADB devices
- Network devices were installed before launch, as required.


## Device matrix

Legend: `ok` = confirmed in the latest evidence bundle, `yes`/`no` = proof-screen state, `sent`/`proceeding`/`unknown` = latest hello status, `n/r` = not reverified in the latest evidence bundle. The `Transport mode` column is a per-row compatibility label derived from the device SDK class and should not be confused with the report-level transport summary above.

| Device Name | Serial | Type | install | grants | launch | proof | Transport mode | latest hello | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [Nothing Phone (2)](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | 1f1dad34 | USB | ok | ok | ok | yes | L2CAP | sent | confirmed send |
| [Nothing Phone (1)](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | adb-P2126T004912-Na69Lt._adb-tls-connect._tcp | network | ok | ok | ok | yes | L2CAP | unknown | send control disabled; hello remained unknown |
| [realme C55](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | 7XHEIBPBLRJJSKFU | USB | n/r | n/r | n/r | n/r | L2CAP | n/r | not reverified in this pass |
| [motorola edge 30 fusion](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | ZY22GCD9ST | USB | ok | ok | ok | yes | L2CAP | sent | target `c67798` was unreachable; send path reported `NotSent(reason=UNREACHABLE)` |
| [Nokia X20](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp | network | ok | ok | ok | yes | L2CAP | sent | confirmed send |
| [OPPO Reno8 5G](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | EQUGS85LJNEIO7Z5 | USB | ok | ok | ok | yes | L2CAP | unknown | hello remained unknown with the proof screen visible |
| [Gigaset GX6](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | GX6CTR500184 | USB | n/r | n/r | n/r | n/r | L2CAP | n/r | not reverified in this pass |
| [OPPO A57s](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | MZLJMJAIO7SKS8BI | USB | ok | ok | ok | no | L2CAP | unknown | uninitialized; Bluetooth off; hello remained unknown |
| [OnePlus Nord 2 5G](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp | network | ok | ok | ok | yes | L2CAP | proceeding | hello stayed proceeding; send completion never finalized |
| [Huawei Nova 9](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | 2ASVB21B09005117 | USB | n/r | n/r | n/r | n/r | GATT | n/r | not reverified in this pass |
| [Xiaomi Pocophone F1](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | e9097611 | USB | ok | ok | ok | yes | GATT | unknown | capture timed out while hello remained unknown |
| [Samsung Galaxy Z Flip4](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | R5CT83ACSJX | USB | ok | ok | ok | yes | L2CAP | sent / proceeding | route-ready was false at tap time, but the send completed |
| [Samsung Galaxy XCover 4](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | 42004386e43c8589 | USB | ok | ok | ok | yes | GATT | unknown | hello remained unknown on the GATT path |
| [Xiaomi Mi Note 3](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) | 42c2cf | USB | ok | ok | ok | yes | GATT | unknown | hello remained unknown on the GATT path |

## Confirmed hello completions
- [Nothing Phone (2)](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) — `Hello sent to ... -> Sent`
- [Samsung Galaxy Z Flip4](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) — `Hello sent to ... -> Sent` (`routeReady=false` at tap time, but the send completed)
- [motorola edge 30 fusion](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) — `Hello sent to b06435 -> Sent`
- [Nokia X20](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) — `Hello sent to ... -> Sent`

## Recovery checklist
- [ ] Route-ready retry: retry [Samsung Galaxy Z Flip4](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`R5CT83ACSJX`) and [OnePlus Nord 2 5G](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp`) only after route-discovered / route-ready evidence is present, and after a fresh proof-screen check shows the Send Hello control enabled.
- [ ] Unknown-state retry: rerun [Nothing Phone (1)](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`adb-P2126T004912-Na69Lt._adb-tls-connect._tcp`), [Xiaomi Pocophone F1](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`e9097611`), [Samsung Galaxy XCover 4](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`42004386e43c8589`), [Xiaomi Mi Note 3](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`42c2cf`), [OPPO Reno8 5G](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`EQUGS85LJNEIO7Z5`), and [OPPO A57s](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`MZLJMJAIO7SKS8BI`) with Bluetooth/preflight/UI-idle checks before attempting hello.
- [ ] Inventory-gap reverify: keep [Huawei Nova 9](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`2ASVB21B09005117`), [realme C55](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`7XHEIBPBLRJJSKFU`), and [Gigaset GX6](../../docs/reference/device-test-matrix.md#device-test-matrix-reference) (`GX6CTR500184`) in the next full inventory pass rather than the current short retry list.

## Regeneration note
To keep this report in the same style on future runs:
1. Start from the latest evidence outputs and the canonical names in `docs/reference/device-test-matrix.md`.
2. Map each serial to the matrix name, link each device name to `docs/reference/device-test-matrix.md#device-test-matrix-reference`, and keep the table ordered by descending SDK then human-readable name.
3. Split `Device Name` and `Serial` into separate columns.
4. Populate `Transport mode` per row from device class / SDK (`GATT` on API 31 and below, `L2CAP` on API 33+); use `n/r` only for rows not reverified in the current bundle.
5. Keep the recovery checklist as checkbox items, not prose, so future evidence deltas can be compared directly.

## Evidence highlights
- Rerun artifacts:
  - `/tmp/reference_android_direct_proof_20260627T200612` — failed because Android passive transport did not start within 30.0 seconds.
  - `/tmp/reference_android_direct_proof_20260627T200846` — diagnostic retry; no new discovery evidence before 60.0 seconds.
- Proof screen verification screenshots:
  - `/tmp/2AS-after-launch.png`
  - `/tmp/420-after-launch.png`
  - `/tmp/42c2cf-after-launch.png`
  - `/tmp/e909.png`
  - `/tmp/net1-after-install.png`
  - `/tmp/net2-after-install.png`
  - `/tmp/net3-after-install.png`
- Follow-up screenshots during the send-path evidence capture:
  - `/tmp/MJJ-latest.png`
  - `/tmp/MZL-after-start.png`
  - `/tmp/e909-after-tap.png`
  - `/tmp/net2-after-tap2.png`
  - `/tmp/net3-after-tap2.png`
- Log evidence captured during the evidence bundle included `Hello sent to ... -> Sent` for successful devices and `Hello send proceeding ...` / route-blocked diagnostics for blocked devices.
- The `R5CT83ACSJX` evidence set added a second confirmed send path and exposed a timeout on `e9097611` while waiting for the hello control.
- The `ZY22GCD9ST` evidence set added a peer-reachability failure (`NotSent(reason=UNREACHABLE)`) without overturning the earlier local send success.
- A later evidence bundle added another confirmed send from `adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp` and preserved the remaining `proceeding` / `unknown` classifications for `adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp`, `adb-P2126T004912-Na69Lt._adb-tls-connect._tcp`, and `e9097611`.
- A later evidence bundle reintroduced `hello=unknown` on `42004386e43c8589`, `42c2cf`, `EQUGS85LJNEIO7Z5`, and `MZLJMJAIO7SKS8BI`, and showed `R5CT83ACSJX` as `hello=proceeding` on a later attempt even though the device had already logged a completed send earlier in the run.

## Notes
- The pass confirmed the permission-unblock ordering that worked on the fleet: install first, grant permissions on-device, then launch.
- The proof UI was the authoritative success surface: `MeshLink Proof`, `State: Running`, and visible `Stop Proof` / `Send Hello` controls when available.
- Some devices were effectively passive or non-initiator paths; on those, Send Hello was intentionally not forced past the app's disabled state.
- Log analysis: the passive side reached `android.meshlink.controller.begin`, then only emitted ambient GATT scan accepts on other peers. Repeated `GATT side-link already active` / `connectIfNeeded skipped: no PSM` lines show it never established a sender-facing route or peer discovery path for this pair. The 60-second retry added no route-ready marker, so the problem is discovery/route setup rather than just the 30-second budget.
