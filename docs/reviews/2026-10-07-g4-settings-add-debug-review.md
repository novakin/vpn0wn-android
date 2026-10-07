# G4 Settings/Add inspection and debug-warning repair

Last updated: 2026-10-07 (Europe/Paris).

## Scope and revisions

This scoped review continues [G4/#52](https://github.com/novakin/wireguard-android/issues/52) after the [APK/preflight investigation](2026-10-07-g4-preflight-investigation.md). The maintainer authorized device testing and confirmed the phone was ready. It does not establish complete Settings [#34](https://github.com/novakin/wireguard-android/issues/34), Add/peer [#35](https://github.com/novakin/wireguard-android/issues/35), Home #36 or Downloads #28 acceptance.

The inspected installation is the existing CI-derived, locally signed debug APK from source `39bb2c3c`, version code 519, on Pixel 10a, Android 16/API 36. Its pulled SHA256 is `35442b340247f5a7b3dba1da31b7318b1c61d606acb3ec18fe2e811c3be6a0ea`. This slice did not build or install another APK, toggle the tunnel, change profiles, export or delete data. The warning repair below is based on refreshed master `0f4c5c89b5c52dda98aa86eca8fbe9786cfde6bd`, branch `fix/g4-debug-warnings`; it was not present in the inspected installation. Final source acceptance and integration belong in the PR.

## Physical output inspected

The phone reports a 1080×2424 physical display at density 420. Screenshots and available native hierarchy captures remain private under ignored `.artifacts/g4-device/acceptance-20261007/`.

| Surface | Actual inspected configurations | Bounded result |
| --- | --- | --- |
| Settings | Dark portrait, dark landscape, system light-mode portrait, dark portrait at 200% font scale, dark portrait with Arabic per-app locale/RTL. | Header/Back, section and row text remain usable in captured output; short and enlarged layouts scroll. Back measures 126×126 px, equivalent to 48×48 dp at reported density. |
| Add sheet | The same five separate configurations. | QR/file/manual order and labels remain visible/reachable. Rows expose native Button roles with combined title/subtitle labels and approximately 74 dp height. Rotation/locale recreation dismisses the sheet; it was reopened for the relevant captures. |

Both system night settings retain the app's existing dark palette. This is a light-mode exercise, not delivery of a distinct light theme. The cases are separate scenarios, not every combination of orientation, theme, font and locale. Captured native roles/labels do not establish actual TalkBack announcement or focus behavior.

Some attempted Home/Detail captures lacked hierarchy output because continuous animation prevented `uiautomator` from reaching idle. Its dump command can report exit zero without creating XML. Screenshots identify the current surface; a filename or MainActivity focus alone cannot prove Home versus Detail or that a requested action completed. Attempts landing on another surface are excluded from the matrix. Picker cancellation, blank-editor validation, QR navigation, peer copying/live counters and actual TalkBack use were not completed. Root/kernel, real tile/export callbacks and broader device coverage remain pending.

## Data and settings preservation

Validated before/after app-data archives contain the same three files, byte for byte: zero added, removed or changed files. Six checked settings match their initial values after restoration: font scale 1.0, auto rotation 1, user rotation 0, system night mode yes, empty per-app locales and stay-awake value 15. Owned remote capture files were removed. APK, backups, screenshots, hierarchy dumps and raw logs stay local; do not publish profile or device metadata from them. The phone is no longer required for this source slice.

## Debug findings and source repair

The maintainer-supplied debug capture contains a preflight send, handshake initiation 51 ms later, and handshake response a further 53 ms later, followed by three incoming keepalives. That capture contains no fatal crash or handshake timeout. Missing-private-key feedback came from an empty new profile and is expected validation before persistence. A completed handshake does not establish preflight benefit or explain the previous reported block.

[Issue #55](https://github.com/novakin/wireguard-android/issues/55) owns two confirmed warnings:

- `UntaggedSocketViolation`: `GoBackend.sendUdpPreflight` created its UDP socket without a thread TrafficStats tag. The socket owner now saves the caller's tag, sets tag 0 before construction and restores the previous tag in `finally`, including exceptional exits. Protection, configured-port policy, bind/payload, retry, interruption and service identity remain unchanged.
- `DiskReadViolation`: a fresh physical Settings opening reproduced `File.exists()` in `WgQuickBackend.hasKernelSupport`, called by `SettingsFragment.onCreatePreferences` on Main. That probe now runs on IO inside the existing lifecycle coroutine. Preference removal and the pending Connection section retain their existing owners and eligibility/root handling.

StrictMode detection remains enabled. Separate resource-ID-zero errors reproduce around Settings opening, but the responsible caller/root cause is not established. They remain investigation work under #34/#52; this repair does not claim to fix them or alter DPI, speed or battery behavior.

## Regression evidence and limits

With the new controls and old production code, the focused run executed 22 tests with three expected failures and zero test errors: Settings observed its kernel probe on Main; failed protection observed caller tag 42; successful loopback preflight observed untagged value -1. This establishes the regressions before the fix. The failed baseline also emitted Gradle cache I/O diagnostics; the next run stopped before tasks on a daemon-held file-hash lock. A normal daemon stop resolved that infrastructure blockage.

Fixed source passed 13 `SettingsActivityTest` and nine `GoBackendLifecycleTest` controls, with zero failures/errors/skips, plus zero Detekt findings. Environment: Windows/WSL2 Debian, OpenJDK 21.0.10, Gradle 9.3.1, SDK 36 and Robolectric API 28/33. Retained success log: `20261007-093037-365-18d9dabacf6e4c5faabe9b45ffbf205c.log` under ignored command logs. Tests observe tag 0 at the protection callback, restore tagged/untagged caller state, receive one zero byte over loopback, and observe the Settings probe off Main while existing preference controls pass. They do not establish Android kernel socket accounting, native VPN traffic or disappearance of these warnings on a candidate APK.

Independent ultra review, final tested/reviewed head, applicable CI and publication/merge state belong in the PR acceptance record. G4 retains physical confirmation of #55's candidate, remaining #28/#34/#35/#36 acceptance, resource-error diagnosis, repeated speed/energy baselines, numeric targets and #47. No optimization or complete G4 delivery is claimed.
