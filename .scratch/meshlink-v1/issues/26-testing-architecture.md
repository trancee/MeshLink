# What is the testing architecture?

Type: grilling
Status: open

## Question

Decide the virtual multi-node transport, Robolectric use, device-lab automation and its CI integration, fuzzing (decoders per ADR 0012), and the conformance vector runner and coverage (ADR 0006).

## Context

- Merges fog items: testing architecture, conformance vector runner, CI with the device lab.
- ADR 0011: `meshlink-testing` (Kotlin) and `MeshLinkTesting` (Swift) run the same reference scenarios.
