# iOS overflow area: format, bit hash, and Android detection

- **Date:** 2026-11-23
- **Builds on:** [`2026-11-02-discovery-advertising.md`](2026-11-02-discovery-advertising.md), cited below as the "discovery note". Its §3.2 ("Q2: iOS background advertising and the overflow area") covers Apple's budget rules and the first lab runs. This note does not repeat them. §2.1 lists where this note confirms or contradicts §3.2.
- **Lab evidence:** [`docs/evidence/2026-11-discovery/README.md`](../evidence/2026-11-discovery/README.md) (cited as "evidence README") and its `raw/` logs. Also cited: ADR 0010 and `docs/agents/lessons.md`.
- **Every URL below was accessed on 2026-11-23.**
- **Outcome:** recommendations 2 and 3 (§7) shipped in [#138](https://github.com/trancee/MeshLink/pull/138) and are recorded in the [ADR 0010](../adr/0010-discovery-and-advertising.md) amendment of 2026-11-23. The iOS scrub in recommendation 5 stays rejected (ADR 0010, Alternatives). [Finding iPhones in the background](../explanation/background-iphone-discovery.md) explains the current behavior; this note is background, not the source of truth.

## Evidence labels

| Label | Meaning |
|---|---|
| **[DOC]** | Primary vendor or specification source: Apple developer documentation, Android reference or source.android.com pages, Bluetooth Core spec. |
| **[AOSP]** | Read directly from AOSP source at the tag named in the citation. This is implementation, not API contract. OEM builds and flags may differ. |
| **[CODE]** | Read directly from a third-party open-source repository at the commit named. |
| **[LAB]** | MeshLink's own device evidence (evidence README, raw logs), re-analyzed for this note. |
| **[CALC]** | Computed here, by a script that was run. The script is reproduced in §4.2.4. |
| **[INFERRED]** | Fits all available data, but no source states it and only a few data points support it. A device test that would settle it is listed in §8. |
| **[SEC]** | Secondary or third-party report (blog, paper). Not independently verified unless noted. |
| **[UNVERIFIED]** | Not established by any source found. |

## 1. Question

How does Apple's CoreBluetooth "overflow area" for background advertising work? How can a non-Apple scanner, Android in particular, detect and decode it? What prior work can MeshLink reuse? Sub-questions:

1. Apple's documented behavior.
2. The on-air format, and the service-UUID → bit hash, including how 16/32-bit UUIDs are handled. Does our MESH UUID `0x4D455348` map to bit `0x08` of the last byte?
3. Collisions and false positives.
4. Android: `ScanRecord` handling of duplicate manufacturer IDs, `ScanFilter` matching, ADV + SCAN_RSP merging, and hardware (APCF) offload. Why does a filter on `[0x10]` match while `[0x01]` does not? Which filter strategy works with the screen off?
5. iOS → iOS discovery through the overflow area.
6. iOS version changes (iOS 13–18 and later).

## 2. Summary / answer

1. **Apple documents only the behavior, not the format [DOC].** In the background, "all service UUIDs are in the overflow area", and they are "discoverable only by an iOS device explicitly scanning for them". The receive-side key is "best effort".
2. **Format [CODE, LAB]:** one AD structure `14 ff 4c 00 01 <16-byte bitmap>`. That is manufacturer-specific data, company `0x004C`, Apple TLV type `0x01` with **no TLV length byte**, then a 128-bit bitmap with one bit per advertised service UUID.
   - Every capture so far had the overflow as the first Apple TLV of its own AD structure. Apple data is a TLV list, so a scanner should still walk the TLVs to type `0x01` rather than expect it at a fixed offset (§4.2.1, §7 recommendation 2); MeshLink's scanner does.
3. **The hash, derived and validated here (new; not found published) [CALC, LAB]:**
   - `bit = CRC-8(poly 0x09, init 0x00, no reflection, xorout 0x00)` over the 16 UUID bytes in big-endian (textual) order, then `& 0x7F`.
   - The bit sits on air at byte `bit >> 3`, mask `1 << (bit & 7)`, so bits are LSB-first within each byte.
   - It reproduces all 128 entries of David G. Young's empirical table, the Apple Watch UUID he observed, linky's iOS 27 bit, and MeshLink's own rotating UUIDs on iOS 15.8–27.
4. **Bit `0x08` of the last byte is bit 123, and it is MESH [LAB].** An iOS scanner filtering on MESH matched it (T3).
   - Under the CRC, 123 is produced by **zero-padding** the 4-byte UUID to 16 bytes (`4D455348-0000-0000-0000-000000000000`). Expanding to the Bluetooth Base UUID gives bit 65 instead **[CALC]**.
   - Settled on device (§4.2.7) **[LAB]**: 32-bit UUIDs are zero-padded on the right; 16-bit UUIDs are expanded with the Bluetooth Base UUID.
5. **Collisions [CALC, SEC]:** all apps on a phone share one 128-bit bitmap, so a random UUID collides with any one other UUID with probability 1/128.
   - The `0x08` seen "before our app started" is best explained by iOS's **stale scan response** [LAB]. A collision with another app's UUID is possible but not shown.
6. **Android [AOSP]:** `ScanRecord.parseFromBytes` treats duplicate company IDs differently by release.
   - Android ≤ 14: `SparseArray.put`, so the **last entry wins**.
   - Android 15: entries are **concatenated** when flag `scan_record_manufacturer_data_merge` is on.
   - Android 16: entries are **always concatenated**.
   - On the Pixel 7 Pro (Android 16), Apple data is therefore `10 05 … 01 <bitmap>`. `ScanFilter` compares a data prefix at offset 0, so `[0x10]` matches and `[0x01]` cannot. The stack re-checks every filter in software after the controller (APCF) match, which is why HCI showed the packets but the app got nothing.
7. **Working filter:** `setManufacturerData(0x004C, ByteArray(0))` (empty data), then walk the raw bytes for the overflow TLV. MeshLink HEAD already does this. Herald uses the same Apple-only filter.
   - The `[0x01]` + mask filter in the task description is an older state of `BleScanner.kt` (§5).
8. **Best reusable prior work:**
   - For the bit mapping: David G. Young's blog and `BackgroundAdvertiser`/`OverflowAreaBeaconRef` lookup table (Apache-2.0). The CRC above now replaces his table.
   - For Android scanning: Herald (Apache-2.0), with its empty-data Apple filter plus a GATT confirmation step.
   - linky's `BeaconCodec.java` is the closest Android TLV-walking decoder.

### 2.1 Relation to the discovery note §3.2

| Discovery note §3.2 statement | This note |
|---|---|
| Background iOS moves service UUIDs to the overflow area; foreground budget rules | **Confirms** (same Apple docs, §4.1). |
| The overflow carries no per-node identifier; only presence of a service can be inferred | **Confirms.** It is a bitmap; the rotating 128-bit UUID appears only as one hashed bit. |
| The hash/bit mapping is undocumented / proprietary | **Refines.** Still undocumented by Apple, but now reproducible (CRC-8/0x09, §4.2). |
| Android cannot discover a background iOS advertiser (or "not supported") | **Contradicts.** Android can: Apple manufacturer filter + bitmap bit test (§4.4, §7). Identification still needs a GATT connect. |

## 3. Sources

Apple [DOC]:

- `startAdvertising(_:)`: <https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)> (fetched via the JSON at `https://developer.apple.com/tutorials/data/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:).json`).
- `CBAdvertisementDataOverflowServiceUUIDsKey`: <https://developer.apple.com/documentation/corebluetooth/cbadvertisementdataoverflowserviceuuidskey>.
- `scanForPeripherals(withServices:options:)`: <https://developer.apple.com/documentation/corebluetooth/cbcentralmanager/scanforperipherals(withservices:options:)>.

