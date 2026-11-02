# Codec prototype (ticket 18), throwaway

Branch `prototype/codec`. Throwaway code: it answers ticket 18 ("What is the wire format, and which codec implements it?") with measurements and is never merged.

The protocol spec is the authority on bytes. This prototype does **not** define MeshLink's wire format. Every frame below is a **placeholder** chosen to be plausible, so that layout and codec questions can be measured on realistic shapes.

## Questions

1. **Layout.** Does a bit-packed layout (LSB-first, sub-byte fields) save meaningful bytes over a byte-aligned layout for MeshLink's frames?
2. **Codec.** For the same bit-packed layout, is Kompact (`ch.trancee.kompact:kompact` 0.10.0, runtime artifact only, no KSP) better than a hand-written encoder and decoder?

## Implementations

- **A:** byte-aligned layout, hand-written. Every field starts and ends on a byte boundary. Single-bit booleans may share one flags byte; a multi-bit numeric field never shares a byte. Multi-byte integers are little-endian.
- **B:** bit-packed layout, hand-written. Sub-byte fields are packed LSB-first (Kompact's bit order); byte strings (tags, message IDs, keys, signatures) are copied as bytes.
- **C:** the same bit-packed layout through the Kompact 0.10.0 runtime (`KompactCursor` for encode and decode: the caller-owned, reusable "holder" path).

B and C must produce identical bytes for every sample frame and must return the same result (frame or typed error) for every fuzz input.

## Pre-registered criteria

Written 2026-11-02 20:07 CET, before any code or measurement. Any change is logged under "Deviations" with time and reason.

**Layout:** bit-packing is worth it only if it saves **≥ 4 B on the routed unicast header** or **≥ 2 B of usable space in the 16 B advertisement metadata**. Savings are reported for every frame.

**Codec (C versus B):** adopt Kompact only if **all** of these hold:

1. **Decode safety:** zero uncaught exceptions, zero out-of-bounds reads, and every malformed input rejected as a typed error. Inputs: every truncated prefix length of every sample, over-long inputs (trailing bytes), and ≥ 1,000,000 random or mutated inputs per frame type from a deterministic seed.
2. **Throughput:** C decode and encode throughput ≥ 0.5× of B on the JVM, on at least one Android phone and on one iPhone.
3. **Allocation:** allocations per decode not worse than B by more than 1 object per frame.
4. **API:** no blocker. It must express length-prefixed lists and bounded counts. Versioning and unknown-field skipping are MeshLink's own rules; the API must not prevent them.

Otherwise the recommendation is the hand-written codec.

## Placeholder frames

Not the spec. Value ranges are chosen so that the bit-packed widths are honest:

| Field | Range | A | B |
|---|---|---|---|
| version | must be 1 | 1 B | 3 bits |
| frame type | 1..8 | 1 B | 5 bits |
| hop limit | 0..255 | 1 B | 8 bits |
| lifetime (remaining seconds) | 120..4095 | 2 B | 12 bits |
| payload length | 0..16383 | 2 B | 14 bits |
| routing tag | 8 B | 8 B | 64 bits |
| message ID | 12 B | 12 B | 96 bits |

Frames: routed unicast, mesh broadcast (optional 64 B Ed25519 signature), fragment, delivery receipt, Plumtree IHAVE (1..32 IDs), graft, catch-up digest (0..512 IDs), handshake-payload envelope (optional 32 B resolving key), and the 16 B advertisement metadata. Exact layouts are in [`Layout.md`](Layout.md).

Unused bits in B (padding, reserved flags) must be zero; a decoder rejects non-zero ones. That keeps every valid encoding canonical, so a decode followed by an encode reproduces the input.

## Method

- **Sizes:** computed from the encoders on the sample frames and cross-checked against the layout tables.
- **Safety:** `fuzz` on the JVM decodes every truncated prefix, an over-long input and 1,000,000 mutated inputs per frame type (seed `0x4D455348`) through A, B and C. It counts uncaught throwables, and checks that B and C agree and that every accepted B input re-encodes to the same bytes.
- **Throughput:** a JMH-free loop: warm up, then 15 batches of timed iterations per (codec, frame, direction); the median ns/op is reported. JVM: JDK 21 on the Mac. Android: the same JVM bytecode dexed with `d8` and run with `app_process` on a lab phone. iOS: a Kotlin/Native release framework in a minimal app on a lab iPhone, if it fits the time budget; otherwise the simulator, clearly labeled as not device evidence.
- **Allocation:** JVM `ThreadMXBean.getThreadAllocatedBytes` around many decodes, divided by the count; objects inferred from the bytes and the code.
- **Ergonomics:** lines of code per frame for B and C; Kompact's license, version and transitive dependencies; bugs found.

## Deviations

None yet.
