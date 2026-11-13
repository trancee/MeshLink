# Enforce link limits, flow control and connect retries

Status: ready-for-agent
Type: AFK
Blocked by: 28

## Behavior

The sans-IO core applies the ADR 0009 link policies on `VirtualAir`, through the `RadioPort` contract. The Android and iOS adapters map their platform signals to this contract in ticket 31, which is blocked by the whole radio tracer (17). This ticket changes no adapter.

- **Connect retries (decision 4):** a connect that fails with a transient cause is retried up to 3 times, with a backoff of 500 ms × n. A close about 30 s after connect with no error counts as a transient link loss and takes the same retries. `RadioPort` gains a typed transient cause, which the Android adapter later maps from GATT status 133 and 147.
- **Crossed connections (decision 5, D-22):** when two completed links reach the same NodeId, both sides keep the link whose central has the lower NodeId (bytewise) and send `CLOSE` `DUPLICATE_LINK` on the other. Frames already received on the closed link are processed. Frames still queued on it move to the kept link. `DUPLICATE_LINK` keeps the peer's routing state.
- **Link limits (decision 6):** a node whose platform reports a peripheral-role cap accepts at most that many incoming links, and makes any further links as central. iOS reports 3 on every version. Refused connections are counted with a typed diagnostic event.
- **Flow control (decision 7; reverses [C-09](../../../docs/spec/decisions.md#c-09)):** a new link-local `CREDIT` frame, chosen by the owner on the evidence of [library rule R14](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md) (the only working signal was an application-level credit from the central; Android's `onNotificationSent` means queued, not sent).
  - **Direction:** the central grants the peripheral GATT notification credits. One credit allows one GATT notification carrying one link record.
  - **Layout:** a type byte, the next free link-local code in `docs/spec/data/frame-types.json` (`0x10` today; verify before assigning), then a `u16` big-endian count of credits granted. 3 B in total.
  - **Window:** the constant `gatt-credit-window` = 8 records, added to `docs/spec/data/limits.json`.
  - **Central:** sends an initial grant of the full window as soon as the link opens (after `PLAIN_HELLO` or handshake completion), and tops up once the peripheral has consumed half the window, restoring the outstanding credits to the full window.
  - **Peripheral:** never has more GATT notifications in flight than its outstanding credits; with none left it queues, never drops. Grants add to the outstanding count; a grant that would raise it above the window is a protocol error.
  - **Not credited:** traffic on an L2CAP CoC bearer, which has its own credits, and writes from the central to the peripheral.
  - A slow peer never blocks other links, and bulk data prefers CoC once ticket 31 adds it.
- **Close (D-22):** `CLOSE` reasons `0x00`–`0x07` are encoded and decoded. An unknown reason is treated as `NORMAL`. After sending `CLOSE`, a node sends nothing more and disconnects.

## Spec and ADR references

- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 4–7 and the 2026-11-08 amendment (pacing is local), which this ticket amends again for the `CREDIT` frame.
- [Spec change control](../../../docs/spec/README.md#status-and-change-control) and [link.md, Link-local frame summary](../../../docs/spec/link.md#link-local-frame-summary).
- [link.md, Keep one link per neighbor](../../../docs/spec/link.md#keep-one-link-per-neighbor) and [Close](../../../docs/spec/link.md#close).
- [D-22](../../../docs/spec/decisions.md#d-22-duplicate-links-and-close-reasons) and [C-09](../../../docs/spec/decisions.md#c-09).
- [Library rules](../../../docs/evidence/2026-11-bearer-bakeoff/library-rules.md) R7, R8, R9, R14, R15 and R16.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 1, 2 and 13; [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md) (`ML3003`, `ML3004`; the catalog lands in ticket 54).

## Acceptance criteria

- [ ] `VirtualAir` can inject transient connect failures, a silent close about 30 s after connect, crossed connections, a peripheral cap and slow notification delivery, each seeded.
- [ ] A seeded test shows exactly 3 retries at 500, 1,000 and 1,500 ms of virtual time, then a reported failure. A non-transient failure is not retried.
- [ ] A seeded test with both nodes dialing at once ends with exactly one link on both sides, chosen by the lower central NodeId, with queued frames delivered once on the kept link.
- [ ] The `CLOSE` cases in `link-frames.json` pass in the one runner, with a deterministic mutation test. An unknown reason acts as `NORMAL`.
- [ ] A seeded 6-node test with an iOS-like cap of 3 shows a fourth incoming link refused and the node dialing out as central instead.
- [ ] Spec change, following [Status and change control](../../../docs/spec/README.md#status-and-change-control): `CREDIT` is registered in `frame-types.json` and the link-local frame summary; `gatt-credit-window` = 8 is in `limits.json`; link.md gains a "Flow control" section with the rules above; a new D-xx entry in `docs/spec/decisions.md` records the frame with its R14 evidence and alternatives, and C-09 gets a note that the D-xx entry reverses it; `link-frames.json` vectors are regenerated with `python3 tools/spec/gen_vectors.py` and cover a valid grant, a short frame, a long frame and a zero grant; `python3 tools/spec/check_spec.py` passes.
- [ ] A dated ADR 0009 amendment (version bump) changes decision 7 from local pacing to central-granted `CREDIT` frames with the 8-record window, and notes the C-09 reversal; `python3 tools/check_adrs.py` passes.
- [ ] The `CREDIT` codec passes the `link-frames.json` cases in the one runner, with a deterministic mutation test (ADR 0017 decision 9).
- [ ] A seeded test shows the central's initial grant after the link opens and a top-up after 4 notifications are consumed; the peripheral never has more than 8 notifications outstanding, and a slow central stalls only its own link while other links keep their throughput.
- [ ] A seeded test shows CoC traffic is not credited and sends no `CREDIT` frames.
- [ ] T10: a grant that raises outstanding credits above the window, and a `CREDIT` frame from a peripheral, close the link with `PROTOCOL_ERROR`.
- [ ] T10: a flood of crossed connections and a peer that never acknowledges cannot grow memory or retries without bound.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
python3 tools/spec/gen_vectors.py
python3 tools/spec/check_spec.py
python3 tools/check_adrs.py
```

## Docs to update

- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md): dated amendment of decision 7 with the `CREDIT` frame and window, noting the C-09 reversal (version bump).
- [link.md](../../../docs/spec/link.md): a "Flow control" section and the `CREDIT` row in the link-local frame summary.
- `docs/spec/data/frame-types.json`, `docs/spec/data/limits.json`, `docs/spec/vectors/link-frames.json` (regenerated), and [decisions.md](../../../docs/spec/decisions.md): the new D-xx entry and the C-09 note.

## Devices needed

None. `VirtualAir` only. Ticket 31 wires the adapters, and ticket 33 confirms the credit window on phones.
