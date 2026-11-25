# Lab record `central-only`: `direct-message`

- **Date:** 2026-11-25T22:14:42+01:00
- **Commit:** `4975296d84812b58e3ff0de68b1cf594e50b57a1`, `6612ff5b35d72f47d1250eeff54d293137283a7a`
- **Platform pairs:** android-android
- **Runs:** 3
- **Result:** pass

Observed on real phones by the lab apps in `labapp/`. Bluetooth addresses are keyed pseudonyms that keep
the 2 address-type bits; the key stays on the lab Mac.

## Devices

| Alias | Model | OS |
|---|---|---|
| `p10lite` | WAS-LX1A | Android 8.0.0 (API 26) |
| `pixel6` | Pixel 6 | Android 17 (API 37) |

## Runs

Times are in ms, each on the phone's own monotonic clock: connect is app start to first link, send to
`Sent` is on the sender, link to received is on the receiver and includes the peer's own link and send.
The phones' clocks are not synchronized, so one-way latency is not measured.

| Run | Phone | Role | Result | Connect | Send to `Sent` | Link to received |
|---|---|---|---|---|---|---|
| `cos-aa-1` | `p10lite` | central | pass | 2458 | 23 | 92 |
| `cos-aa-1` | `pixel6` | peripheral | pass | 1912 | 91 | 198 |
| `cos-aa-2` | `p10lite` | central | pass | 5248 | 20 | 95 |
| `cos-aa-2` | `pixel6` | peripheral | pass | 4919 | 96 | 147 |
| `cos-ctl-6` | `p10lite` | central | pass | 4733 | 18 | 374 |
| `cos-ctl-6` | `pixel6` | peripheral | pass | 4226 | 188 | 535 |

## Identity across relaunches

Each run relaunches both apps. A phone's NodeId is the one its partner recorded on linking.

| Phone | NodeIds seen | Across runs |
|---|---|---|
| `p10lite` | `cc1195b2ac49c1b5` | same |
| `pixel6` | `4568b8b286e5f544` | same |

## Run details

### `cos-aa-1`

- **Date:** 2026-11-25T22:14:42+01:00
- **Commit:** `6612ff5b35d72f47d1250eeff54d293137283a7a`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices p10lite,pixel6 --profile unsecured --roles central,peripheral --run-id cos-aa-1`
- **Profile:** `unsecured`, 60 s per phone

Launch arguments:

- `p10lite`: `scenario=direct-message role=central peer=pixel6 mesh=lab-cos-aa-1 profile=unsecured run=cos-aa-1 duration=60`
- `pixel6`: `scenario=direct-message role=peripheral peer=p10lite mesh=lab-cos-aa-1 profile=unsecured run=cos-aa-1 duration=60`

```text
  p10lite    pass       role=central
  pixel6     pass       role=peripheral
result: pass
```

Raw results:

- [`raw/cos-aa-1/p10lite.jsonl`](raw/cos-aa-1/p10lite.jsonl)
- [`raw/cos-aa-1/pixel6.jsonl`](raw/cos-aa-1/pixel6.jsonl)
- [`raw/cos-aa-1/run.json`](raw/cos-aa-1/run.json)

### `cos-aa-2`

- **Date:** 2026-11-25T22:24:31+01:00
- **Commit:** `6612ff5b35d72f47d1250eeff54d293137283a7a`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices p10lite,pixel6 --profile unsecured --roles any,peripheral --run-id cos-aa-2`
- **Profile:** `unsecured`, 40 s per phone

Launch arguments:

- `p10lite`: `scenario=direct-message role=any peer=pixel6 mesh=lab-cos-aa-2 profile=unsecured run=cos-aa-2 duration=40`
- `pixel6`: `scenario=direct-message role=peripheral peer=p10lite mesh=lab-cos-aa-2 profile=unsecured run=cos-aa-2 duration=40`

```text
  p10lite    pass       role=central
  pixel6     pass       role=peripheral
result: pass
```

Raw results:

