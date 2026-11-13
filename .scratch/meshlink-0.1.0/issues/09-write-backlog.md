# Write the 0.1.0 backlog

Type: task
Status: open
Blocked by: 01, 02, 03, 04, 05, 06, 07, 08, 10

## Question

Write the ordered implementation backlog in the form ticket 05 decides, sliced per ticket 07, including release infrastructure (v1 ticket 36), the release workflow, device-lab fixes and the evidence runs of ticket 06. Reaching this ticket's end reaches the destination.

## Context

- Carried from v1: [ticket 36](../../meshlink-v1/issues/36-release-infrastructure.md).
- From ticket 06 ([ADR 0017 amendment](../../../docs/adr/0017-testing-architecture.md#amendments)): the `labapp/` app, the `tools/lab/` scripts, a HITL lab-fault ticket (Bluetooth off on 5 remote phones, nova 9 adb timeout, Pixel 4a address) and the release evidence run itself.
- From ticket 07: milestones M0–M9, the tracer order and the slicing rules ([answer](07-build-order-and-slices.md#answer)).
- From ticket 08 ([ADR 0006 amendment](../../../docs/adr/0006-documentation-architecture.md#amendments)): the Pages workflow publishing Dokka at `/api/` plus the hand-written root `index.html`.
