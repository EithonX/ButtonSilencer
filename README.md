# Button Silencer — Shizuku privileged media-key build v2.1

This revision replaces the unreliable screen-off Accessibility-only design with Android's privileged media-key listener through a Shizuku UserService.

## Why this route

Android's hidden `MediaSessionManager.setOnMediaKeyListener()` receives media keys before normal media sessions. If the listener returns `true`, the event is consumed. The permission is privileged, but Android's shell package holds it, so a Shizuku UserService running as shell can register the listener without claiming the USB interface.

## Safety profile

- No USB device/interface ownership.
- No raw USB transfers.
- No root requirement; normal Shizuku ADB mode is sufficient on Android builds where shell has `SET_MEDIA_KEY_LISTENER`.
- No foreground service, silent audio, wake lock, network permission, background polling, or media-session playback takeover. The status panel refreshes only while the app screen is open.
- While enabled, the privileged process sleeps until Android sends a media-key event. Disabling the switch unregisters the listener and stops that UserService process.
- The listener only returns `true` for `HEADSETHOOK` and Android `MEDIA_*` key codes.

This is much lower risk than claiming a USB audio/HID interface. It still cannot be guaranteed that an OEM framework bug will never crash, but this code uses a system media-service listener rather than a hardware driver path.

## Important limitations

- Requires Android 8.0/API 26 or newer. The project now declares minSdk 26 because the privileged listener requires it and Shizuku 13.1.5 itself requires at least API 24.
- Requires Shizuku v13+ and Shizuku permission.
- Shizuku must be running. After a phone or Shizuku restart, reopen Button Silencer and reconnect.
- This privileged listener handles media keys, not ordinary `VOLUME_UP`/`VOLUME_DOWN` presses.
- Accessibility remains included as a separate fallback for screen-on media/assistant/call keys and external volume keys.
- Android supports only one global media-key listener. Another privileged/Shizuku app using the same listener can replace this one.

## Build with GitHub Actions

1. Upload the entire repository, including `.github/workflows/build-apk.yml`.
2. Run **Build APKs** from GitHub Actions.
3. Download the `ButtonSilencer-apks` artifact.
4. Install `ButtonSilencer-release.apk` for the smaller R8-minified build. The debug and release variants use different package IDs, so uninstall the old debug build first if you do not want two app icons.

The workflow also builds `ButtonSilencer-debug.apk`, runs unit tests, runs debug and release lint, verifies both APK signatures, and writes SHA-256 checksums.

## Release signing note

Both APKs are signed with the repository's deterministic test key so Actions can produce directly installable APKs and later builds can update earlier ones. This is appropriate for private testing, not Play Store or public production distribution.

## Enable

1. Start Shizuku and verify it says it is running.
2. Open Button Silencer.
3. Enable **Shizuku media-key listener**.
4. Approve the permission request in Shizuku.
5. Wait for the status to show `ACTIVE`.
6. Lock the phone and test the IEM media/headset button.

The privileged status box shows the latest received media key and whether it was blocked.


## v2.1 build correction

- Project `minSdk` is now API 26, matching the actual privileged-listener requirement and exceeding Shizuku 13.1.5's API 24 minimum.
- GitHub Actions now runs manifest and AIDL preflight tasks before tests, lint, and APK assembly.
