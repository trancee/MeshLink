# Agent Rules

Operational rules for any agent (human or AI) working in this repository.
`constitution.md` is the full authoritative source for engineering rules —
do not duplicate its content here; link to it instead.

## Workflow

- Before implementation or best-practice-oriented work, read the relevant
  skill files and include a `Skills Used` summary in the completion report.
- After making repository changes, create a Conventional Commit before
  moving to another governed task, phase, or command — unless an enabled
  auto-commit hook has already done it. See
  [Quality Gates](constitution.md#quality-gates) for the required commit
  format.
- All work MUST occur on feature branches. See
  [Quality Gates](constitution.md#quality-gates). Never commit directly
  to `main`.
- Always run the `/review` slash command before opening a pull request, and
  address any genuine issues it surfaces beforehand.
- Use the `gh` CLI for GitHub-related operations (issues, PRs, repos,
  workflow runs, etc.) instead of raw API calls or other tooling.
- When a decision is needed, do not choose alone. Present the available
  options clearly and concisely, then wait for the user to decide.

## Engineering rules

See `constitution.md` for the binding rules on code quality, testing,
cross-platform consistency, performance, and maintainable design:

- [I. Rigorous Code Quality](constitution.md#i-rigorous-code-quality)
- [II. Exhaustive Testing Standards](constitution.md#ii-exhaustive-testing-standards)
- [III. User Experience Consistency](constitution.md#iii-user-experience-consistency)
- [IV. Performance Requirements](constitution.md#iv-performance-requirements)
- [V. Maintainable Design and Change Isolation](constitution.md#v-maintainable-design-and-change-isolation)
- [Quality Gates](constitution.md#quality-gates)
- [Technical Constraints](constitution.md#technical-constraints)
- [Governance](constitution.md#governance) (amendment process and versioning)

Day-to-day conventions below constitutional level live in `docs/`.
