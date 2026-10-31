# AGENTS

R full [`CONSTITUTION.md`](CONSTITUTION.md) before mutation; summary != source. R/X/D/O/priority defined there.

## START

1. Load governing `AGENTS.md`, task specs/ADRs, [`PROJECT.md`](PROJECT.md) if present; Kotlin task => R [`guidance/kotlin.md`](guidance/kotlin.md).
2. Same-priority instructions: narrower path refines broader; unresolved same-scope conflict => ASK. Lower priority X weaken higher.
3. Inspect worktree; preserve user/concurrent changes. Derive stack/layout/framework/commands from scripts/config/build/CI; missing facts => unverified, X invent.
4. Map affected contracts/callers/tests/artifacts/platforms/docs/gates.
5. Non-trivial => short ordered plan + checkable completion + exactly one active implementation step.

R verified project profile per `PROJECT.md` schema, in that file or project-specific `AGENTS.md`.
START done iff behavior authority + proof commands known. Ecosystem/version recipes belong in scoped guidance.

## PATH

- feature|fix => T1: one behavior test red for intended reason -> smallest complete implementation -> green -> refactor green -> repeat. Pre-change pass/setup-failure != red.
- refactor => green baseline; preserve behavior; migrate all callers; delete obsolete path. Alias/dead path only for Q7 deprecation.
- docs-only => source/config/command-backed claims + O6 checks; X unrelated app suite unless executable/generated content affected.
- review|investigate|explain => read-only unless edits requested; findings R path/lines + limitations.
- security|protocol|schema|persistence => applicable ADR + O3 update/create + T10 cases.

## ASK

Unclear requirement/constraint/outcome/material tradeoff or human-only input => R ask tool; first exhaust repo/docs/config/tools.
Payload R self-contained `{objective,current_behavior/state,exact_unknown,why,distinct_options,cost/risk/compatibility/irreversibility_each}`.
O recommendation with constraint-backed reason. Ask minimum blocker; finish independent work first.
X ask tool-answerable facts or confirmation already determined by policy/convention.

## IMPLEMENT

- R existing compliant structure/naming + root-cause fix + requested scope; X parallel conventions/unrelated cleanup/error suppression/fixture special-cases/validation weakening/speculative retry/fallback/config/abstraction.
- Contract cutover => O1/O2: migrate all consumers/artifacts; delete obsolete paths, subject to Q7.
- R repo formatter; generated files => E6 owning command; X manual formatter workaround.
- Materially different compliant product/compat/security/maintenance choices => ASK.

Done iff zero repository-controlled consumer needs old behavior, except Q7 deprecation.

## VERIFY

1. Narrow changed behavior/test/program path.
2. All applicable format/static-analysis/build/test/coverage/security/compat/docs/benchmark/platform gates.
3. Clean/forced execution where supported; cache-only != proof.
4. Performance-sensitive => committed-baseline comparison.
5. Check applicable Constitution IDs.

Failure => incomplete: fix cause + rerun. External prerequisite => finish reachable work; report exact command/failure/missing prerequisite.

## GIT/EXTERNAL

- GitHub Actions: use the latest stable official release, verified when updating workflows; retain immutable commit-SHA pins and matching version comments.

- R G1 feature branch; X protected-default direct commit.
- PR only after O1/O2 reconciliation; if docs unchanged, explain why in PR.
- Commit/push/open-or-merge PR/publish/external-service change R prior explicit user approval.
- X discard unrelated work/rewrite history/force-push/destructive cleanup without explicit approval.
- Approved commit => repo format else Conventional Commits; AI co-author trailer if repo requires.
- R repo-preferred issue/PR/CI integration tool.

## DONE

R acceptance complete + applicable TDD red/green + O1/O2 agreement + applicable local/CI-equivalent gates pass or exact external blocker + no temporary/placeholder/disabled/stale/unjustified suppression/`TODO` state + Constitution compliance.
Report R `{changed_files/behavior,exact_commands/results,docs/API/compat/security/performance_impact,blockers/unverified,skills/specialized_instructions_used}`; X unobserved claims.

## Agent skills

### Issue tracker

Issues are local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` and `docs/adr/`. See `docs/agents/domain.md`.

### Lessons

Radio, fleet/device, crypto-choice, gate or proof work => R `docs/agents/lessons.md` first: concept, code map, verified commands, root causes found, rejected ideas.
