# Emit platform diagnostics and write them to the platform log

Status: ready-for-agent
Type: AFK
Blocked by: 54b

## Behavior

Split from ticket [54](54-diagnostics-catalog.md). The Android and iOS adapters report platform status numbers, and MeshLink emits the codes that need them:

- ML2002 `ScanFailed(platformCode)`, ML2004 `BackgroundDiscoveryDegraded`, ML2005 `AdvertisingFailed(platformCode)`;
- ML3006 `GattOperationRetried(platformCode)`, and `platformCode` on ML3002 `LinkClosed` and ML3003 `ConnectFailed`;
- ML8001 `ScannerRegistrationFailed`, ML8002 `AppSuspendedByOs`, ML8003 `DozeActive`.

`diagnostics.platformLog`, off by default, writes events to Logcat on Android and to `os_log` on iOS.

## Spec and ADR references

- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md#decision) decisions 3 (platform status numbers allowed), 6 (`platformLog`) and 7.
- [Device-lab lessons](../../../docs/agents/lessons.md#device-lab): GATT 133/147, "scan failed 2", and the Kotlin/Native `NSLog` varargs crash.

## Acceptance criteria

- [ ] Each listed code is emitted from its adapter, with a test through the Robolectric shadows or the `iosTest` fakes.
- [ ] The radio contract carries the raw platform status number beside `RadioFailure`; every adapter and fake is migrated.
- [ ] `diagnostics.platformLog` writes to Logcat and `os_log`; a test covers on and off. In `os_log`, every field other than codes and numbers is private. The iOS call is a thin adapter listed in the CI-named adapter file; it formats in Kotlin and never passes Kotlin strings as C varargs.
- [ ] The redaction test covers every new event type (S1).
- [ ] `docs/reference/configuration.md` is regenerated with `diagnostics.platformLog`; any change to the code list amends ADR 0020 in this PR.
- [ ] Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

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
