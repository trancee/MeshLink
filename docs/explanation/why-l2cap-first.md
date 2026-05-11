# Why L2CAP-First

## The decision

When MeshLink discovers a peer, it connects via L2CAP directly if the peer's advertisement contains a non-zero PSM (Protocol/Service Multiplexer). GATT is only used as a fallback.

## What L2CAP gives us

L2CAP (Logical Link Control and Adaptation Protocol) provides a raw byte-stream channel between two BLE devices:

- **3–10× throughput** over GATT (no 20-byte ATT_MTU fragmentation, no attribute read/write overhead)
- **No service discovery** — connect directly to the PSM, skip the 200–500ms GATT service enumeration
- **No MTU negotiation** — L2CAP credit-based flow control handles buffer management
- **Bidirectional streaming** — both sides can send simultaneously without request/response coupling

## Why not GATT-only?

GATT was designed for reading sensor characteristics (heart rate, temperature). Using it for bidirectional streaming requires awkward workarounds:

1. Write to a characteristic (max ~512 bytes per write on BLE 5.x)
2. Subscribe to notifications from another characteristic
3. Negotiate MTU explicitly
4. Handle write-without-response flow control manually
5. Service discovery on every connection (~200–500ms)

For a mesh networking library transferring 10KB+ payloads, GATT's overhead is unacceptable.

## How the PSM is discovered

MeshLink advertises two service UUIDs in a single advertisement packet with no
scan response:

- fixed discovery UUID: `4d455348-0000-1000-8000-00805f9b34fb`
- second 128-bit service UUID carrying the raw 16-byte MeshLink discovery payload

The second UUID encodes the payload bytes directly, and byte offset 3 carries the
L2CAP PSM hint:

```
[Version+Power bits: 1B] [Mesh hash: 2B] [PSM: 1B] [Key hash: 12B]
```

- PSM = 0x00: This peer is GATT-only (old device, OEM limitation)
- PSM = 128–255: Valid L2CAP dynamic PSM — connect directly

This avoids the "chicken-and-egg" problem: you don't need to connect via GATT to learn the PSM. It's in the advertisement payload UUID.

## When GATT fallback activates

1. **PSM = 0x00** in the advertisement — peer doesn't support L2CAP
2. **L2CAP connection fails** — OEM chipset bug, iOS limitation, radio contention
3. **OemL2capProbeCache returns false** — we've learned this device model doesn't support L2CAP reliably

The GATT fallback uses a fixed MeshLink service UUID and five counting characteristic UUIDs:

- Service: `4d455348-0001-1000-8000-000000000000`
- Characteristics: `4d455348-0002-1000-8000-000000000000` through `4d455348-0006-1000-8000-000000000000`

The logical layout remains one characteristic for data TX, one for data RX
(notifications), one for control, one for MTU exchange, and one for service
identification.

## OEM reality

Not all BLE chipsets support L2CAP CoC (Connection-oriented Channels) reliably:

- Some Samsung devices (Exynos BLE) drop L2CAP connections under load
- Some iOS versions have L2CAP reconnection bugs
- Some Android 10 devices don't advertise PSM correctly

`OemSlotTracker` learns from failures: if a `manufacturer|model|SDK_INT` combination fails L2CAP repeatedly, future connections to that device class fall back to GATT immediately.

## Connection initiation

When two MeshLink devices discover each other simultaneously (both scanning, both advertising), who initiates the connection?

`ConnectionInitiationPolicy` resolves this deterministically:
1. Both devices compute FNV-1a hash of their advertisement payloads
2. Higher hash initiates
3. Tie-break: compare raw key hashes lexicographically

No coordination message is needed — both sides independently arrive at the same answer.
