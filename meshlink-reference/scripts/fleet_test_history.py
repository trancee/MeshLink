#!/usr/bin/env python3

from __future__ import annotations

import argparse
import html
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, Mapping

DEFAULT_HISTORY_DIR = Path(__file__).resolve().parents[1] / "fleet-test-history"
DEFAULT_HISTORY_JSON = DEFAULT_HISTORY_DIR / "history.json"
DEFAULT_OUTPUT_HTML = DEFAULT_HISTORY_DIR / "index.html"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Maintain and render the MeshLink fleet-test history HTML artifact."
    )
    parser.add_argument(
        "--history-json",
        help=f"Path to the fleet-test history ledger. Defaults to {DEFAULT_HISTORY_JSON}",
    )
    parser.add_argument(
        "--output-html",
        help=f"Path to the rendered HTML report. Defaults to {DEFAULT_OUTPUT_HTML}",
    )
    parser.add_argument(
        "--append-entry-json",
        help="Optional JSON file containing one history entry to append before rendering.",
    )
    return parser.parse_args(argv)


def load_json_object(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{path.name} must contain a JSON object")
    return payload


def load_history(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ValueError(f"{path.name} must contain a JSON array")
    history: list[dict[str, Any]] = []
    for entry in payload:
        if not isinstance(entry, dict):
            continue
        history.append(entry)
    return history


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def text(value: object | None, default: str = "—") -> str:
    if value is None:
        return default
    rendered = str(value).strip()
    return rendered or default


def escape(value: object | None, default: str = "—") -> str:
    return html.escape(text(value, default=default), quote=True)


def normalize_devices(devices: object) -> list[dict[str, Any]]:
    if not isinstance(devices, list):
        return []
    result: list[dict[str, Any]] = []
    for item in devices:
        if isinstance(item, dict):
            result.append(item)
    return result


def normalize_results(results: object) -> list[dict[str, Any]]:
    if not isinstance(results, list):
        return []
    result: list[dict[str, Any]] = []
    for item in results:
        if isinstance(item, dict):
            result.append(item)
    return result


def comparison_note(entry: Mapping[str, Any], previous: Mapping[str, Any] | None) -> str:
    explicit = text(entry.get("comparison"), default="")
    if explicit != "":
        return explicit
    if previous is None:
        return "first recorded run"

    current_devices = len(normalize_devices(entry.get("devices")))
    previous_devices = len(normalize_devices(previous.get("devices")))
    current_result = text(entry.get("campaign_result"))
    previous_result = text(previous.get("campaign_result"))
    if current_devices == previous_devices and current_result == previous_result:
        return f"same device count and result as the previous run ({previous_result})"
    return (
        f"compared with the previous run ({previous_result}), this run changed the fleet shape from "
        f"{previous_devices} device(s) to {current_devices} device(s)"
    )


def summarize_devices(entry: Mapping[str, Any]) -> str:
    devices = normalize_devices(entry.get("devices"))
    if not devices:
        return "No device details recorded."
    aliases = [text(device.get("alias")) for device in devices if text(device.get("alias")) != "—"]
    platform_counts: dict[str, int] = {}
    for device in devices:
        platform = text(device.get("platform"), default="unknown")
        platform_counts[platform] = platform_counts.get(platform, 0) + 1
    platform_summary = ", ".join(
        f"{count} {platform.capitalize() if platform not in {'ios'} else 'iOS'} device(s)"
        for platform, count in sorted(platform_counts.items())
    )
    alias_preview = ", ".join(aliases[:4])
    if len(aliases) > 4:
        alias_preview += f" … (+{len(aliases) - 4} more)"
    return f"{len(devices)} device(s): {platform_summary}. {alias_preview}"


def render_device_table(devices: Iterable[Mapping[str, Any]]) -> str:
    rows = []
    for device in devices:
        rows.append(
            "<tr>"
            f"<td>{escape(device.get('alias'))}</td>"
            f"<td>{escape(device.get('platform'))}</td>"
            f"<td>{escape(device.get('role'))}</td>"
            f"<td>{escape('yes' if device.get('available') else 'no')}</td>"
            "</tr>"
        )
    if not rows:
        return '<p class="muted">No device details recorded.</p>'
    return (
        '<table class="device-table">'
        '<thead><tr><th>Alias</th><th>Platform</th><th>Role</th><th>Available</th></tr></thead>'
        f"<tbody>{''.join(rows)}</tbody>"
        '</table>'
    )


def render_results_list(results: Iterable[Mapping[str, Any]]) -> str:
    items = []
    for result in results:
        items.append(
            "<li>"
            f"<strong>{escape(result.get('scenario'))}</strong> — {escape(result.get('status'))}"
            f" <span class='muted'>(eligibility: {escape(result.get('eligibility'))})</span>"
            "</li>"
        )
    if not items:
        return '<p class="muted">No scenario results recorded.</p>'
    return "<ul>" + "".join(items) + "</ul>"


def render_entry(entry: Mapping[str, Any], previous: Mapping[str, Any] | None) -> str:
    devices = normalize_devices(entry.get("devices"))
    results = normalize_results(entry.get("results"))
    return (
        '<article class="entry">'
        '<header class="entry-header">'
        f"<h2>{escape(entry.get('title'))}</h2>"
        f"<div class='entry-meta'><span>Date: <strong>{escape(entry.get('date'))}</strong></span>"
        f"<span>Result: <strong>{escape(entry.get('campaign_result'))}</strong></span>"
        f"<span>Selection: <strong>{escape(entry.get('selection'))}</strong></span></div>"
        '</header>'
        '<div class="entry-summary">'
        f"<p><strong>Tested:</strong> {escape(entry.get('tested'))}</p>"
        f"<p><strong>Devices:</strong> {escape(summarize_devices(entry))}</p>"
        f"<p><strong>Comparison:</strong> {escape(comparison_note(entry, previous))}</p>"
        f"<p><strong>Gate:</strong> {escape(entry.get('gate'))} / {escape(entry.get('report_gate'))}</p>"
        '</div>'
        '<div class="entry-grid">'
        '<section>'
        '<h3>Device matrix</h3>'
        f"{render_device_table(devices)}"
        '</section>'
        '<section>'
        '<h3>Scenario results</h3>'
        f"{render_results_list(results)}"
        '</section>'
        '</div>'
        '</article>'
    )


def render_history(history: list[dict[str, Any]]) -> str:
    entries = sorted(history, key=lambda entry: text(entry.get("date"), default=""))
    previous_lookup: dict[str, Any] | None = None
    rendered_entries: list[str] = []
    for entry in entries:
        rendered_entries.append(render_entry(entry, previous_lookup))
        previous_lookup = entry

    total_runs = len(entries)
    latest_entry = entries[-1] if entries else {}
    result_counts: dict[str, int] = {}
    for entry in entries:
        result = text(entry.get("campaign_result"), default="unknown")
        result_counts[result] = result_counts.get(result, 0) + 1
    result_summary = ", ".join(f"{result}: {count}" for result, count in sorted(result_counts.items())) or "No runs recorded yet."

    return (
        '<!doctype html>'
        '<html lang="en">'
        '<head>'
        '<meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        '<title>MeshLink fleet-test history</title>'
        '<style>'
        'body{margin:0;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#f7fafc;color:#102a43;line-height:1.5}'
        '.page{max-width:1100px;margin:0 auto;padding:32px 20px 48px}'
        '.hero{background:#ffffff;border:1px solid #d9e2ec;border-radius:16px;padding:24px 28px;margin-bottom:20px;box-shadow:0 4px 20px rgba(16,42,67,.06)}'
        '.hero h1{margin:0 0 8px;font-size:2rem}'
        '.hero p{margin:0 0 10px;color:#486581}'
        '.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin-top:18px}'
        '.stat{background:#f8fafc;border:1px solid #d9e2ec;border-radius:12px;padding:14px}'
        '.stat .label{font-size:.8rem;text-transform:uppercase;letter-spacing:.06em;color:#627d98}'
        '.stat .value{font-size:1.5rem;font-weight:700;margin-top:4px}'
        '.history{display:grid;gap:16px}'
        '.entry{background:#fff;border:1px solid #d9e2ec;border-radius:16px;padding:20px 22px;box-shadow:0 3px 16px rgba(16,42,67,.05)}'
        '.entry-header{display:flex;flex-wrap:wrap;gap:10px 16px;align-items:baseline;justify-content:space-between}'
        '.entry-header h2{margin:0;font-size:1.25rem}'
        '.entry-meta{display:flex;flex-wrap:wrap;gap:10px 16px;color:#486581;font-size:.95rem}'
        '.entry-summary{margin-top:12px;padding:12px 14px;background:#f8fafc;border-radius:12px;border:1px solid #e2e8f0}'
        '.entry-summary p{margin:0 0 6px}'
        '.entry-summary p:last-child{margin-bottom:0}'
        '.entry-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px;margin-top:16px}'
        '.entry-grid section{background:#fdfefe;border:1px solid #e2e8f0;border-radius:12px;padding:14px}'
        '.entry-grid h3{margin:0 0 10px;font-size:1rem}'
        '.device-table{width:100%;border-collapse:collapse;font-size:.95rem}'
        '.device-table th,.device-table td{text-align:left;padding:8px 6px;border-bottom:1px solid #e2e8f0;vertical-align:top}'
        '.device-table th{font-size:.8rem;text-transform:uppercase;letter-spacing:.05em;color:#627d98}'
        '.muted{color:#627d98}'
        'ul{margin:0;padding-left:20px}'
        'li{margin:6px 0}'
        '@media (max-width:640px){.page{padding:16px 12px 28px}.hero{padding:18px}.entry{padding:16px}.entry-header{display:block}.entry-meta{margin-top:6px}}'
        '</style>'
        '</head>'
        '<body>'
        '<main class="page">'
        '<section class="hero">'
        '<p class="muted">MeshLink fleet-test history artifact</p>'
        '<h1>Fleet test history</h1>'
        '<p>Concise HTML history of repeated fleet tests, rendered from the repo-visible JSON ledger only.</p>'
        '<p class="muted">Use this to compare what was tested, on which devices, and what result each run produced.</p>'
        '<div class="stats">'
        f'<div class="stat"><div class="label">Recorded runs</div><div class="value">{total_runs}</div></div>'
        f'<div class="stat"><div class="label">Latest run</div><div class="value">{escape(latest_entry.get("date"))}</div></div>'
        f'<div class="stat"><div class="label">Latest result</div><div class="value">{escape(latest_entry.get("campaign_result"))}</div></div>'
        f'<div class="stat"><div class="label">Result mix</div><div class="value">{escape(result_summary)}</div></div>'
        '</div>'
        '</section>'
        '<section class="history">'
        + "".join(reversed(rendered_entries))
        + '</section>'
        '</main>'
        '</body>'
        '</html>'
    )


def append_entry(history: list[dict[str, Any]], entry: Mapping[str, Any]) -> list[dict[str, Any]]:
    normalized = dict(entry)
    normalized.setdefault("date", datetime.now().strftime("%Y-%m-%d"))
    normalized.setdefault("title", "Unnamed fleet test")
    normalized.setdefault("tested", normalized.get("title"))
    normalized.setdefault("campaign_result", "unknown")
    normalized.setdefault("selection", "unknown")
    normalized.setdefault("gate", "unknown")
    normalized.setdefault("report_gate", "unknown")
    normalized.setdefault("devices", [])
    normalized.setdefault("results", [])
    normalized.setdefault("comparison", "")

    updated = list(history)
    updated.append(normalized)
    return updated


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    history_json = Path(args.history_json) if args.history_json else DEFAULT_HISTORY_JSON
    output_html = Path(args.output_html) if args.output_html else DEFAULT_OUTPUT_HTML
    history = load_history(history_json)

    if args.append_entry_json:
        entry = load_json_object(Path(args.append_entry_json))
        history = append_entry(history, entry)
        write_json(history_json, history)
    elif not history_json.exists():
        write_json(history_json, history)

    html_text = render_history(history)
    output_html.parent.mkdir(parents=True, exist_ok=True)
    output_html.write_text(html_text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
