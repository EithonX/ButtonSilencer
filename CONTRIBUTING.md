# Contributing

Button Silencer is intentionally small. Changes should stay close to its job: blocking faulty external headset controls without interfering with the phone's own buttons.

## Development

Requirements:

- JDK 17
- Android SDK 36
- Build Tools 35.0.0

Run the same checks as CI:

```bash
bash scripts/verify-native-libs.sh
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

If `app/src/main/cpp/evgrab.c` changes, rebuild all native libraries with `scripts/build-native.sh` before running the verification script.

## Pull requests

Keep changes focused. For input-routing fixes, include the Android version, headset or DAC model, Shizuku mode, and whether the behavior was tested with the screen both on and off.

Changes involving call keys or exclusive input grabbing should include a regression test where practical. Do not loosen device-selection safety checks simply to make more input nodes selectable; an exclusive grab owns the whole event node.

Do not commit signing material, APKs, local SDK paths, or diagnostic logs.
