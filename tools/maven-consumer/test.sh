#!/usr/bin/env bash
# Publishes every MeshLink module into a temporary Maven repository (never ~/.m2), checks each POM,
# then generates a Kotlin Multiplatform consumer whose only MeshLink dependency is
# ch.trancee.meshlink:meshlink and fails unless its Android runtime classpath and both iOS klib
# compile classpaths resolve from that repository and contain meshlink-core and meshlink-crypto-sodium
# (ticket 65). Kotlin and AGP versions come from gradle/libs.versions.toml. Needs ANDROID_HOME.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
if [[ -z "${ANDROID_HOME:-}" ]]; then
    echo "error: set ANDROID_HOME to the Android SDK" >&2
    exit 1
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
repo="$work/repo"
consumer="$work/consumer"

# Gradle's mavenLocal() honors maven.repo.local, so publishToMavenLocal writes into $repo.
"$root/gradlew" -p "$root" --quiet publishToMavenLocal "-Dmaven.repo.local=$repo"

version="$(sed -n 's/^meshlink\.version=//p' "$root/gradle.properties")"
python3 - "$repo" "$version" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

repo, version = Path(sys.argv[1]), sys.argv[2]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
errors = []
for artifact in ("meshlink", "meshlink-core", "meshlink-crypto-sodium", "meshlink-testing"):
    pom = repo / "ch/trancee/meshlink" / artifact / version / f"{artifact}-{version}.pom"
    if not pom.is_file():
        errors.append(f"{artifact}: no POM at {pom.relative_to(repo)}")
        continue
    p = ET.parse(pom).getroot()
    text = lambda path: (p.findtext(path, namespaces=ns) or "").strip()
    for field in ("m:name", "m:description", "m:url", "m:developers/m:developer/m:id", "m:scm/m:url"):
        if not text(field):
            errors.append(f"{artifact}: POM lacks {field.replace('m:', '')}")
    if text("m:version") != version:
        errors.append(f"{artifact}: version {text('m:version')!r}, expected {version!r}")
    if (text("m:licenses/m:license/m:name"), text("m:licenses/m:license/m:url")) != (
        "The Unlicense",
        "https://unlicense.org",
    ):
        errors.append(f"{artifact}: license is not The Unlicense, https://unlicense.org")
if errors:
    sys.exit("\n".join(f"error: {e}" for e in errors))
print(f"POMs OK: 4 artifacts at {version}")
PY

catalog="$root/gradle/libs.versions.toml"
catalog_version() { sed -n "s/^$1 = \"\(.*\)\"$/\1/p" "$catalog"; }
kotlin="$(catalog_version kotlin)"
agp="$(catalog_version agp)"
compile_sdk="$(catalog_version android-compileSdk)"
min_sdk="$(catalog_version android-minSdk)"

mkdir -p "$consumer"
cat > "$consumer/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven(url = uri("$repo")) }
            filter { includeGroup("ch.trancee.meshlink") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "meshlink-maven-consumer"
EOF
cat > "$consumer/build.gradle.kts" <<EOF
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    kotlin("multiplatform") version "$kotlin"
    id("com.android.kotlin.multiplatform.library") version "$agp"
}

kotlin {
    extensions.configure<KotlinMultiplatformAndroidLibraryExtension> {
        namespace = "ch.trancee.meshlink.consumer"
        compileSdk = $compile_sdk
        minSdk = $min_sdk
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets.commonMain.dependencies {
        implementation("ch.trancee.meshlink:meshlink:$version")
    }
}

// Prints every resolved MeshLink component of each classpath as "<configuration> <artifact>".
val classpaths = listOf("androidRuntimeClasspath", "iosArm64CompileKlibraries", "iosSimulatorArm64CompileKlibraries")
val resolved = classpaths.associateWith { name ->
    configurations.named(name).flatMap { it.incoming.artifacts.resolvedArtifacts }
}
tasks.register("meshLinkClasspaths") {
    val artifacts = resolved
    doLast {
        artifacts.forEach { (name, set) ->
            set.get()
                .map { it.id.componentIdentifier }
                .filterIsInstance<ModuleComponentIdentifier>()
                .filter { it.group == "ch.trancee.meshlink" }
                .map { it.module }
                .distinct()
                .forEach { println("\$name \$it") }
        }
    }
}
EOF
echo "sdk.dir=$ANDROID_HOME" > "$consumer/local.properties"

output="$("$root/gradlew" -p "$consumer" --quiet meshLinkClasspaths)"
echo "$output"
missing=0
# Gradle module metadata redirects each artifact to its per-target variant, such as meshlink-core-android.
for entry in androidRuntimeClasspath:android iosArm64CompileKlibraries:iosarm64 \
    iosSimulatorArm64CompileKlibraries:iossimulatorarm64; do
    classpath="${entry%%:*}"
    for artifact in meshlink meshlink-core meshlink-crypto-sodium; do
        if ! grep -qx "$classpath $artifact-${entry##*:}" <<<"$output"; then
            echo "error: $classpath lacks $artifact-${entry##*:}" >&2
            missing=1
        fi
    done
done
[[ $missing -eq 0 ]] || exit 1
echo "consumer OK: meshlink, meshlink-core and meshlink-crypto-sodium resolve on Android and both iOS targets"
