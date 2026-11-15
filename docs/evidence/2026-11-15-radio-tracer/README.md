# Lab record `radio-tracer`: `direct-message`

- **Date:** 2026-11-15T18:45:36+01:00
- **Commit:** `5ccbea16ac6b1233658315b7910a63edc5c4c7f0`, `882b7331a55e32e614136be9e36843927aa67889`
- **Platform pairs:** android-android and android-ios and ios-ios
- **Runs:** 7
- **Result:** pass

Observed on real phones by the lab apps in `labapp/`. Bluetooth addresses are keyed pseudonyms that keep
the 2 address-type bits; the key stays on the lab Mac.

## Devices

| Alias | Model | OS |
|---|---|---|
| `minote3` | Mi Note 3 | Android 9 (API 28) |
| `pixel6` | Pixel 6 | Android 17 (API 37) |
| `p10lite` | WAS-LX1A | Android 8.0.0 (API 26) |
| `xcover4` | SM-G390F | Android 9 (API 28) |
| `ip12mini` | iPhone13,1 | iOS 27.2 |
| `se2016` | iPhone8,4 | iOS 15.8.8 |

## Runs

Times are in ms, each on the phone's own monotonic clock: connect is app start to first link, send to
`Sent` is on the sender, link to received is on the receiver and includes the peer's own link and send.
The phones' clocks are not synchronized, so one-way latency is not measured.

| Run | Phone | Role | Result | Connect | Send to `Sent` | Link to received |
|---|---|---|---|---|---|---|
| `rt-aa-5` | `minote3` | central | pass | 2615 | 15 | 11 |
| `rt-aa-5` | `pixel6` | peripheral | pass | 3387 | 4 | 87 |
| `rt-aa-6` | `minote3` | peripheral | pass | 1698 | 22 | 54 |
| `rt-aa-6` | `pixel6` | central | pass | 2483 | 9 | 85 |
| `rt-aa-8` | `p10lite` | central | pass | 2200 | 14 | 12 |
| `rt-aa-8` | `pixel6` | peripheral | pass | 1782 | 7 | 146 |
| `rt-ai-1` | `ip12mini` | peripheral | pass | 7674 | 2 | 132 |
| `rt-ai-1` | `xcover4` | central | pass | 4260 | 20 | 20 |
| `rt-ai-2` | `ip12mini` | central | pass | 5802 | 0 | 119 |
| `rt-ai-2` | `xcover4` | peripheral | pass | 2145 | 46 | 36 |
| `rt-ii-2` | `ip12mini` | peripheral | pass | 1337 | 2 | 86 |
| `rt-ii-2` | `se2016` | central | pass | 1657 | 4 | 33 |
| `rt-ii-3` | `ip12mini` | central | pass | 1380 | 3 | 26 |
| `rt-ii-3` | `se2016` | peripheral | pass | 1604 | 19 | 62 |

## Identity across relaunches

Each run relaunches both apps. A phone's NodeId is the one its partner recorded on linking.

| Phone | NodeIds seen | Across runs |
|---|---|---|
| `minote3` | `97b00e76b462b464` | same |
| `pixel6` | `4568b8b286e5f544` | same |
| `p10lite` | `cc1195b2ac49c1b5` | same |
| `ip12mini` | `dd64e167f1f78232` | same |
| `xcover4` | `edde26466d025397` | same |
| `se2016` | `b763df2b3c763e22` | same |

## Notes

Ticket 17 of the 0.1.0 build: the M1 scenario over GATT, each phone central once and peripheral once.

**Commands.** These runs predate `lab.py` recording `--roles` in the method above; the launch arguments
show each phone's role. The runs were:

