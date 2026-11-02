# Are Swift interop and libsodium fast enough on the iPhone SE (2016)?

Type: prototype
Status: resolved

## Question

Benchmark on the slowest supported iPhone (SE 2016, iOS 15): per-message `Data` ↔ `KotlinByteArray` conversion and Flow overhead through SKIE, Swift-task cancellation reaching Kotlin (ADR 0007), and libsodium primitives used by each profile (X25519, Ed25519, ChaCha20-Poly1305, ML-KEM-768, X-Wing) as T9 evidence (ADR 0005).

## Context

- Urgent: free Apple provisioning profiles expire around 2026-11-07.
- Pre-register pass bars against the latency budgets of ADR 0008 (first message p95).

## Answer

Resolved 2026-11-03 with the user. Evidence: [`docs/evidence/2026-11-se2016-bench/`](../../../docs/evidence/2026-11-se2016-bench/README.md); prototype on throwaway branch `prototype/se2016-bench`.

- **Crypto is fast enough.** On the SE 2016 every crypto bar passed by a wide margin: under 0.8 ms median per side for an `XXhfs` handshake and for an `IKhfs` plus HPKE first message, 1.8 µs for ChaCha20-Poly1305 of 244 B. The X-Wing and hpke-pq vectors matched on the SE 2016 and the iPhone 15.
- **Swift interop is fast enough.** Round trip through SKIE 95–111 µs median for 64 B to 16 KB; `Task` cancellation reached Kotlin 100/100, p95 under 0.5 ms.
- **Sustained send (I2) failed, and stays recorded as failed.** The pre-registered sender retried `QueueFull` in a loop; its allocations outran the garbage collector (380 msg/s, about +1 GB). A sender with 32 messages in flight reached 9,200–9,500 msg/s with flat memory. No rerun.
- **Decisions:**
  - ADR 0007 v3: keep non-suspending `send`, add `suspend fun awaitSendCapacity()`; never retry `QueueFull` in a loop.
  - ADR 0007 v3: the Swift overlay converts `Data` with one bulk copy, guarded by a benchmark.
  - ADR 0004 v2: libsodium symbols are hidden through an export allow-list, enforced by an `nm` CI gate (228 were exported).
  - Lessons: the SE 2016 is signed with team `apple-team-2`, bundle `ch.trancee.lab.host.b`.
