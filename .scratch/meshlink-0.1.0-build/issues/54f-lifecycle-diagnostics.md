# Emit the lifecycle diagnostics

Status: ready-for-agent
Type: AFK
Blocked by: 54c

## Behavior

Split from ticket [54c](54c-platform-diagnostics.md) (owner decision, 2026-11-26, Q1). MeshLink observes the operating system's treatment of the app and emits:

- ML2004 `BackgroundDiscoveryDegraded`: discovery runs in the degraded background mode, as an iPhone does once its app leaves the foreground (ADR 0010: background-to-background iPhone discovery succeeded in 62% of 40 s trials);
- ML8002 `AppSuspendedByOs`: the operating system suspended the app, observed when it resumes;
- ML8003 `DozeActive`: Android entered Doze, which stretched a 4 s on / 4 s off scan schedule to 30 s gaps (ADR 0008, ADR 0010).

## Triage decisions (owner, 2026-11-26)

- Q1: `PlatformPort` gains `osState`, a `StateFlow<OsState>` of `background` and `doze`, and `suspensions`, a `Flow<Long>` of suspension lengths. The node that holds the radio emits each code when the state turns on, and once at session start if it is already on.
- Q2: ML2004 is iOS only: iOS posts `UIApplication.didEnterBackgroundNotification` for scene-based apps too, and a node starting in the background reads `applicationState` on the main queue. Android, which scans with hardware filters, does not emit it. Warning; symptom "peers not found".
- Q3: ML8002 is iOS only. iOS notifies no suspension, so a 10 s check measured on a clock that counts sleep (`CLOCK_MONOTONIC`) reports a check at least 30 s late as `AppSuspendedByOs(suspendedMillis)`, the lateness, a lower bound. Android does not emit it: a late timer there mostly means the CPU slept, which ML8003 covers. Info; symptom "messages delivered slowly". ADR 0020 gains the field.
- Q4: ML8003 is Android only and covers deep Doze (`ACTION_DEVICE_IDLE_MODE_CHANGED`, `isDeviceIdleMode`, API 23), not light idle. It reports entering only. Info; symptom "peers not found".
- Q5: Robolectric and `iosTest` fakes prove this ticket; device runs stay with tickets 62 and 63.

## Spec and ADR references

- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md#decision) decision 7.
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) "Background timing" and its promise of background-degraded diagnostics.
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) "Background duty".

## Acceptance criteria

- [x] Each code is emitted from its platform observation, with a test through the Robolectric shadows or the `iosTest` fakes.
- [x] The redaction test covers every new event type (S1).
- [x] Any change to the code list or fields amends ADR 0020 in this PR.
- [x] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

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
