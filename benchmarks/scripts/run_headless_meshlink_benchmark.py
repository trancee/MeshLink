#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import plistlib
import re
import selectors
import shlex
import signal
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


IOS_BUNDLE_ID = "ch.trancee.meshlink.proof.ios"
ANDROID_PACKAGE = "ch.trancee.meshlink.proof.android"
ANDROID_ACTIVITY = f"{ANDROID_PACKAGE}/.MainActivity"
DEFAULT_CAPTURE_TIMEOUT_SECONDS = 120
DEFAULT_POST_RESULT_IDLE_SECONDS = 5
DEFAULT_ANDROID_READY_SECONDS = 8
DEFAULT_LOGCAT_TAGS = ["MeshLinkTransport:D", "MeshLinkProof:I", "*:S"]
ADB_DEVICE_PATTERN = re.compile(r"^(?P<serial>\S+)\s+(?P<state>device|offline|unauthorized)\b")
PROVISIONING_PROFILE_DIRS = [
    Path.home() / "Library/Developer/Xcode/UserData/Provisioning Profiles",
    Path.home() / "Library/MobileDevice/Provisioning Profiles",
]
BENCHMARK_RESULT_PATTERN = re.compile(
    r"BENCHMARK transport bytes=(?P<bytes>\d+) elapsedMs=(?P<elapsed_ms>\d+) "
    r"throughputKBps=(?P<throughput>[0-9]+(?:\.[0-9]+)?) result=(?P<result>\S+)"
)


@dataclass
class RunOutcome:
    run_dir: Path
    app_id: str
    benchmark_line_seen: bool
    iphone_benchmark_result: str | None
    iphone_receipt_line: str | None
    android_receipt_send: str | None
    android_message_line: str | None

    @property
    def throughput_kbps(self) -> float | None:
        if self.iphone_benchmark_result is None:
            return None
        match = BENCHMARK_RESULT_PATTERN.search(self.iphone_benchmark_result)
        if match is None:
            return None
        return float(match.group("throughput"))

    @property
    def result_label(self) -> str | None:
        if self.iphone_benchmark_result is None:
            return None
        match = BENCHMARK_RESULT_PATTERN.search(self.iphone_benchmark_result)
        if match is None:
            return None
        return match.group("result")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run a headless physical MeshLink benchmark with an Android passive proof app "
            "and an iPhone sender without hanging on quiet devicectl console streams."
        )
    )
    parser.add_argument("--android-serial", required=True, help="ADB serial of the passive Android peer")
    parser.add_argument("--ios-device", required=True, help="CoreDevice identifier of the iPhone sender")
    parser.add_argument("--payload-bytes", type=int, required=True, help="Benchmark payload size in bytes")
    parser.add_argument(
        "--app-id",
        help=(
            "Explicit app ID shared by both proof apps. "
            "Defaults to demo.meshlink.benchmark.headless.<timestamp>."
        ),
    )
    parser.add_argument(
        "--benchmark-transport",
        default="meshlink",
        choices=["meshlink", "gatt", "gatt-notify"],
        help="Benchmark transport selector passed to the proof apps",
    )
    parser.add_argument(
        "--run-dir",
        help="Directory for retained logs and metadata. Defaults to /tmp/ios_meshlink_headless_<timestamp>",
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=1,
        help="How many retained benchmark runs to execute in sequence. Uses numbered run directories when > 1.",
    )
    parser.add_argument(
        "--vary-app-id-per-run",
        action="store_true",
        help=(
            "Append the run index to the app ID during --repeat. "
            "By default repeat series now keep one stable app ID across the whole series."
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
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=DEFAULT_ANDROID_READY_SECONDS,
        help="Sleep after launching the Android proof app before starting the iPhone sender",
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=DEFAULT_CAPTURE_TIMEOUT_SECONDS,
        help="Hard timeout for the iPhone console capture loop",
    )
    parser.add_argument(
        "--post-result-idle-seconds",
        type=float,
        default=DEFAULT_POST_RESULT_IDLE_SECONDS,
        help="How long to wait after the benchmark result line before stopping console capture",
    )
    parser.add_argument(
        "--skip-ios-build",
        action="store_true",
        help="Skip xcodebuild and reuse the latest built ProofApp.app",
    )
    parser.add_argument(
        "--skip-ios-install",
        action="store_true",
        help="Skip devicectl install and reuse the currently installed iPhone app",
    )
    parser.add_argument(
        "--disable-auto-send",
        action="store_true",
        default=True,
        help="Launch the Android proof app in passive mode without its own sender auto-send",
    )
    parser.add_argument(
        "--no-disable-auto-send",
        dest="disable_auto_send",
        action="store_false",
        help="Allow Android proof auto-send instead of launching passively",
    )
    return parser.parse_args()


def timestamp() -> str:
    return time.strftime("%Y%m%dT%H%M%S")


def shell_join(command: Iterable[str]) -> str:
    return " ".join(shlex.quote(part) for part in command)


def run(command: list[str], *, check: bool = True, capture_output: bool = False, text: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=check, capture_output=capture_output, text=text)


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
    if not devices:
        raise SystemExit(
            "No Android devices are connected via ADB. Connect the passive Android proof peer before running the benchmark."
        )
    available = ", ".join(f"{serial}({state})" for serial, state in sorted(devices.items()))
    if state is None:
        raise SystemExit(
            f"Android serial '{android_serial}' is not connected via ADB. Available devices: {available}"
        )
    raise SystemExit(
        f"Android serial '{android_serial}' is connected but not ready (state={state}). Available devices: {available}"
    )


class BackgroundProcess:
    def __init__(self, process: subprocess.Popen[str]) -> None:
        self.process = process

    def stop(self) -> None:
        if self.process.poll() is not None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)


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