Android [DOC]/[AOSP]:

- Bluetooth HCI requirements (APCF): <https://source.android.com/docs/core/connect/bluetooth/hci_requirements>.
- AOSP `platform/packages/modules/Bluetooth` at `android-13.0.0_r1`, `android-14.0.0_r1`, `android-15.0.0_r1` and `android-16.0.0_r1`.
  - Base: `https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/tags/<tag>/`.
  - Files:
    - `framework/java/android/bluetooth/le/ScanRecord.java`
    - `framework/java/android/bluetooth/le/ScanFilter.java`
    - `android/app/src/com/android/bluetooth/le_scan/{ScanController,ScanManager,ScanFilterQueue}.java`
    - `system/gd/hci/le_scanning_reassembler.cc`
    - `flags/gap.aconfig`
- `android-8.0.0_r1` `frameworks/base/core/java/android/bluetooth/le/ScanRecord.java` (pre-mainline baseline).

Prior work [CODE]/[SEC]:

- D. G. Young, "The Secrets of iOS Bluetooth Advertising in the Background" (2020-05-07, updated 2020-11-13): <https://davidgyoungtech.com/2020/05/07/hacking-the-overflow-area>.
- `davidgyoung/BackgroundAdvertiser` (Apache-2.0, commit `e20c3973`): `OverflowArea/OverflowAreaUtils.swift`. `davidgyoung/OverflowAreaBeaconRef` (commit `d7639823`, 2023-02-10) contains an identical copy of that file.
- `AltBeacon/android-beacon-library` (Apache-2.0, master `6fbc0c94`): `OverflowAreaBeaconTest.java`, `BeaconParser.java`, `ScanFilterUtils.java`.
- `theheraldproject/herald-for-android` (Apache-2.0, develop `7dcef0cf`, last push 2023-02-20): `ConcreteBLEReceiver.java`, `BLEAdvertParser.java`, `BLESensorConfiguration.java`.
- `linky-fit/linky` (no SPDX license detected, main `7de33ab5`): `apps/native-shell/android/app/src/main/java/fit/linky/app/BeaconCodec.java`.
- `Kuzminyo/Cubechat` (no license, main `691d2f08`): `lib/core/ble/ble_constants.dart`, `ble_scan_platform.dart`.
- `Dynamical-Systems-Laboratory/humanToHuman` (MIT, `682f5c41`): Android port of Young's table.
- furiousMAC `continuity` Wireshark dissector (GPL-2.0): `dissector/4.4.0/packet-bthci_cmd.c`.
- J. Martin et al., "Handoff All Your Privacy", PoPETs 2019(2): <https://petsymposium.org/popets/2019/popets-2019-0057.pdf>.
- G. Celosia, M. Cunche, "Discontinued Privacy", PoPETs 2020(1): <https://petsymposium.org/popets/2020/popets-2020-0003.pdf>.

