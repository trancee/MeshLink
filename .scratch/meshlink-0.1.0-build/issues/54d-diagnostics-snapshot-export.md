# Snapshot and export diagnostics

Status: ready-for-agent
Type: AFK
Blocked by: 54c

## Behavior

Split from ticket [54](54-diagnostics-catalog.md). `diagnosticsSnapshot()` returns the current redacted state. `exportDiagnostics()` returns a JSON report that the app owns; MeshLink sends nothing off the device.

## Spec and ADR references

- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md#decision) decisions 5 (snapshot) and 6 (field tooling).
- Constitution S1, C5 and E3 in [`CONSTITUTION.md`](../../../CONSTITUTION.md).

## Acceptance criteria

- [x] `diagnostics.snapshot()` returns the fields of ADR 0020 decision 5: configuration, profile and density class; each link with bearer, current PHY (null where the platform can't tell, as on iOS; ticket 54c Q7), MTU and role; queue and held-message counts and certificate cache size; cumulative counters. No time series.
- [x] `diagnostics.export()` returns JSON with the snapshot, the last 500 events, the MeshLink and protocol versions, the platform and the OS version. It adds no new dependency (E3).
- [x] A golden test fixes the JSON shape, and the generated reference documents it (C5).
- [x] The redaction test covers the snapshot and the export (S1).
- [x] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
./gradlew updateReference && git diff --exit-code docs
```

## Docs to update

- Generated reference for the export shape; `CHANGELOG.md`.

## Devices needed

None.

## Decisions (2026-11-26, owner accepted every recommendation)

- **Q1 PHY:** `RadioLink.phy: Phy?` (`Le1M`, `Le2M`, `LeCoded`). Android reads it with `readPhy` at open and follows `onPhyUpdate`; iOS reports `null`.
- **Q2 Counters:** the hub counts every reported event by code, before the Debug filter and coalescing, plus three explicit counters: `framesSent`, `framesRelayed`, `handshakesSucceeded`. All reset on each `start()`.
- **Q3 API:** `suspend fun diagnosticsSnapshot(): DiagnosticsSnapshot` and `suspend fun exportDiagnostics(): String`, callable in any state. The snapshot holds `configuration` (`describe()` lines), `profile`, `densityClass` (`null` while stopped), `links` (`peer`, `bearer`, `phy`, `mtu`, `role`), `outgoing`, `held`, `inbox`, `certificates`, `events` (count by code) and the three explicit counters.
- **Q4 JSON:** a hand-written encoder in commonMain, with no new dependency. The top level carries `"format": 1`. Each event has `code`, `name`, `severity`, `count`, `fields` and `at`, the wall-clock milliseconds at emission, recorded by the hub.
- **Q5 Versions:** the MeshLink version is generated from `meshlink.version` into a generated common source set. The protocol version is `LinkPolicy.PROTOCOL_VERSION`. `PlatformPort` gains the platform and OS version (`Android <release> (API n)`, `iOS <major.minor.patch>`), with no device model.
- **Q6 History:** a ring of 500 events after the Debug filter and coalescing, kept across starts and cleared only at creation. ML7001 is excluded.
- **Q7 Golden:** `DiagnosticsExportReferenceTest`, beside `DiagnosticReferenceTest`, generates `docs/reference/diagnostics-export.md`, with a field table and example JSON, from a fixed fixture.
- **Q3 amended (owner chose A, 2026-11-27):** detekt's 11-function limit on `MeshLink` (no suppressions) groups the API under one `MeshDiagnostics` type (`Diagnostics` is already the config type): `meshLink.diagnostics.events` (the 54a flow), `suspend fun snapshot()` and `suspend fun export(): String`. The snapshot groups its fields as `queues` (`outgoing`, `held`, `inbox`, `certificates`) and `counters` (`events`, `framesSent`, `framesRelayed`, `handshakesSucceeded`), because of detekt's 6-parameter constructor limit.
- **Q8 Redaction:** the snapshot's configuration shows `meshId = <redacted>`. Only links past their hellos are listed, by `PeerRef`. `DiagnosticRedactionTest` covers the snapshot and the export.
