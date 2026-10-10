# Detail topology-header acceptance

Last updated: 2026-10-11 (Europe/Paris).

## Scope and artifact

This partial [#35](https://github.com/novakin/vpn0wn-android/issues/35) repair separates the topology title and live status through the existing XML layout. An 8dp relative margin preserves complete wrapping text, RTL mirroring, fonts, palette and status binding. It changes no tunnel, MTU, protocol, backend, animation or persistence policy. Broader Add/peer, accessibility and support acceptance remains open.

Source checkpoint: `f1b570bbe2ba7b6b6879dd2e7b713f5323d9d202`, against merged master `cc9880283167c7fe5700d17ad60a2b6684260c0e`. The authorized debug APK's SHA-256 is `478ce448776619fce41c591e3d156ca03d394cada3468a57a55f85f399dea734`. Its signer matches the previously installed artifact; the installed APK hash matches the built file. Final documentation-only changes do not alter that executable source. The delivering PR owns final-head review, CI, publication and merge readbacks.

## Source and native controls

Removing the margin reproduced a rendered failure in `AddPeerPresentationTest`: `rtl-portrait-light: UP gap 0.0`. That completed negative control ran one test with one failure, zero errors and zero skips. With the repair, the focused Add/peer, Detail lifecycle and binding suites passed 36 tests with zero failures, errors or skips. Detekt passed its zero-issue gate; debug APK assembly, including the four configured Android native ABIs, succeeded in WSL Linux/OpenJDK 21.0.10/Gradle 9.8.1.

The new native Robolectric API 28 control inflates the real bound Detail layout, transitions UP to CONNECTING and requires complete text, no ellipsis or clipping, correct direction and at least 4dp between rendered lines sharing vertical space. Fourteen layout/theme configurations produce 28 header previews: 320dp portrait at 1×/2× text in LTR/Arabic RTL, 411dp RTL portrait at 2× text, and 480dp landscape at 2× text in LTR/RTL, each under both system themes. Four representative enlarged portrait/landscape previews were visually inspected; all configurations passed the layout assertions. This is synthetic framework/rendering proof.

## Rooted physical output

The maintainer authorized the available rooted Pixel 7 Pro and APK builds/tests while sleeping. The phone ran Android 14 with physical 1080×2340 output and 420 dpi. A fresh backup and preservation snapshot preceded installation. Its unchanged saved profiles and existing active kernel interface supplied presentation state; no tunnel was toggled or edited.

The previous installed build visibly placed `UP` against `FIG.01` at 2× Arabic RTL text. The repaired build shows separate complete labels. Seven captured header configurations were inspected:

| Configuration | Result |
| --- | --- |
| English portrait, 1×, system day/night | Complete title and UP status remain separated; fixed app palette remains. |
| Arabic RTL portrait, 2×, system day/night | The original touching-label case is corrected; complete title and status remain readable. |
| English/Arabic RTL landscape, 2×, system night | Header remains readable and separated in rotated physical output. |
| Arabic RTL portrait, 2×, temporary 840×1680 override | Complete title wraps onto two lines with separate UP text. This is an approximately 320dp software viewport on the same phone, not another hardware model. |

Applied font scale, app locale, rotation, night-mode setting and window size were read back. These captures establish the header only: lower-screen scrolling, transient system-bar appearance, notification overlays, full Detail fidelity and spoken TalkBack are outside this result. The physical cases use UP; synthetic native controls separately cover CONNECTING. Canvas typography and broader [#52](https://github.com/novakin/vpn0wn-android/issues/52) acceptance remain unchanged. No VPN-health, DPI, speed or battery benefit is claimed.

## Preservation and limits

Original app locale, font scale, rotation settings, night-mode setting, physical size/density and interface inventory match the fresh snapshot. Both profile hashes and settings DataStore hashes remain unchanged. Existing system-tool paths, WireGuard/Magisk module inventory and app-UID Magisk policy fields match their originals. Authentication settings, accessibility services and device animations were not changed. The repaired debug APK remains installed under the maintainer's authorization; original signer-compatible APK and private app-data backup remain local for recovery.

Screenshots, APKs, backups, raw device reports and command logs remain ignored locally. No personal names, keys or endpoints are included in this record. This scoped repair does not close #35, #52 or #71. [QA controls](../qa-test-plan.md#robolectric-view-tests) and the [UI roadmap](../ui-redesign-roadmap.md#done) own current guidance; the earlier [peer-diagnostics record](2026-10-10-peer-diagnostics-acceptance.md) retains its original runtime scope.
