# Discovery and advertising for MeshLink (Android API 26+, iOS 15+)

- **Date:** 2026-11-02
- **Ticket:** `.scratch/meshlink-v1/issues/13-discovery-advertising.md`
- **Builds on:** [`2026-10-31-platform-ble-capabilities.md`](2026-10-31-platform-ble-capabilities.md) (cited below as "capabilities note"). This note does not repeat its matrix. It corrects two of its statements (§3.3, §3.4).
- **Every URL below was accessed on 2026-11-02.**

## Evidence labels

| Label | Meaning |
|---|---|
| **[DOC]** | Stated in a primary vendor or specification source: Android reference, guide or behavior-change pages, Google Play policy pages, Apple developer documentation, Apple Accessory Design Guidelines (ADG), Apple Platform Security, or Bluetooth Core 6.2. |
| **[AOSP]** | Read directly from AOSP `packages/modules/Bluetooth` at tag `android-17.0.0_r1` (commit `c77db469`, 2026-05-15). This is implementation, not API contract. OEM builds, sysprops and flags may differ. |
| **[CALC]** | Arithmetic derived only from [DOC] or [AOSP] numbers. |
| **[EMP:cairn]** | Observed on real devices by the sibling project *cairn* (`../cairn`). Study only. |
| **[SEC]** | Secondary or third-party report. Not verified here. |
| **[UNVERIFIED]** | Not established by any source found. Each one has a "Device test to settle" line. |

## 1. Question

What can MeshLink put on air, and what will peers see, so that discovery, the density estimator (ADR 0008) and identifier rotation (ADR 0001) work on Android API 26+ and iOS 15+? The ticket asks eight questions:

1. iOS advertising content and byte budget.
2. iOS background advertising and the overflow area.
3. iOS 26+ Live Activities, AccessorySetupKit and other newer APIs.
4. BLE address rotation on both platforms, and whether MeshLink can rotate its identifiers in step.
5. Extended advertising and Coded PHY.
6. Usable bytes for presence or summary data in a legacy advertisement.
7. Google Play foreground service (FGS) policy and Android background scanning limits.
8. Scanner behavior relevant to the 10 s density window.

## 2. Sources

### Apple: primary

- Core Bluetooth framework overview (iOS 26 Live Activity text): <https://developer.apple.com/documentation/corebluetooth>
- `CBPeripheralManager.startAdvertising(_:)`: <https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)>
- `CBCentralManager.scanForPeripherals(withServices:options:)`: <https://developer.apple.com/documentation/corebluetooth/cbcentralmanager/scanforperipherals(withservices:options:)>
- `CBCentralManagerScanOptionAllowDuplicatesKey`: <https://developer.apple.com/documentation/corebluetooth/cbcentralmanagerscanoptionallowduplicateskey>
- `CBAdvertisementDataOverflowServiceUUIDsKey`: <https://developer.apple.com/documentation/corebluetooth/cbadvertisementdataoverflowserviceuuidskey>
- `CBAdvertisementDataServiceDataKey` and `CBAdvertisementDataManufacturerDataKey`: <https://developer.apple.com/documentation/corebluetooth/cbadvertisementdataservicedatakey>, <https://developer.apple.com/documentation/corebluetooth/cbadvertisementdatamanufacturerdatakey>
- `CBAdvertisementDataSolicitedServiceUUIDsKey`: <https://developer.apple.com/documentation/corebluetooth/cbadvertisementdatasolicitedserviceuuidskey>
- `CBCentralManager.Feature.extendedScanAndConnect`: <https://developer.apple.com/documentation/corebluetooth/cbcentralmanager/feature/extendedscanandconnect>
- `CBPeer.identifier`: <https://developer.apple.com/documentation/corebluetooth/cbpeer/identifier>
- Core Bluetooth Programming Guide, Background Processing (archived, last updated before iOS 14): <https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html>
- ActivityKit, "Displaying live data with Live Activities": <https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities>
- AccessorySetupKit: <https://developer.apple.com/documentation/accessorysetupkit> and `ASDiscoveryDescriptor`: <https://developer.apple.com/documentation/accessorysetupkit/asdiscoverydescriptor>
- Wi-Fi Aware framework: <https://developer.apple.com/documentation/wifiaware>
- Accessory Design Guidelines R32 Update 1 (footer date 2026-10-30), §19.4.7.2 and §57.2–57.8: <https://developer.apple.com/accessories/Accessory-Design-Guidelines.pdf>
- Apple Platform Security, "Bluetooth security": <https://support.apple.com/guide/security/bluetooth-security-sec82597d97e/web>

Apple text was read from the documentation JSON at `https://developer.apple.com/tutorials/data/documentation/<framework>/<symbol>.json`.

### Android and Google Play: primary

