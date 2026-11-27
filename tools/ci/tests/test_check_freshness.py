import importlib.util
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch


MODULE = Path(__file__).resolve().parents[2] / "check_freshness.py"
spec = importlib.util.spec_from_file_location("check_freshness", MODULE)
freshness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(freshness)

CATALOG = """\
[versions]
kotlin = "2.4.21"
# A comment.
agp = "9.3.3"
cucumber-kmp = "0.2.1"
detekt = "2.0.0-alpha.6"
ktlint = "1.8.0"
android-ndk = "30.0.16248370"
android-compileSdk = "37"
android-minSdk = "26"
jdk-toolchain = "21"
robolectric-sdk37 = "17-robolectric-16653203-i7"
floor-netty = "4.1.141.Final"
floor-guava = "33.8.0-jre"

[libraries]
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
android-gradle-plugin = { module = "com.android.tools.build:gradle", version.ref = "agp" }
detekt-gradle-plugin = { module = "dev.detekt:detekt-gradle-plugin", version.ref = "detekt" }
android-all = { module = "org.robolectric:android-all-instrumented", version.ref = "robolectric-sdk37" }
floor-netty-codec = { module = "io.netty:netty-codec", version.ref = "floor-netty" }
floor-guava = { module = "com.google.guava:guava", version.ref = "floor-guava" }
compose-ui = { module = "androidx.compose.ui:ui" }

[plugins]
cucumber-kmp = { id = "io.github.menjoo.cucumberkmp", version.ref = "cucumber-kmp" }
"""

REGISTER = """\
| Tool | Version | Action |
|---|---|---|
| lychee | 0.24.2 | `lycheeverse/lychee-action` v2.9.0 |
| markdownlint-cli2 | 0.24.0 | `DavidAnson/markdownlint-cli2-action` v25.0.0 |
| actionlint | 1.7.12 | None |
"""

WORKFLOW = """\
jobs:
  build:
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/cache/restore@0057852bfaa89a56745cba8c7296529d2fc39830 # v6.1.0
      - uses: actions/cache/save@0057852bfaa89a56745cba8c7296529d2fc39830 # v6.1.0
"""


def metadata(*versions):
    return "<metadata><versioning>" + "".join(f"<version>{v}</version>" for v in versions) + "</versioning></metadata>"


def release(tag):
    return '{"tag_name": "%s"}' % tag


CENTRAL = "https://repo1.maven.org/maven2/"
GOOGLE = "https://dl.google.com/android/maven2/"
GITHUB = "https://api.github.com/repos/"

RESPONSES = {
    CENTRAL + "org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml": metadata("2.4.21", "2.4.30", "2.5.0-Beta1"),
    CENTRAL + "com/android/tools/build/gradle/maven-metadata.xml": None,
    GOOGLE + "com/android/tools/build/gradle/maven-metadata.xml": metadata("9.5.0-alpha01", "9.3.3", "9.4.0"),
    CENTRAL + "dev/detekt/detekt-gradle-plugin/maven-metadata.xml": metadata("1.23.8", "2.0.0-alpha.6", "2.0.0-alpha.7"),
    CENTRAL + "com/pinterest/ktlint/ktlint-cli/maven-metadata.xml": metadata("1.8.0", "1.9.0", "2.0.0-RC1"),
    CENTRAL + "io/netty/netty-codec/maven-metadata.xml": metadata("4.1.141.Final", "4.1.142.Final", "4.2.9.Final"),
    CENTRAL + "com/google/guava/guava/maven-metadata.xml": metadata("33.8.0-jre", "33.9.0-android"),
    "https://plugins.gradle.org/m2/io/github/menjoo/cucumberkmp/io.github.menjoo.cucumberkmp.gradle.plugin/"
    "maven-metadata.xml": metadata("0.2.0", "0.2.1"),
    "https://dl.google.com/android/repository/repository2-4.xml": (
        "<sdk-repository>"
        '<remotePackage path="ndk;30.0.16248370"/><remotePackage path="ndk;30.1.100"/>'
        '<remotePackage path="ndk;31.0.1-rc1"/><remotePackage path="platforms;android-37.2"/>'
        '<remotePackage path="platforms;android-38"/><remotePackage path="platforms;android-39-beta1"/>'
        '<remotePackage path="platforms;android-canary-20261104"/>'
        "</sdk-repository>"
    ),
    "https://services.gradle.org/versions/current": '{"version": "9.9.3"}',
    GITHUB + "jedisct1/libsodium/releases/latest": release("1.0.22-RELEASE"),
    GITHUB + "actions/checkout/releases/latest": release("v7.0.1"),
    GITHUB + "actions/cache/releases/latest": release("v6.2.0"),
    GITHUB + "lycheeverse/lychee/releases/latest": release("lychee-v0.25.0"),
    GITHUB + "rhysd/actionlint/releases/latest": release("v1.7.12"),
    "https://registry.npmjs.org/markdownlint-cli2/latest": '{"version": "0.24.2"}',
    "https://pypi.org/pypi/zensical/json": '{"info": {"version": "0.1.4", "classifiers": ["Development Status :: 4 - Beta"]}}',
}


