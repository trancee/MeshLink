#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import plistlib
import re
import shlex
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable

IOS_BUNDLE_ID = "ch.trancee.meshlink.reference.ios"
IOS_KNOWN_DEV_APPS_FOR_CLEANUP = (
    "ch.trancee.meshlink.proof.ios",
    "ch.trancee.meshlink.proof.ios.benchmarks.xctrunner",
)
ANDROID_PACKAGE = "ch.trancee.meshlink.reference"
ANDROID_ACTIVITY = f"{ANDROID_PACKAGE}/.MainActivity"
ANDROID_AUTOMATION_LOG_TAGS = ["MeshLinkReferenceAutomation:I", "*:S"]
DEFAULT_ANDROID_READY_SECONDS = 8
DEFAULT_CAPTURE_TIMEOUT_SECONDS = 60
DEFAULT_POST_RESULT_IDLE_SECONDS = 5
ADB_DEVICE_PATTERN = re.compile(r"^(?P<serial>\S+)\s+(?P<state>device|offline|unauthorized)\b")
ANDROID_PROOF_COMPLETE_PATTERN = re.compile(
    r"REFERENCE_AUTOMATION proof\.complete role=passive .* export=(?P<export>\S+)"
)
PROVISIONING_PROFILE_DIRS = [
    Path.home() / "Library/Developer/Xcode/UserData/Provisioning Profiles",
    Path.home() / "Library/MobileDevice/Provisioning Profiles",
]
ANDROID_EXTRA_UI_AUTOMATION = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION"
ANDROID_EXTRA_MODE = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_MODE"
ANDROID_EXTRA_STORAGE = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_STORAGE_SUBDIRECTORY"
ANDROID_EXTRA_APP_ID = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_APP_ID"
ANDROID_EXTRA_ROLE = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_ROLE"
AUTOMATION_MODE_LIVE_PROOF = "live-proof"
IOS_SENDER_MARKER = "REFERENCE_AUTOMATION proof.complete role=sender"
IOS_XCUITEST_LIVE_PROOF_CONFIG = Path("/tmp/meshlink_reference_live_proof_xcuitest.json")
IOS_XCUITEST_SKIPPED_MARKER = "Physical live-proof UI test only runs when explicitly opted in"


class BackgroundProcess:
    def __init__(
        self,
        process: subprocess.Popen[str],
        cleanup_actions: Iterable[Callable[[], None]] = (),
    ) -> None:
        self.process = process
        self.cleanup_actions = list(cleanup_actions)

    def stop(self) -> None:
        try:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=5)
        finally:
            for cleanup in self.cleanup_actions:
                cleanup()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run a retained Android-reference-app ↔ physical-iPhone-reference-app BLE proof with "
            "host-launched live automation and export/history verification."
        )
    )
    parser.add_argument("--android-serial", required=True, help="ADB serial of the Android device")
    parser.add_argument("--ios-device", required=True, help="Physical iPhone UDID for devicectl/xcodebuild")
    parser.add_argument(
        "--app-id",
        help="Shared MeshLink app ID. Defaults to demo.meshlink.reference.live.<timestamp>",
    )
    parser.add_argument(
        "--run-dir",
        help="Directory for retained logs and artifacts. Defaults to /tmp/reference_live_proof_<timestamp>",
    )
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=DEFAULT_ANDROID_READY_SECONDS,
        help="How long to wait after launching the Android passive app before starting the iPhone sender",
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=DEFAULT_CAPTURE_TIMEOUT_SECONDS,
        help="Hard timeout for both the iPhone launch path and Android completion wait",
    )
    parser.add_argument(
        "--ios-launch-mode",
        choices=["devicectl", "xcuitest"],
        default="devicectl",
        help="How to launch the physical iPhone sender",
    )
    parser.add_argument(
        "--post-result-idle-seconds",
        type=float,
        default=DEFAULT_POST_RESULT_IDLE_SECONDS,
        help="How long to keep the iPhone console open after the sender completion marker when using devicectl",
    )
    parser.add_argument("--skip-android-install", action="store_true")
    parser.add_argument("--skip-ios-build", action="store_true")
    parser.add_argument("--skip-ios-install", action="store_true")
    parser.add_argument(
        "--cleanup-ios-dev-app-slots",
        action="store_true",
        help="Before the xcuitest launch path, uninstall known MeshLink dev apps to free free-profile app slots",
    )
    return parser.parse_args()


