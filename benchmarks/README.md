# MeshLink benchmark and validation baselines

Use this page for the current benchmark posture.

The normative targets still live in:

- `specs/001-ble-mesh-sdk/spec.md` — success criteria
- `specs/001-ble-mesh-sdk/plan.md` — mirrored performance goals

A failing result in this directory is still useful retained evidence, but it
does not relax the underlying requirement.

If you need the full historical experiment trail, older matrices, and detailed
investigation notes, use [benchmark history](history.md).

## Normative acceptance thresholds (mirror only)

| Criterion | Normative threshold | Canonical source |
|---|---:|---|
| 1-hop 256 B message latency | <= 50 ms p95 after connection establishment | Spec `SC-004`, Plan `Performance Goals` |
| 1-hop 64 KiB Android throughput | >= 80 KB/s | Spec `SC-004`, Plan `Performance Goals` |
| 1-hop 64 KiB iOS throughput | >= 60 KB/s | Spec `SC-004`, Plan `Performance Goals` |
| 8-peer steady-state heap | <= 8 MiB | Spec `SC-005`, Plan `Performance Goals` |
| LOW-mode scan duty | <= 5% | Spec `SC-006`, Plan `Performance Goals` |
| LOW-mode 1-hop 256 B delivery | <= 5 s after peer discovery and connection establishment | Spec `SC-006`, Plan `Performance Goals` |
| Cold start to first advertisement | < 500 ms | Plan `Performance Goals` |
| 10-node route convergence | < 3 s | Spec `SC-002`, Plan `Performance Goals` |
| JVM wire encode/decode | < 1 us/op | Plan `Performance Goals` |

Rows not listed above are retained as regression-tracked evidence only.

## Latest retained evidence

### JVM evidence (2026-05-11)

| Metric | Latest result | Interpretation |
|---|---:|---|
| Convergence, 10-node topology change | 0.344 ms/op | Meets the route-convergence target |
| Wire encode message | 0.269 us/op | Meets the codec target |
| Wire decode message | 0.083 us/op | Meets the codec target |
| Wire encode transfer chunk | 0.210 us/op | Meets the codec target |
| Wire decode transfer chunk | 0.083 us/op | Meets the codec target |
| 8-peer steady-state memory budget | 3,993,216 retained bytes | Meets the memory target |

### Physical mobile evidence

| Surface | Latest retained result | Interpretation |
|---|---|---|
| Android cold start | Physical benchmark pass (< 500 ms) | Meets the cold-start target |
| Android LOW-power scan duty | 5% | Meets the LOW-mode duty target |
| Android LOW-power 256 B delivery | 411 ms (Samsung -> OPPO) | Meets the LOW-mode delivery target |
| Android 256 B latency | Physical benchmark pass (<= 50 ms) | Meets the latency target |
| Android 64 KiB throughput | 1306.12 KB/s (OPPO -> Samsung) | Meets the Android throughput target |
| iOS cold start | 18 ms (iPhone 15) | Meets the cold-start target |
| iOS LOW-power scan duty | 5% (iPhone 15) | Meets the LOW-mode duty target |
| iOS LOW-power 256 B delivery | 239 ms (iPhone 15 -> OPPO) | Meets the LOW-mode delivery target |
| iOS 256 B latency | 28 ms (iPhone 15 -> Samsung) | Meets the latency target |
| iOS 64 KiB throughput | 61.96-70.64 KB/s on Samsung, 77.48-80.10 KB/s on OPPO | Current-head retained evidence clears the iOS throughput target on both reference peers |

## Current release-decision posture

Two truths matter at the same time:

1. the historical released baseline still carries the documented iOS waiver
   history
2. the latest current-head retained evidence now clears `SC-004` on both
   reference peers

Use the release-decision document for the canonical release framing, and use the
historical benchmark archive when you need the full path from waiver-era runs to
current-head conformance evidence.

## Current takeaways

- The current JVM benchmark posture meets the retained codec, convergence, and
  memory targets listed above.
- Android physical evidence continues to meet the retained latency,
  throughput, cold-start, and LOW-mode targets on the reference peers.
- Current-head iPhone 15 product-path evidence now clears the retained
  `>= 60 KB/s` iOS target on both Samsung and OPPO reference peers with
  recipient-confirmed closure.
- The historical investigation trail still matters for understanding how the
  transport posture evolved, but it no longer belongs on the critical reading
  path for the current state.

## Refresh commands

- JVM benchmarks: `./gradlew :benchmarks:jvmBenchmark`
- JVM smoke baselines: `./gradlew :benchmarks:jvmSmokeBenchmark`
- Android proof benchmarks: run the instrumented proof benchmarks against physical peers
- iOS proof benchmarks: run the XCTest proof benchmarks plus a physical peer-backed proof-app run
