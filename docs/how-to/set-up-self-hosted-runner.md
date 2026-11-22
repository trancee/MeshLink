# Set up the self-hosted macOS runner

This guide sets up a Mac as the self-hosted runner for the Apple phases of [`build.yml`](../../.github/workflows/build.yml): `simulator`, `swiftpm` and `maven`. The rules behind it are in [ADR 0017](../adr/0017-testing-architecture.md), "2026-11-21, self-hosted runner" amendment.

You need an Apple silicon Mac with macOS 27, an admin account on it, and admin rights on the repository. In this guide, *owner* means the admin account whose JDKs and Android SDK the runner reuses.

## Trust boundary

A persistent runner runs whatever code an eligible job brings, and that code can reach everything the runner account can read and the network the Mac sits on ([GitHub's hardening guidance](https://docs.github.com/en/actions/reference/security/secure-use#hardening-for-self-hosted-runners)). GitHub advises keeping public repositories off persistent runners. This repository is public, so ADR 0017 accepts the exposure only with these limits:

- Fork pull requests never reach the runner (the fork guard below).
- The runner account is a standard (non-admin) user with no SSH keys, signing keys or personal files, and no `sudo`.
- `build.yml` uses no secrets and a read-only `GITHUB_TOKEN`.

## What the workflow expects

| Item | Value | Used by |
|---|---|---|
| Runner labels | `self-hosted`, `macOS`, `ARM64` (the defaults on Apple silicon) | `runs-on` of the `apple` job |
| Xcode | `/Applications/Xcode-27.2.0.app` (the name [`xcodes`](https://github.com/XcodesOrg/xcodes) gives it) with the iOS 18.6 simulator runtime | `DEVELOPER_DIR`, `tools/swiftpm-consumer/test.sh` |
| `JAVA_HOME_21_arm64`, `JAVA_HOME_25_arm64` | JDK 21 (toolchain) and JDK 25 (Gradle daemon) | "Use the runner's JDKs" step |
| `ANDROID_HOME` | An Android SDK with `cmdline-tools/latest` and the NDK pinned as `android-ndk` in `gradle/libs.versions.toml` | `maven` phase |
| `minisign`, `python3` | On the runner's `PATH` (Homebrew) | release checks, simulator diagnostics |

Fork pull requests never reach this runner; they run on GitHub's `macos-26` image instead. `tools/ci/tests/test_require_apple_jobs.py` fails if that guard is removed.

## Steps

### 1. Create the runner account

1. In System Settings → Users & Groups, add a **Standard** user named `gh-runner` with a password. Don't make it an administrator: every step that needs `sudo` runs from the owner's account. Its primary group, `staff`, grants it the owner's group execute bits (step 3).
2. Under System Settings → Control Center → **Fast User Switching**, choose **Show in Menu Bar**. You need it to log in as `gh-runner` while staying logged in yourself.

### 2. Install Xcode and the simulator runtime

Xcode versions on the Mac are managed with [`xcodes`](https://github.com/XcodesOrg/xcodes), which installs each version side by side as `/Applications/Xcode-<version>.app`. As the owner:

```sh
brew install xcodes aria2 minisign
xcodes install 27.2
xcodes runtimes install "iOS 18.6"
xcodes installed
export DEVELOPER_DIR=/Applications/Xcode-27.2.0.app/Contents/Developer
sudo -E xcodebuild -license accept
xcodebuild -runFirstLaunch
```

`xcodes` asks for an Apple Account to download Xcode; `aria2` makes downloads faster. Don't run `xcodes select` for CI: the workflow picks Xcode per job through `DEVELOPER_DIR`, so the Mac's global selection can stay on whatever the owner uses. The Xcode path must match `DEVELOPER_DIR` in `build.yml` exactly. When you change Xcode, install the new version with `xcodes`, change `build.yml` and its test, and amend ADR 0005.

### 3. Share the owner's toolchains

The runner reads the owner's JDKs and Android SDK; it never writes to them.

1. Let `gh-runner` traverse, but not list, the owner's `Library` folder:

   ```sh
   chmod +a "gh-runner allow search" ~/Library
   ```

2. The NDK ships some binaries that only their owner may execute. Add the group execute bit, and repeat this after every NDK install or update:

   ```sh
   ndk="$ANDROID_HOME/ndk/<version>"
   find "$ndk" -type f -perm -u+x ! -perm -g+x -exec chmod g+x {} +
   ```

   If you skip this, the `maven` phase fails in `buildLibsodiumAndroid` with "C compiler cannot create executables".

### 4. Register the runner

1. In the repository, open Settings → Actions → Runners → **New self-hosted runner**, choose macOS and ARM64, and keep the page open. Use the runner version it offers, even if a newer release exists.
2. Log in as `gh-runner` through the Fast User Switching menu. It must be a real login session, not `su` or `sudo -u`: the service in step 6 needs it.
3. In Terminal, as `gh-runner`, create `~/actions-runner`, download the archive from that page into it, and compare `shasum -a 256` with the digest on the page. Extract only on a match.
4. Run the page's `./config.sh` command **without** its `--token` argument, so the runner prompts for the token and the token stays out of shell history. Accept the default labels.
5. Run `./run.sh` in the foreground and wait for `Listening for Jobs`. Stop it with Ctrl-C before step 6; never run it and the service at the same time.

### 5. Give the runner a clean environment

Jobs see only what the runner's `.env` and `.path` contain. Never start the runner from another user's terminal: it passes that shell's environment, including API keys, to every job, and job logs are public.

As `gh-runner`, in `~/actions-runner`:

```sh
cat > .env <<'EOF'
LANG=en_US.UTF-8
JAVA_HOME_21_arm64=<owner JDK 21 home>
JAVA_HOME_25_arm64=<owner JDK 25 home>
ANDROID_HOME=<owner Android SDK>
EOF
echo '/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin' > .path
```

### 6. Run it as a launchd service

Still in `gh-runner`'s login session:

```sh
cd ~/actions-runner
./svc.sh install
./svc.sh start
./svc.sh status
```

Don't use `sudo`. `svc.sh` installs a LaunchAgent in `gh-runner`'s `~/Library/LaunchAgents`, and a LaunchAgent only runs while `gh-runner` is logged in. Switch back to your account without logging `gh-runner` out. For the runner to come back after a reboot, set `gh-runner` as the automatic login user in System Settings → Users & Groups.

### 7. Allow unsigned build tools

libsodium's `configure` compiles and runs small unsigned test programs. A launchd service, unlike Terminal, lacks the **Developer Tools** permission, so Gatekeeper blocks those programs and the job hangs on a dialog that nobody sees.

1. As the owner, run `sudo spctl developer-mode enable-terminal`. This makes the **Developer Tools** list appear in System Settings.
2. In `gh-runner`'s session, in System Settings → Privacy & Security → **Developer Tools**, click **+**, press ⇧⌘G and add `/bin/bash`, which runs the service script.
3. Restart the service: `./svc.sh stop && ./svc.sh start`.

The permission belongs to `gh-runner`'s account only. Gatekeeper stays on for every other account.

### 8. Check it

1. In Settings → Actions → Runners, the runner is **Idle**.
2. Run `gh workflow run build.yml --ref main`.
3. Confirm that all three Apple phases pass and ran on this runner:

   ```sh
   gh api repos/trancee/MeshLink/actions/runs/<run id>/jobs \
     --jq '.jobs[] | select(.name | startswith("Apple")) | "\(.name) \(.conclusion) \(.runner_name)"'
   ```

## Maintain the runner

- **Before maintenance:** wait until the runner is idle in Settings → Actions → Runners, then run `./svc.sh stop` and check that it shows **Offline**. Stopping it mid-job fails that job.
- **Logs:** the listener writes `~/actions-runner/_diag/Runner_*.log`; each job writes a `Worker_*.log` beside it.
- **Updates:** leave runner auto-update on. Update macOS, Xcode (with `xcodes`), the JDKs and the NDK separately, then run step 8 again.
- **Retirement:** run `./svc.sh uninstall`, then `./config.sh remove` and enter the removal token from the runner's page when it asks. Check that the runner is gone in GitHub.

## Rules for workflow changes

- Never print the environment (`env`, `printenv`, `set`) in a step. Job logs are public.
- Keep the fork guard on `runs-on` and `DEVELOPER_DIR`.
- Use no repository secrets in `build.yml`.
- The runner keeps `~/.gradle` and `~/.konan` between jobs, so its jobs skip the Actions cache for both (`runner.environment == 'self-hosted'`).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `svc.sh`: "Must not run with sudo" | `svc.sh` installs a per-user LaunchAgent | Run it as `gh-runner`, without `sudo` (step 6) |
| The runner is **Offline** | The service stopped, or `gh-runner` isn't logged in | As `gh-runner`, check `./svc.sh status` and the newest `_diag/Runner_*.log` |
| `svc.sh start`: "Load failed: 5: Input/output error", status "Stopped" | `gh-runner` has no login session | Log in as `gh-runner` through Fast User Switching, then start again |
| "Load failed: 5" but status "Started" with a PID | The agent was already loaded at login | Nothing to do |
| `buildLibsodiumAndroid`: "C compiler cannot create executables" | Owner-only NDK executables | Step 3.2 |
| A job hangs in `buildLibsodiumApple`; the system log shows `ASP: Security policy would not allow process … conftest` | No Developer Tools permission | Step 7 |
| `EACCES … stat '/Users/<owner>/…'` lines in job logs | `.path` copied from another user's shell | Step 5 |
| A job fails with "The runner has received a shutdown signal" | The runner was stopped or restarted mid-job | Re-run the job |
| After a restart, the runner shows busy with a job that no longer runs | GitHub hasn't released the lost job yet | Wait until GitHub marks the job failed, then re-run it |
| Apple jobs time out at 30 minutes | Another process is loading the Mac | Check `uptime` and the top CPU users (`ps -axo pid,%cpu,etime,command -r`) |
