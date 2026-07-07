#!/usr/bin/env python3
"""Run the Android MeshLink proof-app benchmark across every attached ADB device.

Unlike run_headless_meshlink_benchmark.py (which pairs exactly one Android
receiver with one iPhone sender via xcrun devicectl), this script only needs
adb. It supports two independent benchmark modes:

- "transport" (default): launches the Android proof app as a forced
  initiator/sender on one device and as a passive receiver on another, using
  the same `meshlink.benchmarkPayloadBytes` / `meshlink.forceInitiator` /
  `meshlink.disableAutoSend` launch extras documented in
  meshlink-proof/android/README.md. That lets it fan out across every device
  currently attached to the host instead of requiring a fixed Android+iOS
  pair.
- "crypto": times X25519/Ed25519/ChaCha20-Poly1305 primitives on real device
  silicon (no pairing needed) via the CryptoRuntimePerformanceDeviceTest
  instrumented test, one device at a time, so crypto performance can be
  compared across the heterogeneous device fleet documented in
  docs/reference/device-test-matrix.md.

Every run is appended to a durable JSON Lines ledger
(meshlink-benchmark/fleet-results/ledger.jsonl and/or crypto-ledger.jsonl by
default) and a regenerated Markdown summary so results survive across
sessions.
"""

from __future__ import annotations

import argparse
import json
import os
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

CRYPTO_TEST_CLASS = "ch.trancee.meshlink.platform.android.CryptoRuntimePerformanceDeviceTest"
CRYPTO_LOGCAT_TAGS = ["CryptoBenchmark:I", "*:S"]
CRYPTO_RESULT_PATTERN = re.compile(
    r"CRYPTO_BENCHMARK device=(?P<device>.+?) sdk=(?P<sdk>\d+) op=(?P<op>\S+) "
    r"iterations=(?P<iterations>\d+) totalMs=(?P<total_ms>[0-9]+(?:\.[0-9]+)?) "
    r"avgUs=(?P<avg_us>[0-9]+(?:\.[0-9]+)?) provider=(?P<provider>\S+)"
)
DEFAULT_CRYPTO_TIMEOUT_SECONDS = 300.0

REPO_ROOT = Path(__file__).resolve().parents[2]
CATALOG_PATH = REPO_ROOT / "scripts" / "device-test-matrix.catalog.json"
DEFAULT_RESULTS_DIR = REPO_ROOT / "meshlink-benchmark" / "fleet-results"
GRADLEW = REPO_ROOT / "gradlew"


def run(
    command: list[str],
    *,
    check: bool = True,
    capture_output: bool = True,
    env: dict[str, str] | None = None,
    timeout: float | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command, check=check, capture_output=capture_output, text=True, env=env, timeout=timeout
    )


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


def _success_rate_lines(records: list[dict], *, window: int = 20) -> list[str]:
    """Build a success-rate-alongside-throughput summary block.

    Reports overall and last-`window` receipt-confirmed rates, plus a
    per-sender/receiver-pair breakdown, so flaky reliability regressions are
    visible without hand-grepping the ledger.
    """
    if not records:
        return []

    def rate(subset: list[dict]) -> str:
        if not subset:
            return "—"
        confirmed = sum(1 for record in subset if record.get("receipt_confirmed"))
        return f"{confirmed}/{len(subset)} ({100.0 * confirmed / len(subset):.0f}%)"

    recent = records[:window]
    lines = [
        "## Receipt success rate",
        "",
        f"- All-time: {rate(records)} confirmed ({len(records)} runs)",
        f"- Last {window}: {rate(recent)} confirmed",
        "",
        "| Sender | Receiver | Success rate | Runs |",
        "|---|---|---|---:|",
    ]
    pairs: dict[tuple[str, str], list[dict]] = {}
    for record in records:
        key = (record.get("sender_device", "?"), record.get("receiver_device", "?"))
        pairs.setdefault(key, []).append(record)
    for (sender, receiver), subset in sorted(pairs.items()):
        lines.append(f"| {sender} | {receiver} | {rate(subset)} | {len(subset)} |")
    lines.append("")
    return lines


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
    ]
    lines.extend(_success_rate_lines(records))
    lines.extend([
        "| Timestamp (UTC) | Sender | Receiver | Payload result | Throughput (KB/s) | Receipt confirmed | Run dir |",
        "|---|---|---|---|---:|---|---|",
    ])
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


