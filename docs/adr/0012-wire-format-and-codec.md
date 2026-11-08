# 0012: Wire format principles and codec

Status: accepted (2026-11-02; ticket "What is the wire format, and which codec implements it?"). Version: 6 (amended 2026-11-03, ADRs 0014 and 0015; 2026-11-08, ADRs 0016, 0018 and 0019).

## Context

The protocol spec (ADR 0006) is the authority on bytes, with conformance vectors. This ADR fixes the principles that the spec's byte layouts follow, and how MeshLink encodes and decodes them. Inputs:

- **ADR 0008:** 8 B routing tag, 12 B message ID, 1 B hop limit, a message lifetime of at least 120 s with ±30 s clock skew tolerated, reassembly at every hop, one wire format for every density class.
- **ADR 0009:** GATT and L2CAP CoC carry the same frames, and a link falls back from CoC to GATT without losing the session. GATT notifications and writes without response, and CoC, are ordered and reliable at the BLE link layer.
- **ADR 0002 and ADR 0003:** Noise transport per link in the secured profiles (16 B tag, at most 65,535 B per Noise message); identity is an Ed25519 key; a certificate binds the static keys.
- **ADR 0010:** a 16 B payload inside the rotating 128-bit UUID (mesh tag, resolvable tag, flags, random).
- **Threat model:** message sizes are metadata; global traffic analysis is out of scope.

Evidence: [the codec evidence](../evidence/2026-11-codec/README.md), 2026-11-02, from the throwaway branch `prototype/codec`. Three codecs over placeholder frames: (A) byte-aligned hand-written, (B) bit-packed hand-written, (C) bit-packed with Kompact 0.10.0. Pre-registered criteria, measured on the JVM, a Samsung XCover 4 (Android 9), a Pixel 6 (Android 17) and an iPhone 15 (iOS 17.6.1):

| Question | Result | Criterion |
|---|---|---|
| Bit-packing vs byte-aligned frames | saves 1–2 B per frame (unicast header 36 → 34 B) | ≥ 4 B: fail |
| Bit-packing the 16 B advertisement | 19 spare bits | ≥ 2 B: pass |
| Kompact decode safety | every truncated prefix, over-long input and 1,000,000 mutated inputs per frame type: 0 exceptions, 0 disagreements | pass |
| Kompact throughput vs hand-written | decode 0.18–0.48× on phones, 0.35–0.73× on the JVM; encode 0.13–0.37× | ≥ 0.5×: fail |
| Kompact allocation | equal bytes per decode on the JVM, with a reused, non-thread-safe cursor | pass (JVM only) |

## Decision

1. **Codec:** hand-written encoders and decoders. Kompact is not adopted, so it gets no dependency-register row.
2. **Layout:** byte-aligned fields with big-endian, fixed-width integers; no varints and no bit-packing in frames. Only byte 0 of the advertisement payload is bit-packed (decision 10).
3. **Link stream:** each bearer is treated as one ordered byte stream per link. A link record is a 2 B length followed by its body; in the secured profiles the body is a Noise transport message. GATT cuts the stream into writes and notifications of the ATT payload size; CoC writes it as is. The same framing therefore survives a CoC ↔ GATT fallback.
4. **Fragmentation is link-local:** a frame larger than one record is split into fragments that carry a 1 B link-local stream ID and a 2 B index whose top bit marks the last fragment. They do not carry the 12 B message ID. Streams interleave, so a large message does not block small ones. There is no per-fragment CRC: Noise AEAD covers the secured profiles and BLE's link-layer CRC24 covers `unsecured`. The record size cap is set with the message size cap.
5. **Lifetime on the wire:** an absolute expiry as 4 B unsigned Unix seconds (valid until 2106), set by the origin, covered by its end-to-end authentication in the secured profiles, and checked under ADR 0008's ±30 s skew.
6. **Versioning:** one protocol version byte, exchanged in the GATT capability read and bound into the Noise prologue. A different major version is rejected with a typed reason. Each frame carries a 1 B type and no version of its own.
7. **Unknown types and extensions:**
   - An unknown routed frame type is still relayed using its routing header, but not delivered.
   - An unknown link-local frame type is dropped with a diagnostic.
   - Extensibility lives in the handshake and capability payload, as TLVs (1 B type, 2 B length). An unknown non-critical TLV is ignored; an unknown critical TLV fails the handshake.
   - Frames have no extension area (Constitution D1).
8. **NodeId:** the full 32 B BLAKE2b-256 hash of the Ed25519 public key, with a domain-separation string fixed in the spec. NodeIds rarely go on air, because routing uses rotating tags. Apps get a shorter display form separately.
9. **Identity certificate:** a fixed-layout binary record: version, profile, Ed25519 key (32 B), X25519 key (32 B), X-Wing key (1,216 B, post-quantum only), not-before and not-after (4 B each), then a 64 B Ed25519 signature over every preceding byte with a domain-separation prefix. No CBOR, ASN.1 or X.509.
10. **Advertisement payload (16 B):**

    | Bytes | Field |
    |---|---|
    | 0 | 3-bit layout version and 5 flag bits (meanings in the spec) |
    | 1–4 | Mesh tag (32 bits) |
    | 5–12 | Resolvable tag (64 bits; random in open and `unsecured` meshes) |
    | 13–15 | Random |

