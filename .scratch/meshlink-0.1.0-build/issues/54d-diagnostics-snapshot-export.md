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

- [ ] `diagnosticsSnapshot()` returns the fields of ADR 0020 decision 5: configuration, profile and density class; each link with bearer, PHY, MTU and role; queue and held-message counts and certificate cache size; cumulative counters. No time series.
- [ ] `exportDiagnostics()` returns JSON with the snapshot, the last 500 events, the MeshLink and protocol versions, the platform and the OS version. It adds no new dependency (E3).
- [ ] A golden test fixes the JSON shape, and the generated reference documents it (C5).
- [ ] The redaction test covers the snapshot and the export (S1).
- [ ] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

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
