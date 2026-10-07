# G4 candidate APK and device checks

Last updated: 2026-10-07 (Europe/Paris).

## Scope and build

[G4/#52](https://github.com/novakin/wireguard-android/issues/52), continuing the maintainer's explicit build/device-test authorization. Executed source: merged master `4e2f473d78e2a19c7d2d6c0daecdab6cca25e85f`, including #55's repairs. Device: Pixel 10a, Android 16/API 36, userspace backend, USB charging and LTE. No speed/energy baseline, server operation, profile-port change or app source edit occurred.

[CI run 37598996218](https://github.com/novakin/wireguard-android/actions/runs/37598996218) passed 245 unit tests with zero failures/errors/skips and zero Detekt findings, then assembled/uploaded the explicitly requested debug APK. Downloaded artifact `11471513353` expires `2026-10-14T09:15:42Z`. Its revision file matches the executed source; original APK SHA256 is `59f94669ef77c15e9de5f36cad7de7675e88b8686436656927ba5a8e643d0d15`.

The untouched CI artifact remains local. A separate test copy was signed with the existing WSL debug key after matching its certificate to the installed app. Signature verification and comparison of all 1,018 non-`META-INF` ZIP entries passed. `adb install -r` succeeded without uninstall/data clear; pulling the installed APK back confirmed test-copy SHA256 `27c7e4dabe4d0451e171728dfda87140847fa59b288d09678156162689495063`. This is a locally signed CI-derived debug build, not a release or the untouched runner-signed artifact.

## Executed checks

| Surface | Observation | Boundary |
| --- | --- | --- |
| #55 startup/preflight | One preflight send and a subsequent handshake response were observed after the update/relaunch. Scoped candidate captures contained no `UntaggedSocketViolation` or fatal exception. | Local send/handshake does not prove DPI protection, sustained health or performance. No manual tunnel toggle was needed. |
| #55 Settings | Fresh candidate Settings opening had no `DiskReadViolation`; Back returned to the list. | Nine resource-ID-zero messages remained around opening. Their caller/root cause is unresolved under #34/#52. |
| Add/manual | Manual creation opened the editor. Blank Save showed `Missing attribute in Interface's PrivateKey`, retained the editor and persisted no profile; Back cancelled it. | Validation feedback was inspected above the gesture bar. Name-validation behavior after supplying a key was not tested. |
| Add/file | Native picker opened and Back cancelled without import. A Downloads fixture with an overlength name was rejected with app-styled invalid-name feedback; the same endpoint-free synthetic config under a valid name imported successfully. | One native provider and one valid config were exercised. ZIP, image QR, denied access, mixed imports and export ownership are separate checks. |
| Add/QR | Scan action opened the native camera scanner; Back returned to the list without import. | No QR payload, permission denial or camera-absent device was tested. |
| Home selection | Synthetic-only selection, Select all, deselection and zero-selected presentation were inspected. Add/switches were hidden in selection. Active-profile warning was shown for Select all; cancellation retained both profiles. | The personal profile was never confirmed for deletion. Partial failures and cancellation during admitted deletion retain their existing source-test proof only. |
| Home deletion | After deselecting the personal profile, the one-profile confirmation listed only the synthetic target. Confirm removed that profile and restored the normal list. | No personal profile deletion or synthetic VPN activation occurred. |
| Home configurations | Selected identity/count survived recreation. Output was inspected separately in portrait, landscape, system light-mode, 200% font scale and Arabic RTL. Header/actions remained visible; large-text values wrapped and secondary text ellipsized. | The existing app retained its dark palette in both system modes. These cases are not every combination, a distinct light theme or TalkBack proof. |

Screenshots and hierarchy dumps were checked for the actual surface. Animations sometimes prevented hierarchy generation despite command success; a transient system notification produced one mismatched dump. Those dumps were excluded from action assertions. Screenshots and fresh matching captures supplied the relevant evidence; file names or MainActivity focus alone were not treated as acceptance.

## Preservation and handoff

The original installed APK and fresh private app-data archive were retained before installation. Immediately after replacement, all three archived app files were byte-for-byte unchanged. After app startup and synthetic tests, the personal config and preference files remained byte-for-byte unchanged, with no added or removed app files. Only Android's `profileInstalled` runtime marker changed. An initial strict equality check correctly detected that marker change; the classified comparison confirmed the config/preference preservation boundary rather than restoring runtime metadata.

Eight checked device values matched their initial state: font scale, auto rotation, user rotation, stay-awake, system night mode, app locales, enabled accessibility services and accessibility-enabled flag. Temporary settings were restored. Owned Downloads fixtures and remote capture files were removed. No clipboard operation, personal-config export or accessibility-service change occurred. APKs, keys, archives, raw logs and screenshots remain local under ignored artifacts. The candidate stays installed; the final inspected surface is Tunnl's normal list with the original active profile. The phone is no longer needed for this slice.

#55's candidate paths now have bounded physical warning confirmation. #28/#34/#35/#36/#47/#52 remain open: Downloads output/failure boundaries, remaining Settings/native actions, peer copy/live-counter checks, TalkBack and wider affected configurations, resource-error diagnosis and controlled performance work are incomplete. LTE and USB charging were observed, so no Wi-Fi or idle-energy baseline was attempted. Re-establish the authorized fixed-profile path before any later load trial; the prior blocking report still applies.

This record adds device evidence without changing source behavior, architecture, dependencies, agent instructions or process. Historical captures keep their original scope. Independent ultra review and final documentation checks/publication belong in the current PR acceptance record; merging this evidence does not complete G4 or close its partial-delivery issues.

## Subsequent source investigation — 2026-10-07 (Europe/Paris)

The [Settings resource-ID investigation](2026-10-07-settings-resource-id-review.md) subsequently reproduced an invalid AndroidX preference root ID during API 36 framework structure capture and repaired initialization through the native preference API. This does not revise the nine messages or missing caller stack observed above. The installed source `4e2f473d` predates that repair; physical warning confirmation and remaining #34/#52 acceptance still need a new authorized candidate check.

The later [candidate phone confirmation](2026-10-07-settings-resource-id-review.md#candidate-phone-confirmation--2026-10-07-europeparis) records replacement with verified source `5fa07cd0` and zero ID-zero messages in three Settings opens and landscape recreation. That supersedes the warning-confirmation handoff, while this record's original APK, captures and broader acceptance limits remain unchanged.
