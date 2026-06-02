#!/usr/bin/env python3

from __future__ import annotations

import argparse
import html
import json
from pathlib import Path
from typing import Any, Mapping

DEFAULT_INPUT_NAME = "report-data.json"
DEFAULT_OUTPUT_NAME = "release-review-report.html"
VERDICT_ORDER = ["pass", "fail", "skipped", "inconclusive", "invalid-environment"]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Render a self-contained release-review HTML report from retained report-data.json."
    )
    parser.add_argument("--run-root", required=True, help="Retained run root that contains report-data.json")
    parser.add_argument(
        "--report-data",
        help=f"Optional report-data.json path. Defaults to <run-root>/{DEFAULT_INPUT_NAME}",
    )
    parser.add_argument(
        "--output-html",
        help=f"Optional HTML output path. Defaults to <run-root>/{DEFAULT_OUTPUT_NAME}",
    )
    return parser.parse_args(argv)


def load_json_object(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{path.name} must contain a JSON object")
    return payload


def as_text(value: object | None) -> str:
    if value is None:
        return "—"
    text = str(value).strip()
    return text or "—"


def escape_text(value: object | None) -> str:
    return html.escape(as_text(value), quote=True)


def escape_attr(value: object | None) -> str:
    return html.escape(as_text(value), quote=True)


def format_percent(value: object | None) -> str:
    if value is None:
        return "—"
    try:
        return f"{float(value) * 100:.1f}%"
    except (TypeError, ValueError):
        return as_text(value)


def render_metric_card(label: str, value: object | None, *, note: str | None = None) -> str:
    note_html = f'<div class="metric-note">{escape_text(note)}</div>' if note else ""
    return (
        '<section class="metric-card">'
        f'<div class="metric-label">{escape_text(label)}</div>'
        f'<div class="metric-value">{escape_text(value)}</div>'
        f"{note_html}"
        "</section>"
    )


def render_list(items: list[object]) -> str:
    if not items:
        return '<p class="muted">None.</p>'
    return "<ul>" + "".join(f"<li>{escape_text(item)}</li>" for item in items) + "</ul>"


def render_json_block(payload: object) -> str:
    return f'<pre class="json-block">{escape_text(json.dumps(payload, indent=2, sort_keys=True))}</pre>'


def render_optional_json_block(payload: object | None, empty_message: str) -> str:
    if payload is None:
        return f'<p class="muted">{escape_text(empty_message)}</p>'
    return render_json_block(payload)


def render_artifact_link(label: str, path_value: object | None) -> str:
    if path_value is None:
        return f'<span class="artifact artifact-missing">{escape_text(label)}: —</span>'
    href = html.escape(str(path_value), quote=True)
    return (
        f'<a class="artifact" href="{href}">'
        f'<span class="artifact-label">{escape_text(label)}</span>'
        f'<span class="artifact-path">{escape_text(path_value)}</span>'
        "</a>"
    )


def render_verdict_badge(verdict: object | None) -> str:
    normalized = str(verdict or "inconclusive")
    return f'<span class="badge verdict verdict-{html.escape(normalized, quote=True)}">{escape_text(normalized)}</span>'


def render_source_paths(report_data: Mapping[str, Any]) -> str:
    source_files = report_data.get("sourceFiles") if isinstance(report_data.get("sourceFiles"), Mapping) else {}
    campaign_plan = source_files.get("campaignPlan") if isinstance(source_files, Mapping) else None
    campaign_state = source_files.get("campaignState") if isinstance(source_files, Mapping) else None
    run_root = report_data.get("runRoot")
    return (
        '<section class="panel">'
        '<h2>Inputs</h2>'
        '<dl class="definition-list">'
        f'<div><dt>Run root</dt><dd>{escape_text(run_root)}</dd></div>'
        f'<div><dt>Campaign plan</dt><dd>{escape_text(campaign_plan)}</dd></div>'
        f'<div><dt>Campaign state</dt><dd>{escape_text(campaign_state)}</dd></div>'
        '</dl>'
        '</section>'
    )


def render_run_classification(report_data: Mapping[str, Any]) -> str:
    classification = report_data.get("runClassification") if isinstance(report_data.get("runClassification"), Mapping) else {}
    reasons = classification.get("reasons") if isinstance(classification, Mapping) else []
    if not isinstance(reasons, list):
        reasons = []
    return (
        '<section class="panel">'
        '<h2>Run classification</h2>'
        '<dl class="definition-list">'
        f'<div><dt>Status</dt><dd>{escape_text(classification.get("status") if isinstance(classification, Mapping) else None)}</dd></div>'
        f'<div><dt>Reasons</dt><dd>{render_list(reasons)}</dd></div>'
        '</dl>'
        '</section>'
    )


def render_gate_math(report_data: Mapping[str, Any]) -> str:
    gate_math = report_data.get("gateMath") if isinstance(report_data.get("gateMath"), Mapping) else {}
    return (
        '<section class="panel">'
        '<h2>Gate math</h2>'
        '<div class="metric-grid">'
        f'{render_metric_card("Status", gate_math.get("status"))}'
        f'{render_metric_card("Total scenarios", gate_math.get("totalScenarios"))}'
        f'{render_metric_card("Runnable scenarios", gate_math.get("runnableScenarios"))}'
        f'{render_metric_card("Terminal scenarios", gate_math.get("terminalScenarios"))}'
        f'{render_metric_card("Pass rate", format_percent(gate_math.get("passRate")))}'
        f'{render_metric_card("Failure rate", format_percent(gate_math.get("failureRate")))}'
        f'{render_metric_card("Inconclusive rate", format_percent(gate_math.get("inconclusiveRate")))}'
        '</div>'
        '</section>'
    )


def render_verdict_counts(report_data: Mapping[str, Any]) -> str:
    verdict_counts = report_data.get("verdictCounts") if isinstance(report_data.get("verdictCounts"), Mapping) else {}
    cards = []
    for verdict in VERDICT_ORDER:
        cards.append(render_metric_card(verdict, verdict_counts.get(verdict)))
    return (
        '<section class="panel">'
        '<div class="panel-heading">'
        '<h2>Verdict counts</h2>'
        '<div class="panel-actions">'
        '<button type="button" data-toggle-all="open">Expand all</button>'
        '<button type="button" data-toggle-all="close">Collapse all</button>'
        '</div>'
        '</div>'
        '<div class="metric-grid verdict-grid">'
        + "".join(cards)
        + '</div>'
        '</section>'
    )


def render_scenario_artifacts(scenario: Mapping[str, Any]) -> str:
    artifacts = scenario.get("artifacts") if isinstance(scenario.get("artifacts"), Mapping) else {}
    artifact_links = [
        render_artifact_link("summary", artifacts.get("summary")),
        render_artifact_link("analysis.json", artifacts.get("analysisJson")),
        render_artifact_link("analysis.md", artifacts.get("analysisMarkdown")),
    ]
    return '<div class="artifact-list">' + "".join(artifact_links) + '</div>'


def render_scenario_details(scenario: Mapping[str, Any]) -> str:
    evidence = scenario.get("evidence") if isinstance(scenario.get("evidence"), Mapping) else {}
    summary_payload = scenario.get("summary") if isinstance(scenario.get("summary"), Mapping) else None
    analysis_payload = scenario.get("analysis") if isinstance(scenario.get("analysis"), Mapping) else None
    evidence_issues = scenario.get("evidenceIssues") if isinstance(scenario.get("evidenceIssues"), list) else []
    return (
        '<details class="scenario-details" data-drilldown>'
        '<summary>Drill down</summary>'
        '<div class="details-grid">'
        '<section>'
        '<h4>Retained evidence</h4>'
        f'{render_json_block(evidence)}'
        '</section>'
        '<section>'
        '<h4>Summary payload</h4>'
        f'{render_optional_json_block(summary_payload, "No summary payload retained.")}'
        '</section>'
        '<section>'
        '<h4>Analysis payload</h4>'
        f'{render_optional_json_block(analysis_payload, "No analysis payload retained.")}'
        '</section>'
        '<section>'
        '<h4>Evidence issues</h4>'
        f'{render_list(list(evidence_issues))}'
        '</section>'
        '</div>'
        '</details>'
    )


def render_scenario_card(scenario: Mapping[str, Any]) -> str:
    header_bits = [
        f"#{escape_text(scenario.get('order'))}" if scenario.get("order") is not None else "#—",
        escape_text(scenario.get("scenarioId")),
        render_verdict_badge(scenario.get("verdict")),
    ]
    summary_line = " · ".join(header_bits)
    return (
        '<article class="scenario-card" id="scenario-{}">'.format(
            html.escape(as_text(scenario.get("scenarioId")), quote=True).replace(" ", "-")
        )
        + '<header class="scenario-header">'
        + f'<div class="scenario-title">{summary_line}</div>'
        + '<div class="scenario-meta">'
        + f'<span>Eligibility: <strong>{escape_text(scenario.get("eligibilityStatus"))}</strong></span>'
        + f'<span>Status: <strong>{escape_text(scenario.get("status"))}</strong></span>'
        + f'<span>Analysis: <strong>{escape_text(scenario.get("analysisStatus"))}</strong></span>'
        + f'<span>Summary: <strong>{escape_text(scenario.get("summaryStatus"))}</strong></span>'
        + '</div>'
        + '</header>'
        + '<div class="scenario-body">'
        + '<div class="scenario-fields">'
        + f'<div><span class="field-label">Assignment</span><span>{escape_text(scenario.get("assignmentId"))} / {escape_text(scenario.get("assignmentShape"))}</span></div>'
        + f'<div><span class="field-label">Baseline</span><span>{escape_text(scenario.get("baseline"))}</span></div>'
        + f'<div><span class="field-label">Complete evidence</span><span>{escape_text("yes" if scenario.get("completeEvidence") else "no")}</span></div>'
        + '</div>'
        + render_scenario_artifacts(scenario)
        + render_scenario_details(scenario)
        + '</div>'
        + '</article>'
    )


def render_scenarios(report_data: Mapping[str, Any]) -> str:
    scenarios = report_data.get("scenarios") if isinstance(report_data.get("scenarios"), list) else []
    cards = [render_scenario_card(scenario) for scenario in scenarios if isinstance(scenario, Mapping)]
    return (
        '<section class="panel">'
        '<h2>Scenario summaries</h2>'
        '<div class="scenario-list">'
        + "".join(cards)
        + '</div>'
        '</section>'
    )


def render_campaign(report_data: Mapping[str, Any]) -> str:
    campaign = report_data.get("campaign") if isinstance(report_data.get("campaign"), Mapping) else {}
    happy_path_gate = campaign.get("happyPathGate") if isinstance(campaign, Mapping) and isinstance(campaign.get("happyPathGate"), Mapping) else {}
    return (
        '<section class="panel">'
        '<h2>Campaign status</h2>'
        '<dl class="definition-list">'
        f'<div><dt>Plan status</dt><dd>{escape_text(campaign.get("planStatus") if isinstance(campaign, Mapping) else None)}</dd></div>'
        f'<div><dt>Campaign status</dt><dd>{escape_text(campaign.get("status") if isinstance(campaign, Mapping) else None)}</dd></div>'
        f'<div><dt>Happy-path gate</dt><dd>{escape_text(happy_path_gate.get("status"))}</dd></div>'
        f'<div><dt>First fail scenario</dt><dd>{escape_text(happy_path_gate.get("firstFailScenarioId"))}</dd></div>'
        '</dl>'
        '</section>'
    )


def render_header(report_data: Mapping[str, Any]) -> str:
    return (
        '<header class="hero">'
        '<p class="eyebrow">MeshLink release review</p>'
        '<h1>Standalone offline review report</h1>'
        '<p class="lede">A self-contained HTML view over retained report-data.json with honest verdict taxonomy, gate math, and drill-down evidence links.</p>'
        '<div class="hero-meta">'
        f'<span>Report data version: <strong>{escape_text(report_data.get("reportDataVersion"))}</strong></span>'
        f'<span>Generated at: <strong>{escape_text(report_data.get("generatedAt"))}</strong></span>'
        f'<span>Run root: <strong>{escape_text(report_data.get("runRoot"))}</strong></span>'
        '</div>'
        '</header>'
    )


def render_report(report_data: Mapping[str, Any]) -> str:
    return (
        '<!doctype html>'
        '<html lang="en">'
        '<head>'
        '<meta charset="utf-8" />'
        '<meta name="viewport" content="width=device-width, initial-scale=1" />'
        '<title>MeshLink release review report</title>'
        '<style>'
        ':root { color-scheme: light; font-family: Inter, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; '
        'background: #f6f7fb; color: #172033; }'
        'body { margin: 0; background: linear-gradient(180deg, #f8fafc 0, #eef2ff 100%); color: #172033; }'
        '.page { max-width: 1200px; margin: 0 auto; padding: 32px 20px 64px; }'
        '.hero { background: #111827; color: #f8fafc; border-radius: 24px; padding: 28px 30px; box-shadow: 0 24px 60px rgba(15, 23, 42, 0.18); }'
        '.eyebrow { margin: 0 0 8px; text-transform: uppercase; letter-spacing: 0.12em; font-size: 12px; color: #a5b4fc; }'
        'h1 { margin: 0; font-size: clamp(2rem, 4vw, 3.2rem); line-height: 1.05; }'
        '.lede { margin: 12px 0 0; max-width: 80ch; color: #dbeafe; }'
        '.hero-meta { display: flex; flex-wrap: wrap; gap: 12px 20px; margin-top: 18px; font-size: 14px; color: #d1d5db; }'
        '.layout { display: grid; gap: 20px; margin-top: 24px; }'
        '.panel { background: rgba(255, 255, 255, 0.92); border: 1px solid rgba(148, 163, 184, 0.18); border-radius: 20px; padding: 22px; box-shadow: 0 14px 36px rgba(15, 23, 42, 0.06); backdrop-filter: blur(8px); }'
        '.panel h2 { margin: 0 0 16px; font-size: 1.2rem; }'
        '.panel-heading { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 16px; }'
        '.panel-actions { display: flex; gap: 10px; flex-wrap: wrap; }'
        'button { border: 0; border-radius: 999px; padding: 10px 16px; background: #2563eb; color: white; font-weight: 600; cursor: pointer; }'
        'button:hover { background: #1d4ed8; }'
        '.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }'
        '.metric-card { border-radius: 16px; background: #f8fafc; border: 1px solid #e2e8f0; padding: 14px 16px; }'
        '.metric-label { font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 6px; }'
        '.metric-value { font-size: 1.3rem; font-weight: 700; color: #0f172a; }'
        '.metric-note { margin-top: 6px; font-size: 12px; color: #64748b; }'
        '.definition-list { display: grid; gap: 12px; margin: 0; }'
        '.definition-list > div { display: grid; gap: 4px; }'
        '.definition-list dt { font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; }'
        '.definition-list dd { margin: 0; color: #0f172a; }'
        '.muted { color: #64748b; }'
        '.verdict { display: inline-flex; align-items: center; border-radius: 999px; padding: 4px 10px; font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; }'
        '.verdict-pass { background: #dcfce7; color: #166534; }'
        '.verdict-fail { background: #fee2e2; color: #991b1b; }'
        '.verdict-skipped { background: #e0e7ff; color: #3730a3; }'
        '.verdict-inconclusive { background: #fef3c7; color: #92400e; }'
        '.verdict-invalid-environment { background: #e2e8f0; color: #334155; }'
        '.scenario-list { display: grid; gap: 16px; }'
        '.scenario-card { border-radius: 18px; border: 1px solid #e2e8f0; background: white; overflow: hidden; }'
        '.scenario-header { display: grid; gap: 10px; padding: 18px 18px 12px; background: linear-gradient(180deg, #ffffff 0, #f8fafc 100%); }'
        '.scenario-title { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; font-weight: 700; font-size: 1.05rem; }'
        '.scenario-meta { display: flex; gap: 14px 18px; flex-wrap: wrap; color: #475569; font-size: 14px; }'
        '.scenario-body { padding: 0 18px 18px; display: grid; gap: 14px; }'
        '.scenario-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px 16px; color: #0f172a; }'
        '.field-label { display: block; font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 4px; }'
        '.artifact-list { display: flex; flex-wrap: wrap; gap: 10px; }'
        '.artifact { display: inline-flex; flex-direction: column; gap: 2px; min-width: 180px; text-decoration: none; border: 1px solid #dbe4f0; background: #f8fafc; border-radius: 14px; padding: 10px 12px; color: #0f172a; }'
        '.artifact:hover { border-color: #94a3b8; background: white; }'
        '.artifact-label { font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; }'
        '.artifact-path { word-break: break-all; font-size: 14px; }'
        '.artifact-missing { color: #64748b; }'
        '.scenario-details { border: 1px solid #e2e8f0; border-radius: 16px; padding: 0 14px 14px; background: #f8fafc; }'
        '.scenario-details > summary { cursor: pointer; list-style: none; padding: 14px 4px; font-weight: 700; color: #0f172a; }'
        '.scenario-details > summary::-webkit-details-marker { display: none; }'
        '.details-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 14px; padding: 0 4px 4px; }'
        '.details-grid section { background: white; border: 1px solid #e2e8f0; border-radius: 14px; padding: 12px; }'
        '.details-grid h4 { margin: 0 0 10px; font-size: 0.98rem; }'
        '.json-block { margin: 0; padding: 12px; overflow: auto; border-radius: 12px; background: #0f172a; color: #e2e8f0; font-size: 12px; line-height: 1.5; }'
        '.footer { margin-top: 20px; color: #64748b; font-size: 13px; }'
        '</style>'
        '<script>'
        'document.addEventListener("click", function (event) {'
        '  const button = event.target.closest("[data-toggle-all]");'
        '  if (!button) return;'
        '  const open = button.getAttribute("data-toggle-all") === "open";'
        '  document.querySelectorAll("details[data-drilldown]").forEach(function (details) { details.open = open; });'
        '});'
        '</script>'
        '</head>'
        '<body>'
        '<main class="page">'
        f'{render_header(report_data)}'
        '<section class="layout">'
        f'{render_campaign(report_data)}'
        f'{render_source_paths(report_data)}'
        f'{render_run_classification(report_data)}'
        f'{render_verdict_counts(report_data)}'
        f'{render_gate_math(report_data)}'
        f'{render_scenarios(report_data)}'
        '</section>'
        '<p class="footer">Rendered from retained report-data.json only. Evidence links remain relative to the run root so the report can be opened offline alongside the archived campaign artifacts.</p>'
        '</main>'
        '</body>'
        '</html>'
    )


def render_release_review_report(run_root: Path, *, report_data_path: Path | None = None, output_html_path: Path | None = None) -> Path:
    resolved_report_data_path = report_data_path or (run_root / DEFAULT_INPUT_NAME)
    resolved_output_html_path = output_html_path or (run_root / DEFAULT_OUTPUT_NAME)
    report_data = load_json_object(resolved_report_data_path)
    html_text = render_report(report_data)
    resolved_output_html_path.parent.mkdir(parents=True, exist_ok=True)
    resolved_output_html_path.write_text(html_text, encoding="utf-8")
    return resolved_output_html_path


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    run_root = Path(args.run_root)
    report_data_path = Path(args.report_data) if args.report_data else None
    output_html_path = Path(args.output_html) if args.output_html else None
    render_release_review_report(run_root, report_data_path=report_data_path, output_html_path=output_html_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
