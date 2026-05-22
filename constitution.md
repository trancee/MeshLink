# MeshLink Constitution

## Core Principles

### I. Rigorous Code Quality

MeshLink is a cryptographic protocol library; code quality requirements
are mandatory because subtle defects create exploitable vulnerabilities.

- All production code MUST pass Detekt with zero suppressed issues.
  Suppressions in test code MUST include inline justification.
- All code MUST be formatted by ktfmt before commit. Manual style
  deviations are prohibited.
- The public API surface MUST remain tracked by Binary Compatibility
  Validator (BCV). Any `.api` diff MUST include a version-bump rationale
  in the PR description or linked planning artifact.
- Kotlin `explicitApi()` mode MUST remain enabled. All public
  declarations MUST declare explicit visibility modifiers and return
  types. Enforced in `meshlink/build.gradle.kts`.
- Merged code MUST NOT contain `TODO` comments. Unfinished work MUST be
  tracked as issues or spec items instead.
- Dependencies MUST use exact versions. Disclosed transitive dependency
  vulnerabilities MUST be overridden within the next scheduled sprint,
  or within 14 calendar days when no sprint cadence exists.
- Code MUST be adequately commented for humans to easily understand its
  purpose and function.

**Rationale**: Static guarantees and explicit contracts catch failure
modes that runtime testing alone cannot.

### II. Exhaustive Testing Standards

Every branch in MeshLink carries protocol meaning and MUST be testable.

- CI MUST enforce 100% line and branch coverage with zero
  `@CoverageIgnore` annotations. Enforced in `meshlink/build.gradle.kts`
  via Kover verification rules.
- Tests MUST use Power-assert for assertion diagnostics. Plain
  `assertEquals` is permitted only for structural comparisons where
  Power-assert cannot add diagnostic value.
- Tests in the default contributor loop MUST be self-validating and
  repeatable. Physical-device validation, proof apps, and retained
  benchmarks MUST remain explicit opt-in surfaces, not replacements for
  automated regression tests.
- Test code SHOULD keep Arrange / Act / Assert phases visually distinct
  so setup, action, and assertions stay obvious to the maintainer.
- Protocol correctness MUST be validated against Wycheproof test vectors
  for ChaCha20-Poly1305, Ed25519, X25519, HKDF, and HMAC-SHA256.
- Multi-node integration tests MUST use the canonical virtual harness
  for the project, currently `MeshTestHarness` with
  `VirtualMeshTransport`, instead of real BLE hardware.
- Validation logic MUST NOT use `require()` with string interpolation;
  use explicit condition checks and throws instead.
- Long-running coroutine logic MUST NOT use `while(isActive)` loops;
  use structured coroutine patterns instead.
- Every `when` expression over a closed set MUST be exhaustive.
- Benchmarks MUST exist for benchmark-covered operations: AEAD
  encrypt/decrypt, routing table lookup, and wire codec encode/decode.
- Benchmark suites MUST run via kotlinx-benchmark on the JVM target.

**Rationale**: An uncovered branch in replay protection, key derivation,
or routing is a correctness and security risk.

### III. User Experience Consistency

MeshLink's public developer experience MUST behave the same across
Android and iOS.

- The primary public API surface, currently `MeshLinkApi`, MUST be
  identical in shape across all targets. Platform differences MUST stay
  behind consumer-invisible `expect/actual` implementations.
- Configuration MUST use one cross-platform DSL builder, currently
  `meshLinkConfig`. Platform-specific inputs MUST be injected through
  factory functions, not DSL branches.
- Diagnostic events MUST use one shared cross-platform catalog of 26
  codes with identical severity tiers and payload shapes on all
  platforms. Platform-only codes require constitutional amendment.
- Error reporting MUST use sealed exception hierarchies defined in
  `commonMain`. Platform exceptions MUST be wrapped and MUST NOT leak to
  consumers.
- State transitions (Uninitialized → Running → Paused/Stopped) MUST
  behave identically and MUST emit the same event sequence on every
  platform.
- Documentation parity is required: when Android documentation is added
  or changed for a public API or workflow, the corresponding iOS
  documentation for that same public API or workflow MUST be added or
  updated in the same change set.

**Rationale**: MeshLink's core promise is one consistent multiplatform
library, not two loosely aligned platform experiences.

### IV. Performance Requirements

MeshLink MUST meet quantified performance targets on resource-
constrained mobile devices.

- **Throughput**: Single-hop L2CAP transfer MUST sustain ≥80 KB/s on
  Android (Pixel 6+) and ≥60 KB/s on iOS (iPhone 12+).
- **Latency**: End-to-end delivery for a 1-hop, 256-byte message MUST
  complete within 50 ms at p95 after connection establishment.
- **Memory**: Steady-state heap allocation MUST NOT exceed 8 MB with up
  to 8 connected peers and an active routing table.
- **Battery**: In the LOW power tier, scan duty cycle MUST NOT exceed 5%
  and connection intervals MUST be ≥500 ms.
- **Cold start**: Time from `mesh.start()` to first advertisement MUST
  be <500 ms on both platforms.
- **Routing convergence**: Babel route tables MUST converge within 3
  seconds for a 10-node topology change measured through the canonical
  virtual transport.
- **Wire codec**: Encode and decode operations MUST complete in <1 μs
  per message on the JVM benchmark target.
