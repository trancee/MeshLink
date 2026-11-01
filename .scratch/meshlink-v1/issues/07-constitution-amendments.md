# Which constitution amendments should MeshLink adopt?

Type: grilling
Status: resolved

## Question

Propose amendments in V2 format {rationale, impact, migration, approval}. Candidates: port guidance/kotlin.md (required by AGENTS.md START but missing); a radio/real-device evidence rule (CI vs simulator vs device claims); an AI-optimized documentation requirement; protocol-spec-before-code rule. Resolved when the user approves or rejects each.

## Answer

Approved by the user on 2026-10-31. Applied, uncommitted, on branch `docs/constitution-3.1.0`.

- **Ownership:** MeshLink-only fork of cairn's constitution 3.0.1, with its own version line. It is now **3.1.0** (MINOR), with an amendment record in `CONSTITUTION.md`.
- **T9 expanded:** a hardware-dependent claim (interop, connection limits, throughput, latency, range, power, background) needs an evidence record: devices with OS/firmware, covered platform pairs, commit, method, raw results and date. Without one, the claim is labeled "unverified".
- **C10 added:** a published wire protocol needs an implementation-independent normative spec plus conformance vectors. The spec is the authority; spec and vectors change in the same change as the wire, or earlier. An implementation that diverges has a defect.
- **O8 added:** public docs have a human layer (Diátaxis) plus an agent layer (machine-oriented index, contract reference, diagnostic index). Both are generated from, or CI-checked against, one source; no hand-maintained divergent copies.
- **Kotlin guidance:** ported cairn's `guidance/kotlin.md` to root `KOTLIN.md`, plus MeshLink constraints (Xcode 16.4, Kotlin `2.2.20` fallback, the libimobiledevice host path). Convention: every stack guide lives at root `<STACK>.md`, for example `SWIFT.md`. `AGENTS.md` START was updated to match.
- **Deferred:** whether T3 (100% coverage) needs amending because Kover can't measure Kotlin/Native (iOS) code. The toolchain-baseline ticket verifies the coverage options first.
- **Rejected as constitution rules:** the dependency approval register (belongs in `PROJECT.md`; E3 already exists), privacy/metadata leakage (threat-model ticket), and "most modern toolchain" (already covered by `KOTLIN.md`, "latest stable").
- No PR templates or hooks exist yet, so V4 had nothing to update.
