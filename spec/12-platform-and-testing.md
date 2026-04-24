# 12 — Platform Considerations & Testing Strategy

> **Covers:** §12 (Platform Considerations) + §13 (Testing Strategy)
> **Dependencies:** `02-architecture.md` (module layout, platform source sets), `03-transport-ble.md` (BleTransport, L2CAP)
> **Key exports:** Per-platform BLE/crypto/storage details, hardware edge cases, VirtualMeshTransport, test layers, coverage enforcement, pre-commit hooks, benchmarks, CI workflows
> **Changes from original:** A10 (benchmark tests), A11 (detekt + ktfmt pre-commit via prek), A12 (100% coverage enforcement per slice), A13 (CI via GitHub Actions)

---

## Part A: Platform Considerations

### Android

**Foreground service:** Required for background BLE. Library ships `MeshLinkService` base class. App subclasses + declares in manifest.

**Permissions:**
- `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (Android 12+)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (Android 14+)
- **No** `ACCESS_FINE_LOCATION` on API 29+ with `neverForLocation` flag

**OEM issues:**
- Samsung/OnePlus/Xiaomi aggressive battery optimizers — apps should guide exemption
- Doze/App Standby: `MeshLinkService` declares `FOREGROUND_SERVICE_CONNECTED_DEVICE` type. Apps should request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **Reactive connection limit discovery:** BLE failures → reduce effective slots, persist per `manufacturer|model`
- **Scan filter fallback:** Hardware UUID filters → software if firmware broken

**Crypto:** `AndroidCryptoProvider` — libsodium via JNI, precompiled `.so` (arm64, armv7, x86_64) in AAR (~800KB). ARM Crypto Extensions.
**Storage:** `AndroidSecureStorage` via `EncryptedSharedPreferences` (Android Keystore).
**ProGuard:** AAR ships consumer rules.

### iOS

**Background BLE restrictions:**

| Constraint | Impact |
|------------|--------|
| Scanning throttled | Discovery ~10–15s+ |
| Ad data limited | UUIDs → overflow area |
| Suspension | iOS may suspend/terminate |
| Two backgrounded apps | **10–15 min** mutual discovery (hard iOS limit) |

State Preservation/Restoration for relaunch. State rebuilt from SecureStorage + Babel (~5–15s).

**Crypto:** `IosCryptoProvider` — libsodium via Kotlin/Native cinterop (static `.a`). ARM Crypto Extensions. **Not** CryptoKit.
**Storage:** `IosSecureStorage` via Keychain Services.
**L2CAP:** `CBL2CAPChannel` via `CBPeripheralManager.publishL2CAPChannel`, bridged to coroutines.

### iOS Distribution
XCFramework via Gradle: `./gradlew :meshlink:assembleMeshLinkXCFramework`. Includes `iosArm64` (device) + `iosSimulatorArm64` (simulator) slices. `Package.swift` at repo root for SPM.

### Hardware Edge Cases

**Thermal throttling:** Performance mode (80% scan, 8 connections) → thermal within 10–15 min. Monitor thermal state, treat throttle as PowerSaver transition.

**Chipset connection limits:**

| Family | Typical Max |
|--------|------------|
| Qualcomm Snapdragon | 6–8 |
| MediaTek Dimensity | 4–6 |
| Samsung Exynos | 4–7 |
| Apple (Broadcom/USI) | 8–10 |

Default should start at 6, increase only after confirmed capacity.

**MTU validation:** Some devices report MTU but fail writes above 20B. Validate by test write of `negotiated_MTU - 4`. Maintain `validated_mtu` distinct from `negotiated_mtu`.

**L2CAP silent stalls:** Some chipsets (Broadcom BCM4389) silently stall. Send 0-length keepalive every 10s; no data within 15s → GATT fallback.

**iOS connection intervals:** Central-side requests often ignored (30ms foreground, 100–500ms background). Measure actual interval from write completion timing; use for ACK timeout calculation.

**BLE 5.0+ features:**
- **2M PHY:** Adaptive: RSSI > -60dBm → 2M, -60 to -75 → 1M, < -75 → 1M. Re-evaluate on ±10dBm.
- Extended Advertising: not leveraged (16B fits standard)
- 5.3 Connection Subrating: post-v1 candidate

**Airtime budget:** 8 connections + ads + scan → ~40–60% available for data. Throughput estimates are best-case.

**BLE address rotation:** iOS rotates MAC ~15min. GATT connections unaffected. Scanning sees "new" device; Noise XX identity resolution handles it.

---

## Part B: Testing Strategy

### VirtualMeshTransport

In-memory BLE simulator implementing `BleTransport`:
- **Topology:** `linkTo()` configurable multi-hop meshes
- **Event injection:** `simulateDiscovery()`, `simulatePeerLost()`
- **Frame capture:** `sentData` for byte-exact assertions
- **Link params:** RSSI, packet loss, latency
- **Error injection:** `simulateWriteFailure()`, `simulateStopHang()`, `simulateMemoryPressure()`
- **Step mode:** `advanceTo(time)` for deterministic race condition testing

Published as test artifact: `ch.trancee:meshlink-testing:<version>` (Maven Central) / XCFramework test module. Used from `commonTest/` (runs on JVM target).

### Deterministic Time

All timers use **injectable clock** via `TestCoroutineScheduler`. Tests complete in milliseconds.

**Clock rule:** Operational timers = monotonic. Exception: replay counter 30-day expiry = wall-clock (calendar scale).

### Test Layers

| Layer | Tool | Count | Description |
|-------|------|-------|-------------|
| Unit + Protocol | VirtualMeshTransport | ~1,240+ | Chunking, encryption, routing, dedup, codecs |
| Integration | VirtualMesh + TestScheduler | ~50+ | Discovery → handshake → messaging → ACK → stop |
| Conformance | Golden vectors | ~40+ | Byte-exact wire format for all 12 types |
| Crypto | Wycheproof vectors | Multiple | ChaCha20-Poly1305, Ed25519, X25519, HMAC-SHA256, HKDF |
| Fuzz | Reproducible seed | Multiple | Wire format fuzz with `Random(seed = 42)` |
| Stress/Scale | VirtualMesh | Multiple | Large-scale simulation (festival scenario) |

### Code Coverage: 100% Line & Branch

Every slice must achieve **100% line and branch coverage** on all production code before committing. Enforced via Kover:

```sh
./gradlew :meshlink:koverVerify
```

| Scope | Tool | Threshold |
|-------|------|-----------|
| Line coverage | Kover (JaCoCo engine) | 100% |
| Branch coverage | Kover (JaCoCo engine) | 100% |

**Source sets covered:** `commonMain`, `androidMain`. Platform `actual` implementations included. Tests run on JVM via `commonTest` — Kover instruments the JVM bytecode.

**What this means in practice:**
- New code paths need tests — no exceptions
- Dead code left after refactoring must be removed, not left uncovered
- `require()` with string interpolation creates uncoverable bytecode branches — use explicit `if (...) throw IllegalArgumentException(...)` instead
- Coverage verified locally before commit AND in CI

### Pre-Commit Hook: detekt + ktfmt via prek

Static analysis and formatting enforced on every commit via [`prek`](https://prek.j178.dev) pre-commit hooks. This is one of the **first tasks** in the project bootstrap sequence — see `02-architecture.md` §5 (Project Bootstrap).

**Setup:**
```sh
prek install
```

**Hook runs (in order):**
1. **trailing-whitespace** — strip trailing whitespace from Kotlin files
2. **end-of-file-fixer** — ensure files end with a newline
3. **check-added-large-files** — reject files >500KB
4. **check-merge-conflict** — reject files with merge conflict markers
5. **ktfmt** — deterministic Kotlin formatting (Kotlinlang style). Validates staged `.kt` files via `./gradlew :meshlink:ktfmtCheck`.
6. **detekt** — static analysis with project ruleset (`detekt.yml`) via `./gradlew :meshlink:detekt`. Fails the commit on violations.

**Configuration:**

| Tool | Config File | Task |
|------|-------------|------|
| prek | `prek.toml` | 6 hooks: 4 builtins + ktfmt + detekt |
| ktfmt | `meshlink/build.gradle.kts` | `kotlinLangStyle()` via `com.ncorti.ktfmt.gradle` plugin |
| detekt | `detekt.yml` | All default rules + `complexity`, `naming`, `style` rulesets |

**Rationale:** Formatting debates are eliminated — ktfmt is deterministic (no config knobs beyond style preset). detekt catches real issues (complexity, naming, unused imports) before they reach review.

**CI parity:** The same `detekt` and `ktfmt` checks run in CI. A clean local commit should never fail formatting or lint in CI.

### Benchmark Tests

Performance-sensitive code paths have dedicated benchmarks using `kotlinx-benchmark`:

| Benchmark Suite | What It Measures | Regression Threshold |
|----------------|-----------------|---------------------|
| `CryptoBenchmark` | Noise K seal/unseal, X25519 DH, Ed25519 sign/verify, HKDF, ChaCha20-Poly1305 | ±10% vs baseline |
| `WireFormatBenchmark` | FlatBuffers encode/decode for all 12 message types | ±15% vs baseline |
| `RoutingBenchmark` | Route lookup, feasibility check, metric calculation, routing table operations at 500/2000 entries | ±15% vs baseline |
| `TransferBenchmark` | Chunking pipeline, SACK processing, chunk reassembly at 1KB/10KB/100KB | ±10% vs baseline |
| `DedupBenchmark` | Dedup insert/lookup at 10K/25K entries, eviction | ±15% vs baseline |

**Execution:**
```sh
./gradlew :meshlink:benchmark
```

**Design rules:**
- JVM target only (JMH via kotlinx-benchmark). Native benchmarks are post-v1.
- Baselines stored as JSON in `benchmarks/baselines/`. Updated explicitly, not on every run.
- CI runs benchmarks on every PR but **warns only** (no hard gate) — hardware variance across CI runners makes hard thresholds brittle.
- Local runs compare against baseline and print a summary table with pass/warn/fail per suite.

### Conventions
- `kotlin.test` — no external test framework
- Power-assert plugin for enhanced messages
- Golden vectors: hex strings with inline field comments
- Fuzz: fixed seeds for reproducibility

### UI Tests (Maestro)
Android + iOS sample apps: launch, navigation, start/stop, broadcast, settings, MTU, diagnostics, visualizer, health, pause/resume, power mode, memory.

### Multi-Device E2E (`meshlink-e2e`)
Maestro flows on real hardware: discovery, direct messaging, broadcast, bidirectional, handshake diagnostics, restart resilience, encrypted broadcast.

### CI: GitHub Actions

All checks that run locally also run in CI. A clean local commit should never fail CI.

#### Workflows

**`ci.yml` — runs on every push and PR to `main`:**

| Job | Runner | Steps | Gate |
|-----|--------|-------|------|
| **lint** | `ubuntu-latest` | ktfmt check (verify-only, no rewrite) → detekt | Hard fail |
| **test** | `ubuntu-latest` | `./gradlew :meshlink:allTests` (commonTest on JVM) | Hard fail |
| **coverage** | `ubuntu-latest` | `./gradlew :meshlink:koverVerify` + upload HTML report as artifact | Hard fail (100% line + branch) |
| **benchmark** | `ubuntu-latest` | `./gradlew :meshlink:benchmark` → compare vs `benchmarks/baselines/` | Warn only (comment on PR) |

**`release.yml` — runs on version tags (`v*`):**

| Job | Runner | Steps |
|-----|--------|-------|
| **publish-android** | `ubuntu-latest` | `./gradlew :meshlink:publishAllPublicationsToMavenCentralRepository` |
| **publish-ios** | `macos-latest` | `./gradlew :meshlink:assembleMeshLinkXCFramework` → create GitHub Release with XCFramework asset |

#### Job Dependency Graph

```
ci.yml:
  lint ───┐
  test ───┼──→ coverage
  benchmark (independent, never blocks merge)