- `AdvertisingSet`: <https://developer.android.com/reference/android/bluetooth/le/AdvertisingSet>
- `AdvertisingSetParameters.Builder`: <https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters.Builder>
- `BluetoothAdapter` (`isLeExtendedAdvertisingSupported`, `getLeMaximumAdvertisingDataLength`, `isLeCodedPhySupported`): <https://developer.android.com/reference/android/bluetooth/BluetoothAdapter>
- `ScanSettings`: <https://developer.android.com/reference/android/bluetooth/le/ScanSettings>
- `ScanFilter.Builder` (`setServiceUuid(uuid, mask)`, `setServiceData`, `setManufacturerData`): <https://developer.android.com/reference/android/bluetooth/le/ScanFilter.Builder>
- `ScanRecord` (`getManufacturerSpecificData`): <https://developer.android.com/reference/android/bluetooth/le/ScanRecord>
- `AdvertiseData.Builder` (`setIncludeDeviceName`, `addServiceData`): <https://developer.android.com/reference/android/bluetooth/le/AdvertiseData.Builder>
- Communicate in the background (BLE): <https://developer.android.com/develop/connectivity/bluetooth/ble/background>
- Companion device pairing: <https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing>
- Foreground service types: <https://developer.android.com/develop/background-work/services/fgs/service-types>
- Behavior changes, Android 15, 16 and 17 (all-apps and targeting pages): <https://developer.android.com/about/versions/15/behavior-changes-all>, <https://developer.android.com/about/versions/15/behavior-changes-15>, <https://developer.android.com/about/versions/16/behavior-changes-all>, <https://developer.android.com/about/versions/16/behavior-changes-16>, <https://developer.android.com/about/versions/17/behavior-changes-all>, <https://developer.android.com/about/versions/17/behavior-changes-17>
- Google Play, "Understanding foreground service and full-screen intent requirements": <https://support.google.com/googleplay/android-developer/answer/13392821>
- Google Play, Device and Network Abuse policy, "Permissions for Foreground Services (FGS)": <https://support.google.com/googleplay/android-developer/answer/9888379>

### AOSP: primary (tag `android-17.0.0_r1`)

Base: <https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/tags/android-17.0.0_r1/>

- Address rotation: `sysprop/ble.sysprop`, `system/main/shim/acl_api.cc` L74–92, `system/gd/hci/le_address_manager.cc` L157–163 and L535–547.
- Advertising: `system/gd/hci/le_advertising_manager_impl.cc` L161–172, L474–497, L574–582, L599–700, L781–850; `framework/java/android/bluetooth/le/AdvertisingSet.java` L210–227; `AdvertisingSetParameters.java` L562–590; `BluetoothLeAdvertiser.java` L64–68, L152–158, L171–176, L704–760.
- Scanning: `framework/java/android/bluetooth/le/ScanSettings.java` L357; `android/app/src/com/android/bluetooth/le_scan/ScanUtil.kt` L50–69, L90–107, L326–344, L501–502; `ScanThrottler.kt`; `ScanManager.java` L374–392, L726–738, L1220–1240; `ScanController.java` L875–881; `AppScanStats.kt` L330–345; `ScanSuspendManager.kt`; `gatt/AdvertiseSuspendManager.kt`; `flags/le_scanning.aconfig` L31–36; `system/gd/hci/le_scanning_manager_impl.cc` L749–758.

The `main` branch of this module was last updated on 2025-03-17, so release tags are the current source. The capabilities note read `main`.

### Bluetooth SIG: primary

Bluetooth Core Specification 6.2 HTML: <https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/index-en.html>. The Core 6.3 path returned 404, so 6.2 is the newest reachable version.

- Vol 3 Part C (GAP) §10.7.1, §10.7.1.1, §10.7.1.2 and Appendix A Table A.1: `host/generic-access-profile.html`
- Vol 4 Part E (HCI) §7.8.45 and §7.8.122: `host-controller-interface/host-controller-interface-functional-specification.html`
- Vol 6 Part B (Link Layer) §6.1: `low-energy-controller/link-layer-specification.html`

### Empirical prior art (study only)

- cairn: ADR 0008 `docs/adr/0008-beacon-discovery-tuning.md`, ADR 0038 `docs/adr/0038-android-extended-advert-2m-secondary.md`, issues `.scratch/cairn-r1/issues/57-legacy-os-device-matrix.md` and `58-crossed-dial-link-loss.md`, research `docs/research/2026-10-05-android-ios-background-ble.md`, `docs/agents/lessons.md`.
- MeshLink-template: `docs/explanation/template-design-review.md`.

### Secondary

- Martin et al., PoPETs 2019, and Celosia and Cunche, PoPETs 2020, on Apple Continuity manufacturer data. Cited through cairn research only, as **[SEC]**.
- WWDC19 session 901 (extended-advert size on iPhone XS and later), cited through cairn, as **[SEC]**.

## 3. Findings

### 3.1 Q1: iOS advertising content

- **Keys an app may set:** "The peripheral manager only supports two keys: `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey`." [DOC startAdvertising]
- **Keys an app may not set:** service data, manufacturer data, solicited UUIDs, TX power and "is connectable" are documented only as keys in *received* advertisement dictionaries. [DOC advertisement data keys]
- **Foreground budget:** "up to 28 bytes of space in the initial advertisement data" and "an additional 10 bytes of space in the scan response, usable only for the local name". Apple adds that these "sizes don't include the 2 bytes of header information required for each new data type". [DOC startAdvertising]
- **Reading of the 28 B figure:** a legacy PDU has 31 B of AdvData. 31 − 3 (Flags AD) = 28. So 28 B must include AD headers; otherwise one 128-bit UUID plus its header would not fit beside the Flags. [CALC]
- **One 128-bit UUID:** it costs 2 + 16 = 18 B, which leaves 10 B in the main packet. A local name AD there could carry at most 8 name bytes. [CALC]
- **Name placement:** Apple says the name gets 10 B in the scan response. Whether iOS puts a short name in the main packet, in the scan response, or both, and whether it uses the Complete (0x09) or Shortened (0x08) AD type, is not documented. [UNVERIFIED]
  - Device test to settle: T1.
