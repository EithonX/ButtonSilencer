# Contributing

Bug fixes and focused improvements are welcome. Button Silencer is intentionally small, so changes should stay close to its job: suppressing faulty external headset controls without interfering with the phone's own buttons.

## Development setup

You need:

- JDK 17
- Gradle 8.13
- Android SDK 36
- Build Tools 35.0.0

Run the same checks used by CI before opening a pull request:

```bash
bash scripts/verify-native-libs.sh
gradle testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

If you change `app/src/main/cpp/evgrab.c`, rebuild all four JNI libraries with `scripts/build-native.sh` and run the native verification script again.

## Pull requests

Keep pull requests focused. For input-routing changes, include the Android version, headset/DAC model, Shizuku mode if relevant, and whether you tested both screen-on and screen-off behavior.

Changes to call-related key handling or exclusive evdev grabbing should include a regression test where practical. Do not weaken the selected-device safety checks just to make more input nodes selectable; `EVIOCGRAB` owns an entire event node, not an individual key.

Do not commit signing keys, keystores, generated APKs, local SDK paths, or diagnostic logs.

## Maintainer releases

Official releases are created from tags matching the version in `gradle.properties`, for example `v3.1.6`.

The `Release` workflow expects these repository secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The keystore is decoded only on the GitHub runner and removed at the end of the job. The release workflow verifies alignment and the APK signature before publishing the APK and its SHA-256 checksum to GitHub Releases.
