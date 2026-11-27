#!/usr/bin/env python3
"""Lists every dependency with a newer stable release (ticket 56; ADR 0017 decision 12).

Checks each version in gradle/libs.versions.toml, the Gradle wrapper, the libsodium pin, every action the workflows
use, the CI tools of docs/reference/dependencies.md, and whether Zensical has a stable release (ADR 0006 decision 8).
Prints a Markdown report, appends it to $GITHUB_STEP_SUMMARY when set, and exits 1 when anything is out of date.
Standard library only; needs the network. Set GITHUB_TOKEN to avoid GitHub's anonymous rate limit.
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

CENTRAL = "https://repo1.maven.org/maven2/"
GOOGLE = "https://dl.google.com/android/maven2/"
PORTAL = "https://plugins.gradle.org/m2/"
SDK = "https://dl.google.com/android/repository/repository2-4.xml"

# Catalog versions with no release to compare, and why.
SKIP = {
    "android-minSdk": "the oldest Android MeshLink supports; a support decision, not a release",
    "jdk-toolchain": "the bytecode and toolchain baseline (ADR 0005); raised by decision, not by freshness",
    "robolectric-sdk": "the android-all jar Robolectric's SDK table names; follows the `robolectric` version",
}
# Catalog versions that need a source other than their library or plugin.
MODULES = {"ktlint": "com.pinterest.ktlint:ktlint-cli"}
# Catalog versions that must stay in their release line: the number of leading version numbers that must match.
LINES = {"floor-netty": 2}
FLAVORS = ("-jre", "-android")
TOOLS = (("lychee", "github", "lycheeverse/lychee"), ("markdownlint-cli2", "npm", "markdownlint-cli2"),
         ("actionlint", "github", "rhysd/actionlint"))

UNSTABLE = re.compile(r"(?i)alpha|beta|rc|cr|eap|dev|snapshot|preview|milestone|canary|(?:^|[.-])m\d")
ENTRY = re.compile(r'^([\w-]+)\s*=\s*"([^"]*)"', re.MULTILINE)
REFERENCE = re.compile(r'^[\w-]+\s*=\s*\{\s*(module|id)\s*=\s*"([^"]+)",\s*version\.ref\s*=\s*"([^"]+)"', re.MULTILINE)
USES = re.compile(r"uses:\s*([\w.-]+/[\w.-]+)[^@\s]*@\S+\s+#\s*v?(\S+)")


@dataclass
class Finding:
    name: str
    current: str
    latest: str
    source: str


@dataclass
class Report:
    findings: list = field(default_factory=list)
    skipped: list = field(default_factory=list)


def numbers(version):
    match = re.match(r"\d+(?:\.\d+)*", version)
    return tuple(int(n) for n in match.group().split(".")) if match else ()


def stable(version):
    return not UNSTABLE.search(version)


def newest(current, candidates, line=0):
    """The newest stable candidate above `current` in its flavor and line, or None."""
    flavor = next((f for f in FLAVORS if current.endswith(f)), None)
    matching = [
        c for c in candidates
        if stable(c) and numbers(c) and (flavor is None or c.endswith(flavor))
        and numbers(c)[:line] == numbers(current)[:line]
    ]
    best = max(matching, key=lambda c: numbers(c), default=None)
    current_key = (numbers(current), stable(current))
    return best if best is not None and (numbers(best), True) > current_key else None


def tag(text):
    return re.sub(r"^.*?v(?=\d)", "", json.loads(text)["tag_name"])


def section(text, name):
    match = re.search(r"^\[" + name + r"\]\n(.*?)(?=^\[|\Z)", text, re.MULTILINE | re.DOTALL)
    return match.group(1) if match else ""


def maven(fetch, coordinates):
    group, artifact = coordinates.split(":")
    path = group.replace(".", "/") + "/" + artifact
    for base, label in ((CENTRAL, "Maven Central"), (GOOGLE, "Google Maven")):
        text = fetch(base + path + "/maven-metadata.xml")
        if text is not None:
            return re.findall(r"<version>([^<]+)</version>", text), f"{label} `{coordinates}`"
    raise ValueError(f"{coordinates} is on neither Maven Central nor Google Maven")


def catalog(root, fetch, report):
    text = (root / "gradle/libs.versions.toml").read_text()
    sources = {}
    for kind, name, key in REFERENCE.findall(section(text, "libraries") + section(text, "plugins")):
        sources.setdefault(key, (kind, name))
    sdk = None
    for key, current in ENTRY.findall(section(text, "versions")):
        if any(key.startswith(skip) for skip in SKIP):
            report.skipped.append((key, current))
            continue
        if key in ("android-ndk", "android-compileSdk"):
            sdk = sdk or fetch(SDK)
            pattern = r'path="ndk;([^"]+)"' if key == "android-ndk" else r'path="platforms;android-(\d+)(?:\.0)?"'
            candidates, source = re.findall(pattern, sdk), "Android SDK repository"
        elif key in MODULES:
            candidates, source = maven(fetch, MODULES[key])
        elif key in sources and sources[key][0] == "module":
            candidates, source = maven(fetch, sources[key][1])
        elif key in sources:
            plugin = sources[key][1]
            text = fetch(f"{PORTAL}{plugin.replace('.', '/')}/{plugin}.gradle.plugin/maven-metadata.xml")
            candidates, source = re.findall(r"<version>([^<]+)</version>", text), f"Gradle Plugin Portal `{plugin}`"
        else:
            raise ValueError(f"{key} has no release source: add it to tools/check_freshness.py")
        latest = newest(current, candidates, LINES.get(key, 0))
        if latest:
            report.findings.append(Finding(key, current, latest, source))


def check(root, fetch):
    report = Report()
    catalog(root, fetch, report)

    wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text()
    gradle = re.search(r"gradle-([\d.]+)-bin\.zip", wrapper).group(1)
    latest = newest(gradle, [json.loads(fetch("https://services.gradle.org/versions/current"))["version"]])
    if latest:
        report.findings.append(Finding("Gradle", gradle, latest, "services.gradle.org"))

    sodium = re.search(r"^version=(\S+)", (root / "meshlink-crypto-sodium/libsodium.properties").read_text(), re.MULTILINE)
    latest = newest(sodium.group(1), [tag(fetch("https://api.github.com/repos/jedisct1/libsodium/releases/latest"))])
    if latest:
        report.findings.append(Finding("libsodium", sodium.group(1), latest, "GitHub releases `jedisct1/libsodium`"))

    actions = {}
    for workflow in sorted((root / ".github/workflows").glob("*.yml")):
        for repository, version in USES.findall(workflow.read_text()):
            actions.setdefault(repository, version)
    for repository, version in sorted(actions.items()):
        latest = newest(version, [tag(fetch(f"https://api.github.com/repos/{repository}/releases/latest"))])
        if latest:
            report.findings.append(Finding(repository, version, latest, f"GitHub releases `{repository}`"))

    register = (root / "docs/reference/dependencies.md").read_text()
    for name, kind, project in TOOLS:
        current = re.search(r"^\| " + re.escape(name) + r" \| ([^|]+?) \|", register, re.MULTILINE).group(1)
        if kind == "github":
            candidate, source = tag(fetch(f"https://api.github.com/repos/{project}/releases/latest")), f"GitHub releases `{project}`"
        else:
            candidate, source = json.loads(fetch(f"https://registry.npmjs.org/{project}/latest"))["version"], f"npm `{project}`"
        latest = newest(current, [candidate])
        if latest:
            report.findings.append(Finding(name, current, latest, source))

    info = json.loads(fetch("https://pypi.org/pypi/zensical/json"))["info"]
    mature = any(c.startswith(("Development Status :: 5", "Development Status :: 6")) for c in info["classifiers"])
    if stable(info["version"]) and (mature or numbers(info["version"])[0] >= 1):
        report.findings.append(Finding("Zensical", "not adopted", info["version"], "PyPI `zensical` (ADR 0006 decision 8)"))
    return report


def markdown(report):
    lines = ["# Dependency freshness", ""]
    if report.findings:
        lines += [f"{len(report.findings)} dependencies have a newer stable release. Update each through a pull request "
                  "that also updates docs/reference/dependencies.md (ADR 0005).", "",
                  "| Dependency | Current | Newest stable | Source |", "|---|---|---|---|"]
        lines += [f"| `{f.name}` | {f.current} | {f.latest} | {f.source} |" for f in report.findings]
    else:
        lines.append("Every checked dependency is on its newest stable release.")
    if report.skipped:
        lines += ["", "Not checked:", "", "| Dependency | Current | Why |", "|---|---|---|"]
        lines += [f"| `{name}` | {current} | {next(r for k, r in SKIP.items() if name.startswith(k))} |"
                  for name, current in report.skipped]
    return "\n".join(lines) + "\n"


def fetch_url(url):
    request = urllib.request.Request(url, headers={"User-Agent": "MeshLink check_freshness"})
    token = os.environ.get("GITHUB_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read().decode()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def main(root, fetch):
    report = check(root, fetch)
    text = markdown(report)
    print(text, end="")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as file:
            file.write(text)
    return 1 if report.findings else 0


if __name__ == "__main__":
    sys.exit(main(Path(__file__).resolve().parents[1], fetch_url))
