# Fragment link records and answer busy

Status: done
Type: AFK
Blocked by: 24

## Behavior

A frame larger than one record's plaintext travels as `FRAGMENT` frames, and a receiver that cannot take a new stream answers `BUSY`. This lets direct messages larger than one record cross a link on `VirtualAir` in every profile.

- **`FRAGMENT` (`0x01`):** `stream_id` (`u8`), `index` (`u16`, bit 15 marks the last fragment), then 1–1,020 B of data in cleartext records or 1–1,004 B in encrypted ones. Fragment only frames that do not fit one record; never nest fragments. Streams may interleave.
- **Fatal link errors:** a fragment number that is not the next one, a fragment after the last, a number above 32,767, or a reassembled frame above `frame-max` (139,264 B). Each closes the bearer; the spec allows a `CLOSE` with `PROTOCOL_ERROR` first.
- **Limits:** a stream idle for 10 s is discarded. At most 4 reassemblies per link, at most 1 of them large. Large reassemblies share a 1 MiB budget per node. A stream is large once it passes `frame-small-max` (8,192 B).
- **`BUSY` (`0x06`):** when a new stream exceeds a limit, the receiver discards its fragments and answers once with `stream_id` and reason `0x01 REASSEMBLY_SLOTS` or `0x02 LARGE_BUDGET`. The sender stops that stream and treats the frame as not sent on this hop, so its normal retry applies. An unknown reason means `REASSEMBLY_SLOTS`.
- **Records (D-19):** a length of 0, a length over 1,024, or a decrypt failure is fatal.

Large messages and their size classes come with ticket 50.

## Spec and ADR references

- [link.md, Link records](../../../docs/spec/link.md#link-records), [Fragments](../../../docs/spec/link.md#fragments), [Busy reply](../../../docs/spec/link.md#busy-reply) and [Close](../../../docs/spec/link.md#close).
- [D-19](../../../docs/spec/decisions.md#d-19-record-and-frame-size-thresholds), [D-23](../../../docs/spec/decisions.md#d-23-busy-reply).
- [ADR 0012](../../../docs/adr/0012-wire-format-and-codec.md) decision 4 and [ADR 0015](../../../docs/adr/0015-message-size-and-compression.md) decisions 3, 5 and 6.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 2, 9 and 10; [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md) (`ML3009`, `ML7004`; the catalog lands in ticket 54).

## Acceptance criteria

- [x] The `FRAGMENT` and `BUSY` cases in `link-frames.json` and every `records.json` case pass in the one runner. Each decoder has a deterministic mutation test: every prefix length, over-long inputs and a fixed number of seeded mutations.
- [x] A seeded `VirtualAir` test sends direct messages of 1 and 2 records in each profile (secured profiles send messages from ticket 45 on; until then, fragment link-level frames there), and a link-level test sends frames up to `frame-max` with interleaved streams. Every frame arrives intact.
- [x] T10: each fatal fragment error and each fatal record error closes the bearer and frees all its reassembly memory.
- [x] T10: a fifth stream on one link and a second large stream get `BUSY REASSEMBLY_SLOTS`. Exhausting the node budget across links gets `BUSY LARGE_BUDGET`. Neither closes the link.
- [x] A test shows a stream idle for 10 s is discarded and its `stream_id` is free again.
- [x] A test shows a sender that receives `BUSY`, including one with an unknown reason, stops the stream and retries the frame later.
- [x] A test shows a sender never fragments a frame that fits one record and never nests `FRAGMENT` frames.
- [x] T10 (D-20, from ticket 24): a `HANDSHAKE` whose reassembled Noise message exceeds `handshake-message-max` (4,096 B) fails the handshake with `CLOSE` `HANDSHAKE_FAILED` and `ML4001`.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected. The spec already defines the behavior. If the code shows a gap, report it to the owner rather than changing the spec in this PR.

## Devices needed

None. `VirtualAir` only. Ticket 33 proves fragments under load on phones.

## Outcome

- **Codec:** `LinkFrame.Fragment` (with `split`), `LinkFrame.Busy` and `BusyReason` in `meshlink-core`; an unknown reason reads as `REASSEMBLY_SLOTS`. The `0x01` and `0x06` vector cases now run in the one runner, with its mutation inputs. A number above 32,767 cannot be encoded: the index has 15 bits for the number and bit 15 for "last".
- **Reassembly:** `Reassembly` per link and `ReassemblyBudget` per node (`meshlink-core`). Fatal: a first fragment that is not number 0, a skipped number, a fragment after the last, a stream that starts with a `FRAGMENT` frame, a frame above the limit, and every fragment or `BUSY` that fails to decode. Each closes with `CLOSE PROTOCOL_ERROR`, and `release` returns the link's large-stream bytes to the budget. The budget discards idle streams on every link before it refuses a charge.
- **Handshake:** while a secured handshake runs, a link reassembles at most a `HANDSHAKE` frame's 4,099 B. A fatal fragment, a longer stream or a `BUSY` fails the handshake with `HANDSHAKE_FAILED` and `ML4001 PROTOCOL`.
- **Sender:** a message frame longer than a record body goes out as one stream; the link's single message pump keeps one stream in flight, so stream ids are taken in turn. Each record takes the write lock on its own, so a `CLOSE` can pass between fragments. `BUSY` during the stream stops it; `BUSY` after it settles the message again as not sent. A stream is remembered for 10 s.
- **Structure:** the receive path (`receive`, `dispatch`, `reassemble`, `frame`) moved into `Links.Inbound`, an inner class, to keep `Links` within detekt's function limit; behavior is unchanged.
- **Retry:** "its normal retry applies" is the existing outbox re-queue at the front of the peer's queue; the doubling route retry comes with tickets 37 and 47. Status stays `Sent` (or `Held` once unlinked) while the message waits again.
- **Payload cap:** the `Unsecured` cap rose from 899 B to `unicast-payload-small-max` (4,096 B), so `send` reaches 1 to 5 records; large messages remain ticket 50.
- **Not in this ticket:** control frames are never fragmented yet: none exceeds a record until ticket 25's post-quantum handshake, which adds it. Secured links carry no messages until ticket 45, so the sender's fragment size for sealed records (1,004 B) is left to ticket 45 rather than kept as untested code.
- **Tests:** `FragmentLinkTest` (9), new cases in `ClassicalLinkTest`, `LinkFrameTest` and `ReassemblyTest` (11). `VirtualLinkEnd.paced` makes a write wait for delivery, so a test can answer `BUSY` mid-stream; ignoring `BUSY` during a write fails that test.
- **Spec gaps:** none found.
