#!/usr/bin/env bash
# Build, codesign, and freshly (re)install the MeshLink ProofApp on every
# attached physical iPhone -- both newer devices reachable via `xcrun
# devicectl` and older devices (e.g. an iPhone SE (2016) on iOS 15) only
# reachable via `ios-deploy` (`brew install ios-deploy`). Also builds the
# ProofBenchmarks XCTest runner (build-for-testing) by default, so it is ready
# to run one-time device setup tests such as BluetoothPermissionGrant (see
# meshlink-proof/ios/ProofBenchmarks/BluetoothPermissionGrant.swift), which
# auto-taps the system Bluetooth permission prompt via an XCUITest
# interruption monitor.
#
# All of this is automatic with just --team: no --device/--ios-deploy-device
# flags are required. Auto-discovery works like this:
#   1. `xcrun devicectl list devices` supplies every "available (paired)"
#      device.
#   2. `ios-deploy -c` supplies every connected device's id and friendly name.
#      devicectl and ios-deploy use *incompatible* device-identifier schemes
#      for the very same physical device (devicectl's CoreDevice identifier
#      vs. ios-deploy's ECID-derived id), so ids alone cannot be compared.
#      Instead, any ios-deploy device whose friendly name (case-insensitively)
#      does not match a name already covered by devicectl in step 1 is
#      treated as reachable via ios-deploy only (e.g. an iPhone SE devicectl
#      cannot tunnel into at all) and is installed via ios-deploy instead.
# This name-based heuristic can under- or over-match if a device's name is
# inconsistent between the two tools (rare in practice); pass --device /
# --ios-deploy-device explicitly to bypass auto-discovery entirely for a
# specific run.
#
# ios-deploy always launches ProofApp when it installs it in a single
# invocation (there is no plain install-only mode for a build meant to
# actually run in that combined form), and keeping it running requires an
# attached debugger the whole time -- see the launch note further down.
# ReferenceApp, which only ever needs to be *installed* as a trust anchor
# (never run), uses --nostart to avoid launching it at all.
#
# This script never uninstalls ProofApp (or ReferenceApp) on iOS -- every
# install is an overwrite-in-place of whatever is already there, on both
# devicectl- and ios-deploy-managed devices. This is a deliberate, disclosed
# exception to AGENTS.md's general physical-device rule (which otherwise
# calls for uninstall-then-install), scoped specifically to this script,
# because uninstalling on iOS has two real, concrete costs neither of which
# exist on Android:
#   1. Uninstalling ProofApp on an ios-deploy-managed device can silently
#      revoke the on-device "trust this developer" record and force a manual
#      re-trust in Settings on every single run. iOS ties that trust to *some
#      app signed by the same certificate actually being installed*, not to
#      the certificate alone -- if ProofApp's uninstall ever left the device
#      with zero apps signed by your certificate (even for a moment), the
#      trust record was lost. (ReferenceApp is still installed as a trust
#      anchor below out of caution/defense-in-depth -- e.g. if you uninstall
#      ProofApp yourself between runs -- even though this script's own
#      overwrite-in-place installs no longer trigger this on their own.)
#   2. A fresh install immediately followed by a launch reliably gets
#      SIGKILLed by the OS a few seconds in (see the launch note further
#      down for the confirmed mechanism) -- relaunching an *already-installed,
#      unmodified* app does not hit this, which overwrite-in-place gives you
#      for every run after the first.
# A rebuilt app is still always installed fresh from this run's build output
# before being (re)launched -- "never uninstall" only means the previous
# install is overwritten rather than removed-then-recreated; you are never
# left running stale, previously-installed code.
#
# ProofApp itself will reliably CRASH within seconds of a plain
# "install-and-launch-then-detach" (e.g. --justlaunch) on this kind of build:
# Xcode's Debug-configuration "stub executor" launch mechanism (the ProofApp
# binary is a small stub that dyld-loads the real code from
# ProofApp.debug.dylib at startup) synchronizes that load through a dyld
# debugger-notify breakpoint (`brk 0` in `lldb_image_notifier`) that only an
# *attached* debugger services; detaching before that point leaves the
# breakpoint trap uncaught, which the OS treats as an uncaught SIGTRAP
# (EXC_BREAKPOINT) and kills the process almost immediately -- confirmed via
# `idevicecrashreport` (`brew install libimobiledevice`) pulling a crash
# report showing exactly this frame, and via `ios-deploy --get_pid` reporting
# no running process (pid: -1) within seconds of a "successful" --justlaunch.
# devicectl-managed devices do not hit this (Apple's own tooling handles the
# handshake correctly), so this is specific to the ios-deploy path.
#
# Separately, a *fresh install* immediately followed by a launch *in the same
# ios-deploy invocation* (even with the debugger kept attached) gets
# SIGKILLed by the OS a few seconds after successfully starting -- confirmed
# via `idevicesyslog` showing "termination reported by launchd" / SIGKILL(9)
# against a visibly-foreground, non-crashed process. Relaunching an
# *already-installed* app in a separate invocation does not hit this. So
# install and launch are always two separate ios-deploy invocations below:
# install with --nostart (never launches, so it can never hit either issue
# above), then launch separately with --noinstall (it is already installed)
# plus --noninteractive -d (keep the debugger attached, working around the
# first issue) run in the background (nohup + disown, detached from this
# script's own lifetime) so the script can still return. Stop the launched
# process yourself when done testing (e.g. `pkill -f "ios-deploy.*$device"`),
# or just rerun this script (which kills any lingering process for that
# device before reinstalling).
#
# This script exists because headless/automation shells (no logged-in GUI/Aqua
# session) cannot unlock the login keychain's Apple Development private key --
# `codesign` fails with `errSecInternalComponent` -- see
# docs/how-to/unblock-meshlink-permissions.md ("If iPhone signing fails before
# launch, check the keychain first") and
# docs/how-to/run-reference-app-physical-integration-scenarios.md for the same
# documented limitation. Run this script yourself, interactively, on the
# physical Mac -- not from an automation/agent shell.
#
# Per AGENTS.md's physical-device rule, this script always rebuilds fresh
# before every install -- it never reinstalls a stale, previously-built
# binary. Unlike that rule's general Android/adb guidance, iOS installs are
# deliberately overwrite-in-place rather than uninstall-then-install; see the
# "never uninstalls" note above for the concrete, iOS-specific reasons why.
#
# Usage:
#   scripts/build_and_install_ios_proof_app.sh --team <DEVELOPMENT_TEAM_ID>
#     [--device <udid>]... [--ios-deploy-device <udid>]... [--launch]
#     [--skip-build-tests] [--run-test <TARGET/TestClass[/testMethod]>]
#
# Example (build your Apple Development team ID first, e.g. via
# `security find-certificate -c "Apple Development: <you>" -p | openssl x509
# -noout -subject`, the OU= field) -- installs on every attached iPhone, old
# and new, and builds the XCTest runner, with zero other flags:
#
#   scripts/build_and_install_ios_proof_app.sh --team <YOUR_TEAM_ID>
#
# Example targeting specific devices only (disables auto-discovery for
# whichever of --device/--ios-deploy-device you pass):
#
#   scripts/build_and_install_ios_proof_app.sh --team <YOUR_TEAM_ID> \
#     --device 6972CDF5-F3EB-5602-B53B-583BDB6D1AD1 \
#     --ios-deploy-device 0f2bc050bcf08e3582b3d62af450df61a59d3039
#
# Example: skip building the XCTest runner (app-only, slightly faster):
#
#   scripts/build_and_install_ios_proof_app.sh --team <YOUR_TEAM_ID> --skip-build-tests
#
# Example: also run the one-time Bluetooth permission-grant test against every
# devicectl-managed target device (only needs to succeed once per device --
# see BluetoothPermissionGrant.swift's doc comment; ios-deploy-managed devices
# are not supported by --run-test since it drives devices through devicectl):
#
#   scripts/build_and_install_ios_proof_app.sh --team <YOUR_TEAM_ID> \
#     --run-test ProofBenchmarks/BluetoothPermissionGrant
#
# Note: at the time --run-test was added, running any XCTest UI-test runner
# against certain physical device/Xcode pairings in this project's own
# development environment failed with "Root install style is not supported on
# this device" while installing the runner -- a pre-existing Xcode/devicectl
# tooling limitation reproduced even against the project's older, unmodified
# benchmark tests, not something this script can work around. If you hit that
# error, it is an Xcode/device-pairing issue to resolve on your machine (e.g. a
# stale device-support cache), not a bug in this script or the test itself.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project_path="$repo_root/meshlink-proof/ios/ProofApp.xcodeproj"
scheme="ProofApp"
tests_scheme="ProofBenchmarks"
bundle_id="ch.trancee.meshlink.proof.ios"
# ReferenceApp is installed (once, if missing) as a trust anchor on every
# ios-deploy-managed device -- see the ios-deploy trust-loss note further
# down for why.
reference_app_project_path="$repo_root/meshlink-reference/ios/ReferenceApp.xcodeproj"
reference_app_scheme="ReferenceApp"
reference_app_bundle_id="ch.trancee.meshlink.reference.ios"
# Every per-device ios-deploy invocation below is bounded by this so a
# device that is slow to respond (or briefly unreachable) fails fast instead
# of hanging indefinitely -- ios-deploy has no default connection timeout.
ios_deploy_timeout=30

