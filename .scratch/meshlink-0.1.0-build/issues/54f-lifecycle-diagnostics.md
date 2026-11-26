# Emit the lifecycle diagnostics

Status: needs-triage
Type: AFK
Blocked by: 54c

## Behavior

Split from ticket [54c](54c-platform-diagnostics.md) (owner decision, 2026-11-26, Q1). MeshLink observes the operating system's treatment of the app and emits:

- ML2004 `BackgroundDiscoveryDegraded`: discovery runs in the degraded background mode, as an iPhone does once its app leaves the foreground (ADR 0010: background-to-background iPhone discovery succeeded in 62% of 40 s trials);
- ML8002 `AppSuspendedByOs`: the operating system suspended the app, observed when it resumes;
- ML8003 `DozeActive`: Android entered Doze, which stretched a 4 s on / 4 s off scan schedule to 30 s gaps (ADR 0008, ADR 0010).

## Questions to settle in triage

- How each platform observes the app leaving the foreground without a new dependency: `UIApplication` notifications on iOS; activity callbacks or `ProcessLifecycleOwner` (a new dependency) on Android.
- How MeshLink detects a suspension: a timer that fires late, and which clock, since `TimeSource.Monotonic` may not count device sleep on either platform; whether `AppSuspendedByOs` carries the gap.
- Whether `DozeActive` reports entering Doze only, or entering and leaving, and whether a node that starts in Doze emits it.
- Whether ML2004 also applies to Android, which scans with hardware filters and keeps discovering with the screen off.

## Spec and ADR references

- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md#decision) decision 7.
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) "Background timing" and its promise of background-degraded diagnostics.
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) "Background duty".

## Acceptance criteria

- [ ] Each code is emitted from its platform observation, with a test through the Robolectric shadows or the `iosTest` fakes.
- [ ] The redaction test covers every new event type (S1).
- [ ] Any change to the code list or fields amends ADR 0020 in this PR.
- [ ] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
./gradlew updateReference && git diff --exit-code docs
```

## Docs to update

- Generated index (regenerated); `CHANGELOG.md`; ADR 0020 amendment if the list or fields change.

## Devices needed

An Android phone in Doze and a locked iPhone, to confirm the observations; the device runs belong to tickets 62 and 63.
