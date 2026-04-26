# S03-UAT: IosBleTransport Manual Test Procedure

> **Proof level:** Real hardware. Requires macOS, Xcode, and physical iOS + Android devices.  
> **Slice:** S03 — IosBleTransport + State Preservation/Restoration  
> **Milestone:** M003 — BLE Transport

---

## Prerequisites

- **macOS** with Xcode 15+ installed
- **Physical iOS device** running iOS 11+ (not Simulator — BLE peripheral role not available in Simulator)
- **Physical Android device** running a build that includes `AndroidBleTransport` (S02)
- **nRF Connect** app installed on a third device (iOS or Android) for advertisement inspection
- iOS device paired to Mac (allow device in Xcode → Devices and Simulators)
- Both devices have Bluetooth enabled and location permissions granted

### Required Info.plist entries on the iOS host app

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>MeshLink uses Bluetooth to communicate with nearby devices.</string>

<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>
    <string>bluetooth-peripheral</string>
</array>
```

---

## Test 1 — Advertising Visible to nRF Connect

**Goal:** Verify the 16-byte advertisement payload is correctly encoded and visible.

**Steps:**
1. Launch the test app on the iOS device with `IosBleTransport.startAdvertisingAndScanning()` called.
2. Open nRF Connect on a third device, go to Scanner tab, and scan.
3. Locate the advertisement with service UUID `4d455348-0000-1000-8000-00805f9b34fb`.
4. Inspect the Service Data field (should be exactly 16 bytes).

**Expected results:**
- `serviceData[0] >> 5 == 1` (protocolVersion = 1)
- `serviceData[3]` is in range 128–255 (L2CAP PSM assigned by OS)
- `serviceData[4..15]` matches the device's keyHash (visible in app logs)
- iOS log output contains: `advertising started psm=<N> meshHash=<M>`

**Pass criteria:** 16-byte payload visible in nRF Connect with correct protocolVersion field.

---

## Test 2 — Peer Discovery (AdvertisementEvent Emission)

**Goal:** Verify iOS discovers Android peer and emits `AdvertisementEvent`.

**Steps:**
1. Start `AndroidBleTransport.startAdvertisingAndScanning()` on the Android device.
2. Start `IosBleTransport.startAdvertisingAndScanning()` on iOS.
3. Subscribe to `advertisementEvents` flow on iOS and observe events.

**Expected results:**
- Within 30 seconds, an `AdvertisementEvent` is emitted with:
  - `peerId` matching the Android device's keyHash
  - `serviceData` matching the Android advertisement payload (16 bytes)
  - `rssi` in range −90 to −30 (typical indoor BLE RSSI)
- iOS log: `BLE scan started allowDuplicates=<bool> tier=<PowerTier>`

**Pass criteria:** `AdvertisementEvent` received within 30 s with correct `peerId`.

---

## Test 3 — L2CAP Channel Opens and Streams Data

**Goal:** Verify bidirectional data transfer over `CBL2CAPChannel`.

**Steps:**
1. Run both transports as in Test 2 and wait for `AdvertisementEvent`.
2. `ConnectionInitiationPolicy` determines which side initiates. If iOS initiates:  
   iOS central connects → `openL2CAPChannel(psm)` → `didOpenL2CAPChannel`  
   If Android initiates, the inbound `peripheralManager:didOpenL2CAPChannel:` fires on iOS.
3. Once connected, call `sendToPeer(androidPeerId, testPayload)` from iOS.
4. Subscribe to `incomingData` on Android and verify receipt.
5. Send a payload from Android → verify `IncomingData` emitted on iOS.

**Expected results:**
- iOS log: `L2CAP channel opened (central) keyHash=<hex> PSM=<N>` (or inbound equivalent)
- `IncomingData(peerId=androidKeyHash, data=testPayload)` received on Android within 1 s
- iOS log: `L2CAP connection registered keyHash=<hex>`
- Keepalive log: `(no-op DATA frame)` every 10 s

**Pass criteria:** Round-trip DATA frames delivered in both directions.

---

## Test 4 — GATT Fallback (L2CAP Disabled)

**Goal:** Verify iOS falls back to GATT characteristic writes when L2CAP PSM is 0.

**Steps:**
1. Build Android with `BleTransportConfig(forceGatt = true)` so it advertises `l2capPsm = 0x00`.
2. Run both transports.
3. When iOS discovers Android with PSM=0, it should initiate GATT central connection instead of L2CAP.

**Expected results:**
- iOS log: `GATT/L2CAP connected keyHash=<hex>` followed by `GATT central ready keyHash=<hex>`
- No `L2CAP channel opened` log entry (GATT path taken)
- `sendToPeer` succeeds via `writeValue(data, forCharacteristic:dataWriteChar, type:.withoutResponse)`
- Android receives data via GATT write request (`onCharacteristicWriteRequest`)

**Pass criteria:** Data flows via GATT with no L2CAP attempt when PSM advertised as 0.

---

## Test 5 — State Preservation and Restoration

**Goal:** Verify `willRestoreState` rebuilds transport after OS-initiated termination.

### 5a — Background S&R

**Steps:**
1. Start both transports and wait for a live L2CAP or GATT connection.
2. Background the iOS app (press Home button).
3. Wait 60 s to allow iOS to decide on memory pressure termination (or use Xcode Memory Gauge to simulate).
4. Trigger a BLE event from Android (advertisement or data write) to wake iOS.
5. Observe the relaunched app.

**Expected results:**
- iOS log on relaunch: `willRestoreState (central) invoked` and/or `willRestoreState (peripheral) invoked`
- `onCentralPoweredOn()` logs: `S&R reconnecting <hex>` for each previously-connected peripheral
- Advertising resumes with new PSM (log: `advertising updated PSM=<N>`)
- Scanning restarts: `BLE scan started allowDuplicates=<bool>`

**Pass criteria:** S&R callbacks fire; scan + advertising resume; previously-known peers reconnect.

### 5b — Force-Quit Limitation (Expected Failure — Not a Bug)

**Steps:**
1. Force-quit the iOS app from the app switcher (swipe up).
2. Trigger a BLE event from Android to relaunch iOS.
3. Observe whether `willRestoreState` fires.

**Expected results:**
- `willRestoreState` is **NOT invoked** after force-quit.
- iOS is relaunched fresh with no restoration state.
- This is documented iOS platform behavior: the OS clears BLE restoration state on user force-quit.

**Pass criteria:** No `willRestoreState` invoked (correct platform behavior); fresh start succeeds.

> **Note for testers:** This is **not a failure** of the implementation. Apple's documentation explicitly states that user-initiated force-quit clears state preservation. The `IosBleTransport` code comments this limitation.

---

## Test 6 — Bluetooth Off/On Recovery

**Goal:** Verify graceful shutdown and automatic restart when Bluetooth is toggled.

**Steps:**
1. Start both transports with an active connection.
2. Turn off Bluetooth on the iOS device (Settings → Bluetooth → Off).
3. Observe iOS logs and `peerLostEvents` flow.
4. Turn Bluetooth back on after 10 s.
5. Observe recovery logs.

**Expected results — Bluetooth Off:**
- iOS log: `BLE power down — emitting PeerLostEvent for all active peers`
- `PeerLostEvent(peerId=<peer>, reason=CONNECTION_LOST)` emitted for all active peers
- `central state=4` (CBManagerStatePoweredOff) logged

**Expected results — Bluetooth On:**
- iOS log: `central state=5` (CBManagerStatePoweredOn = powered on)
- `BLE scan started` and `advertising updated` logged
- Rediscovery of Android peer within 30 s

**Pass criteria:** Clean `PeerLostEvent` on off; scan + advertising restart automatically on on.

---

## iOS-Specific Limitations (Documented)

| Limitation | Detail |
|---|---|
| No PHY adaptation | iOS manages PHY selection automatically; `requestConnectionPriority` is a documented no-op |
| Background advertising strips service data | When app is backgrounded, advertisement moves to overflow area with no local name; UUIDs are still present |
| `AllowDuplicatesKey` ignored in background | Background scan coalesces duplicate advertisements per Apple policy |
| Background-to-background discovery latency | Can take 10–15 minutes when both devices are backgrounded |
| Force-quit clears S&R state | User force-quit prevents `willRestoreState` — expected iOS platform behavior |
| UIApplicationDidReceiveMemoryWarningNotification | UIKit notification; triggers POWER_SAVER tier to reduce BLE overhead under memory pressure |
| L2CAP PSM changes on restart | OS assigns a new PSM after each process launch; advertisement is updated after `didPublishL2CAPChannel` fires |

---

## Pass/Fail Summary

| Test | Description | Result |
|---|---|---|
| T1 | Advertising visible in nRF Connect with correct payload | ☐ Pass / ☐ Fail |
| T2 | Android peer discovered, AdvertisementEvent emitted | ☐ Pass / ☐ Fail |
| T3 | L2CAP channel opens, bidirectional data flows | ☐ Pass / ☐ Fail |
| T4 | GATT fallback when PSM=0 | ☐ Pass / ☐ Fail |
| T5a | State Restoration rebuilds scan+advertising after OS kill | ☐ Pass / ☐ Fail |
| T5b | Force-quit limitation documented and confirmed | ☐ Pass / ☐ Fail |
| T6 | Bluetooth off → PeerLostEvents; on → auto-restart | ☐ Pass / ☐ Fail |

**Slice S03 UAT passes when all 7 rows are marked Pass.**
