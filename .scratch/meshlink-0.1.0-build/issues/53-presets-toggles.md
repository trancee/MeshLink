# Configure nodes with presets and toggles

Status: ready-for-agent
Type: AFK
Blocked by: 32, 39, 46, 48, 50, 52

## Behavior

An app builds its config with `meshLinkConfig(preset, securityProfile) { … }` from one of the five built-in presets or a `Preset.Custom(name, base, overrides)`. The DSL block overrides preset values, `build()` validates the merged result, and `describe()` prints every effective value with its source: `preset`, `override` or `default`. Each v1 toggle governs only local origination, holding or local radio use. A node with a toggle off still parses and relays that feature's frames. Settings that earlier tickets added move under this one config surface; no second path remains (O2).

## Spec and ADR references

- [ADR 0007](../../../docs/adr/0007-public-api-shape.md#decision) decisions 4 (immutable regular class, one DSL, declared exception from `build()`, `describe()`), 6 (sealed `Preset`, five built-ins plus `Custom`) and 11 (no nullable primitives; sealed alternatives such as `MessageLifetime.Default` / `MessageLifetime.Of(seconds)`).
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#decision) decisions 8 (toggle rule), 9 (no relay-off mode), 10 (toggles), 11 (preset mechanism and value sources), 12 (built-in presets) and 13 (no background values in presets), with its [amendments](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments): read receipts, lifetimes, size caps and the `power` knob.
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md#decision) decision 8: automatic density by default; an app may pin a class; only `LocalMultiplayer` pins low.
- [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md#decision) decision 10: `readReceipts.requestByDefault` and `readReceipts.send`.
- [ADR 0014](../../../docs/adr/0014-store-and-forward.md#decision) decision 2: lifetime between 120 s and 1 h.
- [ADR 0015](../../../docs/adr/0015-message-size-and-compression.md#decision) decisions 8 and 9: `limits.maxUnicastPayload` (4–128 KiB) and `limits.maxBroadcastPayload` (at most 1 KiB) only lower the caps; `config.limits` and `describe()` show them.
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md#amendments) and [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments) amendments (2026-11-13, deferred for 0.1.0): extended advertising (ADR 0010 decision 2) and the iOS Live Activity hook (ADR 0010 decision 6) are deferred beyond 0.1.0, so their toggles are not in this ticket.
- [ADR 0006](../../../docs/adr/0006-documentation-architecture.md#decision) decision 3: options, defaults and bounds live in Kotlin with KDoc, and a JVM test generates the reference.

## Acceptance criteria

- [ ] `Preset` is sealed with `OfflineChat`, `EmergencyBroadcast`, `ProximityDiscovery`, `LocalMultiplayer`, `FieldTeams` and `Custom(name, base, overrides)`. Each built-in carries exactly the values of ADR 0011 decision 12 as amended: density, sending, holding, read receipts, default lifetime, unicast and broadcast caps, and `power`.
- [ ] One test per built-in preset asserts every value it sets. One test proves `Custom` inherits from `base` and applies its overrides.
- [ ] The toggles of ADR 0011 decision 10 as amended exist, each with its default: routed unicast, broadcast origination, `readReceipts.requestByDefault`, `readReceipts.send`, holding for others, L2CAP CoC upgrade, density (automatic or a pinned class), default message lifetime, `limits.*` and `power`.
- [ ] `build()` throws a declared `MeshLinkException` subtype (with `@Throws`) for a lifetime under 120 s or over 1 h, and for limits outside their bounds. Each bound has a test on both sides of the edge (T10).
- [ ] `describe()` lists every effective value with source `preset`, `override` or `default`, including the effective `config.limits`. A test covers each source.
- [ ] Toggle rule, on `VirtualAir` in `:meshlink-testing`'s `commonTest`: a node with routed unicast, broadcast origination, holding or `readReceipts.send` off still relays other nodes' frames of that feature. One test per toggle.
- [ ] Routed unicast off makes `send` direct-only. Broadcast origination off rejects local `broadcast` with a typed `SendResult.Rejected` reason. Tests cover both.
- [ ] No extended-advertising or Live Activity toggle exists in the config, the presets or `describe()`; no test or doc claims background behavior.
- [ ] If a preset value such as `EmergencyBroadcast`'s "broadcast first" needs behavior beyond what the ADRs define, stop and ask. Do not invent it; an ADR 0011 amendment comes first.
- [ ] A JVM test generates `docs/reference/configuration.md` (options, defaults, bounds) and `docs/reference/presets.md` (the preset table) from the Kotlin source. It fails when the committed files differ. `./gradlew updateReference` rewrites them.
- [ ] The public API is in the ABI dump with KDoc on every declaration. Kover stays at 100%. `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
./gradlew updateReference && git diff --exit-code docs/reference
npx --yes markdownlint-cli2@0.24.1 "docs/**/*.md"
typos
lychee --offline .
```

`./gradlew updateReference` is new: this ticket creates it and adds it to the spine's proof-command table.

## Docs to update

- `docs/reference/configuration.md` and `docs/reference/presets.md` (generated; new). Together they replace the draft preset table, as ADR 0011's consequences require.
- [`docs/README.md`](../../../docs/README.md): add both pages to "Look up a contract".
- [`PROJECT.md`](../../../PROJECT.md): add `./gradlew updateReference` to the commands.

## Devices needed

None. Toggle behavior is proved on `VirtualAir`.