def decode_provisioning_profile(path: Path) -> dict[str, Any] | None:
    result = subprocess.run(
        ["security", "cms", "-D", "-i", str(path)],
        check=False,
        capture_output=True,
    )
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
        "DEVELOPMENT_TEAM is required unless --skip-ios-build is used, unless a local cached provisioning profile already matches "
        f"{IOS_BUNDLE_ID}. Export it in your shell instead of hardcoding it in the repo."
    )


def latest_built_app() -> Path:
    derived_data = Path.home() / "Library/Developer/Xcode/DerivedData"
    candidates = sorted(
        derived_data.glob("ProofApp-*/Build/Products/Debug-iphoneos/ProofApp.app"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise SystemExit("Could not find a built ProofApp.app in Xcode DerivedData")
    return candidates[0]


def build_ios_app(ios_device: str) -> Path:
    development_team = resolve_development_team()
    build_attempts = [
        (
            "local cached signing assets",
            [
                "xcodebuild",
                "-project",
                "meshlink-sample/ios/ProofApp.xcodeproj",
                "-scheme",
                "ProofApp",
                "-destination",
                f"id={ios_device}",
                f"DEVELOPMENT_TEAM={development_team}",
                "build",
            ],
        ),
        (
            "Xcode automatic provisioning fallback",
            [
                "xcodebuild",
                "-project",
                "meshlink-sample/ios/ProofApp.xcodeproj",
                "-scheme",
                "ProofApp",
                "-destination",
                f"id={ios_device}",
                "-allowProvisioningUpdates",
                f"DEVELOPMENT_TEAM={development_team}",
                "build",
            ],
        ),
    ]
    for attempt_index, (label, command) in enumerate(build_attempts, start=1):
        redacted_command = [
            "DEVELOPMENT_TEAM=<redacted>" if part == f"DEVELOPMENT_TEAM={development_team}" else part
            for part in command
        ]
        print(f"==> Building iPhone app via {label}: {shell_join(redacted_command)}")
        with tempfile.NamedTemporaryFile(prefix="proofapp-build.", delete=False) as raw_log:
            log_path = Path(raw_log.name)
        with log_path.open("w", encoding="utf-8") as log_file:
            result = subprocess.run(command, stdout=log_file, stderr=subprocess.STDOUT, text=True)
        log_text = log_path.read_text(encoding="utf-8", errors="replace").replace(development_team, "<redacted>")
        if result.returncode == 0:
            print(f"==> iPhone build succeeded via {label}")
            return latest_built_app()
        if attempt_index < len(build_attempts):
            print(f"==> iPhone build via {label} failed; trying the next signing strategy")
            continue
        print("==> iPhone build failed; tail follows:", file=sys.stderr)
        print("\n".join(log_text.splitlines()[-40:]), file=sys.stderr)
        raise SystemExit(result.returncode)
    raise AssertionError("unreachable")


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
    print(f"==> Installing iPhone app: {shell_join(command)}")
    run(command)


def start_android_app(run_dir: Path, android_serial: str, app_id: str, benchmark_transport: str, disable_auto_send: bool) -> BackgroundProcess:
    ensure_android_device_ready(android_serial)
    logcat_path = run_dir / "android_logcat.log"
    subprocess.run(["adb", "-s", android_serial, "shell", "am", "force-stop", ANDROID_PACKAGE], check=False)
    run(["adb", "-s", android_serial, "logcat", "-c"])
    logcat = subprocess.Popen(
        ["adb", "-s", android_serial, "logcat", *DEFAULT_LOGCAT_TAGS],
        stdout=logcat_path.open("w", encoding="utf-8"),
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
        "-n",
        ANDROID_ACTIVITY,
        "--es",
        "meshlink.appId",
        app_id,
    ]
    if disable_auto_send:
        command += ["--ez", "meshlink.disableAutoSend", "true"]
    if benchmark_transport != "meshlink":
        command += ["--es", "meshlink.benchmarkTransport", benchmark_transport]

    start_output = run(command, capture_output=True)
    (run_dir / "start.txt").write_text(start_output.stdout + start_output.stderr, encoding="utf-8")
    return BackgroundProcess(logcat)


def capture_android_proof_log(run_dir: Path, android_serial: str) -> None:
    command = [
        "adb",
        "-s",
        android_serial,
        "exec-out",
        "run-as",
        ANDROID_PACKAGE,
        "cat",
        "files/proof.log",
    ]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    (run_dir / "android_proof.log").write_text(result.stdout, encoding="utf-8")


def first_match(lines: list[str], needle: str) -> str | None:
    for line in lines:
        if needle in line:
            return line
    return None


def summarize(run_dir: Path, payload_bytes: int) -> RunOutcome:
    iphone_lines = (run_dir / "iphone_console.log").read_text(encoding="utf-8", errors="replace").splitlines()
    android_logcat = (run_dir / "android_logcat.log").read_text(encoding="utf-8", errors="replace").splitlines()
    android_proof_path = run_dir / "android_proof.log"
    android_proof_lines = android_proof_path.read_text(encoding="utf-8", errors="replace").splitlines() if android_proof_path.exists() else []

    benchmark_result = first_match(iphone_lines, f"BENCHMARK transport bytes={payload_bytes}")
    receipt_line = first_match(iphone_lines, "BENCHMARK receipt from")
    android_receipt_send = first_match(android_logcat, "BENCHMARK receipt send") or first_match(android_proof_lines, "BENCHMARK receipt send")
    android_message = first_match(android_logcat, "MSG from") or first_match(android_proof_lines, "MSG from")

    print("==> Retained run directory:", run_dir)
    print("==> iPhone benchmark result:", benchmark_result or "missing")
    print("==> iPhone receipt line:", receipt_line or "missing")
    print("==> Android proof receipt send:", android_receipt_send or "missing")
    print("==> Android proof message:", android_message or "missing")

    return RunOutcome(
        run_dir=run_dir,
        app_id=(run_dir / "meta.txt").read_text(encoding="utf-8", errors="replace").splitlines()[0].split("=", 1)[1],
        benchmark_line_seen=benchmark_result is not None,
        iphone_benchmark_result=benchmark_result,
        iphone_receipt_line=receipt_line,
        android_receipt_send=android_receipt_send,
        android_message_line=android_message,
    )


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
    meta = {
        "app_id": app_id,
        "android_serial": args.android_serial,
        "ios_device": args.ios_device,
        "payload_bytes": str(args.payload_bytes),
        "benchmark_transport": args.benchmark_transport,
    }
    (run_dir / "meta.txt").write_text(
        "".join(f"{key}={value}\n" for key, value in meta.items()),
        encoding="utf-8",
    )

    logcat_process = start_android_app(
        run_dir=run_dir,
        android_serial=args.android_serial,
        app_id=app_id,
        benchmark_transport=args.benchmark_transport,
        disable_auto_send=args.disable_auto_send,
    )

    capture = IOSConsoleCapture(
        device=args.ios_device,
        bundle_id=IOS_BUNDLE_ID,
        env={
            "MESHLINK_APP_ID": app_id,
            "MESHLINK_BENCHMARK_PAYLOAD_BYTES": str(args.payload_bytes),
            **(
                {"MESHLINK_BENCHMARK_TRANSPORT": args.benchmark_transport}
                if args.benchmark_transport != "meshlink"
                else {}
            ),
        },
        log_path=run_dir / "iphone_console.log",
        result_marker=f"BENCHMARK transport bytes={args.payload_bytes}",
        timeout_seconds=args.capture_timeout_seconds,
        post_result_idle_seconds=args.post_result_idle_seconds,
    )

    try:
        print(f"==> Waiting {args.android_ready_seconds} seconds for the Android proof app to initialize")
        time.sleep(args.android_ready_seconds)
        print("==> Launching headless iPhone sender via devicectl")
        benchmark_line_seen = capture.launch()
    finally:
        capture.stop()
        logcat_process.stop()
        capture_android_proof_log(run_dir, args.android_serial)

    outcome = summarize(run_dir, args.payload_bytes)
    outcome.benchmark_line_seen = benchmark_line_seen
    return outcome


def summarize_series(outcomes: list[RunOutcome]) -> list[float]:
    throughputs = [outcome.throughput_kbps for outcome in outcomes if outcome.throughput_kbps is not None]
    successful = [outcome for outcome in outcomes if outcome.benchmark_line_seen]
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

    ensure_android_device_ready(args.android_serial)

    base_run_dir = Path(args.run_dir or f"/tmp/ios_meshlink_headless_{timestamp()}")
    app_path = latest_built_app() if args.skip_ios_build else build_ios_app(args.ios_device)
    if not args.skip_ios_install:
        install_ios_app(args.ios_device, app_path)

    outcomes: list[RunOutcome] = []
    series_app_id = args.app_id or f"demo.meshlink.benchmark.headless.{timestamp()}"
    for run_index in range(1, args.repeat + 1):
        run_dir = prepare_run_dir(base_run_dir, run_index, args.repeat)
        app_id = build_app_id(
            series_app_id,
            run_index,
            args.repeat,
            vary_per_run=args.vary_app_id_per_run,
        )
        outcome = run_once(
            args,
            run_dir=run_dir,
            app_id=app_id,
        )
        outcomes.append(outcome)

    throughputs = summarize_series(outcomes)

    if not all(outcome.benchmark_line_seen for outcome in outcomes):
        print(
            f"ERROR: at least one run did not observe BENCHMARK transport bytes={args.payload_bytes} within {args.capture_timeout_seconds} seconds",
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

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
