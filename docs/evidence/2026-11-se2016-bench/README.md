# Swift interop and libsodium on the iPhone SE (2016): evidence

- **Date:** 2026-11-02, 20:53–21:25 CET.
- **Ticket:** `.scratch/meshlink-v1/issues/24-se2016-interop-crypto-bench.md`
- **Decisions it feeds:** [ADR 0002](../../adr/0002-noise-suites-and-handshake-patterns.md), [ADR 0004](../../adr/0004-crypto-provider-libsodium.md), [ADR 0007](../../adr/0007-public-api-shape.md), [ADR 0008](../../adr/0008-routing-topology-and-density-adaptation.md). Context: [ADR 0005](../../adr/0005-toolchain-and-dependency-baseline.md), [ADR 0012](../../adr/0012-wire-format-and-codec.md).
- **Code:** throwaway prototype on branch `prototype/se2016-bench`, under `prototypes/se2016-bench/` (pre-registration, code, raw results). It is never merged.

The Noise, HPKE and API code measured here is **prototype code**, not MeshLink's implementation. The fake mesh is in memory and has no radio.

## Evidence labels

| Label | Meaning |
|---|---|
| **[RUN]** | Measured in the named run. The raw output is in [`raw/`](raw/). |
| **[SRC]** | Read in the prototype source, a generated header or a build artifact. |
| **[UNVERIFIED]** | A likely explanation that no run proves. |

## 1. Question

1. **Crypto.** On the slowest supported iPhone, is the crypto of a post-quantum link handshake (`XXhfs`) and of a post-quantum end-to-end first message (`IKhfs` plus HPKE X-Wing) small against the [ADR 0008](../../adr/0008-routing-topology-and-density-adaptation.md) first-message budget, when called through Kotlin/Native cinterop into static libsodium 1.0.22?
2. **Interop.** Is the [ADR 0007](../../adr/0007-public-api-shape.md) API shape, exported through SKIE and a thin Swift overlay, cheap enough per message, fast enough under sustained load, and does cancelling a Swift `Task` reach the Kotlin coroutine? These were the ADR's "untested costs".

## 2. Setup

### Devices

| Device | Details |
|---|---|
| iPhone SE (2016) | iPhone8,4 (Apple A9, 2 cores), iOS 15.8.8 (19H422), driven through libimobiledevice 1.4.0; team `apple-team-2`, bundle `ch.trancee.lab.host.b` |
| iPhone 15 | iPhone15,4, iOS 17.6.1, driven through `devicectl`; team `apple-team-1`, bundle `ch.trancee.lab.host`. Comparison only, decides no bar |
| Host | Apple M1, macOS 27.0.1, Xcode 16.4, xcodegen 2.46.0, JDK 21.0.13 |

### Software

- Kotlin 2.4.21, SKIE 0.10.16 (analytics upload disabled), Gradle 9.7.1, minos iOS 15.0 ([ADR 0005](../../adr/0005-toolchain-and-dependency-baseline.md)). Release `MeshLinkKit` XCFramework; Swift app in the Release configuration (`-O`).
- libsodium 1.0.22 from the official tarball: sha256 `adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349` checked, minisign signature verified with the upstream public key (in `libsodium/build.sh`). Static, `--enable-minimal`, `-O3`, flags copied from upstream `dist-build/apple-xcframework.sh` ([ADR 0004](../../adr/0004-crypto-provider-libsodium.md)). [SRC]
- Prototype crypto: `Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256` and `IKhfs` ([ADR 0002](../../adr/0002-noise-suites-and-handshake-patterns.md)); HPKE base mode X-Wing (0x647a) / HKDF-SHA256 / ChaCha20Poly1305; a 1,354 B post-quantum certificate ([ADR 0012](../../adr/0012-wire-format-and-codec.md) layout) signed with Ed25519.
- Fake API ([ADR 0007](../../adr/0007-public-api-shape.md)): `suspend start()/stop()`, non-suspending `send` returning `Accepted(OutgoingMessage)` with `StateFlow<DeliveryStatus>` or `Rejected(QueueFull | …)`, `messages: Flow<ReceivedMessage>` from a 64-slot inbox, single collector; an echo coroutine on `Dispatchers.Default`.
- Swift overlay, two variants: **bulk** (`memcpy` through `NSData`, decides the bars) and **per-byte** (the prior-art `prototype/public-api` overlay, one `get`/`set` per byte, finding only).