## 4. Findings

### 4.1 Q1: Apple's documented behavior [DOC]

`CBPeripheralManager.startAdvertising(_:)`, quoted exactly:

> "Any service UUIDs contained in the value of the CBAdvertisementDataServiceUUIDsKey key that don't fit in the allotted space go to a special "overflow" area. These services are discoverable only by an iOS device explicitly scanning for them."
>
> "While your app is in the background, the local name isn't advertised and all service UUIDs are in the overflow area."

The same page also gives the foreground budget: 28 bytes, plus 10 bytes of scan response for the local name. The discovery note §3.1 covers that.

`CBAdvertisementDataOverflowServiceUUIDsKey`:

> "An array of UUIDs found in the overflow area of the advertisement data."
>
> "...UUIDs listed here are "best effort" and may not always be accurate."

"Best effort" is consistent with a lossy hash. The receiver reports any of *its own* filter UUIDs whose bit is set (§4.3).

`scanForPeripherals(withServices:options:)`: an app scanning in the background "must explicitly scan for one or more services by specifying them in the serviceUUIDs parameter", and "The CBCentralManager scan option has no effect while scanning in the background."

Apple documents neither the on-air format nor the hash. Nothing in these pages changed across iOS 13–18 as far as the archived docs show [UNVERIFIED for older revisions; current pages only].

### 4.2 Q2: Format and the UUID → bit hash

#### 4.2.1 Format

- **AD structure:** `14 ff 4c 00 01 b0 b1 … b15`. The length is 0x14 = 20, AD type 0xFF, company 0x004C little-endian, then Apple type `0x01` and 16 bitmap bytes [LAB, evidence README L293–315].
- **Apple TLV framing:** Apple manufacturer data is a sequence of `type, length, value` items. Martin et al. 2019 Fig. 1 shows this framing [SEC]. Type `0x01` is the exception: it has **no length byte**.
  - Herald `BLEAdvertParser.java` L145–174 handles it explicitly: "01 marks legacy service UUID encoding without length data" [CODE].
  - furiousMAC's dissector labels type 1 only "Observed on iOS" and does not decode it [CODE].
- **Placement:**
  - On iOS 16–27 the background ADV_IND carries Flags + TX power + Nearby Info (`ff 4c 00 10 05 …`), and the overflow AD is in the **SCAN_RSP**.
  - On the iPhone SE 2016 (iOS 15) the overflow AD was in the ADV itself [LAB].
  - A scanner must therefore accept the overflow in either packet, and possibly packed after another Apple TLV inside one 0xFF AD. linky's parser assumes this [CODE]; we have not seen it on air [UNVERIFIED].

#### 4.2.2 Prior work on the mapping

- **Young (2020) [SEC]:**
  - The overflow area is "0x01 followed by a 128-bit mask", one bit per service UUID, set by a "proprietary Apple hashing algorithm".
  - He built a lookup table empirically. He advertised UUIDs `00000000-0000-0000-0000-0000000000XX` one by one and recorded which bit lit up.
  - The result is the 128-entry `TableOfOverflowServiceUuidStringsByBitPosition` in `OverflowAreaUtils.swift` L17ff [CODE].
  - It also records the Apple Watch setup UUID `9AA4730F-B25C-4CC3-B821-C931559FC196` at index 69 (L16).
- **Downstream reuse of the table:** humanToHuman, PocketCareS and AltBeacon's overflow layout (`m:2-2=01,i:3-18`) [CODE]. None of them derives the function.
- **The two papers:** neither Martin et al. nor Celosia & Cunche describes the overflow hash.

#### 4.2.3 Derivation (new)

