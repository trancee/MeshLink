# Dial only peers above the RSSI floor and reuse former neighbor lists

Status: ready-for-agent
Type: AFK
Blocked by: 35b

## Behavior

Split from ticket 35b (owner decision 3). Two v1 parameters of neighbor selection that 35b left out:

- **RSSI floor for dialing:** a node dials only candidates heard at −85 dBm or stronger; a bridge may be dialed down to −88 dBm (the relaxed floor of the bridge rule).
- **Former neighbor list reuse:** a node keeps a peer's last neighbor list for 120 s after its link closes, and uses it for the overlap and bridge decisions as if still linked.

## Spec and ADR references

- [v1 parameters, Neighbor selection and topology](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#neighbor-selection-and-topology): "RSSI floor for dialing" and "Former neighbor list reuse"; [Bridge rule](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#bridge-rule): the relaxed floor.
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 1 and the 2026-11-19 amendment.

## Acceptance criteria

- [ ] A seeded `VirtualAir` test shows a candidate weaker than −85 dBm is not dialed, and a bridge between −88 and −85 dBm is.
- [ ] A seeded test shows a closed peer's list still counts for 120 s and not after.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected; both rules are local (spec README, "Purely local choices").

## Devices needed

None. Host and simulator only.
