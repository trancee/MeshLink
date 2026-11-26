# Troubleshoot by diagnostic code

Status: ready-for-agent
Type: AFK
Blocked by: 54d

## Behavior

Split from ticket [54](54-diagnostics-catalog.md). Three troubleshooting how-tos list the diagnostic codes by symptom, in regions generated from the hierarchy, with hand-written guidance that links each code's anchor.

## Spec and ADR references

- [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md#decision) decision 8; [ADR 0006](../../../docs/adr/0006-documentation-architecture.md#decision) decision 6.
- [Device-lab lessons](../../../docs/agents/lessons.md#device-lab): GATT 133/147 and "scan failed 2".

## Acceptance criteria

- [ ] The generator fills the generated regions of `docs/how-to/troubleshoot-peers-not-found.md`, `troubleshoot-messages-not-delivered.md` and `troubleshoot-high-battery-use.md` from each code's symptom group, and fails on a difference. `./gradlew updateReference` rewrites them.
- [ ] The pages explain the common platform codes (133, 147, scan failure 2). Their hand-written text links each code's anchor.
- [ ] `docs/README.md` links the troubleshooting pages.
- [ ] Kover stays at 100%. `./gradlew check` and `./gradlew dokkaGenerate` pass; markdownlint, typos and lychee pass.

## Proof commands

```sh
./gradlew check
./gradlew updateReference && git diff --exit-code docs
npx --yes markdownlint-cli2@0.24.1 "docs/**/*.md"
typos
lychee --offline .
```

## Docs to update

- The three troubleshooting pages (new); [`docs/README.md`](../../../docs/README.md).

## Devices needed

None.
