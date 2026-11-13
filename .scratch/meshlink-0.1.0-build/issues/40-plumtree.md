# Spread broadcasts with Plumtree

Status: ready-for-agent
Type: AFK
Blocked by: 39

## Behavior

In the medium and high density classes, mesh broadcasts spread with Plumtree instead of plain flooding. The low class keeps flooding. Delivery stays once per node; the airtime drops.

- **Eager and lazy links:** all links start eager. A node pushes a new broadcast on its eager links and announces its ID in `IHAVE` on its lazy links.
- **Prune:** a duplicate broadcast on an eager link answers with `PRUNE`; the link becomes lazy in both directions.
- **Graft:** a node that learns an ID from `IHAVE` and lacks the frame after 500 ms sends `GRAFT` to the first announcer, then to each further announcer after another 250 ms. A `GRAFT` makes the link eager, and the receiver sends the frame. A `GRAFT` for an ID the node no longer holds gets nothing.
- **Frames:** `IHAVE` (`0x0B`, 1–32 IDs), `GRAFT` (`0x0C`, 13 B) and `PRUNE` (`0x0D`, 1 B) are link-local and never forwarded.
- **Profiles:** all three. Plumtree carries the `Unsecured` and the signed broadcasts of ticket 39 alike; a secured node verifies a broadcast (ticket 39) before pushing it on eager links or announcing it in `IHAVE`, and a `GRAFT` is answered only with a verified frame.

`GRAFT` for catch-up offers is ticket 41.

## Spec and ADR references

- [routing.md, Plumtree](../../../docs/spec/routing.md#plumtree) and [Flood forwarding](../../../docs/spec/routing.md#flood-forwarding) (step 3).
- [D-26](../../../docs/spec/decisions.md#d-26-plumtree-frames).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 3 (Mesh broadcast row).
- [v1 parameters, Mesh broadcast](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#mesh-broadcast).

## Acceptance criteria

- [ ] `IHAVE`, `GRAFT` and `PRUNE` codecs pass every matching `link-frames.json` vector, including `IHAVE` count 0 and 33 as `invalid`. Each new decoder has a deterministic mutation test.
- [ ] Seeded `VirtualAir` test, 12 nodes, `Unsecured`, class pinned to medium: 20 broadcasts reach every connected node exactly once, intact. After the first, the test sees `PRUNE` frames and fewer full-frame transmissions than the same seed with the class pinned to low.
- [ ] A seeded test drops an eager link mid-run and shows the graft after 500 ms, the next announcer after a further 250 ms, and the link turning eager.
- [ ] A seeded test checks that the low class still floods and sends no Plumtree frames.
- [ ] The 12-node medium-class test also runs in `Classical` and `PostQuantum`, with the same once-per-node delivery and every delivered broadcast verified.
- [ ] A seeded mixed-class test (some nodes low, some medium) delivers every broadcast once to every node.
- [ ] T10: a `GRAFT` for an unknown ID, an `IHAVE` flood of unknown IDs and truncated frames cause no unbounded state ([guarantees](../../../docs/reference/security-profile-guarantees.md), "Bounded resources under any remote input"). The PR names the bound on pending grafts and its source, and a test hits it.
- [ ] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None. The behavior is internal and the spec already describes it.

## Devices needed

None. Host and simulator only.
