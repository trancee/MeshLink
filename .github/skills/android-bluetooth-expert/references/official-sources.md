# Official sources for `android-bluetooth-expert`

Use this file as a quick map to the official documentation that should anchor answers.

## Bluetooth overview

- `https://developer.android.com/develop/connectivity/bluetooth`
  - Use for the high-level overview of Bluetooth Classic on Android, key classes (BluetoothAdapter, BluetoothDevice, BluetoothSocket, BluetoothServerSocket), and the overall connection flow.

## Bluetooth Classic

- `https://developer.android.com/develop/connectivity/bluetooth/setup`
  - Use for getting the BluetoothAdapter, checking availability, and enabling Bluetooth via ACTION_REQUEST_ENABLE.

- `https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices`
  - Use for querying bonded/paired devices with `getBondedDevices()`, starting active discovery with `startDiscovery()`, BroadcastReceiver for ACTION_FOUND, and enabling discoverability with ACTION_REQUEST_DISCOVERABLE.

- `https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices`
  - Use for RFCOMM socket connections — server side (`listenUsingRfcommWithServiceRecord`, `accept()`), client side (`createRfcommSocketToServiceRecord`, `connect()`), UUID matching, and threading requirements.

- `https://developer.android.com/develop/connectivity/bluetooth/transfer-data`
  - Use for InputStream/OutputStream data transfer over BluetoothSocket, threading patterns for read/write, and the Handler-based communication pattern with the UI.

## Bluetooth permissions

- `https://developer.android.com/develop/connectivity/bluetooth/bt-permissions`
  - Use for the complete permissions reference across API levels: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE (API 31+), legacy BLUETOOTH/BLUETOOTH_ADMIN (API ≤ 30), neverForLocation flag, ACCESS_FINE_LOCATION requirements, and uses-feature declarations.
  - High-value fact: On Android 12+, BLUETOOTH_SCAN/BLUETOOTH_CONNECT/BLUETOOTH_ADVERTISE are runtime permissions under the "Nearby devices" group.
  - High-value fact: Add `android:maxSdkVersion="30"` to legacy BLUETOOTH and BLUETOOTH_ADMIN permissions.
  - High-value fact: Use `android:usesPermissionFlags="neverForLocation"` on BLUETOOTH_SCAN if you don't derive physical location from scan results.

## Bluetooth profiles

- `https://developer.android.com/develop/connectivity/bluetooth/profiles`
  - Use for working with BluetoothProfile proxy objects (Headset/HFP, A2DP, Health Device Profile), the ServiceListener pattern, getProfileProxy/closeProfileProxy, and vendor-specific AT commands.

## Companion Device Manager (CDM)

- `https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing`
  - Use for CompanionDeviceManager pairing flow on API 26+, AssociationRequest with BluetoothDeviceFilter/BluetoothLeDeviceFilter/WifiDeviceFilter, the Callback with onAssociationPending/onAssociationCreated (API 33+), the older onDeviceFound pattern (API ≤ 32), and companion background permissions (REQUEST_COMPANION_RUN_IN_BACKGROUND, REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND).
  - High-value fact: CDM pairing does not require ACCESS_FINE_LOCATION.
  - High-value fact: Location services must still be enabled on the device for CDM to work.

## BLE (Bluetooth Low Energy)

- `https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview`
  - Use for BLE overview, key roles (central/peripheral, GATT client/server), and when to use BLE vs Classic.

- `https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices`
  - Use for BluetoothLeScanner, ScanCallback (onScanResult, onBatchScanResults, onScanFailed), ScanFilter, ScanSettings, and scan timeout best practices.

- `https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server`
  - Use for connectGatt(), BluetoothGattCallback (onConnectionStateChange, onServicesDiscovered), bound Service architecture for BLE, and the BluetoothAdapter initialization pattern within a Service.

- `https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data`
  - Use for GATT service discovery (discoverServices, getServices), reading/writing characteristics, enabling notifications via descriptors (CLIENT_CHARACTERISTIC_CONFIG), and the broadcast-based communication pattern between Service and Activity.

## BLE Audio

- `https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview`
  - Use for LE Audio support overview, LC3 codec, Auracast broadcast audio, and API availability.

- `https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager`
  - Use for AudioManager self-managed calls with BLE Audio devices.

- `https://developer.android.com/develop/connectivity/bluetooth/ble-audio/telecom-api-managed-calls`
  - Use for managing calls through the Telecom API with BLE Audio routing.

- `https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording`
  - Use for audio recording setup with BLE Audio input devices.

## Sample code

- `https://github.com/android/connectivity-samples/tree/master/BluetoothChat`
  - Bluetooth Classic chat sample demonstrating discovery, connection, and data transfer.

- `https://github.com/android/connectivity-samples`
  - The broader connectivity samples repo with BLE and other Bluetooth examples.

## Debugging tools

- `https://developer.android.com/develop/connectivity/bluetooth/ble/test-debug`
  - Use for HCI snoop log instructions, dumpsys debugging, and Bluetooth testing guidance.

- HCI snoop log: Settings → Developer options → Enable Bluetooth HCI snoop log → toggle BT → reproduce issue → `adb pull /data/misc/bluetooth/logs/btsnoop_hci.log` → open in Wireshark.
- `adb shell dumpsys bluetooth_manager` — Check GATT connection state, bonded devices, and profile status.
- `adb logcat -s BluetoothGatt:V BluetoothAdapter:V bt_btif:V` — Filter logcat for Bluetooth events.

## BLE scan throttling

- Starting in Android 7.0 (API 24), the system limits BLE scans to 5 starts per 30-second window per app. Exceeding this silently stops scans without error.
- Scans with `ScanFilter` objects are exempt from this throttle.
- Source: https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices (noted in implementation guidance)

## How to apply these sources

- Start with the permissions page to get the manifest right — this is the most common source of errors.
- Use the Classic or BLE pages depending on the Bluetooth variant in question.
- For CDM, always check the API level — the callback API changed between API 32 and 33.
- When a user asks an architecture question, point them toward the bound Service pattern for BLE and the ConnectedThread pattern for Classic data transfer.
- If the answer depends on a target API level, quote the level precisely and separate API 31+ behavior from legacy behavior.
