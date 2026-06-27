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

## Transport mode
Transport mode in the underlying direct-proof summaries is derived from nested `final.timings.transportMode` when the top-level field is absent. The evidence set includes both `GATT` fallback on the passive/proof-app side and `L2CAP` on the carried sender transport. Treat this as report-level transport evidence, not a promise that every row carried the same bearer.

## Fleet coverage
- Connected devices observed: 14
  - 11 USB devices
  - 3 network ADB devices
- Network devices were installed before launch, as required.


## Device matrix

Legend: `ok` = confirmed in the latest sweep output, `yes`/`no` = proof-screen state, `sent`/`proceeding`/`unknown` = latest hello status, `n/r` = not reverified in the latest sweep bundle. The `Transport mode` column reflects the device-class bearer family used by the sweep path (`GATT` on API 31 and below, `L2CAP` on API 33+); it is separate from the report-level transport summary above.

| Device Name | Serial | Type | install | grants | launch | proof | Transport mode | latest hello | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [Nothing Phone (2)](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | 1f1dad34 | USB | ok | ok | ok | yes | L2CAP | sent | confirmed send |
| [Nothing Phone (1)](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | adb-P2126T004912-Na69Lt._adb-tls-connect._tcp | network | ok | ok | ok | yes | L2CAP | unknown | send button disabled; sweep 2 reported `hello=unknown` |
| [realme C55](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | 7XHEIBPBLRJJSKFU | USB | n/r | n/r | n/r | n/r | n/r | n/r | not reverified in the latest sweep bundle |
| [motorola edge 30 fusion](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | ZY22GCD9ST | USB | ok | ok | ok | yes | L2CAP | sent | sweep E also reported `NotSent(reason=UNREACHABLE)` on target `c67798` |
| [Nokia X20](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp | network | ok | ok | ok | yes | L2CAP | sent | confirmed send |
| [OPPO Reno8 5G](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | EQUGS85LJNEIO7Z5 | USB | ok | ok | ok | yes | L2CAP | unknown | sweep 1 reported `hello=unknown` |
| [Gigaset GX6 Outdoor Smartphone](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | GX6CTR500184 | USB | n/r | n/r | n/r | n/r | n/r | n/r | not reverified in the latest sweep bundle |
| [OPPO A77](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | MZLJMJAIO7SKS8BI | USB | ok | ok | ok | no | L2CAP | unknown | uninitialized; Bluetooth off; sweep 1 reported `hello=unknown` |
| [OnePlus Nord CE 2 5G](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp | network | ok | ok | ok | yes | L2CAP | proceeding | sweep 2 reported `hello=proceeding` |
| [Huawei nova 9 SE](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | 2ASVB21B09005117 | USB | n/r | n/r | n/r | n/r | n/r | n/r | not reverified in the latest sweep bundle |
| [Xiaomi Pocophone F1](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | e9097611 | USB | ok | ok | ok | yes | GATT | unknown | sweep C timeout, sweep 2 reported `hello=unknown` |
| [Samsung Galaxy Z Flip4](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | R5CT83ACSJX | USB | ok | ok | ok | yes | L2CAP | sent / proceeding | earlier confirmed send; later sweep 1 reported `hello=proceeding` |
| [Samsung Galaxy XCover4](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | 42004386e43c8589 | USB | ok | ok | ok | yes | GATT | unknown | sweep 1 reported `hello=unknown` |
| [Xiaomi Mi Note 3](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) | 42c2cf | USB | ok | ok | ok | yes | GATT | unknown | sweep 1 reported `hello=unknown` |

## Confirmed hello completions
- [Nothing Phone (2)](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) — `Hello sent to ... -> Sent`
- [Samsung Galaxy Z Flip4](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) — `Hello sent to ... -> Sent` (`routeReady=false` at tap time, but the send completed)
- [motorola edge 30 fusion](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) — `Hello sent to b06435 -> Sent`
- [Nokia X20](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) — `Hello sent to ... -> Sent`

## Recovery checklist
- [ ] Route-ready retry: retry [Samsung Galaxy Z Flip4](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`R5CT83ACSJX`) and [OnePlus Nord CE 2 5G](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp`) only after route-discovered / route-ready evidence is present, and after a fresh proof-screen check shows the Send Hello control enabled.
- [ ] Unknown-state retry: rerun [Nothing Phone (1)](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`adb-P2126T004912-Na69Lt._adb-tls-connect._tcp`), [Xiaomi Pocophone F1](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`e9097611`), [Samsung Galaxy XCover4](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`42004386e43c8589`), [Xiaomi Mi Note 3](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`42c2cf`), [OPPO Reno8 5G](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`EQUGS85LJNEIO7Z5`), and [OPPO A77](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`MZLJMJAIO7SKS8BI`) with Bluetooth/preflight/UI-idle checks before attempting hello.
- [ ] Inventory-gap reverify: keep [Huawei nova 9 SE](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`2ASVB21B09005117`), [realme C55](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`7XHEIBPBLRJJSKFU`), and [Gigaset GX6 Outdoor Smartphone](../../docs/reference/device-test-matrix.md#current-fleet-aliases-captured-during-the-2026-06-27-direct-proof-sweep) (`GX6CTR500184`) in the next full inventory sweep rather than the current short retry list.

## Regeneration note
To keep this report in the same style on future runs, start from the latest sweep outputs and the canonical names in `docs/reference/device-test-matrix.md`, then render rows in descending SDK order with `Device Name`, `Serial`, `Type`, `install`, `grants`, `launch`, `proof`, `Transport mode`, `latest hello`, and `Notes` columns. Link each device name to the matrix alias section, and only use the per-row transport value when the device class can be mapped confidently (`GATT` on API 31 and below, `L2CAP` on API 33+). If a device has not been reverified in the current bundle, keep `n/r` rather than guessing.

## Evidence highlights
- Proof screen verification screenshots:
  - `/tmp/2AS-after-launch.png`
  - `/tmp/420-after-launch.png`
  - `/tmp/42c2cf-after-launch.png`
  - `/tmp/e909.png`
  - `/tmp/net1-after-install.png`
  - `/tmp/net2-after-install.png`
  - `/tmp/net3-after-install.png`
- Follow-up screenshots during the send sweep:
  - `/tmp/MJJ-latest.png`
  - `/tmp/MZL-after-start.png`
  - `/tmp/e909-after-tap.png`
  - `/tmp/net2-after-tap2.png`
  - `/tmp/net3-after-tap2.png`
- Log evidence captured during the sweep included `Hello sent to ... -> Sent` for successful devices and `Hello send proceeding ...` / route-blocked diagnostics for blocked devices.
- Sweep C added a second confirmed send path (`R5CT83ACSJX`) and exposed a timeout on `e9097611` while waiting for the hello control.
- Sweep E added a peer-reachability failure for `ZY22GCD9ST` (`NotSent(reason=UNREACHABLE)`) without overturning the earlier local send success.
- Sweep 2 added another confirmed send from `adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp` and preserved the remaining `proceeding` / `unknown` classifications for `adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp`, `adb-P2126T004912-Na69Lt._adb-tls-connect._tcp`, and `e9097611`.
- Sweep 1 reintroduced `hello=unknown` on `42004386e43c8589`, `42c2cf`, `EQUGS85LJNEIO7Z5`, and `MZLJMJAIO7SKS8BI`, and showed `R5CT83ACSJX` as `hello=proceeding` on a later attempt even though the device had already logged a completed send earlier in the run.

## Notes
- The pass confirmed the permission-unblock ordering that worked on the fleet: install first, grant permissions on-device, then launch.
- The proof UI was the authoritative success surface: `MeshLink Proof`, `State: Running`, and visible `Stop Proof` / `Send Hello` controls when available.
- Some devices were effectively passive or non-initiator paths; on those, Send Hello was intentionally not forced past the app's disabled state.
