# Why Noise XX Only

## The decision

MeshLink uses a single handshake pattern: `Noise_XX_25519_ChaChaPoly_SHA256`. Noise IK was in the original spec but was deliberately removed.

## What Noise IK would have given us

Noise IK is a "known key" pattern — the initiator encrypts their static key to the responder's known static key in the first message. This saves one round-trip (~60–80ms on BLE) because the responder can authenticate the initiator immediately.

## Why we removed it

### 1. Wrong-key fallback race

If the initiator has a stale responder key (peer rotated identity), the first IK message is undecryptable. The responder must signal "try XX instead." This creates a race condition:

- What if both peers simultaneously attempt IK with stale keys?
- What if the "fallback to XX" signal is lost?
- What if an attacker replays an old IK message to trigger fallback?

Each scenario requires its own recovery logic. Two handshake code paths means two sets of state machines, two sets of edge cases, two sets of tests.

### 2. The cost is negligible

On a 28-second BLE connection budget, 60–80ms is less than 0.3% of available time. The "Tinder without Internet" use case involves connections lasting minutes to hours. The reconnect overhead is invisible to users.

### 3. One code path, one correctness proof

With XX only:
- Every connection uses the same 3-message flow
- Simultaneous-race resolution is clean (lexicographic key comparison)
- No "which handshake are we in?" state tracking
- Test coverage is exhaustive on one path instead of partial on two

### 4. SHA-256 for hardware acceleration

The full pattern name is `Noise_XX_25519_ChaChaPoly_SHA256`. SHA-256 was chosen over SHA-512 or BLAKE2 because ARM64 (both Android and iOS target SoCs) has dedicated SHA-256 hardware instructions (`SHA256H`, `SHA256SU0`). This makes the handshake hash computations near-zero cost.

## What we lost

- ~60ms on reconnect to a previously-known peer
- The ability to authenticate in one round-trip instead of two

## When to revisit

If a future use case requires sub-100ms reconnection (e.g., real-time audio relay), Noise IK could be reconsidered. The `CryptoProvider` interface and `HandshakeState` abstraction support it without breaking changes — but the state machine complexity cost remains.