## 3. Method

The criteria were pre-registered in `prototypes/se2016-bench/README.md` (commit `1242642`, 20:53 CET) before any code ran on a device. Every timed operation: 30 warm-up iterations, then 300 timed (I3: 100 trials), median and nearest-rank p95; app in the foreground, launched detached, phone on a charger. Timing uses `CLOCK_UPTIME_RAW` on both sides of the bridge.

### Pre-registered criteria (summary)

| Bar | Pass if |
|---|---|
| **C1** `XXhfs` crypto per side (all `writeMessage`/`readMessage` work incl. ephemeral X25519 + ML-KEM-768, certificates in messages 2 and 3, one Ed25519 verify and one sign per side) | median and p95 ≤ 50 ms per side |
| **C2** `IKhfs` message 1 + HPKE X-Wing seal/open of 1 KB + certificate verify | median and p95 ≤ 50 ms per side |
| **C3** ChaCha20-Poly1305 encrypt of 244 B and 2 KB | median ≤ 0.5 ms |
| **V** X-Wing draft 11 Appendix C and hpke-pq-05 A.5 on the device | every value matches |
| **I1** Swift `send(Data)` → Kotlin → echo → Swift `for await` → `Data`, 64 B, 1 KB, 16 KB, one in flight | median ≤ 1 ms and p95 ≤ 5 ms per size |
| **I2** 40,000 × 1 KB, one sender task (yield and retry on `QueueFull`), one collector | ≥ 500 msg/s and `phys_footprint` growth from 25% to 100% ≤ 10 MB |
| **I3** 100 trials each: cancel a `for await` collector `Task`; cancel a `Task` awaiting a never-ending suspend call | 100/100 Kotlin-side cancellations, p95 ≤ 100 ms, 0 leaked probes |

## 4. Results

### Verdict table, iPhone SE (2016) [RUN `se2016-main.txt`, `se2016-i2.txt`]

| Bar | Median / p95 | Repeat run | Verdict |
|---|---|---|---|
| C1 initiator | 790 / 900 µs | 791 / 881 µs | **PASS** |
| C1 responder | 729 / 811 µs | 728 / 797 µs | **PASS** |
| C2 sender | 768 / 813 µs | 767 / 821 µs | **PASS** |
| C2 recipient | 713 / 754 µs | 714 / 754 µs | **PASS** |
| C3 244 B | 1.83 / 1.83 µs | — | **PASS** |
| C3 2 KB | 8.13 / 11.50 µs | — | **PASS** |
| I1 64 B | 100 / 124 µs | 98 / 123 µs | **PASS** |
| I1 1 KB | 95 / 128 µs | 98 / 128 µs | **PASS** |
| I1 16 KB | 105 / 149 µs | 111 / 164 µs | **PASS** |
| I2 | 380 msg/s; +1,009 MB (257 → 1,266 MB) | 387 msg/s; +928 MB | **FAIL** |
| I3 collector | 100/100; 271 / 463 µs | 100/100; 271 / 453 µs | **PASS** |
| I3 suspend call | 100/100; 462 / 488 µs | 100/100; 303 / 464 µs | **PASS** |

C1 messages total 5,188 B; C2 message 1 is 4,810 B. The `IKhfs` reply, without a bar, takes 366 / 398 µs to write and 326 / 348 µs to read. [RUN]

### Vectors [RUN `se2016-main.txt`, `iphone15-main.txt`]

