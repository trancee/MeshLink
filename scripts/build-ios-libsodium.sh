#!/usr/bin/env bash
# scripts/build-ios-libsodium.sh
# Build libsodium 1.0.22 static library for iOS arm64 device.
#
# Compiles with LTO bitcode and per-function sections so the Kotlin/Native
# linker can eliminate unused libsodium code when building the framework.
#
# Requires:
#   - macOS with Xcode command-line tools (xcrun, clang)
#   - Internet access to download libsodium source tarball
#
# Usage: bash scripts/build-ios-libsodium.sh
#
# Output:
#   meshlink/src/iosMain/interop/lib/ios/libsodium.a       (arm64 device)
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

NPROC="$(sysctl -n hw.logicalcpu 2>/dev/null || echo 4)"

# ── Download libsodium source ────────────────────────────────────────────────
echo "Downloading libsodium ${LIBSODIUM_VERSION}..."
TARBALL="${BUILD_DIR}/libsodium.tar.gz"
curl -fsSL "${LIBSODIUM_URL}" -o "${TARBALL}"
(cd "${BUILD_DIR}" && tar -xzf libsodium.tar.gz)
SRC_DIR="${BUILD_DIR}/libsodium-${LIBSODIUM_VERSION}"

# ── Build ────────────────────────────────────────────────────────────────────
echo ""
echo "Building ios (arm64 device, sdk=iphoneos)..."

BUILD_SUBDIR="${BUILD_DIR}/build-ios"
mkdir -p "${BUILD_SUBDIR}"

OUT_DIR="${INTEROP_DIR}/lib/ios"
mkdir -p "${OUT_DIR}"

SYSROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
CC="$(xcrun --sdk iphoneos --find clang)"
AR="$(xcrun --sdk iphoneos --find ar)"
RANLIB="$(xcrun --sdk iphoneos --find ranlib)"
TARGET_TRIPLE="arm64-apple-ios${IOS_MIN_VERSION}"

# Size-optimized CFLAGS:
#   -Os:                    optimize for size
#   -ffunction-sections:    one section per function — enables dead-code stripping
#   -fdata-sections:        one section per global — enables dead-data stripping
#   -fvisibility=hidden:    hide all symbols by default (only cinterop-declared
#                           functions are pulled in by Kotlin/Native)
#   -march=armv8-a+crypto:  enable ARMv8 crypto intrinsics (AES, SHA, PMULL)
#
# Note: -flto is intentionally omitted. Kotlin/Native uses its own LLVM backend
# and does not consume Apple clang LTO bitcode — it needs native Mach-O objects.
CFLAGS="-arch arm64 -isysroot ${SYSROOT} -target ${TARGET_TRIPLE}"
CFLAGS="${CFLAGS} -mios-version-min=${IOS_MIN_VERSION}"
CFLAGS="${CFLAGS} -Os -ffunction-sections -fdata-sections -fvisibility=hidden"
CFLAGS="${CFLAGS} -march=armv8-a+crypto"

(
    cd "${BUILD_SUBDIR}"

    # Full build — MeshLink uses AEAD, Ed25519, X25519, SHA-256, HKDF.
    # Unused code is eliminated when the framework is linked.
    export LIBSODIUM_FULL_BUILD=1

    "${SRC_DIR}/configure" \
        --host="arm-apple-ios" \
        --prefix="${BUILD_SUBDIR}/install" \
        --enable-static \
        --disable-shared \
        --disable-soname-versions \
        CC="${CC}" \
        AR="${AR}" \
        RANLIB="${RANLIB}" \
        CFLAGS="${CFLAGS}" \
        LDFLAGS="-arch arm64 -isysroot ${SYSROOT} -target ${TARGET_TRIPLE} -mios-version-min=${IOS_MIN_VERSION}" \
        > /dev/null 2>&1

    make -j"${NPROC}" > /dev/null 2>&1
    make install > /dev/null 2>&1
)

# Verify the archive is non-trivial.
INSTALLED_LIB="${BUILD_SUBDIR}/install/lib/libsodium.a"
LIB_SIZE="$(wc -c < "${INSTALLED_LIB}" | tr -d ' ')"
if [[ "${LIB_SIZE}" -lt 1000 ]]; then
    echo "  ERROR: libsodium.a is suspiciously small (${LIB_SIZE} bytes)" >&2
    exit 1
fi

cp "${INSTALLED_LIB}" "${OUT_DIR}/libsodium.a"

# Strip local symbols and debug info. Apple's ar/ranlib handles Mach-O archives natively.
strip -S "${OUT_DIR}/libsodium.a" 2>/dev/null || true
ranlib "${OUT_DIR}/libsodium.a" 2>/dev/null || true

MEMBER_COUNT="$(ar t "${OUT_DIR}/libsodium.a" | wc -l | tr -d ' ')"
FILE_SIZE="$(ls -lh "${OUT_DIR}/libsodium.a" | awk '{print $5}')"
echo "  ✓ ios → ${OUT_DIR}/libsodium.a (${FILE_SIZE}, ${MEMBER_COUNT} objects)"

echo ""
echo "Build complete."
echo "  Device: ${INTEROP_DIR}/lib/ios/libsodium.a"
