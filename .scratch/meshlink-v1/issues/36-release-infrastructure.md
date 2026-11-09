# Set up the release infrastructure

Type: task
Status: out of scope
Blocked by: 0.1.0 release preparation (split from 30)

## Question

Create `meshlink-swift` (ADR 0011), the release environment with manual approval, its secrets (PGP key, Central token, GitHub App private key) and a GitHub App installed only on `meshlink-swift` with contents write access (ADR 0019). Every remote change needs explicit approval.

## Context

- Split from [ticket 30](30-github-repositories.md) on 2026-11-08: nothing can be released before Kotlin code exists, and secrets that sit unused are only risk.
- The main repository, its `main` ruleset and the docs workflow already exist (ticket 30).

## Comments

- 2026-11-09, ruled out of scope for the v1 spec map: it is execution work, blocked on 0.1.0 code (ADR 0019). Carried forward to the implementation effort, where 0.1.0 preparation unblocks it (map, "Carried forward"). Nothing was changed on GitHub.