On both iPhones: X-Wing C#0, C#1, C#2 public key, ciphertext and both shared secrets match; hpke-pq-05 A.5 `skRm`, `pkRm`, `enc`, both shared secrets, `key`, `base_nonce`, `exporter_secret`, 10/10 encryptions (nonce, seal, open) and 5/5 exports match.

### Crypto microbenchmarks, median µs [RUN]

| Operation | SE 2016 | iPhone 15 | Pixel 6 (Android smoke) |
|---|---|---|---|
| X25519 base | 42.92 | 15.63 | 35.81 |
| X25519 scalarmult | 114.04 | 37.13 | 75.6 |
| Ed25519 sign | 45.63 | 14.38 | 24.74 |
| Ed25519 verify | 139.69 | 34.04 | 63.56 |
| ChaChaPoly 244 B | 1.83 | 0.63 | 0.94 |
| ChaChaPoly 2 KB | 8.13 | 3.63 | 5.09 |
| SHA-256 2 KB | 12.50 | 5.54 | 7.12 |
| HKDF extract + expand | 6.17 | 2.42 | 3.09 |
| HMAC-SHA256 2 KB | 13.79 | 6.08 | 7.45 |
| ML-KEM-768 keypair | 45.23 | 16.25 | 34.51 |
| ML-KEM-768 enc | 44.06 | 14.92 | 37.23 |
| ML-KEM-768 dec | 50.42 | 17.58 | 45.82 |
| X-Wing keypair | 86.79 | 27.58 | 52.78 |
| X-Wing enc | 210.69 | 54.83 | 106.24 |
| X-Wing dec | 250.08 | 70.13 | 144.21 |

iPhone 15 full flows: C1 223 / 205 µs, C2 216 / 201 µs median. Pixel 6 numbers come from the earlier Android smoke, not from this run.

### I1 detail, SE 2016 [RUN `se2016-main.txt`]

| Size | Data→KotlinByteArray (bulk) | KotlinByteArray→Data (bulk) | Round trip, pre-converted | Round trip, per-byte overlay |
|---|---|---|---|---|
| 64 B | 2.88 / 3.17 µs | 3.54 / 3.92 µs | 88 / 109 µs | 108 / 128 µs |
| 1 KB | 3.08 / 4.04 µs | 3.88 / 11.17 µs | 88 / 109 µs | 226 / 236 µs |
| 16 KB | 7.62 / 16.62 µs | 28.71 / 34.54 µs | 76 / 112 µs | 2,626 / 2,708 µs |

Bulk conversion costs at most ~36 µs per round trip; the ~90 µs floor is the Swift → Kotlin → echo coroutine → flow → Swift hop. The per-byte overlay costs 1.4 ms (to Kotlin) plus 1.1 ms (to `Data`) at 16 KB: within the bar, but ~25× the bulk path.

### I2 analysis [RUN `se2016-i2.txt`, `se2016-i2-repeat.txt`, `se2016-i2-window.txt`, `iphone15-main.txt`]

| Run | Sender | Throughput | `QueueFull` retries | Footprint 25% → 100% |
|---|---|---|---|---|
| SE 2016 | yield + retry (pre-registered) | 380 msg/s | 7,279,322 | 257 → 1,266 MB |
| SE 2016 repeat | yield + retry (pre-registered) | 387 msg/s | 7,052,584 | 187 → 1,114 MB |
| SE 2016 (`debug-i2w.txt`) | window of 32 in flight (diagnostic) | 9,552 msg/s | 0 | flat, +0.16 MB, peak 23.6 MB |
| SE 2016 (`se2016-i2-window.txt`) | window of 32 in flight (diagnostic) | 9,232 msg/s | 0 | flat, +0.02 MB, peak 23.7 MB |
| iPhone 15 | yield + retry (pre-registered) | 17,421 msg/s | 1,171,157 | flat, −0.36 MB |
| iPhone 15 | window of 32 in flight (diagnostic) | 52,225 msg/s | 0 | flat, −0.34 MB |

