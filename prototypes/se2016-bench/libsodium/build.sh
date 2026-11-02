#!/bin/sh
# Builds static libsodium 1.0.22 (--enable-minimal) for iosArm64 and macosArm64 (host vector checks only).
# Compiler and configure flags are copied from upstream dist-build/apple-xcframework.sh (build_ios, build_macos);
# only the deployment targets differ (iOS 15.0 per ADR 0005). Running the whole upstream script builds every
# Apple platform (~16 min, ADR 0005), which this benchmark does not need.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
version=1.0.22
sha256=adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349
pubkey=RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3
work="$here/work"
out="$here/out"
mkdir -p "$work" "$out"
cd "$work"
tarball="libsodium-$version.tar.gz"
[ -f "$tarball" ] || curl -sSLO "https://download.libsodium.org/libsodium/releases/$tarball"
[ -f "$tarball.minisig" ] || curl -sSLO "https://download.libsodium.org/libsodium/releases/$tarball.minisig"
echo "$sha256  $tarball" | shasum -a 256 -c -
minisign -Vm "$tarball" -P "$pubkey"
rm -rf "libsodium-$version"
tar xzf "$tarball"
cd "libsodium-$version"
xcodedir=$(xcode-select -p)
jobs=$(sysctl -n hw.ncpu)

build() {
    target=$1 platform=$2 minflag=$3
    sdk="$xcodedir/Platforms/$platform.platform/Developer/SDKs/$platform.sdk"
    export CFLAGS="-O3 -arch arm64 -isysroot $sdk $minflag"
    export LDFLAGS="-arch arm64 -isysroot $sdk $minflag"
    make distclean >/dev/null 2>&1 || true
    ./configure --host=aarch64-apple-darwin23 --prefix="$out/$target" --enable-minimal \
        --disable-shared --enable-static >/dev/null
    make -j"$jobs" install >/dev/null
}

build iosArm64 iPhoneOS -mios-version-min=15.0
build macosArm64 MacOSX -mmacosx-version-min=11.0
cd "$here"
rm -rf "$work/libsodium-$version"
ls -l "$out"/*/lib/libsodium.a
