#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from functools import lru_cache
from pathlib import Path
from typing import Any

from run_headless_reference_live_proof import shell_join, timestamp

PROOF_SCRIPT = Path("meshlink-reference/scripts/run_headless_reference_android_direct_proof.py")
TARGET_PEER = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_TARGET_PEER_ID"
DEFAULT_ANDROID_READY_SECONDS = 20.0
DEFAULT_CAPTURE_TIMEOUT_SECONDS = 30.0
DEFAULT_PAIR_TIMEOUT_SECONDS = 300.0
DEFAULT_MIN_ANDROID_API_LEVEL = 33
DEFAULT_FALLBACK_TRANSPORT = "gatt"
DEFAULT_PRIMARY_TRANSPORT = "meshlink"

PAIRS = [
    {"label": "a065_nam_lx9", "sender": "1f1dad34", "passive": "2ASVB21B09005117"},
    {"label": "a065_xcover", "sender": "1f1dad34", "passive": "42004386e43c8589"},
    {"label": "a065_mi_note3", "sender": "1f1dad34", "passive": "42c2cf"},
    {"label": "a065_cph2359", "sender": "1f1dad34", "passive": "EQUGS85LJNEIO7Z5"},
    {"label": "a065_e940", "sender": "1f1dad34", "passive": "GX6CTR500184"},
    {"label": "nam_lx9_a065", "sender": "2ASVB21B09005117", "passive": "1f1dad34"},
    {"label": "nam_lx9_xcover", "sender": "2ASVB21B09005117", "passive": "42004386e43c8589"},
    {"label": "nam_lx9_mi_note3", "sender": "2ASVB21B09005117", "passive": "42c2cf"},
    {"label": "nam_lx9_cph2359", "sender": "2ASVB21B09005117", "passive": "EQUGS85LJNEIO7Z5"},
    {"label": "nam_lx9_e940", "sender": "2ASVB21B09005117", "passive": "GX6CTR500184"},
    {"label": "xcover_a065", "sender": "42004386e43c8589", "passive": "1f1dad34"},
    {"label": "xcover_nam_lx9", "sender": "42004386e43c8589", "passive": "2ASVB21B09005117"},
    {"label": "xcover_mi_note3", "sender": "42004386e43c8589", "passive": "42c2cf"},
    {"label": "xcover_cph2359", "sender": "42004386e43c8589", "passive": "EQUGS85LJNEIO7Z5"},
    {"label": "xcover_e940", "sender": "42004386e43c8589", "passive": "GX6CTR500184"},
    {"label": "mi_note3_a065", "sender": "42c2cf", "passive": "1f1dad34"},
    {"label": "mi_note3_nam_lx9", "sender": "42c2cf", "passive": "2ASVB21B09005117"},
    {"label": "mi_note3_xcover", "sender": "42c2cf", "passive": "42004386e43c8589"},
    {"label": "mi_note3_cph2359", "sender": "42c2cf", "passive": "EQUGS85LJNEIO7Z5"},
    {"label": "mi_note3_e940", "sender": "42c2cf", "passive": "GX6CTR500184"},
    {"label": "cph2359_a065", "sender": "EQUGS85LJNEIO7Z5", "passive": "1f1dad34"},
    {"label": "cph2359_nam_lx9", "sender": "EQUGS85LJNEIO7Z5", "passive": "2ASVB21B09005117"},
    {"label": "cph2359_xcover", "sender": "EQUGS85LJNEIO7Z5", "passive": "42004386e43c8589"},
    {"label": "cph2359_mi_note3", "sender": "EQUGS85LJNEIO7Z5", "passive": "42c2cf"},
    {"label": "cph2359_e940", "sender": "EQUGS85LJNEIO7Z5", "passive": "GX6CTR500184"},
    {"label": "e940_a065", "sender": "GX6CTR500184", "passive": "1f1dad34"},
    {"label": "e940_nam_lx9", "sender": "GX6CTR500184", "passive": "2ASVB21B09005117"},
    {"label": "e940_xcover", "sender": "GX6CTR500184", "passive": "42004386e43c8589"},
    {"label": "e940_mi_note3", "sender": "GX6CTR500184", "passive": "42c2cf"},
    {"label": "e940_cph2359", "sender": "GX6CTR500184", "passive": "EQUGS85LJNEIO7Z5"},
]

