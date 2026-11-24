# Find a background iPhone's MESH bit wherever iOS packs its overflow area

Status: done
Type: AFK

## Behavior

An Android node sights a backgrounded iPhone running MeshLink whenever that iPhone's Apple manufacturer data carries the overflow MESH bit, whether the overflow sub-field (`01` + 16-byte bitmap) opens its AD structure or follows another Apple sub-field in the same one. The scanner computes the bit from `MeshGatt.MESH` with Apple's hash instead of hard-coding `0x08`. The sighting stays a hint: the core still dials it, reads `ADVERTISEMENT` from the capability record and vets it (ADR 0010, 2026-11-18 amendment "Background iPhones"); this ticket leaves that path unchanged.

## State at hand-off (2026-11-23)

The owner's session did the research, the device tests and the implementation. What is left is landing it.

- **PR #138**, branch `fix/ios-overflow-parse`, one commit `6e56312` on `main` `f6f1866`:
  - `BleScanner.kt`: `overflowBit32`/CRC-8 derive `MESH_OVERFLOW_BIT` (123); `appleDataHasMeshBit` walks Apple sub-fields (type, length, data; type `0x01` has no length byte and a 16-byte bitmap).
  - `AndroidRadioDiscoveryTest.kt`: a packed case (`ff 4c 00 | 10 05 … | 01 <bitmap>`) in `anOverflowMeshBitAnywhereInTheAdvertisementIsSightedWithoutPayload`, which fails on `f6f1866`'s scanner; "every bit but 123" and "no overflow sub-field" cases in `appleDataWithoutTheOverflowMeshBitIsIgnored`.
  - `docs/research/2026-11-23-ios-overflow-area-android.md`: the source of truth for the format, the hash, AOSP's manufacturer-data handling, prior work and open questions.
