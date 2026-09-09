# Button Silencer 3.1 — QA and device verification

## What was audited

The 3.1 pass focused on the two failure modes most likely to explain intermittent screen-off protection loss and the earlier heat concern:

1. the app-side Shizuku/UserService connection lifecycle;
2. the selected headset `/dev/input/event*` monitor lifecycle.

The visual hierarchy was also rebuilt around the actual operational task: see protection state first, see the selected headset second, and keep fallback/troubleshooting controls out of the main path.

## Reliability fixes included

- Sticky Shizuku binder listener for app-process reconnects.
- Direct death-recipient tracking for the privileged UserService binder.
- Rebind on UserService disconnect/binding death while Shizuku is still alive.
- Eight-second bind timeout so a stuck bind cannot leave `binding=true` forever.
- Bounded reconnect delays instead of permanent keep-alive polling.
- Stable Shizuku UserService tag; `versionCode` is used to replace stale daemon code on upgrades.
- `/dev/input` `FileObserver` to react to USB/input-node create/delete/move events.
- Selected headset is re-resolved by device name if its event number changes after reconnect.
- The `getevent` reader now treats an unexpected process/pipe exit as recoverable and re-arms the guard.
- Input-device scans are time-bounded and drain process output concurrently, avoiding a stuck pipe or indefinite scan.
- More built-in key-device names are rejected (`s2mp`, `spmi`, `sec_key`, `volume-keys`, etc.) to reduce the chance of selecting the phone's side buttons.

## Efficiency fixes included

- No foreground service.
- No wake lock.
- No periodic activity refresh loop.
- No permanent reconnect watchdog.
- Raw `getevent -q` is used for the one selected input node; no timestamp or label formatting is requested for the live monitor.
- The monitor blocks waiting for kernel input instead of polling.
- Repeated faulty volume key-down bursts are coalesced into one bounded three-step restore cycle. A noisy/ghosting inline remote therefore cannot create an unbounded queue of delayed restore jobs.
- Detailed event logging remains off by default.

## Static validation completed in this workspace

- Project preflight script passed: XML/resource structure and required view IDs are present.
- All resource XML is well-formed.
- Every `MainActivity` view ID used by Java exists in the redesigned layout.
- Java source passed a parser-level `javac` syntax check. Full Android symbol resolution was not possible because this runtime does not contain an Android SDK.
- A standalone parser smoke test passed for external-headset ordering, encode/decode, and rejection of common internal phone-key device names.

## Physical-device regression matrix

Run these against the release APK before calling the build final:

| Case | Expected result |
| --- | --- |
| Screen on, selected IEM connected | Headset media and selected-headset volume controls are silenced. |
| Phone side volume buttons | Continue to change volume normally. |
| Lock screen for 15–30 minutes | Headset blocking remains active with no app UI open. |
| Unplug/replug the USB DAC/IEM | Guard automatically re-resolves the input node and returns to ACTIVE. |
| Replug causes `/dev/input/eventN` to change | Guard follows the same external device name rather than requiring a rescan. |
| Stop Shizuku | UI should report Shizuku offline; privileged blocking cannot continue while Shizuku itself is down. |
| Start Shizuku again | App/UserService should reconnect when the Shizuku binder is delivered; opening Button Silencer is the manual recovery fallback. |
| Kill/restart only the privileged UserService while Shizuku stays alive | Controller should detect the dead binder and perform bounded rebinds. |
| Faulty remote emits many volume events | Volume should stay at the baseline and the app should not accumulate restore callbacks. |
| 15–30 minutes of normal playback with no button events | There should be no sustained busy-loop CPU use from Button Silencer; check device temperature/battery statistics as a sanity test. |

## Build limitation of this workspace

This environment has Java but no Gradle executable, Android SDK, `android.jar`, `aapt2`, or `apksigner`, and binary toolchain downloads are unavailable here. The included GitHub Actions workflow performs manifest/AIDL/resource processing, unit tests, Android lint, debug/release assembly, APK signature verification, and SHA-256 packaging using the required Android toolchain.
