# Preserved local work and recovery boundaries

Last updated: 2026-10-10 (Europe/Paris).

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
| Logs | `LogLineParser`, parser fixtures and `LogViewerActivity` stream/filter/export changes. Preserve merged error/cancellation handling; prove deadlines, retention/filter indices and pipe ownership. | [#33](https://github.com/novakin/vpn0wn-android/issues/33) |
| Settings | `SettingsActivity`, preference XML and header/row/category resources. Preserve platform/admin/kernel visibility, existing values, feedback and inset ownership. | [#34](https://github.com/novakin/vpn0wn-android/issues/34) |
| Add/detail | Add-sheet order/subtitles/header/resources and peer RX/TX cells. Preserve import actions, safe areas, surviving-view feedback and view/selection lifecycle guards. | [#35](https://github.com/novakin/vpn0wn-android/issues/35) |
| Selection/delete | List fragment/layouts, checkbox/stats/delete-sheet resources and old menu deletion. Reconcile one selection owner, current 48dp compact header targets, Add footer and manager settlement. | [#36](https://github.com/novakin/vpn0wn-android/issues/36) |
| UDP variant | `GoBackend`, its new error reason/message and related local documentation. The local mandatory/fresh-port behavior conflicts with merged endpoint-free and explicit ListenPort compatibility; review intent before compatible recovery. | [#37](https://github.com/novakin/vpn0wn-android/issues/37) |

The 36 changed default/locale string files span several UI slices. Port reviewed keys with their owning change; resource presence alone does not prove translation quality. Preserve `.vscode/settings.json` as a local preference. Local Done claims describe unmerged candidates and must not replace current guides wholesale. Existing #28/#17 and requested APK CI remain separate priorities.

## Verification and delivery limits

Executed here: refreshed Git comparison, file/patch/history preservation, before/copy/after SHA-256 checks, Git bundle verification, status/index readback, installed/local APK identity checks and GitHub duplicate/tracking inspection. The focused preservation helper and command logs remain ignored local evidence.

No Gradle runtime/static suite, APK assembly, app reinstall, UI interaction, VPN control, root/SSH or live-server operation ran for this preservation task. Preserved parser test source is not an executed result. Each recovery PR needs current tests/Detekt and relevant actual-output proof, updated owning guidance and explicit remaining limits. Implementation, verification, merge and any eventual cleanup remain separate states.

## Local archival and clean checkout — 2026-10-07

The maintainer requested an end to the dirty checkout. The [recovery plan](../improvement-plan.md#preserved-local-work-recovery) now records reviewed source integration for Logs, Settings, Add/peer and Home, plus the decision to reject the mandatory/random-port UDP variant. The 76 source/documentation paths below include translations and older implementations; they are not 76 pending features or a new accepted runtime change.

Before changing checkout state, all 355 snapshot files were verified against the original manifest; all 352 source copies still matched the original checkout, including its expected menu deletion. The index remained empty. A credential-pattern scan identified only the two localized PEM header placeholders, with no key payload. The existing preservation snapshot remains unchanged apart from its local recovery guide's dated clarification.

All 56 tracked changes, including the deletion, and 20 untracked source/test/resource additions are preserved in local branch `archive/preserved-local-work-2026-10-07`, signed commit `592e1ddd73e4f1ab29cd044d637698da0a951fe4`. Windows Git verified its SSH signature against the configured public signing key. This is an unreviewed archival tree, not integration of its old implementations. The branch was not pushed. A separately verified complete-history bundle under ignored `.artifacts/local-work-cleanup-20261007/` includes the archive and captured master `48e9310151c347beeb33bbf816924dbb1602c4d6`. Raw snapshots retain original file bytes where Git normalizes line endings; keep both forms.

The original checkout was switched to `master` and fast-forwarded to that captured revision. All ten registered worktrees then reported zero tracked, staged or untracked status entries. `.vscode/settings.json` remains byte-for-byte unchanged and is excluded through repository-local `.git/info/exclude`; it was not added to source history. All 17 read-only design files still match the snapshot, and the original checkout's retained APK keeps its recorded SHA256. Existing branches, worktrees, APKs, snapshots and current-task evidence were retained; no reset, rebase, force-push or deletion of unique work occurred.

Private verification summaries, archival path lists, bundle and local recovery guidance remain ignored. They are local recovery on the same disk, not an independent backup. Inspect old source with `git show archive/preserved-local-work-2026-10-07:<path>` or reconstruct it separately under the local recovery guide; use current master for new work and recover any useful remaining hunk through the normal reviewed PR workflow. Physical acceptance remains open under #28/#34/#35/#36/#52; archive preservation does not complete those gates. No local Gradle run, APK build/install, phone action or server operation was needed for this cleanup. The documentation PR owns its final review, CI and integration state.
