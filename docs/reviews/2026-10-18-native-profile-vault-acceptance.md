# Native retained-profile vault acceptance

Last updated: 2026-10-18 (Europe/Paris).

## Scope and provenance

Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143), which remains open. This accepts a bounded physical Android storage slice of `VpsUserProfileStore`, `VpsUserProfileFile` and `VpsUserProfile`. The [retention contract](../backend-contracts.md#retained-generated-user-profiles--143) and [QA requirements](../qa-test-plan.md#retained-user-profile-controls--143) retain the wider gates. Production source did not change.

Source: clean `13fcc23fd5428fa289b42e9b76f08453c467ac88`, tree `1bc2525394941d37b197c92dfed778f21969c7c8`, with all 637 available tracked-file hashes unchanged across assembly and both exact native gitlinks initialized. This is PR #254's tested source boundary, not a claim about later master delivery. Independently reviewed ignored inputs add self-target instrumentation to a separate package, `local.vpn0wn.profilevaultprobe.debug`. Its actual packaged manifest uses plain `android.app.Application`, no permissions or application children, no shared UID and one self-target instrumentation. It cannot exercise product startup, UI or network behavior.

The arm64-v8a APK SHA256 is `94fe972f0849aa88130345edd6effd1eb0d9b4fa0dda753d055f442d75d6ba3b`. APK v2 verification passes; certificate SHA256 is `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. Actual packaged manifest, runner and production-owner DEX bodies, BuildConfig identity and isolated native package string were audited. The installed base APK hash matches. These static audits are distinct from the native results below.

Private evidence: `.artifacts/continuation-20261018/native-profile-vault-prep/runs/20261018-013535-a65be5/`, with source/harness/build receipts and APK audits. `device-attempt-02/` owns the complete admitted device trial, raw command arguments/exit codes, externally timed phases, process retirement and cleanup. The six reviewed build inputs remain pinned; the instrumentation SHA256 is `6bc0032f0ee1588c6ee5befb23358c7fd2e331a12a2e148c1d4cf5d76d1e9bb9`. The independently reviewed host runner SHA256 is `c7bf82e373a7c4e1b586bb989eac90369f6e1fe7d651600055b0499364628099`; its exact executed copy is retained. Raw device inventories and private metadata stay outside source distribution.

## Executed native controls

Pixel 7 Pro, Android API 34, completed boot: the isolated package was absent before installation. Both phases ran under the same newly assigned UID, with an external 90-second deadline each. Each supplied exactly one schema-1 PASS receipt, the complete expected true check set, the admitted package/API/UID and successful instrumentation completion code. Host exit status alone was not used as acceptance. The first trial's fourteen native controls passed but its foreground comparison selected no fields; that invalid preservation claim was withdrawn. A fresh absent-package installation repeated the same fourteen controls with complete nonempty restoration evidence. These are fourteen distinct controls, not twenty-eight.

| Phase | Native controls and result |
| --- | --- |
| Create | Five PASS: fresh owned storage; real AndroidKeyStore/profile round trip; private no-backup ciphertext; idempotent save without rewrite; unrelated sentinel preservation. |
| Process boundary | Explicitly force-stopped only the probe; `pidof` and the original `/proc` PID confirmed disappearance. Reopen used a different process under the same installed UID and unchanged boot. |
| Reopen | Nine PASS: persistent process reopen; atomic replacement and unrelated-profile preservation; failed rename preserving committed data; waiting cancellation settlement; waiting cancellation preserving the record; admitted cancellation settling its write before return; already-cancelled caller publishing nothing; authenticated tamper refusal without overwrite; missing-key refusal without overwrite or regeneration. |

Normal controls use the production store and real native key/file owners with synthetic profiles. They compare complete canonical payloads, require owned regular 0600 ciphertext under `noBackupFilesDir`, check native nonexportable AES key bytes and authenticated decryption, and reject private-key/PSK plaintext on disk. Successful replacement checks fresh nonce/inode and exact retained records. Rename refusal and cancellation use existing internal seams while retaining actual native sync/key/file operations. They prove those injected admission/commit boundaries, not naturally occurring disk failure or arbitrary interruption of a blocking native call.

The final key-loss control deliberately removes only the isolated UID's alias. The unreadable owned vault and unchanged ciphertext are expected; uninstall owns subsequent cleanup. No personal alias, profile, tunnel or server was used.

## Preservation and cleanup

The complete second trial's uninstall returned Success. Raw package path/list, process and boot readbacks establish owned absence on the unchanged boot. Installed-package/UID inventory, global and secure settings and exact sampled files under both personal apps' `files`/`no_backup` directories match their nonempty pre-install receipts with case-sensitive comparisons. Full `dumpsys window` supplies exactly two nonnull focus/app fields before and after; both match exactly. The legacy app's absent `no_backup` directory is recorded as absent in both snapshots. Two earlier read-only baseline commands failed on shell quoting and that absent directory; the complete paired baseline supersedes those incomplete setup attempts. No native control failed.

The one-off host runner's preflight review also caught a receipt gap on timeout/launch failure before any execution. The final runner persists phase arguments/start before launch, the owned PID before waiting, and completion/outcome/available exit in `finally`. The successful trial exercised the normal completion branch; timeout and launch-failure retention are source-reviewed, not failure-injected runtime proof.

No device setting, personal app, clipboard, VPN, root tool, remote host or live server was changed. Root was used only for the read-only personal-file hash comparison. Uninstall provides the owned cleanup boundary; this record does not claim inaccessible post-uninstall private-file or alias inspection.

## Limits and delivery

All fourteen controls pass on this one physical API-34 device and isolated debug artifact. This adds current-platform profile-vault storage/process evidence without extending the earlier [SSH-vault API-24/28 record](2026-10-15-native-ssh-credential-storage-acceptance.md) or accepting the whole provisioning feature.

API-24/28 profile-vault behavior, production Application/UI integration, configured authentication/admin restrictions, QR/export/import, current fleet/pin/revocation admission, Android SSH, actual backup/device transfer or exclusion, OEM/hardware-key quality, complete memory erasure, arbitrary crash/power-loss/directory-sync durability, release and live VPN/server behavior remain unverified. Deliberate alias deletion does not represent every key invalidation cause. Documentation review, publication, CI and merge remain with the delivering PR; no release/deployment or full #143 completion follows.
