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

## Standard-peer traffic follow-up — 2026-10-14

The maintainer reserved the phone for sequential checks under the existing APK/root authority. Two fresh installations reused the same audited `ac0cbf2e` APK. Runtime source in `ui/src/main`, `tunnel/src/main` and `tunnel/tools` is unchanged at `30ab37ff`; its later changes affect tests and documentation only. Each trial captured a new baseline and used independent ownership markers. Private receipts remain under ignored `.artifacts/vpn-consent-traffic-20261014/`.

Both trials used synthetic keys and numeric loopback endpoints. Android's real consent dialog was used; no AppOps preauthorization occurred. Only the isolated app UID and its SDK sandbox were admitted. After consent, the existing guarded helper created a standard kernel WireGuard peer with its interface and HTTP server in a private network namespace; its UDP socket remained in its birth namespace. The namespace holder and HTTP server had finite 180-second and 90-second lifetimes. No personal profile, remote VPS, global route or existing kernel interface was changed.

| Fresh control | Consent/lifecycle result | Authenticated transport result |
| --- | --- | --- |
| Stable, including cancellation and same-switch retry | Cancellation settled enabled DOWN with no active VPN/TUN; retry granted once and reached own-UID UP. | Exact expected HTTP body and status 200; fresh synthetic-peer kernel handshake; RX increased 804 bytes and TX 588 bytes. |
| Light, 2× Arabic, portrait request and landscape approval | Owned PID `9925` destroyed/recreated `MainActivity` at `09:41:08.872Z`/`09:41:08.932Z`, before the approval tap command completed at `09:41:10.098492Z`; own-UID UP followed without process replacement. | Exact expected HTTP body and status 200; fresh synthetic-peer kernel handshake; RX increased 804 bytes and TX 588 bytes. |

HTTP used root curl explicitly bound to the newly owned client TUN. Kernel peer key attribution, fresh handshake time, bidirectional counter deltas, exact response and retained TUN identity were checked separately. This proves authenticated transport through that TUN; it does not establish ordinary Android application routing or external Internet reachability.

Both transport controls passed before cleanup. The stable cleanup initially failed when the finite HTTP process disappeared during a `/proc/PID/ns/net` read: the imported helper expected `CalledProcessError`, while the assigned command owner reports `RuntimeError`. The corrected checker admits absence only for the exact empty-output exit-1 process-read receipt followed by confirmed PID absence; live/reused PIDs, timeouts, transport errors and ambiguous output remain failures. A separate reconciliation passed all fifteen restoration comparisons without repeating the transport trial.

The rotated cleanup initially restored native free rotation but retained raw `user_rotation=1` instead of original `0`. All other selected settings matched. The revised restoration first requires full native/raw agreement with the captured saved rotation under native lock, then restores the original free mode and requires full agreement again. State observation is bounded; unsettled or unrelated errors remain failures. The precise platform cause of the earlier divergence remains unproved. Thirty-four injected host controls pass for the final checker, which was independently ultra-reviewed before reconciliation.

The rotated reconciliation also passed all fifteen comparisons with no errors: original raw settings, night mode, native rotation, prepared VPN owner/type, no userspace TUN, personal profile bytes, kernel links/rules, root policies, foreground, package absence, scratch/hierarchy absence, released owned ports, preserved personal kernel configuration/tool and absent owned processes. Both original failed `cleanup.json` records, baselines and session pins remain intact beside separate successful reconciliation receipts. Successful reconciliation blocks stale cleanup replay. These are sampled preservation checks, not a complete device/storage audit.

## Limits and remaining gates

This establishes scoped native consent cancellation, retry and continuation across real in-process recreation on one rooted Android 14 phone, with an isolated debug artifact. The follow-up adds authenticated same-phone standard-kernel transport through a root client bound to the owned TUN. Ordinary-app routing, external IPv4/IPv6 paths, DPI behavior, battery/speed gains, shipping/release artifact acceptance, changed-selection/replacement behavior on the device, process death, permanent Fragment removal and a complete Android support matrix remain unverified. Source controls for those lifecycle boundaries retain their own scope.

The original failed phone trial's cause remains unproved. [#177](https://github.com/novakin/vpn0wn-android/issues/177) stays open. These consent controls accepted no animation recording or motion cell; [PR #175](https://github.com/novakin/vpn0wn-android/pull/175) then had its separate 8/64 checkpoint and 56 remaining cells. [Later motion acceptance](2026-10-13-connecting-motion-acceptance.md#fresh-integrated-matrix--2026-10-14) records its completed matrix and integration delivery. No merge or release follows from the consent controls alone. PR #178 owns exact-head checks and independently reviewed acceptance; its then-current CI failure was not waived by native activation.
