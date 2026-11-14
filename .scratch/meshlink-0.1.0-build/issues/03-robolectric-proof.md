# Prove Robolectric 4.18 on SDK 26 and 37

Status: done
Type: AFK
Blocked by: 01

## Behavior

Android host tests of `:meshlink` run under Robolectric on SDK 26 and SDK 37 from pinned android-all jars, with no network access during the test run. The Android BLE adapter (ticket 13) then has a working test harness. There is no product behavior yet.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 5 (Robolectric tests only the Android BLE adapter, on SDK 26 and 37; android-all jars pinned and loaded offline; own shadows where Robolectric's fall short) and decision 13 (no retries, per-test timeout; one SDK 37 run is known to have hung).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Version tuple" (Robolectric 4.18, JUnit 4.13.2, androidx.test core 1.7.0; 4.17 if 4.18 fails), "Tools" (the two JVM flags plus a per-test timeout) and the 2026-11-13 amendment (check whether the two flags are still needed with 4.18).
- Constitution T2 (deterministic, no network or workstation dependency) and E2.

## Acceptance criteria

- [x] `:meshlink` has a Robolectric test in its Android host-test source set that gets the `BluetoothManager` from `ApplicationProvider` and asserts it returns a `BluetoothAdapter`. It runs once on SDK 26 and once on SDK 37 (`@Config(sdk = [26, 37])` or an equivalent).
- [x] The android-all jars for SDK 26 and 37 are resolved as pinned Gradle dependencies and passed to Robolectric in offline mode. A test run with network access blocked still passes; the PR states how this was checked.
- [x] Robolectric 4.18 is tried first. If it fails on either SDK, the catalog returns to 4.17, and the PR and the dependency register record the failure (command, error) as the reason.
- [x] The two JVM flags from ADR 0005 (`--add-exports=java.base/jdk.internal.access=ALL-UNNAMED`, `--add-opens=java.base/java.io=ALL-UNNAMED`) are each kept only if removing it breaks a run. The PR records which were needed.
- [x] The Robolectric `Test` task has a per-test timeout and no retry.
- [x] Ten consecutive runs of the test on both SDKs pass without a hang, shown in the PR.
- [x] `./gradlew check` stays green, Kover at 100%.

## Proof commands

```sh
./gradlew :meshlink:testAndroidHostTest --tests '*Robolectric*'
./gradlew check
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
```

## Docs to update

- `docs/reference/dependencies.md`: the Robolectric, JUnit 4 and androidx.test core rows (version actually pinned, the android-all jar pins, the flag result).
- `docs/adr/0005-toolchain-and-dependency-baseline.md`: only if the pin falls back to 4.17 or the flags change; record it as a dated amendment with the evidence, and bump the ADR version.
- `PROJECT.md`: proof-matrix row for Android host tests under Robolectric (SDK 26 and 37, offline jars).
- `docs/agents/lessons.md`: only if a new root cause is found (for example a hang).

## Devices needed

None.