- **Passes locally:** the Gradle proof command below, 40/40 tests, and `koverVerify` (100% branches; a lone-type-byte case was added after CI's first run).
- **CI red on docs only** ("Markdown lint, spelling and ADR headers"), all in the research note:
  - 15 markdownlint errors. MD052: adjacent evidence labels such as `[CODE][LAB]` parse as a full reference link (a single `[LAB]` is fine; write combined labels as `[CODE, LAB]`). MD032: lists without a blank line before them (L69, L74, L86, L262, L271, L290, L343). MD028: blank line inside a blockquote (L104, L112).
  - 1 `typos` hit at L254: a misspelling inside a verbatim quote of AOSP's `scan_record_manufacturer_data_merge` description. Paraphrase it or mark it as a quote the way `_typos.toml` already allows.
- The Gradle and Apple jobs were still running at hand-off; check them before assuming green.

## Outcome

- Shipped in [#138](https://github.com/trancee/MeshLink/pull/138) (`686098c`); all 16 CI jobs passed.
- `BleScanner.kt` walks every Apple sub-field of each `ff 4c 00` AD structure for type `0x01` and tests the computed MESH bit 123 (CRC-8, poly `0x09`). `AndroidRadioDiscoveryTest`: 40 of 40 pass on the branch. Against `f6f1866`'s scanner the packed case fails, giving 3 sightings instead of 4.
- ADR 0010 amendment 2026-11-23 "overflow bit anywhere" (Version 13): the walk, the hash and the Android 16 power result. Android 15 and 17 stay unmeasured.
- The research note got lint fixes only: blank lines before lists and inside blockquotes, combined labels written as `[A, B]`, `re-analyzed`, and the AOSP flag description paraphrased.
- The hand-off's "rebase merge" was corrected to squash, since ADR 0021 decision 4 rejects rebase merges.
- Device follow-ups stay in [ticket 69](69-overflow-device-questions.md).

## Spec and ADR references

- Research note §2 (summary), §4.2.7 (short-UUID hashing, settled on device), §4.4 (Android), §7 (recommendations; 2 and 3 are this ticket, 1, 4 and 5 already hold on `main`).
- [ADR 0010](../../../docs/adr/0010-discovery-and-advertising.md) decisions 5–7, Alternatives (scrub rejected), amendments 2026-11-18 "Apple filter" and "Risk: Android 17 scan limits".
- [ADR 0021](../../../docs/adr/0021-change-workflow-and-merge-gates.md): one PR per ticket.

## Settled decisions to build on

- **Hardware filter:** Apple company ID `0x004C` with empty data, as on `main`. Matching on the Apple sub-field type is release-dependent (Android ≤ 14 keeps the last entry; 15+ concatenates), research note §4.4.5.
- **Hash:** `bit = CRC-8(poly 0x09, init 0, no reflection) over the 16 UUID bytes, big-endian, & 0x7F`; on air at byte `bit >> 3`, mask `1 << (bit & 7)`. 16-bit UUIDs expand with the Bluetooth Base UUID, 32-bit UUIDs are zero-padded on the right, so MESH (`4D455348`) is bit 123 (byte 15, `0x08`). Two device runs confirmed it (§4.2.7).
- **False positives and stale bitmaps** are already handled by vetting and 10-minute stale marks (`Scanner.vet`, `Scanner.fresh`). The iOS scrub stays rejected (ADR 0010 Alternatives). MESH keeps its UUID.

## Steps

1. Set `Status: claimed`, then work in a worktree of `fix/ios-overflow-parse` (copy `local.properties` into it).
2. Fix the research note until the docs proof commands pass; content stays as is.
3. Append a 2026-11-23 amendment to ADR 0010 and bump its version so `tools/check_adrs.py` passes. It records:
   - **Apple filter (decisions 5 and 6):** the scanner walks every Apple sub-field of each `0x004C` AD structure for type `0x01`, and tests the MESH bit computed with the hash above; cite the research note.
   - **Risk "Android 17 scan limits":** the measurement in [Power evidence](#power-evidence) for Android 16, leaving Android 15 and 17 open.
4. Fill PR #138's body: link this ticket under "Ticket", list the ADR amendment under "Docs".
5. Push, wait for every CI job, and hand the PR to the owner to merge (squash merge, ADR 0021 decision 4). Then set `Status: done`.

## Acceptance criteria

- [x] Every CI job on PR #138 passes.
- [x] ADR 0010 carries the 2026-11-23 amendment, with the power result, and `tools/check_adrs.py` passes.
- [x] PR #138 links this ticket.
- [x] The packed-overflow test still fails against `f6f1866`'s `BleScanner.kt` and passes on the branch.

## Proof commands

```sh
./gradlew spotlessCheck :meshlink:detektAndroidMainSourceSet :meshlink:detektAndroidHostTestSourceSet \
  :meshlink:testAndroidHostTest --tests '*AndroidRadioDiscoveryTest*'
npx markdownlint-cli2 "**/*.md"
typos
python3 tools/check_adrs.py
python3 tools/check_private.py
gh pr checks 138
```

## Docs to update

ADR 0010 (amendment above). The research note only gets lint fixes. No public API, spec or KDoc beyond `BleScanner.kt` changes.

## Devices needed

None: the device evidence is in. Open device questions are [ticket 69](69-overflow-device-questions.md).

## Power evidence

The owner's run on 2026-11-23, Pixel 7 Pro on Android 16, screen off, an iPhone 15 (iOS 17) with MeshLink backgrounded nearby. The energy lab app (`MeshLink-energy-proto`, branch `prototype/energy`) scanned 4 s on / 4 s off; each window was 300 s after 20 s settling, with rails read from the on-device power monitor (ODPM) through Perfetto. The order was A/B/A/B:

| Apple filter | `wifi.bt` rail | CPU rails | Scan results per window |
|---|---|---|---|
| `[0x01]` mask `[0xFF]` (legacy) | 40.8 / 40.8 mW | 21.6 / 21.2 mW | 0 / 0 |
| empty data (as on `main`) | 41.0 / 40.7 mW | 23.1 / 24.1 mW | 3,221 / 3,001 |

The empty-data filter costs no radio power and about +2 mW CPU for roughly 10 callbacks per second from every nearby Apple device. The lab analyzer flags these windows "invalid" only because it expects 600 s windows. The raw traces stay outside the repository.