def run(command: list[str], *, check: bool = True, capture_output: bool = False, text: bool = True, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=check, capture_output=capture_output, text=text, env=env)


def shell_join(command: Iterable[str]) -> str:
    return " ".join(shlex.quote(part) for part in command)


def timestamp() -> str:
    return time.strftime("%Y%m%dT%H%M%S")


def adb_devices() -> dict[str, str]:
    result = run(["adb", "devices"], capture_output=True)
    devices: dict[str, str] = {}
    for line in result.stdout.splitlines():
        match = ADB_DEVICE_PATTERN.match(line.strip())
        if match is None:
            continue
        devices[match.group("serial")] = match.group("state")
    return devices


def ensure_android_device_ready(android_serial: str) -> None:
    devices = adb_devices()
    state = devices.get(android_serial)
    if state == "device":
        return
    available = ", ".join(f"{serial}({state})" for serial, state in sorted(devices.items())) or "none"
    raise SystemExit(f"Android serial '{android_serial}' is not ready. Available devices: {available}")


def decode_provisioning_profile(path: Path) -> dict[str, Any] | None:
    result = subprocess.run(["security", "cms", "-D", "-i", str(path)], check=False, capture_output=True)
    if result.returncode != 0:
        return None
    try:
        return plistlib.loads(result.stdout)
    except Exception:
        return None


def candidate_provisioning_profiles() -> list[Path]:
    candidates: list[Path] = []
    for directory in PROVISIONING_PROFILE_DIRS:
        if not directory.exists():
            continue
        candidates.extend(sorted(directory.glob("*.mobileprovision")))
        candidates.extend(sorted(directory.glob("*.provisionprofile")))
    return candidates


def local_development_team_for_bundle_id(bundle_id: str) -> str | None:
    now = datetime.now(timezone.utc)
    matches: list[tuple[datetime, Path, str]] = []
    for path in candidate_provisioning_profiles():
        profile = decode_provisioning_profile(path)
        if profile is None:
            continue
        entitlements = profile.get("Entitlements")
        team_identifiers = profile.get("TeamIdentifier")
        if not isinstance(entitlements, dict) or not isinstance(team_identifiers, list) or not team_identifiers:
            continue
        app_identifier = entitlements.get("application-identifier")
        if not isinstance(app_identifier, str) or not app_identifier.endswith(f".{bundle_id}"):
            continue
        expiration = profile.get("ExpirationDate")
        if isinstance(expiration, datetime):
            if expiration.tzinfo is None:
                expiration = expiration.replace(tzinfo=timezone.utc)
            if expiration < now:
                continue
        else:
            expiration = datetime.max.replace(tzinfo=timezone.utc)
        team = team_identifiers[0]
        if not isinstance(team, str) or not team:
            continue
        matches.append((expiration, path, team))
    if not matches:
        return None
    matches.sort(reverse=True)
    return matches[0][2]


def resolve_development_team() -> str:
    team = os.environ.get("DEVELOPMENT_TEAM", "").strip()
    if team:
        return team
    team = local_development_team_for_bundle_id(IOS_BUNDLE_ID)
    if team:
        print("==> Using DEVELOPMENT_TEAM from local cached provisioning assets")
        return team
    raise SystemExit(
        "DEVELOPMENT_TEAM is required unless a matching local provisioning profile already exists for "
        f"{IOS_BUNDLE_ID}."
    )


