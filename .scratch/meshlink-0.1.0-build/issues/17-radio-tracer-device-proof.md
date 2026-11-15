# Prove the radio tracer on real phone pairs

Status: done
Type: HITL
Blocked by: 16

## Behavior

The M1 scenario, two `Unsecured` nodes that discover each other and exchange one direct message, passes over GATT on real phones for Android↔Android, Android↔iOS and iOS↔iOS, with each phone in both the central and the peripheral role. A T9 evidence record in `docs/evidence/` holds the results. This completes milestone M2. HITL because the owner keeps the lab phones powered, unlocked, trusted and signed, and fixes physical faults.

## Spec and ADR references

- [Spine](../spec.md) M2 exit criterion.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 7 (device lab, T9 record per run), decision 8 (platform pairings, both roles, oldest and newest OS on each side) and the 2026-11-13 [amendments](../../../docs/adr/0017-testing-architecture.md#amendments) (release phones by alias, pass criteria, owner fixes physical faults).
- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decisions 4 (connect retries) and 9 (support tiers).
- [Lessons, device lab](../../../docs/agents/lessons.md#device-lab): status 133/147, never send PHY commands to `p10lite`, team B signing for iOS 15/16, re-trust after a new profile.
- [Constitution](../../../CONSTITUTION.md) T9.

## Device pairs

Minimal pairs from the release set, chosen so that together they cover the oldest and newest OS on each side and both install paths:

| Pairing | Phones | Why |
|---|---|---|
| Android↔Android | `minote3` (9) ↔ `pixel6` (17), substituting `p10lite` (8.0) | Oldest advertising Android and newest; see Notes |
| Android↔iOS | `xcover4` (9, `armeabi-v7a`) ↔ `ip12mini` (27) | The only 32-bit ABI against the newest iOS |
| iOS↔iOS | `se2016` (15.8) ↔ `ip12mini` (27) | Oldest and newest iOS; libimobiledevice and `devicectl` paths |

## Acceptance criteria

- [x] For each pair, `lab.py run --scenario direct-message` runs twice, swapping the `role` argument, so each phone is central once and peripheral once. Each run passes the ADR 0017 amendment's criteria: each message delivered within its lifetime, exactly once and intact (payload hash matches); no crash or ANR.
- [x] Between runs one app is relaunched; it starts with the same NodeId (ticket 15's stored identity).
- [x] Connect time and message latency are recorded per run as the first baseline; no threshold applies yet.
- [x] Any miss is root-caused in the record. A miss caused by first-connect status 133 or 147 is raised with the owner: ADR 0009 decision 4 retries are in ticket 29, after this ticket in the spine, so the owner decides whether to move them into ticket 13. Lab faults go to ticket 61.
- [x] `python3 tools/lab/lab.py evidence` produces `docs/evidence/<date>-radio-tracer/README.md` with every T9 field, the roles observed, pseudonymized addresses and the raw result files; `python3 tools/check_private.py` passes.
- [x] `PROJECT.md` and the evidence index agree; docs checks pass.

## Proof commands

```sh
python3 tools/lab/lab.py run --scenario direct-message --devices minote3,pixel6 --profile unsecured
python3 tools/lab/lab.py run --scenario direct-message --devices xcover4,ip12mini --profile unsecured
python3 tools/lab/lab.py run --scenario direct-message --devices se2016,ip12mini --profile unsecured
python3 tools/lab/lab.py evidence --run build/lab/<run>
python3 tools/check_private.py
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
```

## Docs to update

- `docs/evidence/<date>-radio-tracer/README.md`: new T9 record.
- `PROJECT.md`: proof-matrix rows for the radio adapters gain the device evidence link.
- `docs/agents/lessons.md`: new root causes found during the runs.

## Devices needed

`minote3` (substituting `p10lite`), `pixel6` and `xcover4` on the remote adb server; `ip12mini` through `xcrun devicectl`; `se2016` through libimobiledevice with team B bundle `ch.trancee.lab.host.b`. Profiles re-signed within 7 days and trusted on each iPhone before the runs.

## Notes

- **Record:** [`docs/evidence/2026-11-15-radio-tracer/`](../../../docs/evidence/2026-11-15-radio-tracer/README.md), six passing runs at `882b733`: `rt-aa-5`, `rt-aa-6` (`minote3`↔`pixel6`), `rt-ai-1`, `rt-ai-2` (`xcover4`↔`ip12mini`), `rt-ii-2`, `rt-ii-3` (`se2016`↔`ip12mini`). Every app relaunches each run, and every phone kept its NodeId. No first-connect status 133 or 147 occurred, so the ADR 0009 retries stay in ticket 29.
- **`p10lite` substituted (owner decision, 2026-11-15):** it reports `isMultipleAdvertisementSupported` false, so MeshLink is `Unsupported` there. An experiment build without the check worked as central but its advertisement never reached the air, so the check stays. The Mi Note 3 (Android 9) took its place; [ticket 66](66-central-only-nodes.md) decides on central-only nodes and blocks ticket 33's `p10lite` pairs.
- **Correction (2026-11-15, after merge):** the P10 lite is not central-only. The multiple-advertisement flag only means fewer than 5 instances; MeshLink now gates on a null advertiser (`5ccbea1`). Its MESH advertisement never reached the air because Android 8.0 and 8.1 truncate advertised 32-bit UUIDs (AOSP `BluetoothUuid` mask, fixed in 9). Run `rt-aa-8` (`p10lite` central) passed and joined the record; `rt-aa-7` (peripheral) failed. Ticket 66 is rescoped and no longer blocks ticket 33.
- **GATT service UUID (owner decision, 2026-11-15):** the Mi Note 3 as peripheral sent the Base-UUID MESH service as a 32-bit UUID, which ATT forbids, so the Pixel 6 found no service (`rt-aa-3`, `rt-aa-4`). The service is now served under `4D455348-4D4C-4E4B-8000-000000000000`; advertising keeps the 32-bit MESH UUID. ADR 0010 amendment 2026-11-15, D-13, spec and lessons updated; red tests first on both platforms.
- **Lab tool:** `lab.py` grants permissions by API level (location below Android 12), keeps several runs in one record, and records the full run command, naming Xcode only for runs with an iPhone.
- **iOS 15 build:** team B's Xcode account had no session, so the grant runner couldn't be provisioned; the `se2016` app target was built from the same commit with the cached profile and both iPhone runs used `--no-build` (record, Notes).
- **Evidence index:** the repository has no separate evidence index; `PROJECT.md` (summary, proof matrix and limitations) links the record.
