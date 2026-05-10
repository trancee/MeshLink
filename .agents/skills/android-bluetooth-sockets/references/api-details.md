# Android Bluetooth Sockets — API Details Reference

<socket_settings>
## BluetoothSocketSettings (API 36)

Unified configuration for creating Bluetooth server and client socket channels. Replaces the need to call different listen/create methods for each transport.

### Builder

```kotlin
val settings = BluetoothSocketSettings.Builder()
    .setSocketType(BluetoothSocket.TYPE_RFCOMM)  // or TYPE_LE
    .setRfcommUuid(MY_UUID)                       // required for RFCOMM
    .setRfcommServiceName("MyApp")                // optional — SDP record name
    .setAuthenticationRequired(true)
    .setEncryptionRequired(true)
    .build()

// Server
val serverSocket = bluetoothAdapter.listenUsingSocketSettings(settings)

// Client
val clientSocket = device.createUsingSocketSettings(settings)
```

### Builder Methods

| Method | Type | Default | Notes |
|--------|------|---------|-------|
| `setSocketType(type)` | `int` | `TYPE_RFCOMM` | Must be `TYPE_RFCOMM` or `TYPE_LE` |
| `setRfcommUuid(uuid)` | `UUID` | `null` | **Required** for RFCOMM. Used for SDP lookup. |
| `setRfcommServiceName(name)` | `String` | `null` | SDP record name for RFCOMM |
| `setL2capPsm(psm)` | `int` | — | 128–255 only. Only for `TYPE_LE`. |
| `setAuthenticationRequired(b)` | `boolean` | `false` | Require authentication |
| `setEncryptionRequired(b)` | `boolean` | `false` | Require encryption |

### Getters

`getSocketType()`, `getRfcommUuid()`, `getRfcommServiceName()`, `getL2capPsm()`, `isAuthenticationRequired()`, `isEncryptionRequired()`

### Rules

- `TYPE_RFCOMM` requires UUID — `IllegalArgumentException` if missing
- `TYPE_LE` with invalid PSM — `IllegalArgumentException`
- Server `TYPE_LE` gets dynamic PSM via `serverSocket.getPsm()`
- Client `TYPE_LE` must provide PSM via `setL2capPsm()`
- Privileged apps can host LE L2CAP on a designated PSM (reserved range)
</socket_settings>

<server_socket>
## BluetoothServerSocket

A listening Bluetooth socket. Thread-safe.

### accept()

```kotlin
val socket: BluetoothSocket = serverSocket.accept()       // blocks forever
val socket: BluetoothSocket = serverSocket.accept(10_000)  // 10s timeout
```

- **Blocks** until a connection is established
- Returns a connected `BluetoothSocket`
- Can be called again after returning to accept subsequent connections
- Throws `IOException` on error, abort (via `close()`), or timeout
- **Run on a background thread**

### close()

```kotlin
serverSocket.close()
```

- Immediately releases resources
- Causes blocked `accept()` in other threads to throw `IOException`
- **Does NOT close any `BluetoothSocket` returned from `accept()`** — they're independent
- Idempotent

### getPsm()

```kotlin
val psm: Int = serverSocket.getPsm()
```

- Returns the dynamic PSM for L2CAP CoC server sockets
- Only meaningful for sockets from `listenUsingL2capChannel()` / `listenUsingInsecureL2capChannel()` / `listenUsingSocketSettings()` with `TYPE_LE`
- Undefined for non-L2CAP sockets
- PSM is released when socket closes, Bluetooth turns off, or app exits
- **App must exchange PSM with client via its own mechanism** (e.g., GATT characteristic, QR code, NFC)

### Accept Loop Pattern

