import json
import os
import subprocess
import sys


def simctl_list(kind):
    try:
        result = subprocess.run(
            ["xcrun", "simctl", "list", kind, "--json"],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode:
            return None
        return json.loads(result.stdout)
    except (OSError, ValueError):
        return None


def diagnostic_query(label):
    try:
        result = subprocess.run(
            ["xcodebuild", "-showdestinations", "-scheme", "MeshLinkKitConsumer-Package"],
            capture_output=True,
            text=True,
            check=False,
            timeout=90,
        )
        available = result.stdout.split("Available destinations", 1)[-1].split("Ineligible destinations", 1)[0]
        count = sum("iOS Simulator" in line and "iPhone" in line for line in available.splitlines())
        print(f"{label} xcodebuild exit status: {result.returncode}", file=sys.stderr)
        print(f"{label} iPhone destination lines: {count}", file=sys.stderr)
    except (OSError, subprocess.TimeoutExpired):
        print(f"{label} xcodebuild unavailable or timed out", file=sys.stderr)


def diagnose_boot(devices):
    if os.environ.get("CI") != "true" or not isinstance(devices, dict):
        return
    device_sets = devices.get("devices")
    if not isinstance(device_sets, dict):
        return
    identifiers = [
        entry["udid"]
        for runtime, entries in device_sets.items()
        if runtime.startswith("com.apple.CoreSimulator.SimRuntime.iOS-") and isinstance(entries, list)
        for entry in entries
        if isinstance(entry, dict) and entry.get("isAvailable") is True
        and isinstance(entry.get("name"), str) and entry["name"].startswith("iPhone")
        and isinstance(entry.get("udid"), str) and entry["udid"]
    ]
    if not identifiers:
        return
    diagnostic_query("pre-boot")
    try:
        boot = subprocess.run(
            ["xcrun", "simctl", "boot", identifiers[0]], capture_output=True, check=False, timeout=90
        )
        print(f"diagnostic simulator boot status: {boot.returncode}", file=sys.stderr)
        if boot.returncode == 0:
            ready = subprocess.run(
                ["xcrun", "simctl", "bootstatus", identifiers[0], "-b"],
                capture_output=True,
                check=False,
                timeout=90,
            )
            print(f"diagnostic simulator readiness status: {ready.returncode}", file=sys.stderr)
            diagnostic_query("post-boot")
    except (OSError, subprocess.TimeoutExpired):
        print("diagnostic simulator boot unavailable or timed out", file=sys.stderr)


def main():
    result = subprocess.run(
        ["xcodebuild", "-showdestinations", "-scheme", "MeshLinkKitConsumer-Package"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    output = result.stdout
    available = output.split("Available destinations", 1)[-1].split("Ineligible destinations", 1)[0]
    simulators = []
    parse_errors = 0
    available_simulator_lines = 0
    available_iphone_lines = 0
    available_iphone_os_lines = 0
    for line in available.splitlines():
        fields = dict(f.split(":", 1) for f in line.strip().strip("{} ").split(", ") if ":" in f)
        if "iOS Simulator" in line:
            available_simulator_lines += 1
            if "iPhone" in line:
                available_iphone_lines += 1
                if "OS:" in line:
                    available_iphone_os_lines += 1
        if fields.get("platform") == "iOS Simulator" and "OS" in fields and fields.get("name", "").startswith("iPhone"):
            try:
                simulators.append(([int(p) for p in fields["OS"].split(".")], fields["id"]))
            except (KeyError, ValueError):
                parse_errors += 1

    ineligible = output.split("Ineligible destinations", 1)[-1] if "Ineligible destinations" in output else ""
    devices = simctl_list("devices")
    runtimes = simctl_list("runtimes")
    print(f"xcodebuild exit status: {result.returncode}", file=sys.stderr)
    print(f"xcodebuild stderr lines: {len(result.stderr.splitlines())}", file=sys.stderr)
    print(f"available destination entries: {sum('platform:' in line for line in available.splitlines())}", file=sys.stderr)
    print(f"ineligible destination entries: {sum('platform:' in line for line in ineligible.splitlines())}", file=sys.stderr)
    print(f"available lines mentioning iOS Simulator: {available_simulator_lines}", file=sys.stderr)
    print(f"available simulator lines mentioning iPhone: {available_iphone_lines}", file=sys.stderr)
    print(f"available iPhone simulator lines with OS field: {available_iphone_os_lines}", file=sys.stderr)
    print(f"ineligible lines mentioning iOS Simulator: {sum('iOS Simulator' in line for line in ineligible.splitlines())}", file=sys.stderr)
    print(f"parsed available iPhone destinations: {len(simulators)}", file=sys.stderr)
    print(f"iPhone destination parse errors: {parse_errors}", file=sys.stderr)
    if isinstance(devices, dict) and isinstance(devices.get("devices"), dict):
        phones = sum(
            1
            for runtime, entries in devices["devices"].items()
            if runtime.startswith("com.apple.CoreSimulator.SimRuntime.iOS-") and isinstance(entries, list)
            for entry in entries
            if isinstance(entry, dict) and entry.get("isAvailable") is True
            and isinstance(entry.get("name"), str) and entry["name"].startswith("iPhone")
        )
        print(f"available iPhone simulators: {phones}", file=sys.stderr)
    else:
        print("simctl devices unavailable", file=sys.stderr)
    if isinstance(runtimes, dict) and isinstance(runtimes.get("runtimes"), list):
        count = sum(
            1
            for runtime in runtimes["runtimes"]
            if isinstance(runtime, dict) and runtime.get("isAvailable") is True
            and isinstance(runtime.get("identifier"), str)
            and runtime["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")
        )
        print(f"available iOS runtimes: {count}", file=sys.stderr)
    else:
        print("simctl runtimes unavailable", file=sys.stderr)
    if result.returncode == 0 and simulators and not parse_errors:
        print(max(simulators)[1])
        return 0
    diagnose_boot(devices)
    print("no available iPhone simulator for the selected Xcode", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
