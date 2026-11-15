# Lab tools

`lab.py` runs lab scenarios on real phones with the lab apps in [`labapp/`](../../labapp/) and turns a run into a T9 evidence record ([ADR 0017](../../docs/adr/0017-testing-architecture.md) decision 7 and its 2026-11-15 lab tools amendment). It uses the Python standard library only.

## Before the first run

1. Create a dedicated worktree. `lab.py run` refuses the main checkout and any uncommitted change, so each run names exactly one commit:

   ```sh
   git worktree add ../meshlink-lab <branch-or-commit>
   cd ../meshlink-lab
   ```

2. Install the host tools: Xcode, [XcodeGen](https://github.com/yonaskolb/XcodeGen), `libimobiledevice` and `ideviceinstaller` (for iOS 15 and 16), and the Android SDK platform-tools. The [dependency register](../../docs/reference/dependencies.md#lab-apps-never-published) lists the versions.
3. Create the local lab configuration folder `~/.config/meshlink-lab/` (or set `MESHLINK_LAB_CONFIG` to another folder). It holds the only mapping from lab alias to real device ID and must never be committed.

### `devices.json`

```json
{
  "adb_server": "tcp:<adb-host>:5037",
  "teams": {
    "apple-team-1": {"id": "<team id>", "bundle": "ch.trancee.lab.host"},
    "apple-team-2": {"id": "<team id>", "bundle": "ch.trancee.lab.host.b"}
  },
  "devices": {
    "pixel6": {"platform": "android", "serial": "<serial>"},
    "ip15": {"platform": "ios", "udid": "<udid>", "team": "apple-team-1", "tool": "devicectl"},
    "se2016": {"platform": "ios", "udid": "<udid>", "team": "apple-team-2", "tool": "imd"}
  }
}
```

- `adb_server` is optional; when set, `lab.py` exports it as `ADB_SERVER_SOCKET`.
- `tool` is `devicectl` for iOS 17 and later, `imd` (libimobiledevice) for iOS 15 and 16. Free personal teams can't share an App ID, so the second team signs `ch.trancee.lab.host.b`.
- `"grant_attach": true` marks an iPhone whose UI-test runner can't launch the app; `lab.py grant` launches it and the runner attaches.

### `pseudonym.key`

32 random bytes that key the Bluetooth address pseudonyms in evidence. Create it once and keep it, so pseudonyms stay comparable across records:

```sh
python3 -c "import os, sys; sys.stdout.buffer.write(os.urandom(32))" > ~/.config/meshlink-lab/pseudonym.key
chmod 600 ~/.config/meshlink-lab/pseudonym.key
```

## Commands

| Command | Does |
|---|---|
| `python3 tools/lab/lab.py run --scenario direct-message --devices pixel6,ip15 --profile unsecured` | Builds both lab apps, installs them, launches each with its launch arguments, waits for both `done` records, pulls the results into `build/lab/<run>/` with `run.json` (commit, method, devices with model and OS) and prints the summary. Exit 0 on pass, 2 on fail, 1 if it can't run. |
| `python3 tools/lab/lab.py evidence --run build/lab/<run> [--name <name>]` | Writes `docs/evidence/<date>-<name>/README.md` with every T9 field and copies the raw files, gzipped above 64 KiB, with Bluetooth addresses pseudonymized. |
| `python3 tools/lab/lab.py grant --devices ip15` | Runs the UI-test runner that accepts the iOS Bluetooth alert by exact button label. |
| `python3 tools/lab/lab.py stop --devices pixel6,ip15` | Stops the lab app. |
| `python3 -m unittest discover -s tools/lab/tests` | The lab tools' unit tests; CI runs them in the docs job. |

`grant` needs the iPhone unlocked with Settings > Developer > Enable UI Automation on. It prints the grant steps and, on failure, xcodebuild's `Testing failed:` summary. A tapped "Don't Allow" survives reinstalling; uninstall the app (`xcrun devicectl device uninstall app --device <udid> <bundle>`) to see the alert again.

`run` options: `--roles central,peripheral` restricts each phone's link role in order (default `any`), `--duration` sets the seconds each phone runs (5–3600, default 60), `--run-id` names the run, and `--no-build` reinstalls the apps already built in this worktree.

### Profiles

| `--profile` | Status |
|---|---|
| `unsecured` | Runs. |
| `classical` | The lab apps stop at once with a message naming ticket 24, which adds the classical profile. |
| `post-quantum` | The lab apps stop at once with a message naming ticket 25, which adds the post-quantum profile. |

## Scenarios

`direct-message` needs two phones. Both start `Unsecured` on the mesh `lab-<run>`, wait for their first peer, send it one message whose payload carries its own SHA-256, and listen for the peer's message until the duration ends. A phone passes when its message reached `Sent`, it received exactly one intact message and, with `--roles`, it linked in the requested role.

## Launch arguments and results

The lab apps read `key=value` launch arguments: `scenario`, `role`, `peer`, `mesh`, `profile`, `run` and `duration`. On Android they arrive as one comma-separated `args` string extra; on iOS as process arguments. Each run appends JSON Lines to `results/<run>.jsonl` in the app container (`files/` on Android, `Documents/` on iOS). Every record starts with `t_ms`, milliseconds since the run started on the monotonic clock, and `event`: `start`, `state`, `link`, `send`, `status`, `received`, `error` and finally `done`, whose `result` is `pass` or `fail`. Scenario records also carry `role`, the role the phone linked in.
