#!/usr/bin/env python3
"""Run the MeshLink proof app between two physical iPhones (sender + passive).

This is the iOS<->iOS counterpart to meshlink-benchmark/scripts/run_headless_meshlink_benchmark.py
(Android passive <-> iPhone sender) and meshlink-proof/scripts/run_android_proof_fleet.py
(Android <-> Android fleet). Both iPhones run the ProofApp headlessly via
`xcrun devicectl device process launch --console`, one in sender mode (auto-send
enabled, drives the scored BENCHMARK transport line) and the other in passive mode
(MESHLINK_DISABLE_AUTO_SEND=true), mirroring the passive role the Android proof app
plays in the cross-platform benchmark script.

Requires both ProofApp copies to already be built and installed (codesigning needs
the logged-in GUI/Aqua session's Security Agent/keychain access, which this
automation shell cannot obtain -- see docs/how-to/evaluate-meshlink-with-the-reference-app.md
for the analogous reference-app constraint). Build/install both devices yourself via
Xcode or `xcodebuild ... -destination id=<udid> build` + `xcrun devicectl device
install app` before running this script, then pass --skip-ios-build (this script
never builds automatically since there's no single "the" device to build for).
"""

from __future__ import annotations

import argparse
import json
import re
import selectors
import shlex
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

IOS_BUNDLE_ID = "ch.trancee.meshlink.proof.ios"
DEFAULT_CAPTURE_TIMEOUT_SECONDS = 120
DEFAULT_POST_RESULT_IDLE_SECONDS = 5
BENCHMARK_RESULT_PATTERN = re.compile(
    r"BENCHMARK transport bytes=(?P<bytes>\d+) elapsedMs=(?P<elapsed_ms>\d+) "
    r"throughputKBps=(?P<throughput>[0-9]+(?:\.[0-9]+)?) result=(?P<result>\S+)"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a headless physical MeshLink proof exchange between two iPhones."
    )
    parser.add_argument("--sender-device", required=True, help="CoreDevice identifier of the sender iPhone")
    parser.add_argument("--passive-device", required=True, help="CoreDevice identifier of the passive iPhone")
    parser.add_argument("--payload-bytes", type=int, default=64, help="Benchmark payload size in bytes")
    parser.add_argument(
        "--app-id",
        help="Shared MeshLink app ID. Defaults to demo.meshlink.proof.ios-ios.<timestamp>",
    )
    parser.add_argument(
        "--run-dir",
        help="Directory for retained logs. Defaults to /tmp/ios_ios_proof_<timestamp>",
    )
    parser.add_argument(
        "--passive-ready-seconds",
        type=float,
        default=5.0,
        help="How long to wait after launching the passive iPhone before starting the sender",
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=DEFAULT_CAPTURE_TIMEOUT_SECONDS,
        help="Hard timeout for each device's console capture loop",
    )
    parser.add_argument(
        "--post-result-idle-seconds",
        type=float,
        default=DEFAULT_POST_RESULT_IDLE_SECONDS,
        help="How long to keep each console open after its own completion marker",
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=1,
        help="How many retained proof runs to execute in sequence. Uses numbered run directories when > 1.",
    )
    parser.add_argument(
        "--vary-app-id-per-run",
        action="store_true",
        help=(
            "Append the run index to the app ID during --repeat. "
            "By default repeat series keep one stable app ID across the whole series."
        ),
    )
    parser.add_argument(
        "--require-run-min-kbps",
        type=float,
        help="Fail if any scored run in the series is below this throughput threshold.",
    )
    parser.add_argument(
        "--require-average-kbps",
        type=float,
        help="Fail if the scored series average is below this throughput threshold.",
    )
    return parser.parse_args()


def timestamp() -> str:
    return time.strftime("%Y%m%dT%H%M%S")


def shell_join(command: Iterable[str]) -> str:
    return " ".join(shlex.quote(part) for part in command)


