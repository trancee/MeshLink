#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from run_headless_reference_live_proof import (
    ANDROID_ACTIVITY,
    ANDROID_EXTRA_APP_ID,
    ANDROID_EXTRA_MODE,
    ANDROID_EXTRA_ROLE,
    ANDROID_EXTRA_SCENARIO,
    ANDROID_EXTRA_STORAGE,
    ANDROID_EXTRA_UI_AUTOMATION,
    ANDROID_PROOF_COMPLETE_PATTERN,
    AUTOMATION_MODE_LIVE_PROOF,
    BackgroundProcess,
    DEFAULT_ANDROID_READY_SECONDS,
    DEFAULT_CAPTURE_TIMEOUT_SECONDS,
    android_logcat_tags,
    ensure_android_device_ready,
    force_stop_reference_app,
    install_android_app,
    read_android_app_file,
    run,
    shell_join,
    timestamp,
    verify_android_runtime_permissions,
)

DEFAULT_APP_ID_PREFIX = "demo.meshlink.reference.android-direct"
DIRECT_GUIDED_SCENARIO = "direct-guided"
ANDROID_START_TIMEOUT_SECONDS = 15.0
SENDER_LOGCAT_NAME = "sender_logcat.log"
PASSIVE_LOGCAT_NAME = "passive_logcat.log"
SENDER_START_NAME = "sender_start.txt"
PASSIVE_START_NAME = "passive_start.txt"
ANDROID_HISTORY_NAME = "android_history.json"
ANDROID_EXPORT_NAME = "android_export.json"
SENDER_PROOF_COMPLETE_NEEDLE = "REFERENCE_AUTOMATION proof.complete role=sender"
SENDER_PROOF_FAILED_NEEDLE = "REFERENCE_AUTOMATION proof.failed role=sender"
PASSIVE_PROOF_COMPLETE_NEEDLE = "REFERENCE_AUTOMATION proof.complete role=passive"
SENDER_REQUIRED_LOG_MARKERS = [
    "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=SENDER",
    f"scenario={DIRECT_GUIDED_SCENARIO}",
    "REFERENCE_AUTOMATION peer.discovered role=SENDER",
    "REFERENCE_AUTOMATION send.requested role=sender",
]
PASSIVE_REQUIRED_LOG_MARKERS = [
    "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE",
    f"scenario={DIRECT_GUIDED_SCENARIO}",
    "REFERENCE_AUTOMATION peer.discovered role=PASSIVE",
    "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview",
]
TRANSPORT_REQUIRED_MARKERS = [
    "D MeshLinkTransport: start()",
    "D MeshLinkTransport: refreshDiscoveryState started=true suspended=false scanner=true advertiser=true",
    "D MeshLinkTransport: scan started",
    "D MeshLinkTransport: advertising started",
]


@dataclass
class AndroidDirectCompletions:
    sender_completion: str | None
    passive_completion: str | None
    export_relative_path: str | None
    sender_failed: str | None


@dataclass(frozen=True)
class RoleArtifacts:
    logcat_name: str
    start_name: str