```sh
python3 tools/lab/lab.py run --scenario direct-message --devices minote3,pixel6 --roles central,peripheral --run-id rt-aa-5
python3 tools/lab/lab.py run --scenario direct-message --devices minote3,pixel6 --roles peripheral,central --run-id rt-aa-6
python3 tools/lab/lab.py run --scenario direct-message --devices p10lite,pixel6 --roles central,peripheral --run-id rt-aa-8
python3 tools/lab/lab.py run --scenario direct-message --devices xcover4,ip12mini --roles central,peripheral --run-id rt-ai-1
python3 tools/lab/lab.py run --scenario direct-message --devices xcover4,ip12mini --roles peripheral,central --run-id rt-ai-2
python3 tools/lab/lab.py run --scenario direct-message --devices se2016,ip12mini --roles central,peripheral --run-id rt-ii-2 --no-build
python3 tools/lab/lab.py run --scenario direct-message --devices se2016,ip12mini --roles peripheral,central --run-id rt-ii-3 --no-build
```

**P10 lite.** The ticket's Android pair was `p10lite` (Android 8.0) ↔ `pixel6`. On `882b733` MeshLink
refused to start there because the phone reports `isMultipleAdvertisementSupported` false (run `rt-aa-1`), so
the Mi Note 3 (Android 9) stood in for both roles. An earlier version of this note called the Kirin 658
central-only. That was wrong: the phone has a legacy advertiser with a single instance, and the fleetcheck
probe in `../android-devices` passed on it in both roles with a random 128-bit service UUID. Since `5ccbea1`
MeshLink starts on any phone whose advertiser is not null. On `5ccbea1`:

