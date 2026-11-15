# Decide whether phones that can't advertise join as central-only nodes

Status: ready-for-human
Type: HITL
Blocked by: 17

## Behavior

The owner decides how MeshLink treats an Android phone whose adapter reports `isMultipleAdvertisementSupported` false, and the docs, the support tiers and the device-proof tickets follow that decision. Today such a phone is `Blocked(Unsupported)`: MeshLink never starts there.

Found in ticket 17: the Huawei P10 lite (Android 8.0) reports the flag false. In AOSP 8.0 that means fewer than 5 advertising instances (`MIN_ADVT_INSTANCES_FOR_MA`). An experiment build that skipped the check linked to the Pixel 6 as central and exchanged the message, but as peripheral no phone linked to it and a Mac scanner never saw its MESH advertisement, while the Pixel 6 was seen at once ([evidence](../../../docs/evidence/2026-11-15-radio-tracer/README.md#notes), [lessons](../../../docs/agents/lessons.md#device-lab)). The owner substituted the Mi Note 3 in ticket 17.

Options to put to the owner, with what each costs:

- **Keep `Unsupported`** (status quo): no new code or tests. Phones like the P10 lite can't join at all.
- **Central-only node:** start without advertising; scan and connect as central only. Two central-only phones never link; peers can't find the phone, so it joins only by reaching out. Needs a new requirement or state (ADR 0007 error model), a diagnostic (ADR 0020), the role in the capability record and routing (a node others can't reach first), host tests and a device proof.
- **Advertise anyway and let it fail:** rejected by the ticket 17 experiment; the advertisement never reached the air.

## Spec and ADR references

- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decision 9 (support tiers: Android 8 and 9 allowed, not fully supported).
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 1 and 6 (every node advertises and scans) and the 2026-11-15 amendment.
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) (`MeshState`, requirements), [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md) (diagnostics).

## Acceptance criteria

- [ ] The owner's decision is recorded as a dated amendment to ADR 0009 decision 9 (and ADR 0010 if nodes may skip advertising); `python3 tools/check_adrs.py` passes.
- [ ] Ticket 33's `p10lite` pairs are replaced or kept to match the decision, and `PROJECT.md`'s Android radio limitation agrees.
- [ ] If central-only nodes are accepted, a follow-up AFK ticket with behavior tests and a device proof is added to the spine; otherwise none.

## Proof commands

```sh
python3 tools/check_adrs.py
npx --yes markdownlint-cli2@0.24.1
lychee --offline .
```

## Docs to update

- ADR 0009 (and ADR 0010 if affected): amendment.
- Ticket 33 device table; `PROJECT.md` limitations.

## Devices needed

None for the decision; `p10lite` and `pixel6` if the owner wants the experiment repeated.
