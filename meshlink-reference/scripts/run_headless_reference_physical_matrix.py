#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from run_headless_reference_live_proof import shell_join, timestamp

AVAILABLE_SCENARIOS = {
    "direct-guided",
    "direct-xcuitest-permission-recovery",
    "relay-constrained",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run the retained MeshLink reference-app physical scenario matrix and analyze each scenario."
        )
    )
    parser.add_argument("--ios-device", required=True, help="Physical iPhone UDID")
    parser.add_argument(
        "--direct-android-serial",
        help="ADB serial for the Android device used in direct scenarios. Defaults to --passive-android-serial when omitted.",
    )
    parser.add_argument(
        "--passive-android-serial",
        help="ADB serial for the passive Android recipient (used by relay and as the default direct Android device).",
    )
    parser.add_argument(
        "--relay-android-serial",
        help="ADB serial for the Android relay device (required for relay-constrained).",
    )
    parser.add_argument(
        "--run-root",
        help="Directory for the full retained scenario matrix. Defaults to /tmp/reference_physical_matrix_<timestamp>",
    )
    parser.add_argument(
        "--scenarios",
        default="auto",
        help=(
            "Comma-separated scenario list. Available values: direct-guided, direct-xcuitest-permission-recovery, relay-constrained. "
            "Defaults to auto (direct-guided, plus relay-constrained when both Android relay/passive serials are available)."
        ),
    )
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=8.0,
        help="Delay between Android peer startup and iPhone sender launch",
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=120.0,
        help="Timeout for scenario completion capture",
    )
    parser.add_argument(
        "--post-result-idle-seconds",
        type=float,
        default=5.0,
        help="Additional iPhone console time after sender completion",
    )
    parser.add_argument("--skip-android-install", action="store_true")
    parser.add_argument("--skip-ios-build", action="store_true")
    parser.add_argument("--skip-ios-install", action="store_true")
    parser.add_argument(
        "--continue-on-failure",
        action="store_true",
        help="Keep running later scenarios even if an earlier scenario fails",
    )
    return parser.parse_args()


def resolve_scenarios(args: argparse.Namespace) -> list[str]:
    if args.scenarios == "auto":
        scenarios = ["direct-guided"]
        if args.passive_android_serial and args.relay_android_serial:
            scenarios.append("relay-constrained")
        return scenarios
    scenarios = [item.strip() for item in args.scenarios.split(",") if item.strip()]
    unknown = sorted(set(scenarios) - AVAILABLE_SCENARIOS)
    if unknown:
        raise SystemExit(f"Unknown scenarios: {', '.join(unknown)}")
    return scenarios


def bool_flag(enabled: bool, flag: str) -> list[str]:
    return [flag] if enabled else []


def run_command(command: list[str]) -> None:
    print(f"==> Running: {shell_join(command)}")
    subprocess.run(command, check=True)


def analyze_run(
    *,
    scripts_dir: Path,
    run_dir: Path,
    scenario_type: str,
) -> None:
    command = [
        sys.executable,
        str(scripts_dir / "analyze_reference_physical_run.py"),
        "--run-dir",
        str(run_dir),
        "--scenario-type",
        scenario_type,
    ]
    run_command(command)


