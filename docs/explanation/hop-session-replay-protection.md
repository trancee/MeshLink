# Hop session replay protection

## Status

Implemented. This document explains the design of the per-hop transport
session's nonce/sequence-number scheme in
[`MeshEngineHopTransportSupport.kt`](../../meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngineHopTransportSupport.kt)
and [`HopSession`](../../meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngineInternalModels.kt),
the vulnerability it replaces, the security properties it provides, and the
risks that come with the new design and how they are mitigated.

## Background: what a `HopSession` is

A [`HopSession`](../../meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngineInternalModels.kt)
is a single-hop (directly connected peer) transport-layer session, established
by a direct Noise XX handshake between two adjacent mesh peers. It carries a
symmetric `sendKey`/`receiveKey` pair, and every `DirectWireFrame.Data` frame
sent or received on the connection is individually sealed with
ChaCha20-Poly1305 under that session's keys. This is distinct from an
[`EndToEndSession`](../../meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngineEndToEndHandshakeModels.kt),
which stays valid across multiple hops; this document covers only the hop
(single physical/direct connection) layer. `EndToEndSession` has an
analogous, independently-incrementing `sendNonce`/`receiveNonce` pair that was
**not** changed by this work -- see [Scope](#scope) below.

## The vulnerability this design replaces

Prior to this change, both sides of a `HopSession` derived the ChaCha20-Poly1305
nonce from their own, independently-incrementing counter (`sendNonce` on the
sender, `receiveNonce` on the receiver), starting at 0 when the session was
established. The wire frame carried no explicit indication of which nonce the
sender had used -- the receiver simply assumed its own counter matched the
sender's.

This is fine as long as every frame the sender successfully transmits is
also successfully decrypted by the receiver, in order, with no gaps. In
practice that invariant did not hold:

- `MeshEngineInboundSupport.activeHopSession()` looks up the receiver's local
  `HopSession` for the peer and, if none exists yet (for example, because the
  hop session establishment on the receiving side had not completed when the
  first data frame physically arrived), logs `"transport.data.noSession"` and
  drops the frame -- **without ever attempting to decrypt it**, and therefore
  without advancing `receiveNonce`.
- Meanwhile, the sender's `sendEncryptedDirectWireFrame` only cares whether
  the underlying transport reported `TransportSendResult.Delivered`. BLE can
  physically deliver a frame (and report success) even though the receiving
  application dropped it before decryption. The sender's `sendNonce`
  advances regardless.

The result: sender and receiver silently and permanently desynchronize.
Every subsequent frame is encrypted by the sender with a nonce the receiver
is not expecting, so `chacha20Poly1305Open` fails with `AEADBadTagException`
forever, until the entire hop session is torn down and re-handshaked. This
is exactly the failure observed in physical device testing: `"transport.data.noSession"`
immediately followed by `"transport.data.decrypt cause=AEADBadTagException"`
on every following frame, requiring a full retry of the exchange to recover.

The pre-existing duplicate-suppression logic (`lastInboundCiphertext`, a
single-slot exact-byte-comparison against only the immediately prior frame)
did not help here: it only recognized a redundant delivery of the *same*
frame (e.g. arriving over both the GATT and L2CAP side-link transports at
once), not a nonce desync caused by a dropped frame.

## The new design: explicit, authenticated sequence numbers + sliding replay window

This mirrors the well-established design used by WireGuard and IPsec ESP.

### 1. Explicit sequence number, bound via AAD

Each hop-encrypted frame now carries its own wire format:

```
[version: 1 byte][sequence: 8 bytes, little-endian][AEAD ciphertext]
```

- `version` is `HOP_FRAME_VERSION` (currently `1`). An unrecognized version is
  rejected immediately as `HopFrameFormatException`, without attempting to
  decrypt -- this avoids ever misinterpreting a future/incompatible frame
  layout as ordinary ciphertext.
- `sequence` is the sender's `HopSession.sendNonce` value at encryption time,
  transmitted explicitly instead of being inferred by the receiver.
- The `[version, sequence]` header is passed as the AEAD's **associated data
  (AAD)**, not left as a bare, unauthenticated plaintext field. This is the
  single most important detail of the design: because the header is bound
  into the Poly1305 tag, nothing on the wire can alter the declared sequence
  number without invalidating the ciphertext's authentication. Concretely,
  this closes a spoofing/replay-relabeling attack: without AAD binding, an
  on-path relay (or any node the frame transits) could take a previously
  captured, still-valid ciphertext and simply relabel its plaintext sequence
  header to a value that would pass the replay window, without needing the
  session key at all.

The receiver decrypts using the sequence number carried in the frame itself,
not its own counter -- so a gap left by a lost/dropped frame is now harmless:
the next frame simply declares a higher sequence number, and decryption uses
that value directly.

### 2. Sliding replay window instead of exact-duplicate check

`HopSession` tracks:

- `receiveHighWaterMark: ULong?` -- the highest sequence number successfully
  *authenticated* so far (`null` until the first frame is accepted).
- `receiveWindowBitmap: ULong` -- a 64-bit bitmap of which of the last 64
  sequence numbers (relative to the high water mark) have already been
  accepted.

`decryptHopPayload` (in `MeshEngineHopTransportSupport.kt`):

1. Parses and validates the header (version, sequence).
2. Checks `HopSession.isWithinReplayWindow(sequence)` -- rejects (via
   `ReplayedHopPayloadException`) if the sequence number is more than 64
   behind the high water mark (too old), or already marked as seen in the
   window.
3. Only if the window check passes, attempts the actual AEAD decryption.
4. **Only after a successful decrypt**, calls
   `HopSession.recordAcceptedSequence(sequence)` to advance the high water
   mark / set the window bit.

Step 4's ordering is deliberate and important: a forged or corrupted frame
that fails authentication must never consume its sequence number's window
slot. If it did, an attacker could send a garbage frame claiming a legitimate
future sequence number purely to "burn" that slot, causing the real frame
with that sequence number to be wrongly rejected as a replay when it later
arrives. Checking the window *before* decrypting (so a replay is rejected
cheaply, without needing a full AEAD computation) and committing it *after* a
successful decrypt handles both concerns correctly.

This tolerates the two real-world reordering scenarios already documented in
the code:

- Redundant delivery of the identical frame over both the GATT and L2CAP
  side-link transports (previously handled by the exact-duplicate check;
  now handled because the repeated sequence number is already in the
  window).
- A frame lost before the receiver had a session ready (the original bug):
  the next real frame simply arrives with a higher sequence number and
  decrypts using its own declared value, no resync needed.

### 3. `DuplicateHopPayloadException` renamed to `ReplayedHopPayloadException`

The exception thrown for "don't surface a decrypt-failure diagnostic for
this" is now `ReplayedHopPayloadException`, reflecting that it covers both
exact-duplicate redelivery and genuine sequence-number replay/staleness, not
only byte-identical duplicates.

## Threat model and security analysis

### What is authenticated, and by whom

Only a peer holding the pairwise `HopSession`'s `sendKey` (established via a
direct Noise XX handshake) can produce a ciphertext that passes
authentication for that session. On-path relays that are not a party to a
given hop session cannot forge, decrypt, or usefully tamper with frames on
that hop; they can only drop, delay, reorder, or duplicate the opaque bytes
in transit (which is exactly the reordering/duplication behavior this design
is built to tolerate). See [the trust model](trust-model.md) for the broader
picture of how per-hop and end-to-end trust are established.