- The 128-entry table is **affine over GF(2)**: the bit of UUID `a XOR b` equals bit(a) XOR bit(b) XOR bit(0).
- Under Young's MSB-first index order, f(0) = 0, and the column for input bit *j* is `0x09·x^j mod x^8 + 0x09`, i.e. the 8-bit register of a CRC-8 with polynomial `0x09` (x⁸+x³+1). Only the low 7 bits are kept.
- A brute force over the standard CRC catalogue, MD5 and SHA prefixes found no other match.
- **Bit-order caveat:**
  - Young indexes his table so that index *i* means byte `i // 8`, mask `0x80 >> (i % 8)`.
  - On air, MeshLink's captures and linky both show the opposite order within each byte: CRC value *b* sets byte `b >> 3`, mask `1 << (b & 7)`.
  - His `overflowServiceUuidsToBytes` (L206–220, `byte | UInt8(2 << (bitNumber-1))`) agrees with the on-air order for building bytes, but his blog's printed example and table indices are mirrored. Use the on-air convention below.

#### 4.2.4 Reference implementation [CALC], verified by running it

```python
import uuid

def overflow_bit(service_uuid: str) -> int:
    """Apple overflow-area bit index (0..127) for a service UUID."""
    s = service_uuid.replace("-", "")
    if len(s) == 4:                            # 16-bit: Bluetooth Base UUID [LAB §4.2.7]
        data = bytes.fromhex("0000" + s + "00001000800000805F9B34FB")
    elif len(s) == 8:                          # 32-bit: zero-pad right [LAB §4.2.7]
        data = bytes.fromhex(s).ljust(16, b"\0")
    else:
        data = uuid.UUID(service_uuid).bytes   # big-endian, as written
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x09) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc & 0x7F

def bitmap_has(bitmap16: bytes, bit: int) -> bool:
    """bitmap16 = the 16 bytes after `ff 4c 00 01` on air."""
    return bitmap16[bit >> 3] & (1 << (bit & 7)) != 0

# Test vectors (all pass):
assert overflow_bit("69567322-4669-60DB-4D4C-303100D85BD9") == 119  # byte 14, 0x80  [LAB t1, iOS 17.6.1]
assert overflow_bit("6E4B00FF-8F3C-4D2A-9B61-2C7A5D3E9F10") == 34   # byte 4,  0x04  [LAB e-uuid3]
assert overflow_bit("00000000-0000-0000-0000-000000000017") == 47   # byte 5,  0x80  [linky, iOS 27]
assert overflow_bit("4D455348") == 123                              # byte 15, 0x08  [LAB T3, MESH]
assert overflow_bit("12345678") == 34 and overflow_bit("DEADBEEF") == 56 and overflow_bit("A1B2C3D4") == 82  # [LAB §4.2.7]
assert overflow_bit("180D") == 61 and overflow_bit("180F") == 115 and overflow_bit("FEAA") == 84             # [LAB §4.2.7]
```

#### 4.2.5 Validation

| Check | Result |
|---|---|
| Young's 128 table entries (after the in-byte mirror of §4.2.3) | 128/128 match [CALC] |
| Apple Watch UUID `9AA4730F-…` (a full-width, non-table UUID) | 66 = Young's 69 mirrored (byte 8, `0x04`) ✓ [CALC] |
| linky `BeaconCodec.java` L50–52: UUID `…0017` → byte 5, mask `0x80`, "seen on an iOS 27 iPhone" | ✓ [CODE] |
| LAB t1, iPhone 15 iOS 17.6.1: background UUID `69567322-…-5BD9` → 119 | Observed bitmap `…00008008` = bits {119, 123} exactly ✓ |
| LAB e-uuid3 UUID `6E4B00FF-…-9F10` → 34 | Observed bitmap `0000000004…` (byte 4, `0x04`) ✓ |
| Aggregate over all raw logs: does a rotating UUID's predicted bit appear within 120 s of its `adv_start`? | 48 of 78 adverts, against about 1 % for random bits; hits on iOS 15.8.8, 16.7.16, 17.6.1, 18.7.8 and 27.0.1 [CALC, LAB] |
| Distinct bits ever observed (19) | 18 explained by MeshLink UUIDs/MESH; **bit 52** (byte 6, `0x10`; seen in `d2-and`, `d3b`, `t1`) unexplained, probably another app on a test phone [UNVERIFIED] |

Misses in the aggregate are expected. iOS publishes the overflow only while the app is in the background, and stale scan responses lag (§4.3).

#### 4.2.6 Does MESH (`0x4D455348`) map to `0x08` of the last byte?

- **Yes, empirically [LAB].** In T3, background iPhones whose only service bits were MESH and a rotating UUID were found by iOS scanners filtering on `[MESH]`, and the callback listed `["4D455348"]`. The bit common to every MeshLink background bitmap is 123 = byte 15, `0x08`.
- **Under the CRC:**
  - Base-UUID expansion (`4D455348-0000-1000-8000-00805F9B34FB`) gives **65** (byte 8, `0x02`) and is ruled out.
  - Zero-padding on the right (`4D455348-0000-0000-0000-000000000000`, i.e. copying `CBUUID.data` into a zeroed 16-byte buffer) gives **123** ✓.
