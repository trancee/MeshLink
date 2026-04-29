# The Trust Model: TOFU and Key Pinning

## The problem

MeshLink operates without servers, certificate authorities, or user accounts. When two devices meet over BLE for the first time, neither has any prior knowledge of the other's identity. How do you build trust from nothing?

## Trust-on-First-Use (TOFU)

The same model SSH uses. The first time you connect to a peer:

1. Noise XX handshake exchanges static public keys (Ed25519 + X25519)
2. Both sides compute the peer's key hash: `SHA-256(ed25519Pub || x25519Pub)[0:20]`
3. The key hash is pinned in `TrustStore` — this peer ID is now associated with these keys
4. All future connections to this peer must present the same keys

No certificate chain, no PKI, no registration server. Trust is established by physical proximity — you're standing near this device, so you trust it.

## What happens when keys change

A peer's keys might change because:
- They rotated intentionally (privacy, compromise recovery)
- Their device was factory-reset (key loss)
- An attacker is impersonating them (MITM)

MeshLink can't distinguish these cases cryptographically. It can only tell you: "this peer ID now presents different keys than before."

## Two trust modes

### TrustMode.STRICT (default)

Key changes are silently rejected. The peer is treated as untrusted and connections are refused.

**When to use:** IoT deployments, automated systems, any case where key changes indicate a problem.

### TrustMode.PROMPT

Key changes fire a `KeyChangeEvent` on the `keyChanges` flow. Your app decides:

```kotlin
meshLink.keyChanges.collect { event ->
    // Show user: "Device X has a new identity. Accept?"
    if (userAccepts) meshLink.acceptKeyChange(event.peerId)
    else meshLink.rejectKeyChange(event.peerId)
}
```

If the app doesn't respond within `keyChangeTimeoutMillis` (default 30s), the change is auto-rejected.

**When to use:** Consumer apps, social mesh networking, any case where users manage trust relationships.

## Rotation announcements

When a peer intentionally rotates keys, the process is:

1. Peer generates new Ed25519 + X25519 keypairs
2. Peer creates `RotationAnnouncement(newEd25519Pub, newX25519Pub)`
3. Peer signs the announcement with the **old** Ed25519 private key
4. Announcement is broadcast to all connected neighbors

Receiving peers verify the signature against the old pinned key. If valid:
- STRICT: still rejects (policy: no key changes, period)
- PROMPT: fires the event with extra context ("signed rotation" vs "unsigned mismatch")

This lets the app show different UI for "intentional rotation" vs "suspicious change."

## Why not verify out-of-band automatically?

Some systems (Signal, WhatsApp) use safety numbers — a fingerprint you compare visually or via QR code. MeshLink provides this:

```kotlin
val fingerprint = meshLink.peerFingerprint(peerId)
// "ab:cd:ef:12:34:56:78:9a:bc:de:f0:12"
```

But it's opt-in. For the "Tinder without Internet" use case, forcing fingerprint verification on every new encounter would destroy the user experience. TOFU is the pragmatic compromise.

## TrustStore internals

```
TrustStore
├── pinned: Map<KeyHash, PinnedKey>   // key hash → (ed25519Pub, x25519Pub, firstSeen)
├── pending: Map<KeyHash, PendingKeyChange>  // awaiting app decision (PROMPT mode)
└── rejected: Set<KeyHash>            // explicitly rejected, won't attempt connection
```

- `pinned` is persisted via `SecureStorage` (survives app restart)
- `pending` is in-memory only (cleared on restart — timeout will have fired)
- `rejected` is in-memory (cleared on restart — peer can try again next session)

## What trust provides

Once a peer is in `TrustStore.pinned`:
- Noise XX handshake verifies the peer presents the pinned static key
- If the peer presents a different key, handshake is aborted before any data exchange
- Routing and transfer only proceed with pinned peers
- The public API never exposes a peer that hasn't completed a verified handshake

## What trust does NOT provide

- **Authentication of identity:** TOFU proves key continuity, not real-world identity
- **Protection against first-contact MITM:** If an attacker is present during the first encounter, they can pin their own key. This is the fundamental TOFU limitation.
- **Non-repudiation:** MeshLink doesn't prove who sent a message to third parties
- **Forward secrecy of trust decisions:** If a pinned key is later compromised, past communications could theoretically be decrypted (but Noise ephemeral keys provide forward secrecy for transport)
