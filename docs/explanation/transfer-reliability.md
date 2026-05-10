# Transfer Reliability in M002

This page explains the large-payload behavior that **actually ships** in M002/S07.

M002 closes large-payload delivery for direct peers only. It does **not** ship the earlier aspirational SACK/NACK/resume protocol, and it does **not** close routed or multi-hop large-payload delivery.

## What ships in M002

The public seam stays unchanged:

- consuming apps still call `MeshLink.create(...)`
- direct sends still go through `MeshLinkApi.send(...)`
- failure visibility still comes out through `SendResult` and `MeshLinkApi.diagnosticEvents`

The large-payload work happens below that seam inside proof-GATT:

1. inline payloads that fit the proven frame capacity still go out as one payload frame
2. larger direct payloads switch to an internal bounded stop-and-wait transfer session
3. the runtime promotes from the conservative identity-frame fallback only when the client or subscribed server path proves a larger payload capacity on the live connection
4. the runtime translates terminal transfer failures into typed public results and redacted diagnostics
5. the proof apps surface only redacted peer suffixes, byte counts, digest summaries, and typed result/failure state

## The wire model that ships

Large proof-GATT sends use a bounded stop-and-wait session:

`START / CHUNK / ACK / COMPLETE / ABORT`

That is the implemented protocol shape. There is no shipped SACK bitmap, no selective retransmission window, and no resume-after-reconnect handshake in M002.

### Session flow

For a large direct send, the sender:

1. sends `TRANSFER_START`
2. waits for an ACK for the start stage
3. sends one `TRANSFER_CHUNK`
4. waits for an ACK for that chunk index
5. repeats chunk-by-chunk
6. sends `TRANSFER_COMPLETE`
7. waits for the terminal ACK

The receiver keeps at most one inbound transfer session per device/peer at a time. Unexpected frames, conflicting transfer ids, out-of-bounds chunk indexes, duplicate terminal frames, or stalled reassembly all terminate the active session fail-closed.

## Why the bounds are conservative and still large enough for `100 KiB`

The proof transport does **not** expose a new public MTU or capacity API. Instead, it derives its send budget from capacity that has already been proven safe on the connection.

Three limits matter:

1. the absolute protocol ceiling: a 512-byte proof-GATT frame ceiling with a 256-chunk transfer budget
2. the conservative identity-frame fallback: the first successfully exchanged identity frame, which is currently 80 bytes on proof transports
3. the promoted per-peer capacity: a platform-reported payload capacity that is only trusted after identity proof on the client path or subscribed server path

With the current proof identity layout (12-byte peer id + 32-byte X25519 public key + 32-byte Ed25519 public key + headers), the conservative identity frame is 80 bytes. That produces these bounded transfer budgets:

| Budget source | Frame capacity | Inline payload capacity | Transfer chunk payload capacity | Max direct transfer bytes | When used |
|---|---:|---:|---:|---:|---|
| Conservative identity fallback | 80 B | 76 B | 70 B | 17,920 B | when the platform cannot prove anything larger yet |
| Platform-proven proof-GATT ceiling | 512 B | 508 B | 502 B | 102,400 B | when the client or subscribed server path proves the full 512-byte payload budget |

The runtime refuses to trust malformed capacity reports. If the platform reports `null`, a non-positive value, a number above the 512-byte protocol ceiling, or a value smaller than the already-proven identity frame, the runtime falls back to the conservative identity budget instead of widening the send contract optimistically.

On the current Android and iOS proof transports, the platform-reported subscribed capacity reaches the full 512-byte proof-GATT ceiling. That is why `Frame + 1` and `100 KiB` are now truthful expected direct-send successes on the current proof clients, while anything above `100 KiB` still fails closed.

## Retry and reassembly bounds

The implementation is intentionally small and bounded:

- Each outbound transfer step waits up to 2 seconds for an ACK and retries at most 3 times.
- If all 3 waits expire, the sender aborts the session and records retry exhaustion.
- Inbound reassembly keeps a single active session per peer/device and expires it after 10 seconds of inactivity.
- Peer loss during an active transfer records a terminal transfer failure instead of silently succeeding.
- Duplicate terminal frames replay the cached terminal response instead of re-delivering partial or duplicate payloads.

These bounds are what the transport, runtime, and platform contract tests verify today.

## What public callers can observe

Only the **direct** send path currently translates terminal large-transfer failures back into typed public results.

### Direct-send outcomes

For direct peers, `MeshLinkApi.send(...)` can return:

- `Sent`
- `NotSent(PAYLOAD_TOO_LARGE)`
- `NotSent(TRANSFER_TIMED_OUT)`
- `NotSent(TRANSFER_ABORTED)`

On the current proof transports, `Sent` is the expected result for both `Frame + 1` and `100 KiB` when the link has proven the full 512-byte proof-GATT budget. Above `100 KiB`, the direct send remains fail closed with `NotSent(PAYLOAD_TOO_LARGE)`.

When a terminal large-transfer failure happens, the runtime also emits `TRANSFER_FAILED` with a redacted `DiagnosticPayload.TransferFailure` containing:

- `recipientSuffix`
- `stage`
- `reason`
- `transferBytes` when available
- `maximumTransferBytes` when available

The public payload omits full peer ids, raw chunk bytes, transfer-session ids, and plaintext bodies.

### Continuity diagnostics that remain additive

The transport also emits additive continuity diagnostics that stay redacted when they cross the public seam:

- `BLE_STACK_UNRESPONSIVE`
- `DELIVERY_TIMEOUT`
- `L2CAP_FALLBACK`
- `TRANSPORT_MODE_CHANGED`

These are useful for failure visibility, but they are not a substitute for the terminal direct-send result above.

## Why the proof apps have both `Frame + 1` and `100 KiB`

The proof clients expose two presets on purpose:

- `Frame + 1` is the smallest payload that proves chunking instead of a single-frame write
- `100 KiB` is the largest bounded direct payload that the current proof-GATT contract expects to succeed on the proven 512-byte path

In other words, the `100 KiB` proof preset is no longer a fail-closed placeholder. It is the bounded top-of-range success target for the current proof transports. The fail-closed boundary now starts **above** that preset.

One subtle but important consequence remains the same: `Last send` in the proof UIs reflects the requested plaintext size, while `TRANSFER_FAILED.transferBytes` reflects the transport-side byte count that hit the large-transfer boundary. Those numbers may differ because encryption adds overhead before the proof-GATT transport sees the frame bytes.

## What remains deferred beyond M002

The following work is explicitly not closed by S07:

- routed and multi-hop large-payload delivery remains deferred beyond M002
- selective acknowledgment bitmaps and NACK-driven sparse retransmission remain deferred
- resume-after-reconnect transfer state remains deferred
- any public MTU/frame-capacity reporting API remains deferred
- routed sends do not currently return the same direct-transfer terminal failure detail; they still fail through the routed next-hop path
- witnessed physical-device bilateral `100 KiB` evidence remains explicit S09 manual/UAT follow-up work; `scripts/verify-m002-automated.sh` proves code/doc drift, not live-radio observation

So the truthful M002 claim is narrower and stronger:

- proof-GATT can chunk and reassemble bounded **direct** large payloads above one frame
- the current Android/iOS proof transports can truthfully deliver `100 KiB` direct payloads within that bounded contract
- retries and reassembly are explicitly bounded
- above-limit direct sends still fail closed with typed, redacted failure state
- the proof apps and runbooks make both bounded success and fail-closed boundary evidence reproducible
