# Run phones that can't advertise MESH as central-only nodes

Status: in-progress (device proof open)
Type: AFK
Blocked by: 17

## Behavior

An Android node that can't put the MESH advertisement on air runs as a central-only node: it scans and connects as central and never advertises. The owner decided this on 2026-11-15, after ticket 17. Two kinds of phone qualify:

- **No advertiser:** `getBluetoothLeAdvertiser()` returns null while Bluetooth is on, or the advertising start fails. Today the null case is `Blocked(Unsupported)` (`5ccbea1`), and a failed start ends discovery as a radio failure.
- **Android 8.0 and 8.1 (API 26–27):** AOSP's `BluetoothUuid.getServiceIdentifierFromParcelUuid` masks with `0x0000FFFF00000000L`, so an advertised 32-bit UUID loses its upper 16 bits (fixed in Android 9). The Huawei P10 lite reported a successful start, but scanners received `0x00005348` instead of `0x4D455348`. The phone can't detect this, so the API level decides. Layout E keeps the 32-bit MESH UUID (owner decision, 2026-11-15).

Ticket 17's record has the evidence: `rt-aa-8` (`p10lite` central) passed and `rt-aa-7` (`p10lite` peripheral) failed ([evidence](../../../docs/evidence/2026-11-15-radio-tracer/README.md#notes), [lessons](../../../docs/agents/lessons.md#device-lab), [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) amendment "single advertising instance").

Costs the design must cover: two central-only phones never link; peers can't find the phone, so it joins only by reaching out; routing must not expect it to accept links; the host app needs to learn why (ADR 0007 requirement or state, ADR 0020 diagnostic).

## Spec and ADR references

- [ADR 0009](../../../docs/adr/0009-ble-bearers-and-phy-policy.md) decision 9 (support tiers: Android 8 and 9 allowed, not fully supported).
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 1 and 6 (every node advertises and scans) and the 2026-11-15 amendments.
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) (`MeshState`, requirements), [ADR 0020](../../../docs/adr/0020-diagnostics-catalog.md) (diagnostics).

## Acceptance criteria

- [x] On API 26–27, and when the advertiser is null or its start fails, a running node scans and connects as central and never starts advertising; host tests went red first.
- [x] The host app can tell a central-only node from a full one through the public API or a diagnostic, as the ADR amendment defines.
- [x] Dated amendments to ADR 0009 decision 9 and ADR 0010 decisions 1 and 6 record the central-only mode; `python3 tools/check_adrs.py` passes.
- [ ] Device proof: `p10lite` (central-only) exchanges the M1 message with `pixel6`, and a radio trace shows no advertising start on the P10 lite.
- [x] `PROJECT.md`'s Android radio limitation and the CHANGELOG agree.

## Proof commands

```sh
ANDROID_HOME=~/Library/Android/sdk ./gradlew check koverVerify
python3 tools/check_adrs.py
python3 tools/lab/lab.py run --scenario direct-message --devices p10lite,pixel6 --roles central,peripheral
```

## Docs to update

- ADR 0009 and ADR 0010: amendments.
- `PROJECT.md` limitations, CHANGELOG, KDoc of the state or requirement that reports it.

## Devices needed

`p10lite` and `pixel6`.

## Outcome (2026-11-25)

- `MeshState.Running` carries `limitations`; `Limitation.CentralOnly(reason)` names `NoAdvertiser`, `AdvertisingFailed` or `LegacyUuidBug` (ADR 0007 amendment "central-only nodes"). A null advertiser is no longer `Blocked(Unsupported)`.
- Android reports `LegacyUuidBug` below API 28 and `NoAdvertiser` while Bluetooth is on without an advertiser; a node with either links only as central and never starts advertising.
- A refused advertising start, at run start, at a rotation or as `RadioEvent.AdvertisingFailed`, stops advertising for the rest of the run; the node keeps serving the capability record and scanning. A refused capability record or scan still ends discovery as a radio failure.
- Red first: reverting the behavior failed `CentralOnlyTest`, the flipped `DiscoveryTest` cases, `aLostAdvertisementIsNotRetriedAtLaterRotations` and three `AndroidRadioRequirementsTest` cases.
- Gate: detekt (`--rerun-tasks`), `./gradlew check iosSimulatorArm64Test dokkaGenerate koverVerify -x :meshlink-crypto-sodium:checkSodiumJniSymbols` and `python3 tools/check_adrs.py` pass.
- Open: device proof. `adb devices` listed no phones on 2026-11-25, so the `p10lite`/`pixel6` run and its radio trace are still to do.