- After the SE 2016 repeat, the Kotlin heap after the last automatic GC was 554 MB; a forced GC brought the footprint from 1,114 MB to 823 MB (heap 378 MB), and after the next run to 87 MB (heap 76 MB). The memory is reclaimable garbage, not a retained leak. [RUN]
- Cause: each retry converts the 1 KB `Data` and the fake's `send` copies the payload and allocates its status flow before the capacity check; ~70,000 retries per second on two A9 cores produce garbage faster than the Kotlin/Native GC reclaims it and starve the echo and collector coroutines. [UNVERIFIED: the GC-rate and CPU-starvation share were not separated.]
- The flow path itself sustains ~9,000 msg/s on the SE 2016 when the sender does not spin. The failure is the busy-retry pattern that a non-suspending `send` with `QueueFull` and no capacity signal invites.
- Per-byte I2 (4,000 messages, finding only): 4,539 msg/s in the first run, 597 msg/s in the repeat that started with an 823 MB heap. [RUN]

### Other observations

- **Exported libsodium symbols:** the release `MeshLinkKit` binary exports 228 libsodium symbols (`nm -gU`), although [ADR 0004](../../adr/0004-crypto-provider-libsodium.md) says symbols are hidden. Static cinterop linking alone does not hide them. Framework binary 2,712,784 B; app bundle 2.9 MB. [SRC]
- **SKIE:** `messages` stays `id<…Flow>` in the Objective-C header but works with Swift `for await`; `break` or dropping the iterator cancels the Kotlin collection; suspend functions become `async throws`; sealed classes get `onEnum(of:)`; Kotlin default arguments are not visible to Swift. [SRC, RUN]
- **Cancellation** reaches Kotlin in well under 1 ms in every trial; after the trials the fake shows 0 collectors, 0 suspend calls and only the echo job, and a new collector receives. [RUN]

## 5. Criteria verdict

| Bar | Verdict | Margin |
|---|---|---|
| C1 | **PASS** | worst p95 0.90 ms, 55× under 50 ms |
| C2 | **PASS** | worst p95 0.82 ms, 61× under 50 ms |
| C3 | **PASS** | 8.13 µs median at 2 KB |
| V | **PASS** | all values match |
| I1 | **PASS** | worst p95 164 µs against 5 ms |
| I2 | **FAIL** | 380 msg/s against 500; +1,009 MB against 10 MB |
| I3 | **PASS** | 100/100 each, worst p95 0.49 ms against 100 ms |

Crypto is not the bottleneck of the first-message budget on the SE 2016: a post-quantum link handshake costs under 1 ms of CPU per side. The interop path is fast per message and cancellation works. I2 fails as pre-registered; the diagnostic points at the API's back-pressure contract rather than the bridge (open question for [ADR 0007](../../adr/0007-public-api-shape.md): a suspending `send` or a capacity signal).

## 6. Deviations

Full list with times in `prototypes/se2016-bench/README.md`. In short:

- **Signing:** the SE 2016 is provisioned for team `apple-team-2` and bundle `ch.trancee.lab.host.b`, not `apple-team-1` / `ch.trancee.lab.host`.
- **Pre-registration time:** the text says "~21:00 CET"; the commit is 20:53 CET. No device run preceded it.
- **Host checks** ran on `macosArm64`; **libsodium** built with the upstream flags replicated for one target rather than the whole upstream script.
- **HPKE A.5** additionally checks `DeriveKeyPair`, the key schedule, encryptions and exports.
- **Split launches:** crypto + I1 + I3, I2, and the windowed diagnostic ran separately.
- **Added after the I2 failure was seen (~21:05–21:20 CET):** progress lines, the windowed sender and the GC diagnostic. None changes a bar.
- **Fake `send`** copies and allocates before the capacity check, which raises the per-retry cost.
- **iPhone 15** ran all sections in one launch, comparison only.

## 7. Limitations and unverified items