- All performance targets MUST be validated by automated benchmarks.
  Regressions greater than 10% from the most recent benchmark result
  committed to the repository for that operation, or from the baseline
  explicitly linked in the planning artifact when no committed result
  exists, MUST block merge.

**Rationale**: BLE mesh networking operates under hard power, latency,
and memory limits that require explicit and enforceable budgets.

### V. Maintainable Design and Change Isolation

MeshLink MUST prefer designs that solve the current problem clearly and
keep future changes local.

- Changes MUST prefer the simplest design that satisfies the approved
  spec today. Speculative extension points, generic frameworks, and
  configuration knobs for uncommitted future requirements are
  prohibited.
- Modules, classes, and files MUST keep one clear reason to change.
  Business logic, platform glue, tooling, UI, and benchmark concerns
  MUST remain separated so changes stay local.
- New abstractions MUST be introduced only to remove demonstrated
  duplication, isolate a known change hotspot, or preserve a stable
  public contract. A single use site or guessed future need is
  insufficient.
- Coupling MUST be minimized and cohesion maximized. Cross-module code
  MUST depend on small contracts and stable APIs rather than deep object
  traversal or incidental implementation details.
- Prefer composition, factories, strategies, and small interfaces over
  inheritance for behavior sharing. Inheritance is allowed only when the
  subtype remains substitutable for the base type without surprising
  callers.
- Volatile implementation details MUST be hidden behind internal types,
  interfaces, or `expect`/`actual` boundaries so refactors do not leak
  across module boundaries.
- Mutating operations and read-only queries SHOULD remain clearly
  separated in naming and behavior. Commands MAY return status or domain
  results, but they MUST NOT masquerade as passive queries.
- Each change SHOULD leave touched code, tests, or docs at least as
  clear as before. Additive complexity without local cleanup requires
  explicit rationale in the planning artifact or PR.

**Rationale**: MeshLink evolves under security, portability, and proof
constraints; simple, cohesive, and deletable designs reduce the cost and
risk of change.

## Quality Gates

Every change MUST satisfy these release-blocking gates before merge.

- All work MUST occur on feature branches. Direct commits to `main` are
  prohibited.
- Every PR MUST pass the coverage gate required by Principle II.
- Every PR MUST pass the formatting and static-analysis gates required
  by Principle I.
- YAML files and workflow definitions MUST be validated with
  `yamllint`. Ruby-based YAML validation commands are prohibited.
- Every PR MUST pass the API compatibility and required platform test
  gates required by Principles I–III.
- Every PR MUST provide the benchmark evidence required by Principle IV.
- Every PR that introduces a new abstraction layer, extension point, or
  inheritance hierarchy MUST explain the current requirement,
  demonstrated duplication, or change hotspot it addresses, per
  Principle V.
- Every PR review MUST include the `plan-template.md` Constitution Check
  section, or a linked artifact containing the same
  principle-by-principle checklist.
- Any `.api` diff MUST include the version-bump rationale required by
  Principle I.
- Public API changes MUST satisfy the KDoc requirement of Principle III
  in the same change set.
- Documentation changes MUST satisfy the documentation-parity
  requirement of Principle III in the same change set.
- Dependency vulnerability fixes MUST merge within 5 business days of
  disclosure.
- Critical issues (CVSS ≥9.0) MUST merge within 48 hours.
- Every commit, including hook-generated and auto-commit commits, MUST
  use Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`,
  `refactor:`, `perf:`, `chore:`). Non-conforming commits MUST be
  amended before merge.

## Technical Constraints

All features and changes MUST respect these non-negotiable boundaries.

- All shared logic MUST reside in `commonMain`. Platform source sets
  (`androidMain`, `iosMain`) MUST contain only `actual`
  implementations and platform glue.
- The library MUST function with zero internet connectivity. No feature
  may introduce a server-side requirement.
- Minimum supported platform versions are Android API 29 and iOS 15.
  APIs exclusive to higher versions MUST use runtime guards.
- All shipped cryptographic operations MUST use the project's own
  provider abstraction, currently `CryptoProvider`, validated against
  Wycheproof vectors. No external crypto library may ship in the
  released artifact.
- Deployed FlatBuffers wire formats MUST remain backward compatible.
  Breaking wire changes require a major version bump and a migration
  period.
- The runtime dependency budget is exactly one runtime dependency,
  `kotlinx-coroutines-core`. Adding another runtime dependency requires
  constitutional amendment.

## Governance

This constitution is the supreme governing document for MeshLink
development.

- Any principle change MUST be proposed in writing before merge.
- Amendment proposals MUST include rationale, impact assessment,
  migration plan, and MUST record documented approval.
- Constitutional versioning MUST follow semantic versioning:
  - MAJOR: Principle removal or incompatible redefinition.
  - MINOR: New principle or materially expanded guidance.
  - PATCH: Clarification, typo fix, structural compression, or other
    non-semantic refinement.
- When guidance conflicts, this constitution MUST take precedence over
  READMEs, guides, and comments.
- Commit-producing automation, templates, and hooks MUST default to
  Conventional Commit messages and MUST be updated in the same change
  set when commit policy changes.
- Day-to-day conventions that do not rise to constitutional level MUST
  live in `docs/` or other project documentation.

**Version**: 1.1.0 | **Ratified**: 2026-04-30 | **Last Amended**: 2026-05-22
