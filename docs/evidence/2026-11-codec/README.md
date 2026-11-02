# Wire layout and codec: evidence

- **Date:** 2026-11-02, 20:07–20:25 CET.
- **Ticket:** `.scratch/meshlink-v1/issues/18-wire-format-codec.md`
- **Decisions it feeds:** [ADR 0012](../../adr/0012-wire-format-and-codec.md). Context: [ADR 0002](../../adr/0002-noise-suites-and-handshake-patterns.md), [ADR 0005](../../adr/0005-toolchain-and-dependency-baseline.md), [ADR 0008](../../adr/0008-routing-topology-and-density-adaptation.md), [ADR 0010](../../adr/0010-discovery-and-advertising.md).
- **Code:** throwaway prototype on branch `prototype/codec`, under `prototypes/codec/` (pre-registration, layouts, code, raw results). It is never merged.

The frames measured here are **placeholders**, not the MeshLink wire format. The protocol spec stays the authority on bytes; this folder only compares layouts and codec implementations on plausible shapes.

## Evidence labels

| Label | Meaning |
|---|---|
| **[RUN]** | Measured in the named run. The raw output is in `prototypes/codec/results/` on branch `prototype/codec`. |
| **[SRC]** | Read in the Kompact 0.10.0 source (tag `v0.10.0`, commit `64e849f`) or its POM, or hit while writing codec C. |
| **[UNVERIFIED]** | A likely explanation that no run proves. |

## 1. Question

1. **Layout.** Does a bit-packed layout (LSB-first, sub-byte fields) save meaningful bytes over a byte-aligned layout for MeshLink's frames?
2. **Codec.** For the same bit-packed layout, is Kompact (`ch.trancee.kompact:kompact` 0.10.0, runtime only, no KSP) better than a hand-written encoder and decoder?

## 2. Setup

### Implementations

- **A:** byte-aligned, hand-written. Every field starts and ends on a byte boundary; single-bit booleans may share a flags byte; a multi-bit numeric field never shares a byte.
- **B:** bit-packed, hand-written. Sub-byte fields sit in a leading little-endian header word, LSB-first. Byte strings (tags, message IDs, keys, signatures) follow byte-aligned.
- **C:** B's layout through Kompact's `KompactCursor`. One cursor and one `KompactByteRange` are reused per codec instance.

All three return a typed result: a frame, or one of `TRUNCATED`, `TRAILING_BYTES`, `UNSUPPORTED_VERSION`, `UNKNOWN_TYPE`, `OUT_OF_RANGE`, `RESERVED_BITS_SET`. Reserved bits must be zero, so every accepted B or C encoding is canonical.

### Placeholder frames

Exact layouts are in `prototypes/codec/Layout.md` on the prototype branch. Field ranges: version 1, frame type 1..8, hop limit 0..255, lifetime 120..4095 s, payload length 0..16383, routing tag 8 B, message ID 12 B ([ADR 0008](../../adr/0008-routing-topology-and-density-adaptation.md)).

Frames: routed unicast, mesh broadcast (optional 64 B Ed25519 signature), fragment, delivery receipt, Plumtree IHAVE (1..32 IDs), graft, catch-up digest (0..512 IDs), handshake-payload envelope (optional 32 B resolving key), and the 16 B advertisement metadata ([ADR 0010](../../adr/0010-discovery-and-advertising.md)). The advertisement holds a format version, epoch low bits, capability flags, a density class, a 4 B mesh tag and an 8 B resolvable tag.

### Platforms

| Platform | Details |
|---|---|
| JVM | OpenJDK 21.0.13 (HotSpot server VM), Apple M1, macOS 27.0.1 |
| XCover 4 | Samsung SM-G390F, Android 9 (API 28), 32-bit `armeabi-v7a` user space, `samsung/xcover4ltexx/xcover4lte:9/PPR1.180610.011/G390FXXU6CTG3` |
| Pixel 6 | Android 17 (API 37), `google/oriole/oriole:17/CP3A.261005.002.A1/16269273` |
| iPhone 15 | iOS 17.6.1, Kotlin/Native 2.4.21 release framework in a SwiftUI shell, bundle `ch.trancee.lab.codecbench`, free team `apple-team-1` |