class IOSConsoleCapture:
    def __init__(
        self,
        *,
        device: str,
        bundle_id: str,
        env: dict[str, str],
        log_path: Path,
        result_marker: str,
        timeout_seconds: float,
        post_result_idle_seconds: float,
    ) -> None:
        self.device = device
        self.bundle_id = bundle_id
        self.env = env
        self.log_path = log_path
        self.result_marker = result_marker
        self.timeout_seconds = timeout_seconds
        self.post_result_idle_seconds = post_result_idle_seconds
        self.result_line: str | None = None
        self.process: subprocess.Popen[str] | None = None

    def launch(self) -> bool:
        command = [
            "xcrun",
            "devicectl",
            "device",
            "process",
            "launch",
            "--device",
            self.device,
            "--terminate-existing",
            "--console",
            "-e",
            json.dumps(self.env),
            self.bundle_id,
        ]
        self.process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert self.process.stdout is not None

        selector = selectors.DefaultSelector()
        selector.register(self.process.stdout, selectors.EVENT_READ)
        started_at = time.monotonic()
        result_seen_at: float | None = None

        with self.log_path.open("w", encoding="utf-8") as log_file:
            while True:
                now = time.monotonic()
                if result_seen_at is not None and now - result_seen_at >= self.post_result_idle_seconds:
                    return True
                if now - started_at >= self.timeout_seconds:
                    return False

                events = selector.select(timeout=0.5)
                if not events:
                    if self.process.poll() is not None:
                        return result_seen_at is not None
                    continue

                for key, _ in events:
                    line = key.fileobj.readline()
                    if line == "":
                        if self.process.poll() is not None:
                            return result_seen_at is not None
                        continue
                    log_file.write(line)
                    log_file.flush()
                    if self.result_marker in line:
                        self.result_line = line.strip()
                        result_seen_at = time.monotonic()

    def stop(self) -> None:
        if self.process is None or self.process.poll() is not None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)


def first_match(lines: list[str], needle: str) -> str | None:
    for line in lines:
        if needle in line:
            return line
    return None


@dataclass
class RunOutcome:
    run_dir: Path
    sender_benchmark_seen: bool
    sender_result_line: str | None
    passive_message_line: str | None

    @property
    def throughput_kbps(self) -> float | None:
        if self.sender_result_line is None:
            return None
        match = BENCHMARK_RESULT_PATTERN.search(self.sender_result_line)
        return float(match.group("throughput")) if match else None

    @property
    def result_label(self) -> str | None:
        if self.sender_result_line is None:
            return None
        match = BENCHMARK_RESULT_PATTERN.search(self.sender_result_line)
        return match.group("result") if match else None


def prepare_run_dir(base_run_dir: Path, run_index: int, repeat: int) -> Path:
    if repeat == 1:
        return base_run_dir
    return Path(f"{base_run_dir}_{run_index}")


def build_app_id(
    base_app_id: str,
    run_index: int,
    repeat: int,
    *,
    vary_per_run: bool,
) -> str:
    if repeat == 1 or not vary_per_run:
        return base_app_id
    return f"{base_app_id}.{run_index}"


def run_once(args: argparse.Namespace, *, run_dir: Path, app_id: str) -> RunOutcome:
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "meta.txt").write_text(
        f"app_id={app_id}\nsender_device={args.sender_device}\npassive_device={args.passive_device}\n"
        f"payload_bytes={args.payload_bytes}\n",
        encoding="utf-8",
    )

    passive_capture = IOSConsoleCapture(
        device=args.passive_device,
        bundle_id=IOS_BUNDLE_ID,
        env={
            "MESHLINK_APP_ID": app_id,
            "MESHLINK_DISABLE_AUTO_SEND": "true",
        },
        log_path=run_dir / "passive_console.log",
        # The passive side has no single scored completion line of its own; watch for
        # the tagged benchmark payload's arrival as evidence it was actively involved.
        result_marker="benchmarkToken=",
        timeout_seconds=args.capture_timeout_seconds,
        post_result_idle_seconds=args.capture_timeout_seconds,
    )
    sender_capture = IOSConsoleCapture(
        device=args.sender_device,
        bundle_id=IOS_BUNDLE_ID,
        env={
            "MESHLINK_APP_ID": app_id,
            "MESHLINK_BENCHMARK_PAYLOAD_BYTES": str(args.payload_bytes),
        },
        log_path=run_dir / "sender_console.log",
        result_marker=f"BENCHMARK transport bytes={args.payload_bytes}",
        timeout_seconds=args.capture_timeout_seconds,
        post_result_idle_seconds=args.post_result_idle_seconds,
    )

    passive_result: dict[str, bool] = {}

    def run_passive() -> None:
        passive_result["seen"] = passive_capture.launch()

    passive_thread = threading.Thread(target=run_passive, daemon=True)
    passive_thread.start()

    try:
        print(f"==> Waiting {args.passive_ready_seconds} seconds for the passive iPhone to initialize")
        time.sleep(args.passive_ready_seconds)
        print("==> Launching headless iPhone sender via devicectl")
        sender_benchmark_seen = sender_capture.launch()
    finally:
        sender_capture.stop()
        # Wait for the passive side's own capture loop to finish naturally (it self-terminates
        # once it either observes benchmarkToken= or hits its own capture_timeout_seconds budget
        # -- see IOSConsoleCapture.launch). A short, fixed grace period here (e.g. tied to
        # post_result_idle_seconds) was previously used and was fine for small payloads, but for
        # large payloads a redundant/late-arriving frame can still be in flight well after the
        # sender times out, so truncating the join early via passive_capture.stop() could kill the
        # passive capture before it had a chance to log evidence of a payload that was still being
        # delivered. Match the join timeout to the passive capture's own configured budget instead.
        passive_thread.join(timeout=args.capture_timeout_seconds + 2)
        passive_capture.stop()
        passive_thread.join(timeout=5)

    sender_lines = (run_dir / "sender_console.log").read_text(encoding="utf-8", errors="replace").splitlines()
    passive_lines = (run_dir / "passive_console.log").read_text(encoding="utf-8", errors="replace").splitlines()

    sender_result_line = first_match(sender_lines, f"BENCHMARK transport bytes={args.payload_bytes}")
    passive_message_line = first_match(passive_lines, "benchmarkToken=")

    print("==> Retained run directory:", run_dir)
    print("==> Sender benchmark result:", sender_result_line or "missing")
    print("==> Passive message evidence:", passive_message_line or "missing")

    return RunOutcome(
        run_dir=run_dir,
        sender_benchmark_seen=sender_benchmark_seen,
        sender_result_line=sender_result_line,
        passive_message_line=passive_message_line,
    )


