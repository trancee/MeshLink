# Drive the lab apps from `tools/lab/`

Status: ready-for-agent
Type: AFK
Blocked by: 13, 14, 15

## Behavior

An agent runs one device scenario with `python3 tools/lab/lab.py run --scenario <name> --devices <alias>,<alias>`. The script builds the lab apps, installs them on the named lab phones, launches each with launch arguments, waits, pulls the result files into `build/lab/<run>/` and prints a pass or fail summary. `lab.py evidence --run <dir>` turns that run into a T9 evidence record under `docs/evidence/` with pseudonymized Bluetooth addresses. The first scenario is `direct-message`, the M1 scenario on real radios.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 7 (`tools/lab/` modeled on the bake-off's `bakeoff.py`, `matrix.py` and `summarize.py`; each run writes a T9 record) and the 2026-11-13 amendment (lab app in `labapp/`, Compose on Android, thin SwiftUI on iOS, `ch.trancee.lab.host`, launch arguments and result files only; agent runs from a dedicated worktree; addresses pseudonymized). See the [amendments](../../../docs/adr/0017-testing-architecture.md#amendments).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Device-lab signing" (shared lab bundle ID per free team, re-sign within 7 days).
- [Lessons, device lab](../../../docs/agents/lessons.md#device-lab): verified adb, `xcrun devicectl`, `idevicedebug` and `afcclient` commands; team B bundle `ch.trancee.lab.host.b` for iOS 15/16 phones; local untracked alias-to-ID mapping; keyed address pseudonyms; pair every `startScan` with `stopScan`.
- [Constitution](../../../CONSTITUTION.md) T9 (record fields), S1.

## Acceptance criteria

- [ ] `labapp/android/` is a Compose app and `labapp/ios/` a thin SwiftUI shell. Both use the local `:meshlink` build, are never published, and stay out of Kover, ABI and Dokka scope. Each reads launch arguments (`scenario`, `role`, `peer`, `mesh`, `profile`, `run`, `duration`), runs the scenario through the public API only, and writes one JSON Lines result file per run to its app container.
- [ ] The `direct-message` scenario: both nodes start `Unsecured` on the same mesh ID, each sends one direct message to the other once linked, and records start, link, send, status changes, receipt of the peer's payload (with a hash), and errors, each with a monotonic timestamp and the role (central or peripheral) observed. `profile` takes the lab profile names `unsecured`, `classical` and `post-quantum` (ADR 0007 decision 5 naming); `lab.py` accepts all three, and the lab apps fail fast with a clear message for `classical` and `post-quantum` until tickets 24 and 25 add those profiles.
- [ ] `tools/lab/lab.py run` resolves aliases through a local, untracked mapping file and fails with a clear message when an alias is missing. It uses adb through `ADB_SERVER_SOCKET` for Android, `xcrun devicectl` for iOS 17+, and `ideviceinstaller`, `idevicedebug --detach` and `afcclient … get -f` for iOS 15/16 with the team B bundle. It refuses to run outside a dedicated worktree or on a dirty tree, and records the commit.
- [ ] `lab.py evidence` writes `docs/evidence/<date>-<name>/README.md` with every T9 field (devices with OS versions, platform pairs, commit, method, raw results, date) and the raw files, gzipped where large. Addresses become lowercase HMAC-SHA256 pseudonyms keeping the 2 address-type bits, keyed by a local secret that is never committed.
- [ ] Only lab aliases appear in committed files; `python3 tools/check_private.py` passes on the result.
- [ ] `lab.py` has standard-library `unittest` tests under `tools/lab/tests/` for alias resolution, argument building, the three `--profile` values and pseudonymization. They run with `python3 -m unittest discover -s tools/lab/tests`, which this ticket adds to the spine's proof-command table, and in CI beside the docs checks.
- [ ] One smoke run of `direct-message` on `pixel6,ip15` completes and its summary is attached to the PR; full proof is ticket 17. Faults found go to ticket 61.

## Proof commands

```sh
python3 -m unittest discover -s tools/lab/tests
python3 tools/lab/lab.py run --scenario direct-message --devices pixel6,ip15 --profile unsecured
python3 tools/lab/lab.py evidence --run build/lab/<run>
./gradlew check
python3 tools/check_private.py
npx --yes markdownlint-cli2@0.24.1
```

## Docs to update

- `PROJECT.md`: layout (`labapp/`, `tools/lab/`), the two lab commands, the lab unit-test command, the `--profile` values and their prerequisites.
- [Spine](../spec.md#proof-commands): the `python3 -m unittest discover -s tools/lab/tests` row and the `--profile` values `unsecured`, `classical`, `post-quantum`.
- `docs/agents/lessons.md`: "Device lab" code map moves from the bake-off prototype to `tools/lab/`; new root causes, if any.
- `docs/adr/0017-testing-architecture.md`: the amendment names the profile `none`; ADR 0007 decision 5 renamed it `unsecured`. Fix it with a dated amendment.

## Devices needed

`pixel6` (Android 17, remote adb server) and `ip15` (iOS 17.6, `xcrun devicectl`) for the smoke run; `se2016` to check the libimobiledevice install and launch path.
