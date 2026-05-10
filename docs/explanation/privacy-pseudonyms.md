# Privacy by Design: Pseudonyms and Unlinkability

## The problem

BLE advertisements are visible to anyone with a radio. If a device broadcasts the same identifier continuously, any observer can track that device's movement over time. A mesh networking library that puts stable identifiers in advertisements is a privacy disaster.

## The threat model

| Attacker | Capability | Goal |
|----------|-----------|------|
| Passive observer | BLE scanner at fixed location | Track device movement patterns |
| Network of observers | Multiple scanners, correlated data | Build a location history |
| Active attacker | BLE scanner + advertisement injection | Correlate identities, impersonate |

## What MeshLink puts in advertisements

The 16-byte advertisement payload:

```
[Protocol version: 1B] [Mesh hash: 2B] [PSM: 1B] [Pseudonym: 12B]
```

The **pseudonym** is the only field that could identify a device. Neither the key hash nor any public key ever appears in an advertisement.

## Pseudonym rotation

```
pseudonym = HMAC-SHA-256(keyHash, epochCounter)[0:12]
```

- `epochCounter = currentTimeMillis / 900_000` (15-minute epoch)
- `keyHash` is the 20-byte identity (never transmitted in the clear)
- Only the first 12 bytes of the HMAC output are used

Every 15 minutes, the pseudonym changes to a completely new value. An observer who sees pseudonym A at 10:00 and pseudonym B at 10:15 cannot determine they came from the same device — without knowing the `keyHash`, the HMAC output is indistinguishable from random.

## Why 15 minutes?

A tradeoff between three concerns:

1. **Too short (1 minute):** Peers can't find each other. If both rotate simultaneously, they briefly can't match advertisements to known peers. The mesh becomes unstable.

2. **Too long (1 hour):** A passive observer at a coffee shop can track you for your entire visit.

3. **15 minutes:** Long enough for peers to maintain stable connections (once connected, the L2CAP/GATT link doesn't use the advertisement pseudonym). Short enough that casual tracking is impractical.

## How peers recognize each other after rotation

When device A rotates its pseudonym, how does device B (already connected) know it's still A?

**Answer:** It doesn't need to — the BLE connection is already established. Advertisement pseudonyms are only used for initial discovery. Once an L2CAP channel or GATT connection exists, identity is proven by the Noise XX session (which binds to the peer's static key, not the pseudonym).

For reconnection after link loss:
1. B scans and sees a new pseudonym
2. B initiates Noise XX handshake
3. During handshake, the peer reveals its static key (encrypted under ephemeral key exchange)
4. B checks TrustStore — "I know this static key, it's A"
5. Connection established, regardless of what pseudonym was advertised

## Mesh hash: privacy vs. isolation

The 2-byte mesh hash (`FNV-1a(appId) & 0xFFFF`) is constant and public. This means:

- An observer can tell "this device is running a MeshLink app with hash 0xA3F2"
- They cannot determine the specific appId (FNV-1a is not invertible from 16 bits)
- Devices with different appIds occupy different mesh hashes and never connect

The mesh hash is a deliberate privacy trade — we sacrifice some unlinkability (same hash = same app) to gain network isolation (different apps don't interfere).

## What's NOT private

| Aspect | Visible to passive observer |
|--------|---------------------------|
| "A MeshLink device is here" | Yes (protocol version byte 0x01 is recognizable) |
| "It's the same app as last time" | Yes (mesh hash is stable) |
| "It's the same device as 20 minutes ago" | No (pseudonym rotated) |
| "It's the same device as 10 minutes ago" | Possibly (within same 15-min epoch) |
| The device's identity/key hash | No (never in advertisement) |
| Which peers it connects to | Partially (BLE connection events are visible to nearby observers) |

## Active attacker defense

An active attacker could:
1. Advertise as a MeshLink peer to trigger connections
2. Observe the Noise XX handshake messages

But Noise XX encrypts static keys under ephemeral DH. The attacker sees `e` (ephemeral public key) in msg1 — this is random and unlinkable. The static key `s` is only revealed encrypted in msg2/msg3.

To learn a device's identity, the attacker must complete a full Noise XX handshake — which means the target would add them to the trust store (TOFU). This is detectable: unexpected new peers appearing in the trust list.

## Comparison to other approaches

| System | Advertising strategy | Rotation period |
|--------|---------------------|-----------------|
| Apple iBeacon | Fixed UUID | Never |
| Google Nearby | Rotating identifier | 15 minutes |
| Apple Find My | Rotating key | 15 minutes |
| COVID exposure notifications | Rotating proximity ID | 10-20 minutes |
| **MeshLink** | **HMAC pseudonym** | **15 minutes** |

MeshLink's approach is directly comparable to Apple Find My and COVID exposure notifications — well-established privacy patterns for BLE proximity systems.
