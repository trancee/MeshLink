#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.error import URLError
from urllib.request import urlopen

import tomllib


def read_version(repo_root: Path) -> str:
    for line in (repo_root / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("VERSION_NAME="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("VERSION_NAME not found in gradle.properties")


def read_kotlin_version(repo_root: Path) -> str:
    data = tomllib.loads((repo_root / "gradle" / "libs.versions.toml").read_text(encoding="utf-8"))
    return data["versions"]["kotlin"]


def wait_for_maven_central(version: str, timeout_seconds: int = 1200) -> None:
    url = f"https://repo.maven.apache.org/maven2/ch/trancee/meshlink/meshlink/{version}/meshlink-{version}.pom"
    deadline = time.time() + timeout_seconds
    while True:
        try:
            with urlopen(url, timeout=20) as response:
                if response.status == 200:
                    return
        except URLError:
            pass
        if time.time() >= deadline:
            raise SystemExit(f"Timed out waiting for Maven Central artifact: {url}")
        time.sleep(30)


def write_consumer_project(consumer_dir: Path, repo_mode: str, version: str, kotlin_version: str) -> None:
    consumer_dir.mkdir(parents=True, exist_ok=True)
    (consumer_dir / "src" / "main" / "kotlin").mkdir(parents=True, exist_ok=True)

    repo_block: str
    if repo_mode == "mavenLocal":
        repo_block = "        mavenLocal()\n"
    else:
        repo_block = f"        maven {{ url = uri(\"{repo_mode}\") }}\n"

    (consumer_dir / "settings.gradle.kts").write_text(
        'rootProject.name = "meshlink-consumer-smoke"\n', encoding="utf-8"
    )
    (consumer_dir / "build.gradle.kts").write_text(
        f'''plugins {{
    kotlin("jvm") version "{kotlin_version}"
}}

repositories {{
{repo_block}    mavenCentral()
}}

dependencies {{
    implementation("ch.trancee.meshlink:meshlink:{version}")
}}

kotlin {{
    jvmToolchain(21)
}}
''',
        encoding="utf-8",
    )
    (consumer_dir / "src" / "main" / "kotlin" / "Smoke.kt").write_text(
        '''package smoke

import ch.trancee.meshlink.config.meshLinkConfig

fun main() {
    val config = meshLinkConfig {
        appId = "consumer-smoke"
    }
    check(config.appId == "consumer-smoke")
}
''',
        encoding="utf-8",
    )


def main() -> int:
    repo_root = Path(__file__).resolve().parents[1]
    repo_mode = sys.argv[1] if len(sys.argv) > 1 else "mavenCentral"
    version = read_version(repo_root)
    kotlin_version = read_kotlin_version(repo_root)

    if repo_mode == "mavenCentral":
        wait_for_maven_central(version)

    with tempfile.TemporaryDirectory(prefix="meshlink-consumer-smoke-") as tmp:
        consumer_dir = Path(tmp)
        write_consumer_project(consumer_dir, repo_mode, version, kotlin_version)

        cmd = [
            str(repo_root / "gradlew"),
            "-p",
            str(consumer_dir),
            "compileKotlin",
            "--no-daemon",
            "--console=plain",
        ]
        result = subprocess.run(cmd, cwd=repo_root)
        if result.returncode != 0:
            return result.returncode

    print(f"consumer-smoke-ok version={version} repo={repo_mode}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
