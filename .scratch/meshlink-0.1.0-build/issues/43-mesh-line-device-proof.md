# Prove a forced 3-hop line on real phones

Status: ready-for-human
Type: HITL
Blocked by: 33, 42

## Behavior

Four lab phones, alternating Android and iPhone, form a line of 3 hops. Routed unicast and mesh broadcasts cross the whole line. The line is forced by an internal topology filter that only the lab app enables.

- **Topology filter (AFK part of this ticket):** an internal allowlist of link peers that the lab app sets from its launch arguments. It is not public API: the ABI dump does not change, and no published type or config field exposes it. If the agent finds no mechanism that meets this, it stops and asks the owner.
- **Profile:** `unsecured` (the lab's `--profile` name for `Unsecured`, defined by ticket 16 after ADR 0007 decision 5). Secured routed unicast needs tickets 45 and 46. Signed broadcast (ticket 39) and store-and-forward on the line are covered by the release run (ticket 62).
- **Scenarios:** routed unicast end to end in both directions; a mesh broadcast from each end; link churn (one middle link drops and returns).
- **Pass criteria:** every message is delivered within its lifetime, exactly once and intact; no crash or ANR. Latency is recorded as a baseline, not judged.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 7 and 8, and the 2026-11-13 amendment (mesh scenarios, topology filter, pass criteria, lab app).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) (the filter stays out of the public API).
- [routing.md, Unicast forwarding](../../../docs/spec/routing.md#unicast-forwarding), [Flood forwarding](../../../docs/spec/routing.md#flood-forwarding) and [Plumtree](../../../docs/spec/routing.md#plumtree).
- [Lessons, Device lab](../../../docs/agents/lessons.md#device-lab) (aliases resolved locally, pre-push privacy gate).

## Acceptance criteria

- [ ] AFK: the topology filter exists, is enabled only by `labapp/` launch arguments, and has a `VirtualAir` test showing it forces a 4-node line. The ABI dump is unchanged.
- [ ] AFK: `tools/lab/` gains the line scenarios `mesh-line-unicast`, `mesh-line-broadcast` and `mesh-line-churn`, each with a timeout.
- [ ] Owner: the four phones run all three scenarios under `--profile unsecured`. The radio trace shows the end phones never link directly.
- [ ] Every message in every run meets the pass criteria. Any miss is root-caused in the record, or the ticket stays open.
- [ ] `python3 tools/lab/lab.py evidence --run <dir>` writes a T9 evidence record under `docs/evidence/` with every field, latency per hop count as the baseline and pseudonymized addresses.
- [ ] `python3 tools/check_private.py` passes and the pre-push privacy gate is active.
- [ ] `./gradlew check` and `./gradlew iosSimulatorArm64Test` pass with the filter in place.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
python3 tools/lab/lab.py run --scenario mesh-line-unicast --devices pixel6,ip15,nothing2,ip12mini --profile unsecured
python3 tools/lab/lab.py run --scenario mesh-line-broadcast --devices pixel6,ip15,nothing2,ip12mini --profile unsecured
python3 tools/lab/lab.py run --scenario mesh-line-churn --devices pixel6,ip15,nothing2,ip12mini --profile unsecured
python3 tools/lab/lab.py evidence --run <dir>
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
python3 tools/check_private.py
```

## Docs to update

- New evidence record under `docs/evidence/` from the `evidence` subcommand.
- `labapp/` and `tools/lab/` usage notes for the new scenarios.

## Devices needed

- Android: `pixel6` and `nothing2`, through remote adb on host `adb-server`.
- iOS: `ip15` and `ip12mini`, on the Mac.
- Placed so that the filter, not distance, decides the line. Any four release phones with two per platform may stand in; the record names the ones used.