def main() -> int:
    args = parse_args()
    scenarios = resolve_scenarios(args)
    if not scenarios:
        raise SystemExit("No scenarios selected")

    direct_android_serial = args.direct_android_serial or args.passive_android_serial
    scripts_dir = Path(__file__).resolve().parent
    run_root = Path(args.run_root or f"/tmp/reference_physical_matrix_{timestamp()}")
    run_root.mkdir(parents=True, exist_ok=True)

    results: list[dict[str, object]] = []
    failures = 0

    for scenario in scenarios:
        scenario_run_dir = run_root / scenario
        scenario_type: str | None = None
        try:
            if scenario == "direct-guided":
                scenario_type = "direct"
                if not direct_android_serial:
                    raise SystemExit(
                        "direct-guided requires --direct-android-serial or --passive-android-serial"
                    )
                command = [
                    sys.executable,
                    str(scripts_dir / "run_headless_reference_live_proof.py"),
                    "--android-serial",
                    direct_android_serial,
                    "--ios-device",
                    args.ios_device,
                    "--run-dir",
                    str(scenario_run_dir),
                    "--android-ready-seconds",
                    str(args.android_ready_seconds),
                    "--capture-timeout-seconds",
                    str(args.capture_timeout_seconds),
                    "--post-result-idle-seconds",
                    str(args.post_result_idle_seconds),
                    *bool_flag(args.skip_android_install, "--skip-android-install"),
                    *bool_flag(args.skip_ios_build, "--skip-ios-build"),
                    *bool_flag(args.skip_ios_install, "--skip-ios-install"),
                ]
                run_command(command)
                analyze_run(
                    scripts_dir=scripts_dir,
                    run_dir=scenario_run_dir,
                    scenario_type=scenario_type,
                )
            elif scenario == "direct-xcuitest-permission-recovery":
                scenario_type = "direct"
                if not direct_android_serial:
                    raise SystemExit(
                        "direct-xcuitest-permission-recovery requires --direct-android-serial or --passive-android-serial"
                    )
                command = [
                    sys.executable,
                    str(scripts_dir / "run_headless_reference_live_proof.py"),
                    "--android-serial",
                    direct_android_serial,
                    "--ios-device",
                    args.ios_device,
                    "--run-dir",
                    str(scenario_run_dir),
                    "--ios-launch-mode",
                    "xcuitest",
                    "--android-ready-seconds",
                    str(args.android_ready_seconds),
                    "--capture-timeout-seconds",
                    str(args.capture_timeout_seconds),
                    "--post-result-idle-seconds",
                    str(args.post_result_idle_seconds),
                    *bool_flag(args.skip_android_install, "--skip-android-install"),
                    *bool_flag(args.skip_ios_build, "--skip-ios-build"),
                    *bool_flag(args.skip_ios_install, "--skip-ios-install"),
                ]
                run_command(command)
                analyze_run(
                    scripts_dir=scripts_dir,
                    run_dir=scenario_run_dir,
                    scenario_type=scenario_type,
                )
            elif scenario == "relay-constrained":
                scenario_type = "relay"
                if not args.passive_android_serial or not args.relay_android_serial:
                    raise SystemExit(
                        "relay-constrained requires both --passive-android-serial and --relay-android-serial"
                    )
                command = [
                    sys.executable,
                    str(scripts_dir / "run_headless_reference_relay_proof.py"),
                    "--ios-device",
                    args.ios_device,
                    "--passive-android-serial",
                    args.passive_android_serial,
                    "--relay-android-serial",
                    args.relay_android_serial,
                    "--run-dir",
                    str(scenario_run_dir),
                    "--android-ready-seconds",
                    str(args.android_ready_seconds),
                    "--capture-timeout-seconds",
                    str(args.capture_timeout_seconds),
                    "--post-result-idle-seconds",
                    str(args.post_result_idle_seconds),
                    *bool_flag(args.skip_android_install, "--skip-android-install"),
                    *bool_flag(args.skip_ios_build, "--skip-ios-build"),
                    *bool_flag(args.skip_ios_install, "--skip-ios-install"),
                ]
                run_command(command)
                analyze_run(
                    scripts_dir=scripts_dir,
                    run_dir=scenario_run_dir,
                    scenario_type=scenario_type,
                )
            else:  # pragma: no cover - guarded by resolve_scenarios()
                raise SystemExit(f"Unhandled scenario: {scenario}")

            analysis_path = scenario_run_dir / "analysis.json"
            analysis = json.loads(analysis_path.read_text(encoding="utf-8"))
            results.append(
                {
                    "scenario": scenario,
                    "status": analysis["status"],
                    "run_dir": str(scenario_run_dir),
                    "analysis_json": str(analysis_path),
                    "analysis_markdown": str(scenario_run_dir / "analysis.md"),
                }
            )
        except Exception as error:  # pragma: no cover - failure aggregation wrapper
            failures += 1
            analysis_result: dict[str, object] = {}
            if scenario_type is not None and scenario_run_dir.exists():
                try:
                    analyze_run(
                        scripts_dir=scripts_dir,
                        run_dir=scenario_run_dir,
                        scenario_type=scenario_type,
                    )
                    analysis_path = scenario_run_dir / "analysis.json"
                    if analysis_path.exists():
                        analysis_result = {
                            "analysis_json": str(analysis_path),
                            "analysis_markdown": str(scenario_run_dir / "analysis.md"),
                        }
                except Exception:
                    analysis_result = {}
            results.append(
                {
                    "scenario": scenario,
                    "status": "failed-to-run",
                    "run_dir": str(scenario_run_dir),
                    "error": str(error),
                    **analysis_result,
                }
            )
            if not args.continue_on_failure:
                break

    summary = {
        "run_root": str(run_root),
        "results": results,
        "failures": failures,
    }
    summary_path = run_root / "matrix-summary.json"
    summary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"==> Wrote matrix summary: {summary_path}")

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
