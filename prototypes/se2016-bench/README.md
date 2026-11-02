# SE 2016 interop and crypto benchmark (throwaway prototype)

Ticket 24, "Are Swift interop and libsodium fast enough on the iPhone SE (2016)?". This directory lives only on branch `prototype/se2016-bench` and is never merged.

## Pre-registration

Written 2026-11-02 ~21:00 CET, before any code ran on a device or a number was measured. Any later change is logged under [Deviations](#deviations) with its time and reason.

### Device and build

- **Primary device:** iPhone SE (2016), iPhone8,4, iOS 15.8.x, UDID `se2016`, driven through libimobiledevice.
- **Optional comparison:** iPhone 15 (`ip15`, `devicectl`), only if cheap. It never decides a bar.
- **Build:** Kotlin 2.4.21, SKIE 0.10.16 (analytics upload disabled), Gradle 9.7.1, Xcode 16.4, minos iOS 15.0, JDK 21 (ADR 0005). Kotlin/Native **release** framework `MeshLinkKit`; Swift app in the **Release** configuration.
- **libsodium 1.0.22**, static, `--enable-minimal`, built for `iosArm64` by `libsodium/build.sh` from the official tarball after checking its sha256 and its minisign signature (ADR 0004). Called from Kotlin/Native through cinterop, which is the real call path.

### Statistics

Every timed operation: warm up, then at least 300 timed iterations; report median, p95 (nearest rank), min and max. The app runs in the foreground, launched detached, with the phone on a charger and nothing else launched by the harness.

### Crypto bars (SE 2016)

What is counted per side is fixed here so the bar cannot drift. Long-term keys (Ed25519 identity, X25519 Noise static, X-Wing static) and certificates are created before timing. The identity certificate is the ADR 0012 layout (post-quantum: 1,354 B). Everything is a real Noise or HPKE computation over libsodium, not a sum of microbenchmarks.

| Bar | Workload per side | Pass if |
|---|---|---|
| **C1** | One full `Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256` handshake (ADR 0002), in process. Initiator and responder are timed separately: every `writeMessage`/`readMessage` call of that side, including ephemeral X25519 and ML-KEM-768 key generation, X25519 DH, ML-KEM-768 encapsulation or decapsulation, ChaChaPoly, SHA-256 and HKDF. Messages 2 and 3 carry the sender's post-quantum certificate as payload (so ~5.2 KB in total, more than ADR 0002's ~2.5 KB estimate without certificates). Each side also verifies the peer's certificate (Ed25519 verify) and, as a conservative upper bound, signs one certificate (Ed25519 sign) per handshake. | median **and** p95 ≤ 50 ms for each side |
| **C2** | One post-quantum end-to-end first message: the sender seals 1 KB with HPKE base mode, X-Wing (0x647a) / HKDF-SHA256 / ChaCha20Poly1305, to the recipient's certified X-Wing key, then writes `IKhfs` message 1 (`e, es, e1, s, ss`) carrying its certificate and the sealed payload, and verifies the recipient's certificate (Ed25519). The recipient reads message 1, verifies the sender's certificate and opens the HPKE payload. The `IKhfs` reply (message 2) is timed and reported but has no bar. | median **and** p95 ≤ 50 ms for each side |
| **C3** | ChaCha20-Poly1305 IETF encryption of a 244 B and of a 2 KB record (also decryption reported). | median ≤ 0.5 ms for each size |
| **V** | X-Wing `draft-connolly-cfrg-xwing-kem-11` Appendix C (3 vectors: public key, ciphertext, shared secret on encapsulation and decapsulation) and `draft-ietf-hpke-pq-05` A.5 (KEM part as in the Android smoke; additionally key schedule, 10 encryptions and 5 exports through this prototype's HPKE) on the device. | every value matches byte for byte |

The same microbenchmarks as the Android smoke (X25519, Ed25519, ChaChaPoly, SHA-256, HKDF, HMAC, ML-KEM-768, X-Wing) are reported for comparison with the Pixel 6; they have no bar.

### Interop bars (SE 2016)

Swift app → `MeshLinkKit` XCFramework built with SKIE → minimal Swift overlay → in-memory fake echo mesh (no radio). The fake follows ADR 0007: `suspend start()/stop()`, non-suspending `send(to:payload:options:)` returning `SendResult.Accepted(OutgoingMessage)` with `status: StateFlow<DeliveryStatus>` or `Rejected(reason)`, `messages: Flow<ReceivedMessage>` from a bounded inbox (single collector), `ByteArray` payloads. The echo peer runs on a Kotlin coroutine on `Dispatchers.Default` and returns each payload into the local inbox.

Two overlay variants are measured, because the prior-art overlay (`prototype/public-api`) copies bytes one `get`/`set` call at a time:

- **bulk** (primary, decides the bars): `Data` ↔ `KotlinByteArray` through a Kotlin helper that copies with `memcpy` (`NSData` in, `NSData` out);
- **per-byte**: the prior-art overlay code unchanged. Reported as a finding; it decides no bar.

| Bar | Workload | Pass if |
|---|---|---|
| **I1** | Swift `send(Data)` → overlay → Kotlin → echo → Swift `for await` receives the message and converts it to `Data`. Round trip timed in Swift with `clock_gettime_nsec_np(CLOCK_UPTIME_RAW)`, one message in flight, for 64 B, 1 KB and 16 KB payloads. Also reported separately: `Data` → `KotlinByteArray` and `KotlinByteArray` → `Data` conversion alone, and the round trip with pre-converted arrays (flow overhead without conversion). | median ≤ 1 ms **and** p95 ≤ 5 ms for each size |
| **I2** | Sustained throughput: one Swift sender task and one Swift collector task, 40,000 messages of 1 KB through the flow. A `Rejected(QueueFull)` makes the sender yield and retry (counted). Memory: the app's `phys_footprint` (`TASK_VM_INFO`) sampled before the run and after every 2,000 received messages; peak reported. | ≥ 500 messages/s **and** footprint at 100% minus footprint at 25% of the messages ≤ 10 MB |
| **I3** | 100 trials each: (a) cancel a Swift `Task` that is in `for await` over `messages`; (b) cancel a Swift `Task` awaiting a Kotlin suspend call that never completes by itself. The Kotlin side records its cancellation time (`onCompletion` / `finally`) on the same clock. Afterwards the Kotlin probes must show 0 active collectors and 0 active suspend calls, and the inbox must accept a new collector. | Kotlin side cancelled in 100/100 trials of each case, **and** cancel → Kotlin cancellation latency p95 ≤ 100 ms, **and** 0 leaked probes |

A failed bar is a finding: it is reported with its numbers and, where possible, a cause, and is not tuned away.

## Layout

| Path | Contents |
|---|---|
| `libsodium/build.sh` | Verifies and builds static libsodium 1.0.22 for `iosArm64` (and `macosArm64` for host checks) |
| `vectors/extract.py` | Extracts the X-Wing and HPKE A.5 vectors from the IETF drafts into `Vectors.kt` |
| `src/nativeMain/kotlin/.../crypto/` | libsodium binding, Noise (`XXhfs`, `IKhfs`), HPKE X-Wing, vectors check, benchmarks |
| `src/nativeMain/kotlin/.../` | Fake ADR 0007 API with echo mesh and cancellation probes |
| `iosApp/` | xcodegen project: SwiftUI shell, overlay variants, interop benchmarks |
| `host/run-se2016.sh` | Build, install, launch and pull results on the SE 2016 |
| `results/` | Raw device outputs |


## How to run

Prerequisites: JDK 21, Xcode 16.4, xcodegen, minisign, libimobiledevice (`ideviceinstaller`, `idevicedebug`, `afcclient`), a signing identity for the lab bundle.

```sh
cd prototypes/se2016-bench
libsodium/build.sh                                   # verify tarball (sha256 + minisign), build iosArm64 + macosArm64
python3 vectors/extract.py                           # regenerate Vectors.kt from the IETF drafts
JAVA_HOME=<jdk21> ./gradlew macosArm64Test           # host check: vectors, XXhfs/IKhfs round trips
JAVA_HOME=<jdk21> host/run-se2016.sh all se2016-main sections=i1,i3
SKIP_BUILD=1 host/run-se2016.sh interop se2016-i2 sections=i2
SKIP_BUILD=1 host/run-se2016.sh interop se2016-i2-window sections=i2w   # diagnostic, no bar
```

The script builds the release XCFramework and the Release app, installs it, launches it detached, polls the app container through `afcclient` and writes `results/<run-id>.txt`. The iPhone 15 comparison used `devicectl` instead (see `results/iphone15-main.txt` header and Deviations).

## Results (measured)

All numbers from `results/`, release builds, n = 300 after 30 warm-up iterations (I3: n = 100). Primary runs: `se2016-main.txt` (2026-11-02 20:17 UTC) and `se2016-i2.txt` (20:17–20:19 UTC); repeats: `se2016-main-repeat.txt`, `se2016-i2-repeat.txt`. iPhone 15 (iOS 17.6.1): `iphone15-main.txt`, comparison only.

### Verdicts (SE 2016)

| Bar | Result (median / p95) | Verdict |
|---|---|---|
| C1 XXhfs, 5,188 B | initiator 790 / 900 µs; responder 729 / 811 µs (repeat 791 / 881, 728 / 797) | **PASS** (≤ 50 ms) |
| C2 IKhfs + HPKE 1 KB, msg 1 4,810 B | sender 768 / 813 µs; recipient 713 / 754 µs (repeat 767 / 821, 714 / 754) | **PASS** (≤ 50 ms) |
| C3 ChaChaPoly encrypt | 244 B 1.83 µs; 2 KB 8.13 µs median | **PASS** (≤ 0.5 ms) |
| V vectors | X-Wing C#0–2 all MATCH; A.5 KEM, key schedule, 10/10 encryptions, 5/5 exports MATCH | **PASS** |
| I1 bulk round trip | 64 B 100 / 124 µs; 1 KB 95 / 128 µs; 16 KB 105 / 149 µs | **PASS** (≤ 1 ms / ≤ 5 ms) |
| I2 throughput + memory | 380 msg/s (repeat 387); footprint 257 → 1,266 MB between 25% and 100% (+1,009 MB; repeat +928 MB) | **FAIL** (both criteria) |
| I3 cancellation | collector 100/100, 271 / 463 µs; suspend call 100/100, 462 / 488 µs; 0 leaked probes, new collector works | **PASS** |

### I1 detail (SE 2016, median / p95 µs)

| Size | Data→KotlinByteArray bulk (median) | KotlinByteArray→Data bulk (median) | RTT pre-converted | RTT per-byte overlay |
|---|---|---|---|---|
| 64 B | 2.88 | 3.54 | 88 / 109 | 108 |
| 1 KB | 3.08 | 3.88 | 88 / 109 | 226 |
| 16 KB | 7.62 | 28.71 | 76 / 112 | 2,626 / 2,708 |

The prior-art per-byte overlay costs 1.4 ms (to Kotlin) and 1.1 ms (to Data) at 16 KB, about 25× the bulk round trip; still inside the I1 bar but wasteful.

### I2 analysis

The pre-registered sender calls `send`, and on `Rejected(QueueFull)` does `Task.yield()` and retries. On the SE 2016 this made ~7.3 million retries (~70k/s). Each retry converts the 1 KB `Data` and the fake's `send` copies the payload and allocates a `MutableStateFlow` before the capacity check, so the sender produces roughly 150 MB/s of garbage and competes with the echo and collector coroutines for the two A9 cores. Diagnostics (added after the failure, see Deviations):

- Kotlin heap after the last automatic GC was 554 MB at the end of the repeat run; a forced GC dropped the footprint 1,114 → 823 MB, and after the following run it was 87 MB. The growth is reclaimable garbage outpacing the GC, not a retained leak (interpretation).
- A windowed sender (at most 32 messages in flight, so never `QueueFull`) reaches 9,552 / 9,232 msg/s on the SE 2016 with flat footprint (+0.16 / +0.02 MB, peak 23.7 MB). The flow path itself is fast enough; the failure is the busy-retry pattern that ADR 0007's non-suspending `send` with no capacity signal invites.
- iPhone 15: the same pre-registered sender reaches 17,421 msg/s with 1.17 million retries and flat footprint (−0.36 MB), so the pathology is specific to slow devices.

### Crypto microbenchmarks (median µs)

| Operation | SE 2016 | iPhone 15 | Pixel 6 (Android smoke) |
|---|---|---|---|
| X25519 base | 42.92 | 15.63 | 35.81 |
| X25519 scalarmult | 114.04 | 37.13 | 75.6 |
| Ed25519 sign | 45.63 | 14.38 | 24.74 |
| Ed25519 verify | 139.69 | 34.04 | 63.56 |
| ChaChaPoly 244 B | 1.83 | 0.63 | 0.94 |
| ChaChaPoly 2 KB | 8.13 | 3.63 | 5.09 |
| SHA-256 2 KB | 12.50 | 5.54 | 7.12 |
| HKDF | 6.17 | 2.42 | 3.09 |
| HMAC 2 KB | 13.79 | 6.08 | 7.45 |
| ML-KEM-768 keypair | 45.23 | 16.25 | 34.51 |
| ML-KEM-768 enc | 44.06 | 14.92 | 37.23 |
| ML-KEM-768 dec | 50.42 | 17.58 | 45.82 |
| X-Wing keypair | 86.79 | 27.58 | 52.78 |
| X-Wing enc | 210.69 | 54.83 | 106.24 |
| X-Wing dec | 250.08 | 70.13 | 144.21 |

### Other findings

- The release `MeshLinkKit` framework exports 228 libsodium symbols (`nm -gU`), contrary to ADR 0004 "symbols hidden"; cinterop static linking does not hide them by itself. Not fixed here.
- SKIE: `messages` stays `id<Flow>` in the ObjC header but is usable with `for await`; breaking out or dropping the iterator cancels the Kotlin collection; suspend functions become `async throws`; Kotlin default arguments are not visible to Swift; sealed classes get `onEnum(of:)`.
- SKIE-internal coroutines are not observable by the probes, so "no leaked jobs" covers only the fake's own jobs.

## Deviations

Times are CET on 2026-11-02.

1. **Signing (before the first device run, ~21:00):** the SE 2016 is provisioned for team `apple-team-2` with bundle `ch.trancee.lab.host.b`, not `apple-team-1` / `ch.trancee.lab.host`; the script defaults to the former. The iPhone 15 used `apple-team-1` / `ch.trancee.lab.host`.
2. **Pre-registration timestamp:** the text says "~21:00"; the commit is 20:53. No device run happened before it.
3. **Host checks** ran on `macosArm64` (not listed in the pre-registration as a target; it decides no bar).
4. **libsodium build:** upstream `apple-xcframework.sh` compiler and configure flags replicated for one target instead of running the whole script.
5. **HPKE A.5:** recipient keys come from `DeriveKeyPair` (SHAKE256 `LabeledDerive`), which goes beyond the Android smoke's KEM-only check.
6. **Split runs (~21:10):** crypto + I1 + I3, I2 and the windowed diagnostic ran as separate launches (`sections=` argument) because the pre-registered I2 took ~105 s and grew to >1 GB.
7. **Added after observing the I2 slowdown (~21:05–21:20):** I2 progress watchdog lines, the windowed I2 sender (`i2w`, diagnostic, no bar) and the post-run GC diagnostic (`BenchRuntime`). The first I2 run predates the GC diagnostic.
8. **Per-byte I2** uses 4,000 messages (coded before the first device run; the pre-registration names per-byte only as a finding). Its result depends on the heap state left by the bulk run.
9. **C2 reply metrics** (`C2 info`) are reported without a bar, as pre-registered.
10. **Fake `send`** copies the payload and creates its status flow before the capacity check; this inflates the per-retry cost in I2 and is part of the cause above. A real implementation could reject earlier.
11. **iPhone 15 run (~21:24):** all sections in one launch via `devicectl`, comparison only.