ROLE_ARTIFACTS = {
    "sender": RoleArtifacts(logcat_name=SENDER_LOGCAT_NAME, start_name=SENDER_START_NAME),
    "passive": RoleArtifacts(logcat_name=PASSIVE_LOGCAT_NAME, start_name=PASSIVE_START_NAME),
}


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run a retained Android-only MeshLink reference-app direct proof with an Android sender "
            "and Android passive peer. The live-proof path starts a foreground service plus wake lock "
            "during automation to reduce doze risk on aggressive OEM builds."
        )
    )
    parser.add_argument(
        "--sender-android-serial",
        required=True,
        help="ADB serial of the Android sender device",
    )
    parser.add_argument(
        "--passive-android-serial",
        required=True,
        help="ADB serial of the Android passive device",
    )
    parser.add_argument(
        "--app-id",
        help=f"Shared MeshLink app ID. Defaults to {DEFAULT_APP_ID_PREFIX}.<timestamp>",
    )
    parser.add_argument(
        "--run-dir",
        help="Directory for retained logs and artifacts. Defaults to /tmp/reference_android_direct_proof_<timestamp>",
    )
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=DEFAULT_ANDROID_READY_SECONDS,
        help=(
            "How long to wait after launching the passive Android peer before starting the sender. "
            "Treat the selected sender/passive pair as part of the environment contract; some attached devices advertise and scan but never discover each other. "
            "On this host, the stable pair that completed direct proof was Spacewar (sender) + DN2103 (passive), while Nokia X20 + DN2103 repeatedly failed to discover until the screen stayed awake. "
            "The Nokia X20 runs Android 14 and was observed in Dozing with quick-doze enabled, so keep the screen awake, disable battery optimization if discovery stalls, and rely on the live-proof foreground wake-lock mitigation when the reference app starts it."
        ),
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=DEFAULT_CAPTURE_TIMEOUT_SECONDS,
        help="Hard timeout for both Android roles to reach proof completion",
    )
    parser.add_argument(
        "--extra-force-stop-serial",
        action="append",
        default=[],
        help="Extra Android serials whose reference app should be force-stopped before and after the run",
    )
    parser.add_argument(
        "--android-transport-logcat",
        action="store_true",
        help="Also capture MeshLinkTransport Android logcat lines for transport diagnosis",
    )
    parser.add_argument(
        "--target-peer-id",
        help="Optional peer id to seed into the Android sender as a bootstrap hint when orchestration knows it",
    )
    parser.add_argument("--skip-android-install", action="store_true")
    return parser.parse_args(argv)


def evidence_paths(run_dir: Path) -> dict[str, str]:
    del run_dir
    return {
        "senderLogcat": SENDER_LOGCAT_NAME,
        "passiveLogcat": PASSIVE_LOGCAT_NAME,
        "senderStart": SENDER_START_NAME,
        "passiveStart": PASSIVE_START_NAME,
        "androidHistory": ANDROID_HISTORY_NAME,
        "androidExport": ANDROID_EXPORT_NAME,
    }


def captured_evidence(run_dir: Path) -> dict[str, bool]:
    manifest = evidence_paths(run_dir)
    return {
        key: (run_dir / relative_path).exists()
        for key, relative_path in manifest.items()
    }


def write_summary(run_dir: Path, payload: dict[str, Any]) -> None:
    (run_dir / "summary.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")


def read_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def sender_log_path(run_dir: Path) -> Path:
    return run_dir / SENDER_LOGCAT_NAME


def passive_log_path(run_dir: Path) -> Path:
    return run_dir / PASSIVE_LOGCAT_NAME


def extract_discovered_peer_id(log_text: str) -> str | None:
    for line in log_text.splitlines():
        if "REFERENCE_AUTOMATION peer.discovered role=PASSIVE" not in line:
            continue
        match = re.search(r"peer=([A-Za-z0-9._:-]+)", line)
        if match is not None:
            return match.group(1)
    return None


def extract_sender_completion(log_text: str) -> str | None:
    return next(
        (line.strip() for line in log_text.splitlines() if SENDER_PROOF_COMPLETE_NEEDLE in line),
        None,
    )


def extract_sender_failure(log_text: str) -> str | None:
    return next(
        (line.strip() for line in log_text.splitlines() if SENDER_PROOF_FAILED_NEEDLE in line),
        None,
    )


def extract_passive_completion(log_text: str) -> tuple[str | None, str | None]:
    for line in log_text.splitlines():
        if PASSIVE_PROOF_COMPLETE_NEEDLE not in line:
            continue
        match = ANDROID_PROOF_COMPLETE_PATTERN.search(line)
        if match is None:
            raise SystemExit("Passive proof.complete line is missing export path")
        return line.strip(), match.group("export")
    return None, None


def collect_android_completions(run_dir: Path) -> AndroidDirectCompletions:
    sender_log = read_text(sender_log_path(run_dir))
    passive_log = read_text(passive_log_path(run_dir))
    passive_completion, export_relative_path = extract_passive_completion(passive_log)
    return AndroidDirectCompletions(
        sender_completion=extract_sender_completion(sender_log),
        passive_completion=passive_completion,
        export_relative_path=export_relative_path,
        sender_failed=extract_sender_failure(sender_log),
    )


