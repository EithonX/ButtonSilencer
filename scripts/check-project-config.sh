#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

python3 - <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path('.')
app = root / 'app'
build = (app / 'build.gradle').read_text(encoding='utf-8')
manifest_path = app / 'src/main/AndroidManifest.xml'
aidl_path = app / 'src/main/aidl/com/buttons/silencer/IPrivilegedBlocker.aidl'
service_path = app / 'src/main/java/com/buttons/silencer/PrivilegedMediaKeyService.java'
workflow_path = root / '.github/workflows/build-apk.yml'
strings_path = app / 'src/main/res/values/strings.xml'

required = [manifest_path, aidl_path, service_path, workflow_path, strings_path]
for path in required:
    if not path.is_file():
        raise SystemExit(f'Missing required file: {path}')

checks = {
    'minSdk 26': re.search(r'^\s*minSdk\s+26(?:\s|$)', build, re.M),
    'compileSdk 36': re.search(r'^\s*compileSdk\s+36(?:\s|$)', build, re.M),
    'Shizuku API': "implementation 'dev.rikka.shizuku:api:13.1.5'" in build,
    'Shizuku provider': "implementation 'dev.rikka.shizuku:provider:13.1.5'" in build,
    'no desugaring dependency': 'coreLibraryDesugaring' not in build,
    'release shrinking': 'minifyEnabled true' in build and 'shrinkResources true' in build,
    'workflow invokes preflight via bash': 'run: bash scripts/check-project-config.sh' in workflow_path.read_text(encoding='utf-8'),
    'developer credit': 'https://github.com/EithonX/' in strings_path.read_text(encoding='utf-8'),
}
for label, ok in checks.items():
    if not ok:
        raise SystemExit(f'Configuration check failed: {label}')

# Parse every XML file now, before Android SDK setup.
xml_files = sorted(root.rglob('*.xml'))
for path in xml_files:
    try:
        ET.parse(path)
    except ET.ParseError as exc:
        raise SystemExit(f'Invalid XML: {path}: {exc}') from exc

# Validate AIDL transaction IDs. All methods must be explicit and unique.
aidl = aidl_path.read_text(encoding='utf-8')
methods = re.findall(r'^\s*[^/\n][^;]*?\s+(\w+)\([^;]*?\)\s*=\s*(\d+)\s*;', aidl, re.M)
expected = {
    'setEnabled': 1,
    'isEnabled': 2,
    'getStatus': 3,
    'listVolumeInputDevices': 4,
    'setHeadsetVolumeGuard': 5,
    'getStateFlags': 6,
    'setDiagnosticLogging': 7,
    'destroy': 16777114,
}
actual = {name: int(value) for name, value in methods}
if actual != expected:
    raise SystemExit(f'AIDL transaction map mismatch: {actual!r}')
if len(set(actual.values())) != len(actual):
    raise SystemExit('AIDL transaction IDs are not unique')

# Validate project resource references without needing aapt2.
res_root = app / 'src/main/res'
resources: dict[str, set[str]] = {}
ids: set[str] = set()
for path in res_root.rglob('*'):
    if not path.is_file():
        continue
    resource_type = path.parent.name.split('-')[0]
    if resource_type == 'values':
        tree = ET.parse(path)
        for child in tree.getroot():
            name = child.attrib.get('name')
            if name:
                resources.setdefault(child.tag, set()).add(name)
    elif resource_type in {'layout', 'drawable', 'mipmap', 'xml'}:
        resources.setdefault(resource_type, set()).add(path.stem)
    if path.suffix == '.xml':
        ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', path.read_text(encoding='utf-8')))

missing = []
for path in list((app / 'src/main').rglob('*.xml')) + list((app / 'src/main/java').rglob('*.java')):
    text = path.read_text(encoding='utf-8')
    for resource_type, name in re.findall(
        r'@(?:\+)?(string|color|style|layout|drawable|mipmap|xml)/([A-Za-z0-9_.]+)',
        text,
    ):
        if name not in resources.get(resource_type, set()):
            missing.append(f'{path}: @{resource_type}/{name}')

for path in (app / 'src/main/java').rglob('*.java'):
    text = path.read_text(encoding='utf-8')
    text_without_android_r = text.replace('android.R.', 'ANDROID_FRAMEWORK_R.')
    for resource_type, name in re.findall(
        r'(?<!ANDROID_FRAMEWORK_)R\.(id|string|layout|drawable|color)\.([A-Za-z0-9_]+)',
        text_without_android_r,
    ):
        pool = ids if resource_type == 'id' else resources.get(resource_type, set())
        if name not in pool:
            missing.append(f'{path}: R.{resource_type}.{name}')

if missing:
    raise SystemExit('Missing resources:\n' + '\n'.join(missing))

service = service_path.read_text(encoding='utf-8')
for required_text in (
    'initializeMediaFrameworkIfNeeded();',
    'android.media.MediaFrameworkPlatformInitializer',
    '/system/bin/getevent',
    'setStreamVolume',
    'getStateFlags()',
    'setDiagnosticLogging(boolean requestedEnabled)',
):
    if required_text not in service:
        raise SystemExit(f'Privileged service check failed: {required_text}')

print(f'Project preflight passed: {len(xml_files)} XML files, {len(ids)} view IDs.')
PY