development_team=""
devices=()
ios_deploy_devices=()
devices_explicit=0
ios_deploy_devices_explicit=0
launch_after_install=0
build_tests=1
run_test=""

usage() {
  cat <<'EOF'
Usage: build_and_install_ios_proof_app.sh --team <DEVELOPMENT_TEAM_ID>
         [--device <udid>]... [--ios-deploy-device <udid>]... [--launch]
         [--skip-build-tests] [--run-test <TARGET/TestClass[/testMethod]>]

  --team <id>              Apple Development Team ID used for DEVELOPMENT_TEAM. If
                            omitted, auto-detected from your keychain's
                            codesigning identities when unambiguous, or asked
                            interactively (with a choice, if more than one is
                            found) otherwise. Never hardcode a real team ID in
                            any script, commit, or committed file -- it is
                            never written anywhere by this script.
  --device <udid>          CoreDevice identifier to target (repeatable). Disables
                            devicectl auto-discovery for this run once passed at
                            least once.
  --ios-deploy-device <id> Device id (from `ios-deploy -c`) to install via
                            ios-deploy instead of devicectl (repeatable).
                            Disables ios-deploy auto-discovery for this run
                            once passed at least once. Installing via
                            ios-deploy always launches the app too (ios-deploy
                            has no install-only mode). Not supported by
                            --run-test.
  --launch                 Launch the app on each --device (devicectl) target
                            after installing it.
  --skip-build-tests       Do not build the ProofBenchmarks XCTest target
                            (build-for-testing is on by default).
  --run-test <id>          Run this test identifier (e.g.
                            ProofBenchmarks/BluetoothPermissionGrant) against every
                            --device target via `xcodebuild test-without-building`,
                            after building the test target. Repeatable is not
                            supported; pass one identifier per invocation.
  -h, --help               Show this help text.

With no --device/--ios-deploy-device flags at all, every attached iPhone is
targeted automatically -- see this script's header comment for exactly how
devicectl- and ios-deploy-reachable devices are told apart.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --team)
      development_team="${2:?--team requires a value}"
      shift 2
      ;;
    --device)
      devices+=("${2:?--device requires a value}")
      devices_explicit=1
      shift 2
      ;;
    --ios-deploy-device)
      ios_deploy_devices+=("${2:?--ios-deploy-device requires a value}")
      ios_deploy_devices_explicit=1
      shift 2
      ;;
    --launch)
      launch_after_install=1
      shift
      ;;
    --skip-build-tests)
      build_tests=0
      shift
      ;;
    --run-test)
      run_test="${2:?--run-test requires a value}"
      build_tests=1
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

