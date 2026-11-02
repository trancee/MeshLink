# Library rules from the bearer bake-off

These are the rules the MeshLink BLE layer must follow, derived from the [bake-off evidence](README.md). [ADR 0009](../../adr/0009-ble-bearers-and-phy-policy.md) is the decision. This file adds the evidence run IDs.

- Run IDs name directories in [`raw/`](raw/). A `*` matches every run of that family.
- Commit IDs are on branch `prototype/bearer-bakeoff`.
- **[LOG]** means a logcat, syslog or `bluetoothd` capture. Those captures stay on the prototype branch (see [Diagnostic captures](README.md#diagnostic-captures)).
- **[UNVERIFIED]** marks a rule whose cause is not proven. The rule still holds as a defensive measure.

## Bearers

### R1. Start on GATT; upgrade to CoC; fall back to GATT

- Every link starts on GATT. GATT carries the node ID, capabilities and the dynamic PSM.
- After GATT is up, open an unbonded CoC when both sides support it (Android 10+, iOS).
- If the CoC fails to open or closes later, keep or reopen the GATT link and carry on. CoC is on by default; the app can turn it off.
- **Evidence:**
  - CoC opened in 98–100% of connections where GATT was up, on every Android 10+ ↔ iPhone pair in both directions: `m1-*`.
  - CoC still fails sometimes, so fallback is needed: `LinkClosedException l2cap-central … closed` (`m1-ipx-pixel6-*`, `m1-ip15-mi8-*` and 4 more `m1-*` pairs), `l2cap open failed NSError CBErrorDomain:0` (`m2-ipx-mi8-t01210932`), `IOException: read failed … ret: -1` (`m3-1m-suite-s10e-pixel6-t01213319`).
  - CoC gave 0.92–1.37× GATT throughput and up to about 20 ms lower round trip with Android centrals: `m2-*`, `m3-*-suite-*`. That is why it is an upgrade, not a replacement.

### R2. Use unbonded CoC only

- Android: `listenUsingInsecureL2capChannel` / `createInsecureL2capChannel`. iOS: `publishL2CAPChannel(withEncryption: false)`.
- Read the PSM over GATT on every connection. Never cache it; it is dynamic.
- **Evidence:** all CoC runs used this, with no pairing prompt: `m1-*`, `m2-*`. Bonded CoC is untested.

### R3. No CoC on Android 8 and 9

- These phones run GATT only. The CoC API needs API 29.
- **Evidence:** P10 lite and XCover 4 ran GATT only: `m5*-*`.

## PHY

### R4. Initiate on the 1M|2M mask, never 2M only

- Call `connectGatt` with `PHY_LE_1M_MASK | PHY_LE_2M_MASK`.
- A 2M-only initiating mask fails with status 147, because the legacy advert is on 1M.
- **Evidence:** smoke finding before the matrix; fixed in `a1c1bd9`. All matrix runs initiate on 1M|2M.

### R5. Request 2M after connecting; keep 1M otherwise

- Call `setPreferredPhy(2M)` after the link is up.
- **Evidence:** 2M gave 1.4–1.9× throughput and did not add connect failures (47/80 at 1M, 50/80 at 2M, no retries): `m3-*`.

### R6. Send PHY commands only when the phone reports 2M support

- Skip `setPreferredPhy` and `readPhy` when `isLe2MPhySupported()` is false.
- **Evidence:**
  - On the P10 lite, `readPhy` aborted `com.android.bluetooth` (`Assert failed: len == 5 … bad response length: 1`). [LOG, not retained]
  - The app then saw `cccd not started` and status 133: P10 lite central 0/20 in `m5-conn-p10lite-*` and `m5b-conn-p10lite-*`.
  - After fix `0748cd2`: 16/20 and 17/20, suite 5/5: `m5c-*-p10lite-*`.

## Connecting

### R7. Retry status 133 and 147 up to 3 times with a 500 ms × n backoff

- Retry a GATT connect that ends with status 133 or 147 before the link is up.
- A fixed delay between scan stop and connect does not help.
- **Evidence:**
  - Pixel 6 → S10e: 9/20 baseline, 8/20 with a 500 ms delay, 19/20 with retries. S10e → Pixel 6: 14/20 baseline, 20/20 with retries: `m147-px-s10e-base`, `m147-px-s10e-delay`, `m147-px-s10e-retry`, `m147-s10e-px-base`, `m147-s10e-px-retry`.
  - In steps 1–2, retries absorbed 637 status-133 and 30 status-147 first-try failures: `m1-*`, `m2-*`.
  - Legacy phones: P10 lite central 16–17/20 and XCover 4 central 20/20 and 12/20 with retries: `m5c-*`, `m5d-*`.

### R8. Treat a nil-error close about 30 s after an unanswered request as transient

- An iOS central may close a link with `error == nil` (or CBError 0). iOS reason 722, HCI 0x13 on Android.
- Treat it as a transient link loss; reconnect through R7.
- **Evidence:** `diag-b1-ip15-p10lite`, `diag-b1c-ip15-p10lite`, `diag-b1d-se2016-pixel6`. [LOG] `Timeout waiting for ATT response, forcing a disconnect` in `diag-b1c/ip15-bluetoothd.txt`, `diag-b1d/se2016-bluetoothd.txt`.

### R9. Keep one link per pair of phones

- Each node has a random 8-byte node ID, exchanged over GATT first.
- When two links exist between the same two phones, keep the link whose central has the lower node ID. Both sides drop the other.
- **Evidence:** 24 crossed rounds out of 60, 0 violations, median detection 299 ms: `m4-dual-ip15-se2020-t01225653` (20/20 crossed), `m4-dual-pixel6-s10e-t01224940` (4/20), `m4-dual-pixel6-ip15-t01224216` (0/20).

## Android stack hygiene

### R10. Pair every `startScan` with a `stopScan`

- Call `stopScan` on every path: result, scan failure, timeout and cancellation. Use `finally`.
- Never start a scan while another one from the same app is running.
- **Evidence:**
  - 22 leaked `ch.trancee.lab.host` scanner registrations after `m5-*` and `m5b-*`. [LOG] `xcover4-btm-post-m5b.txt`
  - XCover 4 central: `scan failed 2` in all of `m5b-conn-xcover4-*`, `m5c-*-xcover4-*`.
  - After a reboot and fix `9d97545`: 0 lab registrations before and after, XCover 4 central 20/20 and 12/20: `m5d-*`. [LOG] `xcover4-btm-pre-m5d.txt`, `xcover4-btm-post-m5d.txt`, `xcover4-scan-samples-m5d.tsv`.
  - The stack can finish a registration after reporting failure (`Context not found for ID 31`). [LOG, not retained] The library cannot release that one. **[UNVERIFIED]** how often this happens.

### R11. Stay under 5 scan starts per 30 s

- Space scan starts at least 6.5 s apart. Android throttles apps that start more than 5 scans in 30 s.
- **Evidence:** every matrix run used this pacing (`scan_pace_wait` timing mark): `m1-*` … `m5d-*`. Not tested without it.

### R12. Retry every Android GATT operation when the stack is busy

- `writeDescriptor`, `writeCharacteristic`, `readCharacteristic`, `requestMtu`, `discoverServices` and the PHY calls can return false (or `ERROR_GATT_WRITE_REQUEST_BUSY`) when the stack is busy.
- Retry each of them with a short backoff, through one serialized GATT queue.
- **Evidence:** `cccd not started` was the CCCD `writeDescriptor` returning false: `m5-conn-p10lite-*`. Fix `31ac3f7` added busy retry for every operation; it exposed the deeper `readPhy` crash (`cccd not started after 80 busy retries` in `m5b-conn-p10lite-*`). See R6.

### R13. Retry `openGattServer`

- `openGattServer` can return null when `onServerRegistered` arrives late. Retry it with a backoff.
- **Evidence:**
  - XCover 4 peripheral: `openGattServer null after 4 retries` in `m4-hub-ipx-t01221034` and `m4-hub-s10e-t01220443`. `onServerRegistered` arrived about 17 s late. [LOG] `x4-diag-*/xcover4-logcat.txt`
  - After the reboot it succeeded on the first try in all 8 `m5d-*` runs.
  - That leaked scanner registrations caused it is **[UNVERIFIED]**.

## Flow control

### R14. Pace the peripheral's notification bursts with credits or acks

- Don't fill the stack's queue with notifications. Send a window, then wait for an application-level credit or ack from the central.
- `onNotificationSent` on Android means the notification was queued, not sent. Do not use it as flow control.
- Platform requests (for example the iOS GAP Device Name read) must still get through.
- **Evidence:**
  - An iOS central's GAP Device Name read landed in a notify burst. The Android peripheral dropped the response (`already congested`, `failed to write data to L2CAP`, `xmit_hold_q` 180/200). iOS then closed the link after 30 s: `diag-b1-ip15-p10lite`, `diag-b1c-ip15-p10lite`, `diag-b1d-se2016-pixel6`. [LOG] `diag-b1b/`, `diag-b1c/`, `diag-b1d/`
  - iPhone-central suites failed `handshake_xx_classical` from attempt 2: `m2-se2016-*`, `m2-ip15-*`, `m5-suite-ip15-p10lite-*`, `m5b-suite-ip15-p10lite-*`.
- Bulk data prefers CoC when it is up, because CoC has its own credits.

### R15. Don't rely on Android write-without-response callbacks for receiver-side delivery

- On the XCover 4 the central's callbacks completed while the peripheral app stopped receiving writes.
- Use application-level acks for anything that must arrive.
- **Evidence:** `x4-diag-*`, `x4-mit*` (no knob fixed it). Gone after a reboot: `m5d-suite-pixel6-xcover4-t02092310`. Cause **[UNVERIFIED]**.

## iOS

### R16. Cap iOS inbound (peripheral-role) links at 3

- **v1 rule (accepted 2026-11-02, ADR 0009):** accept at most 3 incoming links on every iOS version. Make further links as central. The per-version measurements below are headroom, not the v1 rule.
- **iOS 15 and 16:** accept at most 3 incoming links. Make further links as central. At 4 inbound links these phones keep the links but stop being discoverable to every scanner, filtered or not.
- **iOS 17 and later:** 6 incoming links were held, all healthy, with the phone still visible. 6 is the measured value; anything higher is **unverified**.
- Even under the cap, expect an iOS peripheral to close an inbound link, and reconnect. The iPhone 15 did that at 3–4 links in step 4; the cause is unverified and step 6 did not reproduce it.
- **Evidence:**
  - iOS 15/16 invisible at 4, healthy at 3: `m6-inbound-se2016-r2-k3`, `-k4`, `m6-inbound-ipx-k3`, `-k4`, `m4-phub-ipx-t01223218`, `diag-b2-ip15-survey-held4`, `diag-b2-ip15-survey-after`, `diag-b2-phub-ipx`.
  - iOS 17/18/27 visible and healthy at 6: `m6-inbound-ip15-k6`, `m6-inbound-se2020-k6`, `m6-inbound-ip12mini-k6`.
  - iPhone 15 closes at 4 in step 4: `m4-phub-ip15-t01222247`.
- One phone per iOS version was measured, so the split may follow the hardware (A9/A11 vs A13+) instead. **Unverified.** Details: [inbound-link sweep](README.md#step-6-ios-inbound-link-sweep).

### R17. On the iOS peripheral, reuse the link through the control read

- An unbonded Android central that reconnects sends no new subscribe (`didSubscribe`).
- When a central reads the control characteristic (offset 0), replace any missing, closed or already-used server link for that central.
- **Evidence:** fixes `7892420` and `82a7e15` after the iOS smokes (`i1-*`, `i1b-*`). Every later iPhone-peripheral run depends on it: `m1-*-ip15`, `m2-*-ip15` and so on (`reopened_on_control_read` events).

### R18. Expect iOS central round trips in steps of about 60 ms

- An iPhone central's 64-byte GATT echo took 60 ms (sometimes 120 ms), regardless of the peer or bearer. Android centrals took 23–51 ms.
- Don't size timeouts or RTT estimates below that.
- **Evidence:** `m2-ip15-*`, `m2-se2020-*`, `m2-ip12mini-*`, `m2-ipx-*`, `m5d-suite-ip15-xcover4-t02093013`.

### R19. Don't advertise anything but the service UUID

- iOS advertises only the local name and service UUIDs, in a legacy 1M advertisement.
- The SE 2016 and iPhone X can't receive extended advertisements.
- **Evidence:** every run used a legacy advertisement with the 128-bit UUID only. iPhone centrals found Android peripherals with and without a UUID filter: `diag-b2-*-survey-f0`, `-f1`.

## Kotlin/Native

### R20. Never pass Kotlin strings through `NSLog` varargs

- `NSLog("%@", kotlinString)` crashed with `SIGTRAP` on the device.
- Format in Kotlin, escape `%`, and pass a single string.
- **Evidence:** fix `7892420`, during the iOS smokes.

## Older and slower hardware

### R21. Expect low throughput from the SE 2016

- The SE 2016 has an ATT MTU of 185 and no 2M. It carried 5.5–17 kB/s in every role. XXhfs over GATT took 208–690 ms.
- Size handshake timeouts for that, not for modern phones.
- **Evidence:** `m2-*-se2016-*`, `m2-se2016-*`.
