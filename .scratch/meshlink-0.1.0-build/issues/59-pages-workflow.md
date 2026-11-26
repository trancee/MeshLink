# Publish the docs site on GitHub Pages

Status: done
Type: AFK
Blocked by: 05

## Behavior

A reusable Pages workflow builds the Dokka HTML reference, places it at `/api/`, adds a small hand-written root `index.html`, and deploys the result to `https://trancee.github.io/MeshLink/`. Pull requests that touch the site build it and check its links without deploying. Only a release deploys (ticket 60 calls this workflow), because Pages holds only the latest release. A manual dispatch for a release tag reruns the deploy. Dokka's module page links to the prose docs on GitHub.

## Spec and ADR references

- [ADR 0006](../../../docs/adr/0006-documentation-architecture.md#decision) decision 8 (Dokka HTML on Pages, no prose site) and its 2026-11-13 [amendment](../../../docs/adr/0006-documentation-architecture.md#amendments): Dokka at `/MeshLink/api/`, a hand-written root `index.html` that links to the API and to `docs/README.md` on GitHub, latest release only, prose linked from the README and Dokka's module page.
- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md#decision) decision 7, step 5 (the release publishes Dokka to Pages), and its "Partial releases" risk (steps 5–7 can be rerun).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md#decision), "CI": actions pinned by commit SHA with a version comment; macOS jobs on `macos-26` with Xcode 26.4.1 selected.
- [`AGENTS.md`](../../../AGENTS.md), GIT/EXTERNAL: the latest stable release of each action, verified when the workflow is written.
- [`KOTLIN.md`](../../../KOTLIN.md), Dokka section: `dokkaGeneratePublicationHtml` builds the HTML for consumers.

## Acceptance criteria

- [x] `.github/workflows/pages.yml` has three triggers: `workflow_call` with a release-tag input, `workflow_dispatch` with the same input, and `pull_request` limited to the workflow, `pages/` and the Dokka configuration.
- [x] The build job runs on the host the required Dokka check uses (ticket 05). It builds `dokkaGeneratePublicationHtml` without warnings, copies the output to `api/` and `pages/index.html` to the root, and runs lychee offline on the assembled site, so the root page's `api/` links are checked against the real output.
- [x] The deploy job runs only from `workflow_call` or `workflow_dispatch`, never on a pull request. It uses the `github-pages` environment, has `pages: write` and `id-token: write` on that job only, and shares one concurrency group that never cancels a running deploy. The workflow's default permissions are `contents: read`.
- [x] `pages/index.html` is hand-written, static, with no script and no external asset. It links to `api/` and to `https://github.com/trancee/MeshLink/blob/main/docs/README.md`. `lychee.toml` keeps the repository-wide offline check green, for example by excluding `pages/` there, since `api/` exists only in the assembled site.
- [x] The Dokka module documentation links to `docs/README.md` on GitHub.
- [x] Every action is pinned to its latest stable release by commit SHA with a version comment, verified on the day it is written, and has a row in the "CI tools" section of the dependency register. `actionlint` passes.
- [x] **Owner approval step:** before any settings change, the agent asks the owner to approve (1) setting the Pages source to GitHub Actions and (2) allowing `v*` tags in the `github-pages` environment's deployment rule. After approval, the agent makes the change and reads it back with `gh api repos/trancee/MeshLink/pages`. Without approval, the ticket stays open at this step.
- [x] The first real deploy happens in ticket 64. This ticket proves the pull-request build only.

## Proof commands

```sh
actionlint
./gradlew dokkaGenerate
npx --yes markdownlint-cli2@0.24.1 "**/*.md"
typos
lychee --offline .
gh api repos/trancee/MeshLink/pages
```

`gh api repos/trancee/MeshLink/pages` is a new read-only proof command for the spine, run after the approved settings change.

## Docs to update

- [`docs/reference/dependencies.md`](../../../docs/reference/dependencies.md): rows for the Pages actions.
- [`docs/README.md`](../../../docs/README.md): the API reference URL.
- [`PROJECT.md`](../../../PROJECT.md): `pages.yml` and `pages/` in the layout table.

## Devices needed

None.

## Outcome (2026-11-25)

- `pages.yml` builds on the Linux Dokka host, assembles `_site/` (Dokka under `api/`, `pages/index.html` at the root) and runs lychee offline on it; the deploy job runs only from `workflow_call` or `workflow_dispatch`, and a guard refuses a run whose ref isn't `refs/tags/<tag>`, since the environment admits only `v*` tags. `actions/configure-pages` isn't used: the site needs none of its outputs.
- Actions verified 2026-11-25 as latest stable: `upload-pages-artifact` v5.0.0 (`fc324d3`), `deploy-pages` v5.0.1 (`368f825`); checkout v7.0.1, setup-gradle v6.4.1 and lychee-action v2.9.0 unchanged.
- The assembled-site check found 53 broken links in Dokka's own output. 50 pointed into `ch.trancee.meshlink.internal`, split with the undocumented `meshlink-core`; that package is now suppressed (ADR 0011: internals). The `Proximity` KDoc `[rssi]` resolved to `--root--`; it now names `[Proximity.rssi]`. Three remaining anchors on `MeshPlatform.ios()`'s receiver are a Dokka 2.3.0 defect (a `Main` fragment the companion page never emits, from the suppressed iOS child source sets); the site check excludes exactly that fragment pattern, and the page itself is still checked.
- `meshlink-testing` has no main sources yet, so Dokka renders no page for it; its `Module.md` appears once it has API.
- Red first: before the change no Dokka page linked `docs/README.md`; after it, `meshlink/index.html` does. The site check fails when `api/` is missing (negative control).
- Proofs: `actionlint`, `./gradlew dokkaGeneratePublicationHtml` (root and modules `failOnWarning`), `./gradlew check -x :meshlink-crypto-sodium:checkSodiumJniSymbols`, markdownlint-cli2 0.24.1, `typos`, `lychee --offline .`, lychee offline on the assembled site, `check_adrs`, `check_private` pass.
- Owner-approved settings (2026-11-25): Pages source GitHub Actions; `github-pages` environment allows tag `v*` only (default `main` rule removed). `gh api repos/trancee/MeshLink/pages` reads back `build_type: workflow`, `html_url: https://trancee.github.io/MeshLink/`.
