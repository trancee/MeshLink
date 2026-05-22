## Spec Kit workflow quick reference

Example seed prompts:

```text
/speckit.specify
MeshLink is a library-first SDK for encrypted, serverless, offline-capable
messaging over Bluetooth Low Energy (BLE) mesh networks — no internet, no
servers, no user accounts.

Core capabilities:
- multi-hop mesh routing (Babel-based, proactive route propagation)
- two-layer encryption — Noise XX hop-by-hop + Noise K end-to-end
- large-message transfer with chunking, SACK, and flow control
- power-aware operation (auto-adjusts radio behavior by battery state)
- cross-platform Android/iOS support via KMP

/speckit.plan Use KMP for implementation with no external dependencies
```

Standard flow:

1. Install: `uv tool install specify-cli --from git+https://github.com/github/spec-kit.git@vX.Y.Z`
2. Init project: `specify init <PROJECT> --integration <agent>`
3. Set principles: `/speckit.constitution`
4. Write the spec: `/speckit.specify`
5. Clarify gaps: `/speckit.clarify` *(optional but recommended before planning)*
6. Validate the spec: `/speckit.checklist` *(optional after clarify)*
7. Generate the plan: `/speckit.plan`
8. Break the work into tasks: `/speckit.tasks`
9. Optionally convert tasks to issues: `/speckit.taskstoissues`
10. Check consistency: `/speckit.analyze` *(optional after tasks, before implementation)*
11. Build it: `/speckit.implement`
