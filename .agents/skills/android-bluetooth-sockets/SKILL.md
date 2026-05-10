---
name: android-bluetooth-sockets
description: Android Bluetooth socket communication reference covering Classic RFCOMM and BLE L2CAP CoC. Server side (BluetoothServerSocket via listen methods — secure/insecure RFCOMM with SDP, secure/insecure L2CAP, unified API 36+ BluetoothSocketSettings). Client side (BluetoothSocket via create methods — RFCOMM by UUID, L2CAP by PSM). Socket lifecycle (blocking accept/connect, getInputStream/getOutputStream, close). Socket types (RFCOMM, L2CAP, LE L2CAP with credit-based flow, SCO). PSM management, SDP auto-registration, permissions (BLUETOOTH_CONNECT API 31+), threading rules, and critical practices (cancelDiscovery before connect, proper close order). Use when implementing RFCOMM server/client, L2CAP CoC channels, choosing secure vs insecure sockets, or any Android BluetoothSocket question.
---

<essential_principles>

**Android Bluetooth Sockets** — TCP-like streaming communication over Bluetooth. Server listens with `BluetoothServerSocket`, client connects with `BluetoothSocket`. Two transports: RFCOMM (Classic BR/EDR) and L2CAP CoC (BLE).

### Socket Types

| Type | Transport | Description | Since |
|------|-----------|-------------|-------|
| `TYPE_RFCOMM` | BR/EDR | Serial Port Profile — streaming, connection-oriented | API 5 |
| `TYPE_L2CAP` | BR/EDR | L2CAP channel (classic) | API 23 |
| `TYPE_LE` | BLE | L2CAP CoC — credit-based flow control, streaming over LE | API 36 |
| `TYPE_SCO` | BR/EDR | Synchronous Connection-Oriented — audio | API 23 |

### Server Side — BluetoothAdapter.listen*

| Method | Transport | Security | SDP | Since |
|--------|-----------|----------|-----|-------|
| `listenUsingRfcommWithServiceRecord(name, uuid)` | RFCOMM | Authenticated + encrypted | Yes — registers UUID+name+channel | API 5 |
| `listenUsingInsecureRfcommWithServiceRecord(name, uuid)` | RFCOMM | Encrypted (BT 2.1+), none (legacy) | Yes | API 10 |
| `listenUsingL2capChannel()` | LE CoC | Authenticated + encrypted | No — use `getPsm()` | API 29 |
| `listenUsingInsecureL2capChannel()` | LE CoC | None | No — use `getPsm()` | API 29 |
| `listenUsingSocketSettings(settings)` | RFCOMM or LE | Configurable | Depends on type | API 36 |

### Client Side — BluetoothDevice.create*

| Method | Transport | Security | Since |
|--------|-----------|----------|-------|
| `createRfcommSocketToServiceRecord(uuid)` | RFCOMM | Authenticated + encrypted | API 5 |
| `createInsecureRfcommSocketToServiceRecord(uuid)` | RFCOMM | Encrypted (BT 2.1+), none (legacy) | API 10 |
| `createL2capChannel(psm)` | LE CoC | Authenticated + encrypted | API 29 |
| `createInsecureL2capChannel(psm)` | LE CoC | None | API 29 |
| `createUsingSocketSettings(settings)` | RFCOMM or LE | Configurable | API 36 |

### End-to-End Pattern

```kotlin
// ═══ SERVER ═══
val MY_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

// 1. Create server socket
val serverSocket = bluetoothAdapter
    .listenUsingRfcommWithServiceRecord("MyApp", MY_UUID)

// 2. Accept (blocks — run on background thread!)
val socket: BluetoothSocket = serverSocket.accept()

// 3. Done accepting — close server socket
serverSocket.close()

// 4. Use the connected socket
val input = socket.inputStream
val output = socket.outputStream
// ... read/write ...
socket.close()
```

```kotlin
// ═══ CLIENT ═══
// 1. Cancel discovery first!
bluetoothAdapter.cancelDiscovery()

// 2. Create client socket
val socket = device.createRfcommSocketToServiceRecord(MY_UUID)

// 3. Connect (blocks — run on background thread!)
socket.connect()

// 4. Use the connected socket
val input = socket.inputStream
val output = socket.outputStream
// ... read/write ...
socket.close()
```

### L2CAP CoC (BLE Sockets)

```kotlin
// Server — dynamic PSM
val serverSocket = bluetoothAdapter.listenUsingL2capChannel()
val psm = serverSocket.getPsm()  // send this to client via your own mechanism
val socket = serverSocket.accept()

// Client — connect with PSM from server
val socket = device.createL2capChannel(psm)
socket.connect()
```

### Critical Rules

