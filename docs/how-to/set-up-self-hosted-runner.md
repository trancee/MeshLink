# Set up the self-hosted macOS runner

This guide sets up a Mac as the self-hosted runner for the Apple phases of [`build.yml`](../../.github/workflows/build.yml): `simulator`, `swiftpm` and `maven`. The rules behind it are in [ADR 0017](../adr/0017-testing-architecture.md), "2026-11-21, self-hosted runner" amendment.

You need an Apple silicon Mac with macOS 27, an admin account on it, and admin rights on the repository. In this guide, *owner* means the admin account whose JDKs and Android SDK the runner reuses.

## What the workflow expects

| Item | Value | Used by |
|---|---|---|
| Runner labels | `self-hosted`, `macOS`, `ARM64` (the defaults on Apple silicon) | `runs-on` of the `apple` job |
| Xcode | `/Applications/Xcode-27.2.0.app` with the iOS 18.6 simulator runtime | `DEVELOPER_DIR`, `tools/swiftpm-consumer/test.sh` |
| `JAVA_HOME_21_arm64`, `JAVA_HOME_25_arm64` | JDK 21 (toolchain) and JDK 25 (Gradle daemon) | "Use the runner's JDKs" step |
| `ANDROID_HOME` | An Android SDK with `cmdline-tools/latest` and the NDK pinned as `android-ndk` in `gradle/libs.versions.toml` | `maven` phase |
| `minisign`, `python3` | On the runner's `PATH` (Homebrew) | release checks, simulator diagnostics |

Fork pull requests never reach this runner; they run on GitHub's `macos-26` image instead. `tools/ci/tests/test_require_apple_jobs.py` fails if that guard is removed.

## Steps

### 1. Create the runner account

1. In System Settings → Users & Groups, add a **Standard** user named `gh-runner` with a password. Its group must include `staff`, which grants it the owner's group execute bits (step 3).
2. Under System Settings → Control Center → **Fast User Switching**, choose **Show in Menu Bar**. You need it to log in as `gh-runner` while staying logged in yourself.

### 2. Install Xcode and the simulator runtime

As the owner:

```sh
sudo xcodebuild -license accept
DEVELOPER_DIR=/Applications/Xcode-27.2.0.app/Contents/Developer xcodebuild -runFirstLaunch
DEVELOPER_DIR=/Applications/Xcode-27.2.0.app/Contents/Developer xcodebuild -downloadPlatform iOS
brew install minisign
```

The Xcode path must match `DEVELOPER_DIR` in `build.yml` exactly. When you change Xcode, change both, and amend ADR 0005.

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

1. In the repository, open Settings → Actions → Runners → **New self-hosted runner**, choose macOS and ARM64, and keep the page open for its token.
2. Log in as `gh-runner` through the Fast User Switching menu. It must be a real login session, not `su` or `sudo -u`: the service in step 6 needs it.
3. In Terminal, as `gh-runner`, run the download and `./config.sh` commands from that page in `~/actions-runner`. Accept the default labels.

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

In `gh-runner`'s session:

1. Run `sudo spctl developer-mode enable-terminal`.
2. In System Settings → Privacy & Security → **Developer Tools**, click **+**, press ⇧⌘G and add `/bin/bash`, which runs the service script.
3. Restart the service: `./svc.sh stop && ./svc.sh start`.

### 8. Check it

1. In Settings → Actions → Runners, the runner is **Idle**.
2. Run `gh workflow run build.yml --ref main`.
3. Confirm that all three Apple phases pass and ran on this runner:

   ```sh
   gh api repos/trancee/MeshLink/actions/runs/<run id>/jobs \
     --jq '.jobs[] | select(.name | startswith("Apple")) | "\(.name) \(.conclusion) \(.runner_name)"'
   ```

## Rules for workflow changes

- Never print the environment (`env`, `printenv`, `set`) in a step. Job logs are public.
- Keep the fork guard on `runs-on` and `DEVELOPER_DIR`.
- Use no repository secrets in `build.yml`.
- The runner keeps `~/.gradle` and `~/.konan` between jobs, so its jobs skip the Actions cache for both (`runner.environment == 'self-hosted'`).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `svc.sh`: "Must not run with sudo" | `svc.sh` installs a per-user LaunchAgent | Run it as `gh-runner`, without `sudo` (step 6) |
| `svc.sh start`: "Load failed: 5: Input/output error", status "Stopped" | `gh-runner` has no login session | Log in as `gh-runner` through Fast User Switching, then start again |
| "Load failed: 5" but status "Started" with a PID | The agent was already loaded at login | Nothing to do |
| `buildLibsodiumAndroid`: "C compiler cannot create executables" | Owner-only NDK executables | Step 3.2 |
| A job hangs in `buildLibsodiumApple`; the system log shows `ASP: Security policy would not allow process … conftest` | No Developer Tools permission | Step 7 |
| `EACCES … stat '/Users/<owner>/…'` lines in job logs | `.path` copied from another user's shell | Step 5 |
| A job fails with "The runner has received a shutdown signal" | The runner was stopped or restarted mid-job | Re-run the job |
| After a restart, the runner shows busy with a job that no longer runs | GitHub hasn't released the lost job yet | Wait until GitHub marks the job failed, then re-run it |
| Apple jobs time out at 30 minutes | Another process is loading the Mac | Check `uptime` and the top CPU users (`ps -axo pid,%cpu,etime,command -r`) |
