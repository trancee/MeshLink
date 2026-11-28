# Give apps a public fake mesh in meshlink-testing

Status: ready-for-agent
Type: AFK
Blocked by: none

## Behavior

A Kotlin or Android developer tests their own MeshLink code without radios. They create a `VirtualMesh` in a coroutine test, ask it for real `MeshLink` nodes built from their own `MeshLinkConfig`, put nodes in or out of range of each other, and advance virtual time. The nodes discover each other, exchange messages and report delivery exactly as they do on `VirtualAir` in MeshLink's own tests. The surface mirrors the Swift `FakeMesh` of `MeshLinkTesting` (`node`, `connect`, `disconnect`, `advance`), so the testing how-to shows the same steps in both languages.

Today every declaration in `meshlink-testing` sits in `ch.trancee.meshlink.internal` (`VirtualAir` and `VirtualPlatform` are `internal`), and `docs/agents/contract.md` lists "No public declarations" for the module. ADR 0007 decision 15 and ADR 0017 decision 3 promise that apps' own tests use the same fake.

## Owner decision (2026-11-28, ticket 57 Q1 A)

Add a small public facade, `ch.trancee.meshlink.testing.VirtualMesh`. `VirtualAir` stays internal. Rejected: making `VirtualAir` and `VirtualPlatform` public (it puts `Clock`, `RandomSource` and radio tuning under the SemVer promise of ADR 0019), and deferring Kotlin app testing (it breaks ADR 0006 decision 5).

## Owner decisions (2026-11-28, grilling Q1–Q5, all recommendations)

- Q1 A: nodes start out of reach of each other; `VirtualAir` gains an internal per-pair override (`setReachable`); `disconnect` drops open links at once as a transient loss.
- Q2 A: `connect(a, b, rssi: Int = -60)`.
- Q3 A: `advance(duration)` runs `advanceTimeBy(duration)` then `runCurrent()`.
- Q4 A: no public seed; a fixed internal seed.
- Q5 A: `node(config): MeshLink`, no name.
- The internal `commonTest` harness `VirtualMesh` is renamed `SeededMesh`.

## Spec and ADR references

- [ADR 0007](../../../docs/adr/0007-public-api-shape.md#decision) decision 15 and its amendments (Swift `FakeMesh`).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md#decision) decision 3 (one virtual radio).
- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md): the facade joins the ABI dump.

## Acceptance criteria

- [x] `VirtualMesh(scope: TestScope)` in `ch.trancee.meshlink.testing` with `node(config): MeshLink`, `connect(a, b)`, `disconnect(a, b)` and `advance(duration)`, each with KDoc. Names follow the Swift `FakeMesh` unless the owner decides otherwise during grilling.
- [x] Tests in `meshlink-testing`'s `commonTest` show two nodes discovering each other and delivering a message, and `disconnect` stopping delivery.
- [x] ADR 0007 and ADR 0017 amendments; the klib ABI dump and `docs/agents/contract.md` updated; Kover stays at 100%.
- [ ] Ticket 57's testing how-to (its third pull request) uses it.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew updateReference && git diff --exit-code
```

## Devices needed

None.
