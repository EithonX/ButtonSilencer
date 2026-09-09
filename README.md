# Button Silencer

[![Android CI](https://github.com/EithonX/ButtonSilencer/actions/workflows/build-apk.yml/badge.svg)](https://github.com/EithonX/ButtonSilencer/actions/workflows/build-apk.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Button Silencer blocks faulty headset and IEM remote buttons without disabling the phone's own physical buttons.

Some damaged or noisy inline remotes generate phantom volume, media, or headset-hook presses. Besides interrupting playback, those events can answer or end calls. Button Silencer filters them at two layers so protection can continue when the screen is off.

## How it works

- **Screen on:** an Accessibility service consumes the configured headset/media keys before applications receive them. Call-capable keys are always blocked while protection is enabled.
- **Screen off:** a Shizuku UserService handles privileged input routing.
- **Selected headset:** Button Silencer uses Linux `EVIOCGRAB` on the selected external headset's safe `/dev/input/event*` nodes. While the raw guard is active, those nodes are exclusively owned and their button events do not continue into Android's dialer, media, or volume handling.

The phone's own side-button input devices are excluded from headset selection.

> [!IMPORTANT]
> Screen-off protection depends on Shizuku. If Shizuku stops or the selected-headset raw guard is recovering, Button Silencer cannot guarantee that screen-off headset events are blocked. The app reports partial protection rather than treating that state as fully protected.

## Requirements

- Android 8.0 (API 26) or newer
- [Shizuku](https://shizuku.rikka.app/) for screen-off protection
- Accessibility permission for the screen-on filter

## Install

Download the signed APK from [GitHub Releases](https://github.com/EithonX/ButtonSilencer/releases/latest).

1. Install Button Silencer.
2. Enable its Accessibility service.
3. Start Shizuku and grant Button Silencer access.
4. Turn on **Headset button protection**.
5. Connect the affected headset or USB DAC.
6. Tap **Scan headset** and choose the external device.
7. Confirm that the app reports full protection before relying on screen-off call protection.
8. Verify that the phone's own volume buttons still work.

After a reboot, Shizuku must be started again before the privileged screen-off path can return.

## What it does not do

- No network permission, analytics, ads, or account system
- No foreground service or wake lock
- No USB interface claiming or control transfers
- No key-layout or system-file modification
- No persistent event-history database

Diagnostic logging is off by default. When enabled, recent Accessibility events are kept only in the app process's in-memory ring buffer.

## Building

The project uses JDK 17, Gradle 8.13, Android Gradle Plugin 8.13.2, and Android SDK 36.

```bash
gradle testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

The four small JNI libraries under `app/src/main/jniLibs/` implement the exclusive evdev grab. Verify them with:

```bash
bash scripts/verify-native-libs.sh
```

To rebuild those libraries, install the Android NDK, set `ANDROID_NDK_HOME`, and run:

```bash
bash scripts/build-native.sh
```

Pull-request CI builds an ordinary debug APK and an **unsigned** release APK. Official release APKs are built from version tags and signed with the maintainer's private release key.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) for development notes. Please report security-sensitive issues according to [SECURITY.md](SECURITY.md), not in a public issue.

Button Silencer is maintained by [EithonX](https://github.com/EithonX/). Licensed under the [MIT License](LICENSE).
