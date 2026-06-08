#!/usr/bin/env python3

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from run_headless_reference_live_proof import (
    ANDROID_ACTIVITY,
    ANDROID_AUTOMATION_LOG_TAGS,
    ANDROID_EXTRA_SCENARIO,
    ANDROID_PACKAGE,
    AUTOMATION_MODE_LIVE_PROOF,
    IOS_AUTOMATION_SCENARIO,
    BackgroundProcess,
    DEFAULT_ANDROID_READY_SECONDS,
    DEFAULT_CAPTURE_TIMEOUT_SECONDS,
    DEFAULT_POST_RESULT_IDLE_SECONDS,
    IOS_BUNDLE_ID,
    build_ios_app,
    ensure_android_device_ready,
    install_android_app,
    install_ios_app,
    read_android_app_file,
    run,
    shell_join,
    timestamp,
    verify_android_runtime_permissions,
)

DEFAULT_APP_ID_PREFIX = "demo.meshlink.reference.relay"
RELAY_SCENARIO = "relay-constrained"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run a retained A=iPhone 15 sender, B=Samsung relay, C=OPPO passive relay proof for "
            "the MeshLink reference app."
        )
    )
    parser.add_argument("--ios-device", required=True, help="Physical iPhone UDID for devicectl")
    parser.add_argument(
        "--relay-android-serial",
        required=True,
        help="ADB serial of the Android relay device (B = Samsung)",
    )
    parser.add_argument(
        "--passive-android-serial",
        required=True,
        help="ADB serial of the Android passive recipient (C = OPPO)",
    )
    parser.add_argument(
        "--app-id",
        help=f"Shared MeshLink app ID. Defaults to {DEFAULT_APP_ID_PREFIX}.<timestamp>",
    )
    parser.add_argument(
        "--run-dir",
        help="Directory for retained logs and artifacts. Defaults to /tmp/reference_relay_proof_<timestamp>",
    )
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=DEFAULT_ANDROID_READY_SECONDS,
        help="How long to wait after the Android peers launch before starting the iPhone sender",
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=DEFAULT_CAPTURE_TIMEOUT_SECONDS,
        help="Hard timeout for sender, relay, and passive completion capture",
    )
    parser.add_argument(
        "--post-result-idle-seconds",
        type=float,
        default=DEFAULT_POST_RESULT_IDLE_SECONDS,
        help="How long to keep the iPhone console open after the sender proof marker",
    )
    parser.add_argument("--skip-android-install", action="store_true")
    parser.add_argument("--skip-ios-build", action="store_true")
    parser.add_argument("--skip-ios-install", action="store_true")
    return parser.parse_args()


def fetch_android_identity_preferences_xml(android_serial: str, app_id: str) -> str:
    preferences_name = f"meshlink-{app_id}.xml"
    command = [
        "adb",
        "-s",
        android_serial,
        "exec-out",
        "run-as",
        ANDROID_PACKAGE,
        "cat",
        f"shared_prefs/{preferences_name}",
    ]
    result = run(command, capture_output=True)
    return result.stdout


def wait_for_android_identity_preferences(
    *,
    android_serial: str,
    app_id: str,
    run_dir: Path,
    label: str,
    timeout_seconds: float,
) -> tuple[str, str]:
    deadline = time.monotonic() + timeout_seconds
    last_error: str | None = None
    while time.monotonic() < deadline:
        try:
            xml_text = fetch_android_identity_preferences_xml(android_serial, app_id)
            peer_id = derive_peer_id_from_identity_preferences(xml_text, app_id)
            (run_dir / f"{label}_identity_prefs.xml").write_text(xml_text, encoding="utf-8")
            return peer_id, xml_text
        except Exception as error:  # pragma: no cover - best-effort polling wrapper
            last_error = str(error)
            time.sleep(0.5)
    raise SystemExit(
        f"Timed out waiting for {label} identity preferences for appId={app_id}. Last error: {last_error}"
    )


