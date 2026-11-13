# Fix the lab faults

Status: ready-for-human
Type: HITL
Blocked by: 16

## Behavior

Every lab phone that the release runs need is reachable, has Bluetooth on, and answers `tools/lab/` within its timeouts. The three faults known since the 0.1.0 map are fixed or written off with a reason: Bluetooth off on 5 remote phones, the nova 9 adb timeout, and the Pixel 4a address. The owner fixes physical faults; an agent finds them, verifies each fix, and records what it learned.

## Spec and ADR references

- [0.1.0 map ticket 06](../../meshlink-0.1.0/issues/06-release-device-evidence.md#answer): the lab-fault ticket and its three faults.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md#amendments), 2026-11-13 amendment: an agent runs `tools/lab/` from a dedicated worktree; the owner fixes physical lab faults.
- [Device-lab lessons](../../../docs/agents/lessons.md#device-lab): remote adb through the `adb-server` alias, aliases resolved through a local untracked mapping, no serials or addresses in committed files, long device jobs in a dedicated worktree.
- The inventory lives outside the repository in `../android-devices/devices.md`. Nothing from it is copied into this repository except aliases.

## Acceptance criteria

Checklist. The agent runs each check, the owner fixes what it finds, and the agent runs the check again:

- [ ] **Inventory:** the agent lists every remote Android phone with `ADB_SERVER_SOCKET=tcp:adb-server:5037 adb devices -l`, maps transports to aliases locally, and names the 5 phones with Bluetooth off by alias only.
- [ ] **Bluetooth off on 5 phones:** for each, the owner finds why it is off (user toggle, airplane mode, a stack crash, a policy) and fixes it. Verify with `adb -s <transport> shell settings get global bluetooth_on` printing `1`, and with the discovery scenario passing between that phone and a known good one.
- [ ] **nova 9 adb timeout:** the owner restores a working transport. Verify with `timeout 15 adb -s <transport> shell getprop ro.build.version.release` returning within 15 s on three tries in a row.
- [ ] **Pixel 4a address:** the owner fixes the address the lab resolves for the phone, in the local mapping and the out-of-repository inventory. Verify that `adb devices -l` lists it as `device` and that the alias resolves to that transport.
- [ ] **The 8 release phones** (`p10lite`, `xcover4`, `nothing2`, `pixel6`, `se2016`, `ipx`, `ip15`, `ip12mini`): each installs and launches the lab app, through adb, `xcrun devicectl` (iOS 17 and later) or libimobiledevice (iOS 15 and 16), and the iPhone apps are re-signed within their 7-day free-team expiry.
- [ ] **Fleet count:** at least 15 phones pass the discovery scenario, so ticket 63 can run. The list is recorded by alias.
- [ ] A fault that cannot be fixed is recorded with its cause, and the phone is left out of the runs. If that leaves fewer than 15 reachable phones or breaks the 8-phone set, the owner decides before tickets 62 and 63 start.
- [ ] New root causes and verified commands go into the "Device lab" section of `docs/agents/lessons.md`. `python3 tools/check_private.py` passes on the change.

## Proof commands

```sh
python3 tools/lab/lab.py run --scenario <discovery-scenario> --devices <fixed-alias>,<known-good-alias>
python3 tools/lab/lab.py run --scenario <discovery-scenario> --devices p10lite,xcover4,nothing2,pixel6,se2016,ipx,ip15,ip12mini
python3 tools/check_private.py
npx --yes markdownlint-cli2@0.24.1 "docs/**/*.md"
typos
```

`<discovery-scenario>` is the discovery scenario name ticket 16 defines. The per-fault `adb` checks above are diagnostics, not gates.

## Docs to update

- [`docs/agents/lessons.md`](../../../docs/agents/lessons.md#device-lab): root causes and commands found.
- `../android-devices/devices.md` (outside the repository): the owner updates transports and states.

## Devices needed

The 5 phones with Bluetooth off, the nova 9 (`nova9`), the Pixel 4a (`pixel4a`), the 8 release phones, and enough other lab phones for 15. Hosts: the remote adb server (`adb-server`), the Mac with `xcrun devicectl` and libimobiledevice. The owner must be at the lab.
