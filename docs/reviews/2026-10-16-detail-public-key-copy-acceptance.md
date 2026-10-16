# Detail full-public-key copy acceptance

Last updated: 2026-10-16 (Europe/Paris).

## Scope and provenance

Related to [#180](https://github.com/novakin/vpn0wn-android/issues/180) and [PR #183](https://github.com/novakin/vpn0wn-android/pull/183). This accepts the bounded interface/peer complete-key copy repair, not complete Detail, spoken accessibility or release acceptance. [QA](../qa-test-plan.md#detail-public-key-copy-controls--180) owns the repeatable controls.

Both isolated APKs used clean producer `9613015b852256b35e308a73a9b5c13be0b1cea0`, tree `80a3e7c7d2debb819503a4ce6fcc6442695f395a`. An ignored debug variant retained production Application/MainActivity/Detail and added self-target instrumentation plus a private plain EditText Activity under `local.vpn0wn.keycopyprobe.debug`. It did not change product source, activate a VPN or inspect personal configurations.

| Native run | Exact artifact and runner |
| --- | --- |
| API 28 | APK SHA256 `f38900f6319570054630b1838a645f6e112dd16747c4b5a221b39a913fc41aa0`; runner SHA256 `1e6c634dd019ed1eb7dc3982cd51ca277352e69e3bf2a02503b71b741376ddff`. |
| API 36 | APK SHA256 `cba870ecea1a6f9d82c4a9a33ee838f9b3a4849f13a10000882c9ad79f2825fc`; runner SHA256 `5e534b8b1bc866404451aa61e8583200db42ed380032dc2706569d0ed2785e2c`. |

The API-36-only runner correction waits within the existing eight-second bound for the observed SystemUI clipboard-preview window to disappear naturally before another targeted app tap. It adds no dismissal, injection bypass, denied-event retry or clipboard read. The API-28 path and all six copy paths/seventeen controls remain unchanged. API-28 evidence retains its original artifact scope rather than being relabeled as a run of the later APK.

Each artifact passed signature, packaged/merged manifest, isolated component/authority, DEX-definition, compiled-resource, four-ABI native identity and frozen-input checks. The later APK's copy adapter/helper and both generated Detail binding DEX dumps exactly match the earlier manually inspected dumps: current complete `Key.toBase64()` feeds copy; `shortKey` feeds display only. Build and static admission do not alone establish runtime acceptance.

## Executed native controls and output

Fresh owned headless Google APIs x86_64 guests ran API 28 and API 36. Admission bound console AVD/content, process/executable hash, no loaded Qt modules, only the shared ADB external loopback client, absent isolated package and same guest boot. Neither host nor personal clipboard was read, backed up or cleared. The runner admitted only an absent guest clip or the exact empty platform bootstrap shape before copying synthetic keys; no populated baseline was cleared to make a run eligible.

Both runs returned `INSTRUMENTATION_CODE: -1`, the expected isolated package/API/UID, all seventeen controls true and six original PNGs. Six actual row taps followed by Android EditText's native context-menu paste implementation returned exact independent fixture keys: A interface/peer, rebound B interface/peer and recreated-view B interface/peer. Compact display remained different from full payload. Controls also covered localized metadata, native 48dp parent bounds/value containment, retired-view detachment, unavailable-file/null-config preservation and a DOWN fixture with no endpoint or ListenPort. B is an in-memory model injection, not editor-persistence proof. Native paste implementation was invoked; long-press menu gestures were not tested.

| Feedback scope | Original-image finding |
| --- | --- |
| API 28 | A interface, A peer, B interface and recreated B interface clearly show the app's existing localized Snackbar. B peer and recreated B peer captures are too faint for legibility acceptance. Their exact pastes and automated controls passed. Representative existing-Snackbar behavior is accepted with this capture limit; no all-six-legible claim follows. |
| API 36 | All six originals clearly show Android's native clipboard preview with the corresponding synthetic full key. SystemUI package/view-ID observations supplement the pixels. No duplicate app Snackbar is introduced. |

Root inspected all twelve originals. Independent ultra analysis accepted API-28 representative proof with its two faint captures and separately inspected all six API-36 originals. These default English portrait guest runs do not establish the full locale/theme/size matrix, rendered keyboard focus/press states or TalkBack speech. Existing RX/TX displayed-value copying remains covered by focused source controls, not native traffic measurement.

## Failures retained and cleanup

The earlier `20261016-0635-copyrows` API-28 attempt passed six pastes but failed null-state acceptance and lacked visible feedback in inspected A frames. It remains failed. The repaired API-28 attempt above then passed all controls with the stated feedback-capture limits.

An initial API-36 attempt with the earlier APK passed its first interface paste/preview, then failed the next UID-targeted pointer injection while the SystemUI preview visibly covered the peer area. The exception class was `IllegalArgumentException`; its exact message/target was not retained, so overlay causation remains a supported explanation rather than a proven exact exception mechanism. The reviewed bounded wait and fresh later run resolved the automation failure without changing product behavior. Original failed attempts and exact frozen inputs remain preserved.

Every installed probe was uninstalled on its original boot; package registration/path absence and post-run host/guest identity were checked. Owned guests were then stopped and serial/process absence verified; disks and evidence remain. The later API-36 immediate serial-removal check briefly raced ADB transport removal; a subsequent readback confirmed absence. No shared ADB server, personal phone, app, clipboard or unrelated emulator was reset.

Private retained evidence roots are `.artifacts/continuation-20261015/key-copy-native/runs/20261016-0715-drawn-feedback` and `20261016-api36-overlay-wait`. They contain frozen harness/source identities, build/static audits, runner results, originals, admission, same-boot uninstall and owned-termination receipts. Raw evidence stays ignored and out of source distribution.

## Source and delivery boundaries

Forty focused source controls passed across `BindingAdaptersTest`, `AddPeerPresentationTest`, `DetailPublicKeyCopyTest` and `TunnelDetailLifecycleTest`, with zero Detekt findings. Current source-head CI [run 38853899521](https://github.com/novakin/vpn0wn-android/actions/runs/38853899521) passed on `9613015b852256b35e308a73a9b5c13be0b1cea0`. That source delta also received [published commit-anchored independent ultra review](https://github.com/novakin/vpn0wn-android/pull/183#pullrequestreview-5543285508). Later documentation-head checks, review, issue relationship and merge state belong to PR #183's current acceptance record; this historical record does not infer their result.

No physical-device copy, personal-data migration, complete accessibility/Detail acceptance, live VPN behavior or distributed release is claimed. Those broader limits do not invalidate the scoped synthetic copy/paste and representative feedback acceptance recorded here.