@dataclass
class CryptoOutcome:
    device: Device
    run_dir: Path
    ops: list[dict] = field(default_factory=list)
    error: str | None = None
    partial: bool = False

    def to_record(self) -> dict:
        return {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "serial": self.device.serial,
            "device": self.device.friendly_name,
            "ops": self.ops,
            "run_dir": str(self.run_dir),
            "error": self.error,
            "partial": self.partial,
        }


def run_crypto_benchmark(device: Device, *, run_dir: Path, timeout_seconds: float) -> CryptoOutcome:
    """Times X25519/Ed25519/ChaCha20-Poly1305 primitives on one device via the
    CryptoRuntimePerformanceDeviceTest instrumented test. No pairing needed."""
    run_dir.mkdir(parents=True, exist_ok=True)
    outcome = CryptoOutcome(device=device, run_dir=run_dir)
    log_path = run_dir / "crypto_logcat.log"

    run(["adb", "-s", device.serial, "logcat", "-c"], check=False)
    try:
        gradle_env = dict(os.environ, ANDROID_SERIAL=device.serial)
        gradle_result = run(
            [
                str(GRADLEW),
                ":meshlink:connectedAndroidDeviceTest",
                f"-Pandroid.testInstrumentationRunnerArguments.class={CRYPTO_TEST_CLASS}",
                "--console=plain",
            ],
            check=False,
            env=gradle_env,
            timeout=timeout_seconds,
        )
        (run_dir / "gradle_output.log").write_text(
            gradle_result.stdout + gradle_result.stderr, encoding="utf-8"
        )

        logcat_result = run(["adb", "-s", device.serial, "logcat", "-d", *CRYPTO_LOGCAT_TAGS], check=False)
        log_path.write_text(logcat_result.stdout, encoding="utf-8")

        for line in logcat_result.stdout.splitlines():
            match = CRYPTO_RESULT_PATTERN.search(line)
            if match:
                outcome.ops.append(
                    {
                        "op": match.group("op"),
                        "iterations": int(match.group("iterations")),
                        "total_ms": float(match.group("total_ms")),
                        "avg_us": float(match.group("avg_us")),
                        "provider": match.group("provider"),
                        "sdk": int(match.group("sdk")),
                    }
                )

        if gradle_result.returncode != 0:
            # The instrumented test can crash partway through (e.g. one op
            # unsupported on this device), leaving earlier CRYPTO_BENCHMARK
            # lines logged even though the overall Gradle run failed. Surface
            # that as a partial result rather than silently reporting success.
            outcome.partial = True
            outcome.error = (
                f"connectedAndroidDeviceTest failed (exit {gradle_result.returncode})"
                + (f"; observed {len(outcome.ops)} op result(s) before failure" if outcome.ops else "; no CRYPTO_BENCHMARK lines were observed")
            )
        elif not outcome.ops:
            outcome.partial = True
            outcome.error = "no CRYPTO_BENCHMARK lines observed in logcat despite a successful test run"
    except subprocess.TimeoutExpired:
        outcome.error = f"timed out after {timeout_seconds}s waiting for connectedAndroidDeviceTest"
    except Exception as error:  # noqa: BLE001 - retain evidence even on unexpected failure
        outcome.error = str(error)

    (run_dir / "meta.json").write_text(json.dumps(outcome.to_record(), indent=2), encoding="utf-8")
    return outcome


def append_crypto_ledger(results_dir: Path, records: list[dict]) -> Path:
    ledger_path = results_dir / "crypto-ledger.jsonl"
    with ledger_path.open("a", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record) + "\n")
    return ledger_path


