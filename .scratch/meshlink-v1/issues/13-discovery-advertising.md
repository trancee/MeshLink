# How do nodes discover each other, and what goes in the advertisement?

Type: prototype
Status: open
Blocked by: 02, 08, 12

## Question

Prototype advertising/scanning: identifier format and rotation (tracking resistance), mesh membership filter, iOS background overflow-area behavior, Android↔iOS visibility, legacy vs extended advertising, duplicate-link resolution between two nodes. Decide the discovery design.

## Scope additions (from research, 2026-10-31)

- Can Android see an iOS app advertising in the background (overflow area)? Does an iOS 26 Live Activity help advertising or only scanning? Is 'both iPhones backgrounded' an accepted degraded mode?
- iPhone X / SE 2016 see no extended adverts; Coded PHY reception on iOS unknown.
- Can tiny presence/summary messages ride in advertising packets across platforms?
- Google Play: does a phone-to-phone mesh qualify for the connectedDevice foreground-service type?
- From the [threat model](08-threat-model.md): in the secured profiles, every on-air identifier (advert payload, readable GATT values, on-air routing tags) rotates in step with the BLE address and can't be linked across rotations. A gated mesh's on-air identifier is derived from the mesh key and rotates. The service UUID, and possibly an open mesh's identifier, may remain visible.
- From the [routing decision](14-routing-topology-simulation.md) (ADR 0008): advertisement identifiers and routing tags rotate every 15 min in step with the OS-controlled BLE address. Decide how MeshLink detects or matches that rotation on Android and iOS, and verify the 15 min figure on both. Per class, scan every 2 / 4 / 6 s with fast / fast / slow advertising. The density estimator counts distinct advertisement identifiers in a 10 s window, so identifiers must be distinguishable per node within a window. An iPhone at its incoming-link cap must stay discoverable, or the cap must be lower (ADR 0009 device sweep).