release.yml:
  publish-android ──→ (parallel)
  publish-ios ──────→ (parallel)
```

`coverage` runs after `test` passes (same Gradle build, avoids duplicate compilation). `benchmark` runs in parallel — never blocks merge.

#### Runner Configuration

| Concern | Decision | Rationale |
|---------|----------|-----------|
| JDK | Zulu 21 via `actions/setup-java` | LTS, free, ARM builds available |
| Gradle | `gradle/actions/setup-gradle` with build cache | Caches `~/.gradle/caches` + local build cache across runs. Typical CI time: 2–4 min for lint+test+coverage. |
| libsodium | Pre-installed via `apt-get install libsodium-dev` (Linux) or bundled in AAR/XCFramework | No manual build step |
| Kotlin/Native | Not needed — no shipping native targets | Saves ~3 min of Kotlin/Native compiler download |

#### Secrets

| Secret | Purpose | Used By |
|--------|---------|---------|
| `MAVEN_CENTRAL_USERNAME` | Sonatype OSSRH publishing | `release.yml` |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype OSSRH publishing | `release.yml` |
| `SIGNING_KEY` | GPG key for Maven Central artifact signing | `release.yml` |
| `SIGNING_PASSWORD` | GPG key passphrase | `release.yml` |

No secrets needed for CI — all checks run on public dependencies.

#### Benchmark PR Comments

The benchmark job compares results against `benchmarks/baselines/*.json` and posts a PR comment with a summary table:

```
| Suite             | Metric         | Baseline | Current | Delta  | Status |
|-------------------|----------------|----------|---------|--------|--------|
| CryptoBenchmark   | NoiseK seal    | 0.12ms   | 0.13ms  | +8.3%  | ✅     |
| RoutingBenchmark  | lookup/2000    | 1.4µs    | 2.1µs   | +50%   | ⚠️     |
```

`⚠️` = exceeds threshold (warn only). Baselines are updated explicitly via `./gradlew :meshlink:benchmark --update-baseline` and committed — never auto-updated by CI.

#### Branch Protection Rules

`main` branch requires:
- All `ci.yml` jobs pass (lint, test, coverage)
- At least 1 approving review
- No force-push
- Benchmark job is **not** required (warn-only)