### Software

Kotlin 2.4.21, Gradle 9.7.1, Xcode 16.4 ([ADR 0005](../../adr/0005-toolchain-and-dependency-baseline.md)). Kompact 0.10.0 from Maven Central. Android build-tools 37.0.0 `d8`.

## 3. Method

The criteria were pre-registered in `prototypes/codec/README.md` (commit `59455ca`, 20:07 CET) before any code or measurement.

| Metric | How |
|---|---|
| Sizes | Encode every sample with A, B and C; compare B and C bytes. |
| Decode safety | Every truncated prefix of every sample; the sample plus 1 zero byte and plus 32 random bytes; 1,000,000 mutated inputs per frame type, seed `0x4D455348` mixed with the sample name. Every input is an exact-size array, so an out-of-bounds read throws and is counted. |
| Fuzz checks | Uncaught throwables; B and C results must agree; every accepted input must re-encode to the same bytes through B and C. |
| Throughput | JMH-free loop: batches of about 40 ms, 5 warm-up rounds, 15 timed rounds, A, B and C interleaved; median ns/op. |
| Allocation | JVM `ThreadMXBean.getThreadAllocatedBytes` over 2,000,000 ops after 2,000,000 warm-up ops. |
| Ergonomics | `scripts/loc.py`: non-blank, non-comment lines per frame function. |

Mutations: bit flips biased to the header, random header bytes, truncation, extension, byte insert or delete, fully random inputs, and random inputs that keep the first byte.

### Pre-registered criteria (summary)

- **Layout:** worth it only if B saves **≥ 4 B on the routed unicast header** or **≥ 2 B of usable space in the 16 B advertisement**.
- **Codec:** adopt Kompact only if **all** hold:
  1. decode safety: zero uncaught exceptions, zero out-of-bounds reads, all malformed input rejected as a typed error;
  2. throughput ≥ 0.5× of B on the JVM, one Android phone and one iPhone;
  3. allocations per decode not worse than B by more than 1 object;
  4. no API blocker.

  Otherwise, the hand-written codec.

## 4. Results

### Sizes [RUN `jvm-sizes.csv`; same on the XCover 4 and the iPhone 15]

| Sample | A (bytes) | B (bytes) | Saving | B = C bytes |
|---|---|---|---|---|
| unicast header | 36 | 34 | 2 | yes |
| unicast + 1 B extension + 40 B payload | 80 | 78 | 2 | yes |
| broadcast + signature | 92 | 90 | 2 | yes |
| broadcast, unsigned | 28 | 26 | 2 | yes |
| fragment + 20 B data | 39 | 37 | 2 | yes |
| receipt | 16 | 15 | 1 | yes |
| IHAVE, 1 / 8 / 32 IDs | 16 / 100 / 388 | 15 / 99 / 387 | 1 | yes |
| graft | 15 | 14 | 1 | yes |
| digest, 0 / 10 / 100 IDs | 6 / 126 / 1206 | 4 / 124 / 1204 | 2 | yes |
| handshake + key | 40 | 38 | 2 | yes |
| handshake, no key | 8 | 6 | 2 | yes |
| advertisement | 16 | 16 | 0 B, but 19 spare bits | yes |

The saving is a constant 1–2 B per frame: the bytes are dominated by tags, IDs, keys and signatures, which both layouts store whole. In the advertisement, A spends 4 bytes on 13 bits of fields and has no room left; B packs them into 13 bits and leaves 19 bits (2 B plus 3 bits) for random fill or new fields.

### Decode safety [RUN]

| Run | Inputs per type | Exceptions | B/C disagreements | Non-canonical accepts |
|---|---|---|---|---|
| Truncation, JVM (`jvm-truncation.csv`) | every prefix of 16 samples, A, B and C | 0 | — | — |
| Fuzz, JVM (`jvm-fuzz.csv`) | about 1,000,000 (9 types) | 0 (A, B, C) | 0 | 0 |
| Fuzz, XCover 4 (`android-xcover4-fuzz.csv`) | about 1,000,000 (9 types) | 0 (A, B, C) | 0 | 0 |
| Fuzz and truncation, iPhone 15 (`ios-iphone15.txt`) | about 100,000 (9 types) | 0 (A, B, C) | 0 | 0 |