ANDROID_MODELS = {
    "1f1dad34": "A065",
    "2ASVB21B09005117": "NAM-LX9",
    "42004386e43c8589": "SM-G390F",
    "42c2cf": "Mi Note 3",
    "EQUGS85LJNEIO7Z5": "CPH2359",
    "GX6CTR500184": "E940-2849-00",
}


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run the directed Android sender→passive direct-proof matrix with checkpointed progress."
        )
    )
    parser.add_argument("--run-root", help="Directory for the checkpointed matrix run")
    parser.add_argument(
        "--sender-passive-limit",
        type=int,
        help="Optional cap on the number of directed pairs to run for a partial sweep",
    )
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=DEFAULT_ANDROID_READY_SECONDS,
        help="Passive startup wait per pair",
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=DEFAULT_CAPTURE_TIMEOUT_SECONDS,
        help="Proof completion timeout per pair",
    )
    parser.add_argument(
        "--pair-timeout-seconds",
        type=float,
        default=DEFAULT_PAIR_TIMEOUT_SECONDS,
        help="Outer timeout per pair so a hung proof script cannot stall the whole sweep",
    )
    parser.add_argument(
        "--min-android-api-level",
        type=int,
        default=DEFAULT_MIN_ANDROID_API_LEVEL,
        help="Android API level below which a directed pair falls back to GATT transport",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume from the last checkpoint if the run root already contains progress",
    )
    return parser.parse_args(argv)


def adb_devices() -> list[str]:
    result = subprocess.run(["adb", "devices"], check=True, capture_output=True, text=True)
    devices: list[str] = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


@lru_cache(maxsize=None)
def adb_device_api_level(serial: str) -> int | None:
    try:
        result = subprocess.run(
            ["adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"],
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError:
        return None
    raw_value = result.stdout.strip()
    if not raw_value:
        return None
    try:
        return int(raw_value)
    except ValueError:
        return None


def select_pair_transport(
    sender_api_level: int | None,
    passive_api_level: int | None,
    *,
    fallback_android_api_level: int,
) -> tuple[str, dict[str, Any] | None]:
    if sender_api_level is None or passive_api_level is None:
        return DEFAULT_PRIMARY_TRANSPORT, None
    if sender_api_level < fallback_android_api_level or passive_api_level < fallback_android_api_level:
        return DEFAULT_FALLBACK_TRANSPORT, {
            "senderApiLevel": sender_api_level,
            "passiveApiLevel": passive_api_level,
            "reason": (
                f"android API below {fallback_android_api_level}; using {DEFAULT_FALLBACK_TRANSPORT.upper()} fallback"
            ),
        }
    return DEFAULT_PRIMARY_TRANSPORT, None


def run_pair(
    *,
    sender: str,
    passive: str,
    app_id: str,
    run_dir: Path,
    target_peer_id: str | None,
    capture_timeout: float,
    android_ready_seconds: float,
    pair_timeout_seconds: float,
    skip_install: bool,
    passive_benchmark_transport: str = DEFAULT_PRIMARY_TRANSPORT,
) -> dict[str, Any]:
    command = [
        sys.executable,
        str(PROOF_SCRIPT),
        "--sender-android-serial",
        sender,
        "--passive-android-serial",
        passive,
        "--app-id",
        app_id,
        "--run-dir",
        str(run_dir),
        "--android-ready-seconds",
        str(android_ready_seconds),
        "--capture-timeout-seconds",
        str(capture_timeout),
        "--advertisement-carrier",
        "uuid-pair-plus-service-data",
    ]
    if passive_benchmark_transport != DEFAULT_PRIMARY_TRANSPORT:
        command.extend(["--passive-benchmark-transport", passive_benchmark_transport])
    if skip_install:
        command.append("--skip-android-install")
    if target_peer_id is not None:
        command.extend(["--target-peer-id", target_peer_id])

    print(f"==> Running: {shell_join(command)}", flush=True)
    started_at = time.monotonic()
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=pair_timeout_seconds)
    except subprocess.TimeoutExpired as error:
        elapsed = round(time.monotonic() - started_at, 1)
        stdout_value = getattr(error, "stdout", None) or getattr(error, "output", None) or ""
        stderr_value = getattr(error, "stderr", None) or ""
        stdout_tail = stdout_value.strip()[-1000:] if isinstance(stdout_value, str) else ""
        stderr_tail = stderr_value.strip()[-1000:] if isinstance(stderr_value, str) else ""
        stage = "preflight" if not skip_install else "capture"
        reason = f"{stage} timed out after {pair_timeout_seconds:.1f}s"
        return {
            "status": "failed",
            "failureStage": stage,
            "failureReason": reason,
            "routeStage": None,
            "routeEvidence": None,
            "senderRouteStage": None,
            "passiveRouteStage": None,
            "timings": {"totalSeconds": elapsed, "pairTimeoutSeconds": pair_timeout_seconds},
            "htmlReportPath": None,
            "stdoutTail": stdout_tail,
            "stderrTail": stderr_tail,
            "elapsedSeconds": elapsed,
            "exitCode": 124,
            "timedOut": True,
            "timeoutSeconds": pair_timeout_seconds,
        }
    elapsed = round(time.monotonic() - started_at, 1)
    summary_path = run_dir / "summary.json"
    summary: dict[str, Any]
    if summary_path.exists():
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
    else:
        summary = {
            "status": "failed",
            "failureStage": "no-summary",
            "failureReason": (completed.stderr or completed.stdout or "").strip(),
        }
    return {
        "status": summary.get("status"),
        "failureStage": summary.get("failureStage"),
        "failureReason": summary.get("failureReason"),
        "routeStage": summary.get("routeStage"),
        "routeEvidence": summary.get("routeEvidence"),
        "senderRouteStage": summary.get("senderRouteStage"),
        "passiveRouteStage": summary.get("passiveRouteStage"),
        "timings": summary.get("timings"),
        "htmlReportPath": summary.get("htmlReportPath"),
        "stdoutTail": (completed.stdout or "")[-1000:],
        "stderrTail": (completed.stderr or "")[-1000:],
        "elapsedSeconds": elapsed,
        "exitCode": completed.returncode,
    }