- **Padding rule now confirmed on device (§4.2.7) [LAB].** Originally inferred from this single data point; three more 32-bit UUIDs all match zero-padding.

#### 4.2.7 Short-UUID encoding test (2026-11-23) [LAB]

iPhone 15 (iOS 17.6.1) in the background, lab app launched with `svc=<uuid>+…`; Pixel 7 Pro unfiltered scan, bitmap read from the SCAN_RSP. Each run's bitmap contained exactly the predicted bits and no others.

| Run | Advertised UUIDs | Zero-pad prediction | Base-UUID prediction | Observed bits |
|---|---|---|---|---|
| `pad-191449` | `180D`, `12345678`, `6E4B00FF-…-9F11` (128-bit control) | 103, 34, 43 | 61, 24, 43 | **{34, 43, 61}** |
| `pad2-191809` | `180F`, `FEAA`, `DEADBEEF`, `A1B2C3D4` | 11, 4, 56, 82 | 115, 84, 2, 104 | **{56, 82, 84, 115}** |

- **16-bit UUIDs** (`180D`, `180F`, `FEAA`) use **Base-UUID expansion** (`0000xxxx-0000-1000-8000-00805F9B34FB`).
- **32-bit UUIDs** (`12345678`, `DEADBEEF`, `A1B2C3D4`, and MESH) use **zero-padding on the right**. Base-UUID expansion would have predicted none of them.
- Chance of a false fit: 7 independent bits at p ≈ 1/128 each, so negligible.
- Evidence: `files/pixel7-iphone-ad-power/padding-test/` in the session workspace (not committed).

### 4.3 Q3: Collisions and false positives

- **Shared bitmap:** one bitmap per phone, covering every app advertising in the background plus system services, with 128 positions. Young calls this the "Tragedy of the Commons" [SEC].
  - A scanner for UUID *U* matches any phone where *any* UUID with the same bit is advertised.
  - With *k* other UUIDs present, P(false bit) ≈ 1 − (127/128)^k.
- **Known occupants:**
  - Apple Watch setup UUID: bit 66 [SEC, CALC].
  - Herald `428132AF-4746-42D3-801E-4572D65BFD9B`: bit 85 (byte 10, `0x20`) [CALC].
  - OpenTrace `A6BA4286-C550-4794-A888-9467EF0B31A8`: bit 38 (byte 4, `0x40`) [CALC].
  - MESH: bit 123.
- **How prior work chooses bits:**
  - Young and linky pick UUIDs from the empirical table and avoid known system bits. linky picks `…0017` and avoids the Watch bit.
  - Cubechat leaves `iosOverflowBit = null`, waiting for a phone measurement.
  - Herald does not trust the bitmap at all. It treats every Apple device as a candidate and confirms over GATT (§4.4.5).
- **Our "0x08 before the app started" observation [LAB]:** the evidence README "Stale iOS scan response" (L310–315) and lessons.md L75 record the cause.
  - iPhones keep the scan response of their **last** app advertisement on air for at least 26 min after the app stops, across address rotations.
  - A bitmap with `0x08` on a phone whose app is terminated is the expected stale MeshLink bitmap. It is not proof of a collision.
  - A genuine collision needs another installed app whose service UUID hashes to 123. The test is to compute this for that phone's apps, or scrub first and then watch (§8, test 4).
- **Bit 52 is a live example of a foreign bit:** it appeared on lab phones without any MeshLink UUID mapping to it.

### 4.4 Q4: Android

#### 4.4.1 `ScanRecord.parseFromBytes` and duplicate company IDs [AOSP]

| Release | Code | Behavior for two `0xFF` ADs with company 0x004C |
|---|---|---|
| `android-8.0.0_r1` (frameworks/base `ScanRecord.java` L254) … `android-13.0.0_r1` (L583), `android-14.0.0_r1` (L602) | `manufacturerData.put(manufacturerId, manufacturerDataBytes)` | **Last wins** |
| `android-15.0.0_r1` L646–659 | `if (Flags.scanRecordManufacturerDataMerge())` → concatenate existing + new; else `put` | Merge if flag on, else last wins |
| `android-16.0.0_r1` L636–653 | Unconditional concatenation | **First + later, concatenated** |

- The flag is `scan_record_manufacturer_data_merge` in `flags/gap.aconfig` L154–161. Its description says that a scan record with several data entries under one manufacturer ID has their values merged; bug 331723396, `PURPOSE_BUGFIX`.
- The Bluetooth stack is a mainline module, so a module update can change this on Android 13/14 devices too [UNVERIFIED].
- `getAdvertisingDataMap()` (API 33+) is a `HashMap` filled with `put(fieldType, …)` (android-16 L552). It is last-wins per AD type, so for 0xFF it holds the last manufacturer AD, i.e. the overflow when that is in the SCAN_RSP. Its value starts with `4c 00`.
- `getBytes()` still returns the raw merged bytes, in which each AD stays separate.

