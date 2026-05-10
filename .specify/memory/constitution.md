<!--
Sync Impact Report
Version change: 1.1.0 -> 1.2.0
Modified principles:
- IV. Evidence-Backed Planning and Validation -> IV. Evidence-Backed Planning, Skill-First Execution, and Validation
- V. Artifact Synchronization and Integration Neutrality (expanded with skills reporting obligations)
Added sections:
- Skill Invocation & Reporting
Removed sections:
- None
Templates requiring updates:
- ✅ updated: .specify/templates/plan-template.md
- ✅ updated: .specify/templates/tasks-template.md
- ✅ verified: .specify/templates/spec-template.md (no new spec section required)
- ✅ verified: .specify/templates/commands/*.md (directory absent; no command templates to sync)
- ✅ updated: AGENTS.md
- ✅ updated: .pi/prompts/speckit.specify.md
- ✅ updated: .pi/prompts/speckit.clarify.md
- ✅ updated: .pi/prompts/speckit.plan.md
- ✅ updated: .pi/prompts/speckit.tasks.md
- ✅ updated: .pi/prompts/speckit.implement.md
- ✅ updated: .pi/prompts/speckit.analyze.md
- ✅ updated: .pi/prompts/speckit.checklist.md
- ✅ updated: .pi/prompts/speckit.constitution.md
- ✅ verified: constitution.md (root project constitution consumed as authoritative source)
Follow-up TODOs:
- None
-->
# MeshLink Specification Constitution

## Core Principles

### I. Specification-First Delivery
Every feature proposal or material delivery-process change MUST begin with a
written specification or constitution amendment before planning or
implementation. A valid `spec.md` MUST capture prioritized user stories,
acceptance scenarios, functional requirements, success criteria, assumptions,
explicit out-of-scope boundaries, and a Constitutional Alignment summary that
references applicable obligations from root `constitution.md`. Unknown details
MAY remain only when marked `NEEDS CLARIFICATION` and carried into the plan as
research work. Rationale: MeshLink is governed by explicit protocol, security,
and performance constraints; undocumented intent is not safe enough.

### II. Independent Value Slices
User stories MUST be organized as independently valuable slices ordered by
priority. Plans and tasks MUST preserve that independence so a reviewer can
implement, validate, and demonstrate a higher-priority story without requiring
lower-priority work. Shared foundational work is allowed only when it is
explicitly identified as blocking multiple stories. Cross-platform or protocol
changes MAY share enabling work only when the plan explains why the work cannot
remain inside one story. Rationale: thin vertical slices reduce coordination
risk and keep delivery incremental.

### III. Measurable, Bounded, Constitution-Aware Requirements
Every requirement MUST be specific, testable, and traceable to a user scenario,
edge case, or explicit business constraint. Every success criterion MUST be
measurable by an independent reviewer without relying on subjective language.
Each specification MUST identify which root-constitution areas are affected:
Rigorous Code Quality, Exhaustive Testing Standards, User Experience
Consistency, Performance Requirements, Quality Gates, and Technical
Constraints. If an area is not applicable, the specification MUST say why.
Specifications MUST declare assumptions and out-of-scope items, and MUST use
`NEEDS CLARIFICATION` instead of silent guesswork when material details are
unknown. Rationale: reviewers must know which MeshLink constraints are in play
before design begins.

### IV. Evidence-Backed Planning, Skill-First Execution, and Validation
Implementation plans MUST convert each applicable constitutional constraint into
concrete evidence. Plans and tasks MUST record the required verification for
formatting and static analysis, API compatibility, explicit public API impact,
coverage expectations, Wycheproof vectors, harness-based integration tests,
cross-platform API and documentation parity, performance benchmarks, wire
compatibility, dependency-budget impact, and offline or minimum-platform
constraints when applicable. Before implementation or best-practice-oriented
work begins, relevant skill files from the project skill catalog MUST be
identified and read, or the operator MUST explicitly record that no specialized
skill applies. Each user story MUST define a validation path. Changes to code,
contracts, or data models MUST include automated tests or
contract/integration checks, and performance-sensitive work MUST include
benchmark evidence or an explicit non-applicability statement. Completion
claims MUST cite fresh evidence from the current change. Rationale: domain
constraints and best practices must shape the work before code changes start,
not after the fact.

### V. Artifact Synchronization and Integration Neutrality
The root `constitution.md`, this constitution, templates, specs, plans, tasks,
quickstarts, and agent guidance MUST remain synchronized whenever process or
governance changes. Repo guidance MUST use integration-neutral language and
MUST not assume a single AI assistant or vendor when generic wording is
possible. Placeholders, stale references, and deferred follow-ups MUST be
removed or explicitly tracked in the document that owns them. When both
constitutions apply, derived artifacts MUST satisfy both and MUST not silently
downgrade the stricter project rule. Task-completion reports for governed
workflow steps MUST include a `Skills Used` summary listing consulted skills or
explicitly stating `None`. Rationale: this repository is a reusable
specification system for a security-sensitive project; stale or vendor-locked
instructions erode trust and reuse.

## MeshLink Constraint Mapping

- `spec.md` MUST include a dedicated Constitutional Alignment section that maps
  the feature against applicable project constraints, including public API
  impact, test and benchmark obligations, cross-platform parity, dependency
  changes, and compatibility risks.
- `plan.md` MUST translate each applicable constraint into research tasks,
  design decisions, constitution checks, evidence-producing work items, and
  applicable skill guidance.
- `tasks.md` MUST add explicit work for API compatibility review,
  documentation parity, benchmark generation, Wycheproof or harness coverage,
  dependency pinning, or FlatBuffers compatibility validation whenever those
  areas are touched.
- Features that modify public APIs, crypto-provider usage, wire formats,
  runtime dependencies, minimum platform support, or cross-platform
  event/error/state behavior MUST be called out as high-risk in the plan.

## Skill Invocation & Reporting

- `plan.md` MUST include an `Applicable Skills` entry naming the skills that
  downstream implementation or best-practice-heavy work MUST consult, or the
  explicit value `None`.
- Before implementation, analysis, checklist generation, or other
  best-practice-oriented work begins, the operator or agent MUST read the
  relevant skill files and apply their guidance during execution.
- When a task completes, the user-facing report MUST include a `Skills Used`
  summary with each consulted skill and a short rationale, or an explicit
  statement that no specialized skill applied.
- Reviews MUST reject governed task outputs that omit required skill loading or
  the required `Skills Used` summary when applicable.

## Artifact Standards

- Feature artifacts MUST live under `specs/<feature-id-name>/` and use the
  canonical filenames `spec.md`, `plan.md`, `tasks.md`, `research.md`,
  `data-model.md`, `quickstart.md`, plus `contracts/` when external interfaces
  exist.
- `spec.md` MUST include sections for Out of Scope, Assumptions, and
  Constitutional Alignment.
- `plan.md` MUST include Constitution Check items for both constitutions, MUST
  record benchmark baselines or the reason benchmarks do not apply when
  performance-sensitive paths change, and MUST include `Applicable Skills`.
- `tasks.md` MUST include validation tasks for every story and cross-cutting
  tasks for any required parity, compatibility, or benchmark evidence.
- Documents MUST preserve stable identifiers for traceability, including user
  story labels, `FR-###`, and `SC-###`.
- Documentation MUST reference repository-relative paths in prose and MUST use
  ISO 8601 dates (`YYYY-MM-DD`) when dates are recorded.
- `AGENTS.md` context markers MUST point to the current plan when a plan exists.

## Workflow & Review

- Root `constitution.md` governs MeshLink engineering rules; this document
  governs how `.specify` artifacts capture and enforce those rules. Both are
  mandatory.
- The governing workflow is: constitution -> specification -> plan -> tasks ->
  implementation/analyze/review. Later artifacts MUST not weaken earlier
  approved intent without updating the upstream artifact first.
- The Constitution Check in each plan is a blocking gate before Phase 0 research
  and MUST be re-evaluated after design artifacts are produced.
- Reviews MUST reject unresolved placeholders, unsupported "done" claims,
  unjustified complexity, any story breakdown that violates independence or
  measurability rules, any plan or task list that omits applicable
  root-constitution obligations, and any governed completion report that omits
  the required `Skills Used` summary.
- Automation, templates, and hook-produced commit guidance MUST preserve the
  feature-branch workflow and Conventional Commit expectations defined by root
  `constitution.md`.
- When a governance change alters required sections, quality gates, review
  expectations, skill-loading expectations, or reporting expectations, the
  dependent templates and guidance MUST be updated in the same change or
  explicitly recorded as a follow-up TODO.

## Governance

This document is the highest-priority workflow document for `.specify`-managed
artifacts. The root `constitution.md` remains the supreme project constitution
for MeshLink development. When they overlap, the stricter rule wins, and this
file MUST be amended if workflow guidance drifts from project governance.

Amendments MUST be made in `.specify/memory/constitution.md` and MUST include:
(1) a clear summary of the change, (2) a semantic version update, (3)
synchronized template or guidance updates, (4) an updated Sync Impact Report at
the top of the constitution, and (5) explicit confirmation that root
`constitution.md` was reviewed for alignment when project-specific rules are
impacted.

Versioning policy is mandatory and follows semantic versioning for governance:
MAJOR for incompatible principle removals or redefinitions, MINOR for new
principles or materially expanded obligations, and PATCH for clarifications,
wording improvements, or non-semantic refinements.

Compliance review is mandatory for every specification, plan, task list,
implementation report, analysis report, checklist report, and completion claim.
Any non-compliance with either constitution MUST block approval until the
artifact is corrected or the constitutions are amended through the documented
process.

**Version**: 1.2.0 | **Ratified**: 2026-05-10 | **Last Amended**: 2026-05-10
