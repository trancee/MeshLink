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

Legend: `ok` = confirmed in the latest sweep output, `yes`/`no` = proof-screen state, `sent`/`proceeding`/`unknown` = latest hello status, `n/r` = not reverified in the latest sweep bundle. Transport mode is described in the report-level section above and is not repeated per row.

| Device Name | Serial | Type | install | grants | launch | proof | latest hello | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Nothing Phone (2) | 1f1dad34 | USB | ok | ok | ok | yes | sent | confirmed send |
| Nothing A063 | adb-P2126T004912-Na69Lt._adb-tls-connect._tcp | network | ok | ok | ok | yes | unknown | send button disabled; sweep 2 reported `hello=unknown` |
| realme RMX3710 | 7XHEIBPBLRJJSKFU | USB | n/r | n/r | n/r | n/r | n/r | not reverified in the latest sweep bundle |
| motorola edge 30 fusion | ZY22GCD9ST | USB | ok | ok | ok | yes | sent | sweep E also reported `NotSent(reason=UNREACHABLE)` on target `c67798` |
| Nokia X20 | adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp | network | ok | ok | ok | yes | sent | confirmed send |
| OPPO CPH2359 | EQUGS85LJNEIO7Z5 | USB | ok | ok | ok | yes | unknown | sweep 1 reported `hello=unknown` |
| Gigaset E940-2849-00 | GX6CTR500184 | USB | n/r | n/r | n/r | n/r | n/r | not reverified in the latest sweep bundle |
| OPPO CPH2385 | MZLJMJAIO7SKS8BI | USB | ok | ok | ok | no | unknown | uninitialized; Bluetooth off; sweep 1 reported `hello=unknown` |
| OnePlus DN2103 | adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp | network | ok | ok | ok | yes | proceeding | sweep 2 reported `hello=proceeding` |
| HUAWEI NAM-LX9 | 2ASVB21B09005117 | USB | n/r | n/r | n/r | n/r | n/r | not reverified in the latest sweep bundle |
| Xiaomi POCOPHONE F1 | e9097611 | USB | ok | ok | ok | yes | unknown | sweep C timeout, sweep 2 reported `hello=unknown` |
| Samsung Galaxy Z Flip4 | R5CT83ACSJX | USB | ok | ok | ok | yes | sent / proceeding | earlier confirmed send; later sweep 1 reported `hello=proceeding` |
| Samsung Galaxy XCover4 | 42004386e43c8589 | USB | ok | ok | ok | yes | unknown | sweep 1 reported `hello=unknown` |
| Xiaomi Mi Note 3 | 42c2cf | USB | ok | ok | ok | yes | unknown | sweep 1 reported `hello=unknown` |

## Confirmed hello completions
- Nothing Phone (2) (1f1dad34) — `Hello sent to ... -> Sent`
- Samsung Galaxy Z Flip4 (R5CT83ACSJX) — `Hello sent to ... -> Sent` (`routeReady=false` at tap time, but the send completed)
- motorola edge 30 fusion (ZY22GCD9ST) — `Hello sent to b06435 -> Sent`
- Nokia X20 (adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp) — `Hello sent to ... -> Sent`

## Recovery checklist
- [ ] Route-ready retry: retry Samsung Galaxy Z Flip4 (R5CT83ACSJX) and OnePlus DN2103 (adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp) only after route-discovered / route-ready evidence is present, and after a fresh proof-screen check shows the Send Hello control enabled.
- [ ] Unknown-state retry: rerun Nothing A063 (adb-P2126T004912-Na69Lt._adb-tls-connect._tcp), Xiaomi POCOPHONE F1 (e9097611), Samsung Galaxy XCover4 (42004386e43c8589), Xiaomi Mi Note 3 (42c2cf), OPPO CPH2359 (EQUGS85LJNEIO7Z5), and OPPO CPH2385 (MZLJMJAIO7SKS8BI) with Bluetooth/preflight/UI-idle checks before attempting hello.
- [ ] Inventory-gap reverify: keep HUAWEI NAM-LX9 (2ASVB21B09005117), realme RMX3710 (7XHEIBPBLRJJSKFU), and Gigaset E940-2849-00 (GX6CTR500184) in the next full inventory sweep rather than the current short retry list.

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
