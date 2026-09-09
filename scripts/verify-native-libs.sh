#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

command -v readelf >/dev/null || { echo 'readelf is required' >&2; exit 1; }

declare -A machines=(
  [arm64-v8a]='AArch64'
  [armeabi-v7a]='ARM'
  [x86]='Intel 80386'
  [x86_64]='Advanced Micro Devices X86-64'
)

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  lib="app/src/main/jniLibs/$abi/libbuttonsilencer_evgrab.so"
  test -s "$lib"

  readelf -h "$lib" | grep -Fq "Machine:                           ${machines[$abi]}"
  ! readelf -d "$lib" | grep -q '(NEEDED)'
  readelf -Ws "$lib" | grep -q 'Java_com_buttons_silencer_EvdevExclusiveGuard_nativeSetGrab'

  while read -r align; do
    (( 16#$align >= 0x4000 )) || {
      echo "$lib has a LOAD segment aligned below 16 KiB" >&2
      exit 1
    }
  done < <(readelf -lW "$lib" | awk '$1 == "LOAD" { sub(/^0x/, "", $NF); print $NF }')
done

echo 'Native libraries verified.'
