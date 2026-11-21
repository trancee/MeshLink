import json
import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
IOS_RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-"


def version(value):
    return value if isinstance(value, str) and re.fullmatch(r"\d+(?:\.\d+)*", value) else "unknown"


def summarize(destinations, runtimes, devices):
    result = []
    section = "unknown"
    count = 0
    simulator_lines = []
    selector_compatible = 0
    for line in destinations.splitlines():
        if re.search(r"platform\s*:\s*iOS Simulator\b", line):
            simulator_lines.append(line)
        if "Ineligible destinations" in line:
            section = "ineligible"
        elif "Available destinations" in line:
            section = "available"
        fields = dict(
            field.split(":", 1)
            for field in line.strip().strip("{} ").split(", ")
            if ":" in field
        )
        if fields.get("platform") != "iOS Simulator":
            continue
        if (
            section == "available"
            and version(fields.get("OS")) != "unknown"
            and fields.get("name", "").startswith("iPhone")
            and "id" in fields
        ):
            selector_compatible += 1
        count += 1
        result.append(
            f"{section}: os={version(fields.get('OS'))} "
            f"iphone={fields.get('name', '').startswith('iPhone')} "
            f"id={'id' in fields} error={'error' in fields}"
        )
    result.insert(0, f"simulator destinations: {count}")
    iphone_lines = sum(bool(re.search(r"name\s*:\s*iPhone\b", line)) for line in simulator_lines)
    os_lines = sum(bool(re.search(r"OS\s*:\s*\d+(?:\.\d+)*(?:\s*[,}])", line)) for line in simulator_lines)
    result.append(
        f"simulator lines={len(simulator_lines)} iphone lines={iphone_lines} numeric os lines={os_lines} "
        f"selector-compatible lines={selector_compatible}"
    )

    ios_runtimes = [
        runtime for runtime in runtimes["runtimes"] if runtime.get("identifier", "").startswith(IOS_RUNTIME)
    ]
    result.append(f"iOS runtimes: {len(ios_runtimes)}")
    for runtime in ios_runtimes:
        inventory = devices["devices"].get(runtime["identifier"], [])
        available = sum(device.get("isAvailable") is True for device in inventory)
        iphones = sum(device.get("name", "").startswith("iPhone") for device in inventory)
        result.append(
            f"iOS {version(runtime.get('version'))}: runtime_available={runtime.get('isAvailable') is True} "
            f"devices={len(inventory)} available={available} iphones={iphones}"
        )
    return "\n".join(result)


def probe_destination(runtimes, devices):
    candidates = []
    for runtime in runtimes["runtimes"]:
        identifier = runtime.get("identifier", "")
        runtime_version = version(runtime.get("version"))
        if not identifier.startswith(IOS_RUNTIME) or runtime.get("isAvailable") is not True or runtime_version == "unknown":
            continue
        for device in devices["devices"].get(identifier, []):
            if device.get("isAvailable") is True and device.get("name", "").startswith("iPhone") and device.get("udid"):
                candidates.append((tuple(map(int, runtime_version.split("."))), device["udid"]))
    if not candidates:
        return "destination probe: no available iPhone"

    udid = max(candidates)[1]
    try:
        result = subprocess.run(
            [
                "xcodebuild", "-showBuildSettings", "-scheme", "MeshLinkKitConsumer-Package",
                "-destination", f"platform=iOS Simulator,id={udid}", "-destination-timeout", "15", "test",
            ],
            cwd=ROOT / "tools" / "swiftpm-consumer",
            capture_output=True,
            text=True,
            timeout=120,
        )
    except (OSError, subprocess.TimeoutExpired):
        return "destination probe: unable to complete"
    return (
        f"destination probe exit code: {result.returncode} "
        f"destination not found={'unable to find a destination' in result.stderr.lower()}"
    )


def run(command, cwd=None):
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=120)
    print(f"{command[0]} exit code: {result.returncode}")
    result.check_returncode()
    return result.stdout


def main():
    destinations = run(
        ["xcodebuild", "-showdestinations", "-scheme", "MeshLinkKitConsumer-Package"],
        cwd=ROOT / "tools" / "swiftpm-consumer",
    )
    runtimes = json.loads(run(["xcrun", "simctl", "list", "-j", "runtimes"]))
    devices = json.loads(run(["xcrun", "simctl", "list", "-j", "devices"]))
    print(summarize(destinations, runtimes, devices))
    print(probe_destination(runtimes, devices))


if __name__ == "__main__":
    main()
