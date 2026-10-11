# Empty Home keyboard-focus acceptance

Last updated: 2026-10-11 (Europe/Paris).

## Scope and provenance

This record covers the local empty-Home focus repair related to [#80](https://github.com/novakin/vpn0wn-android/issues/80). Android's inherited default focus highlight dimmed the ScrollView's children. The container now reuses the existing transparent-interior cyan focus background. Palette, text/artwork alpha, geometry, focusability, scrolling, Add and header behavior are preserved.

| Artifact | Source revision | Debug APK SHA-256 |
| --- | --- | --- |
| Physical baseline | `15eb25239d876579ab60609efcc35413dde589f3` | `3e0860ba843e2ab74f4a16791cf3d702441261572e4f4cacdccce684dbac2402` |
| Physical/AVD candidate | `d33da01587864e5518bdcb385338a1f408e378db` | `bc8e595ede320b469208a8bfa58c7d585af6895397ccde481056ef10973279ee` |

Both artifacts use `com.vpn0wn.android.debug` and the same verified signing certificate. Candidate source was clean and committed at assembly; its compiled Home layout and focused selector were checked. Between these source revisions, other production-source differences are 37 legal-comment additions only. APK inventory comparison found the Home XML and one DEX payload changed; byte comparison alone does not establish DEX semantics. Later acceptance-documentation edits do not change the tested source/resources.

Source checks ran at `d211b8af862022e947555ae71c818dca949bf173`: Home 20 plus binding 16 tests passed with zero failures/errors/skips, and Detekt reported zero findings. Candidate production/test blobs are identical to that tested revision. Robolectric native-graphics controls cover six API 34 configurations; API 24 separately covers inflation, focus and Add navigation. Removing the new background fails visible-focus/owner assertions. Robolectric does not reproduce the physical dimming overlay, so those controls are not physical contrast proof.

## Physical paired output

An authorized rooted Pixel 7 Pro, Android 14/API 34, produced baseline and candidate pairs at the unchanged original portrait/French configuration, 1080×2340 pixels. Each build pairs actual native `home_empty_scroll` focus with actual Add focus. The two pairs preserve all seven recorded Home bounds and instruction/header/Add strings, both within each build and across builds. Baseline needed two TAB keys; candidate needed one. No Add/Settings action or tunnel activation was issued.

All four settled full images were inspected at their original size. Baseline parent focus visibly darkens the instruction and artwork; Add focus restores their original paint. Candidate parent focus preserves the readable instruction/artwork and shows a cyan outline; Add has its own focus feedback. No root notification obscures the accepted frames. Pixel measurements are retained with the private run evidence; native focus/property checks alone do not establish this painted result.

Each installation started with candidate absence and checked exact installed APK/UID identity. Each cleanup proved candidate/helper absence, actual original Home return and equality of all 12 preservation fields, kernel/network state and display. Original app data, profiles, preferences, root tools/modules/policies and running kernel tunnel were preserved. Phone display, theme, font, locale, animations and accessibility settings were not changed.

## Actual Android output matrix

An isolated API 36 AVD captured seven candidate pairs at 420 dpi. Native resumed-Activity configuration and PNG dimensions bracketed each capture; requested settings alone were not accepted as output proof.

| Output | Text scale | Locale | System/app night |
| --- | --- | --- | --- |
| 840×1680 portrait | 100% | English | off and on |
| 1680×840 landscape | 100% | English | on |
| 1680×840 landscape | 200% | English | off and on |
| 840×1680 portrait | 200% | Arabic/RTL | off and on |

All 14 paired images and five initial/reachability frames were inspected at their original sizes. Every accepted pair has complete readable guidance, a visible cyan parent-focus outline and fully visible Add. All seven instruction regions and ScrollView interiors, excluding the 4-pixel focus edge, have zero differing pixels between parent and Add focus. Native geometry, strings and actual configuration remain equal within every pair.

Normal portrait pairs retain the complete unchanged header. Landscape and 200% RTL pairs have a collapsed header in both focus states; this run does not prove an expanded header in those cases. The normal landscape start and first two bounded drags lack complete guidance; the third reveals it before accepted pairing. Retained partial frames are not acceptance frames.

AVD cleanup restored initial display overrides, rotation, font scale and night settings and proved candidate/helper absence. The exact owned emulator was then stopped; its process and ports were absent and the launch command exited successfully. No existing AVD or phone configuration was altered by this matrix.

## Stopped setup attempts and limits

Before installation, the first phone guard rejected an original Detail screen. Its helper-only session was cleaned and preservation passed. A fresh session used one native Back; Home remained with a collapsed header, so the unchanged Home guard still rejected it. One guarded Home scroll revealed the header before the successful baseline/candidate sessions. These rejected captures are retained separately and are not contrast proof.

The AVD's first admission stopped before writes because Windows ADB's CRCRLF name receipt produced blank parsed lines. A narrow blank-line parser correction, one real-receipt regression and affected independent ultra review preceded admission. The final host controls passed 27 tests; no matrix attempt was restarted or extended.

This completes only the empty-Home focus repair's physical contrast and relevant actual-Android output gates. TalkBack speech, broader Home state/navigation/accessibility acceptance and [#80](https://github.com/novakin/vpn0wn-android/issues/80) remain open. No backend, network-health, DPI, transfer-speed, battery, migration or release/distribution claim follows. Private screenshots, hierarchy dumps and command logs remain outside source distribution. [Home QA](../qa-test-plan.md#home-reference-source-checks) owns the continuing acceptance requirements; the PR owns final-head independent review, CI and delivery state.