- **Overflow in the foreground:** UUIDs "that don't fit in the allotted space go to a special 'overflow' area" and are "discoverable only by an iOS device explicitly scanning for them". [DOC startAdvertising]
- **Two 128-bit UUIDs:** one AD with two 128-bit UUIDs is 2 + 32 = 34 B > 28 B. So a second 128-bit UUID always overflows. [CALC] Which of the two iOS keeps in the main packet is not documented. [UNVERIFIED] Device test to settle: T1.
- **Rotating identifier in a 128-bit UUID:** iOS accepts any `CBUUID`, so an app *can* advertise a 128-bit UUID whose bits rotate. [DOC by API shape]
  - Android can still filter such adverts: `ScanFilter.Builder.setServiceUuid(uuid, mask)` matches under a mask, so a fixed prefix plus rotating bits is filterable. [DOC ScanFilter.Builder, API 21]
  - iOS scanners cannot mask. A background iOS scan must list exact UUIDs (§3.2), so it can never find a rotating UUID. A foreground iOS scan must use `nil` services and filter in the app. [DOC scanForPeripherals]
  - A backgrounded iOS advertiser hashes the rotating UUID into the overflow area. No peer can look for it without knowing it in advance. [DOC + CALC]
  - Conclusion: a rotating 128-bit UUID breaks every iOS background path. It is not viable as the only UUID.
- **Rotating identifier in the local name:** the name is an `NSString`. A rotating ASCII token of ≤ 8 characters (main packet) or ≤ 10 (scan response) is possible in the foreground. In base64url that is 48 or 60 bits. [CALC] It disappears in the background (§3.2). [DOC]
- **Unset name:** whether iOS advertises the device name (for example "Phil's iPhone") when the app omits `LocalNameKey` is not documented. [UNVERIFIED] It matters for privacy. Device test to settle: T1.
- **Implication for Android scanners:** an Android `ScanFilter` on the fixed mesh UUID matches a *foreground* iOS advertiser only if that UUID is in the main packet. So iOS must advertise exactly one 128-bit UUID, the fixed one, or the order must be proven by T1. [CALC]

### 3.2 Q2: iOS background advertising and the overflow area

- **What Apple documents:** "While your app is in the background, the local name isn't advertised and all service UUIDs are in the overflow area." [DOC startAdvertising]
- **Who can see overflow:** "discoverable only by an iOS device explicitly scanning for them". [DOC startAdvertising] `CBAdvertisementDataOverflowServiceUUIDsKey` is "best effort and may not always be accurate". [DOC]
- **Cadence:** "If all apps that are advertising are in the background, the frequency at which your device advertises may decrease." [DOC archived guide]
- **Wire format:** Apple does not document it. Third parties describe Apple manufacturer data (company ID 0x004C) carrying a hash bitmap of service UUIDs. [SEC via cairn research]
- **Can Android see it:** Android's `ScanRecord` exposes manufacturer data as raw bytes, so an Android app *can* receive 0x004C payloads with an unfiltered scan or a manufacturer-ID filter. [DOC ScanRecord] Android has no notion of the overflow area. [EMP:cairn research, AOSP `ScanRecord.java`]
  - Even if decoded, a bitmap bit says "some device advertises a UUID that hashes here". It carries no per-node identifier, and every nearby iPhone emits 0x004C Continuity data. [SEC + CALC]
  - An Android scan filtered on the mesh UUID did not find a backgrounded iPhone in cairn BG6/BG7 (iPhone 12 mini, iOS 27; P10 lite and Pocophone never dialed it, while the iPhone dialed both). [EMP:cairn issue 57]
  - Status: Android discovery of a backgrounded iOS advertiser is **not supported** for design purposes. Raw 0x004C visibility is [UNVERIFIED]. Device test to settle: T2.
- **Must a foreground iOS scanner name the UUID:** yes. Overflow UUIDs are found only by a scan that "explicitly" lists them. A `nil`-services foreground scan is not documented to surface overflow-only advertisers. [DOC] Whether it does in practice is [UNVERIFIED]. Device test to settle: T3.
- **Background iOS ↔ background iOS:** a background scan must list service UUIDs, and the overflow area is designed for exactly that listener. So it is allowed by the documentation. [DOC] Apple also says background scan intervals increase and discoveries coalesce, so latency is undefined. [DOC archived guide] Whether it works at all with both screens locked, and how fast, is [UNVERIFIED]. Device test to settle: T3. Ticket 13 already accepts "both iPhones backgrounded" as a documented degraded mode.
- **State restoration:** a restored peripheral manager resumes its advertising. A restored central resumes UUID-filtered scans. Neither changes the content rules above: the app is still in the background, so it is still overflow-only. [DOC archived guide; capabilities note §4]
- **iOS 17–27 changes:** the `startAdvertising` and `scanForPeripherals` pages carry no version-specific changes to these rules. The only documented change is the iOS 26 Live Activity text (§3.3). iOS 27 added Channel Sounding, which is unrelated. [DOC]

### 3.3 Q3: Live Activities, AccessorySetupKit and newer APIs

| API | What it actually does | Effect on MeshLink background discovery | Evidence |
|---|---|---|---|
| Live Activity + CBManager (iOS 26+) | "If your app has an instantiated CBManager and starts a Live Activity, it can use the same privileges while in the background that it uses when it is in the foreground. This means activities like scanning without providing service UUID's and scanning with duplicates filter disabled will be allowed while in the background." | Background **scanning** gets foreground rules. **Advertising** is not mentioned; whether the name and in-packet UUID survive backgrounding is [UNVERIFIED] (T4). | [DOC] Core Bluetooth overview page |
| Live Activity lifetime | Normally started while the app is in the foreground. It "can be active for up to eight hours", after which the system ends it. It can then stay on the Lock Screen for up to four more hours. | The scan privilege lasts at most about 8 h per user-started session. Whether it persists while an ended activity is still shown is [UNVERIFIED] (T4). | [DOC] ActivityKit |
| AccessorySetupKit (iOS 18+) | A system picker for Bluetooth or Wi-Fi *accessories*. A descriptor matches by company ID or service UUID, plus a name substring or a masked manufacturer-data or service-data blob. The user picks each device, and the app then uses Core Bluetooth for it. | No background advertising or scanning privilege is documented. Per-device user consent does not fit transient mesh peers. Not useful for v1. | [DOC] |
| Wi-Fi Aware (iOS 26+) | Wi-Fi Aware links to devices paired through AccessorySetupKit or DeviceDiscoveryUI; iPhone 12 or later. | Not BLE. Pairing is per device. Out of scope for v1. | [DOC] |