- Every truncated prefix returned a typed error, except 19 fragment prefixes past the header, which are valid by design (the data length is implicit). Over-long inputs returned `TRAILING_BYTES`, or `OUT_OF_RANGE` for the fragment (offset + size > 16384).
- The accept and error counts are identical on the JVM, ART and Kotlin/Native for the same seed and iteration count.
- C's safety partly comes from the codec author: C calls `ensureAvailable` for the whole header before reading, and checks every status. Kompact itself returns a status for out-of-range reads instead of throwing [SRC].

### Throughput, C relative to B [RUN `ratios.csv`]

Cells: B ns/op / C ns/op (**B ÷ C**; 0.5 means C runs at half of B's speed). XCover 4 "JIT" is the plain `app_process` run; "AOT" ran after `dex2oat --compiler-filter=speed`. An interpreter-only run (`dalvikvm -Xint`) took about 36,000 ns per B unicast decode, so both reported runs executed compiled code.

Decode:

| Frame | JVM | XCover 4 JIT | XCover 4 AOT | Pixel 6 | iPhone 15 |
|---|---|---|---|---|---|
| advert | 9.2 / 25.7 (**0.36**) | 509.5 / 1711.6 (**0.30**) | 572.6 / 1991.8 (**0.29**) | 45.2 / 160.6 (**0.28**) | 17.8 / 99.7 (**0.18**) |
| broadcast+sig | 17.6 / 38.9 (**0.45**) | 1519.9 / 3149.8 (**0.48**) | 1618.8 / 3867.7 (**0.42**) | 117.0 / 243.1 (**0.48**) | 59.4 / 147.3 (**0.40**) |
| digest10 | 83.6 / 114.1 (**0.73**) | 2813.8 / 6686.5 (**0.42**) | 2942.1 / 7797.0 (**0.38**) | 222.6 / 546.5 (**0.41**) | 159.0 / 353.5 (**0.45**) |
| fragment+data20 | 11.3 / 27.1 (**0.42**) | 614.3 / 1895.4 (**0.32**) | 684.6 / 2282.8 (**0.30**) | 61.2 / 192.0 (**0.32**) | 30.2 / 102.0 (**0.30**) |
| graft | 11.3 / 21.0 (**0.54**) | 432.2 / 1258.5 (**0.34**) | 500.2 / 1567.2 (**0.32**) | 32.9 / 102.9 (**0.32**) | 26.3 / 74.4 (**0.35**) |
| handshake+key | 12.9 / 26.0 (**0.50**) | 594.5 / 1772.7 (**0.34**) | 730.6 / 2153.5 (**0.34**) | 70.8 / 178.0 (**0.40**) | 33.1 / 106.5 (**0.31**) |
| ihave8 | 47.5 / 90.7 (**0.52**) | 2283.0 / 5403.9 (**0.42**) | 2388.7 / 6484.8 (**0.37**) | 190.3 / 488.5 (**0.39**) | 64.9 / 288.0 (**0.23**) |
| receipt | 15.1 / 22.9 (**0.66**) | 526.1 / 1633.3 (**0.32**) | 613.4 / 2027.7 (**0.30**) | 46.4 / 164.7 (**0.28**) | 30.4 / 92.7 (**0.33**) |
| unicast | 12.9 / 37.0 (**0.35**) | 1041.4 / 2580.9 (**0.40**) | 1151.8 / 3253.6 (**0.35**) | 79.9 / 210.7 (**0.38**) | 49.9 / 128.7 (**0.39**) |

Encode:

| Frame | JVM | XCover 4 JIT | XCover 4 AOT | Pixel 6 | iPhone 15 |
|---|---|---|---|---|---|
| advert | 18.1 / 57.1 (**0.32**) | 686.5 / 2542.8 (**0.27**) | 825.2 / 2980.6 (**0.28**) | 70.2 / 255.5 (**0.27**) | 27.8 / 124.7 (**0.22**) |
| broadcast+sig | 16.4 / 100.5 (**0.16**) | 1164.1 / 4326.4 (**0.27**) | 1294.0 / 5104.7 (**0.25**) | 66.1 / 341.9 (**0.19**) | 28.6 / 180.7 (**0.16**) |
| digest10 | 35.7 / 262.7 (**0.14**) | 2454.3 / 10555.1 (**0.23**) | 2593.6 / 11953.9 (**0.22**) | 166.2 / 918.8 (**0.18**) | 69.3 / 466.3 (**0.15**) |
| fragment+data20 | 13.3 / 66.9 (**0.20**) | 714.3 / 3078.4 (**0.23**) | 849.2 / 3720.7 (**0.23**) | 77.1 / 346.0 (**0.22**) | 25.8 / 142.0 (**0.18**) |
| graft | 13.4 / 42.0 (**0.32**) | 601.8 / 2030.3 (**0.30**) | 727.1 / 2432.5 (**0.30**) | 48.4 / 161.7 (**0.30**) | 23.9 / 96.5 (**0.25**) |
| handshake+key | 18.1 / 48.5 (**0.37**) | 655.1 / 2567.4 (**0.26**) | 804.8 / 3146.8 (**0.26**) | 59.0 / 232.5 (**0.25**) | 28.2 / 128.7 (**0.22**) |
| ihave8 | 28.8 / 200.9 (**0.14**) | 1971.5 / 8493.0 (**0.23**) | 2142.9 / 9672.3 (**0.22**) | 121.4 / 666.0 (**0.18**) | 56.9 / 377.2 (**0.15**) |
| receipt | 11.1 / 48.9 (**0.23**) | 586.9 / 2509.3 (**0.23**) | 716.3 / 2970.4 (**0.24**) | 49.0 / 203.6 (**0.24**) | 21.8 / 117.4 (**0.19**) |
| unicast | 14.0 / 111.5 (**0.13**) | 910.8 / 4264.9 (**0.21**) | 1008.1 / 4940.7 (**0.20**) | 58.1 / 310.7 (**0.19**) | 26.7 / 179.4 (**0.15**) |

- **B versus A** (`ratios.csv`, last column): bit-packing by hand costs little. B decode takes 0.68–1.47× A's time and B encode 0.93–1.57×, over all frames and platforms.
- **Why C is slower** is [UNVERIFIED]: likely one status-returning call per field (each updates cursor state) instead of one 64-bit header load and inline shifts. A Kompact expert might write a faster C; this prototype used the public cursor API as documented.
- **XCover 4** absolute times are about 80× the JVM's. It runs 32-bit ARM code, where the `Long` arithmetic both codecs use is costly [UNVERIFIED]. Its AOT run was 5–27% slower than its JIT run on every row; the cause is [UNVERIFIED].

### Allocation [RUN `jvm-alloc.csv`, `jvm-alloc-noEA.csv`]

| Frame | A, B, C decode (bytes/op) | Encode (bytes/op) |
|---|---|---|
| unicast | 72 | 0 |
| broadcast+sig | 152 | 0 |
| fragment+data20 | 48 | 0 |
| receipt, graft | 32 | 0 |
| ihave8 | 152 | 0 |
| digest10 | 184 | 0 |
| handshake+key | 80 | 0 |
| advert | 40 | 0 |

A, B and C allocate the same bytes for every frame, also with escape analysis off (`-XX:-DoEscapeAnalysis`). C reaches parity only by reusing one cursor and one byte range per codec instance. That makes the C codec stateful and not thread-safe, while B is a stateless object. A cursor per call would add at least one `KompactCursor` per decode, which is the 1-object limit [UNVERIFIED, not measured]. ART and Kotlin/Native allocation were not measured.

### API and ergonomics [SRC, RUN `loc.csv`]

| Frame | B lines | C lines |
|---|---|---|
| unicast | 44 | 43 |
| broadcast | 54 | 49 |
| fragment | 21 | 23 |
| receipt | 15 | 19 |
| IHAVE | 26 | 26 |
| graft | 11 | 11 |
| digest | 32 | 29 |
| handshake | 22 | 22 |
| advertisement | 19 | 25 |
| whole codec file | 284 | 310 |

Kompact findings, all [SRC]:

- **License and dependencies:** The Unlicense (POM); one runtime dependency, `kotlin-stdlib` 2.4.21. Pre-1.0. Published targets: Android, `androidNativeArm64`, `iosArm64`, `iosSimulatorArm64`, JVM; **no macOS target**.
- **Length-prefixed lists:** `readFixedRepeat` and `readVariableRepeat` take count prefixes of 8, 16 or 32 bits only, placed directly before the elements, with elements of 1..64 bits. They cannot express a 6-bit count followed by another field and then 96-bit message IDs. C uses a manual loop with `readBits`. Not a blocker, but the repeat API gives nothing here.
- **Byte strings:** `readByteRange` and `writeByteRange` need an 8, 16 or 32-bit length prefix. There is no fixed-length unprefixed byte-string read; C uses `skipBits` plus the cursor position. Raw writes use `writeRawByteRange`.
- **Versioning and unknown fields:** none in the runtime (Kompact's own ADR-0002 defers it). MeshLink defines its own; C skipped the extension block with `readByteRange(8, …)`. Not a blocker.
- **Pitfall, not a bug:** `writeUnsigned(64, value: Long)` returns `INVALID_VALUE` for negative `Long` values; 64-bit fields need the `ULong` overload.
- **Error model:** input errors come back as a status; caller-contract errors (bad widths) throw `IllegalArgumentException`. `KompactFrame` uses exceptions internally and allocates frame and result objects, so C does not use it.
- **Bugs:** none found. No run produced an exception, a wrong status or a B/C mismatch.

## 5. Criteria verdict

| Criterion | Result | Verdict |
|---|---|---|
| Layout: unicast saving ≥ 4 B | 2 B (36 → 34) | **fail** |
| Layout: advertisement usable space ≥ 2 B | 19 bits (2 B + 3 bits) | **pass** |
| Codec 1: decode safety | 0 exceptions, 0 disagreements, all malformed inputs typed; 1,000,000 per type on the JVM and the XCover 4 | **pass** |
| Codec 2: throughput ≥ 0.5× of B | Device decode 0.18–0.48, device encode 0.15–0.30; JVM decode 0.35–0.73, encode 0.13–0.37 | **fail** on every platform |
| Codec 3: allocation ≤ B + 1 object | Equal bytes per decode on the JVM, with a reused cursor | **pass** (JVM only) |
| Codec 4: no API blocker | Lists, bounded counts and extension skipping all expressible, with manual loops | **pass** |

- **Layout:** the pre-registered bar is met, through the advertisement alone. Bit-packing frame headers saves only 1–2 B per frame. In the 16 B advertisement, packing its 13 bits of sub-byte fields frees 19 bits; that can be done by hand in one header word without bit-packing every frame.
- **Codec:** Kompact fails criterion 2, so by the pre-registration the recommendation is the **hand-written codec**.

## 6. Deviations

All were logged in `prototypes/codec/README.md`.

- **Kompact source:** Maven Central 0.10.0; the local Maven repository had only `0.3.0-SNAPSHOT`.
- **Android artifact:** the JVM fat jar (`kompact-jvm`) dexed with `d8` and run through `app_process`, not an APK against `kompact-android`. Same common code.
- **iOS:** iPhone 15 instead of the SE 2016, with a new bundle ID so the bake-off app stays installed. The iPhone ran 100,000 fuzz inputs per type, not 1,000,000; the 1,000,000 criterion is met on the JVM and the XCover 4.
- **No Kotlin/Native macOS host numbers:** Kompact 0.10.0 has no macOS target.
- **Criterion 2 reading:** applied per frame and per direction. No reading changes the verdict: the best device ratio is 0.48.
- **Allocation unit:** bytes per op, not objects; equal bytes are taken as equal objects.

## 7. Limitations and unverified items

- **Placeholder frames.** The real layouts may have different sub-byte fields. The result depends on byte strings dominating the frames, which [ADR 0008](../../adr/0008-routing-topology-and-density-adaptation.md) makes likely (8 B tags, 12 B IDs).
- **One author wrote B and C.** A differently written C might be faster. The gap (2–8×) is large compared with the 2× bar.
- **Microbenchmark, not JMH.** Medians of 15 rounds; run-to-run variation was measured only on the XCover 4 (JIT versus AOT, 5–27%).
- **Allocation** was measured only on HotSpot.
- **One iPhone, one run.** iPhone 15 only; the SE 2016 and iOS 15 were not tested.
- **Fuzzing** is a deterministic mutation loop, not coverage-guided.
- **Not studied:** encode-side safety against invalid frames (all encoders call `validate()` first), multithreaded use, code size.

## 8. Raw data

Copied into [`raw/`](raw/) from `prototypes/codec/results/` on branch `prototype/codec` (commit `5564128`):

| File | Contents |
|---|---|
| `jvm-sizes.csv` | per-sample sizes for A, B, C and B = C check |
| `jvm-truncation.csv` | prefix and over-long results per sample and codec |
| `jvm-fuzz.csv`, `android-xcover4-fuzz.csv` | 1,000,000 per type: inputs, accepts, exceptions, disagreements, error counts |
| `jvm-bench.csv`, `android-xcover4-bench.csv`, `android-xcover4-bench-aot.csv`, `android-pixel6-bench.csv` | median and minimum ns/op |
| `ios-iphone15.txt` | sizes, truncation, 100,000 fuzz per type and benchmark from the iPhone 15 |
| `jvm-alloc.csv`, `jvm-alloc-noEA.csv` | bytes per op, with and without escape analysis |
| `ratios.csv`, `loc.csv` | derived by `scripts/ratios.py` and `scripts/loc.py` |

## 9. How to reproduce

The iOS profile from the free team expires around 2026-11-07.

```sh
git worktree add ../MeshLink-codec-proto prototype/codec
cd ../MeshLink-codec-proto/prototypes/codec
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew jvmTest iosSimulatorArm64Test benchJar
J=build/libs/codec-bench.jar
for m in sizes truncation "fuzz 1000000 0x4D455348" "bench 15" alloc; do
  $JAVA_HOME/bin/java -cp $J meshlink.proto.codec.JvmMainKt $m
done
$JAVA_HOME/bin/java -XX:-DoEscapeAnalysis -cp $J meshlink.proto.codec.JvmMainKt alloc

# Android (XCover 4 shown; the Pixel 6 is pixel6-wifi:5555)
export ADB_SERVER_SOCKET=tcp:adb-server:5037
S=xcover4
~/Library/Android/sdk/build-tools/37.0.0/d8 --release --min-api 28 --output build/dex $J
(cd build/dex && zip -q -j ../codec-bench-dex.jar classes.dex)
adb -s $S push build/codec-bench-dex.jar /data/local/tmp/meshlink-codec-bench.jar
adb -s $S shell 'CLASSPATH=/data/local/tmp/meshlink-codec-bench.jar app_process / meshlink.proto.codec.JvmMainKt bench 15'
adb -s $S shell 'CLASSPATH=/data/local/tmp/meshlink-codec-bench.jar app_process / meshlink.proto.codec.JvmMainKt fuzz 1000000 0x4D455348'

# iOS: see prototypes/codec/README.md, "How to run"
python3 scripts/ratios.py results/*bench*.csv results/ios-*.txt
python3 scripts/loc.py
```

## 10. Evidence record (Constitution T9)

| Field | Value |
|---|---|
| Devices and OS | Mac (Apple M1, macOS 27.0.1, JDK 21.0.13); Samsung XCover 4 SM-G390F, Android 9 (API 28); Pixel 6, Android 17 (API 37); iPhone 15, iOS 17.6.1 |
| Firmware | XCover 4 `G390FXXU6CTG3`; Pixel 6 `CP3A.261005.002.A1`; iPhone 15 iOS 17.6.1 |
| Covered platforms | HotSpot JVM; ART (JIT and `dex2oat` AOT) on 32-bit and 64-bit ARM; Kotlin/Native `iosArm64` release |
| Not covered | iOS 15/16 devices; Kotlin/Native macOS; Android allocation; an APK built against `kompact-android`; real (non-placeholder) frames |
| Commit | Branch `prototype/codec`: pre-registration `59455ca`, code and results `5564128`; Kompact `v0.10.0` (`64e849f`) |
| Method | Pre-registered criteria in `prototypes/codec/README.md`; see [Method](#3-method) |
| Raw results | [`raw/`](raw/), copied from `prototypes/codec/results/` on branch `prototype/codec` |
| Date | 2026-11-02 20:07–20:25 CET |
