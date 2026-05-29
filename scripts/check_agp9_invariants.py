#!/usr/bin/env python3

from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent


class InvariantFailure(RuntimeError):
    pass


def read(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def require_contains(relative_path: str, expected_text: str, message: str) -> None:
    contents = read(relative_path)
    if expected_text not in contents:
        raise InvariantFailure(f"{message} ({relative_path})")


def require_not_contains(relative_path: str, forbidden_text: str, message: str) -> None:
    contents = read(relative_path)
    if forbidden_text in contents:
        raise InvariantFailure(f"{message} ({relative_path})")


def main() -> int:
    try:
        require_contains(
            "settings.gradle.kts",
            'include(":meshlink-reference:android-app")',
            "settings must keep the nested reference Android app module included",
        )
        require_contains(
            "settings.gradle.kts",
            'project(":meshlink-reference:android-app").projectDir = file("meshlink-reference/android-app")',
            "settings must keep the nested reference Android app module directory mapping",
        )

        require_contains(
            "build.gradle.kts",
            "alias(libs.plugins.android.kmp.library) apply false",
            "root build must expose the AGP 9 KMP Android library plugin",
        )
        require_contains(
            "build.gradle.kts",
            "alias(libs.plugins.android.application) apply false",
            "root build must expose the Android application plugin",
        )
        require_not_contains(
            "build.gradle.kts",
            "alias(libs.plugins.android.library) apply false",
            "root build must not keep the legacy Android library plugin alias wired in",
        )

        require_contains(
            "gradle/libs.versions.toml",
            'android-kmp-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }',
            "version catalog must define the AGP 9 KMP Android library plugin alias",
        )
        require_contains(
            "gradle/libs.versions.toml",
            'android-application = { id = "com.android.application", version.ref = "agp" }',
            "version catalog must define the Android application plugin alias",
        )
        require_not_contains(
            "gradle/libs.versions.toml",
            'android-library = { id = "com.android.library", version.ref = "agp" }',
            "version catalog must not keep the legacy Android library plugin alias",
        )

        require_contains(
            "meshlink/build.gradle.kts",
            "alias(libs.plugins.android.kmp.library)",
            ":meshlink must keep the AGP 9 KMP Android library plugin",
        )
        require_not_contains(
            "meshlink/build.gradle.kts",
            "alias(libs.plugins.android.application)",
            ":meshlink must not become an Android application module",
        )
        require_not_contains(
            "meshlink/build.gradle.kts",
            "alias(libs.plugins.android.library)",
            ":meshlink must not fall back to the legacy Android library plugin",
        )
        require_not_contains(
            "meshlink/build.gradle.kts",
            "org.jetbrains.kotlin.android",
            ":meshlink must not use the pure-Android Kotlin plugin",
        )

        require_contains(
            "meshlink-reference/build.gradle.kts",
            "alias(libs.plugins.android.kmp.library)",
            ":meshlink-reference must keep the AGP 9 KMP Android library plugin",
        )
        require_contains(
            "meshlink-reference/build.gradle.kts",
            'baseName = "MeshLinkReference"',
            ":meshlink-reference must keep the shared iOS framework export anchored in the shared module",
        )
        require_contains(
            "meshlink-reference/build.gradle.kts",
            'tasks.named("check") { dependsOn(":meshlink-reference:android-app:check") }',
            ":meshlink-reference must keep the shared-module check alias wired to the nested Android app module",
        )
        require_contains(
            "meshlink-reference/build.gradle.kts",
            'val localCheck by tasks.registering',
            ":meshlink-reference must keep the localCheck compatibility task",
        )
        require_contains(
            "meshlink-reference/build.gradle.kts",
            'val installDebug by tasks.registering',
            ":meshlink-reference must keep the installDebug compatibility task",
        )
        require_contains(
            "meshlink-reference/build.gradle.kts",
            'val connectedDebugAndroidTest by tasks.registering',
            ":meshlink-reference must keep the connectedDebugAndroidTest compatibility task",
        )
        require_not_contains(
            "meshlink-reference/build.gradle.kts",
            "alias(libs.plugins.android.application)",
            ":meshlink-reference must not become an Android application module again",
        )
        require_not_contains(
            "meshlink-reference/build.gradle.kts",
            "alias(libs.plugins.android.library)",
            ":meshlink-reference must not fall back to the legacy Android library plugin",
        )
        require_not_contains(
            "meshlink-reference/build.gradle.kts",
            "org.jetbrains.kotlin.android",
            ":meshlink-reference must not use the pure-Android Kotlin plugin",
        )

        require_contains(
            "meshlink-reference/android-app/build.gradle.kts",
            "alias(libs.plugins.android.application)",
            ":meshlink-reference:android-app must remain the Android application entry point",
        )
        require_contains(
            "meshlink-reference/android-app/build.gradle.kts",
            'implementation(project(":meshlink-reference"))',
            ":meshlink-reference:android-app must depend on the shared reference module",
        )
        require_not_contains(
            "meshlink-reference/android-app/build.gradle.kts",
            "org.jetbrains.kotlin.android",
            ":meshlink-reference:android-app must use AGP built-in Kotlin",
        )

        require_contains(
            "meshlink-proof/android/app/build.gradle.kts",
            "alias(libs.plugins.android.application)",
            "the proof app must remain a pure Android application module",
        )
        require_not_contains(
            "meshlink-proof/android/app/build.gradle.kts",
            "org.jetbrains.kotlin.android",
            "the proof app must not reintroduce the pure-Android Kotlin plugin",
        )

        require_contains(
            "scripts/run-reference-local-check.sh",
            ":meshlink-reference:localCheck",
            "the fast reference-app verification script must stay anchored to the preserved localCheck task",
        )
        require_contains(
            "meshlink-reference/scripts/run_headless_reference_live_proof.py",
            ":meshlink-reference:installDebug",
            "the headless reference harness must keep using the preserved installDebug task",
        )
    except InvariantFailure as error:
        print(f"AGP 9 invariant check failed: {error}", file=sys.stderr)
        return 1

    print("AGP 9 invariants are satisfied")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