def repository(root, catalog=CATALOG):
    for path, text in {
        "gradle/libs.versions.toml": catalog,
        "gradle/wrapper/gradle-wrapper.properties": "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n",
        "meshlink-crypto-sodium/libsodium.properties": "# Pin.\nversion=1.0.22\n",
        "docs/reference/dependencies.md": REGISTER,
        ".github/workflows/build.yml": WORKFLOW,
    }.items():
        file = Path(root, path)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
    return Path(root)


def fetcher(overrides=None):
    responses = {**RESPONSES, **(overrides or {})}
    return lambda url: responses[url]


class CheckFreshnessTest(unittest.TestCase):
    def test_reports_each_newer_stable_release_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as root:
            report = freshness.check(repository(root), fetcher())

        self.assertEqual(
            [
                ("kotlin", "2.4.21", "2.4.30"),
                ("agp", "9.3.3", "9.4.0"),
                ("ktlint", "1.8.0", "1.9.0"),
                ("android-ndk", "30.0.16248370", "30.1.100"),
                ("android-compileSdk", "37", "38"),
                ("floor-netty", "4.1.141.Final", "4.1.142.Final"),
                ("Gradle", "9.7.1", "9.9.3"),
                ("actions/cache", "6.1.0", "6.2.0"),
                ("lychee", "0.24.2", "0.25.0"),
                ("markdownlint-cli2", "0.24.0", "0.24.2"),
            ],
            [(f.name, f.current, f.latest) for f in report.findings],
        )
        self.assertEqual(["android-minSdk", "jdk-toolchain", "robolectric-sdk37"], [name for name, _ in report.skipped])

    def test_reports_the_first_stable_zensical_release(self):
        stable = '{"info": {"version": "1.0.0", "classifiers": ["Development Status :: 5 - Production/Stable"]}}'
        with tempfile.TemporaryDirectory() as root:
            report = freshness.check(repository(root), fetcher({"https://pypi.org/pypi/zensical/json": stable}))

        finding = report.findings[-1]
        self.assertEqual(("Zensical", "not adopted", "1.0.0"), (finding.name, finding.current, finding.latest))

    def test_catalog_version_without_a_source_fails(self):
        with tempfile.TemporaryDirectory() as root:
            root = repository(root, CATALOG.replace("[libraries]", 'orphan = "1.0"\n\n[libraries]'))

            with self.assertRaisesRegex(ValueError, "^orphan has no release source: add it to tools/check_freshness.py$"):
                freshness.check(root, fetcher())

    def test_main_writes_the_summary_and_fails_on_a_finding(self):
        with tempfile.TemporaryDirectory() as root, tempfile.NamedTemporaryFile("r") as summary:
            repository(root)
            with patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": summary.name}), redirect_stdout(io.StringIO()) as out:
                code = freshness.main(Path(root), fetcher())
            written = summary.read()

        self.assertEqual(1, code)
        self.assertEqual(out.getvalue(), written)
        self.assertIn("| `actions/cache` | 6.1.0 | 6.2.0 | GitHub releases `actions/cache` |\n", written)
        self.assertIn("| `jdk-toolchain` | 21 | ", written)

    def test_main_passes_when_everything_is_current(self):
        current = {
            CENTRAL + "org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml": metadata("2.4.21"),
            GOOGLE + "com/android/tools/build/gradle/maven-metadata.xml": metadata("9.3.3"),
            CENTRAL + "com/pinterest/ktlint/ktlint-cli/maven-metadata.xml": metadata("1.8.0"),
            CENTRAL + "io/netty/netty-codec/maven-metadata.xml": metadata("4.1.141.Final", "4.2.9.Final"),
            "https://dl.google.com/android/repository/repository2-4.xml": (
                '<sdk-repository><remotePackage path="ndk;30.0.16248370"/>'
                '<remotePackage path="platforms;android-37"/></sdk-repository>'
            ),
            "https://services.gradle.org/versions/current": '{"version": "9.7.1"}',
            GITHUB + "actions/cache/releases/latest": release("v6.1.0"),
            GITHUB + "lycheeverse/lychee/releases/latest": release("lychee-v0.24.2"),
            "https://registry.npmjs.org/markdownlint-cli2/latest": '{"version": "0.24.0"}',
        }
        with tempfile.TemporaryDirectory() as root, redirect_stdout(io.StringIO()) as out:
            code = freshness.main(repository(root), fetcher(current))

        self.assertEqual(0, code)
        self.assertIn("Every checked dependency is on its newest stable release.", out.getvalue())


if __name__ == "__main__":
    unittest.main()
