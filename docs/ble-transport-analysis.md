# BLE Transport Layer Analysis

## Context

This analysis evaluated alternative BLE transport mechanisms for MeshLink. The outcome — hybrid GATT + L2CAP CoC — is now fully specified in [design.md §3 (Transport Layer)](design.md#3-transport-layer).

This document preserves the **evaluation rationale** for the four transport options considered.

---

## Transport Options Evaluated

### 1. RFCOMM (Bluetooth Classic SPP)

| Aspect | Detail |
|--------|--------|
| Throughput | 100–200 KB/s |
| iOS support | ❌ **Blocked** — MFi certification required. No public API. |
| Android support | ✅ API 5+ (BluetoothSocket RFCOMM) |
| Pairing | Required |
| Power | ~10× BLE power draw |

**Verdict: Eliminated.** iOS does not expose Bluetooth Classic to third-party apps. Non-starter for cross-platform.

---

### 2. BLE GATT (Current Design)

| Aspect | Detail |
|--------|--------|
| Throughput | 10–20 KB/s (write-with-response), 20–50 KB/s (write-without-response) |
| iOS support | ✅ All versions (CoreBluetooth) |
| Android support | ✅ API 21+ (Android 5.0) |
| Pairing | Not required |
| Power | Low (BLE native) |
| MTU | iOS: auto-negotiated (185–512 depending on device); Android: requestMtu() up to 517 |

**Strengths:**
- Universal compatibility across all BLE-capable devices since 2015
- No pairing barrier — critical for mesh discovery
- Natural message boundaries (each write = one PDU)

**Weaknesses:**
- Low throughput for bulk data (~410 chunks for 100KB at 244-byte MTU)
- ATT protocol overhead per write (3-byte ATT header + characteristic lookup)
- Requires app-level SACK (selective ACK) and flow control
- 100KB transfer takes ~5–10 seconds at typical throughput

---

### 3. BLE L2CAP CoC (Connection-Oriented Channels)

| Aspect | Detail |
|--------|--------|
| Throughput | 50–150 KB/s (3–10× GATT) |
| iOS support | ✅ iOS 11+ (CBL2CAPChannel, 2017) |
| Android support | ✅ API 29+ / Android 10+ (createL2capChannel, 2019) |
| Pairing | **Not required** for insecure variant |
| Power | Same as GATT (same BLE physical layer) |
| Flow control | Built-in credit-based (automatic backpressure) |

**How it works:**
- Peripheral publishes an L2CAP channel → system assigns a dynamic PSM (Protocol/Service Multiplexer)
- Central opens a channel to that PSM
- Both sides get stream I/O (InputStream/OutputStream on Android, NSStream on iOS)
- Credit-based flow control: receiver grants credits, sender blocks when credits exhausted
- SDU sizes up to 65,535 bytes (vs 244-byte GATT writes)

**Strengths:**
- **3–10× throughput** vs GATT — 100KB transfer drops from ~5–10s to ~1–2s
- **Built-in flow control** — credit-based backpressure replaces our SACK
- **No pairing** when using insecure variant (iOS: `publishL2CAPChannel(withEncryption: false)`, Android: `createInsecureL2capChannel()`)
- Lower per-byte overhead (no ATT framing, no characteristic lookup)
- Works in iOS background mode (with bluetooth-central/peripheral capability)
- MeshLink already encrypts at app level (Noise XX + Noise K) — BLE-level encryption is redundant

**Weaknesses:**
- **Android fragmentation**: Known reliability issues on Samsung, OnePlus; retry logic essential
- **Narrower device support**: Android 10+ (vs Android 5+ for GATT) — excludes ~8% of active Android devices
- **Stream-oriented**: No natural message boundaries — must add length-prefix framing
- **PSM discovery**: Requires out-of-band PSM exchange (via GATT characteristic or handshake)
- **iOS MTU**: Not configurable for L2CAP; default 672 bytes (adequate but not optimal)

---

### 4. BLE Extended Advertisements (Bluetooth 5.0+)

| Aspect | Detail |
|--------|--------|
| Payload | Up to 251 bytes (vs 31 legacy) |
| iOS | Auto-managed by stack; no developer control |
| Android | API 26+ via AdvertisingSet API |

**Verdict: Not suitable for data transfer.** Useful only for richer advertisements. iOS doesn't expose control. Current decision to use GATT for gossip remains correct. However, on Android 8+, extended advertisements could carry a few extra gossip hints — marginal benefit, not worth the complexity.

---

## Outcome

**Adopted: Hybrid GATT + L2CAP CoC** with GATT as guaranteed fallback.

- **Zero compatibility risk** — worst case = GATT-only behavior
- **3–10× throughput** when both peers support L2CAP
- **Built-in flow control** — L2CAP credit-based replaces app-level SACK
- **No pairing needed** — insecure L2CAP + Noise XX app-level encryption

Full specification: [design.md §3 (Transport Layer)](design.md#3-transport-layer).

## Rejected Alternatives

| Transport | Why Rejected |
|-----------|-------------|
| **RFCOMM / Bluetooth Classic** | iOS blocks for third-party apps; requires pairing; higher power |
| **Wi-Fi Direct** | Shorter battery life, less universal |
| **Multipeer Connectivity** | iOS-only, Apple-controlled protocol |
| **Nearby Connections API** | Google-only, opaque protocol |
| **Extended Advertisements for data** | Unreliable cross-platform; iOS gives no developer control |
| **Multiple L2CAP channels per peer** | Unclear platform limits; unnecessary complexity |