- **Fake mesh, no radio.** I1 to I3 measure the bridge and flow machinery only; BLE latency, background execution and real queuing are not covered.
- **Prototype crypto.** One author wrote Noise and HPKE; it matches the vectors but is not MeshLink's implementation. C1 and C2 count CPU only, in process, not transmission.
- **Two runs per bar on one SE 2016**, foreground, on a charger; thermal state was not recorded.
- **Memory** is `phys_footprint` and the Kotlin heap after GC; allocation counts per message were not measured.
- **SKIE-internal coroutines** are not observable, so "no leaked jobs" covers only the fake's own jobs.
- **iOS 16 and other A9/A10 devices** were not tested.

## 8. Raw data

Copied into [`raw/`](raw/) from `prototypes/se2016-bench/results/` on branch `prototype/se2016-bench` (commit `d5fc968`):

| File | Contents |
|---|---|
| `se2016-main.txt`, `se2016-main-repeat.txt` | vectors, microbenchmarks, C1 to C3, I1, I3 |
| `se2016-i2.txt`, `se2016-i2-repeat.txt` | pre-registered I2 with progress lines (repeat also has the GC diagnostic) and per-byte I2 |
| `se2016-i2-window.txt` | windowed I2 diagnostic |
| `iphone15-main.txt` | everything on the iPhone 15 |
| `smoke.txt`, `debug-i2.txt`, `debug-i2w.txt` | first smoke and debugging runs; not used for verdicts (`debug-i2w.txt` is cited as a windowed diagnostic) |

## 9. How to reproduce

The free-team profiles expire around 2026-11-07. Installing replaces the previous lab app.

```sh
git worktree add ../MeshLink-se2016-bench prototype/se2016-bench
cd ../MeshLink-se2016-bench/prototypes/se2016-bench
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
libsodium/build.sh
./gradlew macosArm64Test
host/run-se2016.sh all se2016-main sections=i1,i3
SKIP_BUILD=1 host/run-se2016.sh interop se2016-i2 sections=i2
SKIP_BUILD=1 host/run-se2016.sh interop se2016-i2-window sections=i2w

# iPhone 15 (devicectl)
D=ip15
(cd iosApp && xcodebuild -project SE2016Bench.xcodeproj -scheme SE2016BenchApp -configuration Release \
  -destination generic/platform=iOS -derivedDataPath build \
  DEVELOPMENT_TEAM=apple-team-1 LAB_BUNDLE_ID=ch.trancee.lab.host -allowProvisioningUpdates build)
xcrun devicectl device install app --device $D iosApp/build/Build/Products/Release-iphoneos/SE2016BenchApp.app
xcrun devicectl device process launch --terminate-existing --device $D ch.trancee.lab.host \
  mode=all run=iphone15-main sections=i1,i2,i2w,i3
xcrun devicectl device copy from --device $D --domain-type appDataContainer \
  --domain-identifier ch.trancee.lab.host \
  --source Documents/results/iphone15-main.txt --destination results/iphone15-main.txt
```

## 10. Evidence record (Constitution T9)

| Field | Value |
|---|---|
| Devices and OS | iPhone SE (2016) iPhone8,4, iOS 15.8.8 (19H422); iPhone 15 iPhone15,4, iOS 17.6.1; Mac (Apple M1, macOS 27.0.1) for builds and host tests |
| Firmware | iOS builds as above |
| Covered platforms | Kotlin/Native `iosArm64` release framework with SKIE, called from Swift (Release) on iOS 15 and iOS 17; libsodium 1.0.22 static via cinterop |
| Not covered | Radio and BLE; background execution; iOS 16; Android interop (Android crypto numbers come from the earlier smoke); thermal throttling |
| Commit | Branch `prototype/se2016-bench`: pre-registration `1242642`, code and results `d5fc968` (README lint fix `0fa52de`); measured from a working tree based on `1242642` |
| Method | Pre-registered criteria in `prototypes/se2016-bench/README.md`; see [Method](#3-method) |
| Raw results | [`raw/`](raw/), copied from `prototypes/se2016-bench/results/` |
| Date | 2026-11-02 20:53–21:25 CET |