def collect_android_completions_best_effort(run_dir: Path) -> AndroidDirectCompletions:
    sender_log = read_text(sender_log_path(run_dir))
    passive_log = read_text(passive_log_path(run_dir))
    passive_completion: str | None = None
    export_relative_path: str | None = None
    for line in passive_log.splitlines():
        if PASSIVE_PROOF_COMPLETE_NEEDLE not in line:
            continue
        passive_completion = line.strip()
        match = ANDROID_PROOF_COMPLETE_PATTERN.search(line)
        export_relative_path = match.group("export") if match is not None else None
        break
    return AndroidDirectCompletions(
        sender_completion=extract_sender_completion(sender_log),
        passive_completion=passive_completion,
        export_relative_path=export_relative_path,
        sender_failed=extract_sender_failure(sender_log),
    )


def transport_failure_reason(run_dir: Path) -> str | None:
    sender_log = read_text(sender_log_path(run_dir))
    passive_log = read_text(passive_log_path(run_dir))
    combined_log = sender_log + "\n" + passive_log
    if "peer.discovered role=PASSIVE" in combined_log or "peer.discovered role=SENDER" in combined_log:
        return None
    if all(marker in combined_log for marker in TRANSPORT_REQUIRED_MARKERS):
        return (
            "Android transport reached scan/advertise startup but never emitted peer.discovered; "
            "direct proof cannot proceed without a retained discovery seed"
        )
    return None


def wait_for_android_completions(run_dir: Path, timeout_seconds: float) -> AndroidDirectCompletions:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        completions = collect_android_completions(run_dir)
        if completions.sender_failed is not None:
            raise SystemExit(
                "Android sender reported proof.failed before retained completion: "
                f"{completions.sender_failed}"
            )
        if completions.sender_completion and completions.passive_completion:
            return completions
        time.sleep(0.5)

    completions = collect_android_completions(run_dir)
    if completions.sender_failed is not None:
        raise SystemExit(
            "Android sender reported proof.failed before retained completion: "
            f"{completions.sender_failed}"
        )
    missing_roles: list[str] = []
    if completions.sender_completion is None:
        missing_roles.append("sender")
    if completions.passive_completion is None:
        missing_roles.append("passive")
    transport_reason = transport_failure_reason(run_dir)
    if transport_reason is not None:
        raise SystemExit(transport_reason)
    raise SystemExit(
        "Timed out waiting for proof.complete on Android roles: "
        + ", ".join(missing_roles or ["unknown"])
    )


def ensure_android_preflight(android_serial: str, *, skip_android_install: bool) -> None:
    ensure_android_device_ready(android_serial)
    if not skip_android_install:
        try:
            install_android_app(android_serial)
        except subprocess.CalledProcessError as error:
            raise SystemExit(
                f"Android app install failed for '{android_serial}' with exit code {error.returncode}"
            ) from error
    try:
        verify_android_runtime_permissions(android_serial)
    except SystemExit as error:
        raise SystemExit(
            f"Android runtime-permission verification failed for '{android_serial}': {error}"
        ) from error
    except subprocess.CalledProcessError as error:
        raise SystemExit(
            "Android runtime-permission verification command failed for "
            f"'{android_serial}' with exit code {error.returncode}"
        ) from error


def validate_serial_configuration(
    *,
    sender_android_serial: str,
    passive_android_serial: str,
    extra_force_stop_serials: list[str],
) -> None:
    if sender_android_serial == passive_android_serial:
        raise SystemExit(
            "Sender and passive Android serials must be different physical devices: "
            f"{sender_android_serial}"
        )

    reserved_serials = {
        sender_android_serial: "sender",
        passive_android_serial: "passive",
    }
    seen_extra_serials: set[str] = set()
    for extra_serial in extra_force_stop_serials:
        if extra_serial in reserved_serials:
            raise SystemExit(
                "Extra force-stop Android serial cannot reuse the "
                f"{reserved_serials[extra_serial]} device: {extra_serial}"
            )
        if extra_serial in seen_extra_serials:
            raise SystemExit(
                "Extra force-stop Android serials must be unique physical devices: "
                f"{extra_serial}"
            )
        seen_extra_serials.add(extra_serial)


