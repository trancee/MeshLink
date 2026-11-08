# Create the GitHub repositories and first workflows

Type: task
Status: resolved

## Question

Create the main repository and `meshlink-swift` (ADR 0011), push `main`, and set up the first workflows (ADR 0005). Needs explicit approval before any remote is created.

## Context

- ADR 0019: also create the release environment (manual approval), its secrets (PGP key, Central token, GitHub App private key) and a GitHub App installed only on `meshlink-swift` with contents write access.

## Answer

`trancee/MeshLink` is public, and `main` was pushed for the first time on 2026-11-08, together with the `prototype/*` branches that the evidence cites. `meshlink-swift`, the release environment, the GitHub App and the secrets moved to [ticket 36](36-release-infrastructure.md), blocked until 0.1.0 preparation.

- **History scrubbed before the first push:** device serials, iOS UDIDs, Apple team IDs, LAN addresses, home paths and an email address were replaced by lab aliases in every commit, with cited commit hashes remapped. `tools/check_private.py` now fails on them, locally and in CI; the rule is in [`docs/agents/lessons.md`](../../../docs/agents/lessons.md).
- **Workflow:** `docs.yml` gains a `spec` job (vectors regenerate without a diff, `check_spec.py`, `check_private.py`, the runner's `python3`); typos is bumped to v1.52.0. Actions stay SHA-pinned.
- **Settings:** private vulnerability reporting on (as `SECURITY.md` promises); a ruleset on `main` blocks force-push and deletion only; wiki and Projects off; rebase merges only; branches deleted on merge; Dependabot security alerts on.
