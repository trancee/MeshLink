# Copilot Instructions — MeshLink

See [AGENTS.md](../AGENTS.md) for the complete agent guide (setup, workflow,
testing, code style, architecture, CI, security, domain language).

This file supplements AGENTS.md with Copilot-specific notes.

## Quick Reference

```bash
# Lint → Test → Compile (run after every change)
./gradlew detekt
./gradlew :meshlink:jvmTest --parallel
./gradlew :meshlink:compileAndroidMain
```

## Copilot-Specific

- When generating Kotlin code, follow `detekt.yml` rules (max line length 160,
  no wildcard imports except `bluetooth.*`, argument wrapping per line).
- Use `expect`/`actual` for platform-specific code — never `#ifdef`-style branching.
- Prefer sealed result types over exceptions for expected failures.
- Use domain terms from `UBIQUITOUS_LANGUAGE.md` (Peer, Neighbor, TOFU, etc.).
- Update `docs/api-reference.md` when adding or changing public APIs.
