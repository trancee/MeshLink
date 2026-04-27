#!/usr/bin/env bash
# scripts/build-android-libsodium.sh
# Cross-compile libsodium 1.0.22 static libraries for Android (arm64-v8a, armeabi-v7a, x86_64).
#
# Requires:
#   - Android NDK 25+ at $ANDROID_NDK_HOME (or auto-detected from $ANDROID_HOME/ndk/)
#   - Internet access to download libsodium source tarball
#
# Usage: bash scripts/build-android-libsodium.sh [--api <N>]
#   --api  Android API level (default: 29, matches meshlink minSdk)
#
# Output:
#   meshlink/src/androidMain/cpp/vendor/libsodium/{abi}/include/sodium.h   (headers)
#   meshlink/src/androidMain/cpp/vendor/libsodium/{abi}/lib/libsodium.a    (static library)
#
# After running this script, build the JNI shared libraries with:
#   bash scripts/build-android-jni.sh
set -euo pipefail

LIBSODIUM_VERSION="1.0.22"
LIBSODIUM_URL="https://download.libsodium.org/libsodium/releases/libsodium-${LIBSODIUM_VERSION}.tar.gz"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENDOR_DIR="${PROJECT_ROOT}/meshlink/src/androidMain/cpp/vendor/libsodium"

# ── API level ────────────────────────────────────────────────────────────────
API_LEVEL=29
while [[ $# -gt 0 ]]; do
    case "$1" in
        --api) API_LEVEL="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

# ── Locate NDK ───────────────────────────────────────────────────────────────
if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    NDK="${ANDROID_NDK_HOME}"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
    NDK_BASE="${ANDROID_HOME}/ndk"
    if [[ -d "${NDK_BASE}" ]]; then
        NDK="$(ls -d "${NDK_BASE}"/*/ 2>/dev/null | sort -V | tail -1)"
        NDK="${NDK%/}"
    fi
fi

if [[ -z "${NDK:-}" || ! -d "${NDK}" ]]; then
    echo "ERROR: Android NDK not found." >&2
    echo "  Set ANDROID_NDK_HOME, or install via sdkmanager 'ndk;<version>'" >&2
    exit 1
fi
echo "NDK: ${NDK}"

# Locate the NDK toolchain prebuilt directory.
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [[ ! -d "${TOOLCHAIN}" ]]; then
    HOST_TAG="$(ls "${NDK}/toolchains/llvm/prebuilt/" 2>/dev/null | head -1)"
    TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
fi
if [[ ! -d "${TOOLCHAIN}" ]]; then
    echo "ERROR: NDK toolchain not found in ${NDK}/toolchains/llvm/prebuilt/" >&2
    exit 1
fi
echo "Toolchain: ${TOOLCHAIN}"

NDK_AR="${TOOLCHAIN}/bin/llvm-ar"
NDK_RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"

# ── Download libsodium source ────────────────────────────────────────────────
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "${BUILD_DIR}"' EXIT

echo "Downloading libsodium ${LIBSODIUM_VERSION}..."
TARBALL="${BUILD_DIR}/libsodium.tar.gz"
curl -fsSL "${LIBSODIUM_URL}" -o "${TARBALL}"
(cd "${BUILD_DIR}" && tar -xzf libsodium.tar.gz)

NPROC="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.logicalcpu 2>/dev/null || echo 4)"

# ── ABI → NDK triple mapping ────────────────────────────────────────────────
# Function instead of declare -A for bash 3.2 (macOS) compatibility.
triple_for_abi() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android${API_LEVEL}" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi${API_LEVEL}" ;;
        x86_64)      echo "x86_64-linux-android${API_LEVEL}" ;;
    esac
}

host_for_abi() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        x86_64)      echo "x86_64-linux-android" ;;
    esac
}

cflags_for_abi() {
    # -Os: optimize for size
    # -flto: LTO object files — enables cross-TU dead-code elimination at JNI link time
    # -ffunction-sections -fdata-sections: one ELF section per function/global so
    #   the linker's --gc-sections can strip individual unused functions
    # -fvisibility=hidden: default-hide all symbols; only JNI exports are visible
    case "$1" in
        arm64-v8a)   echo "-Os -flto -ffunction-sections -fdata-sections -fvisibility=hidden -march=armv8-a+crypto" ;;
        armeabi-v7a) echo "-Os -flto -ffunction-sections -fdata-sections -fvisibility=hidden -mfloat-abi=softfp -mfpu=vfpv3-d16 -mthumb -marm -march=armv7-a" ;;
        x86_64)      echo "-Os -flto -ffunction-sections -fdata-sections -fvisibility=hidden -march=westmere" ;;
    esac
}

