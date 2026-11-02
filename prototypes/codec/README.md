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

## How to run

Requires JDK 21 (the build uses `jvmToolchain(21)`), Xcode 16.4 and xcodegen for iOS, and the Android SDK build-tools for `d8`. Kompact 0.10.0 comes from Maven Central.

```sh
cd prototypes/codec
./gradlew jvmTest iosSimulatorArm64Test         # B==C bytes, round trips, truncation, short fuzz
./gradlew benchJar                              # build/libs/codec-bench.jar (fat jar)
J=build/libs/codec-bench.jar
java -cp $J meshlink.proto.codec.JvmMainKt sizes        > results/jvm-sizes.csv
java -cp $J meshlink.proto.codec.JvmMainKt truncation   > results/jvm-truncation.csv
java -cp $J meshlink.proto.codec.JvmMainKt fuzz 1000000 0x4D455348 > results/jvm-fuzz.csv
java -cp $J meshlink.proto.codec.JvmMainKt bench 15     > results/jvm-bench.csv
java -cp $J meshlink.proto.codec.JvmMainKt alloc        > results/jvm-alloc.csv
python3 scripts/loc.py > results/loc.csv
python3 scripts/ratios.py results/*bench*.csv results/ios-*.txt > results/ratios.csv
```

Android (remote adb lab; `S` is the device serial):

```sh
export ADB_SERVER_SOCKET=tcp:adb-server:5037
d8 --release --min-api 28 --output build/dex build/libs/codec-bench.jar
(cd build/dex && zip -q -j ../codec-bench-dex.jar classes.dex)
adb -s $S push build/codec-bench-dex.jar /data/local/tmp/meshlink-codec-bench.jar
adb -s $S shell 'cd /data/local/tmp && mkdir -p oat/arm && dex2oat --dex-file=meshlink-codec-bench.jar \
  --oat-file=oat/arm/meshlink-codec-bench.odex --instruction-set=arm --compiler-filter=speed'   # optional AOT
adb -s $S shell 'CLASSPATH=/data/local/tmp/meshlink-codec-bench.jar app_process / meshlink.proto.codec.JvmMainKt bench 15'
```

iOS (free team `apple-team-1`, bundle `ch.trancee.lab.codecbench`, iOS 17+ device):

```sh
./gradlew linkReleaseFrameworkIosArm64
cd iosApp && xcodegen generate
xcodebuild -project CodecBench.xcodeproj -scheme CodecBenchApp -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build DEVELOPMENT_TEAM=apple-team-1 -allowProvisioningUpdates build
xcrun devicectl device install app --device $UDID build/Build/Products/Release-iphoneos/CodecBenchApp.app
xcrun devicectl device process launch --device $UDID --terminate-existing ch.trancee.lab.codecbench
# about 70 s later:
xcrun devicectl device copy from --device $UDID --domain-type appDataContainer \
  --domain-identifier ch.trancee.lab.codecbench --source Documents/codec-results.txt --destination results/ios-iphone15.txt
```

## Results (2026-11-02)

Raw outputs are in [`results/`](results). Summary:

- **Sizes** (`jvm-sizes.csv`, identical on Android): B saves 2 B on the unicast header (36 → 34), 1–2 B on every other frame, and 19 spare bits (2 B usable plus 3 bits) in the 16 B advertisement. B and C bytes are identical for all 16 samples.
- **Safety**: 0 exceptions, 0 B/C disagreements and 0 non-canonical accepts over every prefix, over-long inputs and 1,000,000 mutated inputs per frame type (9 types), on the JVM (JDK 21) and on the XCover 4 (Android 9). The iPhone 15 ran 100,000 per type with the same result. Counts are identical across platforms (same seed).
- **Throughput, C relative to B** (`ratios.csv`, B_ns / C_ns per frame): JVM decode 0.35–0.73, encode 0.13–0.37; XCover 4 decode 0.29–0.48, encode 0.20–0.30; Pixel 6 decode 0.28–0.48, encode 0.18–0.30; iPhone 15 decode 0.18–0.45, encode 0.15–0.25.
- **Allocation** (`jvm-alloc.csv`): B and C allocate the same bytes per decode for every frame (32–184 B), also with `-XX:-DoEscapeAnalysis`; 0 B per encode. C reaches parity only by reusing one cursor and one byte range per codec instance, which makes the C codec stateful and not thread-safe.
- **LOC** (`loc.csv`): B 284, C 310 non-blank lines in the codec file; per frame within ±6 lines.

**Verdicts.** Layout: unicast saving 2 B < 4 B (fails); advertisement 19 bits ≥ 2 B (passes), so the pre-registered layout bar is met through the advertisement only. Codec: criterion 1 passes, 2 fails on every platform, 3 passes, 4 passes with workarounds. Recommendation per the pre-registration: the hand-written codec.

## Deviations

All entries logged 2026-11-02 20:25 CET, after the runs (20:07–20:23 CET) in which they were decided. None changes a criterion threshold.

- **Kompact source.** `~/.m2` holds only `0.3.0-SNAPSHOT`, so the build uses `ch.trancee.kompact:kompact:0.10.0` from Maven Central (commonMain unchanged since tag `v0.10.0`).
- **Android artifact.** Android runs dex the JVM fat jar (`kompact-jvm` 0.10.0) with `d8` and run it through `app_process` instead of building an APK against `kompact-android`. Same common code, no instrumentation harness. The XCover 4 ran once with the default JIT and once after `dex2oat --compiler-filter=speed`; both are reported. An interpreter-only run (`dalvikvm -Xint`) was about 35× slower, so the reported runs executed compiled code.
- **iOS device and bundle.** The iPhone 15 (iOS 17.6.1, team `apple-team-1`) was used instead of the SE 2016, with a new bundle ID `ch.trancee.lab.codecbench` so the bake-off app stays installed. The iPhone ran 100,000 fuzz inputs per type, not 1,000,000; the 1,000,000 criterion is met on the JVM and on the XCover 4.
- **no Kotlin/Native macOS host numbers.** Kompact 0.10.0 publishes no `macosArm64` target.
- **criterion 2 reading.** "Throughput ≥ 0.5× of B" is read per frame and per direction. No reading changes the verdict: the best device decode ratio is 0.48 and the best device encode ratio is 0.30.
- **allocation unit.** Measured in bytes per op, not objects. Equal bytes for B and C on every frame is taken as equal object counts.
