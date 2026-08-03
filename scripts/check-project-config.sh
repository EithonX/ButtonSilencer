#!/usr/bin/env bash
set -euo pipefail

# Work correctly even when invoked from another directory and do not rely on
# the executable bit surviving ZIP extraction or web uploads.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

build_file="app/build.gradle"
manifest="app/src/main/AndroidManifest.xml"
aidl="app/src/main/aidl/com/buttons/silencer/IPrivilegedBlocker.aidl"

[[ -f "$build_file" && -f "$manifest" && -f "$aidl" ]]
grep -Eq '^[[:space:]]*minSdk[[:space:]]+26([[:space:]]|$)' "$build_file"
grep -Fq "implementation 'dev.rikka.shizuku:api:13.1.5'" "$build_file"
grep -Fq 'void destroy() = 16777114;' "$aidl"
grep -Fq 'rikka.shizuku.ShizukuProvider' "$manifest"

echo "Project configuration preflight passed."