11. **Padding:** the secured profiles pad the end-to-end plaintext to a multiple of 32 B (at most 31 B extra), so relays do not learn exact sizes. `unsecured` has no padding. Larger size buckets are rejected for v1 because of airtime.
12. **Sender in `unsecured`:** routed messages carry the sender's 32 B NodeId in clear, because there is no Noise IK to carry it. The NodeId is claimed, not proven.

## Alternatives

- **Kompact as the codec:** safe on every input tested, but 2–8× slower than hand-written code, and pre-1.0.
- **Bit-packed frames:** 1–2 B per frame for a codec that is harder to read and to write vectors for.
- **Varints for lengths:** save a byte on short fields, but add a second encoding to validate and fuzz.
- **The 12 B message ID in every fragment:** fragments never leave the link, so a 1 B stream ID is enough; the prototype's 17–19 B fragment header shrinks to 3 B.
- **A relative TTL decremented at every hop:** needs no wall clock (both platforms have monotonic clocks that run during sleep, for example `mach_continuous_time` on iOS), but relays must rewrite it, so it cannot be covered by the origin's end-to-end authentication. A malicious relay could then extend a message's life without bound, which breaks ADR 0001's replay rejection within the message lifetime. An absolute expiry set by the origin can be authenticated end to end.
- **A version byte in every frame:** costs a byte per frame; the link already agreed on one version.
- **A 16 B NodeId:** saves bytes that are rarely sent, at a 64-bit collision bound.
- **CBOR or X.509 certificates:** a general parser on the handshake path, for one fixed record.
- **No padding:** exact plaintext sizes visible to relays.

## Risks

- **Clock-dependent expiry:** a phone with a wrong clock drops or over-holds messages. Mitigation: ADR 0008's ±30 s tolerance and a diagnostic for expiries far from local time.
- **Placeholder frames:** the evidence used plausible shapes, not the final spec. The verdict rests on byte strings dominating the frames, which ADR 0008's sizes make likely.
- **Hand-written decoders:** every decoder needs truncation, over-long and fuzz tests (Constitution T10) and conformance vectors.
- **One run per platform:** the margins are wide (the best device ratio is 0.48 against a 0.5 bar), and allocation was measured on the JVM only.

## Consequences

- The protocol spec defines the record, fragment, routing-header, certificate, TLV and advertisement layouts with vectors.
- The decoder test suite reuses the prototype's method: every prefix length, over-long inputs and a deterministic mutation fuzzer.
- The threat model's per-hop fragmentation paragraph and the profile guarantees table are updated (see Migration).
- ADR 0003 gains the NodeId size and certificate encoding; ADR 0010's placeholder payload layout is replaced by decision 10.

## Migration

None: greenfield. The amendments to ADRs 0003 and 0010, the threat model and the profile guarantees are recorded with this ADR.

## Amendments

2026-11-03, store-and-forward ([ADR 0014](0014-store-and-forward.md)):

- **Routing header:** a unicast carries a 16 B tombstone commitment H(k) in clear. Delivery receipts carry the matching tombstone {message ID, k} in clear.

2026-11-03, message size ([ADR 0015](0015-message-size-and-compression.md)):

- **Record cap (decision 4):** a record body is at most 1,024 B in every profile and bearer, without negotiation.
- **Busy reply (decision 4):** a link-local reply refuses a large frame when the receiver's reassembly budget is full; the sender backs off and retries.

2026-11-08, routing metadata exposure ([ADR 0016](0016-routing-metadata-exposure.md)):

- **Routing header:** source and destination fields are 8 B tags of either kind: public tags (first messages, route requests for them) or conversation tags; relays treat both alike. Sizes are unchanged.
- **Neighbor-list entry:** the 8 B identifier field of the peer's current advertisement (bytes 5–12 of decision 10).

2026-11-08, certificate acquisition ([ADR 0018](0018-certificate-acquisition.md)):

- **New routing messages:**
  - A certificate lookup request: an 8 B salt, an 8 B lookup tag (BLAKE2b over salt ‖ NodeId with a domain-separation string, truncated) and a 32 B ephemeral X25519 key, flooded under route-request rules.
  - A lookup reply: one certificate sealed to that key with ChaCha20-Poly1305 (+16 B tag), returned along the reverse path.
- The protocol spec defines both layouts, the domain string and their vectors.

2026-11-08, release and versioning ([ADR 0019](0019-release-versioning-and-api-compatibility.md)):

- **Versioning (decision 6):** the version byte is independent of the library version and changes only on incompatible wire changes. A release that introduces protocol N+1 also speaks N for at least one library MINOR; the capability read selects the highest version both sides speak. Other versions are still rejected with a typed reason.
