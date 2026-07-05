# Agent Rules

Operational rules for any agent (human or AI) working in this repository.
This file is the quick-reference checklist; `constitution.md` is the full
authoritative source and takes precedence if anything here seems to conflict.

## Workflow

- Before implementation or best-practice-oriented work, read the relevant
  skill files and include a `Skills Used` summary in the completion report.
- After making repository changes, create a Conventional Commit
  (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `perf:`, `chore:`) before
  moving to another governed task, phase, or command — unless an enabled
  auto-commit hook has already done it.
- All work MUST occur on feature branches. Never commit directly to `main`.
- When a decision is needed, do not choose alone. Present the available
  options clearly and concisely, then wait for the user to decide.

## Code quality (constitution.md § I)

- Zero suppressed Detekt issues in production code; justify any test-code
  suppression inline.
- Format with ktfmt before every commit — no manual style deviations.
- Track the public API with Binary Compatibility Validator; any `.api` diff
  needs a version-bump rationale.
- Keep Kotlin `explicitApi()` enabled; all public declarations need explicit
  visibility and return types.
- No `TODO` comments in merged code — track unfinished work as issues/specs.
- Pin exact dependency versions; fix disclosed vulnerabilities within the
  sprint or 14 days.

## Testing (constitution.md § II)

- 100% line/branch coverage in CI, zero `@CoverageIgnore`.
- Use Power-assert for diagnostics; plain `assertEquals` only for structural
  comparisons.
- Keep default-loop tests self-validating and repeatable — physical-device
  runs, proof apps, and benchmarks are opt-in extras, not substitutes.
- Structure tests with visually distinct Arrange / Act / Assert phases.
- Validate crypto against Wycheproof vectors (ChaCha20-Poly1305, Ed25519,
  X25519, HKDF, HMAC-SHA256).
- Use `MeshTestHarness` + `VirtualMeshTransport` for multi-node tests, not
  real BLE hardware.
- No `require()` with string interpolation — use explicit condition checks.
- No `while(isActive)` loops — use structured coroutine patterns.
- Every `when` over a closed set must be exhaustive.
- Benchmark AEAD encrypt/decrypt, routing table lookup, and wire codec
  encode/decode via kotlinx-benchmark on the JVM target.

## Cross-platform consistency (constitution.md § III)

- Keep the public API (`MeshLink`) identical in shape across targets;
  platform differences stay behind `expect`/`actual`.
- Configure only through the shared `meshLinkConfig` DSL.
- Use one shared diagnostic event catalog (26 codes) across platforms.
- Wrap platform exceptions in `commonMain` sealed hierarchies — never leak
  them to consumers.
- State transitions and their event sequences must be identical on every
  platform.
- When Android docs change for a public API/workflow, update the matching
  iOS docs in the same change set.

## Performance (constitution.md § IV)

- Throughput ≥80 KB/s (Android), ≥60 KB/s (iOS) single-hop L2CAP.
- 1-hop 256-byte message delivery <50 ms p95.
- Steady-state heap ≤8 MB with up to 8 peers.
- LOW power tier: scan duty cycle ≤5%, connection interval ≥500 ms.
- Cold start to first advertisement <500 ms.
- Babel routing convergence <3 s for a 10-node topology change.
- Wire codec encode/decode <1 μs/message on the JVM benchmark target.
- All targets need automated benchmark evidence; >10% regression from the
  last committed baseline blocks merge.

## Maintainable design (constitution.md § V)

- Prefer the simplest design that satisfies today's approved spec — no
  speculative extension points or unused config knobs.
- One clear reason to change per module/class/file.
- Add new abstractions only to remove demonstrated duplication, isolate a
  known change hotspot, or preserve a stable public contract.
- Favor composition/factories/strategies over inheritance.
- Hide volatile details behind internal types or `expect`/`actual`.
- Keep commands and queries distinct; leave touched code at least as clear
  as you found it.

## Technical constraints (constitution.md § "Technical Constraints")

- Shared logic lives in `commonMain`; platform source sets hold only
  `actual` implementations and glue.
- Zero internet dependency — no feature may require a server.
- Minimum platforms: Android API 29, iOS 15 (guard higher-only APIs).
- All shipped crypto goes through `CryptoProvider`, validated against
  Wycheproof — no external crypto library in the released artifact.
- FlatBuffers wire formats stay backward compatible; breaking changes need
  a major version bump and migration period.
- The shipped `:meshlink` artifact has exactly one runtime dependency,
  `kotlinx-coroutines-core`. Adding another requires constitutional
  amendment.

## Full details

See `constitution.md` for rationale, quality gates, governance/amendment
process, and versioning. Day-to-day conventions below constitutional level
live in `docs/`.
