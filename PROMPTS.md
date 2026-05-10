/speckit.specify
MeshLink is a **library-first SDK** for encrypted, serverless, offline-capable
messaging over **Bluetooth Low Energy (BLE) mesh networks** — no internet, no
servers, no user accounts.

**Core capabilities:**
- Multi-hop mesh routing (Babel-based, proactive route propagation)
- Two-layer encryption — Noise XX hop-by-hop + Noise K end-to-end
- Large message transfer with chunking, SACK, and flow control
- Power-aware operation (auto-adjusts radio behavior by battery state)
- Cross-platform — Android, iOS (shared code via KMP)

/speckit.plan Use KMP for implementation with no external dependencies



## Full Spec Kit Workflow — Quick Reference
Install:
> `uv tool install specify-cli --from git+https://github.com/github/spec-kit.git@vX.Y.Z`

Init project:
> `specify init <PROJECT> --integration <agent>`

Set principles:
> `/speckit.constitution`
— run once per project

Write spec:
> `/speckit.specify`
— describe what to build, not how

Clarify gaps:
> `/speckit.clarify`
(optional, recommended before plan)

Validate spec:
> `/speckit.checklist`
(optional, after clarify)

Generate plan:
> `/speckit.plan`
— specify tech stack and architecture

Break into tasks:
> `/speckit.tasks`
+ optional `/speckit.taskstoissues`

Check consistency:
> `/speckit.analyze`
(optional, **after tasks**, before implement)

Build it:
> `/speckit.implement`
