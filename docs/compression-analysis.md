# MeshLink — Compression Algorithm Analysis

Analysis of five compression RFCs for MeshLink's BLE mesh messaging use case.

## Context

MeshLink compresses payloads before encryption (compress-then-encrypt). Each
message is compressed independently — no shared dictionary across messages.
Typical payloads range from 50 bytes to 100 KB, with the majority under 1 KB
(chat messages, sensor readings). The library targets Android, iOS, macOS,
Linux, and JVM with zero external dependencies.

**Current implementation:** raw DEFLATE (RFC 1951) via platform-native APIs.

## RFCs Evaluated

| RFC | Algorithm | Year | Core technique |
|-----|-----------|------|----------------|
| 1951 | DEFLATE | 1996 | LZ77 + Huffman coding |
| 1950 | zlib | 1996 | DEFLATE + Adler-32 wrapper |
| 1952 | gzip | 1996 | DEFLATE + CRC-32 wrapper |
| 7932 | Brotli | 2016 | LZ77 + Huffman + 120 KB static dictionary |
| 8878 | Zstandard | 2021 | LZ77 + FSE (Finite State Entropy) |

## Comparison

### Overhead per message

| Algorithm | Min header | Checksum | Total overhead | Notes |
|-----------|-----------|----------|----------------|-------|
| Raw DEFLATE | ~3 bits | None | < 1 byte | No integrity check |
| zlib | 2 bytes | 4 B Adler-32 | 6 bytes | Redundant with AEAD |
| gzip | 10 bytes | 8 B (CRC-32 + size) | 18 bytes | Wasteful for BLE |
| Brotli | 1–4 bytes | None | 1–4 bytes | Low overhead |
| Zstandard | 4 B magic + hdr | Optional 4 B xxHash | 9+ bytes | Frame-oriented |

### Compression ratio

| Algorithm | Text (> 1 KB) | Text (< 1 KB) | Binary | Incompressible |
|-----------|---------------|---------------|--------|----------------|
| DEFLATE/zlib | 2.5–3× | Poor (tree overhead dominates) | 1.5–2× | ~1.0× (slight expansion) |
| gzip | 2.5–3× | Poor | 1.5–2× | ~1.0× |
| Brotli | 3–5× | Good (static dictionary) | 1.5–2.5× | ~1.0× |
| Zstandard | 3–4× | Moderate | 2–3× | ~1.0× |

### Performance characteristics

| Algorithm | Compress speed | Decompress speed | Encoder memory | Decoder memory |
|-----------|---------------|-----------------|----------------|----------------|
| DEFLATE/zlib | Fast | Fast | ~32 KB | ~32 KB |
| gzip | Fast | Fast | ~32 KB | ~32 KB |
| Brotli (level 5) | Slow | Fast | 8–15 MB | 1–2 MB |
| Zstandard (level 3) | Fast | **Fastest** (2–10× vs zlib) | ~1 MB | ~1 MB |

### Platform availability (native, zero-dependency)

| Algorithm | Android | iOS/macOS | Linux | JVM |
|-----------|---------|-----------|-------|-----|
| DEFLATE/zlib | ✅ `java.util.zip` | ✅ `NSData` zlib | ✅ libz | ✅ `java.util.zip` |
| gzip | ✅ `java.util.zip` | ⚠️ partial | ✅ libz | ✅ `java.util.zip` |
| Brotli | ❌ JNI required | ❌ not in Foundation | ❌ libbrotli | ❌ external lib |
| Zstandard | ❌ JNI required | ❌ not in Foundation | ❌ libzstd | ❌ external lib |

Apple's `NSData.CompressionAlgorithm` also natively supports LZFSE (Apple-only,
faster than zlib) and LZ4 (ultra-fast, poor ratio) — but neither is available
on Android/JVM/Linux without external libraries.

## Decision

**Keep DEFLATE (switch from zlib wrapper to raw DEFLATE).**

### Rationale

1. **Zero external dependencies** — Brotli and Zstandard both require native C
   libraries on all 4 platform groups. This violates MeshLink's zero-dependency
   constraint and would add ~500 KB of native binaries per platform.

2. **AEAD makes checksums redundant** — Every payload is encrypted with
   ChaCha20-Poly1305 (AEAD), which provides integrity verification. The zlib
   Adler-32 checksum is redundant — switching to raw DEFLATE saves 6 bytes per
   compressed message.

3. **Small payloads limit algorithmic advantage** — Brotli's 20–30% better
   ratio comes from its 120 KB static dictionary (English/HTML words). For
   arbitrary binary payloads under 1 KB, the advantage shrinks to < 10%.
   Zstandard's speed advantage matters less when payloads are small.

4. **Encoder memory** — Brotli's encoder uses 8–15 MB, unsuitable for
   battery-constrained mobile devices. Zstandard's encoder at low levels uses
   ~1 MB, acceptable but unnecessary when DEFLATE uses only ~32 KB.

### Optimizations applied

| Change | Benefit |
|--------|---------|
| Raw DEFLATE (drop zlib wrapper) | −6 bytes per compressed message |
| Raise `compressionMinBytes` 64 → 128 | Avoid negative compression on small payloads |
| Use BEST_SPEED (level 1) | 2–3× faster compression, ~5% worse ratio |
| Reuse Deflater/Inflater instances | ~10% throughput improvement, fewer native allocations |

### Alternatives considered but rejected

- **Brotli** — Best compression ratio but requires external library on all
  platforms and encoder memory is prohibitive for mobile.
- **Zstandard** — Best decompression speed but requires external library.
  Frame overhead (9+ bytes) is larger than raw DEFLATE for small messages.
- **gzip** — Same DEFLATE algorithm but 18 bytes of mandatory overhead.
  Strictly worse than zlib/raw DEFLATE for BLE.
