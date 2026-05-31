from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import reference_fleet  # noqa: E402


class FakeCommandRunner:
    def __init__(self, responses: dict[tuple[str, ...], dict[str, Any] | None]) -> None:
        self._responses = responses
        self.calls: list[dict[str, Any]] = []

    def __call__(self, command: list[str], *, timeout_seconds: float | None = None) -> reference_fleet.ProbeResult:
        self.calls.append({"command": list(command), "timeout_seconds": timeout_seconds})
        key = tuple(command)
        if key not in self._responses:
            raise AssertionError(f"Unexpected command: {command!r}")
        response = self._responses[key] or {}
        return reference_fleet.ProbeResult(
            command=list(command),
            returncode=response.get("returncode", 0),
            stdout=response.get("stdout", ""),
            stderr=response.get("stderr", ""),
            timed_out=response.get("timed_out", False),
            missing=response.get("missing", False),
            error=response.get("error"),
        )


def probe_response(
    *,
    stdout: str = "",
    stderr: str = "",
    returncode: int = 0,
    timed_out: bool = False,
    missing: bool = False,
    error: str | None = None,
) -> dict[str, Any]:
    return {
        "stdout": stdout,
        "stderr": stderr,
        "returncode": returncode,
        "timed_out": timed_out,
        "missing": missing,
        "error": error,
    }


def adb_devices_output(*rows: tuple[str, str]) -> str:
    lines = ["List of devices attached"]
    for serial, state in rows:
        lines.append(f"{serial}\t{state}")
    return "\n".join(lines) + "\n"


def android_model_output(model: str) -> str:
    return f"{model}\n"


def devicectl_table(*rows: tuple[str, str, str, str]) -> str:
    lines = ["Name | Platform | State | Identifier"]
    for name, platform, state, identifier in rows:
        lines.append(f"{name} | {platform} | {state} | {identifier}")
    return "\n".join(lines) + "\n"


def resolved_team_lookup(_: str) -> str | None:
    return "TEAM12345"


def unresolved_team_lookup(_: str) -> str | None:
    return None


def candidate_by_shape(manifest: dict[str, Any], shape: str) -> dict[str, Any]:
    for candidate in manifest["candidateAssignments"]:
        if candidate["shape"] == shape:
            return candidate
    raise AssertionError(f"Missing candidate for shape={shape!r}")


def reason_codes(payload: dict[str, Any]) -> set[str]:
    return {reason["code"] for reason in payload.get("reasons", [])}
