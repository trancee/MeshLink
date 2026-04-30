<!--
Sync Impact Report
==================
Version change: (new) → 1.0.0
Modified principles: N/A (initial ratification)
Added sections:
  - Principle I: Rigorous Code Quality
  - Principle II: Exhaustive Testing Standards
  - Principle III: User Experience Consistency
  - Principle IV: Performance Requirements
  - Section: Technical Constraints
  - Section: Development Workflow
  - Section: Governance
Removed sections: N/A
Templates requiring updates:
  - .specify/templates/plan-template.md ✅ (Constitution Check section compatible)
  - .specify/templates/spec-template.md ✅ (Success Criteria aligns with performance principle)
  - .specify/templates/tasks-template.md ✅ (Phase structure supports testing-first principle)
Follow-up TODOs: None
-->

# MeshLink Constitution

## Core Principles

### I. Rigorous Code Quality

Every module in MeshLink MUST maintain verifiable code quality through
static analysis, formatting enforcement, and binary compatibility
tracking. This is non-negotiable for a cryptographic protocol library
where subtle defects create exploitable vulnerabilities.

- All code MUST pass Detekt static analysis with zero suppressed issues
  in production source sets. Suppressions in test code require inline
  justification.
- All code MUST be formatted by ktfmt before commit. No manual style
  deviations are permitted.
- The public API surface MUST be tracked by Binary Compatibility
  Validator (BCV). Any `.api` file diff requires explicit review and
  version bump rationale.
- Kotlin `explicitApi()` mode MUST remain enabled. All public
  declarations require explicit visibility modifiers and return types.
- No `TODO` comments are permitted in merged code. Unfinished work MUST
  be tracked as issues or spec items, not inline markers.
- Dependencies MUST be pinned to exact versions. Transitive dependency
  vulnerabilities MUST be addressed via resolution strategy overrides
  within one sprint of disclosure.

**Rationale**: A cryptographic networking library tolerates zero ambiguity
in its public contract and zero leniency in code hygiene. Static
guarantees catch classes of bugs that runtime testing cannot.

### II. Exhaustive Testing Standards

MeshLink MUST maintain 100% line and branch coverage as measured by
Kover on every commit. This is achievable and sustainable because the
codebase follows deliberate patterns that eliminate untestable bytecode
branches.

- 100% line and branch coverage MUST be enforced in CI. Zero
  `@CoverageIgnore` annotations are permitted.
- Tests MUST use Power-assert for assertion diagnostics. Plain
  `assertEquals` is acceptable only for collection/structural
  comparisons where Power-assert cannot introspect.
- Protocol correctness MUST be validated against Wycheproof test vectors
  for all cryptographic primitives (ChaCha20-Poly1305, Ed25519, X25519,
  HKDF, HMAC-SHA256).
- Integration tests MUST use `MeshTestHarness` and
  `VirtualMeshTransport` to simulate multi-node topologies without
  real BLE hardware.
- Code patterns that create untestable branches are prohibited:
  - No `require()` with string interpolation (use explicit if/throw).
  - No `while(isActive)` loops (use structured coroutine patterns).
  - No `when` without exhaustive branches.
- Benchmarks MUST exist for all hot-path operations (AEAD
  encrypt/decrypt, routing table lookup, wire codec encode/decode) and
  MUST run via kotlinx-benchmark on the JVM target.

**Rationale**: In a protocol implementation every branch has semantic
meaning. An uncovered branch in replay protection, key derivation, or
routing is not missing coverage — it is a potential exploit. The 100%
rule is the floor, not the ceiling.

### III. User Experience Consistency

MeshLink MUST present a uniform, predictable developer experience across
Android and iOS through a single Kotlin Multiplatform API surface. The
library is consumed by app developers who expect platform-native
behavior without platform-specific surprises.

- The public API (`MeshLinkApi`) MUST be identical in shape across all
  targets. Platform differences MUST be confined to `expect/actual`
  implementations invisible to consumers.
- Configuration MUST use a single DSL builder (`meshLinkConfig`) that
  works identically on both platforms. Platform-specific settings (e.g.,
  Android Context) are injected via factory functions, not DSL branches.
- Diagnostic events MUST use the same 27 codes, severity tiers, and
  payload shapes on all platforms. No platform-only diagnostic codes are
  permitted without constitutional amendment.
- Error reporting MUST use sealed exception hierarchies defined in
  `commonMain`. Platform exceptions MUST be wrapped — never leaked to
  consumers.
- State machine transitions (Idle → Scanning → Connected → Meshed) MUST
  behave identically and emit the same event sequence regardless of
  platform.
- Documentation (KDoc, tutorials, how-to guides) MUST cover both
  platforms. A guide that exists for Android MUST have an iOS
  counterpart and vice versa.

