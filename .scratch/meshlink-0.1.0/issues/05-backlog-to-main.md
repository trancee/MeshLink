# How does an implementation ticket get from the backlog to main?

Type: grilling
Status: resolved

## Question

Decide where the implementation backlog lives (local .scratch files or GitHub Issues), the ticket template (acceptance criteria, proof commands, triage labels), pull requests versus approved fast-forward merges, which CI checks the main ruleset requires, and which steps an agent may take without asking each time (commit, push, merge).

## Context

- Today: local markdown tracker (docs/agents/issue-tracker.md), triage labels (docs/agents/triage-labels.md), approval per commit and push (AGENTS.md GIT/EXTERNAL), ruleset with no required checks.

## Answer

2026-11-13, grilling; recorded as [ADR 0021](../../../docs/adr/0021-change-workflow-and-merge-gates.md).

- **Backlog:** local markdown in `.scratch/meshlink-0.1.0-build/` (`spec.md` plus `issues/NN-slug.md`), with the template in the [tracker conventions](../../../docs/agents/issue-tracker.md#implementation-ticket-template) and the states `needs-triage` → `ready-for-agent`/`ready-for-human` → `claimed` → `done`.
- **Merging:** every change, docs and tracker edits included, goes through a squash-merged pull request with the PR template; the local fast-forward flow is retired because it broke G2.
- **Ruleset:** strict required checks "Markdown lint, spelling and ADR headers", "Protocol spec" and "No private identifiers in any branch history"; linear history; PR with 0 approvals, squash only; no bypass. Applied after the ADR's PR merges, with approval. The first build ticket adds its Gradle and Xcode jobs.
- **Autonomy:** on a `ready-for-agent` ticket an agent may branch, commit, push and open the PR without asking; merges, releases, settings and external changes still need explicit approval.
