# Detail header acceptance — #193

Last updated: 2026-10-17 (Europe/Paris).

## Scope and source

[#193](https://github.com/novakin/vpn0wn-android/issues/193) owns separate 48dp Back/Edit targets, accurate localized names, native feedback and preserved Detail navigation. The bound XML keeps the existing 24dp Back and 18dp Edit artwork viewports, fonts, palette and single-line name ellipsis. `MainActivity` hides the retained Home Fragment while phone Detail is shown; popping the transaction restores it. Tablet Home remains available under the existing two-pane policy.

The installed producer was `3872e8e09f2829a1699a35de9451a6416832210a`, tree `ca1e363ea137c1e672f6f62a9f1c60a9e206d995`. A private Gradle overlay added one platform instrumentation to the production Application/MainActivity/Fragment/resource path and changed only the installed/native package identity to `local.vpn0wn.detailheaderprobe.debug`. The ARM64 debug APK SHA-256 was `5970d97384e083d881ee4043765fadb004f38ac8aaf0696eae8797e3a8d83519`. Its actual manifest target, DEX instrumentation definition and retained-Home assertion, sole ARM64 ABI, signature and four frozen input hashes were checked before installation. This was an isolated acceptance artifact, not a shipping APK installation or release.

## Source checks

The original header checkpoint `7737cd5c` passed 39 tests across the five suites recorded in [QA](../qa-test-plan.md#detail-header-action-controls--193), with zero failures/errors/skips and zero Detekt findings. Its fifty native-graphics originals retain that earlier source scope.

Installed keyboard investigation then exposed retained Home focus targets behind phone Detail. The repair used the existing Fragment transaction's `hide` operation. The corrected window-level regression and neighboring `HomeSelectionTest`, `TunnelDetailLifecycleTest` and `VpsNavigationTest` passed **38 tests**, zero failures/errors/skips, with zero Detekt findings. Those source-equivalent results cover final behavior at `3872e8e0`; the header XML and its renderer inputs are unchanged from `7737cd5c`. The initial direct-Home-subtree focus assertion was unsuitable and was replaced by actual Activity-window traversal. Its failed assertion is not evidence of the corrected keyboard boundary.

## Installed Pixel 7 Pro checks

On API 34, a fresh isolated installation used a synthetic DOWN tunnel with no peers or ListenPort. No VPN was activated and no personal profile, clipboard, authentication or server operation was involved. The runner used Android's per-app LocaleManager only for this disposable package. It selected the app night bucket through AppCompat and recorded actual Activity locale, layout direction, scale, night bucket and safe-area dimensions, rather than accepting requested settings as proof.

| Cases | Actual Activity viewport | Locale and text scale | Result |
| --- | --- | --- | --- |
| Stock portrait, both day/night resource modes | 411×802dp | English, 1× | Pass |
| Stock landscape, both day/night resource modes | 802×383dp | French, 1× | Pass |
| Compact portrait, both day/night resource modes | 320×562dp | English, 1× | Pass |
| Compact landscape, both day/night resource modes | 562×292dp | French, 1× | Pass |
| Compact RTL portrait, both day/night resource modes | 320×562dp | Arabic, 2× | Pass |
| Compact RTL landscape, both day/night resource modes | 562×292dp | Arabic, 2× | Pass |

All twelve cases required fully visible, non-overlapping 48×48dp actions, accurate localized descriptions, native Tab reachability and retained Home hidden on phones. Actual pointer events two dp inside each target's trailing-bottom padding exercised space outside its artwork, opened Edit without saving, returned to Detail and navigated Back to Home. The tunnel remained DOWN and the backend's running-tunnel set stayed empty. Seventy-two original screenshots were retained privately. Author inspection covered all twelve final default frames and representative focus/press originals; focused and pressed feedback is visible without moving the header. The existing dark app palette remains consistent under both day/night resource modes.

UI/UX Pro Max, Interface Design, Design System and Frontend Design informed the bounded review against the read-only approved Detail reference and existing Android tokens. Author verdict: **satisfied for #193's header target, feedback and navigation scope**. At compact 320dp/2× text, the long synthetic tunnel name retains existing end ellipsis; this does not establish full long-name visibility. Landscape displays that name fully. No new name-layout policy is claimed.

## Failed attempts and restoration

Earlier API 24 attempts retain their original evidence. Initial capture included a Fragment fade, and an oversized override caused a SurfaceFlinger crop failure. Corrected attempts at the original source exposed genuine RTL keyboard unreachability and motivated the retained-Home repair. At repaired source, the emulator completed portrait day/night and landscape day before guest transport stalled; the incomplete matrix is not a full pass or an attributed product defect. The owned read-only emulator was stopped and its guest overlay discarded. Its inaccessible guest settings could not be read back. Base AVD, shared ADB server and unrelated host artifacts were preserved.

The first physical attempt stopped because AppCompat locale selection before an active delegate did not apply English on API 34; the Activity reported French. A corrected attempt used platform per-app locale selection. Subsequent attempts stopped at host viewport/orientation guards after successful native interactions: physical safe areas were smaller than the original guard allowed, and rotation needed to be locked before each instrumentation process. Final `phone-r4` used explicit per-case WindowManager rotation and actual safe-area readbacks; all twelve cases passed. These host/harness failures remain separate from product acceptance.

Final physical cleanup independently attempted and verified all five baseline fields: size, density, font scale, accelerometer rotation and user rotation. No synthetic config remained before uninstall. The owned package was uninstalled and package absence checked; its per-app locale disappeared with that installation. The original launcher/task resumed. Existing phone apps and personal configs were not modified. Private evidence, admission/cleanup receipts and failed logs remain under ignored `.artifacts/continuation-20261017/detail193-native`; the final successful quiet log is `20261017-101345-057-bafde7dbfa9a45b0b8918b819af0aa0a.log`.

## Limits and delivery

Native event injection and screenshots establish this installed header path, not a human touch-usability study, TalkBack speech, all OEMs/APIs, a release artifact, active VPN behavior or whole-app accessibility. Broader #35/G4 accessibility, provider, migration and VPN acceptance remain in their owning issues. The issue-linked PR owns final-head independent ultra review, CI, publication and merge state; this historical record does not substitute for those gates.