```kotlin
class AcceptThread(private val adapter: BluetoothAdapter) : Thread() {
    private val serverSocket: BluetoothServerSocket? =
        adapter.listenUsingRfcommWithServiceRecord("MyApp", MY_UUID)

    override fun run() {
        var shouldLoop = true
        while (shouldLoop) {
            val socket: BluetoothSocket? = try {
                serverSocket?.accept()
            } catch (e: IOException) {
                shouldLoop = false
                null
            }
            socket?.also {
                manageConnectedSocket(it)
                // If only accepting one connection:
                serverSocket?.close()
                shouldLoop = false
            }
        }
    }

    fun cancel() {
        serverSocket?.close()  // aborts accept()
    }
}
```
</server_socket>

<client_socket>
## BluetoothSocket

A connected or connecting Bluetooth socket. Thread-safe.

### connect()

```kotlin
// ALWAYS cancel discovery first!
bluetoothAdapter.cancelDiscovery()

val socket = device.createRfcommSocketToServiceRecord(MY_UUID)
socket.connect()  // blocks until connected or fails
```

- **Blocks** until connection is established or fails
- Throws `BluetoothSocketException` with error code on connection failure
- Throws `IOException` for stream-level errors
- `close()` from another thread aborts this call
- **Cancel discovery before calling** — discovery is a heavyweight system procedure that significantly slows connections
- **Run on a background thread**

### IO Streams

```kotlin
val input: InputStream = socket.inputStream
val output: OutputStream = socket.outputStream

// Read
val buffer = ByteArray(1024)
val bytesRead = input.read(buffer)

// Write
output.write("Hello".toByteArray())
output.flush()
```

- Streams are available **before** connect, but operations throw `IOException` until connected
- Use `getMaxReceivePacketSize()` to optimize read buffer size
- Use `getMaxTransmitPacketSize()` to avoid sending half-full packets

### Packet Size Optimization

```kotlin
val recvSize = socket.maxReceivePacketSize   // optimal read buffer
val sendSize = socket.maxTransmitPacketSize  // optimal write chunk

val buffer = ByteArray(recvSize)
val bytesRead = socket.inputStream.read(buffer)
```

### Status

```kotlin
socket.isConnected          // true if active connection exists
socket.connectionType       // TYPE_RFCOMM, TYPE_SCO, TYPE_L2CAP, or TYPE_LE
socket.remoteDevice         // BluetoothDevice at the other end
```

### close()

```kotlin
socket.close()
```

- Releases all resources
- Aborts blocked `connect()` in other threads
- Idempotent
- **Always close when done** — failing to close drains battery

### Connect Thread Pattern

```kotlin
class ConnectThread(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter
) : Thread() {
    private val socket: BluetoothSocket? =
        device.createRfcommSocketToServiceRecord(MY_UUID)

    override fun run() {
        adapter.cancelDiscovery()  // always cancel before connect
        try {
            socket?.connect()
        } catch (e: IOException) {
            try { socket?.close() } catch (closeException: IOException) { }
            return
        }
        manageConnectedSocket(socket!!)
    }

    fun cancel() {
        socket?.close()  // aborts connect()
    }
}
```
</client_socket>

<rfcomm_details>
## RFCOMM Details

### Secure RFCOMM (Recommended)

```kotlin
// Server — registers SDP record
val serverSocket = adapter.listenUsingRfcommWithServiceRecord("MyApp", MY_UUID)
// SDP record: UUID + name + auto-assigned channel
// Record removed on close() or process death

// Client — SDP lookup by UUID to find channel
val socket = device.createRfcommSocketToServiceRecord(MY_UUID)
socket.connect()
```

- Remote device is **authenticated** (link key verified, MITM protection)
- Communication is **encrypted**
- Both sides must use the **same UUID**

### Insecure RFCOMM

```kotlin
val serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("MyApp", MY_UUID)
val socket = device.createInsecureRfcommSocketToServiceRecord(MY_UUID)
```

- **Not authenticated** — vulnerable to MITM attacks
- Bluetooth 2.1+ devices: **encrypted** (encryption is mandatory)
- Pre-2.1 legacy devices: **not encrypted**

