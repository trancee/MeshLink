# Set up the release accounts, secrets and `meshlink-swift`

Status: ready-for-human
Type: HITL
Blocked by: 05

## Behavior

The owner creates everything a release needs outside this repository's code: the verified Maven Central namespace and its publishing token, the PGP signing key, the `release` GitHub environment with manual approval and its secrets, the `trancee/meshlink-swift` repository, and a GitHub App that can write only to `meshlink-swift`. An agent then reads the result back through the GitHub API and records the facts, never secret values, in the docs. Nothing is published here.

## Spec and ADR references

- [v1 ticket 36](../../meshlink-v1/issues/36-release-infrastructure.md), carried forward: `meshlink-swift`, the release environment with manual approval, its secrets and the GitHub App. Every remote change needs explicit approval.
- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md#decision) decisions 7 (the approval environment; nothing irreversible before it) and 8 (App on `meshlink-swift` only, contents write; PGP key, Central token and App key are release-environment secrets only).
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#amendments), 2026-11-13 coordinates amendment: namespace `ch.trancee`, verified through a DNS TXT record on `trancee.ch`; repository `trancee/meshlink-swift`, package `MeshLink`.
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md#decision) decision 5: creating `meshlink-swift` needs explicit approval.
- [`AGENTS.md`](../../../AGENTS.md), GIT/EXTERNAL, and [ADR 0021](../../../docs/adr/0021-change-workflow-and-merge-gates.md#decision) decision 7: settings and external changes need the owner's approval each time.
- [Ticket 04](04-apple-packaging-proof.md): the license (The Unlicense) is decided; ticket 04 adds the root `LICENSE` file and the POM license entry that Maven Central requires.
- [Device-lab lessons](../../../docs/agents/lessons.md#device-lab) and `tools/check_private.py`: no private identifiers in committed docs.

## Acceptance criteria

Owner steps. An agent prepares each one and waits for approval:

- [ ] **Central namespace:** the owner signs in to the Central Portal and confirms `ch.trancee` shows as verified. If not, the owner adds the DNS TXT record on `trancee.ch` and completes verification.
- [ ] **Central token:** the owner generates a Portal user token for publishing.
- [ ] **PGP key:** the owner creates a signing key for releases, with an expiry date, and uploads its public key to a public keyserver that Central reads. The fingerprint and expiry are public facts; the private key and passphrase never leave the secret store.
- [ ] **`trancee/meshlink-swift`:** the owner approves creating it as a public repository with the same Unlicense `LICENSE` text as this repository (ticket 04), a ruleset that blocks force-pushes and deletion, and no other content yet. Ticket 60 adds the initial content.
- [ ] **GitHub App:** the owner creates an App with repository contents read and write only, installs it on `meshlink-swift` only, and generates a private key. If ticket 60 must push workflow files to `meshlink-swift`, the App also needs the workflows permission; the owner decides and the ADR 0019 decision 8 wording is amended to match.
- [ ] **Environment `release` in `trancee/MeshLink`:** the owner as required reviewer, deployments limited to `v*` tags, and secrets for the PGP private key, its passphrase, the Central token name and password, and the App private key, plus the App ID as an environment variable. No repository-level or organization-level copy of any of these exists.

Agent steps after approval:

- [ ] Read back the environment, its protection rules, its secret and variable names, the App installation scope, and the `meshlink-swift` settings through `gh api`. Paste no secret value anywhere.
- [ ] Write `docs/reference/release-infrastructure.md`: environment name and rules, secret and variable names with purpose, App name, ID, permissions and installation scope, the PGP fingerprint, expiry and keyserver, the namespace status, and the `meshlink-swift` URL and ruleset. `python3 tools/check_private.py` passes.

## Proof commands

```sh
gh api repos/trancee/MeshLink/environments/release
gh secret list --env release --repo trancee/MeshLink
gh variable list --env release --repo trancee/MeshLink
gh api repos/trancee/meshlink-swift
gh api repos/trancee/meshlink-swift/rulesets
python3 tools/check_private.py
npx --yes markdownlint-cli2@0.24.1 "docs/**/*.md"
typos
lychee --offline .
```

The `gh` read-backs are new proof commands for the spine. They print names and settings, never secret values.

## Docs to update

- `docs/reference/release-infrastructure.md` (new).
- [`docs/README.md`](../../../docs/README.md): link it from "Look up a contract".
- [`docs/reference/dependencies.md`](../../../docs/reference/dependencies.md): none yet; ticket 60 registers the release actions.
- An ADR 0019 amendment, only if the App's permissions change what decision 8 says.

## Devices needed

None. Needs the owner's GitHub, Central Portal, DNS and keyserver accounts.
