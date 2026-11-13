# Create, start, stop and close a node through the public API

Status: ready-for-agent
Type: AFK
Blocked by: 07, 08

## Behavior

An app builds a `MeshLinkConfig` with `meshLinkConfig(preset, securityProfile) { meshId = … }`, creates a node with `MeshLink.create(config, platform)`, and drives it with `start()`, `stop()` and `close()` while observing `state`. In this ticket a node can run only in `SecurityProfile.Unsecured`. On start the node creates its installation identity (real Ed25519 through ticket 07) in an in-memory identity store and derives its NodeId. A node whose radio is missing a requirement reports `Blocked(requirements)` and leaves it by itself.

## Spec and ADR references

- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decisions 1 (entry point, typed rejection of a second running instance), 2 (lifecycle and `MeshState`), 3 (`Blocked` requirements), 4 (config DSL, `build()` validation, `describe()`), 5 (no default profile), 6 (presets), 11 (errors), 13 (threading).
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) "coordinates and names" amendment (public types in `ch.trancee.meshlink`).
- [ADR 0003](../../../docs/adr/0003-installation-identity-key-model.md) decision (one Ed25519 identity per installation) and the wire-format amendment (NodeId).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 3 (several real nodes in one process over `VirtualAir`).
- Spec: [NodeId](../../../docs/spec/identity.md#nodeid), [mesh identifier](../../../docs/spec/discovery.md#mesh-identifier).
- Constitution Q4, Q8 (no placeholder), C2.

## Acceptance criteria

- [ ] `SecurityProfile` is a sealed type that holds only `Unsecured` in this ticket. Tickets 24 and 25 add `Classical` and `PostQuantum` with their behavior. No public stub throws "not supported" (Q8). There is no default profile: `meshLinkConfig` requires it.
- [ ] `Preset` is sealed with the five built-in names. Its contents are only those that M1 uses (the message lifetime defaults of ticket 12); ticket 53 adds the rest and `Preset.Custom`.
- [ ] `meshLinkConfig(...) { }.build()` validates the mesh ID (spec "Mesh identifier" length rules) and throws a declared, `@Throws`-annotated subtype of `MeshLinkException` on an invalid one. `describe()` lists every effective setting. `MeshLinkConfig` is a regular class.
- [ ] `MeshLink.create` does not suspend. `start()` and `stop()` suspend and are idempotent; `close()` is final, and any later call throws a declared `MeshLinkException`. `state` moves `Stopped → Starting → Running → Stopping → Stopped`, tested in virtual time.
- [ ] Single instance: ADR 0007 decision 1 narrows to "one running instance per platform radio" (owner decision). A second running instance on the same platform radio gets a typed rejection; any number of instances may run on `VirtualAir` in one process, so the M1 two-node test works. A dated amendment to ADR 0007 decision 1 (version bump) records the narrowed rule in this PR; `python3 tools/check_adrs.py` passes.
- [ ] `Blocked(requirements)`: a `VirtualAir` radio can report `BluetoothOff`, `PermissionDenied(kind)`, `Unsupported` or `RadioInUse`. A test shows `Blocked` on start and the automatic move to `Running` when the requirement clears.
- [ ] On first start the node generates an Ed25519 key pair through `CryptoProvider` and keeps it in an internal in-memory identity store port; a restart of the same instance keeps the same NodeId. Ticket 15 adds platform storage behind the same port.
- [ ] The public API surface matches the committed ABI dump; KDoc on every public symbol; Dokka zero warnings.
- [ ] The SwiftPM consumer test creates and starts a node with an `Unsecured` config on the simulator and sees `Blocked(Unsupported)` or `Running`, whichever the simulator radio reports; the test asserts which.
- [ ] Tests run on the Android host and `iosSimulatorArm64`; Kover 100%.

## Proof commands

```sh
./gradlew :meshlink:testAndroidHostTest --tests '*Lifecycle*'
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
tools/swiftpm-consumer/test.sh
python3 tools/check_adrs.py
```

## Docs to update

- `docs/adr/0007-public-api-shape.md`: dated amendment narrowing decision 1 to one running instance per platform radio, with any number on `VirtualAir` (version bump).
- `CHANGELOG.md`: "Unreleased" entry for the lifecycle API.
- `GLOSSARY.md`: `MeshState` and "installation identity", if absent.

## Devices needed

None.