def ensure_extra_force_stop_devices_ready(extra_force_stop_serials: list[str]) -> None:
    for extra_serial in extra_force_stop_serials:
        ensure_android_device_ready(extra_serial)


def force_stop_extra_peers(extra_force_stop_serials: list[str]) -> None:
    failures: list[str] = []
    for extra_serial in extra_force_stop_serials:
        try:
            force_stop_reference_app(extra_serial)
        except Exception as error:
            failures.append(f"{extra_serial}: {error}")
    if failures:
        raise SystemExit(
            "Failed to isolate extra Android peers before launch: " + "; ".join(failures)
        )


def cleanup_android_direct_run(
    *,
    sender_process: BackgroundProcess | None,
    passive_process: BackgroundProcess | None,
    sender_android_serial: str,
    passive_android_serial: str,
    extra_force_stop_serials: list[str],
) -> None:
    failures: list[str] = []
    for label, process in (("sender", sender_process), ("passive", passive_process)):
        if process is None:
            continue
        try:
            process.stop()
        except Exception as error:
            failures.append(f"{label} logcat cleanup failed: {error}")

    for android_serial in [
        sender_android_serial,
        passive_android_serial,
        *extra_force_stop_serials,
    ]:
        try:
            force_stop_reference_app(android_serial)
        except Exception as error:
            failures.append(f"{android_serial}: {error}")

    if failures:
        raise SystemExit("Android direct cleanup failed: " + "; ".join(failures))