def derive_peer_id_from_identity_preferences(xml_text: str, app_id: str) -> str:
    root = ET.fromstring(xml_text)
    ed25519_public: bytes | None = None
    x25519_public: bytes | None = None
    for node in root.findall("string"):
        key = node.attrib.get("name", "")
        value = node.text or ""
        if key == f"identity:{app_id}:ed25519-public":
            ed25519_public = base64.b64decode(value)
        elif key == f"identity:{app_id}:x25519-public":
            x25519_public = base64.b64decode(value)
    if ed25519_public is None or x25519_public is None:
        raise ValueError("Missing stored public keys in Android identity preferences")
    # The routed automation target is the 24-hex advertisement peer ID, not the
    # longer 40-hex full peer identity. The runtime derives it from the first
    # 12 bytes of the public-key hash.
    return hashlib.sha256(ed25519_public + x25519_public).digest()[:12].hex()


def start_android_role_app(
    *,
    run_dir: Path,
    android_serial: str,
    label: str,
    app_id: str,
    storage_subdirectory: str,
    role: str,
) -> BackgroundProcess:
    run(["adb", "-s", android_serial, "shell", "am", "force-stop", ANDROID_PACKAGE], check=False)
    run(["adb", "-s", android_serial, "logcat", "-c"])
    log_path = run_dir / f"{label}_logcat.log"
    log_file = log_path.open("w", encoding="utf-8")
    logcat_process = subprocess.Popen(
        ["adb", "-s", android_serial, "logcat", *ANDROID_AUTOMATION_LOG_TAGS],
        stdout=log_file,
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
        "ch.trancee.meshlink.reference.extra.UI_AUTOMATION",
        "true",
        "--es",
        "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_MODE",
        AUTOMATION_MODE_LIVE_PROOF,
        "--es",
        "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_STORAGE_SUBDIRECTORY",
        storage_subdirectory,
        "--es",
        "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_APP_ID",
        app_id,
        "--es",
        "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_ROLE",
        role,
        "--es",
        ANDROID_EXTRA_SCENARIO,
        RELAY_SCENARIO,
    ]
    print(f"==> Launching {label} Android reference app ({role}): {shell_join(command)}")
    start_output = run(command, capture_output=True)
    (run_dir / f"{label}_start.txt").write_text(
        start_output.stdout + start_output.stderr,
        encoding="utf-8",
    )
    return BackgroundProcess(logcat_process, cleanup_actions=[log_file.close])


