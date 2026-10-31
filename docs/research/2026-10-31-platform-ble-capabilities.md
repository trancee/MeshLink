# Platform BLE capabilities for MeshLink (Android API 26+, iOS 15+)

- **Date:** 2026-10-31
- **Ticket:** `.scratch/meshlink-v1/issues/02-platform-ble-capabilities.md`
- **Every URL below was accessed on 2026-10-31** unless another date is given.

## Evidence labels

| Label | Meaning |
|---|---|
| **[DOC]** | Stated in a primary vendor or specification source: Android reference or behaviour-change pages, AOSP source, Apple developer documentation, Apple Accessory Design Guidelines, or Bluetooth Core 6.1. |
| **[AOSP]** | Read directly from AOSP `packages/modules/Bluetooth` `main` source on 2026-10-31. This is implementation, not API contract, and OEM builds may differ. |
| **[EMP:cairn]** | Observed on real devices by the sibling project *cairn* (`../cairn`). Its lessons, ADRs and issues name the device and OS. |
| **[SEC]** | Secondary or third-party report. Not verified here. |
| **[UNVERIFIED]** | Not established by any source found. Needs device-lab proof. |

## 1. Question

What can MeshLink rely on from BLE on Android (API 26 → current 37/37.2) and iOS (15 → current 27)? The ticket covers:

- GATT client and server
- LE L2CAP connection-oriented channels (CoC): APIs, bonding/encryption, interoperability
- PHY control and extended advertising
- ATT MTU and data length extension (DLE)
- Maximum concurrent connections
- Advertising payload limits, including the iOS overflow area and service data
- Background execution: iOS state restoration, Android foreground services (FGS) and CompanionDeviceService
- Permissions
- Address randomisation

Unverifiable items must be marked as such.

## 2. Sources

### Android: primary

- **API reference** (each "Added in API level" was extracted from the HTML):
  - BluetoothAdapter: https://developer.android.com/reference/android/bluetooth/BluetoothAdapter
  - BluetoothDevice: https://developer.android.com/reference/android/bluetooth/BluetoothDevice
  - BluetoothGatt: https://developer.android.com/reference/android/bluetooth/BluetoothGatt
  - BluetoothGattServer: https://developer.android.com/reference/android/bluetooth/BluetoothGattServer
  - BluetoothGattConnectionSettings: https://developer.android.com/reference/android/bluetooth/BluetoothGattConnectionSettings
  - BluetoothSocketSettings: https://developer.android.com/reference/android/bluetooth/BluetoothSocketSettings
  - ScanSettings.Builder: https://developer.android.com/reference/android/bluetooth/le/ScanSettings.Builder
  - AdvertisingSetParameters.Builder: https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters.Builder
  - BluetoothLeAdvertiser: https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser
  - AdvertiseCallback: https://developer.android.com/reference/android/bluetooth/le/AdvertiseCallback
  - CompanionDeviceManager: https://developer.android.com/reference/android/companion/CompanionDeviceManager
- **Guides and policy:**
  - Permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
  - Background BLE: https://developer.android.com/develop/connectivity/bluetooth/ble/background
  - FGS types: https://developer.android.com/develop/background-work/services/fgs/service-types
  - Android 14 behaviour changes (MTU 517): https://developer.android.com/about/versions/14/behavior-changes-all
  - Android 15, 16 and 17 behaviour-change pages (all-apps and targeting): https://developer.android.com/about/versions/15/behavior-changes-all, https://developer.android.com/about/versions/16/behavior-changes-all, https://developer.android.com/about/versions/17/behavior-changes-all and the `-15`/`-16`/`-17` targeting pages
  - Google Play FGS policy: https://support.google.com/googleplay/android-developer/answer/13392821
