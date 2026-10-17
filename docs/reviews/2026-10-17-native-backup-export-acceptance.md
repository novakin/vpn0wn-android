# Native encrypted server-export acceptance

Last updated: 2026-10-17 (Europe/Paris).

## Verdict and boundary

Related to [#196](https://github.com/novakin/vpn0wn-android/issues/196), which remains open. The bounded private writer gate passes: actual native authentication selection, masked password form, Main/progress responsiveness, Cancel authorization retirement, structured drain, owned closed ciphertext and independent exact-snapshot recovery. The available API34 physical trial accepts the [conditional writer policy](../architecture.md#private-writer-derivation-cost-acceptance-policy--196-2026-10-17) at unchanged costs. It does not establish cold p95, configured authentication, general device/provider behavior, live-server backup/restore, complete #196 delivery or release.

The [original codec record](2026-10-16-native-backup-codec-acceptance.md) retains the initial API36 cold p95 **3044.284840 ms FAIL** against the proposed 2000 ms target, the separate settled diagnostic and the separate fresh-boot cohort including its first **6004.295886 ms** maximum. No cohort is replaced, pooled or pruned; no cost reduction, hidden KDF warm-up or writer-format freeze follows from this targeted workflow proof.

## Tested producer and artifact

The trial used the uncommitted `fix/vps-backup-document-io` source based on `3d5639fe964d9790c1bdd61d581e7fc74f1f830b`. Its exact changed-file SHA-256 manifest is `d49000bd248d0c2f5f5b856b3a070deebef44a30ffc0587c220fc48921eea052`; the production patch SHA-256 is `672ed72ae1963f336e32d0703d05578fbdde5e12562053c0b16c5697a1412952`. These identify the tested source without inventing a clean commit or making this later documentation a new runtime run.

The exact ARM64 isolated debug APK SHA-256 is `4cddd35feb7e154951770543b407e069738cc27c6efab68d1347ee9eadcb0174` (18,611,275 bytes). Its harness-input manifest SHA-256 is `65c03d2ca40ec9e8a8ce720a13fd752111232118d84b5a1ef7e40c2dd15cc610`. Source/input hashes remained unchanged across assembly, actual-artifact audit and the trial; independent review checked their readback and actual DEX.

The isolated manifest retains the real Application and one non-exported real MainActivity with self-target instrumentation, min24/target36, backup disabled, sole `arm64-v8a` and only the normal `USE_BIOMETRIC` permission. It has no INTERNET permission, VPN service, receiver or provider. Signature, actual runner/Fragment/codec/Argon2 DEX, corrected password setter order and exact BC MIT notice passed artifact audit. Real stores, Keystore, Fragment, ViewModel, provisioning client, codec and Downloads writer execute; only the existing remote-shell factory receives synthetic in-process command responses. No SSH, server or VPN activation occurs.

The corrected source passed 91 controls in six complete maintained classes with zero failures/errors/skips and zero Detekt findings. [QA](../qa-test-plan.md#private-encrypted-server-export-controls--196) preserves both this checkpoint and the earlier 90-control source scope, plus the six inspected synthetic form images.

## Native observations

The fresh isolated trial ran on a Pixel 7 Pro, API34, with an unlocked device and no configured device authentication. It used the actual no-configured-authentication branch, actual Keystore credential round trip and actual Backup action. The secure native form had masked editable password nodes, associated labels and complete 48dp actions at 411×802dp and 1× font scale. `FLAG_SECURE` remained set; no screenshot was requested.

| Interval/control | Observation |
|---|---|
| First actual derivation | BC Argon2 worker observed off Main; real progress shown, confirmation disabled and password editors cleared/disabled |
| Main callback during derivation | 0 ms at uptime-clock resolution; no zero-cost or KDF-duration claim |
| Cancel UI/action retirement | 30 ms from click, within the predeclared 1000 ms bound; worker still observed deriving afterward |
| Click through structured join/drain | 247 ms; both retained jobs and remote credentials/clients drained, no cancelled output |
| Cancellation interval | 20 Main heartbeats, 24 frame callbacks, maximum observed heartbeat gap 67 ms |
| Completed export interval | 22 Main heartbeats, 23 frame callbacks, maximum observed heartbeat gap 88 ms |
| Completed owned file | Expected owner/MIME/extension, complete closed ciphertext; zero matching self-process writer descriptors after close |

The completed file has 489 bytes and SHA-256 `d8d21a350bb00c070339f7164bd8e4b41bdcd13a9dd39c797e96b24559768446`. Binary-safe extraction matched that native byte count/hash. A separate host Python implementation independently derived Argon2id v1.3 at 19,456 KiB/two passes/one lane, authenticated the existing header as AES-256-GCM AAD, decrypted the file and compared every synthetic snapshot field and exact config/history value. It passed with the same byte count/hash; no replacement writer or codec was used to produce the file.

Cleanup verified removal and absence of owned Downloads/fleet data and activity destruction, then isolated-package uninstall/path absence and restoration of the original foreground application. Raw serial, UID, installed paths, foreground component and host/process receipts remain private and outside source distribution.

## Retained findings and corrections

Four earlier native trials stopped before KDF: an invalid synthetic six-character temporary-name fixture, instrumentation racing real Application startup, the isolated manifest stripping the required normal biometric-query permission, and a real default-masking defect. The first three were harness admission failures. The masking finding was product behavior: Android `TextView.setSingleLine` overwrote the password transformation. The Backup helper now applies single-line setup before password input type and the approved Mono typeface afterward. A populated-text/accessibility/actual reveal-remask regression reproduced the original failure before passing after correction. The separate existing SSH helper correction remains owned by #143.

A fifth trial reached actual KDF/progress and passed the corrected native form controls, then failed a premature same-runnable Cancel assertion. Packaged AppCompat queues button dismissal, and Android queues the `OnDismiss` callback that retires authorization. The harness was corrected to await that settled state using the original absolute click-time-plus-1000ms deadline, preserving every dialog/action/editor, elapsed, still-deriving, join/drain and no-output assertion. This remains a failed actual-derivation/cancellation trial; it is not relabeled pre-KDF or accepted as a latency sample. All failure and cleanup receipts remain retained privately. The next fresh isolated trial produced the passing controls above.

## Limits and later integration

This is one targeted two-attempt workflow, not a cold/warm cohort or universal ANR/frame/latency guarantee. Heartbeat/frame observations cover only their recorded intervals. Configured biometric/credential authentication, admin restrictions on a real device, API24–28 permission flow, other OEM/providers, oldest physical devices, IME/TalkBack, process death/power loss and provider durability/cleanup remain separate. Immutable strings and provider/library copies retain existing memory-erasure limits. A closed self descriptor does not prove every other process has closed a provider file.

Real SSH, unchanged-server traffic, import/restore UI, privileged fresh restore/reconciliation, client migration and release remain open. Later source/docs integration must preserve delivered metadata/header changes, #143 SSH masking/removal admission and its exact current-record/pin guards. Final integrated-head checks and independent review have their own source scope; documentation or semantically unchanged integration does not create another native run. [The contract](../backend-contracts.md#private-encrypted-server-export-candidate--196), [operations](../operations.md#managed-server-backup-status) and [QA](../qa-test-plan.md#private-encrypted-server-export-controls--196) retain the owning behavior and remaining boundaries.
