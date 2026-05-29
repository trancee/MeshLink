#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BENCHMARK_SOURCE_ROOT = REPO_ROOT / "benchmarks" / "src" / "jvmMain" / "kotlin"
SMOKE_REPORT_ROOT = REPO_ROOT / "benchmarks" / "build" / "reports" / "benchmarks" / "smoke"

PACKAGE_PATTERN = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*$")
CLASS_PATTERN = re.compile(r"\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\b")
FUNCTION_PATTERN = re.compile(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
QUOTED_STRING_PATTERN = re.compile(r'"([^"\\]|\\.)*"')


@dataclass
class BenchmarkClass:
    package_name: str
    class_name: str
    benchmark_methods: list[str] = field(default_factory=list)
    param_multiplier: int = 1


def parse_expected_benchmark_counts(source_root: Path) -> Counter[str]:
    expected_counts: Counter[str] = Counter()

    for path in sorted(source_root.rglob("*.kt")):
        package_name = ""
        pending_state_class = False
        pending_benchmark_method = False
        brace_depth = 0
        current_class: BenchmarkClass | None = None
        current_class_depth = -1
        benchmark_classes: list[BenchmarkClass] = []

        for line in path.read_text(encoding="utf-8").splitlines():
            if not package_name:
                package_match = PACKAGE_PATTERN.match(line)
                if package_match:
                    package_name = package_match.group(1)

            stripped = line.strip()

            if current_class is not None and brace_depth == current_class_depth and "@Param(" in stripped:
                value_count = len(QUOTED_STRING_PATTERN.findall(stripped))
                if value_count == 0:
                    raise SystemExit(f"Could not parse @Param values in {path}: {stripped}")
                current_class.param_multiplier *= value_count

            if current_class is not None and brace_depth == current_class_depth and "@Benchmark" in stripped:
                function_match = FUNCTION_PATTERN.search(stripped)
                if function_match:
                    current_class.benchmark_methods.append(function_match.group(1))
                    pending_benchmark_method = False
                else:
                    pending_benchmark_method = True
            elif pending_benchmark_method and current_class is not None and brace_depth == current_class_depth:
                function_match = FUNCTION_PATTERN.search(stripped)
                if function_match:
                    current_class.benchmark_methods.append(function_match.group(1))
                    pending_benchmark_method = False
                elif stripped and not stripped.startswith("@"):
                    raise SystemExit(
                        f"Expected a benchmark method declaration after @Benchmark in {path}: {stripped}"
                    )

            if "@State(" in stripped:
                pending_state_class = True

            opens = line.count("{")
            closes = line.count("}")
            class_match = CLASS_PATTERN.search(stripped)
            if pending_state_class and class_match:
                current_class = BenchmarkClass(
                    package_name=package_name,
                    class_name=class_match.group(1),
                )
                benchmark_classes.append(current_class)
                current_class_depth = brace_depth + opens - closes
                pending_state_class = False
                pending_benchmark_method = False

            brace_depth += opens - closes

            if current_class is not None and brace_depth < current_class_depth:
                current_class = None
                current_class_depth = -1
                pending_benchmark_method = False

        for benchmark_class in benchmark_classes:
            if not benchmark_class.benchmark_methods:
                continue
            benchmark_prefix = f"{benchmark_class.package_name}.{benchmark_class.class_name}"
            for method_name in benchmark_class.benchmark_methods:
                expected_counts[f"{benchmark_prefix}.{method_name}"] += benchmark_class.param_multiplier

    if not expected_counts:
        raise SystemExit("No benchmark methods found under benchmarks/src/jvmMain/kotlin")

    return expected_counts


def load_actual_benchmark_counts(report_root: Path) -> tuple[Counter[str], Path]:
    report_paths = sorted(report_root.glob("*/jvm.json"))
    if len(report_paths) != 1:
        raise SystemExit(
            "Expected exactly one JVM smoke benchmark report after a clean run, "
            f"but found {len(report_paths)} under {report_root}"
        )

    report_path = report_paths[0]
    results = json.loads(report_path.read_text(encoding="utf-8"))
    actual_counts: Counter[str] = Counter(entry["benchmark"] for entry in results)
    if not actual_counts:
        raise SystemExit(f"Smoke benchmark report {report_path} did not contain any benchmark results")
    return actual_counts, report_path


def main() -> int:
    expected_counts = parse_expected_benchmark_counts(BENCHMARK_SOURCE_ROOT)
    actual_counts, report_path = load_actual_benchmark_counts(SMOKE_REPORT_ROOT)

    missing = sorted(expected_counts.keys() - actual_counts.keys())
    extra = sorted(actual_counts.keys() - expected_counts.keys())
    mismatched = {
        benchmark_name: (expected_counts[benchmark_name], actual_counts[benchmark_name])
        for benchmark_name in sorted(expected_counts.keys() & actual_counts.keys())
        if expected_counts[benchmark_name] != actual_counts[benchmark_name]
    }

    if missing or extra or mismatched:
        print("Smoke benchmark verification failed.", file=sys.stderr)
        print(f"Report: {report_path}", file=sys.stderr)
        if missing:
            print("Missing benchmark result entries:", file=sys.stderr)
            for benchmark_name in missing:
                print(f"  - {benchmark_name} (expected {expected_counts[benchmark_name]})", file=sys.stderr)
        if extra:
            print("Unexpected benchmark result entries:", file=sys.stderr)
            for benchmark_name in extra:
                print(f"  - {benchmark_name} (actual {actual_counts[benchmark_name]})", file=sys.stderr)
        if mismatched:
            print("Benchmark result counts did not match the declared parameter grid:", file=sys.stderr)
            for benchmark_name, (expected_count, actual_count) in mismatched.items():
                print(
                    f"  - {benchmark_name}: expected {expected_count}, actual {actual_count}",
                    file=sys.stderr,
                )
        return 1

    print(
        "verified JVM smoke benchmark report: "
        f"{sum(actual_counts.values())} result entries in {report_path}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
