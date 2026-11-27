# Update the dependencies the first freshness report found

Status: done
Type: AFK
Blocked by: none

## Behavior

Ticket 56 added `tools/check_freshness.py`. Its first run, on `main` at `052dd5e` (2026-11-27), found eight dependencies with a newer stable release:

| Dependency | Current | Newest stable | Source |
|---|---|---|---|
| `spotless` | 8.11.0 | 8.12.0 | Maven Central `com.diffplug.spotless:spotless-plugin-gradle` |
| `robolectric` | 4.18 | 4.18.1 | Maven Central `org.robolectric:robolectric` |
| `compose-bom` | 2026.09.00 | 2026.11.01 | Google Maven `androidx.compose:compose-bom` |
| `floor-jackson` | 2.22.4 | 2.23.0 | Maven Central `com.fasterxml.jackson.core:jackson-core` |
| `Gradle` | 9.7.1 | 9.9.3 | services.gradle.org |
| `DavidAnson/markdownlint-cli2-action` | 25.0.0 | 25.1.0 | GitHub releases |
| `crate-ci/typos` | 1.52.0 | 1.52.2 | GitHub releases |
| `markdownlint-cli2` | 0.24.0 | 0.24.2 | npm |

Update them in one pull request, as ADR 0005 requires. Versions that ADR 0005 holds or pins as floors stay where they are, as the acceptance list explains.

## Acceptance

- [x] Read each release's notes before you update it, and record any breaking change in the PR. None of Spotless 8.12.0, Robolectric 4.18.1 (same android-all pins, preinstrumented version 7), typos 1.52.1/1.52.2, or markdownlint-cli2 0.24.1/0.24.2 breaks the build. Compose BOM 2026.11.01 (`ui` 1.13.0, `material3` 1.5.0) needs compileSdk 37.1 or newer, since its AAR metadata has `minCompileSdk=37`, `minCompileMinorSdk=1`.
- [x] Update `gradle/libs.versions.toml`: spotless 8.12.0, robolectric 4.18.1, compose-bom 2026.11.01. The owner chose option A, so every module compiles against `android-compileSdk = "37.2"`. That goes through the AGP `compileSdk { version = release(37) { minorApiLevel = 2 } }` DSL, and CI installs the platform with `.github/actions/android-platform` where a runner lacks it.
- [x] Gradle stays 9.7.1 and AGP stays 9.3.3. ADR 0005 holds both, because the KGP 2.4.21 compatibility table lists Gradle only up to 9.7.0 and AGP only up to 9.3.1. The freshness report now lists them in a separate "Held" table, which doesn't fail the run.
- [x] `floor-jackson` is a security floor. ADR 0005 raises a floor only for a security alert, and no GitHub advisory affects 2.22.4. Keep it, and change `tools/check_freshness.py` to skip every `floor-*` version and say why.
- [x] For each GitHub Action, pin the release commit SHA and put the version in the comment beside it. Check the SHA against the release tag: markdownlint-cli2-action v25.1.0 `3ce8f71`, typos v1.52.2 `276087d`, both dereferenced from the annotated tags.
- [x] Update `typos` and `markdownlint-cli2` wherever CI and `PROJECT.md` name them.
- [x] Update the matching rows of `docs/reference/dependencies.md`, so `checkDependencyRegister` passes.
- [x] `python3 tools/check_freshness.py` exits 0, apart from releases published after this ticket was filed. Fixed along the way: AGP is now read from Google Maven as well as Maven Central, whose copy stops at 2.3.0; minor SDK platforms such as 37.2 now count; held versions get their own table.
- [x] Full gates, docs checks, and the iOS and Robolectric suites pass. Kover stays at 100%.

## References

- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md), [dependency register](../../../docs/reference/dependencies.md), ticket 56.
