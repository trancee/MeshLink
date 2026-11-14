# Run a sans-IO core on `VirtualAir` in virtual time

Status: done
Type: AFK
Blocked by: 05

## Behavior

The protocol core reads no clock, draws no randomness and touches no radio of its own: a `Clock`, a seeded `Random` and a `RadioPort` are injected. `VirtualAir` in `:meshlink-testing` implements `RadioPort` for any number of nodes in one process, in kotlinx-coroutines-test virtual time, seeded and deterministic. This ticket builds only the radio operations the M1 tracer needs; no protocol frames yet.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 1 (sans-IO core), decision 2 (multi-node tests in `commonTest` under virtual time, T8), decision 3 (`VirtualAir` in `:meshlink-testing`, multi-node tests in its `commonTest`, Kover merges `:meshlink` and `:meshlink-testing`) and decision 13.
- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decision (GATT bearer; central and peripheral roles).
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decision 2 (module allow-list) and the "coordinates and names" amendment (`ch.trancee.meshlink.internal` for non-API types).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Version tuple" (kotlinx-coroutines 1.11.0, including `kotlinx-coroutines-test`).
- Spec: [roles and bearers](../../../docs/spec/link.md#roles-and-bearers), [GATT service](../../../docs/spec/discovery.md#gatt-service).
- Constitution T2, T8.

## Acceptance criteria

- [x] `:meshlink-core` defines `Clock` (monotonic and wall time), a seeded random source and `RadioPort` in `ch.trancee.meshlink.internal`. Nothing in `commonMain` of any module calls a system clock, `kotlin.random.Random.Default` or a platform radio API; a detekt rule or a test enforces this.
- [x] `RadioPort` covers exactly what M1 and M2 need: start and stop advertising a 16 B payload with the mesh service UUID; start and stop scanning, delivering sightings (payload, an opaque peer handle, RSSI); connect as central; serve and read the capability characteristic; an ordered byte stream per connection in both directions, chunked by the negotiated ATT MTU; disconnect events. Each operation reports failure as a value, not an exception.
- [x] `VirtualAir` lives in `:meshlink-testing` and needs no dependency beyond the allow-list. It models per-link MTU, latency, ordered delivery and disconnects, all seeded. Loss, link caps and advertising and scan windows are left for the tickets that need them; the PR names those tickets.
- [x] A `TestClock` advances with the `TestScope` scheduler, so a test that waits 10 virtual minutes finishes in milliseconds of real time.
- [x] Tests in `:meshlink-testing` `commonTest`: two radios see each other's advertisement; a connected pair exchanges a 2 KB stream in order across an MTU of 23 and of 247; a disconnect reaches both sides; running a scenario twice with the same seed yields identical event logs, and a different seed changes the latency order.
- [x] Every test has a timeout; none uses real time or sleeps.
- [x] The new tests pass on the Android host and on `iosSimulatorArm64`; Kover stays at 100% with `:meshlink-testing` merged.
- [x] `./gradlew check` and `./gradlew iosSimulatorArm64Test` pass.

## Proof commands

```sh
./gradlew :meshlink-testing:testAndroidHostTest --tests '*VirtualAir*'
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- `GLOSSARY.md`: `VirtualAir`, `RadioPort` and "sans-IO core", if absent.
- `docs/reference/dependencies.md`: the `kotlinx-coroutines-test` row, if absent.
- `PROJECT.md`: proof-matrix row for multi-node `VirtualAir` tests.

## Devices needed

None.