def start_android_role_app(
    *,
    run_dir: Path,
    android_serial: str,
    label: str,
    role: str,
    app_id: str,
    storage_subdirectory: str,
    android_transport_logcat: bool,
    target_peer_id: str | None = None,
) -> BackgroundProcess:
    role_artifacts = ROLE_ARTIFACTS[label]
    force_stop_reference_app(android_serial)
    run(["adb", "-s", android_serial, "logcat", "-c"])
    logcat_path = run_dir / role_artifacts.logcat_name
    logcat_file = logcat_path.open("w", encoding="utf-8")
    logcat_process = subprocess.Popen(
        ["adb", "-s", android_serial, "logcat", *android_logcat_tags(android_transport_logcat)],
        stdout=logcat_file,
        stderr=subprocess.STDOUT,
        text=True,
    )
    process = BackgroundProcess(logcat_process, cleanup_actions=[logcat_file.close])
    command = [
        "adb",
        "-s",
        android_serial,
        "shell",
        "am",
        "start",
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
        role,
        "--es",
        ANDROID_EXTRA_SCENARIO,
        DIRECT_GUIDED_SCENARIO,
    ]
    if target_peer_id:
        command.extend(["--es", "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_TARGET_PEER_ID", target_peer_id])
    print(f"==> Launching {label} Android reference app ({role}): {shell_join(command)}")
    start_path = run_dir / role_artifacts.start_name
    try:
        start_output = run(
            command,
            capture_output=True,
            timeout=ANDROID_START_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as error:
        start_path.write_text(
            "Timed out waiting for Android activity launch to return.\n"
            f"Command: {shell_join(command)}\n"
            f"TimeoutSeconds: {ANDROID_START_TIMEOUT_SECONDS}\n",
            encoding="utf-8",
        )
        process.stop()
        raise SystemExit(
            f"{label} Android launch timed out after {ANDROID_START_TIMEOUT_SECONDS} seconds"
        ) from error
    except subprocess.CalledProcessError as error:
        start_path.write_text((error.stdout or "") + (error.stderr or ""), encoding="utf-8")
        process.stop()
        raise
    start_path.write_text(
        start_output.stdout + start_output.stderr,
        encoding="utf-8",
    )
    return process


def wait_for_discovered_peer_id(log_path: Path, timeout_seconds: float) -> str | None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        peer_id = extract_discovered_peer_id(read_text(log_path))
        if peer_id is not None:
            return peer_id
        time.sleep(0.5)
    return extract_discovered_peer_id(read_text(log_path))


def verify_sender_log(log_path: Path) -> str:
    log_text = read_text(log_path)
    if not log_text.strip():
        raise SystemExit("Sender log is empty; cannot trust Android-only direct proof completion")
    for marker in SENDER_REQUIRED_LOG_MARKERS:
        if marker not in log_text:
            raise SystemExit(f"Expected sender log to contain '{marker}'")
    completion_line = extract_sender_completion(log_text)
    if completion_line is None:
        raise SystemExit("Missing sender proof.complete line in sender log")
    if SENDER_PROOF_FAILED_NEEDLE in log_text:
        raise SystemExit("Sender log contains proof.failed")
    return completion_line


def verify_passive_log(log_path: Path) -> str:
    log_text = read_text(log_path)
    if not log_text.strip():
        raise SystemExit("Passive log is empty; cannot trust Android-only retained export evidence")
    for marker in PASSIVE_REQUIRED_LOG_MARKERS:
        if marker not in log_text:
            raise SystemExit(f"Expected passive log to contain '{marker}'")
    completion_line, export_relative_path = extract_passive_completion(log_text)
    if completion_line is None or export_relative_path is None:
        raise SystemExit("Missing passive proof.complete line in passive log")
    return completion_line


def summarize_and_verify(
    *,
    sender_android_serial: str,
    passive_android_serial: str,
    run_dir: Path,
    storage_subdirectory: str,
    app_id: str,
    completions: AndroidDirectCompletions,
) -> dict[str, Any]:
    sender_completion_line = verify_sender_log(sender_log_path(run_dir))
    passive_completion_line = verify_passive_log(passive_log_path(run_dir))
    if completions.export_relative_path is None:
        raise SystemExit("Passive retained completion is missing an export path")

    history_relative_path = f"live-automation/{storage_subdirectory}/reference/history.json"
    export_relative_file = f"live-automation/{storage_subdirectory}/{completions.export_relative_path}"
    history_json = read_android_app_file(passive_android_serial, history_relative_path)
    export_json = read_android_app_file(passive_android_serial, export_relative_file)
    (run_dir / ANDROID_HISTORY_NAME).write_text(history_json, encoding="utf-8")
    (run_dir / ANDROID_EXPORT_NAME).write_text(export_json, encoding="utf-8")

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
        "status": "passed",
        "scenario": DIRECT_GUIDED_SCENARIO,
        "appId": app_id,
        "storageSubdirectory": storage_subdirectory,
        "senderPlatform": "android",
        "passivePlatform": "android",
        "senderSerial": sender_android_serial,
        "passiveSerial": passive_android_serial,
        "senderCompletion": sender_completion_line,
        "passiveCompletion": passive_completion_line,
        "exportRelativePath": completions.export_relative_path,
        "evidence": evidence_paths(run_dir),
        "captured": captured_evidence(run_dir),
    }
    write_summary(run_dir, summary)
    print("==> Scenario:", DIRECT_GUIDED_SCENARIO)
    print("==> Android sender completion:", sender_completion_line)
    print("==> Android passive completion:", passive_completion_line)
    print("==> Android export:", completions.export_relative_path)
    return summary


