# Selected Home native acceptance

Last updated: 2026-10-10 (Europe/Paris).

## Scope and provenance

Bounded native presentation evidence for [PR #98](https://github.com/novakin/wireguard-android/pull/98) and [#80](https://github.com/novakin/wireguard-android/issues/80), including the short-viewport repair found during installed validation. The selected [Home reference](../ui-redesign-roadmap.md#home-visual-handoff--2026-10-08) owns visual intent; runtime status remains the existing model contract.

The authorized isolated API 36 Google APIs x86_64 AVD used density 420, actual animation scales 1.0 and synthetic tunnel data. The phone, shared emulator and live VPS were not used. Retained ignored evidence is under `.artifacts/runtime-acceptance-20261010/`; artifacts are not source-distribution inputs.

The repaired debug APK was assembled from `ad6de555c094b4355c6298b2f5f6fa0cb17a8e55` plus the archived `apks/98-repaired/source.patch`. Built and installed APK SHA-256 both equal `025a5b45885520b96418f5e0bac76c055baad43a2611e0cb5280af8021830a50`. The archive records source scope, build log and focused results: 16 Home tests, nine BindingAdapters tests, zero failures/errors/skips and zero Detekt findings. PR acceptance owns subsequent integrated-head checks and publication; this source-plus-patch provenance is not a claim of a clean committed build.

## Finding and repair

The original fixed header/status could consume a short landscape viewport at 2× text, leaving the name and switch inaccessible. The repair uses the installed Material `AppBarLayout` scrolling pattern for the unchanged header/status and existing list, with an empty-state `NestedScrollView` above the fixed Add footer. Selection entry/exit expands the header; count and statistics updates retain scroll position. No custom scrolling behavior or additional state owner was added.

## Native results

- Six configurations passed: 320×640dp light/dark; 640×320dp dark at 1×/2× text; Arabic RTL 320×640dp at 2× text in light/dark. Expanded/scrolled/selection/returned captures preserve the actual configuration and bounds.
- Header scrolling exposes the full tunnel name and 48dp switch at 2× landscape. Native selection checks long-pressed the sole synthetic tunnel, tapped Select all twice and used the selection-close action; normal Logs/Settings/Add controls returned enabled afterward. Search remained disabled. Native zero-selected and multiple-selection states are not established by this matrix; count-update scroll preservation and deletion-confirmation cancellation are focused JVM controls.
- An active synthetic tunnel's IN/OUT, relative handshake age and cipher remain fully visible above Add after scrolling at 2× short landscape. The raster shows the card bottom around 566px, clear of Add at 625px. App-UID HTTP returned 200 and the synthetic peer token before rotation; this supports private authenticated reachability, not general protection or DPI.
- Native deletion of the sole synthetic profile reaches the empty state. An ordinary body swipe exposes the complete hint at `[153,486][1527,593]`, clear of Add at `[32,625][1648,751]`. The first direct header drag after deletion can retain the stock behavior's previous scrolling-child association; a body swipe releases space. This does not obstruct the normal empty-state gesture or Add action.
- The synthetic profile was restored byte-for-byte and the tunnel restored DOWN. Display size, font scale, locale, rotation and theme settings were restored.

## Evidence limits and remaining acceptance

The active raster was captured while UIAutomator's idle wait stalled; no active hierarchy result is claimed. Owned stalled commands were stopped before restoration. The empty-state helper's last assertion used the wrong view ID; the saved hierarchy and raster already show the actual `home_empty_placeholder` and complete hint. Earlier delete-sheet captures are excluded from empty-state proof. These failed helper attempts remain retained separately from valid postconditions.

Focused JVM gesture controls supplement the native matrix but do not reproduce every native gesture timing. Connecting, stale/error and multi-tunnel combinations, TalkBack speech, broader physical/OEM/system-bar coverage and the remaining [#36](https://github.com/novakin/wireguard-android/issues/36)/[G4 #52](https://github.com/novakin/wireguard-android/issues/52) acceptance are not completed by this record. No DPI, packet-leak, battery, transfer-speed, release or full-app fidelity claim is made.
