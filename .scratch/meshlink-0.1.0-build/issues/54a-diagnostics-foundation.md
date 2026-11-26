# Emit diagnostics as a public cataloged event

Status: done
Type: AFK
Blocked by: 53

## Behavior

Split from ticket [54](54-diagnostics-catalog.md). `MeshLink.diagnostics: Flow<DiagnosticEvent>` replaces the internal `LinkDiagnostic` and `linkDiagnostics`. Each event has a stable `ML` code, a severity and redacted typed fields. Peers appear only as `PeerRef` and unauthenticated links as `LinkRef`. Repeats are coalesced and the buffer is bounded. `diagnostics.debug` is a config value, off by default. One Kotlin source generates the diagnostic index in Markdown and JSON.

The seven codes emitted today (ML3003, ML3004, ML3005, ML3007, ML4001, ML4002, ML7002) keep their numbers and move into the hierarchy. This ticket adds the lifecycle codes ML1001–ML1003 and ML7001. Later codes arrive in 54b and 54c.

## Spec and ADR references

- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md#decision) decisions 1 (code shape), 2 (severity), 3 (privacy), 4 (flood control) and 7 (code table).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md#decision) decision 12 and its 2026-11-08 amendment; decision 11: every Flow is exception-free.
- [ADR 0006](../../../docs/adr/0006-documentation-architecture.md#decision) decisions 3 and 4 (one source, generated index in Markdown and JSON).
- Constitution S1 and C5 in [`CONSTITUTION.md`](../../../CONSTITUTION.md).

## Acceptance criteria

- [x] `DiagnosticEvent` is a public closed hierarchy with `code`, `severity` (`Debug`, `Info`, `Warning`, `Error`) and typed fields. Each subclass's KDoc gives its meaning, likely cause, fix, related setting or platform limit, and symptom group.
- [x] The seven existing codes move into it with their numbers; `LinkDiagnostic` and `MeshLink.linkDiagnostics` are deleted and every caller, including `meshlink-testing`, uses `diagnostics`.
- [x] ML1001 `Started`, ML1002 `Stopped`, ML1003 `UnsecuredProfile` (on every `Unsecured` start) and ML7001 `DiagnosticsDropped` are emitted, each with a test.
- [x] `Debug` events appear only with `diagnostics.debug` on. A test covers both settings.
- [x] `PeerRef` is a 4 B keyed BLAKE2b hash of the NodeId under a key drawn on each `start()` through `CryptoProvider`. Tests show equal refs within a start and different refs across starts. `LinkRef` is a per-session counter.
- [x] Flood control in virtual time: repeats of one code and reference within 10 s give one event with a count. At 256 buffered events the oldest is dropped, then `ML7001` reports the count.
- [x] A redaction test serializes every event type. It asserts no key, payload, routing or conversation tag, advertisement identifier, lookup salt, certificate or Bluetooth address appears (S1). Later tickets extend it to their codes.
- [x] A JVM test generates `docs/reference/diagnostics.md` (one stable anchor per code) and `docs/agents/diagnostics.json` from the hierarchy and fails on a difference. `./gradlew updateReference` rewrites them. `docs/reference/configuration.md` is regenerated with `diagnostics.debug`.
- [x] Any field that differs from ADR 0020 decision 7 (for example `ConnectFailed` keeping `reason` until 54c adds `platformCode`) is recorded in an ADR 0020 amendment in the same PR.
- [x] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
./gradlew updateReference && git diff --exit-code docs
```

## Docs to update

- `docs/reference/diagnostics.md` and `docs/agents/diagnostics.json` (generated; new); `docs/reference/configuration.md` (regenerated).
- [`docs/README.md`](../../../docs/README.md): link the diagnostic reference.
- `CHANGELOG.md`; ADR 0020 amendment if fields change.

## Devices needed

None.