- **AOSP `main`:**
  - `packages/modules/Bluetooth` files: `system/stack/gatt/gatt_utils.cc`, `system/internal_include/bt_target.h`, `android/app/src/com/android/bluetooth/le_scan/{ScanManager,AppScanStats}.java` and `android/app/src/com/android/bluetooth/btservice/AdapterService.java`
  - Base URL: https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/

### Apple: primary

- **Core Bluetooth documentation** (per-symbol availability taken from the doc JSON at `developer.apple.com/tutorials/data/documentation/corebluetooth/...`):
  - `CBPeripheralManager.startAdvertising(_:)`: https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)
  - `CBCentralManager.scanForPeripherals(withServices:options:)`: https://developer.apple.com/documentation/corebluetooth/cbcentralmanager/scanforperipherals(withservices:options:)
  - `CBL2CAPChannel`: https://developer.apple.com/documentation/corebluetooth/cbl2capchannel
  - `publishL2CAPChannel(withEncryption:)`: https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/publishl2capchannel(withencryption:)
  - `CBCentralManager.Feature`: https://developer.apple.com/documentation/corebluetooth/cbcentralmanager/feature
  - `maximumWriteValueLength(for:)`: https://developer.apple.com/documentation/corebluetooth/cbperipheral/maximumwritevaluelength(for:)
  - `CBConnectPeripheralOptionEnableAutoReconnect`: https://developer.apple.com/documentation/corebluetooth/cbconnectperipheraloptionenableautoreconnect
  - `CBAdvertisementDataOverflowServiceUUIDsKey`: https://developer.apple.com/documentation/corebluetooth/cbadvertisementdataoverflowserviceuuidskey
  - Channel Sounding symbols (iOS 27): https://developer.apple.com/documentation/corebluetooth
- **Other Apple sources:**
  - Accessory Design Guidelines R32 (2026-10-23), §56–57 BLE: https://developer.apple.com/accessories/Accessory-Design-Guidelines.pdf
  - Core Bluetooth Programming Guide, Background Processing (archived): https://developer.apple.com/library/archive/documentation/NetworkingInternet/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformanceTipsForIOSApps.html

### Bluetooth SIG: primary

Bluetooth Core Specification 6.1 HTML: https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-61/out/en/

- Vol 3 Part F (ATT) §3.2.9
- Vol 3 Part A (L2CAP) §4.22 and Table 4.15
- Vol 6 Part B (Link Layer) §2.3.4 and §4.5.10

### Empirical prior art (study only)

- **cairn:**
  - `docs/agents/lessons.md`
  - ADRs 0003, 0025, 0028, 0038–0042
  - `.scratch/cairn-r1/issues/57-legacy-os-device-matrix.md`
  - `docs/research/` (Android and iOS background BLE)
- **MeshLink-template:** `docs/reference/ble-spike.md` and `docs/explanation/template-design-review.md`

### Secondary

- Ditto engineering blog on L2CAP between iOS and Android (2021)
- JuulLabs/Kable discussion #588 (L2CAP)
- Stack Overflow threads on iOS connection limits

These are cited only as **[SEC]**.

## 3. Capability matrix

### 3.1 Android by API level

The "API" column shows when a member was *added*. Its availability depends on the controller wherever the table notes that.

