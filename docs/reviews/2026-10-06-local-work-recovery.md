# Preserved local work and recovery boundaries

Last updated: 2026-10-06 (Europe/Paris).

## Scope and revision

This source/provenance review covers the original checkout at `65feb91d122f4954ee0ced7537f5cf7c9d4769b4`, branch `docs/agent-readme-review`, with 77 dirty entries and an empty index. Comparison base is merged master `17020152edf4c57fc4952478a486952a8940ebf7` after PR #32. The separate PR worktree was clean before this documentation change. Preserve the original checkout and its retained evidence; its branch name does not describe all its uncommitted work.

The review identifies recoverable slices and compatibility concerns. It does not validate the dirty source as a release, execute its test classes, implement recovery or authorize cleanup. The [improvement plan](../improvement-plan.md#preserved-local-work-recovery) owns current action status; linked issues own acceptance.

## Working APK provenance

A read-only check of the connected phone's installed `com.wireguard.android.debug` APK returned SHA-256 `6C954F327CF8C9FAB15CC002AAEED8721403B73998F31266D57813251E53D257`. This matches the APK retained from PR #31's reviewed worktree and its recorded assembly/install evidence. The original checkout's APK instead hashes to `59AE49B8F5FFCE0AB23DEBE1AA80A68B913766F2103FCB568B5311D0E469E197`; preserve both.

PR #31's runtime source remains in captured master; intervening changes are documentation. Artifact identity separates the current installed build from the original dirty checkout. It is not a new VPN, traffic, UI, root/kernel or process-lifecycle test. Earlier [Home header evidence](../development.md#home-header-touch-targets--2026-10-06) retains its original scope.

## Preservation evidence

The local snapshot is under ignored `.artifacts/preservation/2026-10-06-before-recovery` in the original checkout. It contains current main-repository tracked/untracked files, the read-only local design reference, both APKs, a binary-capable tracked patch, a manifest/reconciliation inventory and a Git bundle for original HEAD and captured master. The previously deleted action-mode menu stays absent in the source copy; its deletion remains recorded in the manifest/patch and its baseline exists in Git history.

All 355 copied files matched their source SHA-256 before and after copying. The bundle verified with complete repository history. Original status remained 77 dirty entries with an empty index. Native `elf-cleaner` and `wireguard-tools` submodules are represented by recorded revisions; their source directories/object stores are not copied or bundled separately. Ignored signing material, device captures and build caches are not included. The snapshot is on the same disk and provides local recovery rather than an independent disaster-recovery backup.

The snapshot's local recovery guide explains reconstruction in a separate directory. Keep raw source copies, local editor settings, manifests and APKs outside the source change and GitHub evidence uploads. No tracked original file or installed app was changed during this review/preservation.

## Source classification and recovery

The 77 entries consist of 56 tracked changes and 21 untracked files. Normalized Git-content comparison found zero exact matches with captured master, 40 changes on master files unchanged since the original HEAD, 15 files overlapping later merged changes, 21 local additions absent from master and one local deletion still differing. Whole-file differences do not prove every hunk is unique; reconcile each candidate against current owners.

| Slice | Preserved candidates and constraints | Tracking |
| --- | --- | --- |
| Logs | `LogLineParser`, parser fixtures and `LogViewerActivity` stream/filter/export changes. Preserve merged error/cancellation handling; prove deadlines, retention/filter indices and pipe ownership. | [#33](https://github.com/novakin/wireguard-android/issues/33) |
| Settings | `SettingsActivity`, preference XML and header/row/category resources. Preserve platform/admin/kernel visibility, existing values, feedback and inset ownership. | [#34](https://github.com/novakin/wireguard-android/issues/34) |
| Add/detail | Add-sheet order/subtitles/header/resources and peer RX/TX cells. Preserve import actions, safe areas, surviving-view feedback and view/selection lifecycle guards. | [#35](https://github.com/novakin/wireguard-android/issues/35) |
| Selection/delete | List fragment/layouts, checkbox/stats/delete-sheet resources and old menu deletion. Reconcile one selection owner, current 48dp compact header targets, Add footer and manager settlement. | [#36](https://github.com/novakin/wireguard-android/issues/36) |
| UDP variant | `GoBackend`, its new error reason/message and related local documentation. The local mandatory/fresh-port behavior conflicts with merged endpoint-free and explicit ListenPort compatibility; review intent before compatible recovery. | [#37](https://github.com/novakin/wireguard-android/issues/37) |

The 36 changed default/locale string files span several UI slices. Port reviewed keys with their owning change; resource presence alone does not prove translation quality. Preserve `.vscode/settings.json` as a local preference. Local Done claims describe unmerged candidates and must not replace current guides wholesale. Existing #28/#17 and requested APK CI remain separate priorities.

## Verification and delivery limits

Executed here: refreshed Git comparison, file/patch/history preservation, before/copy/after SHA-256 checks, Git bundle verification, status/index readback, installed/local APK identity checks and GitHub duplicate/tracking inspection. The focused preservation helper and command logs remain ignored local evidence.

No Gradle runtime/static suite, APK assembly, app reinstall, UI interaction, VPN control, root/SSH or live-server operation ran for this preservation task. Preserved parser test source is not an executed result. Each recovery PR needs current tests/Detekt and relevant actual-output proof, updated owning guidance and explicit remaining limits. Implementation, verification, merge and any eventual cleanup remain separate states.
