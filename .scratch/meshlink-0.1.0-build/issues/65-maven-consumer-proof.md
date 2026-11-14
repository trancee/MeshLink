# Prove Maven-local consumer resolution of every artifact

Status: ready-for-agent
Type: AFK
Blocked by: 04, 07, 08

## Behavior

`./gradlew publishToMavenLocal` publishes every Gradle module, and a generated Kotlin Multiplatform consumer that depends only on `ch.trancee.meshlink:meshlink` resolves every transitive project module from that Maven repository alone. `:meshlink-core` and `:meshlink-crypto-sodium` publish as dependency-only internal artifacts. No product behavior changes.

Split from ticket 04 (owner decision, 2026-11-14): KGP writes no iOS klib for a module without Kotlin source, so `generateMetadataFileFor{IosArm64,IosSimulatorArm64}Publication` fails with `FileNotFoundException` on an empty module. Ticket 07 gives `:meshlink-core` and `:meshlink-crypto-sodium` source and ticket 08 gives `:meshlink-testing` source; until then only `:meshlink:publishToMavenLocal` passes.

## Spec and ADR references

- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decision 1 (published artifacts; this ticket amends it), decision 2 (`:meshlink-core` and `:meshlink-crypto-sodium` "are not published on their own") and the 2026-11-13 amendment "coordinates and names".
- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md) decision 1 (one version for every artifact).
- Owner decision (2026-11-13): the internal modules publish as dependency-only artifacts.

## Acceptance criteria

- [ ] An internal-library convention plugin in `build-logic/` applies `meshlink.kmp-library` and the shared Maven publishing of ticket 04 with an empty javadoc JAR, and no ABI validation or Dokka. `:meshlink-core` and `:meshlink-crypto-sodium` apply it and set a `description`.
- [ ] Their POM descriptions and the docs state they are internal: no API guarantee, outside the ABI and SemVer promise of ADR 0019. Consumers depend on `ch.trancee.meshlink:meshlink`.
- [ ] A dated amendment to ADR 0011 decision 1 (version bump) records the dependency-only internal artifacts and their exclusion from the ABI and SemVer promise, and reconciles decision 2; `python3 tools/check_adrs.py` passes.
- [ ] `./gradlew publishToMavenLocal` passes for all four modules. Every POM has name, description, URL, developer, SCM and license entries (license name `The Unlicense`, URL `https://unlicense.org`), and all share one version.
- [ ] `tools/maven-consumer/test.sh` publishes into a temporary Maven repository (never the user's `~/.m2`), checks every POM, generates a KMP consumer whose only MeshLink dependency is `ch.trancee.meshlink:meshlink` (Kotlin and AGP versions read from the catalog; `exclusiveContent` for group `ch.trancee.meshlink`), and fails unless the Android runtime classpath and both iOS klib compile classpaths resolve and contain `meshlink-core` and `meshlink-crypto-sodium`. The ticket verifies the configuration names.
- [ ] `./gradlew check` stays green.

## Proof commands

```sh
./gradlew publishToMavenLocal
tools/maven-consumer/test.sh
./gradlew check
python3 tools/check_adrs.py
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
```

## Docs to update

- `docs/adr/0011-modules-toggles-and-presets.md`: dated amendment to decision 1 (version bump).
- `PROJECT.md`: module table and layout (`tools/maven-consumer/`), commands, a proof-matrix row for the generated-consumer proof, and the removal of the "Maven consumer resolution" limitation.

## Devices needed

None.