- **Correction to the capabilities note:** its §3.2 places the Live Activity text in the `scanForPeripherals` discussion. It is on the Core Bluetooth framework overview page. [DOC]
- No other iOS API found gives a third-party app background advertising beyond the overflow area. [DOC by absence]

### 3.4 Q4: BLE address rotation

#### Bluetooth Core 6.2 [DOC]

- `TGAP(private_addr_int)` is 15 min, "Maximum time interval between private address change", a recommended value (Vol 3 Part C, Appendix A, Table A.1).
- Host-based privacy: the host runs a timer equal to `TGAP(private_addr_int)` and generates a new address when it expires (Vol 3 Part C §10.7.1.2).
- "The device should not send the device name or unique data in the advertising data that can be used to recognize the device." (Vol 3 Part C §10.7.1)
- "If the advertising data or the scan response data change regularly then those changes should be synchronized with any changes in private addresses" (Vol 3 Part C §10.7.1.1).
- Link Layer: "It is recommended to set the timer to 15 minutes. If requested by the Host, the Controller shall generate a new resolvable private address each time the advertising data or scan response data changes." (Vol 6 Part B §6.1)
- `HCI_LE_Set_Resolvable_Private_Address_Timeout` v1 defaults to 900 s (range 1 s–1 h). v2 takes a min and max, defaulting to 480 s and 900 s, and the controller picks a random value in between (Vol 4 Part E §7.8.45).
- `HCI_LE_Set_Data_Related_Address_Changes` (Core 5.3+) lets the host ask for a new address whenever advertising data (bit 0) or scan response data (bit 1) changes (Vol 4 Part E §7.8.122).

#### Android [AOSP unless marked]

- **Interval:** `bluetooth.ble.random_address_rotation_interval_min` defaults to 7 min and `_max` to 15 min (`ble.sysprop`, `acl_api.cc`).
- **Timer:** a non-wake alarm fires at a random time in [min, min + 2 min] and a wake alarm in [max − 2 min, max] (`le_address_manager.cc` L535–547). So an awake device rotates about 7–9 min after the last change. A sleeping device rotates by 13–15 min at the latest. [AOSP + CALC]
- **Controller offload:** with `bluetooth.core.le.rpa_offload`, the stack sends `LE_Set_RPA_Timeout_V2(min, max)` and the controller rotates (`le_address_manager.cc` L157–163).
- **Advertising sets get their own address:** on a controller with extended advertising, each new advertising set gets a fresh RPA (`new_advertiser_address`, L474–497) and its own 7–15 min rotation timer (L599–700). The timer is cancelled while the set is disabled.
- **Legacy API too:** `BluetoothLeAdvertiser.startAdvertising` is routed through the same set-creation path (L574–582). So on an extended-capable controller each start also gets a fresh RPA.
- **Legacy-only controllers:** with no extended advertising, the advertiser reuses the shared initiator address ("no way to use a different address"). Restarting does not change it.
- **Vendor multi-advertising (`ANDROID_HCI`) controllers:** chosen when extended advertising is absent but `LE_MULTI_ADVT` exists (L161–172). Per-set addressing on this path was not traced. [UNVERIFIED] Device test to settle: T5.
- **Changing data on a live set:** no use of `Data_Related_Address_Changes` exists in the module, so `AdvertisingSet.setAdvertisingData` keeps the address. [AOSP by absence]
- **Can the app learn its address:** no. `AdvertisingSet.getOwnAddress` is `@Hide`, needs `BLUETOOTH_PRIVILEGED`, and its Javadoc says it is "exposed only for Bluetooth PTS tests, no app or system service should ever use it" (`AdvertisingSet.java` L210–227). It is absent from the public reference page. [AOSP + DOC]
- **Can the app choose the address type:** `AdvertisingSetParameters.Builder.setOwnAddressType` is `@SystemApi`. Public apps get `ADDRESS_TYPE_DEFAULT`. [AOSP + DOC]
- **Android 15–17 changes:** the behavior-change pages list no advertising or address change. Bluetooth items are bond-loss handling (16), autonomous re-pairing (17) and RFCOMM `read()` returning −1 (17 target). [DOC]
- **Correction to the capabilities note:** its §5 item 7 lists the current rotation as [UNVERIFIED]. On `android-17.0.0_r1` it is 7–15 min as above. [AOSP]

#### iOS

- Apple devices "use a Random Device Address" (ADG §57.8, written for accessories). [DOC]
- Apple Platform Security says address randomization changes the address "on a frequent basis" and gives no interval. [DOC]
- No API returns or rotates the local address. `CBPeer.identifier` is an opaque per-peer UUID. [DOC by absence]
- Whether `stopAdvertising` then `startAdvertising` yields a new address is [UNVERIFIED]. Device test to settle: T6.
- iPhone RPA rotation during a live link made Android dial the new address while the old link was up. [EMP:cairn issue 58]

#### Conclusion

