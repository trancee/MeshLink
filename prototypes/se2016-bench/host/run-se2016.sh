#!/usr/bin/env bash
# Builds the framework and app, installs on the iPhone SE (2016), runs one mode, and pulls the result file.
# Usage: host/run-se2016.sh <mode: crypto|interop|all> <run-id> [extra k=v launch args...]
# Env: UDID (default SE 2016), TEAM (default apple-team-2), BUNDLE (default ch.trancee.lab.host.b), SKIP_BUILD=1, TIMEOUT (s).
set -euo pipefail
cd "$(dirname "$0")/.."
mode=${1:?mode}
run=${2:?run id}
shift 2
udid=${UDID:-se2016}
team=${TEAM:-apple-team-2}
bundle=${BUNDLE:-ch.trancee.lab.host.b}
timeout=${TIMEOUT:-1800}
app=iosApp/build/Build/Products/Release-iphoneos/SE2016BenchApp.app

if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
    ./gradlew --console=plain -q assembleMeshLinkKitReleaseXCFramework
    (cd iosApp && xcodegen generate --quiet && xcodebuild -quiet -project SE2016Bench.xcodeproj -scheme SE2016BenchApp \
        -configuration Release -destination generic/platform=iOS -derivedDataPath build \
        DEVELOPMENT_TEAM="$team" LAB_BUNDLE_ID="$bundle" -allowProvisioningUpdates build)
    ideviceinstaller -u "$udid" install "$app"
fi

mkdir -p results
idevicedebug -u "$udid" kill "$bundle" >/dev/null 2>&1 || true
idevicedebug -u "$udid" --detach run "$bundle" "mode=$mode" "run=$run" "$@"

destination=results/$run.txt
deadline=$((SECONDS + timeout))
while ((SECONDS < deadline)); do
    sleep 15
    printf 'get -f Documents/results/%s.txt %s\nquit\n' "$run" "$destination" |
        afcclient -u "$udid" --container "$bundle" >/dev/null 2>&1 || true
    if [[ -s "$destination" ]] && tail -1 "$destination" | grep -qE '^(DONE|FAILED)'; then
        tail -1 "$destination"
        exit 0
    fi
done
echo "timed out waiting for $destination" >&2
exit 1