### Risk: the sequence number must be authenticated, not a bare field

**Risk.** If the `[version, sequence]` header were left outside the AEAD's
AAD (an unauthenticated plaintext field), an on-path relay could tamper with
the declared sequence number on an old, previously-valid captured ciphertext
to slip it past the replay window's "already seen" check, effectively
replaying old traffic without needing the session key.

**Mitigation.** The header is passed as the AEAD's AAD on both seal and open
(`encryptHopPayload`/`decryptHopPayload`). Any tampering with the header
invalidates the Poly1305 tag, so the frame fails authentication rather than
being silently replayed with a relabeled sequence number. This is verified in
`MeshEngineHopTransportSupportJvmCryptoTest` ("...tampered with"), using the
real JVM `ChaCha20-Poly1305` implementation (see
[Test coverage and a crypto test-double caveat](#test-coverage-and-a-crypto-test-double-caveat)
for why that specific test needed a real, rather than the shared
placeholder, crypto provider).

### Risk: nonce reuse

**Risk.** ChaCha20-Poly1305, like all AEAD ciphers built on a stream cipher,
is catastrophically broken if the same key+nonce pair is ever used to seal
two different plaintexts: the keystream can be recovered by XORing the two
ciphertexts, and forgery becomes possible. Under the previous implicit-counter
design, nonce uniqueness was guaranteed structurally -- the sender's `sendNonce`
only advances by one, once, per attempted send, and is never reused without a
whole new session (with fresh keys) being established. That structural
guarantee is unchanged by this work: `sendNonce` is still a single,
mutex-guarded (`session.outboundMutex`), monotonically-incrementing counter,
now simply transmitted explicitly instead of inferred. **This design does not
introduce a new nonce-reuse vector** -- it only changes how the *receiver*
determines which nonce was used, not how the *sender* generates it.

**Mitigation / residual risk.** A future change to the sending path that
allowed re-sealing the same `sendNonce` value with different plaintext (for
example, a naive retry-with-different-payload) would be a nonce-reuse bug
regardless of this design; the existing `outboundMutex`-guarded,
append-only `sendNonce` increment (only on confirmed
`TransportSendResult.Delivered`) is what prevents this, and any future
change to that logic should preserve the "advance-once, on confirmed
delivery" invariant.

### Risk: replay window size and its trade-offs

**Risk.** `REPLAY_WINDOW_SIZE = 64` is a security/robustness trade-off: too
small, and the legitimate reordering this design is meant to tolerate (dual
GATT/L2CAP delivery, or a frame from a few positions back arriving late)
would still be wrongly rejected; too large, and a captured frame remains
replayable for a correspondingly longer horizon before it ages out of the
window.

**Mitigation.** 64 matches the window size used by WireGuard and IPsec ESP
for the same class of problem, chosen because it comfortably covers the
actual reordering distance possible on this system (redundant delivery of the
same or an adjacent frame via two side-link transports) without giving an
attacker an impractically long replay horizon. The window is anchored to a
strictly monotonic high water mark, so there is no way to "reset" or replay
indefinitely -- once the high water mark advances past a given sequence
number's replay-window position, that sequence number is permanently
rejected as too old, regardless of whether a copy of it is replayed once, or
many times, or years later.

### Risk: wire-format/version confusion

**Risk.** Introducing a new header format for hop-encrypted frames without a
version marker could lead a future incompatible change (or a malfunctioning
peer running different code) to have its old-format bytes misinterpreted as
the new format (or vice versa), potentially treating attacker- or
corruption-controlled bytes as an authenticated sequence number in a way the
implementation did not intend.

**Mitigation.** `HOP_FRAME_VERSION` is checked before any other parsing;
unrecognized versions are rejected outright as `HopFrameFormatException`
rather than guessed at. This mirrors the version byte
[`MessageSealer`](../../meshlink/src/commonMain/kotlin/ch/trancee/meshlink/crypto/MessageSealer.kt)
already uses for its own (separate) message-level sealed format.

### Risk: availability (this was itself a DoS-shaped bug)

**Note, not a new risk.** The bug this design fixes was itself
security-relevant from an availability standpoint: a single frame lost
before session establishment (which could occur naturally, or be induced
deliberately by an attacker capable of influencing BLE-layer timing/delivery
without needing to break any cryptography) would permanently wedge a hop
session, forcing an expensive full re-handshake. The new design removes this
failure mode entirely: a gap in received sequence numbers is simply
tolerated, with no resync required.

## Scope

This work covers only `HopSession` (the single-hop/direct-connection
transport layer, in `MeshEngineHopTransportSupport.kt` and
`MeshEngineInternalModels.kt`). It intentionally does not touch
`EndToEndSession` (`MeshEngineEndToEndHandshakeModels.kt`), the analogous
multi-hop, Noise-XX-relayed session type, which has its own independently
incrementing `sendNonce`/`receiveNonce` pair following the same *shape* as
the old, now-replaced hop-session design. Whether `EndToEndSession` is
exposed to the same kind of desync bug (and whether the same fix should be
applied there) was out of scope for this change and has not been
investigated; a future audit should check whether the same
"receiver-drops-a-frame-before-decrypting-it" pattern can occur on that path,
and if so, apply the same explicit-sequence-number + replay-window treatment.

## Test coverage and a crypto test-double caveat

- `MeshEngineHopTransportSupportTest` (common test, runs on every platform)
  covers: normal send/receive nonce/sequence advancement, redundant/duplicate
  delivery rejection, out-of-order acceptance within the replay window and
  rejection of a later replay of the same sequence number, tolerance of a
  sequence-number gap left by a frame that is never delivered to
  `decryptHopPayload` at all (the exact scenario that caused the original
  bug), and rejection of an unsupported frame version.
- `MeshEngineHopTransportSupportJvmCryptoTest` (JVM-only test, new) covers
  the AAD-tampering and ciphertext-corruption rejection cases specifically
  against the real `JvmCryptoProvider`.

The AAD-tampering case could not be added to the shared `commonTest` suite
using `PlaceholderCryptoProvider` (the fake `CryptoProvider` used throughout
`commonTest` for platform-independent tests): its pseudo-AEAD tag
computation feeds `key + nonce + aad + ciphertext` through a hash function
that only reads as many input bytes as its fixed 32-byte output has indices,
and because the key alone is 32 bytes and comes first in that concatenation,
the computed tag ends up depending only on the key -- never on the nonce,
AAD, or ciphertext content. That means `PlaceholderCryptoProvider` cannot
actually detect AAD tampering, so a test asserting that property against it
would not be verifying anything real. This is a pre-existing limitation of
that test double (unrelated to this change) and was worked around by placing
the AAD-tamper-detection test in `jvmTest` against the real
`JvmCryptoProvider` instead, which does implement full ChaCha20-Poly1305 AAD
semantics via the JCA `Cipher` API. This limitation of
`PlaceholderCryptoProvider` was not otherwise fixed as part of this change,
since doing so was out of scope and would need its own careful validation
against every other test relying on its current behavior.
