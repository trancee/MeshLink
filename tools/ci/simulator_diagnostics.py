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
    for line in destinations.splitlines():
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
        count += 1
        result.append(
            f"{section}: os={version(fields.get('OS'))} "
            f"iphone={fields.get('name', '').startswith('iPhone')} "
            f"id={'id' in fields} error={'error' in fields}"
        )
    result.insert(0, f"simulator destinations: {count}")

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


if __name__ == "__main__":
    main()