def rewrite_crypto_summary(results_dir: Path, *, max_rows: int = 200) -> Path:
    ledger_path = results_dir / "crypto-ledger.jsonl"
    summary_path = results_dir / "crypto-latest-summary.md"
    records = []
    if ledger_path.exists():
        for line in ledger_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                records.append(json.loads(line))
    records.sort(key=lambda record: record["timestamp"], reverse=True)

    lines = [
        "# MeshLink fleet crypto benchmark results",
        "",
        "Generated by `meshlink-benchmark/scripts/run_fleet_meshlink_benchmark.py --mode crypto`.",
        "Do not hand-edit; rerun the script to refresh this file.",
        "",
        "| Timestamp (UTC) | Device | Op | Iterations | Avg (\u00b5s) | Provider | SDK | Run dir |",
        "|---|---|---|---:|---:|---|---:|---|",
    ]
    for record in records[:max_rows]:
        if record.get("error") and not record.get("ops"):
            lines.append(
                f"| {record['timestamp']} | {record['device']} (`{record['serial']}`) | \u2014 | \u2014 | \u2014 "
                f"| \u2014 | \u2014 | error: {record['error']} (`{record['run_dir']}`) |"
            )
            continue
        partial_suffix = f" (PARTIAL: {record['error']})" if record.get("partial") and record.get("error") else ""
        for op in record.get("ops", []):
            lines.append(
                f"| {record['timestamp']} | {record['device']} (`{record['serial']}`) | {op['op']}{partial_suffix} "
                f"| {op['iterations']} | {op['avg_us']:.2f} | {op['provider']} | {op['sdk']} "
                f"| `{record['run_dir']}` |"
            )
    summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return summary_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run the Android MeshLink proof-app benchmark across every attached ADB device: "
            "'transport' pairs devices as sender/receiver for BLE throughput, 'crypto' times "
            "crypto primitives on each device individually (no pairing, no iOS required either way)."
        )
    )
    parser.add_argument(
        "--mode",
        choices=["transport", "crypto"],
        default="transport",
        help="'transport' (default) benchmarks BLE throughput between paired devices; "
        "'crypto' times X25519/Ed25519/ChaCha20-Poly1305 on each device individually.",
    )
    parser.add_argument(
        "--payload-bytes",
        type=int,
        help="Benchmark payload size in bytes. Required when --mode transport.",
    )
    parser.add_argument(
        "--serials",
        help="Comma-separated ADB serials to restrict the fleet to. Defaults to every attached device in 'device' state.",
    )
    parser.add_argument(
        "--pairing",
        choices=["ring", "all-pairs"],
        default="ring",
        help="'ring' pairs each device with the next one (N runs total); 'all-pairs' runs every ordered pair (N*(N-1) runs). "
        "Only applies to --mode transport.",
    )
    parser.add_argument("--repeat", type=int, default=1, help="How many times to repeat the full pairing/device plan")
    parser.add_argument("--app-id", help="Shared appId for the run. Defaults to fleet.meshlink.benchmark.<timestamp>.")
    parser.add_argument("--results-dir", default=str(DEFAULT_RESULTS_DIR), help="Directory for the persistent ledger and run evidence")
    parser.add_argument("--ready-seconds", type=float, default=DEFAULT_READY_SECONDS)
    parser.add_argument("--capture-timeout-seconds", type=float, default=DEFAULT_CAPTURE_TIMEOUT_SECONDS)
    parser.add_argument("--post-result-idle-seconds", type=float, default=DEFAULT_POST_RESULT_IDLE_SECONDS)
    parser.add_argument("--receipt-grace-seconds", type=float, default=DEFAULT_RECEIPT_GRACE_SECONDS)
    parser.add_argument(
        "--crypto-timeout-seconds",
        type=float,
        default=DEFAULT_CRYPTO_TIMEOUT_SECONDS,
        help="Per-device timeout for the crypto instrumented test (includes Gradle/install overhead). Only applies to --mode crypto.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Only discover devices and print the planned pairing/device list; do not launch anything.",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Actually run the benchmark. Required in addition to omitting --dry-run, as a safety gate "
        "before driving BLE traffic or installing/running instrumented tests across every attached device.",
    )
    args = parser.parse_args()
    if args.mode == "transport" and args.payload_bytes is None:
        parser.error("--payload-bytes is required when --mode transport")
    return args