def latest_built_app() -> Path:
    derived_data = Path.home() / "Library/Developer/Xcode/DerivedData"
    candidates = sorted(
        derived_data.glob("ReferenceApp-*/Build/Products/Debug-iphoneos/ReferenceApp.app"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise SystemExit("Could not find a built ReferenceApp.app in Xcode DerivedData")
    return candidates[0]


def latest_built_xctestrun() -> Path:
    derived_data = Path.home() / "Library/Developer/Xcode/DerivedData"
    candidates = sorted(
        derived_data.glob("ReferenceApp-*/Build/Products/*.xctestrun"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise SystemExit("Could not find a built ReferenceApp .xctestrun file in Xcode DerivedData")
    return candidates[0]


def build_ios_app(ios_device: str, run_dir: Path) -> Path:
    development_team = resolve_development_team()
    command = [
        "xcodebuild",
        "-project",
        "meshlink-reference/ios/ReferenceApp.xcodeproj",
        "-scheme",
        "ReferenceApp",
        "-destination",
        f"id={ios_device}",
        f"DEVELOPMENT_TEAM={development_team}",
        "build",
    ]
    redacted_command = [
        "DEVELOPMENT_TEAM=<redacted>" if part == f"DEVELOPMENT_TEAM={development_team}" else part
        for part in command
    ]
    print(f"==> Building physical iPhone reference app: {shell_join(redacted_command)}")
    log_path = run_dir / "ios_build.log"
    with log_path.open("w", encoding="utf-8") as log_file:
        result = subprocess.run(command, stdout=log_file, stderr=subprocess.STDOUT, text=True)
    log_text = log_path.read_text(encoding="utf-8", errors="replace").replace(development_team, "<redacted>")
    if result.returncode != 0:
        print("==> iPhone reference app build failed; tail follows:", file=sys.stderr)
        print("\n".join(log_text.splitlines()[-40:]), file=sys.stderr)
        raise SystemExit(result.returncode)
    return latest_built_app()


def install_ios_app(ios_device: str, app_path: Path) -> None:
    command = [
        "xcrun",
        "devicectl",
        "device",
        "install",
        "app",
        "--device",
        ios_device,
        str(app_path),
    ]
    print(f"==> Installing iPhone reference app: {shell_join(command)}")
    run(command)


def list_ios_installed_meshlink_apps(ios_device: str) -> set[str]:
    command = [
        "xcrun",
        "devicectl",
        "device",
        "info",
        "apps",
        "--device",
        ios_device,
        "--filter",
        'bundleIdentifier CONTAINS "ch.trancee.meshlink"',
    ]
    result = run(command, capture_output=True)
    return set(re.findall(r"ch\\.trancee\\.meshlink(?:\\.[A-Za-z0-9_-]+)+", result.stdout))


def cleanup_ios_dev_app_slots(ios_device: str) -> None:
    installed_apps = list_ios_installed_meshlink_apps(ios_device)
    removable_apps = [app for app in IOS_KNOWN_DEV_APPS_FOR_CLEANUP if app in installed_apps]
    if not removable_apps:
        print("==> No known extra MeshLink iPhone dev apps needed cleanup")
        return
    for bundle_id in removable_apps:
        command = [
            "xcrun",
            "devicectl",
            "device",
            "uninstall",
            "app",
            "--device",
            ios_device,
            bundle_id,
        ]
        print(f"==> Removing iPhone dev app to free a signing slot: {bundle_id}")
        run(command)


def run_ios_live_proof_test(
    *,
    ios_device: str,
    app_id: str,
    storage_subdirectory: str,
    run_dir: Path,
) -> None:
    development_team = resolve_development_team()
    build_command = [
        "xcodebuild",
        "-project",
        "meshlink-reference/ios/ReferenceApp.xcodeproj",
        "-scheme",
        "ReferenceApp",
        "-destination",
        f"id={ios_device}",
        f"DEVELOPMENT_TEAM={development_team}",
        "-allowProvisioningUpdates",
        "build-for-testing",
    ]
    redacted_build_command = [
        "DEVELOPMENT_TEAM=<redacted>" if part == f"DEVELOPMENT_TEAM={development_team}" else part
        for part in build_command
    ]
    print(f"==> Building physical iPhone UI tests for xctestrun launch: {shell_join(redacted_build_command)}")
    build_log_path = run_dir / "ios_build_for_testing.log"
    with build_log_path.open("w", encoding="utf-8") as log_file:
        build_result = subprocess.run(build_command, stdout=log_file, stderr=subprocess.STDOUT, text=True)
    build_log_text = build_log_path.read_text(encoding="utf-8", errors="replace").replace(development_team, "<redacted>")
    if build_result.returncode != 0:
        print("==> Physical iPhone build-for-testing failed; tail follows:", file=sys.stderr)
        print("\n".join(build_log_text.splitlines()[-60:]), file=sys.stderr)
        raise SystemExit(build_result.returncode)

    source_xctestrun = latest_built_xctestrun()
    xctestrun_path = source_xctestrun.with_name(f"{source_xctestrun.stem}-live-proof.xctestrun")
    shutil.copy2(source_xctestrun, xctestrun_path)
    with xctestrun_path.open("rb") as source_file:
        xctestrun = plistlib.load(source_file)
    test_targets = xctestrun.get("TestConfigurations", [{}])[0].get("TestTargets", [])
    ui_test_target = next(
        (target for target in test_targets if target.get("BlueprintName") == "ReferenceAppUITests"),
        None,
    )
    if ui_test_target is None:
        raise SystemExit("Could not find ReferenceAppUITests in generated .xctestrun file")
    runner_environment = {
        "MESHLINK_REFERENCE_LIVE_PROOF": "true",
        "MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY": storage_subdirectory,
        "MESHLINK_REFERENCE_APP_ID": app_id,
    }
    target_environment = {
        "MESHLINK_REFERENCE_AUTOMATION_MODE": AUTOMATION_MODE_LIVE_PROOF,
        "MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY": storage_subdirectory,
        "MESHLINK_REFERENCE_APP_ID": app_id,
        "MESHLINK_REFERENCE_AUTOMATION_ROLE": "sender",
    }
    ui_test_target.setdefault("EnvironmentVariables", {}).update(runner_environment)
    ui_test_target.setdefault("TestingEnvironmentVariables", {}).update(runner_environment)
    ui_test_target.setdefault("UITargetAppEnvironmentVariables", {}).update(target_environment)
    with xctestrun_path.open("wb") as destination_file:
        plistlib.dump(xctestrun, destination_file)

    command = [
        "xcodebuild",
        "test-without-building",
        "-xctestrun",
        str(xctestrun_path),
        "-destination",
        f"id={ios_device}",
        "-only-testing:ReferenceAppUITests/ReferenceAppPhysicalLiveProofUITests/testLiveProofSender",
    ]
    IOS_XCUITEST_LIVE_PROOF_CONFIG.write_text(
        json.dumps(
            {
                "appId": app_id,
                "storageSubdirectory": storage_subdirectory,
                "createdAtEpochMillis": int(time.time() * 1000),
            }
        ),
        encoding="utf-8",
    )
    print(f"==> Running physical iPhone sender UI test: {shell_join(command)}")
    log_path = run_dir / "ios_xcodebuild.log"
    try:
        with log_path.open("w", encoding="utf-8") as log_file:
            result = subprocess.run(command, stdout=log_file, stderr=subprocess.STDOUT, text=True)
    finally:
        IOS_XCUITEST_LIVE_PROOF_CONFIG.unlink(missing_ok=True)
    log_text = log_path.read_text(encoding="utf-8", errors="replace").replace(development_team, "<redacted>")
    if IOS_XCUITEST_SKIPPED_MARKER in log_text:
        raise SystemExit(
            "Physical iPhone sender UI test was skipped instead of executed. "
            "The live-proof opt-in did not reach the XCTest runner."
        )
    if result.returncode != 0:
        print("==> Physical iPhone sender UI test failed; tail follows:", file=sys.stderr)
        print("\n".join(log_text.splitlines()[-60:]), file=sys.stderr)
        if "maximum number of installed apps using a free developer profile" in log_text:
            raise SystemExit(
                "Physical iPhone sender UI test failed because the device is out of free developer-profile app slots. "
                "Re-run with --cleanup-ios-dev-app-slots or manually uninstall older MeshLink dev apps."
            )
        raise SystemExit(result.returncode)


def install_android_app(android_serial: str) -> None:
    env = os.environ.copy()
    env["ANDROID_SERIAL"] = android_serial
    command = ["./gradlew", ":meshlink-reference:installDebug", "--console=plain"]
    print(f"==> Installing Android reference app: {shell_join(command)}")
    run(command, env=env)


def android_sdk_int(android_serial: str) -> int:
    result = run(
        ["adb", "-s", android_serial, "shell", "getprop", "ro.build.version.sdk"],
        capture_output=True,
    )
    return int(result.stdout.strip())


def android_runtime_permissions(android_serial: str) -> list[str]:
    return (
        [
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.ACCESS_FINE_LOCATION",
        ]
        if android_sdk_int(android_serial) >= 31
        else ["android.permission.ACCESS_FINE_LOCATION"]
    )


def verify_android_runtime_permissions(android_serial: str) -> None:
    result = run(
        ["adb", "-s", android_serial, "shell", "dumpsys", "package", ANDROID_PACKAGE],
        capture_output=True,
    )
    package_dump = result.stdout
    missing_permissions = []
    for permission in android_runtime_permissions(android_serial):
        if f"{permission}: granted=true" not in package_dump:
            missing_permissions.append(permission)
    if missing_permissions:
        raise SystemExit(
            "Android runtime permissions are not granted after installDebug: "
            + ", ".join(missing_permissions)
        )
    print(
        "==> Verified Android runtime permissions: "
        + ", ".join(permission.split(".")[-1] for permission in android_runtime_permissions(android_serial))
    )


def start_ios_app_via_devicectl(
    *,
    ios_device: str,
    app_id: str,
    storage_subdirectory: str,
    run_dir: Path,
) -> BackgroundProcess:
    command = [
        "xcrun",
        "devicectl",
        "device",
        "process",
        "launch",
        "--device",
        ios_device,
        "--terminate-existing",
        "--console",
        "-e",
        json.dumps(
            {
                "MESHLINK_REFERENCE_AUTOMATION_MODE": AUTOMATION_MODE_LIVE_PROOF,
                "MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY": storage_subdirectory,
                "MESHLINK_REFERENCE_APP_ID": app_id,
                "MESHLINK_REFERENCE_AUTOMATION_ROLE": "sender",
            }
        ),
        IOS_BUNDLE_ID,
    ]
    print("==> Launching physical iPhone sender reference app via devicectl")
    log_file = (run_dir / "iphone_console.log").open("w", encoding="utf-8")
    process = subprocess.Popen(
        command,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return BackgroundProcess(process, cleanup_actions=[log_file.close])


def verify_ios_sender_log(log_path: Path) -> str:
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    required_markers = [
        "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=SENDER",
        "REFERENCE_AUTOMATION peer.discovered role=SENDER",
        "REFERENCE_AUTOMATION send.requested role=sender",
    ]
    for marker in required_markers:
        if marker not in log_text:
            raise SystemExit(f"Expected iPhone sender log to contain '{marker}'")
    completion_line = next(
        (line.strip() for line in log_text.splitlines() if IOS_SENDER_MARKER in line),
        None,
    )
    return completion_line or "REFERENCE_AUTOMATION send.requested role=sender"


def start_android_app(run_dir: Path, android_serial: str, app_id: str, storage_subdirectory: str) -> BackgroundProcess:
    run(["adb", "-s", android_serial, "shell", "am", "force-stop", ANDROID_PACKAGE], check=False)
    run(["adb", "-s", android_serial, "logcat", "-c"])
    logcat_path = run_dir / "android_logcat.log"
    logcat_file = logcat_path.open("w", encoding="utf-8")
    logcat = subprocess.Popen(
        ["adb", "-s", android_serial, "logcat", *ANDROID_AUTOMATION_LOG_TAGS],
        stdout=logcat_file,
        stderr=subprocess.STDOUT,
        text=True,
    )
    command = [
        "adb",
        "-s",
        android_serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        ANDROID_ACTIVITY,
        "--ez",
        ANDROID_EXTRA_UI_AUTOMATION,
        "true",
        "--es",
        ANDROID_EXTRA_MODE,
        AUTOMATION_MODE_LIVE_PROOF,
        "--es",
        ANDROID_EXTRA_STORAGE,
        storage_subdirectory,
        "--es",
        ANDROID_EXTRA_APP_ID,
        app_id,
        "--es",
        ANDROID_EXTRA_ROLE,
        "passive",
    ]
    print(f"==> Launching Android passive reference app: {shell_join(command)}")
    start_output = run(command, capture_output=True)
    (run_dir / "android_start.txt").write_text(start_output.stdout + start_output.stderr, encoding="utf-8")
    return BackgroundProcess(logcat, cleanup_actions=[logcat_file.close])


def wait_for_android_completion(run_dir: Path, timeout_seconds: float) -> tuple[str, str]:
    log_path = run_dir / "android_logcat.log"
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if log_path.exists():
            text = log_path.read_text(encoding="utf-8", errors="replace")
            for line in text.splitlines():
                match = ANDROID_PROOF_COMPLETE_PATTERN.search(line)
                if match is not None:
                    return line, match.group("export")
        time.sleep(0.5)
    raise SystemExit("Android passive reference app did not reach proof.complete within the timeout")


def read_android_app_file(android_serial: str, relative_path: str) -> str:
    command = [
        "adb",
        "-s",
        android_serial,
        "exec-out",
        "run-as",
        ANDROID_PACKAGE,
        "cat",
        f"files/{relative_path}",
    ]
    result = run(command, capture_output=True)
    return result.stdout


def summarize_and_verify(
    *,
    android_serial: str,
    run_dir: Path,
    storage_subdirectory: str,
    android_completion_line: str,
    ios_completion: str,
    export_relative_path: str,
) -> None:
    history_relative_path = f"live-automation/{storage_subdirectory}/reference/history.json"
    export_relative_file = f"live-automation/{storage_subdirectory}/{export_relative_path}"
    history_json = read_android_app_file(android_serial, history_relative_path)
    export_json = read_android_app_file(android_serial, export_relative_file)
    (run_dir / "android_history.json").write_text(history_json, encoding="utf-8")
    (run_dir / "android_export.json").write_text(export_json, encoding="utf-8")

    checks = [
        ('"historyStatus": "RETAINED"', history_json),
        ('"defaultMode": "redacted-preview"', export_json),
        ('"fullPayloadIncluded": false', export_json),
        ('"operatorOptInRecorded": false', export_json),
    ]
    for needle, haystack in checks:
        if needle not in haystack:
            raise SystemExit(f"Expected '{needle}' in retained export evidence")
    if '"fullPayload":' in export_json:
        raise SystemExit("Redacted export unexpectedly included fullPayload")

    summary = {
        "android_completion": android_completion_line,
        "ios_completion": ios_completion,
        "export_relative_path": export_relative_path,
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("==> Android completion:", android_completion_line)
    print("==> iPhone completion:", ios_completion)
    print("==> Android export:", export_relative_path)


def main() -> int:
    args = parse_args()
    ensure_android_device_ready(args.android_serial)

    run_dir = Path(args.run_dir or f"/tmp/reference_live_proof_{timestamp()}")
    run_dir.mkdir(parents=True, exist_ok=True)
    app_id = args.app_id or f"demo.meshlink.reference.live.{timestamp()}"
    storage_subdirectory = run_dir.name.replace("/", "_")

    if not args.skip_android_install:
        install_android_app(args.android_serial)
    verify_android_runtime_permissions(args.android_serial)

    ios_app_path = None
    if args.ios_launch_mode == "devicectl":
        ios_app_path = latest_built_app() if args.skip_ios_build else build_ios_app(args.ios_device, run_dir)
        if not args.skip_ios_install:
            install_ios_app(args.ios_device, ios_app_path)

    android_logcat = start_android_app(run_dir, args.android_serial, app_id, storage_subdirectory)
    ios_console_process: BackgroundProcess | None = None

    try:
        print(f"==> Waiting {args.android_ready_seconds} seconds for Android passive initialization")
        time.sleep(args.android_ready_seconds)
        if args.ios_launch_mode == "xcuitest":
            if args.cleanup_ios_dev_app_slots:
                cleanup_ios_dev_app_slots(args.ios_device)
            run_ios_live_proof_test(
                ios_device=args.ios_device,
                app_id=app_id,
                storage_subdirectory=storage_subdirectory,
                run_dir=run_dir,
            )
            ios_completion = "xcodebuild test passed"
        else:
            ios_console_process = start_ios_app_via_devicectl(
                ios_device=args.ios_device,
                app_id=app_id,
                storage_subdirectory=storage_subdirectory,
                run_dir=run_dir,
            )
        android_completion_line, export_relative_path = wait_for_android_completion(
            run_dir,
            args.capture_timeout_seconds,
        )
        if args.ios_launch_mode == "devicectl":
            time.sleep(args.post_result_idle_seconds)
            ios_completion = verify_ios_sender_log(run_dir / "iphone_console.log")
    finally:
        if ios_console_process is not None:
            ios_console_process.stop()
        android_logcat.stop()
        run(["adb", "-s", args.android_serial, "shell", "am", "force-stop", ANDROID_PACKAGE], check=False)

    summarize_and_verify(
        android_serial=args.android_serial,
        run_dir=run_dir,
        storage_subdirectory=storage_subdirectory,
        android_completion_line=android_completion_line,
        ios_completion=ios_completion,
        export_relative_path=export_relative_path,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