- **Central:** run `rt-aa-8` passed, in the record above.
- **Peripheral (run `rt-aa-7`, failed, no link):** advertising started on every variant tried (advertising
  set and legacy, with and without the payload UUID), yet no scanner saw the MESH UUID. A Mac CoreBluetooth
  dump with the probe advertising `4D455348-0000-1000-8000-00805F9B34FB` on the P10 lite received the service
  UUID `00005348`: Android 8.0 and 8.1 drop the upper 16 bits of an advertised 32-bit UUID ([AOSP 8.x `BluetoothUuid`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-8.1.0_r1/core/java/android/bluetooth/BluetoothUuid.java#227) masks with `0x0000FFFF00000000L`; Android 9 fixed it). The same probe
  with `4D455348-4D4C-4E4B-8000-000000000000`, which is off the Base UUID, was received intact, and the Nothing
  Phone (2) connected and read it. The phone cannot see the defect, since every start reports success.
  Layout E ([ADR 0010](../../adr/0010-discovery-and-advertising.md) decision 1) keeps the 32-bit UUID, as the
  owner decided; such phones are found by nobody but can still link as central.

**Misses, root-caused.** Runs on earlier commits, not part of this record:

| Run | Phones | Result | Cause |
|---|---|---|---|
| `rt-aa-1` | `p10lite` ↔ `pixel6` | fail | P10 lite `Unsupported` by the multiple-advertisement check, removed in `5ccbea1` |
| `rt-aa-2` | `minote3` central, `pixel6` peripheral | pass | Superseded by `rt-aa-5` after the fix below |
| `rt-aa-3`, `rt-aa-4` | `minote3` peripheral, `pixel6` central | fail, no link | The Mi Note 3's stack sent the Base-UUID MESH service as a 32-bit UUID, which ATT forbids; the Pixel 6 logged `DO NOT ALLOW 32 BITS UUID IN ATT PDU` and closed with `CAPABILITY_MISSING`. Fixed in `882b733` by serving the service under a UUID off the Base UUID ([ADR 0010](../../adr/0010-discovery-and-advertising.md#amendments) amendment 2026-11-15) |
| `rt-aa-7` | `p10lite` peripheral, `pixel6` central | fail, no link | The P10 lite truncates the 32-bit MESH UUID on air, as above |

No run saw a first-connect status 133 or 147.

**iOS 15 build.** The `se2016` app is signed by team B. Its Xcode account had no session, so `lab.py`'s
`build-for-testing` could not provision the grant runner. The app target alone was built from the same
commit with the cached team B profile (`xcodebuild … -scheme LabHost … build`), and both iPhone runs used
`--no-build`; the `ip12mini` app came from `rt-ai-2`'s build of `882b733`.

## Run details

### `rt-aa-5`

- **Date:** 2026-11-15T18:45:36+01:00
- **Commit:** `882b7331a55e32e614136be9e36843927aa67889`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices minote3,pixel6 --profile unsecured`
- **Profile:** `unsecured`, 60 s per phone

Launch arguments:

- `minote3`: `scenario=direct-message role=central peer=pixel6 mesh=lab-rt-aa-5 profile=unsecured run=rt-aa-5 duration=60`
- `pixel6`: `scenario=direct-message role=peripheral peer=minote3 mesh=lab-rt-aa-5 profile=unsecured run=rt-aa-5 duration=60`

```text
  minote3    pass       role=central
  pixel6     pass       role=peripheral
result: pass
```

Raw results:

- [`raw/rt-aa-5/minote3.jsonl`](raw/rt-aa-5/minote3.jsonl)
- [`raw/rt-aa-5/pixel6.jsonl`](raw/rt-aa-5/pixel6.jsonl)
- [`raw/rt-aa-5/run.json`](raw/rt-aa-5/run.json)

### `rt-aa-6`

- **Date:** 2026-11-15T18:47:07+01:00
- **Commit:** `882b7331a55e32e614136be9e36843927aa67889`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices minote3,pixel6 --profile unsecured`
- **Profile:** `unsecured`, 60 s per phone

Launch arguments:

- `minote3`: `scenario=direct-message role=peripheral peer=pixel6 mesh=lab-rt-aa-6 profile=unsecured run=rt-aa-6 duration=60`
- `pixel6`: `scenario=direct-message role=central peer=minote3 mesh=lab-rt-aa-6 profile=unsecured run=rt-aa-6 duration=60`

```text
  minote3    pass       role=peripheral
  pixel6     pass       role=central
result: pass
```

Raw results:

- [`raw/rt-aa-6/minote3.jsonl`](raw/rt-aa-6/minote3.jsonl)
- [`raw/rt-aa-6/pixel6.jsonl`](raw/rt-aa-6/pixel6.jsonl)
- [`raw/rt-aa-6/run.json`](raw/rt-aa-6/run.json)

### `rt-aa-8`

- **Date:** 2026-11-15T20:06:02+01:00
- **Commit:** `5ccbea16ac6b1233658315b7910a63edc5c4c7f0`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices p10lite,pixel6 --profile unsecured --roles central,peripheral --run-id rt-aa-8`
- **Profile:** `unsecured`, 60 s per phone

Launch arguments:

- `p10lite`: `scenario=direct-message role=central peer=pixel6 mesh=lab-rt-aa-8 profile=unsecured run=rt-aa-8 duration=60`
- `pixel6`: `scenario=direct-message role=peripheral peer=p10lite mesh=lab-rt-aa-8 profile=unsecured run=rt-aa-8 duration=60`

```text
  p10lite    pass       role=central
  pixel6     pass       role=peripheral
result: pass
```

Raw results:

- [`raw/rt-aa-8/p10lite.jsonl`](raw/rt-aa-8/p10lite.jsonl)
- [`raw/rt-aa-8/pixel6.jsonl`](raw/rt-aa-8/pixel6.jsonl)
- [`raw/rt-aa-8/run.json`](raw/rt-aa-8/run.json)

### `rt-ai-1`

- **Date:** 2026-11-15T18:50:02+01:00
- **Commit:** `882b7331a55e32e614136be9e36843927aa67889`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices xcover4,ip12mini --profile unsecured`; iOS apps built with Xcode 27.2
- **Profile:** `unsecured`, 60 s per phone

Launch arguments:

- `xcover4`: `scenario=direct-message role=central peer=ip12mini mesh=lab-rt-ai-1 profile=unsecured run=rt-ai-1 duration=60`
- `ip12mini`: `scenario=direct-message role=peripheral peer=xcover4 mesh=lab-rt-ai-1 profile=unsecured run=rt-ai-1 duration=60`

```text
  ip12mini   pass       role=peripheral
  xcover4    pass       role=central
result: pass
```

Raw results:

- [`raw/rt-ai-1/ip12mini.jsonl`](raw/rt-ai-1/ip12mini.jsonl)
- [`raw/rt-ai-1/run.json`](raw/rt-ai-1/run.json)
- [`raw/rt-ai-1/xcover4.jsonl`](raw/rt-ai-1/xcover4.jsonl)

### `rt-ai-2`

- **Date:** 2026-11-15T18:51:34+01:00
- **Commit:** `882b7331a55e32e614136be9e36843927aa67889`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices xcover4,ip12mini --profile unsecured`; iOS apps built with Xcode 27.2
- **Profile:** `unsecured`, 60 s per phone

Launch arguments:

- `xcover4`: `scenario=direct-message role=peripheral peer=ip12mini mesh=lab-rt-ai-2 profile=unsecured run=rt-ai-2 duration=60`
- `ip12mini`: `scenario=direct-message role=central peer=xcover4 mesh=lab-rt-ai-2 profile=unsecured run=rt-ai-2 duration=60`

```text
  ip12mini   pass       role=central
  xcover4    pass       role=peripheral
result: pass
```

Raw results:

- [`raw/rt-ai-2/ip12mini.jsonl`](raw/rt-ai-2/ip12mini.jsonl)
- [`raw/rt-ai-2/run.json`](raw/rt-ai-2/run.json)
- [`raw/rt-ai-2/xcover4.jsonl`](raw/rt-ai-2/xcover4.jsonl)

### `rt-ii-2`

- **Date:** 2026-11-15T18:53:51+01:00
- **Commit:** `882b7331a55e32e614136be9e36843927aa67889`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices se2016,ip12mini --profile unsecured`; iOS apps built with Xcode 27.2
- **Profile:** `unsecured`, 60 s per phone

Launch arguments:

- `se2016`: `scenario=direct-message role=central peer=ip12mini mesh=lab-rt-ii-2 profile=unsecured run=rt-ii-2 duration=60`
- `ip12mini`: `scenario=direct-message role=peripheral peer=se2016 mesh=lab-rt-ii-2 profile=unsecured run=rt-ii-2 duration=60`

```text
  ip12mini   pass       role=peripheral
  se2016     pass       role=central
result: pass
```

Raw results:

- [`raw/rt-ii-2/ip12mini.jsonl`](raw/rt-ii-2/ip12mini.jsonl)
- [`raw/rt-ii-2/run.json`](raw/rt-ii-2/run.json)
- [`raw/rt-ii-2/se2016.jsonl`](raw/rt-ii-2/se2016.jsonl)

### `rt-ii-3`

- **Date:** 2026-11-15T18:55:10+01:00
- **Commit:** `882b7331a55e32e614136be9e36843927aa67889`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices se2016,ip12mini --profile unsecured`; iOS apps built with Xcode 27.2
- **Profile:** `unsecured`, 60 s per phone

Launch arguments:

- `se2016`: `scenario=direct-message role=peripheral peer=ip12mini mesh=lab-rt-ii-3 profile=unsecured run=rt-ii-3 duration=60`
- `ip12mini`: `scenario=direct-message role=central peer=se2016 mesh=lab-rt-ii-3 profile=unsecured run=rt-ii-3 duration=60`

```text
  ip12mini   pass       role=central
  se2016     pass       role=peripheral
result: pass
```

Raw results:

- [`raw/rt-ii-3/ip12mini.jsonl`](raw/rt-ii-3/ip12mini.jsonl)
- [`raw/rt-ii-3/run.json`](raw/rt-ii-3/run.json)
- [`raw/rt-ii-3/se2016.jsonl`](raw/rt-ii-3/se2016.jsonl)
