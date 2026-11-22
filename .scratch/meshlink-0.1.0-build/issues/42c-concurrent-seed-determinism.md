# Keep a seed's results identical when seeds run concurrently

Status: done
Type: AFK
Blocked by: 42

## Behavior

Found while running ticket 42's density suite in CI (owner decision: own ticket). A seeded run must give the same numbers wherever and however it runs. Run alone on the Mac mini and alone on a hosted Ubuntu runner, n200-high seed 1 measured the same busiest node, 4,699.998 B/s ([run 45047999300](https://github.com/trancee/MeshLink/actions/runs/45047999300)). Run on one of 5 parallel threads in one JVM with its cell's other seeds, it measured 4,628 B/s (run 45033048587). Concurrently running seeds therefore share some state.

- **Suspects:** a process-wide singleton or cache in `meshlink-core` or the test harness (`VirtualMesh`, `VirtualAir`, `SeededCrypto`), or iteration over identity-hashed collections whose order depends on allocation.
- **Impact:** CI is unaffected, since `density.yml` runs one seed per job. A local `densityTest` with several seeds runs them on parallel threads and may not reproduce a seed's CI numbers. A shared mutable singleton in the library would also be a defect for apps that run several meshes in one process.
- **Expected fix:** find the shared state, then remove it or scope it to one mesh. Do not serialize the seeds to hide it.

## Outcome

- Shipped in [#119](https://github.com/trancee/MeshLink/pull/119) (`a705d31`). The shared state was in the library, not the harness: the process-wide `RadioRegistry` claimed a radio by compare-and-setting its whole holder map, so a node of another mesh changing the map made a claim fail, and the node reported `Blocked(RadioInUse)` for a moment. A node now claims its own port with `getAndUpdate` and watches only its radio's holder.
- `ConcurrentReplayTest` failed 4 of 4 runs before the fix and passes after; three Gherkin scenarios in `lifecycle.feature` and `RadioRegistryTest` cover the claim and release rules.
- n200-high seed 1 measured busiest node 4,699.998 B/s and idle share 7.546e-4 both alone and among 5 parallel seeds; the `.run` files differ only in wall time.
- Mutation testing tooling is tracked separately in ticket 67.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md), decision 12 (seeded replay) and the 2026-11-22 amendment "density suites".

## Acceptance criteria

- [x] A test runs the same seed concurrently with other seeds and asserts its results equal the seed run alone; it fails before the fix.
- [x] n200-high seed 1 measures 4,699.998 B/s busiest node both alone and among 5 parallel seeds.
- [x] The cause and fix are recorded in `docs/agents/lessons.md`, "Density suites".

## Proof commands

```sh
./gradlew :meshlink-testing:densityTest -Pdensity.cells=n200-high -Pdensity.seeds=1
./gradlew :meshlink-testing:densityTest -Pdensity.cells=n200-high
```

## Docs to update

- `docs/agents/lessons.md`, "Density suites".
- ADR 0017 if the fix changes the replay contract.

## Devices needed

None.
