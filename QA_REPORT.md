# Button Silencer 3.1.2 — QA and device verification

## What was audited

The 3.1.2 pass focused on the two failure modes most likely to explain intermittent screen-off protection loss and the earlier heat concern:

1. the app-side Shizuku/UserService connection lifecycle;
2. the selected headset `/dev/input/event*` monitor lifecycle.

The visual hierarchy was also rebuilt around the actual operational task: see protection state first, see the selected headset second, and keep fallback/troubleshooting controls out of the main path.

## Reliability fixes included

- Fixed the GitHub Actions Java compile failure where `reconnectRunnable` captured the final `context` field from an instance initializer before the constructor assigned it. The runnable is now initialized inside the constructor, and the preflight script guards against regressing to the broken pattern.
- Added AndroidX annotations to the compile-only classpath so Shizuku's `RestrictTo.Scope` metadata no longer produces the repeated missing-annotation javac warnings.
- Sticky Shizuku binder listener for app-process reconnects.
- Direct death-recipient tracking for the privileged UserService binder.
- Rebind on UserService disconnect/binding death while Shizuku is still alive.
- Eight-second bind timeout so a stuck bind cannot leave `binding=true` forever; timeout cleanup detaches the stale app-side UserService connection before a retry.
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

- Project preflight script passed: XML/resource structure, required view IDs, build invariants, workflow invariants, and the `reconnectRunnable` regression guard are all present.
- All resource XML is well-formed and referenced file/value resources resolve in the source tree.
- Every `MainActivity` view ID used by Java exists in the redesigned layout.
- The real `ShizukuController.java` passed a Java 17 compiler-flow check against minimal Android/Shizuku API stubs, directly exercising the constructor/callback definite-assignment path that failed in GitHub Actions. Full Android symbol resolution is still delegated to CI because this runtime does not contain the Android SDK/AGP toolchain.
- A standalone parser smoke test passed for external-headset ordering, encode/decode, and rejection of common internal phone-key device names.
- Every shell `run:` block extracted from the GitHub Actions workflow passed `bash -n`, and the bundled debug signing keystore passed `keytool -list`.
- The design-intelligence anti-slop detector reports zero generic-interface signals for the final layout/style pass.

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

This environment has Java but no Gradle executable, Android SDK, `android.jar`, `aapt2`, or `apksigner`, and binary toolchain downloads are unavailable here. The hardened GitHub Actions workflow therefore remains the authoritative end-to-end Android build: it compiles both Java/resource variants, runs unit tests and debug/release lint, assembles the minified release plus debug APK, verifies 16 KiB-aware alignment and APK signatures, writes SHA-256 hashes, and uploads Gradle/Android reports automatically on failure.

## 3.1.2 CI regression audit

The 3.1.1 Actions run failed before compilation because the app added `androidx.annotation:annotation:1.9.1` while Shizuku 13.1.5's dependency graph resolves `androidx.annotation` at 1.3.0. 3.1.2 aligns exactly with Shizuku's official demo/provider dependency (`1.3.0`) and the project preflight rejects any other direct annotation version.

Additional checks completed for this package:

- every production Java source compiled together under a Java 17 Android/Shizuku API stub harness;
- every unit-test source compiled against those production classes;
- all 10 existing policy/parser unit tests passed in the same harness;
- every resource XML parsed and every app resource reference was resolved by the project preflight;
- manifest application components map to real source classes;
- release R8 keep targets map to the real UserService/AIDL classes;
- format-string syntax was checked;
- the bundled signing keystore was opened and the expected alias was verified;
- workflow YAML parsed and every shell `run:` block passed `bash -n`;
- CI uses Gradle `--continue` and preserves `.ci/gradle.log` plus build reports so independent failures are visible in one run;
- design-intelligence static review reports 0 generic-interface signals for the main layout.

This environment still does not contain Google's Android SDK/AGP binaries, so AAPT2, Android Lint, R8, APK assembly, zip alignment, and APK signature verification remain the real GitHub Actions stage. The workflow is deliberately structured to exercise all of those in one run and retain diagnostics if any stage fails.
