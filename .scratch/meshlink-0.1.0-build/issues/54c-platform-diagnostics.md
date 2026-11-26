# Emit platform diagnostics and write them to the platform log

Status: ready-for-agent
Type: AFK
Blocked by: 54b

## Behavior

Split from ticket [54](54-diagnostics-catalog.md). The Android and iOS adapters report platform status numbers, and MeshLink emits the codes that need them:

- ML2002 `ScanFailed(platformCode)`, ML2005 `AdvertisingFailed(platformCode)`, and the new ML2008 `GattServiceFailed(platformCode)`;
- ML3006 `GattOperationRetried(platformCode)`, and `platformCode` on ML3002 `LinkClosed` and ML3003 `ConnectFailed`;
- ML8001 `ScannerRegistrationFailed`.

The lifecycle codes ML2004, ML8002 and ML8003 moved to ticket [54f](54f-lifecycle-diagnostics.md).

`diagnostics.platformLog`, off by default, writes events to Logcat on Android and to `os_log` on iOS.

## Decisions (owner, 2026-11-26)

- Q1: the lifecycle codes ML2004 `BackgroundDiscoveryDegraded`, ML8002 `AppSuspendedByOs` and ML8003 `DozeActive` move to [54f](54f-lifecycle-diagnostics.md); they need operating-system observation that MeshLink lacks.
- Q2: `RadioResult.Failed`, the radio failure events and `RadioEvent.Disconnected` carry `platformCode: Int?`, null when the platform gave no number. Android passes the raw status; iOS passes `NSError.code` only from `CBErrorDomain`, since `CBATTErrorDomain` numbers collide with it.
- Q3: `RadioEvent.Failed` splits into `ScanFailed` and `ServeFailed`. ML2002 reports scanning only; a serving failure reports the new Warning ML2008 `GattServiceFailed(platformCode)`.
- Q4: ML8001 is emitted beside ML2002 when Android reports scan error 2, `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`.
- Q5: ML3006 is emitted once per Android GATT request that found the stack busy and retried; `platformCode` is 201 on API 33 and later, null on the legacy API. iOS never retries.
- Q6: `LinkClosed.platformCode` is set only for reason `ConnectionLost`; `ConnectFailed.platformCode` is the connect status or the iOS `didFailToConnect` code.
- Q7: `LinkOpened` gets no `phy`; the snapshot of [54d](54d-diagnostics-snapshot-export.md) reports each link's current PHY. ADR 0020 is amended.
- Q8: `platformLog` writes one line per emitted event, such as `ML3002 LinkClosed count=1 platformCode=8 peer=1a2b3c4d reason=ConnectionLost byPeer=true`. Android logs with tag `MeshLink` at the severity's priority. iOS logs to subsystem `ch.trancee.meshlink`, category `diagnostics`: the code, name, count and numbers go in a `%{public}s` part and every other field in a `%{private}s` part, through a C wrapper in a cinterop definition, Kermit's pattern. Formatting lives in common code.

## Spec and ADR references

- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md#decision) decisions 3 (platform status numbers allowed), 6 (`platformLog`) and 7.
- [Device-lab lessons](../../../docs/agents/lessons.md#device-lab): GATT 133/147, "scan failed 2", and the Kotlin/Native `NSLog` varargs crash.

## Acceptance criteria

- [x] Each listed code is emitted from its adapter, with a test through the Robolectric shadows or the `iosTest` fakes.
- [x] The radio contract carries the raw platform status number beside `RadioFailure`; every adapter and fake is migrated.
- [x] `diagnostics.platformLog` writes to Logcat and `os_log`; a test covers on and off. In `os_log`, every field other than codes and numbers is private. The iOS call is a thin adapter listed in the CI-named adapter file; it formats in Kotlin and never passes Kotlin strings as C varargs.
- [x] The redaction test covers every new event type (S1).
- [x] `docs/reference/configuration.md` is regenerated with `diagnostics.platformLog`; any change to the code list amends ADR 0020 in this PR.
- [x] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
./gradlew updateReference && git diff --exit-code docs
```

## Docs to update

- Generated index and configuration reference (regenerated); `CHANGELOG.md`; ADR 0020 amendment if the list changes.

## Devices needed

None. Device runs read the logs later, in tickets 62 and 63.
