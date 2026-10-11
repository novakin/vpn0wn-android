# API 36 Settings output acceptance

Last updated: 2026-10-11 (Europe/Paris).

## Scope and artifact

This partial [#34](https://github.com/novakin/vpn0wn-android/issues/34) / [#52](https://github.com/novakin/vpn0wn-android/issues/52) record covers existing Settings output, scrolling and header Back in a fresh isolated AVD configured with the Android 16/API 36 Google APIs x86_64 image. The retained launch/configuration evidence owns that environment designation; the matrix did not collect a separate runtime API/model property readback. It supplements the [rooted Settings record](2026-10-11-rooted-settings-acceptance.md); emulator output does not establish the remaining physical configurations or privileged behavior.

The run reused the reviewed debug APK from source `94f27da9e72851e9317ac943bdcbdac6975a48f5`, version 519, package `com.vpn0wn.android.debug`, SHA-256 `04f01101097bcca6ced072cbe577a879b94b55dc33a2d4a048803697f733995d`. The local artifact and installed remote `sha256sum` matched. [PR #146](https://github.com/novakin/vpn0wn-android/pull/146) merged that source at `38e49c05411bed0f96f41aaac48e7b74beb1df6e`; the two Git trees are identical. No new APK assembly or physical installation occurred. The ongoing goal's standing APK/emulator authorization covers this isolated run; the delivering documentation PR owns final-head review and checks.

## Accepted native output

Each case opened Settings through Home's actual Settings action. The harness admitted captures only after the resumed activity reported the requested primary locale, font scale and orientation. It recorded configuration and rotation before/after hierarchy capture, scrolled until the visible hierarchy settled, then used the actual header Back and confirmed resumed Home.

| Actual locale, size and text scale | System-mode requests | Cases |
| --- | --- | --- |
| English portrait, 411×914dp, 1× | Day and night | 2 |
| English landscape, 640×320dp, 1× | Day and night | 2 |
| English landscape, 640×320dp, 2× | Day and night | 2 |
| Arabic RTL portrait, 320×640dp, 2× | Day and night | 2 |
| Arabic RTL landscape, 640×320dp, 2× | Night | 1 |

All nine cases completed, producing 47 native PNG/configuration/hierarchy frames. Inspected top and bottom output showed readable headers, reachable Back and bottom About/credits content. Enlarged landscape rows use the scroll viewport; some row text was not fully exposed in the retained scroll sweep. This does not establish exhaustive row readability or claim every row fits on screen at once. Arabic primary locale and RTL layout were verified, while existing English fallback strings remain visible. No translation-completeness or spoken accessibility acceptance is claimed.

The app retained its fixed dark palette. Day/night labels describe requested system modes; separate system-mode readback was not retained for every frame. These captures do not establish a distinct light theme. This run changed no preference switch and exercised no export, authentication, tile, administrator or root-tools action.

## Failed attempts and preservation

Earlier attempts are retained and excluded from this matrix: absent-package `pm path` failure, truncated large binary reads despite exit zero, explicit `adb pull` failures, an overly strict locale admission check, and rotation requested before configuration had settled. Two portrait cases from the earlier native-configuration attempt retain their original scope but are not counted again here. Neither ADB host selection nor these failures establishes a transport cause or the rooted phone's earlier rotation-reset cause.

The accepted harness settled startup configuration before requesting native rotation. Its final restoration compared all 13 recorded baseline fields: font scale, accelerometer rotation, user rotation, screen timeout, three animation settings, size, density, rotation mode, system night mode and absence of both current and legacy debug packages. All matched. The owned hierarchy output was removed; app locale was cleared and the newly installed package uninstalled. This checks recorded state in a disposable AVD, not an exhaustive device-state snapshot.

The owned emulator was stopped gracefully. Launcher/backend processes, its serial and active listeners were absent afterwards; the AVD configuration hash remained unchanged. The shared ADB process and retained migration emulator remained present. No physical phone, personal tunnel, server, existing migration intent or unrelated artifact was modified. Raw captures, command journals, failed-attempt logs and restoration/shutdown receipts remain under ignored local artifacts.

## Remaining acceptance and documentation impact

#34/#52 remain open. Physical enlarged/RTL output, cutout/gesture behavior, spoken TalkBack, other switches, applicable administrator/kernel conditions, privileged actions, actual boot restoration and the separate tile/authentication/export/provider gates retain their acceptance boundaries. The [QA runbook](../qa-test-plan.md#remaining-g4-device-acceptance) and [UI roadmap](../ui-redesign-roadmap.md#pending) link this scoped proof.

Only this historical record and its QA/roadmap references change. AGENTS, README, development, backend contracts/changelog, operations, architecture, improvement plan and templates need no edit: source behavior, setup, ownership, policy and issue delivery state are unchanged. Earlier event dates and proof limits remain intact.