def start_ios_sender_app(
    *,
    ios_device: str,
    app_id: str,
    storage_subdirectory: str,
    target_peer_id: str,
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
                "MESHLINK_REFERENCE_AUTOMATION_TARGET_PEER_ID": target_peer_id,
                IOS_AUTOMATION_SCENARIO: RELAY_SCENARIO,
            }
        ),
        IOS_BUNDLE_ID,
    ]
    print("==> Launching physical iPhone sender reference app for constrained relay proof")
    log_file = (run_dir / "iphone_console.log").open("w", encoding="utf-8")
    process = subprocess.Popen(
        command,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return BackgroundProcess(process, cleanup_actions=[log_file.close])


def wait_for_line(log_path: Path, needle: str, timeout_seconds: float) -> str:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if log_path.exists():
            for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
                if needle in line:
                    return line.strip()
        time.sleep(0.5)
    raise SystemExit(f"Timed out waiting for '{needle}' in {log_path}")


def verify_ios_sender_relay_log(log_path: Path) -> str:
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    required_markers = [
        "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=SENDER",
        f"scenario={RELAY_SCENARIO}",
        "REFERENCE_AUTOMATION bootstrap.requested role=sender",
        "REFERENCE_AUTOMATION send.requested role=sender",
        "REFERENCE_AUTOMATION proof.complete role=sender",
    ]
    for marker in required_markers:
        if marker not in log_text:
            raise SystemExit(f"Expected iPhone relay sender log to contain '{marker}'")
    completion_line = next(
        (line.strip() for line in log_text.splitlines() if "REFERENCE_AUTOMATION proof.complete role=sender" in line),
        None,
    )
    if completion_line is None:
        raise SystemExit("Missing sender proof completion line in iPhone relay log")
    if "routeIsDirect=false" not in completion_line:
        raise SystemExit(
            "The sender proof completed on a direct route instead of a routed one. Reposition the devices until A can only see B, C can only see B, and the sender proof line itself contains routeIsDirect=false."
        )
    if "REFERENCE_AUTOMATION proof.failed role=sender" in log_text:
        raise SystemExit("Relay sender log contains proof.failed")
    return completion_line


def verify_relay_log(log_path: Path) -> dict[str, bool]:
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    return {
        "forward_queued": "forward.message.queued" in log_text,
        "forward_delivered": "forward.message.delivered" in log_text,
        "temporary_peer_promoted": "promoted temporary peer" in log_text,
        "no_session": "transport.data.noSession" in log_text,
    }


def summarize_and_verify_relay(
    *,
    passive_android_serial: str,
    run_dir: Path,
    passive_storage_subdirectory: str,
    passive_completion_line: str,
    sender_completion_line: str,
    relay_observations: dict[str, bool],
    target_peer_id: str,
    passive_label: str,
    relay_label: str,
) -> None:
    export_relative_path = extract_export_path(passive_completion_line)
    if export_relative_path is None:
        raise SystemExit("Passive relay completion line did not expose an export path")

    history_relative_path = f"live-automation/{passive_storage_subdirectory}/reference/history.json"
    export_relative_file = f"live-automation/{passive_storage_subdirectory}/{export_relative_path}"
    history_json = read_android_app_file(passive_android_serial, history_relative_path)
    export_json = read_android_app_file(passive_android_serial, export_relative_file)
    (run_dir / f"{passive_label}_history.json").write_text(history_json, encoding="utf-8")
    (run_dir / f"{passive_label}_export.json").write_text(export_json, encoding="utf-8")

    checks = [
        ('"historyStatus": "RETAINED"', history_json),
        ('"defaultMode": "redacted-preview"', export_json),
        ('"fullPayloadIncluded": false', export_json),
        ('"operatorOptInRecorded": false', export_json),
    ]
    for needle, haystack in checks:
        if needle not in haystack:
            raise SystemExit(f"Expected '{needle}' in retained relay export evidence")
    if '"fullPayload":' in export_json:
        raise SystemExit("Redacted relay export unexpectedly included fullPayload")
    if not relay_observations["forward_queued"] or not relay_observations["forward_delivered"]:
        raise SystemExit("Relay run never logged both forward.message.queued and forward.message.delivered")
    if relay_observations["no_session"]:
        raise SystemExit("Relay run hit transport.data.noSession")

    summary = {
        "scenario": RELAY_SCENARIO,
        "ios_completion": sender_completion_line,
        "passive_completion": passive_completion_line,
        "relay_forward_queued": relay_observations["forward_queued"],
        "relay_forward_delivered": relay_observations["forward_delivered"],
        "relay_temporary_peer_promoted": relay_observations["temporary_peer_promoted"],
        "target_peer_id": target_peer_id,
        "export_relative_path": export_relative_path,
        "passive_label": passive_label,
        "relay_label": relay_label,
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("==> iPhone completion:", sender_completion_line)
    print("==> Passive completion:", passive_completion_line)
    print("==> Relay queued delivery:", relay_observations["forward_queued"])
    print("==> Relay forwarded delivery:", relay_observations["forward_delivered"])
    print("==> Passive export:", export_relative_path)


def extract_export_path(passive_completion_line: str) -> str | None:
    import re

    match = re.search(r"export=(?P<export>\S+)", passive_completion_line)
    return match.group("export") if match is not None else None


def main() -> int:
    args = parse_args()
    ensure_android_device_ready(args.relay_android_serial)
    ensure_android_device_ready(args.passive_android_serial)
    if args.relay_android_serial == args.passive_android_serial:
        raise SystemExit("Relay and passive Android serials must be different physical devices")

    run_dir = Path(args.run_dir or f"/tmp/reference_relay_proof_{timestamp()}")
    run_dir.mkdir(parents=True, exist_ok=True)
    run_token = timestamp()
    app_id = args.app_id or f"{DEFAULT_APP_ID_PREFIX}.{run_token}"

    passive_label = "oppo"
    relay_label = "samsung"
    passive_storage = f"{run_token}-{passive_label}"
    relay_storage = f"{run_token}-{relay_label}"
    iphone_storage = f"{run_token}-iphone"

    if not args.skip_android_install:
        install_android_app(args.passive_android_serial, run_dir)
        install_android_app(args.relay_android_serial, run_dir)
    verify_android_runtime_permissions(args.passive_android_serial)
    verify_android_runtime_permissions(args.relay_android_serial)

    ios_app_path = None
    if not args.skip_ios_build:
        ios_app_path = build_ios_app(args.ios_device, run_dir)
    if ios_app_path is None:
        from run_headless_reference_live_proof import latest_built_app

        ios_app_path = latest_built_app()
    if not args.skip_ios_install:
        install_ios_app(args.ios_device, ios_app_path)

    passive_process = start_android_role_app(
        run_dir=run_dir,
        android_serial=args.passive_android_serial,
        label=passive_label,
        app_id=app_id,
        storage_subdirectory=passive_storage,
        role="passive",
    )
    relay_process: BackgroundProcess | None = None
    ios_process: BackgroundProcess | None = None

    try:
        passive_peer_id, _ = wait_for_android_identity_preferences(
            android_serial=args.passive_android_serial,
            app_id=app_id,
            run_dir=run_dir,
            label=passive_label,
            timeout_seconds=30,
        )
        print(f"==> Derived passive advertisement peer ID ({passive_label}): {passive_peer_id}")

        relay_process = start_android_role_app(
            run_dir=run_dir,
            android_serial=args.relay_android_serial,
            label=relay_label,
            app_id=app_id,
            storage_subdirectory=relay_storage,
            role="relay",
        )
        wait_for_android_identity_preferences(
            android_serial=args.relay_android_serial,
            app_id=app_id,
            run_dir=run_dir,
            label=relay_label,
            timeout_seconds=30,
        )

        print(
            f"==> Waiting {args.android_ready_seconds} seconds for passive and relay initialization"
        )
        time.sleep(args.android_ready_seconds)

        ios_process = start_ios_sender_app(
            ios_device=args.ios_device,
            app_id=app_id,
            storage_subdirectory=iphone_storage,
            target_peer_id=passive_peer_id,
            run_dir=run_dir,
        )
        wait_for_line(
            run_dir / "iphone_console.log",
            "REFERENCE_AUTOMATION proof.complete role=sender",
            args.capture_timeout_seconds,
        )
        time.sleep(args.post_result_idle_seconds)
        sender_completion_line = verify_ios_sender_relay_log(run_dir / "iphone_console.log")
        passive_completion_line = wait_for_line(
            run_dir / f"{passive_label}_logcat.log",
            "REFERENCE_AUTOMATION proof.complete role=passive",
            args.capture_timeout_seconds,
        )
        relay_observations = verify_relay_log(run_dir / f"{relay_label}_logcat.log")
    finally:
        if ios_process is not None:
            ios_process.stop()
        if relay_process is not None:
            relay_process.stop()
        passive_process.stop()
        run(
            ["adb", "-s", args.passive_android_serial, "shell", "am", "force-stop", ANDROID_PACKAGE],
            check=False,
        )
        run(
            ["adb", "-s", args.relay_android_serial, "shell", "am", "force-stop", ANDROID_PACKAGE],
            check=False,
        )

    summarize_and_verify_relay(
        passive_android_serial=args.passive_android_serial,
        run_dir=run_dir,
        passive_storage_subdirectory=passive_storage,
        passive_completion_line=passive_completion_line,
        sender_completion_line=sender_completion_line,
        relay_observations=relay_observations,
        target_peer_id=passive_peer_id,
        passive_label=passive_label,
        relay_label=relay_label,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
