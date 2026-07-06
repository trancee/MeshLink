#!/usr/bin/env python3
"""Run the Android MeshLink proof-app benchmark across every attached ADB device.

Unlike run_headless_meshlink_benchmark.py (which pairs exactly one Android
receiver with one iPhone sender via xcrun devicectl), this script only needs
adb: it launches the Android proof app as a forced initiator/sender on one
device and as a passive receiver on another, using the same
`meshlink.benchmarkPayloadBytes` / `meshlink.forceInitiator` /
`meshlink.disableAutoSend` launch extras documented in
meshlink-proof/android/README.md. That lets it fan out across every device
currently attached to the host instead of requiring a fixed Android+iOS pair.

Every run is appended to a durable JSON Lines ledger
(meshlink-benchmark/fleet-results/ledger.jsonl by default) and a regenerated
Markdown summary (latest-summary.md) so results survive across sessions.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

ANDROID_PACKAGE = "ch.trancee.meshlink.proof.android"
ANDROID_ACTIVITY = f"{ANDROID_PACKAGE}/.MainActivity"
DEFAULT_LOGCAT_TAGS = ["MeshLinkReferenceAutomation:I", "MeshLinkProof:I", "*:S"]
DEFAULT_READY_SECONDS = 8.0
DEFAULT_CAPTURE_TIMEOUT_SECONDS = 120.0
DEFAULT_POST_RESULT_IDLE_SECONDS = 5.0
DEFAULT_RECEIPT_GRACE_SECONDS = 15.0

ADB_DEVICE_PATTERN = re.compile(r"^(?P<serial>\S+)\s+(?P<state>device|offline|unauthorized)\b")
BENCHMARK_RESULT_PATTERN = re.compile(
    r"BENCHMARK transport bytes=(?P<bytes>\d+) elapsedMs=(?P<elapsed_ms>\d+) "
    r"throughputKBps=(?P<throughput>[0-9]+(?:\.[0-9]+)?) result=(?P<result>\S+)"
)

REPO_ROOT = Path(__file__).resolve().parents[2]
CATALOG_PATH = REPO_ROOT / "scripts" / "device-test-matrix.catalog.json"
DEFAULT_RESULTS_DIR = REPO_ROOT / "meshlink-benchmark" / "fleet-results"


def run(command: list[str], *, check: bool = True, capture_output: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=check, capture_output=capture_output, text=True)


def getprop(serial: str, prop: str) -> str:
    result = run(["adb", "-s", serial, "shell", "getprop", prop], check=False)
    return result.stdout.strip()


@dataclass
class Device:
    serial: str
    manufacturer: str
    model: str
    hardware_serial: str
    friendly_name: str


def load_catalog_names() -> dict[str, str]:
    if not CATALOG_PATH.exists():
        return {}

    def norm(value: str) -> str:
        return re.sub(r"[^a-z0-9]+", "", value.lower())

    catalog = json.loads(CATALOG_PATH.read_text())
    return {norm(entry["model"]): entry["device"] for entry in catalog}


def adb_attached_serials() -> list[str]:
    result = run(["adb", "devices"])
    serials = []
    for line in result.stdout.splitlines():
        match = ADB_DEVICE_PATTERN.match(line.strip())
        if match and match.group("state") == "device":
            serials.append(match.group("serial"))
    return serials


def describe_device(serial: str, catalog_names: dict[str, str]) -> Device:
    manufacturer = getprop(serial, "ro.product.manufacturer")
    model = getprop(serial, "ro.product.model")
    hardware_serial = getprop(serial, "ro.serialno") or serial
    key = re.sub(r"[^a-z0-9]+", "", model.lower())
    friendly = catalog_names.get(key, f"{manufacturer} {model}".strip())
    return Device(
        serial=serial,
        manufacturer=manufacturer,
        model=model,
        hardware_serial=hardware_serial,
        friendly_name=friendly,
    )


def discover_devices(explicit_serials: list[str] | None) -> list[Device]:
    catalog_names = load_catalog_names()
    serials = explicit_serials if explicit_serials else adb_attached_serials()
    devices = [describe_device(serial, catalog_names) for serial in serials]

    # Prefer a single (USB-first) transport per physical device: the same
    # phone can show up multiple times over USB, direct wifi, and mDNS.
    seen: dict[str, Device] = {}
    for device in devices:
        existing = seen.get(device.hardware_serial)
        if existing is None:
            seen[device.hardware_serial] = device
            continue
        prefer_current = "." not in device.serial and ":" not in device.serial
        prefer_existing = "." not in existing.serial and ":" not in existing.serial
        if prefer_current and not prefer_existing:
            seen[device.hardware_serial] = device
    return list(seen.values())


def build_pairs(devices: list[Device], mode: str) -> list[tuple[Device, Device]]:
    if len(devices) < 2:
        raise SystemExit(f"Need at least 2 attached devices to benchmark a pair; found {len(devices)}")
    if mode == "ring":
        return [(devices[i], devices[(i + 1) % len(devices)]) for i in range(len(devices))]
    if mode == "all-pairs":
        return [(a, b) for a in devices for b in devices if a.hardware_serial != b.hardware_serial]
    raise SystemExit(f"Unknown pairing mode: {mode}")


@dataclass
class PairOutcome:
    sender: Device
    receiver: Device
    app_id: str
    run_dir: Path
    benchmark_line: str | None = None
    receipt_confirmed: bool = False
    error: str | None = None

    @property
    def throughput_kbps(self) -> float | None:
        if not self.benchmark_line:
            return None
        match = BENCHMARK_RESULT_PATTERN.search(self.benchmark_line)
        return float(match.group("throughput")) if match else None

    @property
    def result_label(self) -> str | None:
        if not self.benchmark_line:
            return None
        match = BENCHMARK_RESULT_PATTERN.search(self.benchmark_line)
        return match.group("result") if match else None

    def to_record(self) -> dict:
        return {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "app_id": self.app_id,
            "sender_serial": self.sender.serial,
            "sender_device": self.sender.friendly_name,
            "receiver_serial": self.receiver.serial,
            "receiver_device": self.receiver.friendly_name,
            "throughput_kbps": self.throughput_kbps,
            "result": self.result_label,
            "receipt_confirmed": self.receipt_confirmed,
            "run_dir": str(self.run_dir),
            "error": self.error,
        }


class LogcatCapture:
    def __init__(self, serial: str, log_path: Path) -> None:
        run(["adb", "-s", serial, "shell", "am", "force-stop", ANDROID_PACKAGE], check=False)
        run(["adb", "-s", serial, "logcat", "-c"], check=False)
        self.process = subprocess.Popen(
            ["adb", "-s", serial, "logcat", *DEFAULT_LOGCAT_TAGS],
            stdout=log_path.open("w", encoding="utf-8"),
            stderr=subprocess.STDOUT,
            text=True,
        )

    def stop(self) -> None:
        if self.process.poll() is not None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)


def launch_app(serial: str, app_id: str, *, force_initiator: bool, disable_auto_send: bool, payload_bytes: int | None) -> None:
    command = [
        "adb", "-s", serial, "shell", "am", "start", "-n", ANDROID_ACTIVITY,
        "--es", "meshlink.appId", app_id,
    ]
    if disable_auto_send:
        command += ["--ez", "meshlink.disableAutoSend", "true"]
    if force_initiator:
        command += ["--ez", "meshlink.forceInitiator", "true"]
    if payload_bytes is not None:
        command += ["--ei", "meshlink.benchmarkPayloadBytes", str(payload_bytes)]
    run(command, check=False)


def pull_proof_log(serial: str, run_dir: Path, filename: str) -> None:
    result = run(
        ["adb", "-s", serial, "exec-out", "run-as", ANDROID_PACKAGE, "cat", "files/proof.log"],
        check=False,
    )
    (run_dir / filename).write_text(result.stdout, encoding="utf-8")


def first_match(lines: list[str], needle: str) -> str | None:
    for line in lines:
        if needle in line:
            return line
    return None


def run_pair(
    sender: Device,
    receiver: Device,
    *,
    app_id: str,
    payload_bytes: int,
    run_dir: Path,
    ready_seconds: float,
    capture_timeout_seconds: float,
    post_result_idle_seconds: float,
    receipt_grace_seconds: float,
) -> PairOutcome:
    run_dir.mkdir(parents=True, exist_ok=True)
    outcome = PairOutcome(sender=sender, receiver=receiver, app_id=app_id, run_dir=run_dir)

    sender_log = run_dir / "sender_logcat.log"
    receiver_log = run_dir / "receiver_logcat.log"
    sender_capture: LogcatCapture | None = None
    receiver_capture: LogcatCapture | None = None

    try:
        sender_capture = LogcatCapture(sender.serial, sender_log)
        receiver_capture = LogcatCapture(receiver.serial, receiver_log)

        launch_app(receiver.serial, app_id, force_initiator=False, disable_auto_send=True, payload_bytes=None)
        print(f"==> Waiting {ready_seconds}s for receiver ({receiver.friendly_name}, {receiver.serial}) to be ready")
        time.sleep(ready_seconds)

        launch_app(sender.serial, app_id, force_initiator=True, disable_auto_send=False, payload_bytes=payload_bytes)
        print(f"==> Sender ({sender.friendly_name}, {sender.serial}) launched; watching for BENCHMARK transport line")

        deadline = time.monotonic() + capture_timeout_seconds
        while time.monotonic() < deadline:
            lines = sender_log.read_text(encoding="utf-8", errors="replace").splitlines()
            match = first_match(lines, "BENCHMARK transport bytes=")
            if match:
                outcome.benchmark_line = match
                break
            time.sleep(1)

        if outcome.benchmark_line is None:
            outcome.error = "timed out waiting for BENCHMARK transport line"
        else:
            time.sleep(min(post_result_idle_seconds + receipt_grace_seconds, capture_timeout_seconds))
            lines = sender_log.read_text(encoding="utf-8", errors="replace").splitlines()
            outcome.receipt_confirmed = first_match(lines, "BENCHMARK receipt confirmed") is not None
    except Exception as error:  # noqa: BLE001 - retain evidence even on unexpected failure
        outcome.error = str(error)
    finally:
        if sender_capture is not None:
            sender_capture.stop()
        if receiver_capture is not None:
            receiver_capture.stop()
        pull_proof_log(sender.serial, run_dir, "sender_proof.log")
        pull_proof_log(receiver.serial, run_dir, "receiver_proof.log")
        run(["adb", "-s", sender.serial, "shell", "am", "force-stop", ANDROID_PACKAGE], check=False)
        run(["adb", "-s", receiver.serial, "shell", "am", "force-stop", ANDROID_PACKAGE], check=False)

    (run_dir / "meta.json").write_text(json.dumps(outcome.to_record(), indent=2), encoding="utf-8")
    return outcome


def append_ledger(results_dir: Path, records: list[dict]) -> Path:
    ledger_path = results_dir / "ledger.jsonl"
    with ledger_path.open("a", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record) + "\n")
    return ledger_path


def rewrite_summary(results_dir: Path, *, max_rows: int = 200) -> Path:
    ledger_path = results_dir / "ledger.jsonl"
    summary_path = results_dir / "latest-summary.md"
    records = []
    if ledger_path.exists():
        for line in ledger_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                records.append(json.loads(line))
    records.sort(key=lambda record: record["timestamp"], reverse=True)

    lines = [
        "# MeshLink fleet benchmark results",
        "",
        "Generated by `meshlink-benchmark/scripts/run_fleet_meshlink_benchmark.py`.",
        "Do not hand-edit; rerun the script to refresh this file.",
        "",
        "| Timestamp (UTC) | Sender | Receiver | Payload result | Throughput (KB/s) | Receipt confirmed | Run dir |",
        "|---|---|---|---|---:|---|---|",
    ]
    for record in records[:max_rows]:
        throughput = f"{record['throughput_kbps']:.2f}" if record.get("throughput_kbps") is not None else "—"
        result = record.get("result") or (record.get("error") or "—")
        lines.append(
            f"| {record['timestamp']} | {record['sender_device']} (`{record['sender_serial']}`) "
            f"| {record['receiver_device']} (`{record['receiver_serial']}`) | {result} | {throughput} "
            f"| {'yes' if record.get('receipt_confirmed') else 'no'} | `{record['run_dir']}` |"
        )
    summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return summary_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run the Android MeshLink proof-app throughput benchmark across every attached "
            "ADB device by pairing them as sender/receiver, with no iOS device required."
        )
    )
    parser.add_argument("--payload-bytes", type=int, required=True, help="Benchmark payload size in bytes")
    parser.add_argument(
        "--serials",
        help="Comma-separated ADB serials to restrict the fleet to. Defaults to every attached device in 'device' state.",
    )
    parser.add_argument(
        "--pairing",
        choices=["ring", "all-pairs"],
        default="ring",
        help="'ring' pairs each device with the next one (N runs total); 'all-pairs' runs every ordered pair (N*(N-1) runs).",
    )
    parser.add_argument("--repeat", type=int, default=1, help="How many times to repeat the full pairing plan")
    parser.add_argument("--app-id", help="Shared appId for the run. Defaults to fleet.meshlink.benchmark.<timestamp>.")
    parser.add_argument("--results-dir", default=str(DEFAULT_RESULTS_DIR), help="Directory for the persistent ledger and run evidence")
    parser.add_argument("--ready-seconds", type=float, default=DEFAULT_READY_SECONDS)
    parser.add_argument("--capture-timeout-seconds", type=float, default=DEFAULT_CAPTURE_TIMEOUT_SECONDS)
    parser.add_argument("--post-result-idle-seconds", type=float, default=DEFAULT_POST_RESULT_IDLE_SECONDS)
    parser.add_argument("--receipt-grace-seconds", type=float, default=DEFAULT_RECEIPT_GRACE_SECONDS)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Only discover devices and print the planned pairing; do not launch anything.",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Actually run the benchmark pairs. Required in addition to omitting --dry-run, as a safety gate "
        "before driving BLE traffic across every attached device.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    explicit_serials = args.serials.split(",") if args.serials else None
    devices = discover_devices(explicit_serials)

    print(f"==> Discovered {len(devices)} distinct attached device(s):")
    for device in devices:
        print(f"    {device.serial}  {device.friendly_name}  ({device.manufacturer} {device.model})")

    pairs = build_pairs(devices, args.pairing)
    print(f"==> Pairing mode '{args.pairing}' plans {len(pairs)} run(s) per repeat, {args.repeat} repeat(s):")
    for sender, receiver in pairs:
        print(f"    sender={sender.friendly_name} ({sender.serial}) -> receiver={receiver.friendly_name} ({receiver.serial})")

    if args.dry_run:
        print("==> --dry-run set; not launching any app.")
        return 0

    if not args.execute:
        raise SystemExit(
            "Refusing to launch BLE traffic on every attached device without --execute. "
            "Re-run with --execute once the planned pairing above looks correct (or narrow scope with --serials)."
        )

    results_dir = Path(args.results_dir)
    results_dir.mkdir(parents=True, exist_ok=True)
    base_app_id = args.app_id or f"fleet.meshlink.benchmark.{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"

    all_outcomes: list[PairOutcome] = []
    for repeat_index in range(1, args.repeat + 1):
        for pair_index, (sender, receiver) in enumerate(pairs, start=1):
            app_id = base_app_id if args.repeat == 1 else f"{base_app_id}.r{repeat_index}"
            run_dir = results_dir / "runs" / app_id.replace(".", "_") / f"{pair_index:02d}_{sender.hardware_serial}_to_{receiver.hardware_serial}"
            print(f"\n=== Repeat {repeat_index}/{args.repeat}, pair {pair_index}/{len(pairs)} ===")
            outcome = run_pair(
                sender,
                receiver,
                app_id=app_id,
                payload_bytes=args.payload_bytes,
                run_dir=run_dir,
                ready_seconds=args.ready_seconds,
                capture_timeout_seconds=args.capture_timeout_seconds,
                post_result_idle_seconds=args.post_result_idle_seconds,
                receipt_grace_seconds=args.receipt_grace_seconds,
            )
            print(f"==> Result: {outcome.result_label or outcome.error or 'unknown'} "
                  f"throughputKBps={outcome.throughput_kbps if outcome.throughput_kbps is not None else 'missing'} "
                  f"receiptConfirmed={outcome.receipt_confirmed}")
            all_outcomes.append(outcome)
            # Persist after every pair so a mid-run interruption (Ctrl-C, device
            # disconnect, adb daemon restart) still leaves a durable record of
            # every pair that already completed.
            append_ledger(results_dir, [outcome.to_record()])
            rewrite_summary(results_dir)

    ledger_path = results_dir / "ledger.jsonl"
    summary_path = results_dir / "latest-summary.md"

    successful = [o for o in all_outcomes if o.benchmark_line is not None]
    print(f"\n==> {len(successful)}/{len(all_outcomes)} runs observed a scored benchmark line")
    print(f"==> Ledger: {ledger_path}")
    print(f"==> Summary: {summary_path}")
    return 0 if len(successful) == len(all_outcomes) else 1


if __name__ == "__main__":
    sys.exit(main())