- **LZFSE** — Apple-only natively. Pure Kotlin port is feasible (~5,400 LOC)
  but encoder needs 685 KB scratch memory (21× DEFLATE) and small payloads
  fall back to LZVN which lacks entropy coding. See [LZFSE Deep-Dive](#lzfse-deep-dive).
- **LZ4** — Ultra-fast but poor compression ratio. Not worth the 1-byte
  envelope overhead when most messages would not compress meaningfully.

## LZFSE Deep-Dive

LZFSE (Lempel-Ziv Finite State Entropy) is Apple's open-source compression
algorithm, released in 2016. It combines LZ77 with tANS-based entropy coding.
The [reference implementation](https://github.com/lzfse/lzfse) is 5,396 lines
of pure C99 with no SIMD or inline assembly.

### Architecture

LZFSE is a two-tier system:

| Tier | Used when | Technique | Encoder memory |
|------|-----------|-----------|----------------|
| **LZVN** | Payload < 4 KB | LZ77 with hash table, no entropy coding | ~256 KB |
| **LZFSE** | Payload ≥ 4 KB | LZ77 + FSE (Finite State Entropy) | **~685 KB** |

The FSE component is a Finite State Entropy coder based on the tANS (table-based
Asymmetric Numeral Systems) algorithm. It approaches arithmetic coding efficiency
with table-lookup speed: O(1) per symbol decode via branch-free state transitions.
Four independent literal streams plus separate L/M/D (literal-length, match-length,
distance) streams are entropy-coded per block.

### Memory requirements

| Component | Size | Notes |
|-----------|------|-------|
| Encoder hash table | 512 KB | 16K entries × 8 bytes × 4 positions |
| Encoder L/M/D arrays | 120 KB | 3 × 40 KB per-match metadata |
| Encoder literal buffer | 40 KB | Raw literals staging |
| **Encoder total** | **~685 KB** | 21× more than DEFLATE's 32 KB |
| Decoder literals | 40 KB | Literals buffer |
| Decoder FSE tables | 7 KB | 64+64+256+1024 state entries |
| **Decoder total** | **~47 KB** | Comparable to DEFLATE's 32 KB |

### Portability assessment

The reference implementation is highly portable:

- Pure C99 — no platform-specific operations
- No SIMD, no inline assembly, no threading
- Endian-agnostic (memcpy for loads/stores)
- Uses `__builtin_clz`/`__builtin_ctzl` (Kotlin equivalents: `countLeadingZeroBits`/`countTrailingZeroBits`)
- LZVN decoder uses GCC labels-as-values for jump tables (replaceable with `when` expressions)

A pure Kotlin port is technically feasible with an estimated effort of 14–18 weeks.

### Why LZFSE is not suitable for MeshLink

1. **Encoder memory is prohibitive.** 685 KB scratch space is 21× what DEFLATE
   needs. MeshLink's `minimalOverhead` preset targets sensor devices with 64 KB
   total buffer capacity — LZFSE's encoder alone exceeds that by 10×.

2. **Wrong payload sweet spot.** MeshLink's majority traffic is under 1 KB (chat,
   sensor data). Below 4 KB, LZFSE falls back to LZVN — a plain LZ77 variant
   without entropy coding. DEFLATE's Huffman coding provides better compression
   ratios than LZVN for these sizes.

3. **Massive effort for marginal gain.** LZFSE only outperforms DEFLATE on
   payloads above 4 KB where FSE entropy coding activates. Large messages are
   the minority of MeshLink traffic. A 14–18 week port would improve compression
   on perhaps 10–20% of real-world messages.

4. **Maintenance burden.** A 5,400-line custom compressor requires ongoing
   maintenance, security review, and cross-platform testing. DEFLATE uses
   battle-tested platform libraries with near-zero MeshLink-side code.

5. **BLE is the bottleneck, not compression.** Chunks transfer at 168 bytes over
   BLE with connection intervals of 7.5–4,000 ms. Even a 50% ratio improvement
   on a 10 KB payload saves ~30 chunks, but radio scheduling dominates latency.

6. **LZVN-only port considered.** Porting only LZVN (~1,300 LOC) would target
   small payloads, but it still needs 256 KB hash tables and lacks entropy
   coding — unlikely to beat DEFLATE at BEST_SPEED for MeshLink's payload sizes.

### Comparison summary

| | DEFLATE (current) | LZFSE/LZVN |
|---|---|---|
| Encoder memory | ~32 KB | ~685 KB (21×) |
| Decoder memory | ~32 KB | ~47 KB |
| Code to maintain | 0 (platform-native) | ~5,400 LOC Kotlin port |
| Dependencies | Zero (all platforms) | Zero (if pure Kotlin) |
| Small payload (< 1 KB) | Good (Huffman) | LZVN only (no entropy — worse ratio) |
| Large payload (> 4 KB) | Good | Better (FSE ≈ arithmetic coding) |
| Platform availability | ✅ All platforms natively | ❌ Apple only (native), others need port |

**Verdict:** LZFSE is not a net improvement for MeshLink. The 685 KB memory
footprint disqualifies it for constrained BLE devices, the small-payload
performance is worse than DEFLATE, and the engineering effort is disproportionate
to the benefit.

## Wire envelope format

Unchanged — the envelope prefix scheme is independent of the compression
algorithm:

```
Uncompressed: [0x00][raw payload...]
Compressed:   [0x01][originalSize: 4 bytes LE][DEFLATE stream...]
```

The only change is that the compressed data is now a raw DEFLATE stream
(RFC 1951) instead of a zlib-wrapped stream (RFC 1950). This is a breaking
wire format change — peers running the old zlib format cannot decompress
raw DEFLATE payloads. Protocol version negotiation handles this.