def summarize_series(outcomes: list[RunOutcome]) -> list[float]:
    throughputs = [outcome.throughput_kbps for outcome in outcomes if outcome.throughput_kbps is not None]
    successful = [outcome for outcome in outcomes if outcome.sender_benchmark_seen]
    if len(outcomes) > 1:
        print(f"==> Series summary: {len(successful)}/{len(outcomes)} runs observed a scored benchmark line")
        if throughputs:
            average = sum(throughputs) / len(throughputs)
            print(
                "==> Throughput summary: "
                f"min={min(throughputs):.2f} KB/s avg={average:.2f} KB/s max={max(throughputs):.2f} KB/s"
            )
        for index, outcome in enumerate(outcomes, start=1):
            print(
                f"==> Run {index}: dir={outcome.run_dir} result={outcome.result_label or 'missing'} "
                f"throughputKBps={outcome.throughput_kbps if outcome.throughput_kbps is not None else 'missing'}"
            )
    return [value for value in throughputs if value is not None]


def main() -> int:
    args = parse_args()
    if args.repeat < 1:
        raise SystemExit("--repeat must be >= 1")

    base_run_dir = Path(args.run_dir or f"/tmp/ios_ios_proof_{timestamp()}")
    series_app_id = args.app_id or f"demo.meshlink.proof.ios-ios.{timestamp()}"

    outcomes: list[RunOutcome] = []
    for run_index in range(1, args.repeat + 1):
        run_dir = prepare_run_dir(base_run_dir, run_index, args.repeat)
        app_id = build_app_id(
            series_app_id,
            run_index,
            args.repeat,
            vary_per_run=args.vary_app_id_per_run,
        )
        outcome = run_once(args, run_dir=run_dir, app_id=app_id)
        outcomes.append(outcome)

    throughputs = summarize_series(outcomes)

    if not all(outcome.sender_benchmark_seen for outcome in outcomes):
        print(
            f"ERROR: at least one run did not observe BENCHMARK transport bytes={args.payload_bytes} within "
            f"{args.capture_timeout_seconds} seconds",
            file=sys.stderr,
        )
        return 1

    if not all(outcome.result_label == "Sent" for outcome in outcomes):
        failed = [outcome for outcome in outcomes if outcome.result_label != "Sent"]
        for outcome in failed:
            print(
                f"ERROR: run {outcome.run_dir} benchmark result was {outcome.result_label!r}, expected 'Sent'",
                file=sys.stderr,
            )
        return 1

    if args.require_run_min_kbps is not None:
        below_threshold = [
            outcome for outcome in outcomes
            if outcome.throughput_kbps is None or outcome.throughput_kbps < args.require_run_min_kbps
        ]
        if below_threshold:
            print(
                f"ERROR: {len(below_threshold)} run(s) fell below require-run-min-kbps={args.require_run_min_kbps:.2f}",
                file=sys.stderr,
            )
            for outcome in below_threshold:
                print(
                    f"  - {outcome.run_dir}: throughputKBps={outcome.throughput_kbps if outcome.throughput_kbps is not None else 'missing'}",
                    file=sys.stderr,
                )
            return 1

    if args.require_average_kbps is not None:
        if not throughputs:
            print(
                "ERROR: require-average-kbps was set but no scored throughput values were captured",
                file=sys.stderr,
            )
            return 1
        average = sum(throughputs) / len(throughputs)
        if average < args.require_average_kbps:
            print(
                f"ERROR: series average {average:.2f} KB/s is below require-average-kbps={args.require_average_kbps:.2f}",
                file=sys.stderr,
            )
            return 1

    print(f"==> Success: throughputKBps={outcomes[-1].throughput_kbps}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
