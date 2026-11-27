# Find why the side that closes a duplicate link sets its pacing

Status: done
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

## Finding (2026-11-27)

The pace depends on which end is central on the kept link; who closed the duplicate is only a timing side effect. Intended; documented, no code change.

- A throwaway probe started 16 fresh `VirtualAir` pairs with random NodeIds, sent 100,000 B from Alice and logged each link's keep decision and each message record's writer. 8 pairs were fast (`Sent` at 0 ms, received at 30 ms) and 8 slow (`Sent` at 2,663 ms, received at 2,689 ms). Both ends always kept the same link, the one whose central has the lower NodeId.
- In every fast pair Alice was central on the kept link and wrote the message's 99 records uncredited; in every slow pair she was its peripheral and wrote them as credited notifications.
- That asymmetry is the spec: "The central's writes … are not credited", and the peripheral sends one notification per credit (spec "Flow control", D-47). The default `VirtualAir` has no airtime, so a central's writes cost nothing, while a peripheral waits a latency round trip for each grant of 4 credits: about 410 chunks, about 100 grants of about 26 ms.
- With `airtimeBytesPerSecond` set, both directions take their airtime. Whether D-47's window suits real phones is a device question, outside this ticket.
- Documented in the `VirtualAir` KDoc, the `DiagnosticsWiringTest` comment and `docs/agents/lessons.md` ("VirtualAir link tests").

## Spec and ADR references

- [link.md, Keep one link per neighbor](../../../docs/spec/link.md#keep-one-link-per-neighbor) and [Flow control](../../../docs/spec/link.md#flow-control); [D-22](../../../docs/spec/decisions.md#d-22-duplicate-links-and-close-reasons).
