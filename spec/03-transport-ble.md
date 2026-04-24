# 03 — Transport Layer — BLE GATT + L2CAP

> **Covers:** §4 (Transport Layer)
> **Dependencies:** `01-vision-and-domain-model.md` (Peer, Neighbor, Central, Peripheral, Power Mode, Tie-Breaking Rule)
> **Key exports:** BleTransport interface, GATT service design, L2CAP-first connection model, advertisement layout, connection strategy
> **Changes from original:** S11 (GATT peer ID resolution compressed), A4 (bootstrap mode added), A8 (L2CAP force override)

---

## 1. BleTransport Interface

The sole boundary between shared mesh logic and platform BLE hardware.

| Member | Description |
|--------|-------------|
| `localPeerId: ByteArray` | 12-byte Key Hash identifying this device |
| `advertisementServiceData: ByteArray` | 16-byte payload set before starting |
| `startAdvertisingAndScanning()` | Begin BLE advertising + scanning |
| `stopAll()` | Stop all BLE activity, release resources |
| `advertisementEvents: Flow<AdvertisementEvent>` | Discovered peer advertisements |
| `peerLostEvents: Flow<PeerLostEvent>` | Peer disconnection/timeout events |
| `sendToPeer(peerId, data)` | Send raw bytes to a connected peer |
| `requestConnectionPriority(peerId, highPriority)` | Request BLE parameter adjustment |
| `incomingData: Flow<IncomingData>` | Raw bytes received from peers |

Platform implementations post lightweight value-type events. No cross-FFI exceptions.

## 2. Dual-Role BLE

Every device simultaneously operates as both **Peripheral** (advertising, GATT server) and **Central** (scanning, GATT client). Connection slots shared across both roles.

## 3. GATT Service Design

Single GATT service with **two characteristic pairs** (write + notify):

| Characteristic | Direction | Traffic |
|----------------|-----------|---------|
| Control Write/Notify | Bidirectional | Noise XX handshake, routing (Hello/Update), keepalive |
| Data Write/Notify | Bidirectional | Chunks, chunk ACKs, delivery ACKs |

**UUIDs** (prefix `4d455348` = `"MESH"` in ASCII hex):

| Resource | UUID |
|----------|------|
| Advertisement | `4d455348-0000-1000-8000-00805f9b34fb` (32-bit Bluetooth Base UUID) |
| Service | `4d455348-0001-1000-8000-000000000000` |
| Control Write | `4d455348-0002-1000-8000-000000000000` |
| Control Notify | `4d455348-0003-1000-8000-000000000000` |
| Data Write | `4d455348-0004-1000-8000-000000000000` |
| Data Notify | `4d455348-0005-1000-8000-000000000000` |

**Write type:** All writes use **write-without-response** (ATT opcode `0x52`). Protocol provides own reliability.

**MTU minimum:** Effective payload < 100 bytes → reject. Effective = `negotiated_MTU − 4` (3B ATT header + 1B type prefix).

**GATT write errors:** 3 retries with exponential backoff (100ms, 200ms, 400ms). Exhausted → tear down connection + `SEND_FAILED` diagnostic.

## 4. Connection Strategy

**Hybrid persistent + on-demand:**
- **Persistent** fill all slots with top-N neighbors ranked by priority score
- **On-demand** initiated for non-connected neighbor; evicts lowest-priority persistent if no slot free

**Eviction priority** (highest = kept longest):
1. Active transfer — NEVER evict during in-flight transfer
2. Recent activity — LRU
3. Route cost tiebreak — lower cost wins

**Tie-Breaking Rule** (prevents duplicate connections):

| Condition | Central (initiator) | Peripheral (waits) |
|-----------|---------------------|-------------------|
| Peer in lower Power Mode | Us | Peer |
| Peer in higher Power Mode | Peer | Us |
| Same Power Mode | Higher Key Hash (lexicographic unsigned byte comparison) | Lower Key Hash |

Both sides compute same result from advertisement data. Power mode used is **at connection establishment time** (not live). Role re-evaluation only on reconnection.

**Connection stagger:** Random jitter 0–2s per peer (uniform, seeded by local Key Hash) to prevent thundering herd.

