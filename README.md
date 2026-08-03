# Button Silencer v2.4 — media-key blocker + selected-headset volume guard

This build keeps the working Shizuku privileged media-key listener from v2.3 and adds a screen-off volume guard that targets only one explicitly selected headset input device.

## What it does

- Consumes `HEADSETHOOK` and Android `MEDIA_*` events before ordinary media sessions, including while the screen is off.
- Uses Shizuku's shell process to run Android's read-only `getevent` tool against the selected headset input node.
- When that selected device reports `VOLUME_UP` or `VOLUME_DOWN`, immediately restores the previous media-volume level.
- Leaves the phone's own side-volume buttons alone because events from other input devices are ignored.
- Keeps the Accessibility Service for screen-on filtering and diagnostics.

## Safety profile

- No USB-interface claiming.
- No USB control transfers or audio-driver manipulation.
- No `EVIOCGRAB`, input-device disable, key-layout replacement, root module, or kernel modification.
- The `getevent` process blocks while waiting for an event; it does not poll continuously.
- No wake lock, silent playback, network permission, or media-session playback takeover.

The volume guard is deliberately a **neutralizer**, not a kernel-level hard block. Android may briefly display its volume overlay, and an extremely short volume change may be observable before the previous level is restored. This is safer than exclusively grabbing the input node.

## Requirements and limitations

- Android 8.0/API 26 or newer.
- Shizuku v13+ running with permission granted.
- The headset must expose its controls as a Linux/Android input device visible to `getevent`.
- Some DACs change their own hardware volume internally. Android cannot undo a change that never reaches the phone as a key/input event.
- The app refuses devices whose names look like built-in phone-button devices such as `gpio-keys`, `qpnp`, PMIC/keypad, or side-key devices.
- If the device path changes after reconnecting, the service attempts to find the same device by name.

## Build with GitHub Actions

1. Upload the entire repository, including `.github/workflows/build-apk.yml`.
2. Run **Build APKs**.
3. Download the `ButtonSilencer-apks` artifact.
4. Install `ButtonSilencer-release.apk` for the smaller R8-minified build.

The artifact also contains `ButtonSilencer-debug.apk` and `SHA256SUMS.txt`. Tests and both debug/release lint tasks run before APK assembly.

## Enable and configure

1. Start Shizuku.
2. Open Button Silencer and enable **Shizuku media-key listener**.
3. Approve Shizuku permission and wait for the media listener to show `ACTIVE`.
4. Connect the IEM/DAC/headset.
5. Tap **Scan volume-key input devices**.
6. Select the USB/IEM/headset entry. Do not select entries marked as likely phone buttons.
7. Enable **Neutralize volume presses from selected headset**.
8. Lock the phone and test play/pause and both headset volume buttons.

The status panel shows the selected device, monitored `/dev/input/event*` path, neutralized-event count, and any permission/device error.

## Signing

Both APKs use the repository's deterministic test key so later Actions builds can update earlier private-test installs. Replace it before any public distribution.