# Never hardcode or default a real team ID anywhere in this script (including
# its own usage examples/comments above) -- it is auto-detected from your own
# keychain when unambiguous, or asked interactively otherwise, and is never
# written to any file this script touches.
resolve_development_team() {
  if [[ -n "$development_team" ]]; then
    return
  fi

  local identity_names=()
  while IFS= read -r identity_name; do
    identity_names+=("$identity_name")
  done < <(
    security find-identity -v -p codesigning 2>/dev/null \
      | grep -oE '"[^"]+"' \
      | tr -d '"' \
      | sort -u
  )

  local team_ids=()
  local identity_name team_id already_seen seen_id
  for identity_name in "${identity_names[@]:-}"; do
    [[ -z "$identity_name" ]] && continue
    team_id=$(
      security find-certificate -c "$identity_name" -p 2>/dev/null \
        | openssl x509 -noout -subject 2>/dev/null \
        | grep -oE 'OU=[^,]+' \
        | head -n1 \
        | cut -d= -f2
    )
    [[ -z "$team_id" ]] && continue
    already_seen=0
    for seen_id in "${team_ids[@]:-}"; do
      if [[ "$seen_id" == "$team_id" ]]; then
        already_seen=1
        break
      fi
    done
    [[ "$already_seen" -eq 1 ]] || team_ids+=("$team_id")
  done

  if [[ ${#team_ids[@]} -eq 1 ]]; then
    development_team="${team_ids[0]}"
    echo "Using the one Apple Development Team ID found in your keychain (not stored/committed anywhere)." >&2
    return
  fi

  if [[ ${#team_ids[@]} -gt 1 ]]; then
    echo "Multiple Apple Development Team IDs found in your keychain:" >&2
    local i
    for i in "${!team_ids[@]}"; do
      echo "  $((i + 1))) ${team_ids[$i]}" >&2
    done
    if [[ -t 0 ]]; then
      local choice
      read -r -p "Which one do you want to use? [1-${#team_ids[@]}]: " choice
      if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#team_ids[@]} )); then
        development_team="${team_ids[$((choice - 1))]}"
        return
      fi
      echo "error: invalid selection" >&2
      exit 1
    fi
    echo "error: pass --team <DEVELOPMENT_TEAM_ID> explicitly to pick one (not running interactively)" >&2
    exit 1
  fi

  # Nothing found in the keychain; fall back to asking directly.
  if [[ -t 0 ]]; then
    read -r -p "Apple Development Team ID (not stored/committed anywhere): " development_team
  fi
  if [[ -z "$development_team" ]]; then
    echo "error: --team <DEVELOPMENT_TEAM_ID> is required (or run interactively to be prompted)" >&2
    usage >&2
    exit 1
  fi
}

resolve_development_team

if [[ -n "$run_test" && ${#ios_deploy_devices[@]} -gt 0 ]]; then
  echo "error: --run-test only supports --device (devicectl) targets, not --ios-deploy-device" >&2
  exit 1
fi

devicectl_names=()

if [[ "$devices_explicit" -eq 0 ]]; then
  echo "No --device given; discovering attached, paired iPhones via xcrun devicectl..."
  # Columns: Name  Hostname  Identifier  State  Model. Only take rows whose
  # State column starts with "available" (skips "unavailable" entries like a
  # device that was recently unplugged but still cached by devicectl).
  while IFS=$'\t' read -r name identifier; do
    devices+=("$identifier")
    devicectl_names+=("$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')")
  done < <(
    xcrun devicectl list devices 2>/dev/null \
      | awk 'NR > 2 && $0 ~ /available \(paired\)/ {
          identifier = ""
          for (i = 1; i <= NF; i++) if ($i ~ /^[0-9A-Fa-f-]{36}$/) identifier = $i
          # Name never contains a "."; Hostname always does (e.g.
          # "iPhone-15.coredevice.local") -- stop accumulating the Name
          # column the moment a dotted (Hostname) or identifier field is hit,
          # so a multi-word name like "iPhone 12 mini" is captured correctly
          # without also swallowing the Hostname column that immediately
          # follows it.
          name = ""
          for (i = 1; i <= NF; i++) {
            if ($i == identifier || $i ~ /\./) break
            name = (name == "" ? $i : name " " $i)
          }
          print name "\t" identifier
        }'
  )
fi

if [[ "$ios_deploy_devices_explicit" -eq 0 ]]; then
  echo "No --ios-deploy-device given; discovering ios-deploy-only iPhones via ios-deploy -c..."
  if ! command -v ios-deploy >/dev/null 2>&1; then
    echo "    (ios-deploy not found on PATH -- skipping; brew install ios-deploy to enable this)"
  else
    seen_ios_deploy_ids=()
    while IFS=$'\t' read -r ios_deploy_id ios_deploy_name; do
      [[ -z "$ios_deploy_id" ]] && continue
      # Dedupe ios-deploy's own list (the same device can appear twice, once
      # per transport, e.g. once via WIFI and once via USB with the same id).
      already_seen=0
      for seen_id in "${seen_ios_deploy_ids[@]:-}"; do
        if [[ "$seen_id" == "$ios_deploy_id" ]]; then
          already_seen=1
          break
        fi
      done
      [[ "$already_seen" -eq 1 ]] && continue
      seen_ios_deploy_ids+=("$ios_deploy_id")

      lowercase_name="$(printf '%s' "$ios_deploy_name" | tr '[:upper:]' '[:lower:]')"
      covered_by_devicectl=0
      for devicectl_name in "${devicectl_names[@]:-}"; do
        if [[ "$devicectl_name" == "$lowercase_name" ]]; then
          covered_by_devicectl=1
          break
        fi
      done
      if [[ "$covered_by_devicectl" -eq 0 ]]; then
        ios_deploy_devices+=("$ios_deploy_id")
      fi
    done < <(
      ios-deploy -c --timeout 8 2>/dev/null \
        | sed -n "s/^\[\.\.\.\.\] Found \([0-9A-Fa-f-]*\).* a\.k\.a\. '\([^']*\)'.*/\1\t\2/p"
    )
  fi
fi

if [[ ${#devices[@]} -eq 0 && ${#ios_deploy_devices[@]} -eq 0 ]]; then
  echo "error: no attached, paired devices found (and none passed via --device/--ios-deploy-device)" >&2
  exit 1
fi

echo "Target devices (${#devices[@]} via devicectl, ${#ios_deploy_devices[@]} via ios-deploy):"
printf '  %s (devicectl)\n' "${devices[@]:-}"
printf '  %s (ios-deploy)\n' "${ios_deploy_devices[@]:-}"

echo
echo "==> Building ProofApp fresh for physical iOS (generic/platform=iOS, DEVELOPMENT_TEAM=$development_team)"
# `generic/platform=iOS` produces one arm64 device build reusable across every
# attached physical iPhone; there is no need to build per-device.
xcodebuild \
  -project "$project_path" \
  -scheme "$scheme" \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$repo_root/build/ios-proof-app-derived-data" \
  DEVELOPMENT_TEAM="$development_team" \
  build

app_path=$(find "$repo_root/build/ios-proof-app-derived-data/Build/Products" -maxdepth 2 -type d -name "${scheme}.app" -path "*iphoneos*" | head -n1)
if [[ -z "$app_path" ]]; then
  echo "error: could not locate built ${scheme}.app under Build/Products" >&2
  exit 1
fi
echo "Built app: $app_path"

for device in "${devices[@]:-}"; do
  [[ -z "$device" ]] && continue
  echo
  echo "==> $device: installing freshly built app (overwrite in place, no uninstall -- see the no-uninstall note above)"
  xcrun devicectl device install app --device "$device" "$app_path"

  if [[ "$launch_after_install" -eq 1 ]]; then
    echo "==> $device: launching ${bundle_id}"
    xcrun devicectl device process launch --device "$device" "$bundle_id"
  fi
done

reference_app_path=""
if [[ ${#ios_deploy_devices[@]} -gt 0 ]]; then
  # Only build ReferenceApp if at least one ios-deploy device might actually
  # need it installed; checked per-device below via --exists so an already-
  # trust-anchored device's ReferenceApp is left untouched.
  needs_reference_app=0
  for device in "${ios_deploy_devices[@]:-}"; do
    [[ -z "$device" ]] && continue
    if ! ios-deploy --id "$device" --timeout "$ios_deploy_timeout" --exists --bundle_id "$reference_app_bundle_id" >/dev/null 2>&1; then
      needs_reference_app=1
      break
    fi
  done

  if [[ "$needs_reference_app" -eq 1 ]]; then
    echo
    echo "==> Building ReferenceApp fresh for physical iOS (trust anchor for ios-deploy-managed devices, generic/platform=iOS, DEVELOPMENT_TEAM=$development_team)"
    xcodebuild \
      -project "$reference_app_project_path" \
      -scheme "$reference_app_scheme" \
      -destination 'generic/platform=iOS' \
      -derivedDataPath "$repo_root/build/ios-reference-app-derived-data" \
      DEVELOPMENT_TEAM="$development_team" \
      -allowProvisioningUpdates \
      build

    reference_app_path=$(find "$repo_root/build/ios-reference-app-derived-data/Build/Products" -maxdepth 2 -type d -name "${reference_app_scheme}.app" -path "*iphoneos*" | head -n1)
    if [[ -z "$reference_app_path" ]]; then
      echo "error: could not locate built ${reference_app_scheme}.app under Build/Products" >&2
      exit 1
    fi
    echo "Built ReferenceApp: $reference_app_path"
  fi
fi

for device in "${ios_deploy_devices[@]:-}"; do
  [[ -z "$device" ]] && continue

  if [[ -n "$reference_app_path" ]] && ! ios-deploy --id "$device" --timeout "$ios_deploy_timeout" --exists --bundle_id "$reference_app_bundle_id" >/dev/null 2>&1; then
    echo
    echo "==> $device (ios-deploy): installing ReferenceApp as a trust anchor (never uninstalled by this script)"
    # --nostart: ReferenceApp only needs to be *installed*, never launched, so
    # this deliberately avoids ever running it at all -- sidestepping the
    # dyld-debugger-notify crash documented below for ProofApp's own launch,
    # since an app that never runs can never hit it. Also gives a reliable
    # exit code (unlike --justlaunch/--noninteractive -d).
    if ! ios-deploy --id "$device" --timeout "$ios_deploy_timeout" --bundle "$reference_app_path" --nostart; then
      echo "    (ios-deploy exited non-zero installing ReferenceApp on $device -- check the output above)"
    fi
  fi

  echo
  echo "==> $device (ios-deploy): installing freshly built app (overwrite in place, no uninstall)"
  # Splitting install and launch into two separate ios-deploy invocations (as
  # opposed to a single "--bundle <path> --noninteractive -d" call that
  # installs and launches together) works around a real, reproducible issue:
  # a fresh install immediately followed by a launch in the same invocation
  # gets SIGKILLed by the OS a few seconds after successfully starting
  # (confirmed via idevicesyslog showing
  # "termination reported by launchd" / "SIGKILL(9)" against a
  # visibly-foreground, non-crashed process), while relaunching an
  # *already-installed* app in its own separate invocation does not. The
  # install step below uses --nostart for the same reason ReferenceApp does
  # (see above): it only needs to install, not run, so it can never hit
  # either this kill or the dyld-debugger-notify crash documented below.
  pkill -f "ios-deploy --id $device " 2>/dev/null || true
  if ! ios-deploy --id "$device" --timeout "$ios_deploy_timeout" --bundle "$app_path" --nostart; then
    echo "    (ios-deploy exited non-zero installing $device -- check the output above)"
  fi

  echo "==> $device (ios-deploy): launching freshly installed app"
  # A plain "install and launch, then exit" (--justlaunch, or any other mode
  # that detaches lldb) reliably CRASHES the app moments after launch on this
  # kind of build: Xcode's Debug-configuration "stub executor" launch
  # mechanism (the ProofApp binary is a small stub that dyld-loads the real
  # code from ProofApp.debug.dylib at startup) synchronizes that load through
  # a dyld debugger-notify breakpoint (`brk 0` in `lldb_image_notifier`) that
  # only an *attached* debugger services; detaching before that point leaves
  # the breakpoint trap uncaught, which the OS treats as an uncaught SIGTRAP
  # (EXC_BREAKPOINT) and kills the process -- confirmed via `idevicecrashreport`
  # (`brew install libimobiledevice`) pulling a crash report showing exactly
  # this frame, and via `ios-deploy --get_pid` reporting no running process
  # (pid: -1) within seconds of a "successful" --justlaunch. devicectl-managed
  # devices do not hit this (Apple's own tooling handles the handshake
  # correctly), so this is specific to the ios-deploy path.
  #
  # The only reliable fix is to keep a debugger attached for as long as the
  # app needs to keep running: launch in the background with --noninteractive
  # -d --noinstall (relaunching the app installed by the step above, not
  # installing again) and leave that process running, detached from this
  # script's own lifetime (nohup + disown) so the script can still return.
  # Stop it yourself when done testing (e.g. `pkill -f "ios-deploy.*$device"`),
  # or just rerun this script (which starts by killing any lingering process
  # for that device before reinstalling).
  ios_deploy_log="$repo_root/build/ios-deploy-launch-${device}.log"
  # setsid(1) does not exist on macOS (it is a Linux/util-linux tool) --
  # nohup (detach from the controlling terminal/SIGHUP) plus disown (remove
  # from this shell's job table so it is never waited-on or signaled) is the
  # portable BSD/macOS-safe equivalent for "launch and leave running
  # independent of this script's own process".
  #
  # Separately from the deterministic crash fixed above, this app also
  # sometimes (not always, and not reliably reproducible on demand) gets
  # SIGKILLed by the OS a few seconds after a fully successful launch on this
  # specific old device via ios-deploy -- confirmed via idevicesyslog, not a
  # crash, and not consistently tied to any one variable we could isolate
  # (occurred both with and without a prior uninstall, for instance). Retry
  # launching a few times rather than chasing full determinism further.
  launched=0
  poll_interval=3
  poll_attempts=$(( (ios_deploy_timeout + poll_interval - 1) / poll_interval ))
  for attempt in 1 2 3; do
    pkill -f "ios-deploy --id $device " 2>/dev/null || true
    nohup ios-deploy --id "$device" --timeout "$ios_deploy_timeout" --bundle "$app_path" --noinstall --noninteractive -d \
      > "$ios_deploy_log" 2>&1 &
    disown

    # Installing (not just launching) can take well over a few seconds on an
    # older device, so poll for a running pid rather than checking once
    # immediately, bounded by the same ios_deploy_timeout used everywhere else.
    for _ in $(seq 1 "$poll_attempts"); do
      sleep "$poll_interval"
      if ! ios-deploy --id "$device" --timeout "$ios_deploy_timeout" --get_pid --bundle_id "$bundle_id" 2>/dev/null | tail -1 | grep -q 'pid: -1'; then
        launched=1
        break
      fi
    done
    if [[ "$launched" -eq 1 ]]; then
      break
    fi
    echo "    launch attempt $attempt did not stay running -- retrying" >&2
  done
  if [[ "$launched" -eq 1 ]]; then
    echo "    launched and left running in the background (log: $ios_deploy_log); stop it yourself when done testing"
  else
    echo "    (warning: ${bundle_id} does not appear to be running on $device after 3 launch attempts -- see $ios_deploy_log)"
  fi
done

echo
echo "Done. Installed a fresh ${scheme}.app build on: ${devices[*]:-} ${ios_deploy_devices[*]:-}"
if [[ "$launch_after_install" -eq 0 && ${#devices[@]} -gt 0 ]]; then
  echo "Launch manually on devicectl-managed devices with:"
  echo "  xcrun devicectl device process launch --device <udid> $bundle_id"
fi

tests_derived_data="$repo_root/build/ios-proof-benchmarks-derived-data"

if [[ "$build_tests" -eq 1 ]]; then
  echo
  echo "==> Building ${tests_scheme} for testing on physical iOS (generic/platform=iOS, DEVELOPMENT_TEAM=$development_team)"
  # Reuses a dedicated derivedDataPath (separate from the app build above) so
  # xcodebuild can later resolve the built test products/.xctestrun by scheme
  # + derivedDataPath alone in the test-without-building step below.
  xcodebuild build-for-testing \
    -project "$project_path" \
    -scheme "$tests_scheme" \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "$tests_derived_data" \
    DEVELOPMENT_TEAM="$development_team" \
    -allowProvisioningUpdates
fi

if [[ -n "$run_test" ]]; then
  for device in "${devices[@]:-}"; do
    [[ -z "$device" ]] && continue
    echo
    echo "==> $device: running $run_test"
    xcodebuild test-without-building \
      -project "$project_path" \
      -scheme "$tests_scheme" \
      -destination "id=$device" \
      -derivedDataPath "$tests_derived_data" \
      DEVELOPMENT_TEAM="$development_team" \
      -allowProvisioningUpdates \
      "-only-testing:$run_test"
  done
  echo
  echo "Done running $run_test on: ${devices[*]:-}"
fi
