#!/usr/bin/env bash
# Builds and runs the MeshLink TUI directly (bypasses Gradle's I/O wrapping).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Building fat JAR…"
cd "$PROJECT_DIR"
./gradlew :meshlink-tui:fatJar --quiet

JAR="$SCRIPT_DIR/build/libs/meshlink-tui-all.jar"
if [ ! -f "$JAR" ]; then
    # Try versioned name
    JAR=$(find "$SCRIPT_DIR/build/libs" -name "*-all.jar" | head -1)
fi

if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "ERROR: Fat JAR not found. Check build output."
    exit 1
fi

echo "Starting MeshLink TUI…"
exec java -jar "$JAR"
