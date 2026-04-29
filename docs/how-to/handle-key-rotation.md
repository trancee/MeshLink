# How to Handle Key Rotation

## When to use this

A peer has rotated their identity keys and your app needs to decide whether to accept the new key (TOFU Prompt mode) or you want to understand how rotation propagates.

## Steps

### 1. Choose your trust mode

```kotlin
val config = meshLinkConfig("com.example.myapp") {
    security { trustMode = TrustMode.PROMPT }  // or TrustMode.STRICT
}
```

- **STRICT:** Key changes are silently rejected. The peer is treated as untrusted. No callback.
- **PROMPT:** Your app receives a `KeyChangeEvent` and decides whether to accept.

### 2. Handle key-change events (PROMPT mode)

```kotlin
meshLink.keyChanges.collect { event ->
    // Show UI: "Peer X has a new identity key. Accept?"
    val accepted = showKeyChangeDialog(event.peerId, event.oldFingerprint, event.newFingerprint)
    if (accepted) {
        meshLink.acceptKeyChange(event.peerId)
    } else {
        meshLink.rejectKeyChange(event.peerId)
    }
}
```

### 3. Trigger your own key rotation

```kotlin
meshLink.rotateIdentity()
```

This:
1. Generates a new Ed25519 + X25519 keypair
2. Signs a `RotationAnnouncement` with the **old** key
3. Broadcasts the announcement to all connected peers
4. Updates local Identity and TrustStore
5. Re-advertises with new pseudonym (derived from new key hash)

### 4. Verify a peer's fingerprint out-of-band

```kotlin
val fingerprint: String? = meshLink.peerFingerprint(peerId)
// Display: "ab:cd:ef:12:34:56:78:9a:bc:de:f0:12"
// Compare visually or via QR code
```

## How rotation propagates

1. Peer generates new keys and creates `RotationAnnouncement(newEd25519Pub, newX25519Pub, signature_by_old_key)`
2. Announcement is broadcast as a wire message to all neighbors
3. Each receiving peer:
   - Verifies the signature against the **old** public key (which they have pinned)
   - In STRICT mode: rejects (peer becomes untrusted)
   - In PROMPT mode: emits `KeyChangeEvent` and waits for app decision
   - If accepted: updates pinned key, re-derives key hash, updates all routing/trust state

## Security considerations

- Rotation announcements are signed by the old key — an attacker cannot forge a rotation without the old private key
- The key hash changes on rotation (it's derived from both Ed25519 and X25519 public keys)
- Concurrent rotations from two peers are handled independently per peer
- Identity rotation does not break existing NoiseSession transport keys — those are ephemeral