**Rationale**: MeshLink's value proposition is "one codebase, two
platforms." If the developer experience diverges between Android and iOS,
the library has failed its core promise. Consistency is enforced
structurally (common API surface) rather than by convention.

### IV. Performance Requirements

MeshLink MUST meet quantified performance targets on resource-
constrained mobile devices. BLE mesh networking operates under tight
power and latency budgets; violations degrade user experience and drain
batteries.

- **Throughput**: L2CAP data transfer MUST sustain ≥80 KB/s on Android
  (Pixel 6+) and ≥60 KB/s on iOS (iPhone 12+) for single-hop
  connections.
- **Latency**: End-to-end message delivery (1 hop, 256 bytes) MUST
  complete within 50 ms at p95 after connection establishment.
- **Memory**: Steady-state heap allocation MUST NOT exceed 8 MB with up
  to 8 connected peers and active routing table.
- **Battery**: In the LOW power tier, scan duty cycle MUST NOT exceed 5%
  and connection intervals MUST be ≥500 ms.
- **Cold start**: Time from `mesh.start()` to first advertisement MUST
  be <500 ms on both platforms.
- **Routing convergence**: Babel route table MUST converge within 3
  seconds for a 10-node topology change (measured via
  VirtualMeshTransport).
- All performance targets MUST be validated by automated benchmarks.
  Regressions exceeding 10% from the established baseline MUST block
  merge.
- Wire codec operations (encode/decode) MUST complete in <1 μs per
  message on JVM benchmark target.

**Rationale**: Mobile BLE devices have hard physical constraints. Vague
"be fast" guidance produces drift. Quantified targets create enforceable
gates and prevent death-by-a-thousand-cuts performance regressions.

## Technical Constraints

MeshLink operates under the following non-negotiable technical
boundaries that all features and changes MUST respect:

- **Kotlin Multiplatform only**: All shared logic resides in
  `commonMain`. Platform source sets (`androidMain`, `iosMain`) contain
  only `actual` implementations and platform glue.
- **No server dependency**: The library MUST function with zero internet
  connectivity. No feature may introduce a server-side requirement.
- **Minimum platform versions**: Android API 29 (Android 10), iOS 15.
  No APIs exclusive to higher versions without runtime guards.
- **No third-party crypto**: All cryptographic operations MUST use the
  project's own `CryptoProvider` implementations validated against
  Wycheproof vectors. No external crypto libraries in the shipped
  artifact.
- **Wire protocol stability**: Deployed wire message formats (FlatBuffers
  schemas) MUST maintain backward compatibility. Breaking wire changes
  require a major version bump and migration period.
- **Dependency budget**: The library ships with exactly one runtime
  dependency (`kotlinx-coroutines-core`). Adding any runtime dependency
  requires constitutional amendment.

## Development Workflow

All contributors and AI agents MUST follow this workflow for changes:

- **Feature branches**: All work occurs on feature branches. Direct
  commits to `main` are prohibited.
- **CI gates**: Every PR MUST pass: Kover 100% coverage, Detekt clean,
  ktfmt formatted, BCV API check, all tests green on Android + JVM
  targets.
- **Commit hygiene**: Commits follow Conventional Commits format
  (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `perf:`, `chore:`).
- **Documentation co-location**: API changes MUST include KDoc updates
  in the same commit. New features MUST include or reference
  documentation updates.
- **Benchmark validation**: Changes to hot-path code MUST include
  benchmark results demonstrating no regression beyond 10% of baseline.
- **Security patches**: Dependency vulnerability fixes MUST be merged
  within 5 business days of disclosure. Critical (CVSS ≥9.0) within
  48 hours.

## Governance

This constitution is the supreme governing document for MeshLink
development. All practices, tooling decisions, and code changes MUST
comply with its principles.

- **Amendment process**: Any principle change requires a written
  proposal documenting rationale, impact assessment, and migration plan.
  Amendments MUST be reviewed and approved before merge.
- **Versioning**: The constitution follows semantic versioning:
  - MAJOR: Principle removal or incompatible redefinition.
  - MINOR: New principle or materially expanded guidance.
  - PATCH: Clarification, typo fix, or non-semantic refinement.
- **Compliance review**: Every PR review MUST include a constitution
  compliance check. The plan template's "Constitution Check" section
  enforces this at the feature-planning stage.
- **Conflict resolution**: When practices conflict, this constitution
  takes precedence over all other documents (READMEs, guides, comments).
- **Runtime guidance**: For day-to-day development patterns and
  conventions not rising to constitutional level, refer to project
  documentation in `docs/`.

**Version**: 1.0.0 | **Ratified**: 2026-04-30 | **Last Amended**: 2026-04-30