# ── Build helper ─────────────────────────────────────────────────────────────
build_for_abi() {
    local ABI="$1"
    local TRIPLE
    TRIPLE="$(triple_for_abi "${ABI}")"
    local HOST
    HOST="$(host_for_abi "${ABI}")"
    local ABI_CFLAGS
    ABI_CFLAGS="$(cflags_for_abi "${ABI}")"

    local CC="${TOOLCHAIN}/bin/${TRIPLE}-clang"
    if [[ ! -x "${CC}" ]]; then
        echo "ERROR: Compiler not found: ${CC}" >&2
        return 1
    fi

    # Each ABI gets its own source copy to avoid configure/make cross-contamination.
    local SRC_COPY="${BUILD_DIR}/libsodium-${ABI}"
    cp -R "${BUILD_DIR}/libsodium-${LIBSODIUM_VERSION}" "${SRC_COPY}"

    local INSTALL_DIR="${BUILD_DIR}/install-${ABI}"

    echo ""
    echo "Building ${ABI} (triple=${TRIPLE})..."

    (
        cd "${SRC_COPY}"

        # Full build — MeshLink uses AEAD, Ed25519, X25519, SHA-256, HKDF.
        # Unused functions are eliminated at JNI link time via LTO + --gc-sections.
        export LIBSODIUM_FULL_BUILD=1

        # Use NDK's llvm-ar/llvm-ranlib so libtool produces valid ELF/LTO archives.
        # macOS system ar cannot handle cross-compiled ELF objects or LTO bitcode.
        ./configure \
            --host="${HOST}" \
            --prefix="${INSTALL_DIR}" \
            --enable-static \
            --disable-shared \
            --disable-soname-versions \
            --disable-pie \
            --with-sysroot="${TOOLCHAIN}/sysroot" \
            CC="${CC}" \
            AR="${NDK_AR}" \
            RANLIB="${NDK_RANLIB}" \
            CFLAGS="${ABI_CFLAGS}" \
            LDFLAGS="-Wl,-z,max-page-size=16384" \
            > /dev/null 2>&1

        make -j"${NPROC}" > /dev/null 2>&1
        make install > /dev/null 2>&1
    )

    # Copy headers and the installed static library.
    local OUT_DIR="${VENDOR_DIR}/${ABI}"
    mkdir -p "${OUT_DIR}/include" "${OUT_DIR}/lib"
    cp -R "${INSTALL_DIR}/include/"* "${OUT_DIR}/include/"

    # Verify the archive is not empty (macOS ar fallback produces 96-byte stubs).
    local LIB_SIZE
    LIB_SIZE="$(wc -c < "${INSTALL_DIR}/lib/libsodium.a" | tr -d ' ')"
    if [[ "${LIB_SIZE}" -lt 1000 ]]; then
        echo "  ERROR: libsodium.a is suspiciously small (${LIB_SIZE} bytes) for ${ABI}" >&2
        echo "  Hint: configure may have fallen back to macOS system ar." >&2
        return 1
    fi
    cp "${INSTALL_DIR}/lib/libsodium.a" "${OUT_DIR}/lib/libsodium.a"

    local MEMBER_COUNT
    MEMBER_COUNT="$("${NDK_AR}" t "${OUT_DIR}/lib/libsodium.a" | wc -l | tr -d ' ')"
    local FILE_SIZE
    FILE_SIZE="$(ls -lh "${OUT_DIR}/lib/libsodium.a" | awk '{print $5}')"
    echo "  ✓ ${ABI} → ${FILE_SIZE}, ${MEMBER_COUNT} objects (LTO bitcode)"
}

# ── Build all ABIs ───────────────────────────────────────────────────────────
SUCCESS=true
for ABI in "arm64-v8a" "armeabi-v7a" "x86_64"; do
    build_for_abi "${ABI}" || SUCCESS=false
done

if [[ "${SUCCESS}" != "true" ]]; then
    echo "" >&2
    echo "One or more ABIs failed to build." >&2
    exit 1
fi

echo ""
echo "Build complete. Vendored libsodium ${LIBSODIUM_VERSION} for Android:"
for ABI in "arm64-v8a" "armeabi-v7a" "x86_64"; do
    echo "  ${VENDOR_DIR}/${ABI}/lib/libsodium.a"
done
echo ""
echo "Next: bash scripts/build-android-jni.sh"