#### 4.4.2 ADV + SCAN_RSP merging [AOSP]

`system/gd/hci/le_scanning_reassembler.cc` (android-16 L35–98):

- A legacy scannable ADV is cached, and the report is withheld until the matching SCAN_RSP arrives.
- The SCAN_RSP bytes are appended **after** the ADV bytes.
- `TrimAdvertisingData` (L120–140) strips zero padding.

So for a background iPhone, the record is `Flags, TX, ff 4c 00 10 05 …, ff 4c 00 01 <bitmap>`, in that order.

#### 4.4.3 `ScanFilter.matches` [AOSP]

`ScanFilter.java` android-16:

- `matches` (L439–510): the manufacturer check (L492–499) runs only when `mManufacturerData != null`. It fetches `getManufacturerSpecificData(id)` and calls `matchesPartialData` (L577–596).
- `matchesPartialData` compares `data[i] & mask[i]` against `parsed[i] & mask[i]` **from offset 0** of the stored value.
- An empty `byte[0]` data therefore matches any record with *some* 0x004C entry.
- An AD-type filter (`setAdvertisingDataTypeWithData`, public API 33, L1077) is checked at L502–509 against `getAdvertisingDataMap()`.

#### 4.4.4 Hardware offload and the software re-check [AOSP, DOC]

- **Controller filters:**
  - `ScanFilterQueue.java` L235–244 sends a manufacturer filter to the controller as APCF Manufacturer Data (sub-op 0x06); empty data is allowed.
  - L169–172 and L261 send an AD-type filter as APCF AD Type (sub-op 0x09; type `TYPE_ADVERTISING_DATA_TYPE = 8`).
  - The HCI requirements page defines both sub-ops and says "Every advertisement and related scan response will have to go through all the filters" [DOC].
- **Software re-check:** after delivery, `ScanController.java` (android-16 L430 parse, L467 `matchesFilters`, L606–621) **re-runs every filter in software** on the parsed `ScanRecord`.
- **Screen-off eligibility:**
  - `ScanManager.java` L470–494: a scan is screen-off-eligible (`requiresScreenOn == false`) if any filter has a non-empty field. A manufacturer filter with empty data counts.
  - L1799–1815: when the controller runs out of filter slots, the stack falls back to an all-pass filter plus the software check.

#### 4.4.5 Explaining the lab observation

On the Pixel 7 Pro (Android 16):

1. The controller APCF (or the all-pass fallback) let both PDUs through. HCI shows them delivered [LAB].
2. The reassembler produced one record: ADV followed by SCAN_RSP.
3. `parseFromBytes` concatenated both 0x004C values, giving `10 05 <5 B> 01 <16 B>`.
4. The software `ScanFilter` re-check compared the filter prefix at offset 0:
   - `[0x10]` matches the Nearby Info type, so about 27 results in 30 s.
   - `[0x01]` mask `[0xFF]` sees `0x10` and fails, so zero results, screen on or off.

On Android ≤ 14 (last wins), the same `[0x01]` filter would have **matched** whenever the overflow was in the SCAN_RSP, and `[0x10]` would have failed. Any filter on Apple TLV type is therefore release-dependent. Our hypothesis ("keeps only the first entry") was close: the actual behavior on Android 16 is concatenation with the first entry at offset 0.

#### 4.4.6 What prior work does on Android

- **Herald** (`ConcreteBLEReceiver.java`) [CODE]:
  - L429–432 builds `setManufacturerData(0x004C, new byte[0], new byte[0])`, i.e. Apple with empty data.
  - L708–715: `isAppleDevice` only checks that Apple data is non-null.
  - It then connects to each Apple device and discovers its service. `BLESensorConfiguration.java` L23–31 explains why.
  - It uses regexes on Apple TLV messages (`deviceFilterFeaturePatterns`, L361–367, including `^0100000000…` for an all-zero overflow bitmap) only to *exclude* devices. It never decodes bits.
- **AltBeacon** [CODE]: `ScanFilterUtils.java` L86–177 turns a layout's matching bytes into `setManufacturerData(manufacturer, typecode, mask)`. For the overflow layout that is `[0x01]`, which per §4.4.5 would fail on Android 16 when the overflow follows Nearby Info [INFERRED; untested].
- **linky** (`BeaconCodec.java` L39–143) [CODE] walks every 0xFF AD with company 0x004C and every Apple TLV, and stops at type `0x01`. This is the most robust parser found.
- **Cubechat** [CODE] uses `MsdFilter [0x01]`, the same Android 16 pitfall, with the bit unmeasured.

### 4.5 Q5: iOS → iOS

