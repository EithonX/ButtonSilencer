# Button Silencer

## Build-fix note

This revision fixes the API-level lint failure from the first repository: `InputDevice.isExternal()` is now called only on Android 10/API 29 or newer, while Android 6–9 use the existing conservative name/descriptor fallback. It also removes a deprecated key-action reference and makes CI run tests/lint before spending time assembling the APK.


Button Silencer is a small Android app that uses an Accessibility Service to consume selected headset/media key events before normal apps handle them. It is designed as a safer replacement for USB-interface claiming.

## What this build does

- Blocks headset hook and Android media keys.
- Blocks volume keys reported by an external input device by default.
- Optionally blocks assistant/call keys.
- Has an explicit dangerous option to block volume keys from every device, including the phone's own side buttons.
- Logs only recognized headset/media/volume/assistant/call events. It does not log keyboard typing or inspect screen contents.
- Records whether the screen was on or off, the key code, scan code, input-device details, and whether the event was consumed.

## What it deliberately does not do

- No USB device or interface claiming.
- No Shizuku, root, ADB privilege, hidden API, or device-owner requirement.
- No MediaSession takeover that could steal playback ownership from the actual music player.
- No foreground service, wake lock, network permission, analytics, advertising, or background polling.

The Accessibility Service remains system-managed and does work only when Android sends a key event. The activity refreshes its event display only while it is open.

## Build the APK with GitHub Actions

1. Create an empty GitHub repository.
2. Upload every file and folder from this repository, including `.github/workflows/build-apk.yml`.
3. Commit to `main` or `master`.
4. Open the repository's **Actions** tab.
5. Select **Build APK**, then choose **Run workflow**. A push to `main` or `master` also starts the build automatically.
6. Open the finished workflow run and download the **ButtonSilencer-debug-apk** artifact.
7. Extract it and install `ButtonSilencer-debug.apk`.

The workflow uses JDK 17, Gradle 8.13, Android Gradle Plugin 8.13.2, compile/target SDK 36, Android Build Tools 35.0.0, unit tests, Android lint, and debug APK assembly. The debug APK is signed with the repository's test-only debug keystore and is directly installable. Because the key is deterministic and the workflow uses its increasing run number as the Android version code, APKs from later workflow runs can update earlier builds instead of failing with a signature or downgrade error. Do not use this public debug key for a production release.

## Enable and test

1. Open Button Silencer.
2. Tap **Open Accessibility settings**.
3. Find **Button Silencer key filter** and enable it.
4. On Android 13 or newer, a sideloaded APK may show a disabled service switch. Open **App info**, use the top-right menu, choose **Allow restricted settings**, then return to Accessibility settings.
5. Leave **Blocking enabled**, **Block headset and media keys**, and **Block volume keys from external devices** enabled.
6. Press every IEM button with the screen on.
7. Lock the phone and repeat the same test with the screen off.
8. Reopen the app and inspect the event list.

Interpretation:

- `BLOCKED` means Android delivered the key to the Accessibility Service and the service returned `true` to consume it.
- `PASSED` means the key was recognized but its blocking rule was disabled.
- `screen=OFF` confirms that Android delivered that event while the display was non-interactive.
- `external=false` on a USB/IEM volume key means the phone's input stack did not identify it as external. Enable **Block volume keys from every device** only for testing, because that also blocks the phone's hardware volume buttons.
- No event at all means the DAC, kernel driver, OEM firmware, telephony stack, or another higher-priority component handled the button before this normal app could receive it.

## Important Android limitations

Android can grant key-event filtering to an Accessibility Service, but behavior still depends on the device firmware and input-driver mapping. Some call controls, voice-assistant gestures, analog headset remotes, and USB audio controls may be intercepted before an Accessibility Service sees them.

If multiple enabled Accessibility Services request key-event filtering, Android may deliver the event to only one of them. Temporarily disable other services that filter hardware keys when testing.

This repository can prove whether the method works for the exact IEM/phone combination without the crash risk of taking ownership of a USB interface.

## Repository layout

- `app/src/main/java/com/buttons/silencer/ButtonBlockerService.java` — key interception and consumption.
- `app/src/main/java/com/buttons/silencer/ButtonPolicy.java` — deterministic blocking policy.
- `app/src/main/java/com/buttons/silencer/DeviceClassifier.java` — conservative external-device detection.
- `app/src/main/java/com/buttons/silencer/EventLogStore.java` — private rolling diagnostic log.
- `app/src/main/java/com/buttons/silencer/MainActivity.java` — settings and diagnostics UI.
- `app/src/test/.../ButtonPolicyTest.java` — unit tests for the safety-critical blocking rules.
- `.github/workflows/build-apk.yml` — reproducible APK build and artifact upload.

## Package name

The debug build installs as `com.buttons.silencer.debug`. This avoids conflicts with any earlier Button Silencer APK that used another signing key or package.
