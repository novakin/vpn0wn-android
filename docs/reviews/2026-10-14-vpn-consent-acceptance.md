# Scoped native VPN consent acceptance — #177

Last updated: 2026-10-14 (Europe/Paris).

## Scope and provenance

This record adds rooted Pixel 7 Pro/API 34 consent and native activation evidence for [#177](https://github.com/novakin/vpn0wn-android/issues/177). [PR #178](https://github.com/novakin/vpn0wn-android/pull/178) owns current source review, CI and delivery state. Its source candidate is `ac0cbf2e76d3263c4eaa49d823440f82e2e27baa`, based on master `f04490e75e87e7abf5da803e23ffd4480ca6fa9a`. Earlier source controls establish lifecycle defects; this trial does not retrospectively establish the cause of the original phone failure.

The maintainer reserved the phone, unlocked with personal VPN off and other ADB tools idle. Standing APK/root-test authorization applied. The existing audited isolated full-app build uses application ID `local.vpn0wn.recoveryprobe.debug`; the isolation ID, debug label and corresponding native package argument differ from the production build. Production source was not edited for the trial. APK SHA256 is `1085aede0fac64b25224f32d8bd96f391a3dfd4c83c337dcc231be2a4f05ce4b`. Build/source audit and installed-byte checks were retained for both fresh installations.

One synthetic profile used an unreachable loopback peer and admitted only the isolated app UID and its Android SDK sandbox. It was seeded through the debug sandbox; no importer acceptance is claimed. Initial file transfer normalized CRLF to LF; reconciliation verified the same synthetic field lines before testing. Personal profiles, keys and remote endpoints were not used. Private command receipts, hierarchies, event logs, ownership observations and restoration evidence remain under ignored `.artifacts/vpn-consent-acceptance-20261014/`; they are not distribution assets.

## Executed controls

| Control | Observed result | Boundary |
| --- | --- | --- |
| Native consent cancellation | Enabled DOWN switch, inactive native VPN and no userspace TUN. | Actual Android denial/cancellation settlement. |
| Same-switch manual retry | One new request and positive native consent action; enabled UP switch and actual own-UID native VPN admission. | Stable positive control; no authenticated peer. |
| Fresh installation, light theme, 2× text, Arabic | Portrait consent request; landscape native approval; enabled UP switch and actual own-UID native VPN admission. | One request tap and one approval tap; the process was preserved. |

The successful rotated trial records owned PID `23694`. Rotation completed at `2026-10-14T08:20:15.245239Z`. Before the approval tap, retained lifecycle events show `MainActivity` destruction at `08:20:15.379Z` and creation at `08:20:15.441Z`; these events are absent from the before-rotation snapshot. The approval tap completed at `08:20:15.860320Z`, and the owned Activity received a result at `08:20:15.977Z`. These establish actual in-process Activity recreation before approval, followed by native activation. Rotation and process preservation alone were not used as recreation proof.

An earlier large-text trial aborted before consent. Its one tap completed at `08:05:23.859773Z`, overlapping an owned Activity destruction at `08:05:23.825Z`; input delivery and callback execution were not proved. It remains excluded from passing coverage. The later fresh-installation trial set the app locale before launch and reached a settled owner before requesting consent. No second blind UP tap converts the aborted trial into a pass.

## Restoration and preservation

Initial cleanup removed the owned installation and scratch paths, restored prepared VPN ownership, and passed its other sampled preservation checks, but failed the global settings comparison. The copied cleanup's `matrix-settings-intent.json` marker was absent, so its conditional settings restoration did not run. This was an incomplete test cleanup, not a passing handoff.

Explicit reconciliation restored original night mode, exact raw settings and native rotation through the existing restoration helper. Fresh readback passed all eleven comparisons: raw font/rotation/animation/night settings; native rotation; night-mode command state; prepared VPN owner/type; absence of userspace TUNs; personal profile bytes; kernel links/rules; root policies; original foreground; absence of the isolated package; and absence of owned remote paths. The original failed `cleanup.json` remains alongside `cleanup-reconciled.json`; reconciliation does not overwrite the failure. The phone was released after verified restoration.

## Limits and remaining gates

This establishes scoped native consent cancellation, retry and continuation across real in-process recreation on one rooted Android 14 phone, with an isolated debug artifact. It does not establish authenticated handshake, usable VPN traffic, DPI behavior, battery/speed gains, shipping/release artifact acceptance, changed-selection/replacement behavior on the device, process death, permanent Fragment removal or a complete Android support matrix. Source controls for those lifecycle boundaries retain their own scope.

The original failed phone trial's cause remains unproved. [#177](https://github.com/novakin/vpn0wn-android/issues/177) stays open. No new animation recording or motion cell is accepted; [PR #175](https://github.com/novakin/vpn0wn-android/pull/175) retains its separate 8/64 matrix and 56 remaining cells. No merge or release follows from these controls. Required exact-head checks and independently reviewed acceptance remain in PR #178; the then-current CI failure is not waived by native activation.
