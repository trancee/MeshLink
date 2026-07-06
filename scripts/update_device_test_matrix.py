#!/usr/bin/env python3
"""Regenerate docs/reference/device-test-matrix.md from adb + catalog metadata."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

SCRIPT_DIR = Path(__file__).resolve().parent
CATALOG_PATH = SCRIPT_DIR / "device-test-matrix.catalog.json"
DEFAULT_OUT = Path("docs/reference/device-test-matrix.md")

CRYPTO_STYLES = {
    "AES-GCM": "background:#dcfce7;color:#166534;border:1px solid #86efac;",
    "ChaCha20-Poly1305": "background:#dcfce7;color:#166534;border:1px solid #86efac;",
    "X25519/XDH": "background:#bbf7d0;color:#14532d;border:1px solid #86efac;",
    "X25519": "background:#bbf7d0;color:#14532d;border:1px solid #86efac;",
    "Ed25519": "background:#bbf7d0;color:#14532d;border:1px solid #86efac;",
    "SHA-256": "background:#fef3c7;color:#92400e;border:1px solid #f59e0b;",
    "HMAC-SHA256": "background:#fef3c7;color:#92400e;border:1px solid #f59e0b;",
    "ECDSA": "background:#fed7aa;color:#9a3412;border:1px solid #fb923c;",
    "ECDSA P-256": "background:#fed7aa;color:#9a3412;border:1px solid #fb923c;",
    "RSA": "background:#fecaca;color:#991b1b;border:1px solid #f87171;",
    "RSA-2048": "background:#fecaca;color:#991b1b;border:1px solid #f87171;",
}


def norm(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def run(cmd: list[str]) -> str:
    return subprocess.check_output(cmd, text=True, stderr=subprocess.STDOUT).strip()


def load_catalog() -> list[dict]:
    return json.loads(CATALOG_PATH.read_text())


def adb_devices() -> list[dict]:
    output = run(["adb", "devices", "-l"])
    devices = []
    for line in output.splitlines()[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) < 2 or parts[1] != "device":
            continue
        serial = parts[0]
        transport = "usb" if "usb:" in line else "wireless" if "adb-tls-connect" in line else "unknown"
        devices.append({"serial": serial, "transport": transport})
    return devices


def live_device_info(serial: str) -> dict:
    info = {
        "model": run(["adb", "-s", serial, "shell", "getprop", "ro.product.model"]),
        "androidVersion": run(["adb", "-s", serial, "shell", "getprop", "ro.build.version.release"]),
        "apiLevel": run(["adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"]),
        "build": run(["adb", "-s", serial, "shell", "getprop", "ro.build.display.id"]),
    }
    size_match = re.search(r"(\d+x\d+)", run(["adb", "-s", serial, "shell", "wm", "size"]))
    density_match = re.search(r"(\d+)", run(["adb", "-s", serial, "shell", "wm", "density"]))
    info["screenSize"] = size_match.group(1) if size_match else ""
    info["density"] = density_match.group(1) if density_match else ""
    props = {}
    for key in [
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.board.platform",
        "ro.hardware",
        "ro.soc.model",
        "ro.boot.hardware.chipname",
        "ro.build.display.id",
    ]:
        try:
            props[key] = run(["adb", "-s", serial, "shell", "getprop", key])
        except subprocess.CalledProcessError:
            props[key] = ""

    try:
        mem_total_kb = re.search(
            r"MemTotal:\s+(\d+) kB",
            run(["adb", "-s", serial, "shell", "cat /proc/meminfo | head -n 5"]),
        ).group(1)
    except Exception:
        mem_total_kb = ""

    try:
        data_df = run(["adb", "-s", serial, "shell", "df -k /data | tail -n 1 | tr -s ' '"])
    except subprocess.CalledProcessError:
        data_df = ""

    return {
        "model": info.get("model") or "",
        "release": info.get("androidVersion") or props["ro.build.version.release"],
        "sdk": int(info.get("apiLevel") or props["ro.build.version.sdk"] or 0),
        "screenSize": info.get("screenSize") or "",
        "density": info.get("density") or "",
        "build": info.get("build") or props["ro.build.display.id"] or "",
        "mem_total_kb": mem_total_kb,
        "df_data": data_df,
        "board_platform": props["ro.board.platform"],
        "soc_model": props["ro.soc.model"],
        "chipname": props["ro.boot.hardware.chipname"],
    }


def tier_memory(kb: str) -> str:
    if not kb:
        return "—"
    gb = round(int(kb) / 1024 / 1024)
    for tier in (2, 4, 6, 8, 12):
        if abs(tier - gb) <= 1:
            return f"{tier} GB"
    return f"{gb} GB"


def tier_storage(df_line: str) -> str:
    if not df_line:
        return "—"
    match = re.match(r"\S+\s+(\d+)\s+(\d+)\s+(\d+)\s+\d+%\s+(.+)$", df_line)
    if not match:
        return "—"
    total_gb = int(match.group(1)) / 1024 / 1024
    if total_gb <= 24:
        return "16 GB"
    if total_gb <= 48:
        return "32 GB"
    if total_gb <= 96:
        return "64 GB"
    if total_gb <= 192:
        return "128 GB"
    return "256 GB"


def android_version_display(release: str) -> str:
    if not release:
        return "—"
    parts = str(release).split(".")
    major = parts[0]
    minor = parts[1] if len(parts) > 1 else "0"
    return f"{major}.{minor}" if minor and minor != "0" else major


def chip_html(label: str, style: str) -> str:
    return (
        '<span style="display:inline-block;padding:0.12rem 0.45rem;border-radius:9999px;'
        'line-height:1;margin:0 0.2rem 0.2rem 0;white-space:nowrap;'
        f'{style}">{label}</span>'
    )


def crypto_chips(items: list[str]) -> str:
    chips = []
    for item in items:
        style = CRYPTO_STYLES.get(item, "background:#e5e7eb;color:#374151;border:1px solid #d1d5db;")
        chips.append(chip_html(item, style))
    return " ".join(chips) if chips else "—"


CRYPTO_LATENCY_STYLES = {
    "good": "background:#dcfce7;color:#166534;border:1px solid #86efac;",
    "warn": "background:#fef3c7;color:#92400e;border:1px solid #f59e0b;",
    "bad": "background:#fecaca;color:#991b1b;border:1px solid #f87171;font-weight:600;",
}

# (warn_threshold_us, bad_threshold_us) per operation family; a value at or below warn_threshold
# is "good", above warn_threshold up to bad_threshold is "warn", above bad_threshold is "bad".
CRYPTO_LATENCY_THRESHOLDS = {
    "x25519KeyGenUs": (1000.0, 5000.0),
    "x25519AgreementUs": (1000.0, 5000.0),
    "ed25519KeyGenUs": (1000.0, 10000.0),
    "ed25519SignUs": (1000.0, 10000.0),
    "ed25519VerifyUs": (1000.0, 10000.0),
    "chacha20SealUs": (100.0, 500.0),
    "chacha20OpenUs": (100.0, 500.0),
}


def crypto_latency_severity(field: str, value_us: float) -> str:
    warn_threshold, bad_threshold = CRYPTO_LATENCY_THRESHOLDS[field]
    if value_us > bad_threshold:
        return "bad"
    if value_us > warn_threshold:
        return "warn"
    return "good"


def crypto_latency_cell(field: str, value_us: float) -> str:
    style = CRYPTO_LATENCY_STYLES[crypto_latency_severity(field, value_us)]
    return chip_html(f"{value_us:.1f}", style)


def chipset_with_model(name: str, model: str) -> str:
    chip = chip_html(model, "background:#e5e7eb;color:#374151;border:1px solid #d1d5db;")
    return f"{name} {chip}" if model else name


def screen_display(static_screen: str, live: dict) -> str:
    if live.get("screenSize") and live.get("density"):
        diag = static_screen.split(" ", 1)[0]
        return f"{diag} ({live['screenSize']} @ {live['density']} dpi)"
    return static_screen


def links(gsma_url: str, ds_url: str) -> str:
    return f"[GSMArena]({gsma_url}) · [DeviceSpecifications]({ds_url})"


def build_rows(catalog: list[dict], live_by_model: dict[str, dict]) -> list[dict]:
    rows = []
    for item in catalog:
        key = norm(item["model"])
        live = live_by_model.get(key)
        if live:
            chipset_model = live.get("soc_model") or live.get("board_platform") or live.get("chipname") or item.get("chipset_model", "")
            row = {
                "device": item["device"],
                "brand": item["brand"],
                "model": item["model"],
                "android_sdk": f"Android {android_version_display(live['release'])} / SDK {live['sdk']}",
                "memory": tier_memory(live.get("mem_total_kb", "")),
                "storage": tier_storage(live.get("df_data", "")),
                "screen": screen_display(item["screen"], live),
                "chipset": chipset_with_model(item["chipset_name"], chipset_model),
                "bluetooth": item["bluetooth"],
                "crypto": crypto_chips(item["crypto"]),
                "build": live.get("build") or item["build"],
                "quirks": item.get("quirks") or "—",
                "links": links(item["gsma_url"], item.get("ds_url") or f"https://www.devicespecifications.com/index.php?action=search&language=en&search={quote(item['model'])}"),
                "sdk": live["sdk"],
                "bluetooth_sort": float(item["bluetooth"].split()[0]),
                "crypto_benchmark_history": item.get("crypto_benchmark_history") or [],
                "transport_benchmark_history": item.get("transport_benchmark_history") or [],
            }
        else:
            row = {
                "device": item["device"],
                "brand": item["brand"],
                "model": item["model"],
                "android_sdk": item["android_sdk"],
                "memory": item["memory"],
                "storage": item["storage"],
                "screen": item["screen"],
                "chipset": chipset_with_model(item["chipset_name"], item["chipset_model"]),
                "bluetooth": item["bluetooth"],
                "crypto": crypto_chips(item["crypto"]),
                "build": item["build"],
                "quirks": item.get("quirks") or "—",
                "links": links(item["gsma_url"], item.get("ds_url") or f"https://www.devicespecifications.com/index.php?action=search&language=en&search={quote(item['model'])}"),
                "sdk": int(re.search(r"SDK (\d+)", item["android_sdk"]).group(1)),
                "bluetooth_sort": float(item["bluetooth"].split()[0]),
                "crypto_benchmark_history": item.get("crypto_benchmark_history") or [],
                "transport_benchmark_history": item.get("transport_benchmark_history") or [],
            }
        rows.append(row)
    rows.sort(key=lambda r: (-r["sdk"], -r["bluetooth_sort"], r["device"].lower(), r["model"].lower()))
    return rows


def render_markdown(rows: list[dict]) -> str:
    lines = [
        "# Device test matrix reference",
        "",
        "Last verified: 2026-07-06",
        "",
        "This page tracks the Android devices currently attached to the MeshLink test",
        "bench and the device facts that matter for validation.",
        "",
        "Use it when you need to:",
        "",
        "- choose a device for a regression or integration run",
        "- compare OEM behavior across Android versions and SDK levels",
        "- add a new device to the fleet using the same reporting shape",
        "",
        "This matrix is rebuilt from live adb probes and GSMArena device pages.",
        "",
        "### Required refresh steps for new or changed devices",
        "",
        "1. Run `adb devices -l` and identify every currently attached device.",
        "2. Prefer a USB-attached row when the same hardware appears over both USB and wireless ADB.",
        "3. Query the live device with `adb shell getprop ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk`, and `adb shell wm size` / `wm density` for Android version, API level, display size, and density.",
        "4. Collect runtime facts with `adb shell getprop`, `cat /proc/meminfo`, `df -k /data`, and `dumpsys display`.",
        "5. Read the Bluetooth hardware/chipset model with `adb shell getprop ro.boot.hardware.chipname`.",
        "6. If that returns nothing, fall back to `adb shell getprop ro.board.platform`.",
        "7. Use the returned chipset model to look up the Bluetooth standard on the manufacturer site or on DeviceSpecifications.",
        "8. Use the GSMArena page for the human-readable device name, screen size, and chipset.",
        "9. Use the DeviceSpecifications search JSON for the device model, find the exact match, and copy the returned `url` field into the catalog entry.",
        "10. Update the existing row or add a new row; never remove devices from the list.",
        "11. Sort by descending Android SDK, then by Bluetooth standard.",
        "12. To record fleet benchmark results, run `meshlink-benchmark/scripts/run_fleet_meshlink_benchmark.py --mode crypto|transport --execute` and append the resulting per-device entries to the `crypto_benchmark_history` / `transport_benchmark_history` arrays in the matching `scripts/device-test-matrix.catalog.json` entry, then re-run `--write`.",
        "",
        "This matrix is generated by `python scripts/update_device_test_matrix.py --write`.",
        "",
        "Memory and storage values are rounded from device-reported totals to the nearest marketed tier used in the table.",
        "",
        "Crypto primitives are shown as colored chips: greener chips indicate more modern primitives, while orange/red chips indicate older or weaker primitives.",
        "",
        "The Quirks column and the Benchmark history tables are hand-maintained data carried in",
        "the `quirks`, `crypto_benchmark_history`, and `transport_benchmark_history` fields of each",
        "`scripts/device-test-matrix.catalog.json` entry; all three survive `--write` regeneration",
        "because they are read from the catalog, not the rendered Markdown.",
        "",
        "## Device list",
        "",
        "| Device | Brand | Model | Android / SDK | Memory | Storage | Screen | Chipset | Bluetooth | Crypto | Build | Quirks | Links |",
        "|---|---|---|---|---:|---:|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append(
            f"| {row['device']} | {row['brand']} | {row['model']} | {row['android_sdk']} | {row['memory']} | {row['storage']} | {row['screen']} | {row['chipset']} | {row['bluetooth']} | {row['crypto']} | {row['build']} | {row['quirks']} | {row['links']} |"
        )
    lines += [
        "",
        "## Notes",
        "",
        "- Never remove devices from this list; only update an existing row or add a new one.",
        "- Rows are sorted by descending Android SDK, then by Bluetooth standard.",
        "- No serial numbers or ADB transport IDs are published here.",
        "- The DeviceSpecifications link is the exact `url` field from the DeviceSpecifications search JSON for the device model.",
        "- Quirks capture evidence-backed caveats from the latest fleet pass; update them when a new run changes the observed failure mode.",
        "",
        "## Benchmark history",
        "",
        "Historical results from `meshlink-benchmark/scripts/run_fleet_meshlink_benchmark.py`",
        "(`--mode crypto` and `--mode transport`), recorded per device so performance and",
        "throughput regressions can be spotted across fleet passes. Entries are appended, not",
        "replaced, on every real fleet run. Timings are per-operation averages over the",
        "iteration count shown; a lower time is better.",
        "",
        "### Crypto benchmarks",
        "",
        "Values are per-operation average microseconds (µs) over the iteration count shown.",
        "Chips flag latency outliers per operation family: green is at or below the warn",
        "threshold, amber is above it, and bold red is above the bad threshold — X25519",
        "ops warn > 1000µs / bad > 5000µs, Ed25519 ops warn > 1000µs / bad > 10000µs, and",
        "ChaCha20-Poly1305 ops warn > 100µs / bad > 500µs. Status is ✅ when every op for",
        "that run is green, ⚠️ when the worst op is amber, and ❌ when the worst op is red.",
        "",
    ]
    crypto_entries = [(row, entry) for row in rows for entry in row.get("crypto_benchmark_history", [])]
    if not crypto_entries:
        lines.append("_No fleet crypto benchmark results recorded yet._")
    else:
        lines.append(
            "| Device | Date | Provider | Status | Iterations | x25519KeyGen (µs) | x25519Agreement (µs) | ed25519KeyGen (µs) | ed25519Sign (µs) | ed25519Verify (µs) | chacha20Seal (µs) | chacha20Open (µs) |"
        )
        lines.append("|---|---|---|:---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for row, entry in crypto_entries:
            severities = [
                crypto_latency_severity(field, entry[field])
                for field in CRYPTO_LATENCY_THRESHOLDS
            ]
            if "bad" in severities:
                status = "❌"
            elif "warn" in severities:
                status = "⚠️"
            else:
                status = "✅"
            lines.append(
                f"| {row['device']} | {entry['date']} | {entry['provider']} | {status} | {entry['iterations']} | "
                f"{crypto_latency_cell('x25519KeyGenUs', entry['x25519KeyGenUs'])} | {crypto_latency_cell('x25519AgreementUs', entry['x25519AgreementUs'])} | "
                f"{crypto_latency_cell('ed25519KeyGenUs', entry['ed25519KeyGenUs'])} | {crypto_latency_cell('ed25519SignUs', entry['ed25519SignUs'])} | "
                f"{crypto_latency_cell('ed25519VerifyUs', entry['ed25519VerifyUs'])} | {crypto_latency_cell('chacha20SealUs', entry['chacha20SealUs'])} | "
                f"{crypto_latency_cell('chacha20OpenUs', entry['chacha20OpenUs'])} |"
            )
    lines += [
        "",
        "### Transport benchmarks",
        "",
    ]
    transport_entries = [(row, entry) for row in rows for entry in row.get("transport_benchmark_history", [])]
    if not transport_entries:
        lines.append("_No fleet transport benchmark results recorded yet._")
    else:
        lines.append("| Device | Date | Role | Peer | Payload | Result | Throughput (KB/s) | Receipt |")
        lines.append("|---|---|---|---|---:|---|---:|---|")
        for row, entry in transport_entries:
            payload = f"{entry['payloadBytes'] // 1024} KiB"
            throughput = f"{entry['throughputKBps']:.2f}" if entry["throughputKBps"] is not None else "—"
            receipt = "✅" if entry["receiptConfirmed"] else "❌"
            lines.append(
                f"| {row['device']} | {entry['date']} | {entry['role']} | {entry['peer']} | {payload} | {entry['result']} | {throughput} | {receipt} |"
            )
    return "\n".join(lines).rstrip("\n") + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="Write the markdown to the default docs path")
    parser.add_argument("--check", action="store_true", help="Fail if the generated markdown differs from the docs path")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="Output path (defaults to docs/reference/device-test-matrix.md)")
    args = parser.parse_args()

    catalog = load_catalog()
    attached = {}
    for item in adb_devices():
        info = live_device_info(item["serial"])
        if info.get("model"):
            attached[norm(info["model"])] = info
    markdown = render_markdown(build_rows(catalog, attached))

    if args.check:
        current = args.out.read_text() if args.out.exists() else ""
        if current != markdown:
            sys.stdout.write(markdown)
            return 1
        return 0

    if args.write:
        args.out.write_text(markdown)
        return 0

    sys.stdout.write(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
