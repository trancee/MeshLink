# BLE Transport Contract

## BleTransport Interface

```kotlin
internal interface BleTransport {
    val incomingData: SharedFlow<IncomingFrame>
    val peerDiscovered: SharedFlow<DiscoveredPeer>
    val peerLost: SharedFlow<LostPeer>
    val connectionStateChanged: SharedFlow<ConnectionStateEvent>

    suspend fun start(advertisementPayload: ByteArray)
    suspend fun stop()
    suspend fun sendFrame(destination: ByteArray, data: ByteArray): Boolean
    fun updateAdvertisementPayload(payload: ByteArray)
}
```

---

## Implementations

| Class | Platform | Source Set | Backend |
|-------|----------|------------|---------|
| `AndroidBleTransport` | Android 10+ (API 29+) | androidMain | Android BLE stack (BluetoothLeScanner, BluetoothGattServer) |
| `IosBleTransport` | iOS 14+ | iosMain | CoreBluetooth (CBCentralManager, CBPeripheralManager) |
| `VirtualMeshTransport` | JVM (test) | commonTest | In-process simulated BLE with configurable topology |

---

## Dual-Role Operation

Every MeshLink node simultaneously acts as:

- **Central (scanner):** Discovers nearby peripherals by scanning for advertisements containing the mesh hash
- **Peripheral (advertiser):** Advertises its presence with a 16-byte payload containing protocol version, mesh hash, L2CAP PSM, and pseudonym

Both roles activate on `start()` and deactivate on `stop()`.

---

## Advertisement Payload (16 bytes)

```
Offset  Size  Field              Description
0       1     Protocol version   0x01 (current)
1       2     Mesh hash          FNV-1a(appId) & 0xFFFF, little-endian
3       1     L2CAP PSM          Dynamic PSM (128-255) or 0x00 (GATT-only)
4       12    Pseudonym          HMAC-SHA-256(keyHash, epoch)[0:12], rotates every 15 min
```

### Pseudonym Rotation

- Epoch = `currentTimeMillis / 900_000` (15-minute windows)
- Prevents long-term BLE tracking by rotating the advertised identifier
- The actual key hash never appears in advertisements

### Mesh Hash Filtering

- Scanner checks the 2-byte mesh hash against local appId hash
- Non-matching advertisements are ignored at scan level (before connection)
- This isolates different MeshLink applications on the same physical layer

---

## Connection Model

### L2CAP-First Strategy

1. Read PSM from advertisement payload (byte offset 3)
2. If PSM ≠ 0x00: attempt L2CAP CoC connection directly
3. If L2CAP fails (or PSM == 0x00): fall back to GATT

### Connection Initiation Policy

When two peers discover each other simultaneously:

1. Both compute FNV-1a hash of their advertisement payloads
2. **Higher hash initiates** the connection
3. Tie-break: lexicographic comparison of raw key hashes

No coordination message needed — both sides independently arrive at the same answer.

### OEM L2CAP Probe Cache

Some device models have broken L2CAP CoC support. `OemSlotTracker` records:
- `manufacturer|model|SDK_INT` → success/failure history
- After N consecutive L2CAP failures for a device class, future connections to that class skip directly to GATT
- The cache persists across MeshLink restarts via SecureStorage

---

## GATT Server Layout (Fallback)

When L2CAP is unavailable, a 5-characteristic GATT server handles framing:

| Characteristic | UUID Suffix | Properties | Purpose |
|----------------|-------------|------------|---------|
| TX | `-0001` | Write, Write Without Response | Peer writes data to us |
| RX | `-0002` | Notify | We notify data to peer |
| Control | `-0003` | Write | Connection control (MTU exchange, keepalive) |
| MTU | `-0004` | Read | Advertises our preferred MTU |
| Service ID | `-0005` | Read | MeshLink service identifier for discovery |

---

## L2CAP Frame Encoding

All frames on L2CAP channels (or GATT characteristics) use a 3-byte header:

```
[FrameType: 1 byte] [Length: 2 bytes LE] [Payload: 0..65535 bytes]
```

FrameType values:
- `0x01` — Data frame (contains a WireCodec message)
- `0x02` — Keepalive (empty payload)
- `0x03` — Close (graceful disconnect)

---

## Connection Limits

`ConnectionLimiter` enforces per-power-tier maximums:

| Power Tier | Max Connections |
|------------|-----------------|
| PERFORMANCE | 7 |
| BALANCED | 5 |
| POWER_SAVER | 3 |

When at capacity, new connection attempts are rejected unless `TieredShedder` evicts a lower-priority peer.

---

## Platform-Specific Notes

### Android

- Requires `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` permissions (API 31+)
- L2CAP CoC: `BluetoothDevice.createL2capChannel(psm)` / `BluetoothAdapter.listenUsingL2capChannel()`
- Background operation requires a foreground `Service` (see `MeshLinkService`)
- `cancelDiscovery()` must be called before any `connect()` call

### iOS

- Requires `NSBluetoothAlwaysUsageDescription` in Info.plist
- Background modes: `bluetooth-central`, `bluetooth-peripheral`
- State Preservation and Restoration: `CBCentralManagerOptionRestoreIdentifierKey` for survive-app-kill reconnection
- L2CAP CoC: `CBPeripheral.openL2CAPChannel(psm)` / `CBPeripheralManager.publishL2CAPChannel(withEncryption:)`
- Advertising data is limited to 28 bytes (no overflow area) — our 16-byte payload fits comfortably

### VirtualMeshTransport (Testing)

- Configurable topology: `linearTopology()`, `fullMeshTopology()`, `starTopology()`, `customTopology()`
- Link manipulation: `breakLink(a, b)`, `restoreLink(a, b)`
- Virtual time compatible — all operations are `delay()`-based
- Captures all sent/received frames for test assertions
