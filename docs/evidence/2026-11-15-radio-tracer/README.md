# Lab record `radio-tracer`: `direct-message`

- **Date:** 2026-11-15T18:45:36+01:00
- **Commit:** `882b7331a55e32e614136be9e36843927aa67889`
- **Platform pairs:** android-android and android-ios and ios-ios
- **Runs:** 6
- **Result:** pass

Observed on real phones by the lab apps in `labapp/`. Bluetooth addresses are keyed pseudonyms that keep
the 2 address-type bits; the key stays on the lab Mac.

## Devices

| Alias | Model | OS |
|---|---|---|
| `minote3` | Mi Note 3 | Android 9 (API 28) |
| `pixel6` | Pixel 6 | Android 17 (API 37) |
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
python3 tools/lab/lab.py run --scenario direct-message --devices xcover4,ip12mini --roles central,peripheral --run-id rt-ai-1
python3 tools/lab/lab.py run --scenario direct-message --devices xcover4,ip12mini --roles peripheral,central --run-id rt-ai-2
python3 tools/lab/lab.py run --scenario direct-message --devices se2016,ip12mini --roles central,peripheral --run-id rt-ii-2 --no-build
python3 tools/lab/lab.py run --scenario direct-message --devices se2016,ip12mini --roles peripheral,central --run-id rt-ii-3 --no-build
```

**Substitute phone.** The ticket's Android pair was `p10lite` (Android 8.0) ↔ `pixel6`. The P10 lite reports
`isMultipleAdvertisementSupported` false, so MeshLink starts as `Unsupported` there (run `rt-aa-1`). An
experiment build that skipped the check linked as central, but as peripheral no phone linked and a Mac
scanner never saw its advertisement, so the check stays. The owner substituted the Mi Note 3 (Android 9),
the oldest Android in the lab that advertises; whether phones like the P10 lite may join as central-only
nodes is a separate decision.

**Misses, root-caused.** Runs on earlier commits, not part of this record:

| Run | Phones | Result | Cause |
|---|---|---|---|
| `rt-aa-1` | `p10lite` ↔ `pixel6` | fail | P10 lite `Unsupported`, as above |
| `rt-aa-2` | `minote3` central, `pixel6` peripheral | pass | Superseded by `rt-aa-5` after the fix below |
| `rt-aa-3`, `rt-aa-4` | `minote3` peripheral, `pixel6` central | fail, no link | The Mi Note 3's stack sent the Base-UUID MESH service as a 32-bit UUID, which ATT forbids; the Pixel 6 logged `DO NOT ALLOW 32 BITS UUID IN ATT PDU` and closed with `CAPABILITY_MISSING`. Fixed in `882b733` by serving the service under a UUID off the Base UUID ([ADR 0010](../../adr/0010-discovery-and-advertising.md#amendments) amendment 2026-11-15) |

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