**Simultaneous handshake race:** Higher ephemeral public key (lexicographic unsigned byte comparison) stays as initiator; loser drops initiator state and creates a fresh responder handshake.

## 5. Peer Identity Resolution

Android/iOS rotate BLE MAC addresses independently for advertising vs connections. Peer identity is resolved **once per connection** during the Noise XX handshake (over L2CAP or GATT), then cached as a `(connection handle → peer ID)` mapping. Subsequent writes carry only the type byte + payload (no per-write peer ID).

## 6. L2CAP CoC — Primary Data Plane

L2CAP is the **preferred transport**. When a peer advertises `l2capPsm ≠ 0x00`, the connection skips GATT entirely and opens L2CAP directly (3–10× throughput over GATT, no MTU negotiation, no service discovery overhead).

| Platform | API | Minimum |
|----------|-----|---------|
| iOS | `CBL2CAPChannel` | iOS 11 |
| Android | `createInsecureL2capChannel(psm)` | API 29 |

**L2CAP-first connection flow** (when peer's `l2capPsm ≠ 0x00`):

| Step | Platform | Detail |
|------|----------|--------|
| 1. BLE connect | iOS only | `CBCentralManager.connect(peripheral)` — required before `openL2CAPChannel`. Sub-second. Android skips this step. |
| 2. Open L2CAP | Both | Android: `createInsecureL2capChannel(psm)` directly from scan result. iOS: `peripheral.openL2CAPChannel(psm)`. |
| 3. Noise XX handshake | Both | Runs over L2CAP stream framing (§6 frame format below). 8s timeout. |
| 4. Data plane ready | Both | No upgrade dance — L2CAP is the data plane from the start. |

No BLE encryption on the L2CAP channel — MeshLink encrypts at the application layer (Noise XX hop-by-hop + Noise K end-to-end).

**GATT fallback** (when `l2capPsm = 0x00` or L2CAP connection fails):

1. BLE connect (10s timeout)
2. MTU negotiation (reject if effective < 100 bytes)
3. GATT service discovery (10s timeout, must find MeshLink UUID)
4. Noise XX handshake over GATT (8s timeout)

**PSM stability:** The peripheral publishes an L2CAP channel once at `start()`. The PSM is stable for the server socket's lifetime. App restart → new PSM → new advertisement. Peers always see the current value.

**OEM capability probe:** First connection to new `manufacturer|model|SDK_INT` → probe L2CAP. Success cached; failure cached as GATT-only with 30-day expiry. Cached GATT-only → ignore `l2capPsm` in advertisement and use GATT fallback path.

**L2CAP frame format** (stream-oriented, length-prefixed):

| Offset | Size | Field |
|--------|------|-------|
| 0 | 1 byte | Frame type: `0x00` DATA, `0x01` ACK, `0x02` CLOSE |
| 1 | 2 bytes | Payload length (LE, max 65,535) |
| 3 | N bytes | Payload (includes MeshLink type prefix byte) |

**CLOSE frame:** Sent before intentionally disconnecting the L2CAP channel. No payload. Receiver should: complete in-flight chunk writes, flush outbound queue (up to 2s), then close the channel. Receiving CLOSE during a transfer → trigger resume protocol (`0x08`) for the active transfer on reconnection.

**L2CAP chunk sizing** (by lower Power Mode of the pair):

| Lower Power Mode | Chunk Size |
|------------------|-----------|
| Performance | 8192 bytes |
| Balanced | 4096 bytes |
| Power Saver | 1024 bytes |

Set at connection establishment. **Renegotiation:** On power mode change, new chunk size proposed via optional FlatBuffers field on next Keepalive; takes effect at next chunk boundary.

**Backpressure → GATT fallback:** 5 L2CAP writes each >200ms within a sliding window (default 7s) → L2CAP teardown + GATT fallback + `resume_request` for in-flight transfers. Temporary — L2CAP retried with exponential backoff (60s, 120s, 300s). Only actual write failures (not latency spikes) → permanent GATT-only for session.

**L2CAP retry policy:** 4 total attempts (1 + 3 retries), backoff 200ms → 400ms → 800ms. All failed → GATT-only for session.

**Force override config:** `transport.forceL2cap = true` skips GATT fallback (testing). `transport.forceGatt = true` disables L2CAP entirely (debugging chipset issues).

## 7. BLE Advertisement Layout

16-byte payload carried as a second 128-bit service UUID alongside the 32-bit ad UUID. Fits in one 27-byte ad packet. No scan response.

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0 [7:5] | 3 bits | `protocolVersion` | Major protocol version only (0–7). Pre-connection filter for incompatible generations. Full major.minor exchanged during Noise XX (see `04-wire-format.md` §4). |
| 0 [4:3] | 2 bits | `powerMode` | 0=Performance, 1=Balanced, 2=PowerSaver, 3=reserved |
| 0 [2:0] | 3 bits | reserved | Must be 0, ignored by receiver (forward-compatible) |
| 1–2 | 2 bytes | `meshHash` | 16-bit FNV-1a of `appId` (XOR-folded, LE). `0x0000` = no filter. If hash result is `0x0000`, substitute `0x0001`. |
| 3 | 1 byte | `l2capPsm` | L2CAP PSM (128–255). `0x00` = L2CAP not supported (GATT-only). |
| 4–15 | 12 bytes | `keyHash` | First 12 bytes of SHA-256(Ed25519Pub ‖ X25519Pub) |

Full protocol version (major.minor) exchanged during Noise XX handshake. The 3-bit advertisement field enables pre-connection filtering of incompatible protocol generations.

**Mesh isolation:** Non-matching `meshHash` → skip connection (~1/65,536 collision rate).

**Android scan filters:** Start with hardware UUID filters. If broken (inbound GATTs arrive but no filter hits), restart with software filtering.

## 8. First-Launch Bootstrap Mode

A new peer with zero neighbors needs aggressive discovery. For the first `transport.bootstrapDurationMs` (default 60,000ms) after `start()` with an empty peer table:

- Scan duty increased to Performance level regardless of battery
- Advertisement interval reduced to minimum (100ms)
- After bootstrap period or first successful connection, revert to normal power-mode parameters

This ensures rapid initial mesh join in sparse environments (expected time-to-first-peer: 1–10s in typical BLE range).

## 9. Connection Establishment Sequence

**L2CAP-first** (peer advertises `l2capPsm ≠ 0x00` and OEM probe cache is not GATT-only):

| Step | Timeout | Notes |
|------|---------|-------|
| 1. BLE connect (iOS only) | 10s | Android skips — L2CAP opens directly from scan result |
| 2. L2CAP open | 10s | `createInsecureL2capChannel(psm)` / `openL2CAPChannel(psm)` |
| 3. Noise XX handshake | 8s | Over L2CAP stream framing |

Total budget: 28s max (iOS), 18s max (Android). Failure at step 2 or 3 → fall through to GATT path.

**GATT fallback** (`l2capPsm = 0x00`, or L2CAP failed, or OEM cached as GATT-only):

| Step | Timeout | Notes |
|------|---------|-------|
| 1. BLE connect | 10s | |
| 2. MTU negotiation | — | Reject if effective < 100 bytes |
| 3. GATT service discovery | 10s | Must find MeshLink UUID |
| 4. Noise XX handshake | 8s | Over GATT characteristic writes |

Total budget: 28s max. Each step sequential. Failure → disconnect + diagnostic.

## 10. BLE Error Categories

| Category | Meaning |
|----------|---------|
| `CONNECTION_FAILED` | BLE connection failed |
| `L2CAP_OPEN_FAILED` | L2CAP channel open failed (falls back to GATT) |
| `HANDSHAKE_FAILED` | Noise XX incomplete |
| `WRITE_FAILED` | GATT/L2CAP write failed |
| `MTU_TOO_SMALL` | MTU below 100-byte minimum (GATT path only) |
| `DISCOVERY_TIMEOUT` | Service discovery timed out (GATT path only) |
| `SERVICE_NOT_FOUND` | MeshLink UUID not present (GATT path only) |

Platform error codes preserved in diagnostic payloads.

## 11. BLE Auto-Recovery

- **GATT drops** → Peer → Disconnected; reconnection via normal discovery
- **Stack unresponsiveness** → `stopAll()` timeout → `BLE_STACK_UNRESPONSIVE` diagnostic
- **Scan filter failures** → auto fallback hardware → software filtering