- **Young [SEC]:** a receiving iPhone sees overflow-only advertisers reliably only with **its screen on**, or during beacon ranging when not in the foreground. He wakes the screen with local notifications.
- **Lab T3 [LAB]** (evidence README L262–271), with the iPhone 15 advertiser in the background:
  - fg scanner filtering on MESH found it in **21/21** trials.
  - bg scanner found it in **13/21** (all-or-nothing within 40 s).
  - `withServices: nil` never surfaced overflow UUIDs.
  - The callback overflow key held only `["4D455348"]`: iOS reports *the scanner's* UUIDs whose bits are set, not the advertiser's real UUIDs.
- **D2 table:** background iOS scanners saw background iPhones at most once per cell.

Conclusion: iOS↔iOS works for presence but not identity, and only opportunistically when both sides are in the background.

### 4.6 Q6: iOS version changes

- **iOS 14 [SEC]:** Young's 2020-11-13 update says an app can no longer change its overflow advertisement while in the background. Restarting advertising with new UUIDs takes effect only after returning to the foreground.
  - This is consistent with MeshLink's observation that the rotating UUID's bit lags rotation [LAB, partial].
- **iOS 15.8 – 27.0 [LAB]:** same `14 ff 4c 00 01` format and the same CRC on all versions tested. iOS 27 is confirmed independently by linky.
  - Placement differs: iOS 15 (SE 2016) puts the overflow in the ADV; iOS 16+ puts it in the SCAN_RSP.
- **iOS 13 [SEC]:** Young's table was built on iOS 13. It agrees with the CRC, so the hash has been stable since at least iOS 13.
- No Apple documentation change was found [DOC].

## 5. Lab observations reconciled

| Observation | Explanation | Status |
|---|---|---|
| iPhone keeps advertising after the background task expires | System Nearby Info ADV, plus the last app scan response kept on air | Consistent with evidence README L310–315 [LAB] |
| ADV `02 01 1a / 02 0a 0c / 0a ff 4c 00 10 05 …`; SCAN_RSP `14 ff 4c 00 01 <16 B>` | Standard background layout, iOS 16+ | §4.2.1 |
| Bitmaps `…02000008`, `…00008008`, `0000000008…` | Bits {97,123}, {119,123}, {35}: MeshLink rotating UUIDs plus MESH (123) | §4.2.5 [CALC] |
| `0x08` set before our app advertised | Stale scan response (≥ 26 min); collision possible but not shown | §4.3 |
| `[0x01]` filter → 0 results; `[0x10]` → ~27/30 s | Android 16 concatenates duplicate 0x004C data; prefix compared at offset 0; software re-check | §4.4.5 [AOSP] |
| HCI delivered both packets during a filtered scan | APCF or all-pass passed them; the framework's software filter dropped them | §4.4.4 [AOSP] |

**Note on the scanner state.**

- The task description says `BleScanner.kt` uses `setManufacturerData(0x004C, [0x01], mask [0xFF])`.
- MeshLink HEAD (`c67b605`) already uses `ScanFilter.Builder().setManufacturerData(APPLE, ByteArray(0))` (`BleScanner.kt` L173), with the raw-byte check `carriesOverflowMeshBit` (L142–170) and constants at L132–134.
- This change was made on 2026-11-18 (ADR 0010 L146; lessons.md L80).
- The `[0x01]` observation is therefore the pre-fix state, and the fix matches this note's recommendation.

## 6. Reusable prior work

| Project | License | What to take | What not to take |
|---|---|---|---|
| D. G. Young blog + `BackgroundAdvertiser` / `OverflowAreaBeaconRef` (`OverflowAreaUtils.swift`) | Apache-2.0 | Format description; empirical table to cross-check the CRC; iOS screen-on caveat; Apple Watch bit | Table index order (mirrored within each byte versus on air); his iOS transmit trick (out of scope) |
| Herald for Android | Apache-2.0 | Empty-data Apple `ScanFilter` (L429–432); "every Apple device is a candidate, confirm by GATT" pattern; Apple TLV parser that handles type 0x01 without length (`BLEAdvertParser.java` L145–174) | Connect-to-all cost; project inactive since 2023 |
| linky `BeaconCodec.java` | No license detected; **do not copy code** | Design idea: walk all 0x004C ADs and TLVs; their independent iOS 27 bit data point | Code (license unclear) |
| AltBeacon `OverflowAreaBeacon` layout | Apache-2.0 | Layout definition as a reference | Its auto-generated `[0x01]` manufacturer `ScanFilter` (Android 16 pitfall) |
| humanToHuman / PocketCareS | MIT / various | Nothing beyond Young's table | — |
| furiousMAC continuity dissector | GPL-2.0 | Apple TLV type catalogue for debugging captures (tooling only) | Any code in the library (GPL) |
| Martin 2019 / Celosia 2020 | Papers | Apple TLV framing; privacy and linkability context | No overflow-hash content |

