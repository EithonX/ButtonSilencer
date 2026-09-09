#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk" ]]; then
  echo 'Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT to an Android NDK installation.' >&2
  exit 1
fi

clang="$(find "$ndk/toolchains/llvm/prebuilt" -type f -path '*/bin/clang' | head -n 1)"
if [[ -z "$clang" ]]; then
  echo 'Could not find clang in the Android NDK.' >&2
  exit 1
fi

src='app/src/main/cpp/evgrab.c'
out='app/src/main/jniLibs'

build_one() {
  local abi="$1" target="$2"
  mkdir -p "$out/$abi"
  "$clang" --target="$target" \
    -Oz -fPIC -fvisibility=hidden -fno-stack-protector \
    -fno-unwind-tables -fno-asynchronous-unwind-tables \
    -nostdlib -shared \
    -Wl,--build-id=none -Wl,-z,max-page-size=16384 \
    -Wl,-z,relro,-z,now,-z,noexecstack \
    -Wl,-soname,libbuttonsilencer_evgrab.so \
    "$src" -o "$out/$abi/libbuttonsilencer_evgrab.so"
}

build_one arm64-v8a aarch64-linux-android26
build_one armeabi-v7a armv7a-linux-androideabi26
build_one x86 i686-linux-android26
build_one x86_64 x86_64-linux-android26

bash scripts/verify-native-libs.sh
