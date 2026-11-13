# Emit and export cataloged diagnostics

Status: ready-for-agent
Type: AFK
Blocked by: 53

## Behavior

`diagnostics: Flow<DiagnosticEvent>` emits every v1 code of ADR 0020 at its site, with a stable `ML` code, a severity and redacted typed fields. Repeats are coalesced and the buffer is bounded. `diagnosticsSnapshot()` returns the current redacted state. `exportDiagnostics()` returns a JSON report that the app owns. `diagnostics.debug` and `diagnostics.platformLog` are config values, both off by default. One Kotlin source generates the diagnostic index in Markdown and JSON, and the troubleshooting pages list their codes by symptom.

## Spec and ADR references

- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md#decision), all eight decisions: code shape and ranges (1), severity (2), privacy with `PeerRef` and `LinkRef` (3), flood control (4), snapshot (5), field tooling with `exportDiagnostics()` and opt-in `platformLog` (6), the v1 code table (7) and troubleshooting pages (8).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md#decision) decision 12 and its 2026-11-08 [amendment](../../../docs/adr/0007-public-api-shape.md#amendments); decision 11: every Flow is exception-free.
- [ADR 0006](../../../docs/adr/0006-documentation-architecture.md#decision) decisions 3 (one source, generated copies), 4 (diagnostic index in Markdown and JSON) and 6 (troubleshooting built on codes).
- [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) and Constitution S1 and C5 in [`CONSTITUTION.md`](../../../CONSTITUTION.md).
- [Device-lab lessons](../../../docs/agents/lessons.md#device-lab): GATT 133/147, "scan failed 2", and the Kotlin/Native `NSLog` varargs crash.

## Acceptance criteria

- [ ] `DiagnosticEvent` is a closed hierarchy. Each code of ADR 0020 decision 7 is one subclass with its typed fields, KDoc meaning, likely cause, fix, related setting or platform limit, and symptom group. A code that earlier tickets already emit keeps its number and moves into this hierarchy.
- [ ] Every code is emitted at its site in common code or in the Android and iOS adapters. A test per code triggers it, on `VirtualAir` or through the adapter fakes. If the full list does not fit one session, the ticket goes back to `needs-triage` and is split by code range (slicing rule 4).
- [ ] Any change to the code list amends ADR 0020 in the same PR (its decision 7 allows changes until 0.1.0).
- [ ] `Debug` events appear only with `diagnostics.debug` on. A test covers both settings.
- [ ] `PeerRef` is a 4 B keyed BLAKE2b hash of the NodeId under a key drawn on each `start()` through `CryptoProvider`. Tests show equal refs within a start and different refs across starts. `LinkRef` is a per-session counter.
- [ ] A redaction test serializes every event type and the export. It asserts no key, payload, routing or conversation tag, advertisement identifier, lookup salt, certificate or Bluetooth address appears (S1).
- [ ] Flood control in virtual time: repeats of one code and reference within 10 s give one event with a count. At 256 buffered events the oldest is dropped, then `ML7001` reports the count.
- [ ] `diagnosticsSnapshot()` returns the fields of ADR 0020 decision 5, with no time series.
- [ ] `exportDiagnostics()` returns JSON with the snapshot, the last 500 events, the MeshLink and protocol versions, the platform and the OS version. It adds no new dependency (E3). Nothing is sent off the device. A golden test fixes the JSON shape, and the generated reference documents it (C5).
- [ ] `diagnostics.platformLog` writes events to Logcat on Android and to `os_log` on iOS. In `os_log`, every field other than codes and numbers is private. The iOS call stays a thin adapter listed in the CI-named adapter file. It formats in Kotlin and never passes Kotlin strings as C varargs.
- [ ] A JVM test generates `docs/reference/diagnostics.md` (one stable anchor per code) and `docs/agents/diagnostics.json`, and fills the generated regions of `docs/how-to/troubleshoot-peers-not-found.md`, `troubleshoot-messages-not-delivered.md` and `troubleshoot-high-battery-use.md`. It fails on a difference. `./gradlew updateReference` rewrites them.
- [ ] The troubleshooting pages explain the common platform codes (133, 147, scan failure 2). Their hand-written text links each code's anchor.
- [ ] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
./gradlew updateReference && git diff --exit-code docs
npx --yes markdownlint-cli2@0.24.1 "docs/**/*.md"
typos
lychee --offline .
```

## Docs to update

- `docs/reference/diagnostics.md` and `docs/agents/diagnostics.json` (generated; new).
- `docs/how-to/troubleshoot-peers-not-found.md`, `troubleshoot-messages-not-delivered.md` and `troubleshoot-high-battery-use.md` (new; generated regions plus hand-written guidance).
- `docs/reference/configuration.md` (regenerated with `diagnostics.debug` and `diagnostics.platformLog`).
- [`docs/README.md`](../../../docs/README.md): link the diagnostic reference and the troubleshooting pages.
- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md): an amendment only if the code list changes.

## Devices needed

None. Platform codes are proved through the Robolectric shadows and the `iosTest` fakes. Device runs read the logs later, in tickets 62 and 63.