The CRC-8 in §4.2.4 is MeshLink's own derivation and needs no third-party code.

## 7. Recommendations for MeshLink's Android scanner

1. **Keep the filter as at HEAD:** `setManufacturerData(0x004C, ByteArray(0))`, together with the MESH service-UUID filter.
   - It works on Android 8–16 regardless of merge semantics.
   - It is screen-off-eligible (§4.4.4) and is what Herald uses.
   - Do **not** filter on the Apple TLV type byte.
2. **Make the parse TLV-aware.** Iterate over all AD structures in `ScanRecord.bytes`. For each `0xFF` AD with company `4c 00`, walk the Apple TLVs:
   - type `0x01` → the next 16 bytes are the bitmap, with no length byte;
   - any other type → skip `1 + 1 + len` bytes.
   - Today `isOverflowMeshBit` accepts only `ff 4c 00 01` at the start of an AD with `OVERFLOW_LENGTH = 20`. That is correct for every capture so far, but it would miss an overflow packed after another TLV [UNVERIFIED on air]. Accepting the overflow in either the ADV or the SCAN_RSP is already implicit, since the bytes are merged.
3. **Replace the magic `MESH_BIT = 0x08` with a computed position.** Use `bit = overflowBit(uuid)`, then test `bitmap[bit ushr 3] and (1 shl (bit and 7))`.
   - Keep the MESH bit pinned at 123 (byte 15, `0x08`) as a constant that a unit test checks against the lab value. The 32-bit zero-padding rule is confirmed (§4.2.7).
   - `overflowBit` must branch on UUID length: Base-UUID expansion for 16-bit, zero-padding for 32-bit, raw bytes for 128-bit.
4. **Treat the bit as a hint, never as identity.**
   - Confirm with a GATT connect and service check before admitting a peer. ADR 0010 decision 5 already requires this.
   - Rate-limit connects per address, because a collision or a stale bitmap causes repeated failed confirms.
5. **Stale-bitmap handling.**
   - Keep the iOS "scrub" step (advertise briefly with a non-MESH UUID on stop), since stale scan responses persist for at least 26 min.
   - On Android, after N failed GATT confirms, back off for a given Apple address until it rotates.
6. **UUID choice (optional).** The padding uncertainty is gone (§4.2.7), so a UUID change would only be to move off a crowded bit.
   - Pick a bit that avoids the known occupants: 66 (Watch), 85 (Herald), 38 (OpenTrace), 52 (seen in the lab).
   - Keep MESH as is; bit 123 is confirmed. Changing it is an ADR-level decision.
7. **Optional narrowing on API 33+:** add `setAdvertisingDataTypeWithData(0xFF, [4C 00 01, 15×00, 08], mask [FF FF FF, 15×00, 08])`.
   - This relies on `getAdvertisingDataMap` being last-wins, i.e. the overflow being the last 0xFF AD. That fails on iOS 15 (overflow in the ADV, before Nearby Info? [UNVERIFIED order]) and depends on controller support for APCF AD-type filters.
   - Use it only alongside, not instead of, filter 1, and only after test 2 in §8.

## 8. Device tests to settle open points

1. ~~**Short-UUID padding**~~ — done 2026-11-23 (§4.2.7): 16-bit → Base UUID, 32-bit → zero-pad.
2. **AD-type filter:** Pixel 7 Pro (Android 16) and an Android 13/14 phone with an iOS 15 SE 2016 and an iOS 17 advertiser in the background. Run filter 7 with the screen off, and record hits and HCI.
3. **TLV-packed overflow:** capture with many system Apple TLVs active (AirPods, Handoff, Watch) and check whether type `0x01` ever appears after another TLV in one AD.
4. **Collision audit:** on each lab iPhone, terminate MeshLink, scrub, wait 30 min, and log the bitmaps. Any persistent bit is a foreign occupant; identify the app behind bit 52.
5. **Merge flag on Android 15:** check `scan_record_manufacturer_data_merge` on a Pixel with Android 15, and look at an Android 13/14 phone with a recent Bluetooth mainline update.

## 9. Open questions

- **Hash stability:** does `crc & 0x7F` hold on every iOS version and device? Validated on iOS 13 (Young's table), 15.8, 16.7, 17.6, 18.7 and 27.0.
- **Bit 52:** which app or service produces it?
- **Android 15 flag:** what is the rollout state of the merge flag? Do mainline updates bring concatenation to Android 13/14 devices? [UNVERIFIED]
- **AltBeacon's `OverflowAreaBeacon` filter:** does it fail on Android 16 as predicted? [INFERRED, untested]
- **Overflow placement:** does iOS ever place the overflow anywhere other than as the first TLV of its own 0xFF AD? [UNVERIFIED]
- **Bitmap timing:** how quickly does iOS update the bitmap after a background UUID change, given the iOS 14 lock? This bounds identity-rotation linkability through the bitmap.
