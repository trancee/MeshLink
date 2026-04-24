#!/usr/bin/env bash
# scripts/build-android-jni.sh
# Cross-compile libmeshlink_sodium.so for all 3 Android ABIs using the NDK clang toolchain.
# Requires:
#   - Android NDK 25+ at $ANDROID_NDK_HOME (or auto-detected from $ANDROID_HOME/ndk/)
#   - Vendored libsodium static libraries + headers at:
#       meshlink/src/androidMain/cpp/vendor/libsodium/{abi}/{include,lib}
#
# Usage: bash scripts/build-android-jni.sh [--api <N>]
#   --api  Android API level for the target triple (default: 29)
#
# Output: meshlink/src/androidMain/jniLibs/{abi}/libmeshlink_sodium.so
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CPP_SRC="${PROJECT_ROOT}/meshlink/src/androidMain/cpp"
JNI_LIBS="${PROJECT_ROOT}/meshlink/src/androidMain/jniLibs"
VENDOR_DIR="${CPP_SRC}/vendor/libsodium"

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
    # Pick the highest installed NDK version
    NDK_BASE="${ANDROID_HOME}/ndk"
    if [[ -d "${NDK_BASE}" ]]; then
        NDK="$(ls -d "${NDK_BASE}"/*/ 2>/dev/null | sort -V | tail -1)"
        NDK="${NDK%/}"  # strip trailing slash
    fi
fi

if [[ -z "${NDK:-}" || ! -d "${NDK}" ]]; then
    echo "ERROR: Android NDK not found." >&2
    echo "  Set ANDROID_NDK_HOME, or install via sdkmanager 'ndk;<version>'" >&2
    exit 1
fi
echo "NDK: ${NDK}"

HOST_TAG="linux-x86_64"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [[ ! -d "${TOOLCHAIN}" ]]; then
    # Fallback for Windows host (unlikely in CI)
    HOST_TAG="windows-x86_64"
    TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
fi
echo "Toolchain: ${TOOLCHAIN}"

# ── Build per ABI ────────────────────────────────────────────────────────────
declare -A ABI_TRIPLE=(
    ["arm64-v8a"]="aarch64-linux-android${API_LEVEL}"
    ["armeabi-v7a"]="armv7a-linux-androideabi${API_LEVEL}"
    ["x86_64"]="x86_64-linux-android${API_LEVEL}"
)

SUCCESS=true
for ABI in "arm64-v8a" "armeabi-v7a" "x86_64"; do
    TRIPLE="${ABI_TRIPLE[$ABI]}"
    CC="${TOOLCHAIN}/bin/${TRIPLE}-clang"

    if [[ ! -x "${CC}" ]]; then
        echo "ERROR: Compiler not found for ${ABI}: ${CC}" >&2
        SUCCESS=false
        continue
    fi

    VENDOR="${VENDOR_DIR}/${ABI}"
    if [[ ! -d "${VENDOR}/include" || ! -f "${VENDOR}/lib/libsodium.a" ]]; then
        echo "ERROR: Vendored libsodium not found for ${ABI} at ${VENDOR}" >&2
        echo "  Expected: ${VENDOR}/include/sodium.h and ${VENDOR}/lib/libsodium.a" >&2
        SUCCESS=false
        continue
    fi

    OUT_DIR="${JNI_LIBS}/${ABI}"
    mkdir -p "${OUT_DIR}"

    echo "Building ${ABI} → ${OUT_DIR}/libmeshlink_sodium.so"
    "${CC}" \
        -shared \
        -fPIC \
        -O2 \
        -Wall \
        -o "${OUT_DIR}/libmeshlink_sodium.so" \
        "${CPP_SRC}/meshlink_sodium.c" \
        -I"${VENDOR}/include" \
        -L"${VENDOR}/lib" \
        -Wl,--whole-archive "${VENDOR}/lib/libsodium.a" -Wl,--no-whole-archive \
        -llog \
        -landroid \
        && echo "  ✓ ${ABI}" \
        || { echo "  ✗ ${ABI} FAILED" >&2; SUCCESS=false; }
done

if [[ "${SUCCESS}" != "true" ]]; then
    echo "One or more ABIs failed to build." >&2
    exit 1
fi

echo ""
echo "Build complete. Verifying ELF output:"
echo "(Note: to verify Android Kotlin compilation, run: ./gradlew :meshlink:compileAndroidMain)"
for ABI in "arm64-v8a" "armeabi-v7a" "x86_64"; do
    SO="${JNI_LIBS}/${ABI}/libmeshlink_sodium.so"
    if [[ -f "${SO}" ]]; then
        file "${SO}"
    else
        echo "MISSING: ${SO}" >&2
    fi
done
