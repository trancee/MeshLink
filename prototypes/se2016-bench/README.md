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

## Deviations

None yet.