- **Android, extended-capable controller:** MeshLink can keep its identifier in step with the address by **creating a new advertising set (or restarting legacy advertising) at every identifier rotation**. Each new set gets a fresh RPA. [AOSP]
  - The stack's own per-set timer fires 7–9 min after creation while awake. If MeshLink's epoch is longer than about 7 min, the stack rotates the address mid-epoch and the unchanged identifier links the old and new addresses. [AOSP + CALC]
  - To stay in step, MeshLink's advertisement-identifier epoch must be **shorter than 7 min** (for example 6 min ± jitter), with a restart at each boundary. ADR 0008's 15 min period is too long for this. [CALC]
  - The 7 min floor is a sysprop default. OEMs may change it. [UNVERIFIED] Device test to settle: T5.
- **Android, legacy-only controller:** the address is shared and rotates on the stack timer. MeshLink cannot align with it. [AOSP]
- **iOS:** MeshLink can neither observe nor trigger rotation. A rotating identifier in the local name will straddle address changes. [DOC by absence] The safe choice is to put **no per-node identifier** in the iOS advert (§4).
- cairn's design restarts the set at each 5 min beacon window with jitter "where the stack allows". This matches the AOSP finding. [EMP:cairn ADR 0008, ADR 0038]

### 3.5 Q5: Extended advertising and Coded PHY

- **iOS as advertiser:** only the two keys and the 28 B + 10 B budget exist. There is no API for extended advertising or PHY choice. [DOC by absence]
- **iOS as scanner:** `CBCentralManager.supports(.extendedScanAndConnect)` (iOS 13+) reports whether "hardware supports extended scans and enhanced connection creation". Apple does not list models. [DOC]
- **Measured:** iOS reports Android extended adverts only on a 2M secondary PHY: iPhone 12 mini iOS 27, iPhone 15 iOS 17.6.1, iPhone SE 2020 iOS 18.7.8. iPhone X iOS 16.7.16 saw none. iPhone SE 2016 iOS 15.8.8 scans 1M only. [EMP:cairn ADR 0038, issue 57]
- **Size:** 124 B on iPhone XS and later. [SEC WWDC19 via cairn]
- **ADG:** the only Apple requirement for extended advertising is for spatial accessories (§19.4.7.2). It is not a statement about iPhone scanning in general. [DOC]
- **Coded PHY on iOS:** no API, no documentation, no measurement. [UNVERIFIED] Device test to settle: T7.
- **Android:** `isLeExtendedAdvertisingSupported()` returns "true if chipset supports LE Extended Advertising feature". `getLeMaximumAdvertisingDataLength()` returns the maximum "if LE Extended Advertising feature is supported, 0 otherwise". `isLeCodedPhySupported()` gates `setPrimaryPhy(PHY_LE_CODED)`, which is "used only if legacy mode is not used". [DOC]
- **Android scanner:** `ScanSettings.Builder.setLegacy(false)` is needed to receive extended adverts (capabilities note §3.1). [DOC]
- **Conclusion:** the legacy 1M advert stays mandatory (ADR 0009, library rule R19). An extended set adds nothing iOS can rely on.

### 3.6 Q6: Usable presence bytes in legacy advertising

Android framework accounting (`BluetoothLeAdvertiser.java` L64–68, L704–760) [AOSP]:

- 31 B per packet. Each AD costs 2 B overhead.
- Flags cost 3 B and are added only when the advert is connectable *and* discoverable (L152–158).
- Service data costs 2 + UUID length (2, 4 or 16) + payload.
- Manufacturer data costs 2 + 2 (company ID) + payload.

| Placement | Android payload | iOS payload | Notes |
|---|---|---|---|
| ADV: Flags + 128-bit UUID list | 0 B (10 B left) | 0 B (10 B left) | Base for both. [CALC] |
| ADV: remaining 10 B as 16-bit-UUID service data | 6 B | not settable | Needs a SIG-assigned 16-bit UUID. MeshLink has none. [CALC; UNVERIFIED ownership] |
| ADV: remaining 10 B as manufacturer data | 6 B | not settable | Needs a SIG company ID. MeshLink has none. [CALC] |
| Scan response: service data under the 128-bit mesh UUID | 31 − 2 − 16 = **13 B** | not settable | Compliant with no SIG assignment. Needs an active scan. [CALC] |
| Scan response: 32-bit-UUID service data | 31 − 2 − 4 = 25 B | not settable | 32-bit UUIDs are SIG-assigned too. [CALC] |
| ADV: local name in remaining 10 B | 8 B text | ≤ 8 B text, foreground only | Android name comes from the adapter name, not the app (`setIncludeDeviceName`). [DOC; iOS placement UNVERIFIED] |
| Scan response: local name | — | ≤ 10 B text, foreground only | [DOC] |

- **Android payload that fits in 13 B:** an 8 B advertisement identifier, a 4 B rotating mesh tag and a 1 B flags field (density class, "accepting links", version). [CALC]
- **iOS payload:** 0 B of binary data. At most an 8–10 character rotating text token in the foreground, and nothing in the background. [DOC + CALC]
- **Active scanning:** Android scans are active unless `setScanType(SCAN_TYPE_PASSIVE)` (API 36.1) is used; the builder default is `SCAN_TYPE_ACTIVE` [DOC ScanSettings; AOSP `ScanSettings.java` L357]. Whether iOS scans actively in the background, so that it sees Android scan responses, is [UNVERIFIED]. Device test to settle: T8.

### 3.7 Q7: Google Play FGS policy and Android background limits

