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
Transport mode in the underlying direct-proof summaries is derived from nested `final.timings.transportMode` when the top-level field is absent. The evidence set includes both `GATT` fallback on the passive/proof-app side and `L2CAP` on the carried sender transport. Treat this as a report-level transport summary, not a row-by-row device attribute.

## Fleet coverage
- Connected devices observed: 14
  - 11 USB devices
  - 3 network ADB devices
- Network devices were installed before launch, as required.

## Device matrix

Legend: `ok` = confirmed in the latest sweep output, `yes`/`no` = proof-screen state, `sent`/`proceeding`/`unknown` = latest hello status, `n/r` = not reverified in the latest sweep bundle, transport summary = report-level `GATT/L2CAP` from nested timings.

| Device | Type | install | grants | launch | proof | latest hello | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `1f1dad34` | USB | ok | ok | ok | yes | sent | confirmed send |
| `2ASVB21B09005117` | USB | n/r | n/r | n/r | n/r | n/r | not reverified in the latest sweep bundle |
| `42004386e43c8589` | USB | ok | ok | ok | yes | unknown | sweep 1 reported `hello=unknown` |
| `42c2cf` | USB | ok | ok | ok | yes | unknown | sweep 1 reported `hello=unknown` |
| `7XHEIBPBLRJJSKFU` | USB | n/r | n/r | n/r | n/r | n/r | not reverified in the latest sweep bundle |
| `EQUGS85LJNEIO7Z5` | USB | ok | ok | ok | yes | unknown | sweep 1 reported `hello=unknown` |
| `GX6CTR500184` | USB | n/r | n/r | n/r | n/r | n/r | not reverified in the latest sweep bundle |
| `MZLJMJAIO7SKS8BI` | USB | ok | ok | ok | no | unknown | uninitialized; Bluetooth off; sweep 1 reported `hello=unknown` |
| `R5CT83ACSJX` | USB | ok | ok | ok | yes | sent / proceeding | earlier confirmed send; later sweep 1 reported `hello=proceeding` |
| `ZY22GCD9ST` | USB | ok | ok | ok | yes | sent | sweep E also reported `NotSent(reason=UNREACHABLE)` on target `c67798` |
| `e9097611` | USB | ok | ok | ok | yes | unknown | sweep C timeout, sweep 2 reported `hello=unknown` |
| `adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp` | network | ok | ok | ok | yes | sent | confirmed send |
| `adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp` | network | ok | ok | ok | yes | proceeding | sweep 2 reported `hello=proceeding` |
| `adb-P2126T004912-Na69Lt._adb-tls-connect._tcp` | network | ok | ok | ok | yes | unknown | send button disabled; sweep 2 reported `hello=unknown` |

## Confirmed hello completions
- `1f1dad34` — `Hello sent to ... -> Sent`
- `R5CT83ACSJX` — `Hello sent to ... -> Sent` (`routeReady=false` at tap time, but the send completed)
- `ZY22GCD9ST` — `Hello sent to b06435 -> Sent`
- `adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp` — `Hello sent to ... -> Sent`

## Recovery plan

1. Route-ready recovery: retry `R5CT83ACSJX` and `adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp` only after route-discovered / route-ready evidence is present. Treat the transport summary as report-level `GATT/L2CAP` evidence, not a guarantee that hello can proceed.
2. Unknown-state recovery: rerun `adb-P2126T004912-Na69Lt._adb-tls-connect._tcp`, `e9097611`, `42004386e43c8589`, `42c2cf`, `EQUGS85LJNEIO7Z5`, and `MZLJMJAIO7SKS8BI` with Bluetooth/preflight/UI-idle checks before attempting hello. `MZLJMJAIO7SKS8BI` also needs Bluetooth enabled before any retry.
3. Inventory-gap recovery: reverify `2ASVB21B09005117`, `7XHEIBPBLRJJSKFU`, and `GX6CTR500184` in the next full inventory sweep rather than the current short retry list.

## Send Hello outcomes that did not reach completion
These devices reached the proof UI, but the hello path was blocked, passive, or not conclusively completed in the captured evidence window.

- `adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp` — button enabled; log showed `Hello send proceeding ... routeReady=false` and sweep 2 still reported `hello=proceeding`
- `adb-P2126T004912-Na69Lt._adb-tls-connect._tcp` — Send Hello button was disabled; sweep 2 still reported `hello=unknown`
- `MZLJMJAIO7SKS8BI` — remained `State: Uninitialized`; Send Hello was disabled and Bluetooth was off; sweep 1 also reported `hello=unknown`
- `e9097611` — proof UI was present in earlier captures, but the sweep C job timed out waiting for the `Send Hello` element, so hello completion was not re-verified in that pass; sweep 2 still reported `hello=unknown`
- `ZY22GCD9ST` — sweep E also captured `Hello sent to c67798 -> NotSent(reason=UNREACHABLE)`, which indicates a peer-level reachability failure even though the device had already produced a local successful send earlier in the run
- `42004386e43c8589` — proof UI present; sweep 1 reported `hello=unknown`
- `42c2cf` — proof UI present; sweep 1 reported `hello=unknown`
- `EQUGS85LJNEIO7Z5` — proof UI present; sweep 1 reported `hello=unknown`

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
