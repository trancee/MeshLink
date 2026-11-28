#!/usr/bin/env python3
"""Fails unless the Swift reference scenarios carry exactly the titles of reference-scenarios.feature.

meshlink-testing runs the feature's scenarios on VirtualAir; swift/Tests runs the same scenarios against
the Swift fake MeshLinkTesting, each test naming its scenario with `scenario("<title>")` (ADR 0017
decision 6, "Swift fake drift"). Usage: tools/check-scenario-parity.py
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FEATURE = ROOT / "meshlink-testing/src/commonTest/resources/features/reference-scenarios.feature"
SWIFT_TESTS = ROOT / "swift/Tests"


def duplicates(titles: list[str]) -> set[str]:
    return {title for title in titles if titles.count(title) > 1}


def main() -> int:
    kotlin = [m.group(1).strip() for m in re.finditer(r"^\s*Scenario:\s*(.+)$", FEATURE.read_text(), re.M)]
    swift = [
        m.group(1)
        for path in sorted(SWIFT_TESTS.rglob("*.swift"))
        for m in re.finditer(r'\bscenario\("((?:[^"\\]|\\.)*)"\)', path.read_text())
    ]
    problems = [f"duplicate feature scenario: {t}" for t in sorted(duplicates(kotlin))]
    problems += [f"duplicate Swift scenario: {t}" for t in sorted(duplicates(swift))]
    problems += [f"in the feature, not in swift/Tests: {t}" for t in sorted(set(kotlin) - set(swift))]
    problems += [f"in swift/Tests, not in the feature: {t}" for t in sorted(set(swift) - set(kotlin))]
    if not kotlin:
        problems.append(f"no scenarios in {FEATURE.relative_to(ROOT)}")
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"Scenario parity: {len(kotlin)} reference scenarios in Kotlin and Swift")
    return 0


if __name__ == "__main__":
    sys.exit(main())
