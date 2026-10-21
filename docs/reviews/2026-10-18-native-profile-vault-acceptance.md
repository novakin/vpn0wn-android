# Native retained-profile vault acceptance

Last updated: 2026-10-18 (Europe/Paris).

## Scope and provenance

Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143), which remains open. This records bounded storage acceptance of `VpsUserProfileStore`, `VpsUserProfileFile` and `VpsUserProfile`: the original physical API34 trial and the separate API24/API28 emulator cohorts below. The [retention contract](../backend-contracts.md#retained-generated-user-profiles--143) and [QA requirements](../qa-test-plan.md#retained-user-profile-controls--143) retain the wider gates. Production source did not change.

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

At this original checkpoint, API24/API28 profile storage remained unverified. The separate follow-up below accepts the named emulator controls without changing the original physical result. Production Application/UI integration, configured authentication/admin restrictions, QR/export/import, current fleet/pin/revocation admission, Android SSH, actual backup/device transfer or exclusion, OEM/hardware-key quality, complete memory erasure, arbitrary crash/power-loss/directory-sync durability, release and live VPN/server behavior remain outside this vault record. Deliberate alias deletion does not represent every key invalidation cause. The delivering PR owns documentation review, publication, CI and merge; no release/deployment or full #143 completion follows.

## API24 and API28 emulator acceptance — 2026-10-18

**VERIFIED, dynamic, within this isolated storage scope:** both fresh x86_64 guests passed the same fourteen controls listed above. These are fourteen distinct controls repeated on each API, not twenty-eight new behaviors. Each guest supplied exactly five create and nine reopen Boolean checks, schema 1 PASS receipts, the admitted package/API/UID and instrumentation completion code `-1`. A direct host exit 0 alone was not acceptance.

| Guest | Installed Google APIs image | Native controls | Process and cleanup result |
| --- | --- | --- | --- |
| Android 7 / API24 | `android-24;google_apis;x86_64`, revision 27 | Fourteen PASS | Same UID, distinct create/reopen PIDs, old PID absent; baseline, uninstall and owned shutdown PASS. |
| Android 9 / API28 | `android-28;google_apis;x86_64`, revision 11 | Fourteen PASS | Same UID, distinct create/reopen PIDs, old PID absent; baseline, uninstall and owned shutdown PASS. |

The fresh x86_64 APK SHA256 is `5b94e17de2193409418012b99c95b1a5ac92e12e0039cb3560acee6b0c19082f`, 18,977,478 bytes, minSdk24/targetSdk36. It reuses the original source revision/tree, probe Kotlin, manifest, Gradle overlay and source pins; this fresh cohort's producer and audit admit x86_64. Its six build inputs remain byte-identical across the A1–A4 host corrections. Independent actual-artifact review verified v2 signing, plain Application, no permissions or application components/shared UID, one self-target instrumentation, meaningful DEX bodies and x86_64 native identity. Source/build receipts remained stable. The separate build-exit observer returned null codes; those remain unproved. The checked producer completion and Gradle success log are separate evidence, while the actual artifact audit retained direct exit 0.

This remains older isolated-APK evidence at `13fcc23f`. A bounded Git-blob comparison against `a0d39751` proves equality of twenty-five complete vault/model/config/crypto/build inputs and only the unchanged `VpsAddPeerRequest` declaration slice in an otherwise changed file. It does not prove whole-file, production Application, later preferences-repair or whole-current-APK equivalence. The original API34 artifact and physical receipts retain their original scope.

### Preserved host admission failures

Three consumed API24 cohorts stopped before any native control; API28 was not started in them. They establish host admission failures, not Android vault failures or passing native results:

- A1 rejected CRCRLF console output and then valid IPv4/IPv6 listener rows owned by one backend. Separately verified exact-owner cleanup completed. The corrected guards retain strict observation, exact names and ownership while accepting those representations.
- A2 installed successfully but its guest lacked `sha256sum` (exit 127). Automatic package, baseline and owned-guest cleanup passed.
- A3's sync-service `adb pull` returned exit 1 for the exact path supplied by `pm path`. Automatic cleanup passed. Physical file absence and a SELinux cause were not established and remain unclaimed.

Each old input set, raw failure and cleanup receipt is preserved. Final A4 admission verifies the ordinary installed UID and matching `run-as` UID, then uses structured `adb exec-out run-as` arguments and raw .NET byte streams to copy only that isolated installed APK into fresh host evidence. The copied bytes must match the audited SHA256 before instrumentation. This uses neither root nor a guest hashing utility. The transfer keeps its 20-second bound; both native phases retain 90 seconds. Seven mocked host controls cover the actual binary helper and failure/cleanup receipts; they are separate from Android results.

### Runtime preservation and independent review

Both A4 copies matched the actual APK, with direct client exit 0 and settled stdout/stderr. Native force-stop, empty `pidof` and old `/proc` PID absence precede distinct-process reopen under the same UID and boot. Real AndroidKeyStore/private-file round trips, the existing injected rename/cancellation boundaries, tamper refusal and deliberate key loss passed on each API.

Both uninstalls and absent path/package/process readbacks passed. Complete sampled package/path inventories, boot IDs, VPN-policy and animation settings match each fresh guest's baseline. Exact-owned emulator launchers/backends and ports are absent; previously observed shared process identities are preserved. Owned userdata and evidence remain retained. The physical phone was never selected by these cohorts. These are sampled preservation checks, not inspection of every Android state or inaccessible post-uninstall alias/file.

Private evidence is retained under `.artifacts/continuation-20261018/profile-vault-api24-28-prep/`: producer `runs/20261018-080509-b9012a/`, actual-artifact audit, immutable input history, `guests/api24-a4/` and `guests/api28-a4/`. Final A4 input pins are `c12ded0abbaf5098c4c9070f75e4811eee2f1b82f8774d36d4b2574700bc8724`; executed runner SHA256 is `a915c1a56fed5a08a17f5f999f784b3fd14006a0b4876aae4ae59236cf9a43c5`. Independent ultra reviewed actual source/artifact bindings, native receipts and exact check sets, UID/process ordering, copied bytes, expected command exits, baseline equality, cleanup and host absence; no unresolved finding remains in this bounded evidence scope. Final documentation-head review and delivery remain the owning PR's gates.

Overall provisioning/profile acceptance remains **PARTIALLY_ASSESSED**. Emulator keys do not establish physical/hardware/OEM key quality; force-stop/reopen does not establish arbitrary crash or power-loss durability. Product startup/disclosure, configured authentication/admin restrictions, actual backup exclusion/transfer, fleet/pin/revocation workflows, current preferences repair, Android SSH, live VPN/server behavior and release remain separate. No performance, battery or DPI benefit, full #143 completion or production-readiness claim follows.
