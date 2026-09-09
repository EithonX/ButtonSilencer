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
controller_path = app / 'src/main/java/com/buttons/silencer/ShizukuController.java'
main_activity_path = app / 'src/main/java/com/buttons/silencer/MainActivity.java'
workflow_path = root / '.github/workflows/build-apk.yml'
strings_path = app / 'src/main/res/values/strings.xml'
workflow = workflow_path.read_text(encoding='utf-8')

required = [manifest_path, aidl_path, service_path, controller_path, main_activity_path, workflow_path, strings_path]
for path in required:
    if not path.is_file():
        raise SystemExit(f'Missing required file: {path}')

checks = {
    'minSdk 26': re.search(r'^\s*minSdk\s+26(?:\s|$)', build, re.M),
    'compileSdk 36': re.search(r'^\s*compileSdk\s+36(?:\s|$)', build, re.M),
    'Shizuku API': "implementation 'dev.rikka.shizuku:api:13.1.5'" in build,
    'Shizuku provider': "implementation 'dev.rikka.shizuku:provider:13.1.5'" in build,
    'Shizuku-compatible AndroidX annotations': "implementation 'androidx.annotation:annotation:1.3.0'" in build,
    'no desugaring dependency': 'coreLibraryDesugaring' not in build,
    'release shrinking': 'minifyEnabled true' in build and 'shrinkResources true' in build,
    'workflow invokes preflight via bash': 'run: bash scripts/check-project-config.sh' in workflow,
    'developer credit': 'https://github.com/EithonX/' in strings_path.read_text(encoding='utf-8'),
    'current checkout action': 'actions/checkout@v6' in workflow,
    'current Gradle action': 'gradle/actions/setup-gradle@v6' in workflow,
    'Android SDK action': 'android-actions/setup-android@v4' in workflow,
    'high-level Android CI tasks': all(task in workflow for task in ('testDebugUnitTest', 'lintDebug', 'lintRelease', 'assembleDebug', 'assembleRelease')),
    'CI continues independent tasks after failure': '--continue' in workflow,
    'CI captures full Gradle log': 'tee .ci/gradle.log' in workflow and '.ci/gradle.log' in workflow,
    'APK alignment verification': 'zipalign' in workflow and '-P 16 -v 4' in workflow,
    'APK signature verification': 'apksigner' in workflow,
    '3.1.4 CI version base': '314000 + GITHUB_RUN_NUMBER' in workflow,
}
for label, ok in checks.items():
    if not ok:
        raise SystemExit(f'Configuration check failed: {label}')

# Shizuku 13.1.5 itself uses androidx.annotation 1.3.0. Keep the app aligned exactly;
# a newer direct annotation version conflicts with Gradle's consistent runtime resolution.
annotation_versions = re.findall(
    r"androidx\.annotation:annotation:([^'\"]+)", build
)
if annotation_versions != ['1.3.0']:
    raise SystemExit(
        'Dependency check failed: expected exactly androidx.annotation:annotation:1.3.0; '
        f'found {annotation_versions!r}'
    )

# Avoid version ranges/dynamic versions in CI-critical Android dependencies.
for coordinate in re.findall(r"(?:implementation|compileOnly|runtimeOnly|testImplementation)\s+['\"]([^'\"]+)['\"]", build):
    if '+' in coordinate or '[' in coordinate or ']' in coordinate or '(' in coordinate or ')' in coordinate:
        raise SystemExit(f'Dynamic/ranged dependency is not allowed: {coordinate}')

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
    elif resource_type in {'layout', 'drawable', 'mipmap', 'xml', 'color'}:
        resources.setdefault(resource_type, set()).add(path.stem)
    if path.suffix == '.xml':
        ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', path.read_text(encoding='utf-8')))

missing = []
for path in list((app / 'src/main').rglob('*.xml')) + list((app / 'src/main/java').rglob('*.java')):
    text = path.read_text(encoding='utf-8')
    for resource_type, name in re.findall(
        r'@(?:\+)?(string|color|style|layout|drawable|mipmap|xml|dimen)/([A-Za-z0-9_.]+)',
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

# Android infers a parent from dotted style names when parent is omitted.
# Catch missing inferred parents before AAPT2 resource linking.
style_names = resources.get('style', set())
for path in sorted(res_root.glob('values*/**/*.xml')):
    tree = ET.parse(path)
    for child in tree.getroot():
        if child.tag != 'style':
            continue
        name = child.attrib.get('name', '')
        if '.' in name and 'parent' not in child.attrib:
            inferred_parent = name.rsplit('.', 1)[0]
            if inferred_parent not in style_names:
                raise SystemExit(
                    f'Missing inferred style parent: {path}: style {name!r} requires '
                    f'@style/{inferred_parent}; add an explicit parent or rename the style'
                )

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


controller = controller_path.read_text(encoding='utf-8')
if 'private final Runnable reconnectRunnable;' not in controller:
    raise SystemExit('Shizuku controller regression: reconnectRunnable must be constructor-initialized')
constructor_pos = controller.find('ShizukuController(Context context)')
assignment_pos = controller.find('reconnectRunnable = () ->', constructor_pos)
if constructor_pos < 0 or assignment_pos < 0:
    raise SystemExit('Shizuku controller regression: reconnectRunnable assignment missing from constructor')
if controller.find('private final Runnable reconnectRunnable =') >= 0:
    raise SystemExit('Shizuku controller regression: reconnectRunnable captures context before construction')


# Guard the interaction regression that caused 3.1.3 to wait forever: the main protection switch
# must persist the protection request before any optional headset scan, and scans must be able to
# create a temporary Shizuku connection even while protection itself is off.
main_activity = main_activity_path.read_text(encoding='utf-8')
listener_start = main_activity.find('protectionSwitch.setOnCheckedChangeListener')
listener_end = main_activity.find('mediaListenerSwitch.setOnCheckedChangeListener', listener_start)
if listener_start < 0 or listener_end < 0:
    raise SystemExit('Protection choreography check failed: primary switch listener not found')
primary_listener = main_activity[listener_start:listener_end]
if 'setProtectionEnabled(true)' not in primary_listener:
    raise SystemExit('Protection choreography check failed: primary switch does not request protection')
if 'scanVolumeInputDevices()' in primary_listener or 'headsetVolumeDevice' in primary_listener:
    raise SystemExit('Protection choreography check failed: headset selection must not gate protection')
if 'pendingProtectionEnable' in main_activity:
    raise SystemExit('Protection choreography check failed: legacy pendingProtectionEnable deadlock returned')
if 'requestSetupConnection()' not in controller or 'setupConnectionRequested' not in controller:
    raise SystemExit('Shizuku setup check failed: temporary setup connection path is missing')
if '!Preferences.privilegedProtectionRequested(context) && !setupConnectionRequested' not in controller:
    raise SystemExit('Shizuku setup check failed: setup connection cannot bypass protection preference')

print(f'Project preflight passed: {len(xml_files)} XML files, {len(ids)} view IDs.')
PY