- **FGS type text:** `connectedDevice` covers "Interactions with external devices that require a Bluetooth, NFC, IR, USB, or network connection." The prerequisite can be a granted `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` or `BLUETOOTH_SCAN`. The manifest needs `FOREGROUND_SERVICE_CONNECTED_DEVICE`. [DOC FGS types]
- **Google's suggested alternatives on the same page:** "If your app needs to do continuous data transfer to an external device, consider using the companion device manager instead. Use the companion device presence API to help your app stay running while the companion device is in range. If your app needs to scan for bluetooth devices, consider using the Bluetooth scan API instead." [DOC FGS types]
- **Play Console declaration:** each FGS type needs a description, the user impact if deferred or interrupted, a video, and a use case. The listed `TYPE_CONNECTED_DEVICE` use case is "Continuous Data Transfer to an External Device … For example, wearable, baby monitor, headset, car." The video must "Show the user pairing and connecting to an external device directly inside the app … not system settings." The list is "non-exhaustive", and an unlisted use case "can" be entered manually. [DOC Play 13392821]
- **Policy criteria:** the FGS must be "beneficial to the user and relevant to the core functionality", "initiated by the user or … user perceptible", stoppable by the user, not deferrable "without causing a negative user experience", and run "only for as long as necessary". [DOC Play 9888379]
- **Does a phone-to-phone mesh qualify:** Play does not mention phones as the "external device". A mesh peer is another phone, and the Play examples are accessories. Approval therefore depends on review. **[UNVERIFIED] policy risk.** The case is strongest when the user starts the mesh session in the UI and a visible notification lets them stop it. Settle by: submitting an internal-testing build with a declaration and video (T12; this is a Play review, not a radio test).
- **CompanionDeviceManager:** CDM pairing "doesn't create connections on its own nor enable continuous scanning". After association, `REQUEST_COMPANION_RUN_IN_BACKGROUND` and `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` are available. With `startObservingDevicePresence(ObservingDevicePresenceRequest)` (API 36), `CompanionDeviceService` is bound "when the companion device is within BLE range". The background guide notes CDM has "no support for random MAC addresses". [DOC] Each association needs a user picker. So CDM fits a few fixed buddies, not a mesh of RPA-rotating strangers. [CALC]
- **PendingIntent scan:** "Call `startScan()` with a `PendingIntent` object … to get notified when a device matching your filter is scanned." It needs a filter and wakes the process. From Android 12, FGS cannot start from that wake, so "it can only support short duration communication". [DOC background guide]
- **Start quota:** `DEFAULT_SCAN_QUOTA_COUNT = 5` per `DEFAULT_SCAN_QUOTA_WINDOW = 30 s`. The sixth start returns `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` unless the app is privileged. [AOSP `ScanUtil.kt` L50–51, `ScanController.java` L875–881, `AppScanStats.kt` L330–337]
- **Long scans:** after `DEFAULT_SCAN_TIMEOUT = 10 min`, an unfiltered scan becomes opportunistic and a filtered one is downgraded. Opportunistic and `CALLBACK_TYPE_FIRST_MATCH` scans are exempt (`ScanUtil.kt` L343–344). This timeout is skipped when the new allowance throttle is on. [AOSP `ScanManager.java` L1220–1240]
- **Screen off:** a scan with no non-empty filter "Cannot start unfiltered scan in screen-off" and is suspended until the screen turns on (`ScanManager.java` L374–382; `requiresScreenOn` in `ScanUtil.kt` L326–327). [AOSP]
- **Background importance:** an app below `IMPORTANCE_FOREGROUND_SERVICE` is capped at LOW_POWER with the screen on and SCREEN_OFF with it off. A FGS counts as foreground. In the foreground with the screen off, LOW_POWER → SCREEN_OFF and BALANCED → SCREEN_OFF_BALANCED, while LOW_LATENCY is kept (`ScanThrottler.throttleScanMode`). [AOSP]
- **Scan duty cycles:** LOW_POWER 140 ms / 1400 ms; BALANCED 183 / 730; LOW_LATENCY 100 / 100; SCREEN_OFF 512 / 10 240; SCREEN_OFF_BALANCED 183 / 730 (`ScanUtil.kt` L59–69). [AOSP]
- **New in Android 17: scan allowance.** Behind the flag `scan_allowance_throttling_enabled` (`le_scanning.aconfig` L31–36), an app that is not foreground-UI gets a 6 min per hour LOW_LATENCY-equivalent allowance (`bluetooth.ble.scan.scan_allowance_seconds.config`). Other modes spend it more slowly by weight: BALANCED 25, LOW_POWER 10, SCREEN_OFF 5 against LOW_LATENCY 100. When it runs out, the scan drops to SCREEN_OFF until the hourly refill. **This applies even to an app with a FGS**, because only `IMPORTANCE_FOREGROUND` (visible UI) or a privileged app is exempt (`ScanThrottler.applyAllowanceThrottling`, `ScanManager.isAppForegroundUi` L733–738). [AOSP]
  - Worked example: a FGS app scanning BALANCED with the screen on would use 6 min × 100 / 25 = 24 min per hour at BALANCED, then 36 min at SCREEN_OFF (512 / 10 240 ms). [CALC]
  - Whether the flag is enabled on shipping Android 17 builds is [UNVERIFIED]. Device test to settle: T9.
- **System suspend:** `ScanSuspendManager` stops all scans and `AdvertiseSuspendManager` pauses advertisers when the adapter enters system suspend. This appears aimed at devices with a system suspend state; phone behavior is [UNVERIFIED]. Device test to settle: T9.

### 3.8 Q8: Scanner behavior and the 10 s density window

- **iOS foreground:** `AllowDuplicatesKey = true` "generates a discovery event each time it receives an advertising packet". The default (false) coalesces discoveries. [DOC]
- **iOS background:** "The CBCentralManager scan option has no effect while scanning in the background" (Live Activity excepted, §3.3). "Multiple discoveries of an advertising peripheral are coalesced into a single discovery event." Scan intervals increase when all scanning apps are in the background. [DOC scanForPeripherals; archived guide]
  - Consequence: a background iOS scanner cannot count distinct advertisers per 10 s. It sees each peer once and does not re-report it. [DOC + CALC] Restarting the scan to clear coalescing is not documented to re-report peers. [UNVERIFIED] Device test to settle: T11.
