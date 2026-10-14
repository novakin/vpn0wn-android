# Scoped rooted Settings acceptance — #34

Last updated: 2026-10-14 (Europe/Paris).

## Scope and provenance

This record adds scoped Pixel 7 Pro/API 34 Settings evidence for [#34](https://github.com/novakin/vpn0wn-android/issues/34) and [#52](https://github.com/novakin/vpn0wn-android/issues/52). The maintainer reserved the phone for sequential checks; standing APK/root-test authorization applied. Personal apps, profiles, preferences and the preexisting kernel runtime remained outside the test mutation scope.

The existing audited isolated debug APK uses source `cc0c3ea8f85cb0530991904db4a8dcd128ac5723`, application ID `local.vpn0wn.recoveryprobe.debug` and SHA256 `5ec7923b7836f1d17f0a0c1594ee4e7a990c5e4e167d66da00c2e8f0cd7f3071`. Production inputs are unchanged through master `813b484ea5313a07eb41f0ddb97ef5f90441861c`; intervening changes affect tests/documentation. Fresh installation and installed-byte checks belong to each session. No new APK assembly, shipping-package, upgrade or migration acceptance follows.

The candidate stayed empty. Before its first launch, the fresh UID received one temporary Magisk DENY row; no privileged app shell, root module, tools installation, saved profile or VPN activation was admitted. The existing ownership/census/retirement helpers preserve unrelated policies and retire only the owned denial before uninstall. Private receipts, hierarchies and images remain under ignored `.artifacts/settings-acceptance-20261014/`.

## First session: actions and normal portrait output

Session `stable-20261014T132707285249Z` used frozen `settings.py`, SHA256 `ab43f7050f862f40c8dfcd78a60ff83a48e867268900fe13f0597712ed417c5f`, after twenty host controls and independent ultra preflight. Actual native Settings rows settled under denied root. Kernel, boot, multiple-tunnel, tools and Appearance rows were absent as expected for the observed userspace/API conditions; this is filtering proof, not privileged-action acceptance.

| Control | Accepted observation | Boundary |
| --- | --- | --- |
| Remote-control preference | Unchecked to checked, persisted through actual landscape rotation and reopening; restored unchecked with byte-identical 101-byte preferences. | One reversible preference control; no remote intent execution. |
| Native tile Cancel | Inventory unchanged and setup remained available. | No tile VPN toggle. |
| Native tile Add/removal | Only the owned candidate tile was added; eligibility refreshed; exact removal restored original inventory. | Existing unrelated tiles preserved. |
| Normal English portrait | Native top/bottom output under system light/dark requests independently passed visual inspection. | Both modes retain the fixed navy app palette. |

The large-text landscape admission failed after successful font/rotation commands: all ten retained hierarchy samples remained actual rotation `0` with portrait bounds. Four enlarged/RTL cases received no acceptance from this session. There was no retained WMS/cache/raw snapshot at the failed request, so neither a product defect nor the reason for the rejected rotation is established. A command exit of zero was not counted as actual configuration proof.

## Restoration and independent review

Initial cleanup failed: owner 12/14 and composite 13/15. The recorder census rejected the valid transient process row `20589 [Shutdown thread]`; scratch/hierarchy cleanup was consequently incomplete. Its original failure remains retained. The transient exited, and unchanged reconciliation completed separately.

Independent raw-receipt review passed all fourteen owner checks, fifteen composite checks and seven additional Settings preservation fields: tools, modules, personal preference hashes, tile inventory, accessibility services/enabled state and sampled kernel-support presence. Actual default-display rotation/cache/native/raw state restored to rotation `0`, free mode and original raw settings. The stopped fresh UID's denial census/retirement, zero pending requests, stable daemon and unchanged unrelated policies were independently checked. This is sampled preservation, not a complete storage audit or the broader personal-kernel snapshot used by the motion transition owner.

The reconciliation verdict SHA256 is `5792a6b0d81b56dae008881cadf625787ae34048562cfeb67c8dbcb83547fd3e`. Original `cleanup.json`, separate `cleanup-reconciled-20261014T133545668726Z.json`, its owner counterpart and `settings-restoration-reconciled-20261014T133545668726Z.json` retain their distinct scopes. Two portrait cases independently pass; no failed or missing matrix case is relabeled as passing.

## Four-case follow-up: installer admission failure

Fresh session `stable-20261014T135426723640Z` used a separately reviewed four-case wrapper, SHA256 `cf5912ee511badea263569abb6dbf886cd8c46cd0a09622fe46102235a8574f5`. Seven focused host controls replayed the retained rotation failure and required actual default-display/cache/native/raw agreement before and after output, alongside hierarchy/configuration/PNG checks. The original Settings and transition owners remained frozen; successful switch/tile and normal portrait controls were not replayed.

This attempt stopped at `adb install --no-streaming` after its 90-second deadline, before candidate launch, denial admission or UI testing. The command's remote outcome was recorded as unknown. Sampled cleanup and the seven extra Settings fields passed, but Android still retained active installer session `1632069400`; package removal alone did not establish installer settlement.

Later native logs record Android backup-restore completion followed by this session's `INSTALL_FAILED_PACKAGE_CHANGED` result: the package had been removed before post-install handling completed. Completion occurred about 4 minutes 26 seconds after session creation. Two later package-manager snapshots place it in history with destroyed state and final status `-23`, with no active/finalized session. Independent review recomputed fourteen composite checks, thirteen owner checks and seven extra preservation fields; terminal installer settlement is a separate gate from those sampled checks. The earlier delay's precise cause remains unproved; the observed backup-completion sequence is not a diagnosed app failure. No reboot, PMS reset or global backup-policy change occurred. This failed attempt supplies no additional matrix acceptance.

## Four-case follow-up: native rotation rejection

Session `stable-20261014T141118862684Z` used a separately copied wrapper, SHA256 `3e54ad3673661e931dc0f980d35638c7ddf7e3e608fb19916dcf56af4a3a68d0`. Its only installation change extended the exact owned non-streaming call's deadline from 90 to 360 seconds, following the observed late completion. Ten focused host controls and independent ultra preflight verified the bounded delta; it introduced no retry, sleep, backup-policy or APK change. Installation returned success in 3.219 seconds, and installed bytes/identity matched the pinned artifact.

The first landscape case again failed admission. Command `0072` requested `lock 1` successfully; the first native observation had locked rotation `1` in native/raw/cache state, while actual display rotation remained `0`. All twenty-one retained display observations remained actual `0`, and later native/raw/cache state returned to locked `0`. These native/raw/cache/display reads are sequential samples, not an atomic snapshot. No owned rotation setter reversed the target before failure diagnosis; the next owned `lock 0` belonged to cleanup. The reason remains unproved, and no remaining output case passed.

The same valid multiword-process parser defect interrupted scratch/hierarchy cleanup: owner 12/14 and composite 13/15. Original failure remains retained. Unchanged reconciliation at `20261014T141341127398Z` independently passed fourteen owner checks, fifteen composite checks and all seven extra Settings fields, including exact denial retirement and stable unrelated policies. The separate reconciliation verdict SHA256 is `d00c64ec3bbe57c7f2fd18d1f1f57f670b4d373053a0048fc26f9736fe109524`.

## Ordered follow-up: accepted English 2× dark landscape

Session `stable-20261014T143637492483Z` used separately reviewed ordered wrapper `67c363dc16ba4252fc8d74fb99fcec095f615a5b52dfd87452f1af561968659d`. Sixteen focused host controls and a composed-factory check verified the final preparation. Its setup followed the existing admitted case order and required actual default-display/cache/native/raw agreement before and after output. The separate recorder copy corrected only the multiword process-row split; existing ownership, finite-wait and cleanup guards remained. Installation returned success in 3.25 seconds, and installed bytes matched the pinned APK.

The first case admitted English, font scale `2.0`, system night mode and actual landscape output at 2340×1080. Actual/default, cached, native and raw rotation agreed at `1` with locked mode. Independent original-pixel inspection accepted the complete header/Back, supported row titles and summaries, wrapping and scroll reachability without observed product overlap or clipping. Clean top/final images replace captures obscured by transient host root toasts; the toasts establish no candidate root authority. The fixed navy palette remains. This is presentation/scroll proof, not execution of the displayed export/import or privileged actions.

The next Arabic 2× light portrait case failed admission: requested rotation `0` did not replace actual/native/cache/raw landscape `1`. These sequential readbacks establish the rejected configuration, not an atomic snapshot or its cause. No new RTL output was accepted. Raw independent review passed fourteen owner checks, fifteen composite checks and seven additional Settings preservation fields, including restored actual/default/cache/native/raw rotation, exact denial retirement and unchanged unrelated policies. HTTP/holder-process absence is vacuous because no such process launched; this session retains no broader per-interface personal-kernel snapshot.

Separate retained verdicts are `.artifacts/yellow-motion-analysis-20261014-ultra/settings/settings-ordered-runtime-independent-verdict.json`, SHA256 `6b3e933ff90e871ff1f1eaccee7d3538e9dcf64ad1352ee136b2c5aca6674f4a`, and `visual-landscape-20261014T143637492483Z.json`, SHA256 `0d162073a836ee530e141545f26148db28945d76ab08642381d05a9bd63f751b`. The raw verdict's visual-pending checkpoint is resolved by the separate visual review without rewriting frozen evidence. Together with the first session's two normal portrait cases, this establishes **3/6 accepted configurations**; the earlier failed admissions and reconciliations retain their original scope.

## Singleton RTL follow-up: 360-second installer timeout

Session `stable-20261014T144913785332Z` selected only Arabic 2× light portrait through separately reviewed wrapper `17087b3549c0c04b3a6ce1af5cbac7f10f4e4ed97f5dad3b35528682ce2cd2d0`. Twenty focused host controls verified the singleton selection/pin delta and retained ordered setup, ownership and output/restoration gates. No earlier accepted case or switch/tile action was replayed.

The exact owned `adb install --no-streaming` call timed out after 360 seconds. Its remote outcome remained unknown; installed UID/base-APK identity, temporary DENY admission, explicit app launch and UI output were never admitted. Independent retained-evidence review passed fourteen composite checks, thirteen owner checks and all seven extra Settings preservation fields. Those sampled comparisons do not establish installer settlement, and this attempt supplies no new output acceptance.

Installer session `1000676129` was matched to the owned interval, shell UID 2000, user 0, unique active session and exact APK size 25,975,494 bytes. Its package name was unresolved. The exact owned abandon request returned `Success`, but the retained after-abandon dump still lists it active and destroyed, with `stageDirInUse=true` and final status `0`. Terminal settlement remains unverified; package absence and accepted abandon alone do not authorize a blind retry. No reboot, PMS reset or global backup-policy change occurred. The delay's cause remains unproved. The pre-abandon independent receipt `settings-case-install-timeout-review.json`, SHA256 `114fa8220f51a58ab18c9239d8bab8cf1e299541154ec15666bc4843da20dae0`, retains HOLD. Fresh installation remains held until exact terminal installer/package/path readback is reconciled.

## Remaining acceptance

| Physical configuration | Current acceptance |
| --- | --- |
| English 1× portrait, system light | Accepted in the first session. |
| English 1× portrait, system dark | Accepted in the first session. |
| English 2× landscape, system dark | Accepted by separate raw and visual reviews of the ordered session. |
| Arabic 2× portrait, system light | Unverified; rejected rotation and later installer timeout add no acceptance. |
| Arabic 2× portrait, system dark | Unverified. |
| Arabic 2× landscape, system dark | Unverified. |

Three physical RTL cases remain pending. Native hierarchy labels, roles and checkable state do not establish spoken TalkBack traversal/actions/announcements, translation or conditional Android/admin/OEM acceptance. Root-granted backend switching, multiple tunnels, tools actions and actual boot restoration remain separate. Tile membership does not prove tile VPN activation. Physical authentication/export/provider/write/cancellation acceptance belongs to the corresponding action owners.

Settings source and policy behavior are unchanged. [QA](../qa-test-plan.md#remaining-g4-device-acceptance) owns remaining checks; [the roadmap](../ui-redesign-roadmap.md#pending) retains incomplete acceptance. Neither #34 nor #52 closes from this scoped record. No performance, protocol, release or whole-E2 acceptance follows.