def failure_summary(
    *,
    run_dir: Path,
    sender_android_serial: str,
    passive_android_serial: str,
    app_id: str,
    storage_subdirectory: str,
    stage: str,
    error_message: str,
    completions: AndroidDirectCompletions,
) -> dict[str, Any]:
    return {
        "status": "failed",
        "scenario": DIRECT_GUIDED_SCENARIO,
        "appId": app_id,
        "storageSubdirectory": storage_subdirectory,
        "senderPlatform": "android",
        "passivePlatform": "android",
        "senderSerial": sender_android_serial,
        "passiveSerial": passive_android_serial,
        "senderCompletion": completions.sender_completion,
        "passiveCompletion": completions.passive_completion,
        "senderFailure": completions.sender_failed,
        "exportRelativePath": completions.export_relative_path,
        "failureStage": stage,
        "failureReason": error_message,
        "evidence": evidence_paths(run_dir),
        "captured": captured_evidence(run_dir),
        "partialEvidenceAvailable": any(captured_evidence(run_dir).values()),
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    run_dir = Path(args.run_dir or f"/tmp/reference_android_direct_proof_{timestamp()}")
    run_dir.mkdir(parents=True, exist_ok=True)
    app_id = args.app_id or f"{DEFAULT_APP_ID_PREFIX}.{timestamp()}"
    storage_subdirectory = run_dir.name.replace("/", "_")
    discovery_wait_seconds = max(args.android_ready_seconds * 2, 20.0)
    discovered_peer_id: str | None = args.target_peer_id
    stage = "input-validation"
    sender_process: BackgroundProcess | None = None
    passive_process: BackgroundProcess | None = None
    completions = AndroidDirectCompletions(
        sender_completion=None,
        passive_completion=None,
        export_relative_path=None,
        sender_failed=None,
    )

    try:
        validate_serial_configuration(
            sender_android_serial=args.sender_android_serial,
            passive_android_serial=args.passive_android_serial,
            extra_force_stop_serials=args.extra_force_stop_serial,
        )

        stage = "preflight"
        ensure_android_preflight(
            args.passive_android_serial,
            skip_android_install=args.skip_android_install,
        )
        ensure_android_preflight(
            args.sender_android_serial,
            skip_android_install=args.skip_android_install,
        )
        ensure_extra_force_stop_devices_ready(args.extra_force_stop_serial)

        try:
            stage = "launch"
            force_stop_extra_peers(args.extra_force_stop_serial)
            passive_process = start_android_role_app(
                run_dir=run_dir,
                android_serial=args.passive_android_serial,
                label="passive",
                role="passive",
                app_id=app_id,
                storage_subdirectory=storage_subdirectory,
                android_transport_logcat=args.android_transport_logcat,
            )
            print(
                f"==> Waiting {args.android_ready_seconds} seconds for Android passive initialization"
            )
            time.sleep(args.android_ready_seconds)
            discovered_peer_id = discovered_peer_id or wait_for_discovered_peer_id(
                passive_log_path(run_dir),
                discovery_wait_seconds,
            )
            sender_process = start_android_role_app(
                run_dir=run_dir,
                android_serial=args.sender_android_serial,
                label="sender",
                role="sender",
                app_id=app_id,
                storage_subdirectory=storage_subdirectory,
                android_transport_logcat=args.android_transport_logcat,
                target_peer_id=discovered_peer_id,
            )
            stage = "capture"
            completions = wait_for_android_completions(run_dir, args.capture_timeout_seconds)
        finally:
            try:
                cleanup_android_direct_run(
                    sender_process=sender_process,
                    passive_process=passive_process,
                    sender_android_serial=args.sender_android_serial,
                    passive_android_serial=args.passive_android_serial,
                    extra_force_stop_serials=args.extra_force_stop_serial,
                )
            except SystemExit:
                stage = "cleanup"
                raise

        stage = "summary"
        summary = summarize_and_verify(
            sender_android_serial=args.sender_android_serial,
            passive_android_serial=args.passive_android_serial,
            run_dir=run_dir,
            storage_subdirectory=storage_subdirectory,
            app_id=app_id,
            completions=completions,
        )
        summary["discoveredPeerId"] = discovered_peer_id
        write_summary(run_dir, summary)
        print("==> Android discovery seed:", discovered_peer_id or "none")
        return 0
    except SystemExit as error:
        write_summary(
            run_dir,
            failure_summary(
                run_dir=run_dir,
                sender_android_serial=args.sender_android_serial,
                passive_android_serial=args.passive_android_serial,
                app_id=app_id,
                storage_subdirectory=storage_subdirectory,
                stage=stage,
                error_message=str(error),
                completions=collect_android_completions_best_effort(run_dir),
            ),
        )
        raise
    except Exception as error:
        write_summary(
            run_dir,
            failure_summary(
                run_dir=run_dir,
                sender_android_serial=args.sender_android_serial,
                passive_android_serial=args.passive_android_serial,
                app_id=app_id,
                storage_subdirectory=storage_subdirectory,
                stage=stage,
                error_message=str(error),
                completions=collect_android_completions_best_effort(run_dir),
            ),
        )
        raise


if __name__ == "__main__":
    raise SystemExit(main())
