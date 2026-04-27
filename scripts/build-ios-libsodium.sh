#!/usr/bin/env bash
# scripts/build-ios-libsodium.sh
# Build libsodium 1.0.22 static libraries for iOS device and simulator targets.
#
# Requires:
#   - macOS with Xcode command-line tools (xcrun, clang)
#   - Internet access to download libsodium source tarball
#
# Usage: bash scripts/build-ios-libsodium.sh
#
# Output:
#   meshlink/src/iosMain/interop/lib/iosArm64/libsodium.a       (arm64 device)
#   meshlink/src/iosMain/interop/lib/iosSimulatorArm64/libsodium.a  (arm64 simulator)
set -euo pipefail

if [[ "$(uname)" != "Darwin" ]]; then
    echo "ERROR: This script requires macOS with Xcode command-line tools." >&2
    exit 1
fi

LIBSODIUM_VERSION="1.0.22"
LIBSODIUM_URL="https://download.libsodium.org/libsodium/releases/libsodium-${LIBSODIUM_VERSION}.tar.gz"
IOS_MIN_VERSION="14.0"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
INTEROP_DIR="${PROJECT_ROOT}/meshlink/src/iosMain/interop"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "${BUILD_DIR}"' EXIT

# ── Download libsodium source ────────────────────────────────────────────────
echo "Downloading libsodium ${LIBSODIUM_VERSION}..."
TARBALL="${BUILD_DIR}/libsodium.tar.gz"
curl -fsSL "${LIBSODIUM_URL}" -o "${TARBALL}"
(cd "${BUILD_DIR}" && tar -xzf libsodium.tar.gz)
SRC_DIR="${BUILD_DIR}/libsodium-${LIBSODIUM_VERSION}"

# ── Build helper ─────────────────────────────────────────────────────────────
build_for_target() {
    local TARGET_NAME="$1"  # iosArm64 or iosSimulatorArm64
    local SDK="$2"          # iphoneos or iphonesimulator
    local TARGET_TRIPLE="$3" # e.g. arm64-apple-ios14.0 or arm64-apple-ios14.0-simulator

    echo ""
    echo "Building ${TARGET_NAME} (sdk=${SDK}, target=${TARGET_TRIPLE})..."

    local BUILD_SUBDIR="${BUILD_DIR}/build-${TARGET_NAME}"
    mkdir -p "${BUILD_SUBDIR}"

    local OUT_DIR="${INTEROP_DIR}/lib/${TARGET_NAME}"
    mkdir -p "${OUT_DIR}"

    local SYSROOT
    SYSROOT="$(xcrun --sdk "${SDK}" --show-sdk-path)"

    local CC
    CC="$(xcrun --sdk "${SDK}" --find clang)"

    (
        cd "${BUILD_SUBDIR}"
        "${SRC_DIR}/configure" \
            --host="arm-apple-ios" \
            --prefix="${BUILD_SUBDIR}/install" \
            --enable-static \
            --disable-shared \
            CC="${CC}" \
            CFLAGS="-arch arm64 -isysroot ${SYSROOT} -target ${TARGET_TRIPLE} -mios-version-min=${IOS_MIN_VERSION} -O2" \
            LDFLAGS="-arch arm64 -isysroot ${SYSROOT} -target ${TARGET_TRIPLE} -mios-version-min=${IOS_MIN_VERSION}"

        make -j"$(sysctl -n hw.logicalcpu)"
        make install
    )

    cp "${BUILD_SUBDIR}/install/lib/libsodium.a" "${OUT_DIR}/libsodium.a"
    echo "  ✓ ${TARGET_NAME} → ${OUT_DIR}/libsodium.a"
}

# ── arm64 device ─────────────────────────────────────────────────────────────
build_for_target \
    "iosArm64" \
    "iphoneos" \
    "arm64-apple-ios${IOS_MIN_VERSION}"

# ── arm64 simulator (Apple Silicon / Rosetta-free) ───────────────────────────
build_for_target \
    "iosSimulatorArm64" \
    "iphonesimulator" \
    "arm64-apple-ios${IOS_MIN_VERSION}-simulator"

echo ""
echo "Build complete."
echo "  Device:    ${INTEROP_DIR}/lib/iosArm64/libsodium.a"
echo "  Simulator: ${INTEROP_DIR}/lib/iosSimulatorArm64/libsodium.a"
echo "(Note: to verify iOS Kotlin compilation, run on macOS: ./gradlew :meshlink:compileKotlinIosArm64)"
