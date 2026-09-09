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
evdev_bridge_path = app / 'src/main/java/com/buttons/silencer/EvdevExclusiveGuard.java'
native_source_path = app / 'src/main/cpp/evgrab.c'
proguard_path = app / 'proguard-rules.pro'
workflow_path = root / '.github/workflows/build-apk.yml'
strings_path = app / 'src/main/res/values/strings.xml'
workflow = workflow_path.read_text(encoding='utf-8')

required = [manifest_path, aidl_path, service_path, controller_path, main_activity_path, evdev_bridge_path, native_source_path, proguard_path, workflow_path, strings_path]
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
    '3.1.5 CI version base': '315000 + GITHUB_RUN_NUMBER' in workflow,
    'JNI class preserved for release': 'com.buttons.silencer.EvdevExclusiveGuard' in proguard_path.read_text(encoding='utf-8'),
    'obsolete audio mutation permission removed': 'android.permission.MODIFY_AUDIO_SETTINGS' not in manifest_path.read_text(encoding='utf-8'),
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
    'EvdevExclusiveGuard.setGrab',
    'ParcelFileDescriptor.open',
    'resolveSelectedDevices',
    'reconcileVolumeGuardLocked',
    'scheduleInputTopologyReconcileLocked',
    'exclusiveGrabSafe',
    'getStateFlags()',
    'setDiagnosticLogging(boolean requestedEnabled)',
):
    if required_text not in service:
        raise SystemExit(f'Privileged service check failed: {required_text}')


# The raw call-safety route is packaged as four tiny prebuilt JNI libraries so CI does not need an
# NDK download. Validate their ABI, lack of libc dependencies, 16 KiB load alignment, and JNI symbol.
import shutil
import subprocess

readelf = shutil.which('readelf')
if not readelf:
    raise SystemExit('Native guard check failed: readelf is unavailable')
expected_machines = {
    'arm64-v8a': 'AArch64',
    'armeabi-v7a': 'ARM',
    'x86': 'Intel 80386',
    'x86_64': 'Advanced Micro Devices X86-64',
}
for abi, machine in expected_machines.items():
    lib = app / 'src/main/jniLibs' / abi / 'libbuttonsilencer_evgrab.so'
    if not lib.is_file() or lib.stat().st_size == 0:
        raise SystemExit(f'Native guard check failed: missing {lib}')
    header = subprocess.check_output([readelf, '-h', str(lib)], text=True)
    if f'Machine:                           {machine}' not in header:
        raise SystemExit(f'Native guard check failed: wrong machine for {abi}')
    dynamic = subprocess.check_output([readelf, '-d', str(lib)], text=True)
    if '(NEEDED)' in dynamic:
        raise SystemExit(f'Native guard check failed: {abi} unexpectedly has DT_NEEDED dependencies')
    program = subprocess.check_output([readelf, '-lW', str(lib)], text=True)
    load_lines = [line for line in program.splitlines() if line.lstrip().startswith('LOAD ')]
    if not load_lines or any(int(line.split()[-1], 16) < 0x4000 for line in load_lines):
        raise SystemExit(f'Native guard check failed: {abi} LOAD alignment is below 16 KiB')
    symbols = subprocess.check_output([readelf, '-Ws', str(lib)], text=True)
    if 'Java_com_buttons_silencer_EvdevExclusiveGuard_nativeSetGrab' not in symbols:
        raise SystemExit(f'Native guard check failed: JNI symbol missing from {abi}')

native_source = native_source_path.read_text(encoding='utf-8')
if 'EVIOCGRAB_REQUEST 0x40044590UL' not in native_source:
    raise SystemExit('Native guard check failed: EVIOCGRAB request constant changed')
# Linux evdev treats EVIOCGRAB's third ioctl argument itself as a boolean pointer value:
# non-zero grabs, zero releases. Passing &arg is a dangerous regression because even a zero int
# then has a non-zero address and cannot perform a normal ungrab.
if 'unsigned long arg = enabled ? 1UL : 0UL;' not in native_source:
    raise SystemExit('Native guard check failed: EVIOCGRAB boolean argument semantics changed')
if 'raw_ioctl(fd, EVIOCGRAB_REQUEST, arg)' not in native_source:
    raise SystemExit('Native guard check failed: EVIOCGRAB value argument call missing')
if re.search(r'raw_ioctl\s*\([^;\n]*&arg', native_source):
    raise SystemExit('Native guard check failed: EVIOCGRAB must pass value 1/0, never &arg')
if 'nativeSetGrab' not in evdev_bridge_path.read_text(encoding='utf-8'):
    raise SystemExit('Native guard check failed: Java bridge method missing')


# While a composite device is only partially acquired, already-grabbed nodes must keep being
# drained. Tying reader lifetime to volumeGuardActive would release/stop the healthy part exactly
# when another node is recovering.
reader_start = service.find('private void drainGrabbedInput(GrabbedInput input)')
reader_end = service.find('private static String joinPaths', reader_start)
if reader_start < 0 or reader_end < 0:
    raise SystemExit('Raw guard check failed: input reader method not found')
reader_body = service[reader_start:reader_end]
if 'grabbedInputs.contains(input)' not in reader_body:
    raise SystemExit('Raw guard check failed: reader is not tied to held input membership')
if 'if (!volumeGuardEnabled || !volumeGuardActive' in reader_body:
    raise SystemExit('Raw guard check failed: partial topology recovery would stop held readers')

parser_source = (app / 'src/main/java/com/buttons/silencer/VolumeInputDeviceParser.java').read_text(encoding='utf-8')
for safety_marker in ('SW_HEADPHONE_INSERT', 'SW_MICROPHONE_INSERT', 'exclusiveGrabSafe', 'containsTypingKey', 'KEY_SEND', 'KEY_FORWARDMAIL'):
    if safety_marker not in parser_source:
        raise SystemExit(f'Raw guard safety regression: missing {safety_marker}')

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

if 'effectiveEnabled = !safeDevice.isEmpty()' not in controller or 'Preferences.privilegedMediaEnabled(context)' not in controller:
    raise SystemExit('Call-safety regression: selected raw guard is not coupled to screen-off protection')
if 'boolean rawGuardRequested = !selectedDevice.isEmpty()' not in controller \
        or 'Preferences.headsetVolumeGuardEnabled(context) || mediaRequested' not in controller:
    raise SystemExit('Call-safety regression: stale preferences can start media protection without raw guard')
button_policy = (app / 'src/main/java/com/buttons/silencer/ButtonPolicy.java').read_text(encoding='utf-8')
for safety_key in ('KEYCODE_HEADSETHOOK', 'KEYCODE_MEDIA_PLAY_PAUSE', 'KEYCODE_CALL', 'KEYCODE_ENDCALL'):
    if safety_key not in button_policy:
        raise SystemExit(f'Call-safety regression: {safety_key} is missing from ButtonPolicy')
if 'case CALL_SAFETY:' not in button_policy or 'return true;' not in button_policy[button_policy.find('case CALL_SAFETY:'):]:
    raise SystemExit('Call-safety regression: safety-critical keys are not unconditional while master is on')

print(f'Project preflight passed: {len(xml_files)} XML files, {len(ids)} view IDs.')
PY
