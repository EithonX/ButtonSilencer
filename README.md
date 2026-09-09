# Button Silencer 3.1.1

A compact Android utility that blocks unwanted headset controls without claiming the USB audio interface or disabling the phone's own buttons.

**Developer:** [EithonX](https://github.com/EithonX/)

## What this build does

- Blocks `HEADSETHOOK` and Android media keys through a privileged Shizuku media-key listener.
- Neutralizes volume-up and volume-down only from the explicitly selected headset/IEM input device.
- Leaves the phone's own physical volume buttons working.
- Keeps privileged protection active with the display on or off while Shizuku is running.
- Keeps the previous Accessibility key filter as an optional advanced fallback.
- Uses no network permission, analytics, ads, account, foreground notification, wake lock, USB-interface claim, or persistent event log.

## 3.1.1 reliability changes

The Shizuku lifecycle is now self-healing instead of relying on the activity being reopened. This point release also fixes the Java definite-assignment bug exposed by GitHub Actions: the reconnect callback is now created only after the application context is assigned. A compile-only AndroidX annotation dependency removes Shizuku metadata warnings without adding runtime APK weight.

- The app uses Shizuku's sticky binder listener and rebinds the daemon UserService when the Shizuku binder returns.
- The app also links directly to the privileged UserService binder so a dead/restarted privileged process is detected even when Shizuku itself is still alive.
- Rebind attempts use a capped backoff and only start after a real disconnect. A timed-out bind is explicitly detached before retrying so a stale app-side connection cannot wedge later recovery. There is no permanent keep-alive loop.
- If the selected `/dev/input/event*` monitor disappears, the privileged service watches `/dev/input` for node changes and re-resolves the selected device by name. This handles USB reconnects and event-number churn without continuously rescanning.
- A few short fallback retries cover transient races around detach/reattach; after those, the service goes fully idle until `/dev/input` changes.

Shizuku daemon UserServices are killed when the Shizuku service itself stops or restarts. Button Silencer therefore cannot protect headset buttons while Shizuku is actually offline, but it will reattach after Shizuku returns and permission is still granted.

## 3.1.1 interface redesign

The main screen was recomposed around the actual job rather than a stack of equally weighted settings cards. One dominant protection surface answers whether blocking is working now, the selected headset is the only other primary task, and Advanced/About stay visually quiet until opened. Recovery text changes with the real failure state (reconnect Shizuku, request permission, or retry protection) instead of showing one generic action.

The UI now uses purpose-built light/dark palettes, state-aware switch tints, rounded ripple controls, compact identity branding, subtle status surfaces, responsive tablet gutters, Android 15+ system-bar/cutout insets, and larger touch targets. Accessibility and App-info actions stack on compact widths so long labels do not get squeezed into two columns.

## GitHub Actions hardening

The workflow pins the Android/Java/Gradle setup actions and toolchain, retries SDK package installation, uses one deterministic Gradle invocation to compile both debug and release Java/resource variants, run unit tests plus debug/release lint, and assemble both APK variants, and verifies APK existence, 16 KiB-aware zip alignment, signatures, and SHA-256 checksums. Build reports are uploaded on failure so the next CI error has actionable artifacts instead of only a collapsed console trace.

## Efficiency design

The privileged input monitor runs raw `getevent -q` on one selected input node, avoiding label/timestamp formatting work. While no button is pressed, the process is blocked in the kernel rather than polling. The media-key listener, `/dev/input` observer, and volume callbacks are event-driven. There is no wake lock and the activity has no periodic refresh loop.

Repeated faulty headset-volume events are coalesced into one bounded three-step restore cycle, so a noisy inline remote cannot grow an unbounded Handler queue. Detailed event logging is disabled by default. When enabled from Advanced settings, Accessibility events are kept only in an in-memory ring buffer for the current process session.

## Build with GitHub Actions

1. Create a repository and upload the contents of this folder, including `.github`.
2. Open **Actions** → **Build APKs** → **Run workflow**.
3. Download the `ButtonSilencer-apks` artifact.

The artifact contains:

- `ButtonSilencer-release.apk` — minified and resource-shrunk.
- `ButtonSilencer-debug.apk` — diagnostic build with a separate `.debug` application ID.
- `SHA256SUMS.txt`.

Both APKs are signed with the included deterministic **test key** so later workflow builds can install over earlier builds without uninstalling. Replace the signing configuration before public distribution.

## First setup

1. Install the release APK.
2. Start Shizuku.
3. Open Button Silencer and enable **Headset button protection**.
4. Grant the Shizuku permission.
5. Connect the USB DAC/IEM/headset.
6. Tap **Scan headset** and choose the external headset entry.
7. Lock the screen and test play/pause plus headset volume controls.
8. Confirm the phone's own side-volume buttons still work.

After a phone reboot, start Shizuku again. Button Silencer will reconnect when Shizuku delivers its binder.

## Advanced settings

Advanced settings allow the two privileged routes to be controlled separately, retain the optional Accessibility fallback rules, and expose manual diagnostics. The app deliberately refuses scan results that look like built-in `gpio-keys`, PMIC, keypad, power-key, or side-key devices.

## Safety model

The headset volume guard reads one `/dev/input/event*` node and restores `STREAM_MUSIC` after a volume press from that node. It does not use `EVIOCGRAB`, modify key-layout files, disable an input device, issue USB control transfers, or claim the USB audio interface. The phone-button device is not monitored.

A brief Android volume-overlay flash may still occur because the volume guard neutralizes the change immediately after the kernel event rather than intercepting the system's volume path before it happens.

## Toolchain

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- JDK 17
- compile/target SDK 36
- minimum SDK 26
- Shizuku API/provider 13.1.5

## License

See [LICENSE](LICENSE).
