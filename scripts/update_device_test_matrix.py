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
    info = json.loads(run(["./tools/android", "device", "info", "--device", serial, "--json"]))
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


def chipset_with_model(name: str, model: str) -> str:
    chip = chip_html(model, "background:#e5e7eb;color:#374151;border:1px solid #d1d5db;")
    return f"{name} {chip}" if model else name


def screen_display(static_screen: str, live: dict) -> str:
    if live.get("screenSize") and live.get("density"):
        diag = static_screen.split(" ", 1)[0]
        return f"{diag} ({live['screenSize']} @ {live['density']} dpi)"
    return static_screen


def links(gsma_url: str, chip_model: str) -> str:
    ds_url = f"https://www.devicespecifications.com/en/search/{quote(chip_model)}"
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
                "links": links(item["gsma_url"], chipset_model),
                "sdk": live["sdk"],
                "bluetooth_sort": float(item["bluetooth"].split()[0]),
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
                "links": links(item["gsma_url"], item["chipset_model"]),
                "sdk": int(re.search(r"SDK (\d+)", item["android_sdk"]).group(1)),
                "bluetooth_sort": float(item["bluetooth"].split()[0]),
            }
        rows.append(row)
    rows.sort(key=lambda r: (-r["sdk"], -r["bluetooth_sort"], r["device"].lower(), r["model"].lower()))
    return rows


def render_markdown(rows: list[dict]) -> str:
    lines = [
        "# Device test matrix reference",
        "",
        "Last verified: 2026-06-18",
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
        "3. Query the live device with `./tools/android device info --device <serial> --json` for Android version, API level, display size, and density.",
        "4. Collect runtime facts with `adb shell getprop`, `cat /proc/meminfo`, `df -k /data`, and `dumpsys display`.",
        "5. Read the Bluetooth hardware/chipset model with `adb shell getprop ro.boot.hardware.chipname`.",
        "6. If that returns nothing, fall back to `adb shell getprop ro.board.platform`.",
        "7. Use the returned chipset model to look up the Bluetooth standard on the manufacturer site or on DeviceSpecifications.",
        "8. Use the GSMArena page for the human-readable device name, screen size, and chipset.",
        "9. Update the existing row or add a new row; never remove devices from the list.",
        "10. Sort by descending Android SDK, then by Bluetooth standard.",
        "",
        "This matrix is generated by `python scripts/update_device_test_matrix.py --write`.",
        "",
        "Memory and storage values are rounded from device-reported totals to the nearest marketed tier used in the table.",
        "",
        "Crypto primitives are shown as colored chips: greener chips indicate more modern primitives, while orange/red chips indicate older or weaker primitives.",
        "",
        "## Device list",
        "",
        "| Device | Brand | Model | Android / SDK | Memory | Storage | Screen | Chipset | Bluetooth | Crypto | Build | Links |",
        "|---|---|---|---|---:|---:|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append(
            f"| {row['device']} | {row['brand']} | {row['model']} | {row['android_sdk']} | {row['memory']} | {row['storage']} | {row['screen']} | {row['chipset']} | {row['bluetooth']} | {row['crypto']} | {row['build']} | {row['links']} |"
        )
    lines += [
        "",
        "## Notes",
        "",
        "- Never remove devices from this list; only update an existing row or add a new one.",
        "- Rows are sorted by descending Android SDK, then by Bluetooth standard.",
        "- No serial numbers or ADB transport IDs are published here.",
        "- The DeviceSpecifications link is a chipset-model lookup, so update the model field before regenerating the table if the chipset changes.",
    ]
    return "\n".join(lines) + "\n"


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
