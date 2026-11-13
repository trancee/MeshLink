# 0021: Change workflow and merge gates

Status: accepted (2026-11-13; 0.1.0 map ticket "How does an implementation ticket get from the backlog to main?"). Version: 1.

## Context

- **Constitution:**
  - G1: every change starts on a feature branch.
  - G2: a clean-checkout CI pass is needed *before* a merge.
  - G5: every PR carries a Constitution Check.
  - G6: Conventional Commits.
  - V4: a policy change ships with its templates, hooks and checklists.
- **Today's flow** (until this ADR): the agent asks before each commit and push, fast-forwards a feature branch into `main` locally, and pushes. CI runs only after the change is on `main`, which breaks G2. On 2026-11-13 a markdownlint error (MD012 in a `.scratch` ticket) reached `main` this way, and a second commit had to fix it.
- **Repository settings, checked 2026-11-13:** the only ruleset on `main` blocks force-pushes and deletion and requires no checks. Merge commits are disabled; squash and rebase merges are allowed; head branches are deleted on merge.
- **CI jobs today:** "Markdown lint, spelling and ADR headers", "Protocol spec" and "Links" (`docs.yml`, run on `pull_request` and on pushes to `main`), and "No private identifiers in any branch history" (`privacy.yml`, run on `pull_request` and every push).
- **GitHub rulesets** ([available rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)): required status checks can be strict (the branch must be up to date with `main`), and a pull request rule can allow only certain merge methods. With 0 required approvals, a PR is a record and a gate for checks, not a review.
- **Tracker:** AGENTS.md and the skills use the local markdown tracker in `.scratch/` ([conventions](../agents/issue-tracker.md)).

## Decision

1. **Backlog location.** The 0.1.0 implementation backlog lives in `.scratch/meshlink-0.1.0-build/`: a `spec.md` that points to the protocol spec and the ADRs, plus one `issues/NN-<slug>.md` per ticket. It is kept apart from the planning map in `.scratch/meshlink-0.1.0/`.
2. **Ticket template.** The tracker conventions define it: `Status:`, `Blocked by:` and `Type: AFK|HITL` lines, then the sections Behavior, Spec and ADR references, Acceptance criteria (checkboxes), Proof commands (exact), Docs to update and Devices needed.
3. **Ticket states.** `needs-triage` → `ready-for-agent` or `ready-for-human` → `claimed` → `done`, plus `needs-info` and `wontfix` ([triage labels](../agents/triage-labels.md)).
4. **Pull requests for every change.** Every change reaches `main` through a pull request merged with a squash merge, including docs-only and tracker changes. The PR title is a Conventional Commit and becomes the single commit on `main`. The local fast-forward flow is retired.
5. **PR template.** `.github/pull_request_template.md` carries the ticket link, the proof commands run, the G5 Constitution Check and the O1/O2 docs note.
6. **Ruleset on `main`.** In addition to the existing rules against force-pushes and deletion:
   - required status checks, strict: "Markdown lint, spelling and ADR headers", "Protocol spec" and "No private identifiers in any branch history";
   - linear history;
   - a pull request with 0 required approvals and squash as the only allowed merge method;
   - no bypass actors.

   "Links" is not required, because its scheduled external run depends on other sites. The backlog's first build ticket adds its Gradle and Xcode jobs to the required checks in the same change.
7. **Agent autonomy.** On a `ready-for-agent` ticket, an agent may create a feature branch, commit, push it and open the PR without asking each time. Merging, releases, ruleset and settings changes, and anything outside the repository still need the owner's explicit approval each time.

## Alternatives

- **GitHub Issues for the backlog:** native blocking and a board, but agents would need the network and `gh` for every read, and the skills and AGENTS.md already use the local tracker. Rejected.
- **Keep fast-forward merges with approval per push:** breaks G2, because CI runs after the change lands. Rejected.
- **Rebase merges:** keep every commit of a branch, but work-in-progress commits would reach `main`, and each would need to be a clean Conventional Commit. Rejected.
- **Required approvals:** the only maintainer can't approve their own PR. Rejected while there is one maintainer.
- **An owner bypass on the ruleset:** would let a direct push skip the checks again. Rejected.
- **Requiring "Links":** a failing external site would block every merge. Rejected.

## Risks

- **Overhead for small changes:** each tracker or docs edit needs a PR and a CI run (about a minute today). Accepted: the MD012 incident shows that small changes break too.
- **Strict checks:** a PR must be updated whenever `main` moves, so it reruns CI. With one maintainer, few PRs overlap.
- **Renamed jobs:** a required check that is renamed, or a workflow that stops running on `pull_request`, leaves PRs waiting forever. A change to a required job's name must update the ruleset in the same PR's approval.
- **Agent autonomy:** an agent can push wrong work to a branch. The PR, the required checks and the owner's merge approval stop it before `main`.

## Migration

- AGENTS.md (GIT/EXTERNAL), the [tracker conventions](../agents/issue-tracker.md), the [triage labels](../agents/triage-labels.md) and the PR template change in the same PR as this ADR (V4).
- The ruleset changes after that PR merges, with the owner's approval, and is checked through the GitHub API.

## Consequences

- **0.1.0 map:** the "write backlog" ticket writes into `.scratch/meshlink-0.1.0-build/` with this template, and the build-order ticket can rely on PR-sized slices.
- **[ADR 0019](0019-release-versioning-and-api-compatibility.md):** releases still need explicit approval through the release environment; this ADR does not change that.