- **Android duplicate handling:** AOSP enables the controller scan with `FilterDuplicates::DISABLED` (non-Floss builds), so every received packet reaches the host (`le_scanning_manager_impl.cc` L749–758). [AOSP] `CALLBACK_TYPE_ALL_MATCHES` will "Trigger a callback for every Bluetooth advertisement found that matches the filter criteria". `CALLBACK_TYPE_FIRST_MATCH` fires only once. [DOC ScanSettings]
- **Android advertiser cadence:** `ADVERTISE_MODE_LOW_POWER` = 1 s, `BALANCED` = 250 ms, `LOW_LATENCY` = 100 ms (`BluetoothLeAdvertiser.java` L171–176). `AdvertisingSetParameters` `INTERVAL_HIGH` = 1600, `INTERVAL_MEDIUM` = 400, `INTERVAL_LOW` = 160 (× 0.625 ms). [AOSP + DOC]
- **iOS advertiser cadence:** not documented for apps. ADG §57.5 gives accessory guidance only. [UNVERIFIED] Device test to settle: T10.
- **At least once per 10 s?** [CALC, idealized; real overlap is not random]

| Scanner state | Duty | Advertiser at 250 ms (40 events / 10 s) | Advertiser at 1 s (10 events / 10 s) |
|---|---|---|---|
| Android LOW_LATENCY | 100 % | ~certain | ~certain |
| Android BALANCED | 25 % | miss ≈ 0.75^40 ≈ 1e−5 | miss ≈ 0.75^10 ≈ 6 % |
| Android LOW_POWER | 10 % | miss ≈ 0.9^40 ≈ 1.5 % | miss ≈ 0.9^10 ≈ 35 % |
| Android SCREEN_OFF | 5 % (512 ms per 10.24 s) | about one window per 10 s; miss is high and correlated | most windows miss |
| iOS foreground, duplicates on | unknown | likely | unknown |
| iOS background | unknown, coalesced | one report per session | one report per session |

- **Conclusion for ADR 0008:** the 10 s distinct-identifier count is sound only for Android scanners at BALANCED or better, and for foreground iOS scanners with duplicates allowed. Android LOW_POWER and SCREEN_OFF need a longer window (30–60 s) or a decay estimator. Background iOS needs a session-based count with a timeout. [CALC] Real hit rates are [UNVERIFIED]. Device test to settle: T10.
- **Identifier choice for counting:** an iOS advert carries no binary identifier, so the counting key must be the BLE address (Android `ScanResult.getDevice().getAddress()`) or `CBPeer.identifier` (iOS). Addresses rotate every 7–15 min (Android) or "frequently" (iOS), which is far longer than 10 s, so double counting per window is rare. [CALC]

## 4. Implications for MeshLink's advertisement design

All layouts keep a legacy, connectable, 1M advert with one fixed 128-bit MeshLink service UUID in the main packet (ADR 0009, R19). Peer identity, mesh membership and capabilities are authenticated over GATT after connect (R9, capabilities note §6).

### Layout A (recommended): fixed UUID + Android scan-response hint, iOS UUID only

