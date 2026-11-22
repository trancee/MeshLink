# Prototype mutation testing with mutflow and decide whether to adopt it

Status: needs-triage
Type: HITL

## Behavior

Owner request (ticket 42c, 2026-11-22). Line coverage at 100% does not show that assertions notice a changed behavior; mutation testing does. MeshLink has no mutation tool, so mutants are checked by hand per change. Evaluate [mutflow](https://github.com/anschnapp/mutflow) and the owner's [mutation-testing-agent-toolkit](https://github.com/trancee/mutation-testing-agent-toolkit), which drives mutflow from Copilot CLI and OMP, then decide in an ADR.

Verified facts (2026-11-22):

- mutflow 1.7.1, Apache-2.0, needs Kotlin 2.4.x; MeshLink uses Kotlin 2.4.21.
- Mutants exist only in a second test compilation; production klibs and jars stay clean.
- KMP support covers a `jvm()` target and the native targets `linuxX64` and `mingwX64`. MeshLink declares an Android library target with host tests, `iosArm64` and `iosSimulatorArm64`, and no `jvm()` target. The toolkit's README warns that projects with iOS or Android targets "can fail dependency resolution" and that it has no Android adapter.
- Tests must wrap the action under test in `MutFlow.underTest {}`, or use JUnit 4 `wrapTestMethods = true`, which mutates the setup too. Robolectric needs a custom runner built from `MutFlowRun`. The cucumber-kmp tests are generated.
- Mutants are switched on for the whole JVM, so mutflow test classes run one at a time per JVM.

Prototype questions:

- Can mutflow instrument `meshlink-core` and `meshlink` through `androidHostTest`, or does a test-only `jvm()` target, or a JVM-only test module over `commonMain`, work better? What does each cost in build time and source-set complexity?
- Which suites can wrap their action: plain kotlin-test, cucumber step glue, Robolectric?
- Survivors and run time on one real class, for example `RadioRegistry` and `MeshLink.watch()`.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) (testing architecture).
- `CONSTITUTION.md` dependency rules: every dependency is approved and documented.

## Acceptance criteria

- [ ] A throwaway prototype branch answers the prototype questions with measured numbers.
- [ ] An ADR records adopt or reject, with the options and their costs; the owner approves it.
- [ ] If adopted: mutflow is pinned, documented in the dependency list, and run by a documented Gradle task and CI job; `PROJECT.md` lists the command.

## Proof commands

To be set by the prototype, for example `./gradlew mutflowJvmTest`.

## Docs to update

- New ADR; ADR 0017; `PROJECT.md`; `docs/agents/lessons.md` if the prototype finds pitfalls.

## Devices needed

None.
