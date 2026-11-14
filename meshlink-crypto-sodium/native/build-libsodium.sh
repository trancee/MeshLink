#!/bin/sh
# Builds static libsodium for one MeshLink target from a tarball that verifyLibsodium already checked
# (ADR 0004, ADR 0005 "libsodium build" and its 2026-11-14 amendment). LibsodiumPlugin calls it:
#
#   build-libsodium.sh toolchain <target>                  prints the toolchain identity for the cache stamp
#   build-libsodium.sh build <target> <tarball> <output>   writes <output>/include and <output>/lib/libsodium.a
#   build-libsodium.sh shim <target> <libsodium> <jni-include> <output>
#                                                         links meshlink_sodium_jni.c and <libsodium>/lib/libsodium.a
#                                                         into <output>, a shared library exporting only JNI_OnLoad
#
# Android runs the unmodified upstream dist-build scripts (-Os, --enable-minimal) and keeps only the
# static archive. Apple and the hosts configure each slice with the flags upstream's
# apple-xcframework.sh uses, except -Os for -O3, because that script builds every Apple platform
# and fails on Xcode 27 (armv7k).
set -eu

IOS_VERSION_MIN=15.0
MACOS_VERSION_MIN=11.0

usage() {
  echo "usage: $0 toolchain <target> | build <target> <tarball> <output> | shim <target> <libsodium> <jni-include> <output>" >&2
  exit 2
}

[ $# -ge 2 ] || usage
command=$1
target=$2

android_ndk() {
  # Like AGP: the first of ANDROID_HOME and ANDROID_SDK_ROOT that exists. The pinned NDK version
  # always wins over an inherited ANDROID_NDK_HOME (runner images point it at their default NDK).
  sdk=
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then
      sdk=$candidate
      break
    fi
  done
  ndk="$sdk/ndk/${NDK_VERSION:-}"
  revision=$(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties" 2>/dev/null || true)
  if [ -z "$sdk" ] || [ "$revision" != "${NDK_VERSION:-}" ]; then
    echo "NDK ${NDK_VERSION:-<unset>} not found at '$ndk' (found '$revision'); install it with: sdkmanager 'ndk;${NDK_VERSION:-}'" >&2
    exit 1
  fi
  export ANDROID_NDK_HOME="$ndk"
}

apple_sdk() {
  case $target in
    ios-arm64) echo iphoneos ;;
    ios-arm64-simulator) echo iphonesimulator ;;
    macos-arm64) echo macosx ;;
  esac
}

toolchain() {
  case $target in
    android-*)
      android_ndk
      echo "ndk $revision $NDK_PLATFORM"
      ;;
    ios-* | macos-*)
      xcodebuild -version | tr '\n' ' '
      echo "sdk $(xcrun --sdk "$(apple_sdk)" --show-sdk-version)"
      ;;
    linux-*)
      cc --version | head -n 1
      ;;
    *)
      echo "unknown target: $target" >&2
      exit 2
      ;;
  esac
}

apple() {
  sdk=$(xcrun --sdk "$(apple_sdk)" --show-sdk-path)
  case $target in
    ios-arm64) min="-mios-version-min=$IOS_VERSION_MIN" ;;
    ios-arm64-simulator) min="-mios-simulator-version-min=$IOS_VERSION_MIN" ;;
    macos-arm64) min="-mmacosx-version-min=$MACOS_VERSION_MIN" ;;
  esac
  CFLAGS="-Os -arch arm64 -isysroot $sdk $min" LDFLAGS="-arch arm64 -isysroot $sdk $min" \
    ./configure --host=aarch64-apple-darwin23 --prefix="$PWD/meshlink-prefix" \
    --enable-minimal --disable-shared
  make -j"$jobs" install
  prefix=meshlink-prefix
}

build() {
  [ $# -eq 4 ] || usage
  tarball=$3
  output=$4
  jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  tar xzf "$tarball" -C "$work"
  cd "$work"/libsodium-*
  case $target in
    android-arm64-v8a)
      android_ndk
      ./dist-build/android-armv8-a.sh
      prefix=libsodium-android-armv8-a+crypto
      ;;
    android-armeabi-v7a)
      android_ndk
      ./dist-build/android-armv7-a.sh
      prefix=libsodium-android-armv7-a
      ;;
    android-x86_64)
      android_ndk
      ./dist-build/android-x86_64.sh
      prefix=libsodium-android-westmere
      ;;
    ios-* | macos-*)
      apple
      ;;
    linux-x86_64)
      # --with-pic: the host JNI shim (ticket 07) links this archive into a shared object.
      CFLAGS="-Os" ./configure --prefix="$PWD/meshlink-prefix" --enable-minimal --disable-shared --with-pic
      make -j"$jobs" install
      prefix=meshlink-prefix
      ;;
    *)
      echo "unknown target: $target" >&2
      exit 2
      ;;
  esac
  mkdir -p "$output/lib"
  cp -R "$prefix/include" "$output/include"
  cp "$prefix/lib/libsodium.a" "$output/lib/libsodium.a"
}

shim() {
  [ $# -eq 5 ] || usage
  sodium=$3
  jni=$4
  output=$5
  native=$(cd "$(dirname "$0")" && pwd)
  mkdir -p "$(dirname "$output")"
  # Hidden visibility plus an export list leaves JNI_OnLoad as the only exported symbol; libsodium's
  # own symbols stay local, and unreferenced libsodium objects are dropped.
  common="-Os -fvisibility=hidden -ffunction-sections -fdata-sections -I$sodium/include $native/meshlink_sodium_jni.c $sodium/lib/libsodium.a"
  case $target in
    android-*)
      android_ndk
      case $target in
        android-arm64-v8a) triple=aarch64-linux-android ;;
        android-armeabi-v7a) triple=armv7a-linux-androideabi ;;
        android-x86_64) triple=x86_64-linux-android ;;
      esac
      clang=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/clang)
      # shellcheck disable=SC2086 # $common is a word list.
      "$clang" --target="$triple${NDK_PLATFORM#android-}" -shared -fPIC $common \
        -Wl,--version-script="$native/meshlink_sodium_jni.map" -Wl,--gc-sections -Wl,--exclude-libs,ALL \
        -Wl,--no-undefined -Wl,-z,max-page-size=16384 -s -o "$output"
      ;;
    macos-arm64)
      # shellcheck disable=SC2086 # $common is a word list.
      cc -dynamiclib -arch arm64 -mmacosx-version-min=$MACOS_VERSION_MIN -I"$jni" -I"$jni/darwin" $common \
        -Wl,-exported_symbols_list,"$native/meshlink_sodium_jni.exports" -Wl,-dead_strip -Wl,-x -o "$output"
      ;;
    linux-x86_64)
      # shellcheck disable=SC2086 # $common is a word list.
      cc -shared -fPIC -I"$jni" -I"$jni/linux" $common \
        -Wl,--version-script="$native/meshlink_sodium_jni.map" -Wl,--gc-sections -Wl,--exclude-libs,ALL \
        -Wl,--no-undefined -s -o "$output"
      ;;
    *)
      echo "no JNI shim for target: $target" >&2
      exit 2
      ;;
  esac
}

case $command in
  toolchain) toolchain ;;
  build) build "$@" ;;
  shim) shim "$@" ;;
  *) usage ;;
esac