- [`raw/cos-aa-2/p10lite.jsonl`](raw/cos-aa-2/p10lite.jsonl)
- [`raw/cos-aa-2/pixel6.jsonl`](raw/cos-aa-2/pixel6.jsonl)
- [`raw/cos-aa-2/run.json`](raw/cos-aa-2/run.json)

### `cos-ctl-6`

- **Date:** 2026-11-25T22:23:17+01:00
- **Commit:** `4975296d84812b58e3ff0de68b1cf594e50b57a1`
- **Method:** `python3 tools/lab/lab.py run --scenario direct-message --devices p10lite,pixel6 --profile unsecured --roles any,peripheral --run-id cos-ctl-6 --no-build`
- **Profile:** `unsecured`, 40 s per phone

Launch arguments:

- `p10lite`: `scenario=direct-message role=any peer=pixel6 mesh=lab-cos-ctl-6 profile=unsecured run=cos-ctl-6 duration=40`
- `pixel6`: `scenario=direct-message role=peripheral peer=p10lite mesh=lab-cos-ctl-6 profile=unsecured run=cos-ctl-6 duration=40`

```text
  p10lite    pass       role=central
  pixel6     pass       role=peripheral
result: pass
```

Raw results:

- [`raw/cos-ctl-6/p10lite.jsonl`](raw/cos-ctl-6/p10lite.jsonl)
- [`raw/cos-ctl-6/pixel6.jsonl`](raw/cos-ctl-6/pixel6.jsonl)
- [`raw/cos-ctl-6/run.json`](raw/cos-ctl-6/run.json)

## Notes

Ticket 66 of the 0.1.0 build: an Android 8.0 phone (API 26, whose advertisements truncate the 32-bit MESH UUID) runs as a central-only node and still exchanges the M1 message.

**Public signal.** On `6612ff5` the P10 lite reported `Running(limitations=[CentralOnly(reason=LegacyUuidBug)])` about 120 ms after start in both runs (`raw/cos-aa-*/p10lite.jsonl`), and linked as central.

**Why `cos-aa-2` and the control use role `any`.** With `--roles central`, as in `cos-aa-1` and the ticket's command, the link-role hook alone already stops a node advertising, on the old commit too. Only an unrestricted P10 lite shows that the central-only limitation, not the hook, keeps it off the air. `cos-ctl-6` runs the same command on `4975296`, the commit before ticket 66.

**Radio trace.** The trace is the HCI summary Android 8 keeps in memory, the `BTSNOOP_LOG_SUMMARY` section of `adb shell dumpsys bluetooth_manager` (base64 of a 9-byte header and a zlib stream of version-2 btsnooz records). It was taken about 20 s after each launch and decoded on the lab Mac; the dumps hold Bluetooth addresses, so they stay off the repository. Outgoing HCI commands found on the P10 lite:

| Run | Commit | Advertising commands | Scanning and connecting |
|---|---|---|---|
| `cos-ctl-6` | `4975296` | `LE Set Advertising Parameters`, `LE Set Advertising Data` with AD `05 05 48 53 00 00` (the MESH UUID truncated to `00005348`), `LE Set Scan Response`, `LE Set Advertise Enable 01` | `LE Set Scan Enable`, `LE Create Connection` |
| `cos-aa-2` | `6612ff5` | none in the 53 s after the control's `LE Set Advertise Enable 00` | 16 `LE Set Scan Enable`, 3 `LE Create Connection`, vendor `0xFD57` scan filters on `4853454d` (MESH) |

No legacy (`0x2006`–`0x200A`), extended (`0x2036`–`0x2039`) or vendor multi-advertising (`0xFD54`) command appears in the `cos-aa-2` window.

**Traces that didn't work.** The P10 lite's logcat shows only `getBluetoothLeAdvertiser` in both builds, and `dumpsys`' `mAdvertisingServiceUuids` covers only the legacy `startAdvertising` API. A Mac CoreBluetooth scan never received the P10 lite, even in the control.