### SDP Registration

- System auto-assigns an RFCOMM channel number
- Registers Service Discovery Protocol record with UUID, service name, and channel
- Remote devices query SDP with UUID to discover which channel to connect to
- SDP record auto-removed when server socket closes or app exits
- **Well-known SPP UUID**: `00001101-0000-1000-8000-00805F9B34FB` (for Bluetooth serial boards)
- **Generate your own UUID** for Android-to-Android communication
</rfcomm_details>

<l2cap_coc_details>
## L2CAP Connection-oriented Channels (LE CoC)

BLE-transport streaming sockets with credit-based flow control. Available since API 29.

### Secure LE CoC

```kotlin
// Server
val serverSocket = adapter.listenUsingL2capChannel()
val psm = serverSocket.getPsm()  // dynamic — must send to client yourself
// ... exchange PSM via GATT characteristic, QR code, NFC, etc.
val socket = serverSocket.accept()

// Client (after receiving PSM)
val socket = device.createL2capChannel(psm)
socket.connect()
```

- **Authenticated + encrypted**
- Dynamic PSM assigned by system (128–255 range for LE)

### Insecure LE CoC

```kotlin
val serverSocket = adapter.listenUsingInsecureL2capChannel()
val socket = device.createInsecureL2capChannel(psm)
```

- No authentication, no encryption

### PSM Lifecycle

- Dynamic PSM assigned when server socket created
- Released when: socket closed, Bluetooth turned off, or application exits
- **App defines the PSM exchange mechanism** — there's no SDP for LE CoC
- Common approaches: GATT characteristic, out-of-band (NFC, QR), hardcoded (privileged apps only)
</l2cap_coc_details>

<security_decision>
## Secure vs Insecure Decision Guide

| Scenario | Method | Security Level |
|----------|--------|----------------|
| Both devices have I/O (screen + buttons) | Secure (default) | Authenticated + encrypted |
| One device has no I/O (e.g., headless sensor) | Insecure | Encrypted only (BT 2.1+) |
| Pre-Bluetooth 2.1 device | Insecure | **No encryption** |
| LE data channel, need privacy | Secure L2CAP | Authenticated + encrypted |
| LE data channel, low-security peripheral | Insecure L2CAP | No protection |

**Authentication** = link key verified to prevent person-in-the-middle attacks. Requires pairing with user confirmation (numeric comparison, passkey entry).

**Encryption** = data encrypted in transit. Mandatory on Bluetooth 2.1+ even for insecure sockets, but without authentication the encryption keys may be compromised by MITM.

**Rule of thumb**: Use secure unless the remote device physically cannot participate in pairing (no display, no buttons).
</security_decision>

<permissions>
## Permissions by API Level

### Android 12+ (API 31+)

```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

Request at runtime with `requestPermissions()`.

### Android 11 and below (API 30-)

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
```

### BluetoothSocketSettings DATA_PATH offload

```xml
<!-- Only if using non-default data path -->
<uses-permission android:name="android.permission.BLUETOOTH_PRIVILEGED" />
```
</permissions>

<threading>
## Threading Patterns

Both `accept()` and `connect()` are **blocking** calls. Never call from the main thread.

### Key threading rules

1. **Dedicated thread for `accept()`** — runs the accept loop
2. **Dedicated thread for `connect()`** — blocks until connected
3. **`close()` is the cancellation mechanism** — call from any thread to abort blocking operations
4. **IO streams block on read** — use a dedicated read thread
5. **`close()` is thread-safe** — always immediately aborts and releases

### Typical architecture

```
AcceptThread ─── serverSocket.accept() ───┐
                                          ├── ConnectedThread (read/write)
ConnectThread ── socket.connect() ────────┘
```

Each connected socket gets its own `ConnectedThread` that runs the read loop and exposes a `write()` method called from any thread.
</threading>
