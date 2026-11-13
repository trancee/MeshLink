# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`, never a single combined tickets file
- Triage state is recorded as a `Status:` line near the top of each issue file (see `triage-labels.md` for the role strings)
- Comments and conversation history append to the bottom of the file under a `## Comments` heading
- The 0.1.0 implementation backlog lives in `.scratch/meshlink-0.1.0-build/` ([ADR 0021](../adr/0021-change-workflow-and-merge-gates.md))

## Implementation ticket template

```markdown
# <behavior, as a short sentence>

Status: needs-triage
Type: AFK
Blocked by: NN, NN

## Behavior

<the slice: what a caller or peer observes once this ticket is done>

## Spec and ADR references

<protocol spec sections and ADR decisions this slice implements>

## Acceptance criteria

- [ ] <checkable outcome>

## Proof commands

<exact commands whose output proves the criteria, e.g. a Gradle task or a device run>

## Docs to update

<pages, references and examples this slice changes, or "none" with the reason>

## Devices needed

<none, or the device classes and hosts a HITL proof needs>
```

`Type: AFK` means an agent can finish the ticket alone; `Type: HITL` means it needs the owner (devices, accounts, decisions). Omit `Blocked by:` when nothing blocks the ticket. The ticket reaches `main` through one pull request (ADR 0021).

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` (the Notes / Decisions-so-far / Fog body).
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`); a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.
