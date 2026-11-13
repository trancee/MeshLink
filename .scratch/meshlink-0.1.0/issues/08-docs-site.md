# Does 0.1.0 publish a prose docs site, and with which generator?

Type: grilling
Status: resolved
Blocked by: 02

## Question

Decide whether 0.1.0 publishes the prose docs as a site next to the Dokka reference, and with which generator; amend ADR 0006 and register any new tool.

## Context

- Inputs: [ticket 02](02-docs-site-generator.md).

## Answer

2026-11-13, grilling; recorded as the 2026-11-13 amendment to [ADR 0006](../../../docs/adr/0006-documentation-architecture.md#amendments).

- **0.1.0:** Pages publishes only the Dokka reference; the prose stays on GitHub. Neither Zensical (beta) nor Starlight (0.x) is a stable release (E1–E3).
- **URLs:** Dokka at `/MeshLink/api/` from day one; a small hand-written root `index.html` links to it and to the docs on GitHub; latest release only.
- **Next look:** at Zensical's first stable release or 1.0.0, whichever is first; Zensical if stable, otherwise Starlight.
- **Backlog:** the Pages workflow and the root page belong to the release-infrastructure work.
