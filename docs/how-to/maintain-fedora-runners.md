# Maintain the Fedora runners

The three repository-scoped Linux runners use the `gh-runner` account and the
`YOGA` label. They have separate runner and work directories under
`/home/gh-runner`, but share the account, SDK and Gradle home. They can run
three jobs at once; they do not form a pool with runners registered to other
repositories.

The account has no `sudo` or personal credentials. The systemd user services
start with a clean environment and keep running after logout through user
lingering. A persistent runner can access its account's files and the host
network; GitHub warns against using it with public repositories. The workflow's
fork guard is not a substitute for reviewing workflow changes and controlling
which pull requests may run. See [ADR 0017](../adr/0017-testing-architecture.md).

## Check the runners

From an account authorized to run commands as `gh-runner`:

```sh
uid=$(id -u gh-runner)
sudo -u gh-runner env XDG_RUNTIME_DIR="/run/user/$uid" \
  DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$uid/bus" \
  systemctl --user status 'meshlink-runner@*.service'
gh api repos/trancee/MeshLink/actions/runners \
  --jq '.runners[] | select(.name | startswith("Fedora-Linux-")) | [.name, .status, .busy] | @tsv'
```

The three services should be active and GitHub should show each runner as
online. The unit is `~gh-runner/.config/systemd/user/meshlink-runner@.service`;
instances 1–3 run `actions-runner-1` through `actions-runner-3`.

## Stop or start an instance

Wait for the runner to be idle before stopping it; stopping a busy runner fails
its job. Replace `1` with the instance number:

```sh
uid=$(id -u gh-runner)
sudo -u gh-runner env XDG_RUNTIME_DIR="/run/user/$uid" \
  DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$uid/bus" \
  systemctl --user stop meshlink-runner@1.service
sudo -u gh-runner env XDG_RUNTIME_DIR="/run/user/$uid" \
  DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$uid/bus" \
  systemctl --user start meshlink-runner@1.service
```

Check its status again after starting it. Runner application updates are
automatic; update Fedora, the JDKs, `minisign`, and Android SDK separately.
The unit supplies `JAVA_HOME_21_X64`, `JAVA_HOME_25_X64` and `ANDROID_HOME`.
Install the catalog's pinned NDK before starting concurrent builds.