def read_passive_peer_id(serial: str, app_id: str, retries: int = 60, delay_s: float = 1.0) -> str | None:
    xml_path = f"shared_prefs/meshlink-{app_id}.xml"
    for _ in range(retries):
        result = subprocess.run(
            ["adb", "-s", serial, "shell", "run-as", "ch.trancee.meshlink.reference", "cat", xml_path],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode == 0 and "x25519-public" in result.stdout:
            try:
                root = ET.fromstring(result.stdout)
            except ET.ParseError:
                time.sleep(delay_s)
                continue
            for item in root.findall(".//map/string"):
                if item.get("name") == TARGET_PEER and item.text:
                    return item.text.strip()
        time.sleep(delay_s)
    print(f"==> Passive peer id unavailable for {serial}; continuing without a seeded target peer")
    return None


def compact_status(summary: dict[str, Any]) -> dict[str, Any]:
    return {
        "status": summary.get("status"),
        "failureStage": summary.get("failureStage"),
        "failureReason": summary.get("failureReason"),
        "senderCompletion": summary.get("senderCompletion"),
        "passiveCompletion": summary.get("passiveCompletion"),
        "timings": summary.get("timings"),
    }


def render_compact_report(results: list[dict[str, Any]]) -> str:
    passing_pairs = [
        f"| {row['senderModel']} | {row['passiveModel']} | passed |"
        for row in results
        if row["final"]["status"] == "passed"
    ]
    failure_counts: dict[str, dict[str, int]] = {}
    for row in results:
        device = row["senderModel"]
        final = row["final"]
        reason = final.get("failureReason") or ""
        stage = final.get("failureStage") or "passed"
        if final["status"] == "passed":
            bucket = "passed"
        elif stage == "preflight" or "install" in reason.lower():
            bucket = "preflight/install"
        elif stage == "launch" or "launch" in reason.lower():
            bucket = "launch timeout"
        else:
            bucket = "capture/route stall"
        failure_counts.setdefault(device, {})
        failure_counts[device][bucket] = failure_counts[device].get(bucket, 0) + 1
    top_failure_rows = []
    for device, buckets in failure_counts.items():
        top_bucket = max(buckets.items(), key=lambda item: item[1])
        top_failure_rows.append(f"| {device} | {top_bucket[0]} | {top_bucket[1]} |")
    report = [
        "# Android direct-proof matrix report",
        "",
        "## Passing pairs",
        "",
        "| Sender | Passive | Result |",
        "|---|---|---|",
        *passing_pairs,
        "",
        "## Most common failure reason per device",
        "",
        "| Device | Most common failure reason | Count |",
        "|---|---|---|",
        *top_failure_rows,
        "",
    ]
    return "\n".join(report)


def load_progress(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return json.loads(path.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    devices = set(adb_devices())
    available_pairs = [pair for pair in PAIRS if pair["sender"] in devices and pair["passive"] in devices]
    if args.sender_passive_limit is not None:
        available_pairs = available_pairs[: args.sender_passive_limit]
    if not available_pairs:
        raise SystemExit("No directed Android pairs are available")

    run_root = Path(args.run_root or f"/tmp/meshlink_android_matrix_{timestamp()}")
    run_root.mkdir(parents=True, exist_ok=True)
    progress_path = run_root / "progress.json"
    results_path = run_root / "matrix-results.json"
    state_path = run_root / "state.json"

    results = load_progress(progress_path) if args.resume else []
    completed = {(row["sender"], row["passive"]) for row in results}

    for index, pair in enumerate(available_pairs, 1):
        if (pair["sender"], pair["passive"]) in completed:
            print(f"==> Skipping completed pair {pair['label']}", flush=True)
            continue
        app_id = f"demo.meshlink.reference.android-direct.{pair['label']}"
        initial_dir = run_root / f"{index:02d}_{pair['label']}_initial"
        final_dir = run_root / f"{index:02d}_{pair['label']}_final"
        initial_dir.mkdir(parents=True, exist_ok=True)
        final_dir.mkdir(parents=True, exist_ok=True)

        sender_api_level = adb_device_api_level(pair["sender"])
        passive_api_level = adb_device_api_level(pair["passive"])
        passive_benchmark_transport, fallback_reason = select_pair_transport(
            sender_api_level,
            passive_api_level,
            fallback_android_api_level=args.min_android_api_level,
        )
        if fallback_reason is not None:
            print(
                "==> Pair "
                f"{pair['label']} uses {passive_benchmark_transport.upper()} fallback: {fallback_reason['reason']} "
                f"(senderApiLevel={fallback_reason['senderApiLevel']} passiveApiLevel={fallback_reason['passiveApiLevel']})",
                flush=True,
            )

        print(f"==> Pair {index}/{len(available_pairs)} {pair['label']}", flush=True)
        initial = run_pair(
            sender=pair["sender"],
            passive=pair["passive"],
            app_id=app_id,
            run_dir=initial_dir,
            target_peer_id=None,
            capture_timeout=args.capture_timeout_seconds,
            android_ready_seconds=args.android_ready_seconds,
            pair_timeout_seconds=args.pair_timeout_seconds,
            skip_install=False,
            passive_benchmark_transport=passive_benchmark_transport,
        )
        target_peer_id = read_passive_peer_id(pair["passive"], app_id)
        final = run_pair(
            sender=pair["sender"],
            passive=pair["passive"],
            app_id=app_id,
            run_dir=final_dir,
            target_peer_id=target_peer_id,
            capture_timeout=args.capture_timeout_seconds,
            android_ready_seconds=args.android_ready_seconds,
            pair_timeout_seconds=args.pair_timeout_seconds,
            skip_install=True,
            passive_benchmark_transport=passive_benchmark_transport,
        )
        row = {
            "label": pair["label"],
            "sender": pair["sender"],
            "passive": pair["passive"],
            "senderModel": ANDROID_MODELS.get(pair["sender"], pair["sender"]),
            "passiveModel": ANDROID_MODELS.get(pair["passive"], pair["passive"]),
            "appId": app_id,
            "targetPeerId": target_peer_id,
            "initial": compact_status(initial),
            "final": compact_status(final),
            "initialRunDir": str(initial_dir),
            "finalRunDir": str(final_dir),
        }
        results.append(row)
        completed.add((pair["sender"], pair["passive"]))
        progress_path.write_text(json.dumps(results, indent=2), encoding="utf-8")
        results_path.write_text(json.dumps(results, indent=2), encoding="utf-8")
        state_path.write_text(
            json.dumps(
                {
                    "runRoot": str(run_root),
                    "totalPairs": len(available_pairs),
                    "completedPairs": len(results),
                    "pendingPairs": len(available_pairs) - len(results),
                    "lastPair": pair,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        print(
            f"    initial {initial['status']} ({initial.get('failureStage')}) -> final {final['status']} ({final.get('failureStage')})",
            flush=True,
        )

    compact_report_path = run_root / "matrix-report.md"
    compact_report_path.write_text(render_compact_report(results), encoding="utf-8")
    print(f"==> Wrote {results_path}", flush=True)
    print(f"==> Wrote {compact_report_path}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
