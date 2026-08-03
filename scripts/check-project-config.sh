#!/usr/bin/env bash
set -euo pipefail

# Work correctly even when invoked from another directory and do not rely on
# the executable bit surviving ZIP extraction or web uploads.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

build_file="app/build.gradle"
manifest="app/src/main/AndroidManifest.xml"
aidl="app/src/main/aidl/com/buttons/silencer/IPrivilegedBlocker.aidl"
service_source="app/src/main/java/com/buttons/silencer/PrivilegedMediaKeyService.java"

[[ -f "$build_file" && -f "$manifest" && -f "$aidl" && -f "$service_source" ]]
grep -Eq '^[[:space:]]*minSdk[[:space:]]+26([[:space:]]|$)' "$build_file"
grep -Fq "implementation 'dev.rikka.shizuku:api:13.1.5'" "$build_file"
grep -Fq 'String[] listVolumeInputDevices() = 4;' "$aidl"
grep -Fq 'boolean setHeadsetVolumeGuard(String encodedDevice, boolean enabled) = 5;' "$aidl"
grep -Fq 'void destroy() = 16777114;' "$aidl"
grep -Fq 'rikka.shizuku.ShizukuProvider' "$manifest"
grep -Fq 'android.permission.MODIFY_AUDIO_SETTINGS' "$manifest"
grep -Fq 'initializeMediaFrameworkIfNeeded();' "$service_source"
grep -Fq 'android.media.MediaFrameworkPlatformInitializer' "$service_source"
grep -Fq 'setMediaServiceManager' "$service_source"
grep -Fq '/system/bin/getevent' "$service_source"
grep -Fq 'setStreamVolume' "$service_source"

echo "Project configuration preflight passed."
