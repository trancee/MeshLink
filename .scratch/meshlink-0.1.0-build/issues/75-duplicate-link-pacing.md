# Find why the side that closes a duplicate link sets its pacing

Status: needs-triage
Type: AFK
Blocked by: none

## Behavior

Found in ticket 54b, while writing the `InboxFull` wiring test (`DiagnosticsWiringTest`). Two linked `LinkFakes` nodes dial each other, and the tie-break keeps the link whose central has the lower NodeId (spec "Keep one link per neighbor"). The NodeIds are random, so the test cannot choose which side is central on the kept link. The kept link then behaves in one of two ways:

- **Fast:** every `send` returns `Sent` at once. The JVM runs always took this variant.
- **Slow:** Alice closes the duplicate, and Bob sees `LinkClosed(DuplicateLink, byPeer=true)`. Each send is then paced over the air: about 2.6 s per 100 kB, and 17–36 ms per 1 kB.

Neither variant reports `ML3010 BearerSwitched`, so the difference is not an L2CAP switch. The kept link should carry frames at the same pace whichever side closed the duplicate. Find the cause, then fix it or document it as intended.

Ticket 54b worked around the difference: the test allows 20 s after sending and asserts only the first `ML7003`. The test passed in four iOS reruns.

## Evidence

- The slow variant was seen only on `iosSimulatorArm64Test`; the iOS runs draw different random NodeIds.
- The cause predates ticket 54b: the variants come from existing link and `VirtualAir` behavior.

## Questions

- Hypothesis: the variant decides whether Alice sends as the central (GATT writes) or as the peripheral (notifications). Do the two directions pace differently in `Links` flow control or in `VirtualAir`?
- If they do, is the difference intended (spec "Flow control"), or should both directions carry frames at the same pace?

## Spec and ADR references

- [link.md, Keep one link per neighbor](../../../docs/spec/link.md#keep-one-link-per-neighbor) and [Flow control](../../../docs/spec/link.md#flow-control); [D-22](../../../docs/spec/decisions.md#d-22-duplicate-links-and-close-reasons).