| API | Capability | Evidence |
|---|---|---|
| 18/21 | **GATT client:** `connectGatt(ctx, autoConnect, cb)` (18), `requestMtu` (21), `requestConnectionPriority` (21; HIGH/BALANCED/LOW_POWER). **GATT server:** `openGattServer`, `notifyCharacteristicChanged` (18). **Legacy advertising:** `BluetoothLeAdvertiser.startAdvertising` (21); data over 31 bytes fails with `ADVERTISE_FAILED_DATA_TOO_LARGE`. | [DOC] BluetoothGatt, BluetoothLeAdvertiser, AdvertiseCallback |
| 23 | `connectGatt(..., transport)`. Pass `TRANSPORT_LE` to avoid BR/EDR dual-mode confusion. | [DOC] BluetoothDevice |
| **26** (floor) | **PHY control:** `connectGatt(..., transport, phy[, handler])`, `BluetoothGatt.setPreferredPhy`/`readPhy` and `BluetoothGattServer.setPreferredPhy`/`readPhy`. The docs say `setPreferredPhy` is "just a recommendation… controller can override". **Feature probes:** `isLe2MPhySupported`, `isLeCodedPhySupported`, `isLeExtendedAdvertisingSupported`, `isLePeriodicAdvertisingSupported`, `getLeMaximumAdvertisingDataLength` (0 if extended advertising is unsupported). **Extended advertising:** `startAdvertisingSet` + `AdvertisingSetParameters.Builder` (`setLegacyMode`, `setPrimaryPhy`, `setSecondaryPhy` 1M/2M/Coded, `setInterval` 160–16 777 215 × 0.625 ms so ≥ 100 ms, `setAnonymous`, `setConnectable`, `setScannable`). **Scanning:** `ScanSettings.Builder.setLegacy` (default **true**, so it must be false to receive extended adverts), `setPhy` (only used when legacy is false), and PendingIntent `startScan`. | [DOC] |
| 29 | **LE L2CAP CoC:** `BluetoothAdapter.listenUsingL2capChannel` / `listenUsingInsecureL2capChannel` and `BluetoothDevice.createL2capChannel` / `createInsecureL2capChannel`. The secure variant means the "remote device will be authenticated and communication … encrypted". The insecure variant means the "link key is not required to be authenticated". The PSM is dynamic (`BluetoothServerSocket.getPsm()`), and the "application is responsible for obtaining PSM value from remote device". | [DOC] BluetoothAdapter, BluetoothDevice |
| 31 | Runtime permissions **BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT** ("Nearby devices"). `neverForLocation` on SCAN removes the location requirement. Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`/`ACCESS_FINE_LOCATION` should be `maxSdkVersion="30"`. FGS start-from-background restrictions begin. CDM `startObservingDevicePresence(String)` was added (deprecated in 36). | [DOC] bt-permissions, background guide, CDM |
| 33 | `writeCharacteristic(char, value, type)`, `writeDescriptor(desc, value)` and `BluetoothGattServer.notifyCharacteristicChanged(dev, char, confirm, value)` return status codes. The byte-array-free overloads are deprecated. `getRemoteLeDevice(addr, addrType)` was added. | [DOC] |
| 34 | **All apps:** on the first `requestMtu` from any GATT client on an ACL, the stack requests ATT MTU **517**. Later `requestMtu` calls on that ACL are ignored. FGS **must declare a type**; `connectedDevice` needs `FOREGROUND_SERVICE_CONNECTED_DEVICE` plus a prerequisite, which can be a granted BLUETOOTH_CONNECT/ADVERTISE/SCAN. `CONNECTION_PRIORITY_DCK`; `AdvertisingSetParameters.Builder.setDiscoverable`. | [DOC] Android 14 behaviour changes, FGS types |
| 35 | `BluetoothDevice.getAddressType()` (PUBLIC/RANDOM/ANONYMOUS/UNKNOWN); `BluetoothGatt.GATT_CONNECTION_TIMEOUT`. The BOOT_COMPLETED FGS start ban (target 35) lists dataSync, camera, mediaPlayback, phoneCall, mediaProjection and microphone; **connectedDevice is not listed**. | [DOC] |
| 35 (stack) | The GATT connection (TCB) table is `min(max(sysprop bluetooth.core.le.max_number_of_concurrent_connections, 8), 16)`. Earlier releases used a fixed 7 (cairn). | [AOSP] `gatt_utils.cc` L106–112, `bt_target.h` L357–363 · [EMP:cairn] ADR 0025: 8/10/16 seen in fleet `dumpsys`, 7 on ≤ 34 |
| 36 | Socket API `BluetoothAdapter.listenUsingSocketSettings(BluetoothSocketSettings)`, TYPE_LE/RFCOMM; BLUETOOTH_PRIVILEGED is needed only for an offload data path. `ObservingDevicePresenceRequest` and `CompanionDeviceService.onDevicePresenceEvent`. **Target 36:** bond-loss handling and `ACTION_KEY_MISSING`. | [DOC] |
| 36.1 | `ScanSettings.Builder.setRssiThreshold` and `setScanType(PASSIVE/ACTIVE)`. `BluetoothGatt.requestSubrateMode` needs BLUETOOTH_PRIVILEGED or a CDM association, so it is not generally available. | [DOC] |
| 37 | `connectGatt(BluetoothGattConnectionSettings, Executor, callback)` deprecates all older `connectGatt` overloads. The settings class has transport, autoConnect, automaticMtu and opportunistic. `isLeHighDataThroughputPhySupported` (HDT PHY). **All apps:** autonomous re-pairing. **Target 37:** RFCOMM `read()` returns −1 on close, matching LE CoC. | [DOC] |
| 37.2 | RSSI/path-loss thresholds were added to `BluetoothGattConnectionSettings`. | [DOC] |

**Scanning limits in AOSP `main`** ([AOSP]; not API contract, and OEMs may override):

- **Start quota:** `DEFAULT_SCAN_QUOTA_COUNT = 5` and `DEFAULT_SCAN_QUOTA_WINDOW_MILLIS = 30 s`. Both can be overridden through DeviceConfig. Exceeding the quota gives `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` (`AdapterService.java` ~L6975; `ScanController` ~L1115).
- **Long scans:** `DEFAULT_SCAN_TIMEOUT_MILLIS = 10 min`. After that, an unfiltered scan is moved to *opportunistic* (it receives nothing of its own) and a filtered scan is downgraded to LOW_POWER (`ScanManager.regularScanTimeout`).
- **Background importance:** an app with importance worse than FOREGROUND_SERVICE is capped at LOW_POWER (140 ms window / 1400 ms interval) while the screen is on, and at SCREEN_OFF (512/10240 ms) while it is off. A FGS counts as foreground.
- **Screen off:** unfiltered scans get no results while the screen is off (`isFilteredScan` gating).

**Controller-dependent features** (2M, Coded, extended advertising, data length) must be probed at runtime on API 26+. **Empirical:** all 15 cairn fleet phones that reported extended advertising also reported 2M, across API 29–37 [EMP:cairn ADR 0038].

### 3.2 iOS by version

| iOS | Capability | Evidence |
|---|---|---|
| 6+ | **Advertising:** `CBPeripheralManager.startAdvertising` accepts only `LocalName` and `ServiceUUIDs`. In the foreground it has ≤ 28 B plus a 10 B scan response for the name. UUIDs that don't fit go to an **overflow area** "discoverable only by an iOS device explicitly scanning for them". In the background the name is dropped and *all* UUIDs go to overflow. `CBAdvertisementDataServiceDataKey` and `ManufacturerData` are **receive-only**, so iOS apps cannot advertise service data. `CBCentral.maximumUpdateValueLength`. | [DOC] startAdvertising, advertisement keys |
| 7+ | State preservation and restoration (`CBCentralManagerOptionRestoreIdentifierKey` / `CBPeripheralManagerOptionRestoreIdentifierKey`). | [DOC] |
| 9+ | `maximumWriteValueLength(for:)`. | [DOC] |
| 11+ | **L2CAP CoC:** `CBPeripheralManager.publishL2CAPChannel(withEncryption:)`, `CBPeripheral.openL2CAPChannel(_:)`, `CBL2CAPChannel` (input/output streams). The system assigns the PSM and "L2CAP channels aren't discoverable by themselves", so the app must convey the PSM (for example in a GATT characteristic). `canSendWriteWithoutResponse` + `peripheralIsReady(toSendWriteWithoutResponse:)` provide backpressure. | [DOC] |
| 13+ | `CBCentralManager.supports(.extendedScanAndConnect)`: "hardware supports extended scans and enhanced connection creation". `registerForConnectionEvents`. | [DOC] |
| **15** (floor) | No new radio APIs relevant to MeshLink were found between 13 and 17. | [DOC] symbol enumeration |
| 17+ | `CBConnectPeripheralOptionEnableAutoReconnect`. | [DOC] |
| 26+ | An app that holds a CBManager and starts a Live Activity gets **foreground scan privileges while backgrounded**: it can scan without service UUIDs, and duplicates are allowed. Apple does not state whether advertising escapes the overflow area in this mode. | [DOC] scanForPeripherals discussion; advertising effect [UNVERIFIED] |
| 27 | Channel Sounding (`CBCentralManager.Feature.channelSounding`, `CBChannelSoundingSessionConfiguration`). Not relevant to MeshLink v1. | [DOC] |

**There is no public API for** PHY selection, ATT MTU request, DLE or connection interval. Apps can only read the results: `maximumWriteValueLength` and `maximumUpdateValueLength`. `CBPeripheralManager.setDesiredConnectionLatency` is peripheral-side only, coarse, and not guaranteed. [DOC]

**What Apple devices do as a central** (ADG R32 §57, written for accessories; it describes Apple devices' behaviour):

- They use resolvable random addresses (§57.8).
- They negotiate DLE 27→251 (§57.7).
- They request an ATT MTU larger than the default and choose the "optimal" value (§57.11).
- They expect connection parameters of interval min ≥ 15 ms in multiples of 15 ms, slave latency ≤ 30, and supervision timeout 6–18 s with Interval_Max × (latency + 1) ≤ 6 s. Requests outside these may be rejected (§57.6). [DOC]

**Extended adverts on iOS:**

- **Measured by cairn:**
  - iOS reports extended adverts only when the Android advertiser uses a **2M secondary PHY**: iPhone 12 mini iOS 27, iPhone 15 iOS 17.6.1, iPhone SE 2020 iOS 18.7.8.
  - iPhone X iOS 16.7.16 saw none even at 2M.
  - iPhone SE 2016 iOS 15.8.8 scans 1M only.
  - Sources: [EMP:cairn ADR 0038, issue 57]
- **Undocumented:** Apple documents no extended-advert reception limits. A 124 B cap on iPhone XS and later is cited from WWDC19 session 901 via cairn [SEC].

### 3.3 Specification limits (Bluetooth Core 6.1) [DOC]

- **Attribute value:** max **512 octets** (Vol 3 Part F §3.2.9). This caps a single characteristic value whatever the MTU.
- **ATT_MTU:** LE minimum is 23. The Android stack uses 517 = 512 + 5 for long writes.
- **Link-layer data:** PDU payload ≤ **251 octets** with DLE. MaxTx/RxOctets is ≥ 27.
- **Legacy advertising:** 31 octets AdvData plus 31 octets scan response.
- **Extended advertising:** host data ≤ **1650 octets** before fragmentation, and the controller must reserve at least 31.
- **LE credit-based L2CAP:** the minimum MTU and minimum MPS are both 23. MPS can be up to 65533. SPSM 0x0080–0x00FF is dynamic and "shall be obtained from the service in GATT upon every reconnection" (Table 4.15). This is the normative basis for exchanging the PSM over GATT.

### 3.4 Connections and throughput

| Item | Android | iOS |
|---|---|---|
| Concurrent LE links | 7 TCBs on ≤ 34. On 35+, 8–16 per sysprop [AOSP]. Shared by client and server roles and all apps. Status 133 appears when the table is full [EMP:cairn]. cairn budget: 5 usable slots on ≤ 34, 6 on 35+ [EMP:cairn ADR 0025]. Only one outgoing dial at a time on API ≤ 28 [EMP:cairn]. | **Unpublished** by Apple. Third parties report more than 20 as central [SEC]. [UNVERIFIED] |
| ATT MTU observed | 517 Android↔Android. iOS central → Android server: 293 on recent iPhones (515 in one legacy case), 185 on SE 2016 [EMP:cairn ADR 0038]. | The OS chooses the MTU. iOS reports a with-response write length of 512 regardless of MTU, so use the without-response length + 3 to infer the MTU [EMP:cairn]. |
| Connection params | `requestConnectionPriority` only. `requestLeConnectionUpdate` is hidden [DOC/EMP]. | iOS uses a 30 ms interval with a 720 ms supervision timeout, and rejects Android's 20 s supervision timeout with 0x3B [EMP:cairn]. |
| Controller defects | A Redmi controller corrupts 512 B writes at the 251 B PDU boundary; an app-level CRC-16 fixed it [EMP:cairn lessons]. | — |

## 4. Background behaviour

### Android [DOC unless marked]

- **What the docs allow:** there is "no limitation" on scanning or connecting while the app is not visible, *but the process must be alive*.
- **Keeping the process alive:**
  - Use a `connectedDevice` FGS. From API 34 it needs a declared type and `FOREGROUND_SERVICE_CONNECTED_DEVICE`; from API 31 it cannot be started from the background except under exemptions.
  - Or use CompanionDeviceService with `REQUEST_COMPANION_RUN_IN_BACKGROUND` / `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`. CDM `associate()` needs user UI for **each** device, which is unsuitable for arbitrary mesh peers.
- **Waking a dead process:** a PendingIntent `startScan` (API 26) can restart it.
- **Scan throttling:** see §3.1 for the quota, timeouts and downgrades [AOSP]. Without an FGS, a backgrounded app's scans run at LOW_POWER or slower.
- **Play policy:** a Play Console FGS declaration is required. The `connectedDevice` examples are wearables and peripherals, and the review video must show pairing. Whether a phone-to-phone mesh qualifies is **[UNVERIFIED] review risk**.
- **Doze and OEM battery killers:** not covered by Android BLE docs. **[UNVERIFIED]** per OEM. cairn observed OEM differences [EMP:cairn research].

### iOS [DOC unless marked]

- **Background modes:** `bluetooth-central` and `bluetooth-peripheral`.
- **Background scanning:**
  - It requires explicit service UUIDs.
  - Options such as `AllowDuplicates` are ignored.
  - Discoveries are coalesced and the scan cadence drops (archived Background Processing guide).
- **Background advertising:**
  - The local name is dropped and all service UUIDs go to the overflow area.
  - Only a foreground or background iOS scanner that explicitly asks for that UUID can see it.
  - **Android cannot see a backgrounded iOS advertiser by service UUID.** The overflow area is an Apple proprietary manufacturer-data encoding. Apple documents it only as iOS-to-iOS discoverable; whether Android can see it is [UNVERIFIED].
- **State restoration:**
  - After the app is terminated by the system (not force-quit by the user), iOS relaunches it for BLE events: a discovered UUID, a connection, notifications, or a write to its GATT server.
  - Each wake gets about 10 s (archived guide).
  - Scene-based apps receive nil `launchOptions`, so the restore identifier must be persisted.
  - A user force-quit disables relaunch. This is established iOS behaviour stated in Apple guidance; an exact current page was not re-located today, so it is [DOC-archived].
- **iOS 26+ Live Activity:** grants foreground scan privileges while backgrounded (§3.2). Whether this extends to advertising is [UNVERIFIED].
- **Background connection intervals:** cadence after backgrounding is [UNVERIFIED]. No primary source was found.

## 5. Cross-platform interoperability risks

1. **Discovery asymmetry (high).**
   - iOS cannot send service data or manufacturer data, so iOS adverts carry only a 128-bit service UUID and a name. A backgrounded iOS device shows neither to Android.
   - Consequence: discovery must work with **iOS as scanner/central** and Android as advertiser. Android must also be able to dial iOS devices it saw while they were in the foreground.
2. **Extended adverts.**
   - iOS sees extended adverts only on a 2M secondary PHY, and not at all on some older iPhones [EMP:cairn].
   - Coded PHY is not exposed on iOS, and no source shows iOS receiving Coded [UNVERIFIED].
   - Consequence: a legacy 31 B advert must always be present.
3. **L2CAP CoC Android↔iOS.**
   - Both stacks expose dynamic-PSM LE CoC (Android 29+, iOS 11+) [DOC].
   - Third parties report insecure CoC works across platforms, while secure CoC (which requires bonding) is unreliable [SEC: Ditto, Kable #588].
   - cairn never tested it.
   - Status: **[UNVERIFIED] for MeshLink**, needs device-lab proof.
   - Secure CoC implies bonding UX and bond-state management, including Android 16/17 bond-loss and re-pair behaviour. Insecure CoC plus app-layer Noise is the only option that avoids pairing dialogs.
4. **Crossed dials and ACL sharing.**
   - Bluedroid cannot distinguish two ACLs to the same address (Android 8/9).
   - `BluetoothGatt.close()` can tear down a shared ACL.
   - The server should call `BluetoothGattServer.connect(device, false)` to hold an inbound link.
   - Source: [EMP:cairn lessons]. The mesh needs a deterministic dial-arbitration rule.
5. **MTU and length mismatch.**
   - The negotiated MTU differs by direction and pair (185/293/517).
   - iOS misreports the with-response write length [EMP:cairn].
   - Payload sizing must come from the observed without-response length, capped at 512 per attribute.
6. **Connection parameters.**
   - Apple rejects supervision timeouts outside its range [EMP:cairn; ADG §57.6 DOC].
   - Android's `requestConnectionPriority` cannot target iOS-compliant values precisely.
   - Expect link drop and recovery as a normal event.
7. **Address randomisation.**
   - iPhones use RPAs that rotate; a rotation produces a new address and a new link identity [DOC ADG §57.8; EMP:cairn].
   - Android advertisers use `ADDRESS_TYPE_DEFAULT` unless the app holds BLUETOOTH_PRIVILEGED [DOC]; what address that resolves to on each OEM is [UNVERIFIED].
   - The rotation period on current AOSP `main` was not located; older AOSP (via a third-party mirror, `BTM_BLE_PRIVATE_ADDR_INT_MS`) was 15 min [SEC], so the current value is [UNVERIFIED].
   - Consequence: **the BLE address must never be the peer identity.** Use a protocol-level identity.
8. **Android GATT status 133.**
   - It appears with a full TCB table, on OEM devices, and in stale-ACL cases [EMP:cairn, MeshLink-template spike on Huawei/Nothing].
   - It needs retry with backoff, plus slot accounting.
9. **iOS cannot disconnect a central from its GATT server** [EMP:cairn; no API exists, DOC by absence]. Shedding peers therefore has to be cooperative at the protocol level.

## 6. Implications for MeshLink (proposals for later decision tickets)

- **Baseline transport:** GATT, as client and server, on every supported OS. It works on API 26+ and iOS 15+. L2CAP CoC is an **optional upgrade** on Android 29+ ↔ iOS/Android 29+:
  - insecure channel, with all security at the app layer;
  - PSM published in a GATT characteristic and re-read on every connection, as Core Table 4.15 requires;
  - fallback to GATT on failure.
- **Advertising:**
  - Always run a legacy connectable advert carrying the 128-bit MeshLink service UUID. Android may add 16-bit service data for a short peer hint.
  - iOS can carry only the UUID, plus the local name while in the foreground.
  - An optional Android extended set must use a 2M secondary PHY. Never use Coded only.
  - Do not design anything that needs payload in the advert.
- **Fragmentation:** fragment body ≤ min(observed without-response length or `notify` length, 512) − MeshLink header. Never assume more than ATT_MTU 23 until measured. Add a per-fragment integrity check (cf. the Redmi controller defect).
- **Fan-out:** plan for about **5–6 simultaneous BLE links on Android** (fewer on ≤ 34). iOS is unknown. This bounds the "high-density" profile:
  - routing must assume a sparse neighbour set and rotate links;
  - it cannot assume a full mesh of every in-range peer.
  - This needs a feasibility check against GLOSSARY density targets.
- **Background:**
  - Android: a typed `connectedDevice` FGS (Play declaration risk), with PendingIntent scan as a revival hook.
  - iOS: background mode central + peripheral and state restoration, best effort only. iOS↔iOS background discovery relies on overflow UUIDs. Android↔background-iOS discovery is effectively one-way (iOS finds Android).
  - Document "both iOS devices backgrounded and never foregrounded since boot" as a degraded mode.
- **Identity:** a protocol-level peer identity, exchanged over GATT after connect. The address is only a transient handle.
- **Permissions:** on Android 31+, request SCAN (`neverForLocation`), ADVERTISE and CONNECT. On API 26–30, request FINE location plus legacy BLUETOOTH/ADMIN (COARSE suffices before API 29 per AOSP Javadoc via cairn research [SEC]). On iOS, set `NSBluetoothAlwaysUsageDescription`.
- **APIs to abstract:** API 33 write overloads and API 37 `BluetoothGattConnectionSettings`. Keep the KMP `expect` surface free of per-API branches.

## 7. Items needing device-lab proof

1. Android↔iOS LE CoC, insecure and secure, in both directions: open, throughput, close semantics and reconnect (API 29+ × iOS 15+).
2. Whether Android can see or decode an iOS overflow-area advert. Also whether iOS background↔background discovery latency is acceptable.
3. iOS 26+ Live Activity: does background **advertising** keep the local name and in-advert UUID?
4. Maximum concurrent iOS links as central and as peripheral (iPhone SE 2/3, 12, 15, 17 class).
5. Per-OEM Android TCB limit and 133 behaviour on API 26–34 vs 35+; the single-dial constraint on ≤ 28.
6. Extended-advert reception on iPhone 8/X/XR-class hardware at iOS 15–16; Coded-PHY reception on iOS.
7. MTU and DLE matrix for every OS/role pair, including the iOS peripheral role (`maximumUpdateValueLength`).
8. Connection-interval and supervision behaviour when iOS is backgrounded or locked.
9. Android scan quota, timeout and background downgrade on OEM builds versus AOSP defaults.
10. iOS state-restoration cold-wake latency and the reliability of reconnecting to a mesh.
11. RPA rotation interval on current Android and iOS, and how quickly the mesh recovers identity.
12. Whether a phone-to-phone mesh passes Google Play `connectedDevice` FGS review.

## 8. Limitations

- **Unpublished limits:** Apple publishes no connection-count, background-cadence or extended-advert limits. Every iOS number here is either from ADG (written for accessories) or empirical.
- **AOSP is not OEM:** AOSP `main` constants are defaults. OEM builds and DeviceConfig flags can change them. Fixed values from older releases (7 TCBs, 15 min RPA) come from cairn or a third-party mirror, not from a re-read of tagged AOSP release branches.
- **cairn evidence:** the empirical data comes from a single project's fleet of about 15 Android and 5 iOS devices. Coverage of Android 8–9 and iOS 15–16 is thin.
- **Apple documentation:** read through the doc JSON. The archived Background Processing guide was not updated after iOS 13, and its ~10 s wake budget and cadence statements may be outdated.
- **Not examined:** LE Audio, periodic advertising sync, subrating (privileged-only) and HDT PHY (API 37 probe only; no iOS counterpart). None is needed for v1.
- **Bluetooth Core version:** 6.1 HTML was used. Values cited are unchanged since 5.x for the sections referenced.