| Packet | Android | iOS foreground | iOS background |
|---|---|---|---|
| ADV (31 B) | Flags 3 + UUID list 18 = 21 B; 10 B spare | Flags + UUID list (Apple's 28 B budget) | overflow bitmap only |
| Scan response (31 B) | Service data under the mesh UUID: 2 + 16 + **13 B** | nothing (no `LocalNameKey`) | nothing |
| 13 B payload | 8 B advertisement ID, 4 B mesh tag, 1 B flags | — | — |

- **Rotation:** Android recreates its advertising set at each epoch boundary with jitter, with the epoch under 7 min (§3.4). The advertisement ID and mesh tag derive from the epoch, so the on-air bytes and the RPA change together on extended-capable controllers. On legacy-only controllers, ADR 0001's "in step" cannot hold; MeshLink should log this and treat it as a known gap.
- **iOS:** advertises no rotating data, so there is nothing to fall out of step with the address. This meets ADR 0001 by having no per-node identifier on air.
- **Density counting:** use the advertisement ID for Android advertisers and the BLE address or `CBPeer.identifier` for iOS advertisers.
- **Pros:** compliant without SIG assignments. Fits the 31 B target. Android and foreground iOS filter on one fixed UUID. Lets Android pick peers (density class, accepting links) without connecting.
- **Cons:** needs active scanning to read the hint (T8). iOS peers are opaque until connected. The epoch is shorter than ADR 0008's 15 min.

### Layout B: Layout A plus a rotating name token on foreground iOS

- iOS foreground adds `LocalNameKey` = 8 base64url characters (48-bit token) in the main packet, or 10 (60 bits) in the scan response.
- **Pros:** iOS advertisers become distinguishable and can signal one or two flag bits.
- **Cons:** iOS address rotation cannot be observed, so the token will straddle address changes, which violates ADR 0001 unless T6 shows that restarting advertising also rotates the address. Placement and AD type are [UNVERIFIED] (T1). The token vanishes in the background.
- **Use only if** T1 and T6 pass.

### Layout C: rotating 128-bit UUID (not recommended)

- One 128-bit UUID = 6 B fixed prefix + 10 B rotating payload. Android filters with a UUID mask. iOS has 0 B for other data.
- **Pros:** gives iOS a 10 B binary payload in the foreground.
- **Cons:** a background iOS scanner cannot list an unknown UUID, so iOS background discovery of every peer fails. A backgrounded iOS advertiser becomes unfindable. Adding the fixed UUID as a second entry overflows on iOS (34 B > 28 B) and needs the scan response on Android. Rejected.

### Layout D: fixed UUID only (fallback)

- Both platforms advertise only the fixed UUID. Everything else comes from GATT.
- **Pros:** simplest, most private, no rotation coupling.
- **Cons:** every candidate must be connected to learn anything, which spends the scarce 5–6 Android link slots and the 3 iPhone inbound slots (ADR 0009). Density can still be counted by address.
- Use this if T8 shows iOS does not read Android scan responses and Android-only hints are not worth the code.

### Other consequences

- **ADR 0008 follow-ups:** (1) shorten the advertisement-identifier epoch below the Android 7 min floor, or accept mid-epoch address changes; (2) define per-state density windows (§3.8); (3) check that the 2 / 4 / 6 s scan cadence is a duty-cycle setting inside one long scan, because stop/start cycles every 2 s would exceed the 5-starts-per-30 s quota (R11).
- **Android 17 allowance:** a FGS alone no longer guarantees BALANCED scanning if the allowance flag is enabled. Plan for SCREEN_OFF cadence after about 24 min per hour without visible UI.
- **Play:** treat `connectedDevice` approval as a release risk, not a given. Prepare an in-app, user-started "join mesh" flow and a stop control for the review video.

## 5. Device tests needed

1. **T1 iOS advert layout.** Advertise from an iPhone in the foreground with (a) the fixed UUID only, (b) UUID + 8-char name, (c) UUID + 10-char name, (d) two 128-bit UUIDs, (e) no name key. Capture with an Android `ScanRecord.getBytes()` dump and, if available, a sniffer. **Settles:** name placement and AD type, which UUID stays in the packet, whether the device name leaks. **Pass:** raw bytes recorded for each case on iOS 15/16 and 26/27; (a) and (e) contain no device name.
2. **T2 Android view of a backgrounded iPhone.** Background an advertising iPhone (screen locked and unlocked). Run an unfiltered Android scan and a 0x004C manufacturer-ID scan. **Settles:** whether overflow bytes are visible to Android and whether they change with the UUID. **Pass:** a logged 0x004C payload that changes when the app's UUID changes, or a documented absence.
3. **T3 iOS overflow discovery.** iPhone A advertises in the background. iPhone B scans (i) in the foreground for the UUID, (ii) in the foreground with `nil`, (iii) in the background for the UUID, screens on and off. **Settles:** overflow visibility rules and background↔background latency. **Pass:** discovery time distribution over 20 trials per case.
4. **T4 Live Activity (iOS 26+).** Start a Live Activity, background the app, and check (i) `nil`-service scans with duplicates, (ii) whether the advert keeps its name and in-packet UUID (verify from Android), (iii) behavior after 8 h end and while the ended activity remains. **Pass:** each item observed and timed.
5. **T5 Android address versus advertising set.** On extended-capable, multi-adv and legacy-only phones, log the advertiser address seen by a second phone while (i) idle for 20 min, (ii) recreating the set every 3 min, (iii) calling `setAdvertisingData` every 3 min. **Settles:** the 7–15 min interval on OEM builds, fresh RPA per set, no change on data update. **Pass:** the address changes at every recreation and never between recreations if the epoch is under 7 min.
6. **T6 iOS restart and address.** Stop and restart advertising every 2 min for 30 min and log the address from Android. **Settles:** whether Layout B can be in step. **Pass:** the address changes at every restart (then Layout B is allowed) or not.
7. **T7 Coded PHY on iOS.** An Android advertiser on Coded primary PHY with a legacy 1M twin. Check whether an iPhone 15/17 class device reports the Coded set. **Pass:** reported or not, with RSSI at range.
8. **T8 scan response reception.** Android advertises Layout A. Check whether foreground and background iOS scanners and Android BALANCED/LOW_POWER scanners return the 13 B service data. **Pass:** the hint is present in at least 95 % of first sightings per state.
9. **T9 Android 17 throttling.** On Pixel and one OEM on Android 17, run a FGS scan at BALANCED with the screen on and off for 2 h. Log effective scan mode via `dumpsys bluetooth_manager`. **Settles:** whether the allowance flag is live, and suspend behavior. **Pass:** mode timeline recorded; downgrade time matches 24 min per hour or not.
10. **T10 density window hit rate.** 10 advertisers (mixed Android and foreground iOS) and scanners in each state of §3.8. Count distinct keys per 10 s window over 30 min. **Settles:** the window length per state and the iOS advert interval. **Pass:** per-state recall at 10, 30 and 60 s windows; ADR 0008 window chosen so recall ≥ 90 %.
11. **T11 iOS background re-report.** Background iOS scanner; restart the scan every 10 s; check whether already-seen peers are reported again. **Pass:** re-report rate measured.
12. **T12 Play review.** Submit an internal-testing build declaring `connectedDevice` with a phone-to-phone mesh video. **Pass:** approved, or the rejection text recorded.

## 6. Open questions

1. Does MeshLink want to buy a SIG 16-bit member UUID? It would move 6 B of hint into the main packet and remove the active-scan dependency.
2. What should the mesh tag be: a per-epoch MAC of a mesh key (rotating) or absent? A fixed per-mesh UUID would reveal mesh membership to any listener.
3. Can ADR 0008 accept an advertisement epoch under 7 min? The routing tag and advertisement identifier periods may need to be decoupled.
4. Will the Android 17 scan allowance ship enabled, and will OEMs tune `scan_allowance_seconds`?
5. Does iOS rotate the RPA when advertising restarts (T6)? Without that, Layout B stays out.
6. Is iOS 26 Live Activity background advertising different from plain background advertising (T4)?
7. How should the density estimator weight iOS background scanners that can only report once per session?
