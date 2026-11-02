# PROJECT

Verified project profile (required by [`AGENTS.md`](AGENTS.md) and [`KOTLIN.md`](KOTLIN.md)). It records only current, checked state; each decided fact lives in its ADR and is linked, not copied.

## State (2026-11-02)

- **Phase:** design. `main` holds no product code yet, only decisions, research and docs.
- **Decided:** [ADRs 0001–0012](docs/README.md) (threat model, Noise suites, identity keys, crypto provider, toolchain, docs architecture, public API, routing, bearers, discovery, modules, wire format).
- **Open:** the [wayfinder map](.scratch/meshlink-v1/map.md) and its tickets.
- **Prototypes:** throwaway local branches, never merged:
  - `prototype/bearer-bakeoff`: GATT vs L2CAP CoC and PHY comparison on devices.
  - `prototype/routing-sim`: the routing and topology simulator.
  - `prototype/public-api`: the Kotlin and Swift API shape.
  - `prototype/codec`: Kompact vs hand-written codec.
  - `prototype/discovery`: advertising and rotation on devices.

## Stack and layout

- **Toolchain, targets, coverage and tools:** decided in [ADR 0005](docs/adr/0005-toolchain-and-dependency-baseline.md) but not built yet; no Gradle build exists on `main`.
- **Dependencies:** every dependency and CI tool is in the [dependency register](docs/reference/dependencies.md).
- **Layout today:**

  | Path | Holds |
  |---|---|
  | `docs/` | Diátaxis docs, ADRs and research ([index](docs/README.md)) |
  | `.scratch/` | Local issue tracker ([conventions](docs/agents/issue-tracker.md)) |
  | `.github/workflows/docs.yml` | Docs checks |
  | `GLOSSARY.md` | Domain terms |

## Commands

Verified locally on 2026-11-01 (macOS, Homebrew lychee 0.24.2 and typos-cli 1.51.1, Node 24). CI runs the same tools at the same versions from [`docs.yml`](.github/workflows/docs.yml).

| Check | Command |
|---|---|
| Markdown lint | `npx -y markdownlint-cli2@0.24.0` |
| Spelling (US English) | `typos` |
| Links, repository only | `lychee --offline .` |
| Links, including external | `lychee .` (set `GITHUB_TOKEN` to avoid GitHub rate limits) |
| Workflow lint | `actionlint` |

## Proof matrix

The `{module,target,host,prerequisites,task,proof,limitations}` matrix that `KOTLIN.md` requires starts with the first Gradle module. There are none yet.

Device hosts, recorded for that matrix ([device lab](.scratch/meshlink-v1/issues/06-device-lab-readiness.md)):

- iOS 17 and later: CoreDevice (`xcrun devicectl`).
- iOS 15 and 16: libimobiledevice (`ideviceinstaller`, `idevicedebug`), because `devicectl` lists them by ECID only.
- Android: adb, local or remote.

## Limitations

- The docs checks are the only gates that exist; build, test, coverage and API gates arrive with the first module.
- Android and iOS radio behavior is proved on hardware only (Constitution T9). The routing simulator results (ADR 0008) are not device evidence.
