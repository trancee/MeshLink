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

## Fleet coverage
- Connected devices observed: 14
  - 11 USB devices
  - 3 network ADB devices
- Network devices were installed before launch, as required.

## Confirmed hello completions
- `1f1dad34` — `Hello sent to ... -> Sent`
- `ZY22GCD9ST` — `Hello sent to b06435 -> Sent`

## Send Hello outcomes that did not reach completion
These devices reached the proof UI, but the hello path was blocked, passive, or not conclusively completed in the captured evidence window.

- `R5CT83ACSJX` — button enabled; log showed `Hello send proceeding ... routeReady=false`
- `adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp` — button enabled; log showed `Hello send proceeding ... routeReady=true` but no completion line was captured in the checked window
- `adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp` — button enabled; log showed `Hello send proceeding ... routeReady=false`
- `adb-P2126T004912-Na69Lt._adb-tls-connect._tcp` — Send Hello button was disabled
- `MZLJMJAIO7SKS8BI` — remained `State: Uninitialized`; Send Hello was disabled and Bluetooth was off
- `e9097611` — proof UI was present, but `ui dump` intermittently failed with `ERROR: could not get idle state`, so hello completion was not re-verified from the capture window
- `42004386e43c8589` — proof UI present; Send Hello disabled in the captured screenshot
- `42c2cf` — proof UI present; Send Hello disabled in the captured screenshot
- `EQUGS85LJNEIO7Z5` — proof UI present; Send Hello disabled in the captured screenshot

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

## Notes
- The pass confirmed the permission-unblock ordering that worked on the fleet: install first, grant permissions on-device, then launch.
- The proof UI was the authoritative success surface: `MeshLink Proof`, `State: Running`, and visible `Stop Proof` / `Send Hello` controls when available.
- Some devices were effectively passive or non-initiator paths; on those, Send Hello was intentionally not forced past the app's disabled state.