def main() -> int:
    args = parse_args()
    explicit_serials = args.serials.split(",") if args.serials else None
    devices = discover_devices(explicit_serials)

    print(f"==> Discovered {len(devices)} distinct attached device(s):")
    for device in devices:
        print(f"    {device.serial}  {device.friendly_name}  ({device.manufacturer} {device.model})")

    pairs: list[tuple[Device, Device]] = []
    if args.mode == "transport":
        pairs = build_pairs(devices, args.pairing)
        print(f"==> Pairing mode '{args.pairing}' plans {len(pairs)} run(s) per repeat, {args.repeat} repeat(s):")
        for sender, receiver in pairs:
            print(f"    sender={sender.friendly_name} ({sender.serial}) -> receiver={receiver.friendly_name} ({receiver.serial})")
    else:
        if not devices:
            raise SystemExit("Need at least 1 attached device to run the crypto benchmark; found 0")
        print(f"==> Crypto mode plans {len(devices)} single-device run(s) per repeat, {args.repeat} repeat(s):")
        for device in devices:
            print(f"    device={device.friendly_name} ({device.serial})")

    if args.dry_run:
        print("==> --dry-run set; not launching any app.")
        return 0

    if not args.execute:
        raise SystemExit(
            "Refusing to launch BLE traffic / instrumented tests on every attached device without --execute. "
            "Re-run with --execute once the planned run above looks correct (or narrow scope with --serials)."
        )

    results_dir = Path(args.results_dir)
    results_dir.mkdir(parents=True, exist_ok=True)
    base_app_id = args.app_id or f"fleet.meshlink.benchmark.{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"

    if args.mode == "crypto":
        return run_crypto_mode(devices, args, results_dir, base_app_id)
    return run_transport_mode(pairs, args, results_dir, base_app_id)


def run_transport_mode(
    pairs: list[tuple[Device, Device]], args: argparse.Namespace, results_dir: Path, base_app_id: str
) -> int:
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


def run_crypto_mode(
    devices: list[Device], args: argparse.Namespace, results_dir: Path, base_app_id: str
) -> int:
    all_outcomes: list[CryptoOutcome] = []
    for repeat_index in range(1, args.repeat + 1):
        for device_index, device in enumerate(devices, start=1):
            app_id = base_app_id if args.repeat == 1 else f"{base_app_id}.r{repeat_index}"
            run_dir = results_dir / "crypto-runs" / app_id.replace(".", "_") / f"{device_index:02d}_{device.hardware_serial}"
            print(f"\n=== Repeat {repeat_index}/{args.repeat}, device {device_index}/{len(devices)} ===")
            print(f"==> Timing crypto primitives on {device.friendly_name} ({device.serial})")
            outcome = run_crypto_benchmark(device, run_dir=run_dir, timeout_seconds=args.crypto_timeout_seconds)
            if outcome.ops:
                summary = ", ".join(f"{op['op']}={op['avg_us']:.1f}\u00b5s" for op in outcome.ops)
                partial_note = f" [PARTIAL: {outcome.error}]" if outcome.partial else ""
                print(f"==> Result: {summary}{partial_note}")
            else:
                print(f"==> Result: {outcome.error or 'unknown'}")
            all_outcomes.append(outcome)
            # Persist after every device so a mid-run interruption still leaves a
            # durable record of every device that already completed.
            append_crypto_ledger(results_dir, [outcome.to_record()])
            rewrite_crypto_summary(results_dir)

    ledger_path = results_dir / "crypto-ledger.jsonl"
    summary_path = results_dir / "crypto-latest-summary.md"

    successful = [o for o in all_outcomes if o.ops and not o.partial]
    print(f"\n==> {len(successful)}/{len(all_outcomes)} devices produced complete crypto benchmark results")
    print(f"==> Ledger: {ledger_path}")
    print(f"==> Summary: {summary_path}")
    return 0 if len(successful) == len(all_outcomes) else 1


if __name__ == "__main__":
    sys.exit(main())