1. **Always `cancelDiscovery()` before `connect()`** — discovery is heavyweight and slows connections
2. **`accept()` and `connect()` block** — always run on background threads
3. **`close()` aborts blocking calls** — call from another thread to cancel accept/connect
4. **Closing `BluetoothServerSocket` does NOT close accepted sockets** — they're independent
5. **Close sockets when done** — failing to close drains battery
6. **SPP UUID** for serial boards: `00001101-0000-1000-8000-00805F9B34FB`
7. **Generate your own UUID** for Android-to-Android — both sides must use the same UUID
8. **PSM values for LE** are dynamic (128–255) — app must exchange them out-of-band
9. **Requires `BLUETOOTH_CONNECT`** permission on Android 12+ (API 31+)

### BluetoothSocket Key Methods

| Method | Description |
|--------|-------------|
| `connect()` | Initiate outgoing connection (blocks) |
| `getInputStream()` | Returns `InputStream` (available before connect, throws until connected) |
| `getOutputStream()` | Returns `OutputStream` (same) |
| `getRemoteDevice()` | Get the `BluetoothDevice` at the other end |
| `isConnected()` | Connection status check |
| `getConnectionType()` | `TYPE_RFCOMM`, `TYPE_SCO`, `TYPE_L2CAP`, or `TYPE_LE` |
| `getMaxReceivePacketSize()` | Optimize reads to this size |
| `getMaxTransmitPacketSize()` | Optimize writes to avoid half-full packets |
| `close()` | Release resources, abort blocking ops in other threads |

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| BluetoothSocketSettings (API 36, Builder, socketType/RFCOMM UUID/L2CAP PSM/auth/encryption), BluetoothServerSocket (accept/accept with timeout/close/getPsm semantics, thread safety), BluetoothSocket (connect/streams/packet sizes/connection type, finalize), RFCOMM vs L2CAP CoC comparison, secure vs insecure socket decision guide, SDP registration lifecycle, PSM management, permissions by API level, threading patterns | `references/api-details.md` |

</routing>

<reference_index>

**api-details.md** — BluetoothSocketSettings Builder API (API 36, setSocketType TYPE_RFCOMM/TYPE_LE, setRfcommUuid for RFCOMM SDP lookup, setRfcommServiceName for SDP record, setL2capPsm 128-255 for LE CoC, setAuthenticationRequired default false, setEncryptionRequired default false, getters for all fields, listenUsingSocketSettings on adapter, createUsingSocketSettings on device). BluetoothServerSocket details (accept() blocks until connection returns BluetoothSocket and can be called again for subsequent connections, accept(timeout) with millisecond timeout throws IOException on timeout, close() immediately aborts ongoing accept in other threads and releases resources but does NOT close accepted sockets, getPsm() returns dynamic PSM for L2CAP CoC sockets — undefined for non-L2CAP, thread-safe). BluetoothSocket details (connect() blocks until connection or failure — throws BluetoothSocketException with error code on failure plus IOException for stream errors, getInputStream/getOutputStream available before connect but throw until connected, getMaxReceivePacketSize/getMaxTransmitPacketSize for transport-optimal IO sizing, getConnectionType returns TYPE_RFCOMM/TYPE_SCO/TYPE_L2CAP, isConnected for status check, getRemoteDevice, close releases resources and is idempotent, thread-safe — close aborts connect in other threads). RFCOMM details (listenUsingRfcommWithServiceRecord registers SDP with UUID+name+auto-assigned channel — record removed on close or process death, listenUsingInsecureRfcommWithServiceRecord same but no authentication — encrypted for BT 2.1+ and unencrypted for legacy, createRfcommSocketToServiceRecord does SDP lookup of UUID to find channel, well-known SPP UUID 00001101-0000-1000-8000-00805F9B34FB for serial boards). L2CAP CoC details (listenUsingL2capChannel creates secure LE CoC with dynamic PSM, listenUsingInsecureL2capChannel creates insecure, getPsm returns assigned value, PSM released on close/BT off/process death, app defines PSM exchange mechanism, createL2capChannel connects to PSM, createInsecureL2capChannel insecure variant). Secure vs insecure decision (secure = authenticated + encrypted = MITM protection, insecure = encrypted on BT 2.1+ but no authentication = vulnerable to MITM, insecure pre-2.1 = no encryption at all, use secure when both devices have input/output capability, use insecure when one device has no I/O for pairing). Permissions (BLUETOOTH_CONNECT required API 31+, BLUETOOTH for API 30 and below, BLUETOOTH_PRIVILEGED for BluetoothSocketSettings DATA_PATH offload). Threading (accept and connect are blocking — must run on worker threads, close is the cancellation mechanism from another thread, pattern: dedicated AcceptThread and ConnectThread classes).

</reference_index>
