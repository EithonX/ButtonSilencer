<p align="center">
  <img src="docs/assets/app-icon.png" width="112" alt="Button Silencer app icon">
</p>

<h1 align="center">Button Silencer</h1>

<p align="center">
  Block faulty headset and IEM remote buttons without disabling your phone's own buttons.
</p>

<p align="center">
  <a href="https://github.com/EithonX/ButtonSilencer/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/EithonX/ButtonSilencer?display_name=tag&sort=semver"></a>
  <a href="https://github.com/EithonX/ButtonSilencer/actions/workflows/build-apk.yml"><img alt="Android CI" src="https://github.com/EithonX/ButtonSilencer/actions/workflows/build-apk.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/license-MIT-5B57E8"></a>
</p>

<p align="center">
  <a href="https://github.com/EithonX/ButtonSilencer/releases/latest"><strong>Download the latest APK</strong></a>
</p>

Faulty inline remotes can generate phantom volume, media, assistant, or headset-hook presses. In the worst case, a ghost headset click can answer or end a call. Button Silencer blocks those controls while leaving the phone's physical buttons alone.

## Protection model

| State | Protection |
| --- | --- |
| Screen on | Android Accessibility filters headset keys before apps receive them. |
| Screen off | Shizuku provides the privileged path used for headset input protection. |
| Selected headset | Its safe remote-control input nodes can be exclusively guarded before Android handles their button events. |

Call-capable headset keys are treated as safety-critical while protection is enabled. The app only reports full protection when the screen-on path and the selected-headset screen-off guard are both ready.

## Getting started

1. Install the APK from [Releases](https://github.com/EithonX/ButtonSilencer/releases/latest).
2. Enable Button Silencer in Android Accessibility settings.
3. Start Shizuku and grant Button Silencer permission.
4. Turn on **Headset button protection**.
5. Connect the affected headset or USB DAC, tap **Scan headset**, and select it.

After a reboot, Shizuku must be started again before screen-off protection can return.

## Requirements

- Android 8.0 (API 26) or newer
- Shizuku 13 or newer for screen-off protection
- Accessibility permission for screen-on filtering

## Privacy and behavior

Button Silencer has no network permission, analytics, ads, or account system. It does not claim USB interfaces, modify Android key-layout files, use a wake lock, or run a permanent polling loop. Diagnostic logging is off by default and stays in memory for the current app session.

## Building

The project uses JDK 17, Android SDK 36, Android Gradle Plugin 8.13.2, and Gradle 8.13.

```bash
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

The native `EVIOCGRAB` bridge is committed for four Android ABIs. Verify it with:

```bash
bash scripts/verify-native-libs.sh
```

To rebuild the native libraries, set `ANDROID_NDK_HOME` and run:

```bash
bash scripts/build-native.sh
```

See [docs/architecture.md](docs/architecture.md) for the input-routing design and safety boundaries.

## Contributing

Focused fixes and device-specific compatibility improvements are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

Security-sensitive issues should follow [SECURITY.md](SECURITY.md).

Maintained by [EithonX](https://github.com/EithonX/). Licensed under the [MIT License](LICENSE).
