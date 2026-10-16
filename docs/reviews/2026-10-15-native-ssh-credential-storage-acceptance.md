# Native saved-SSH-credential storage acceptance

Last updated: 2026-10-15 (Europe/Paris).

## Scope and provenance

Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143), which remains open. This record documents a bounded Android storage test of `VpsCredentialFile`, `VpsCredentialRecords` and `VpsServerStore`; it does not accept the complete SSH/provisioning feature. The [credential contract](../backend-contracts.md#saved-ssh-credentials--c11) and [QA controls](../qa-test-plan.md#protected-ssh-credential-controls--c11) retain their owners and wider gates.

The source was clean `aa5b7791df39c28f4fab9be24ecfc5dcff2123bf`, tree `3c6605f581c716c3a60660b5d3eff6aa1b2306cc`, identical to integrated master `fd6af10fc9f58b80b1e759737ae26b4d6c7c5186`. An ignored debug acceptance variant added self-target platform instrumentation under `local.vpn0wn.credentialsprobe.debug`, replacing production startup with plain `android.app.Application` and removing inherited components and permissions. The packaged manifest has no Activity, service, receiver, provider or network permission. This isolates production storage owners from the unfinished UI/transport and from other app data.

The rebuilt APK SHA256 is `1521385b0aa55888cb3ef7322ab654965d76941b629ff0e8128ad87a8e95b470`. The retained packaged-manifest, actual runner DEX definition and successful APK v2-signature audit bind the runner and isolated identity to that artifact; its minimum/target APIs are 24/36. The debug certificate SHA256 is `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. Both guests executed this same APK, with no product-source change between runs.

Private retained evidence root: `.artifacts/continuation-20261015/credentials-probe/runs/20261015-211421-bb0578`. It contains source/submodule identity, all five build-input hashes, APK/manifest/signature/DEX audits, per-guest admission/results and cleanup receipts. The runner source SHA256 is `ed167ed6aa3b7b5e0f11b816423849ca23295a1bacc99257d3c118c78fb99a7b`. The original build-time README is preserved byte-for-byte as `harness-readme-at-build.md`; the current ignored README records later results without changing that historical input.

## Executed controls

Fresh owned headless Google APIs x86_64 guests used API 28 and API 24. Both admitted an absent isolated package and a distinct recorded boot ID. The same ADB serial was reused sequentially; it does not identify the guests independently. Each run returned the correct package/SDK, `status=PASS`, all seven checks true and `INSTRUMENTATION_CODE: -1`.

| Check | Observed boundary on both APIs |
| --- | --- |
| `freshOwnedStorage` | Isolated UID, initially absent named Keystore alias/ciphertext/metadata file, and the expected native `noBackupFilesDir` path. |
| `nativeKeystoreRoundTrip` | Synthetic password, PEM/passphrase and required pin reload through a newly constructed store/file owner; the native AES key reports no exportable encoded bytes. |
| `privateNoBackupCiphertext` | Native `lstat` reports an owned regular `0600` file under `noBackupFilesDir`; named synthetic plaintext needles are absent from ciphertext and non-secret metadata. |
| `nativeAtomicReplacement` | A normal successful replacement changes the GCM nonce and file inode, retains private mode, preserves the other credential and reloads the replacement. This is not an interrupted-write or power-loss test. |
| `unrelatedCredentialAndMetadataPreserved` | Clearing one credential leaves the other credential, fleet records and a synthetic metadata sentinel intact; the cleared credential can be saved again. |
| `tamperFailsWithoutOverwrite` | A changed ciphertext byte produces `CORRUPT`; both load and attempted replacement refuse it without changing ciphertext or metadata. Restoring the exact owned original permits reload. |
| `lostKeyFailsWithoutOverwrite` | Deleting the owned alias produces `KEY_UNAVAILABLE`; load and attempted replacement preserve ciphertext/metadata, do not recreate the alias and retain non-secret fleet/sentinel records. |

The test uses real AndroidKeyStore, AES-GCM, native file attributes/rename and DataStore. The synthetic key text is deliberately unusable for SSH authentication; nothing connects to a host. Reload occurs within the same app process through fresh store/file objects, not after a process restart.

Decisive raw evidence SHA256 values:

| Evidence | SHA256 |
| --- | --- |
| `api28-instrumentation-result.txt` | `96dd7674f0f4e80020ada6eb192fc5bc27d4e564e0f092d0c84172ed6f361ce5` |
| `api24-instrumentation-result.txt` | `78f9fcc168f612fb032ca77516f7d0b21e06d5bc1f22ff0f6d09bbd7ee175385` |
| `harness-inputs.json` | `4dd05e4d27dd514f0683d00c7b1d3bcc0e392f5afb2c4a2590dd47f9960d0597` |
| `termination-receipt.json` | `f582ea1db64cff487485afa932b086ef63c8b7e6eee76bd308bca0f275cdb5c1` |

## Earlier failure and cleanup

The first API-28 attempt, retained separately under `runs/20261015-210303-47b1f2`, failed to launch the runner with `ClassNotFoundException` and `INSTRUMENTATION_CODE: 0`. It executed no credential test. The ignored init script was corrected to register `debug.kotlin.srcDir` immediately after the Android application plugin applied, before source inputs were finalized. The rebuilt artifact's actual DEX definition and later successful native runs establish the correction; a DSL receipt or manifest reference alone would not.

The API-28 cleanup receipt records successful uninstall and absent package path/list before termination. API24's uninstall receipt reports `Success`. The final receipt records both owned guests terminated, all recorded emulator processes exited and the owned serial absent at 2026-10-15 21:33:42 Europe/Paris. Uninstall owns remaining private-file/key removal; no separate inaccessible post-uninstall private-path inspection is claimed. Owned guest disks, failed-attempt evidence and current run receipts remain retained. No personal phone, original application/configuration, clipboard, VPN, root tools or live server was operated by this probe.

## Acceptance limits

These seven controls pass on the two recorded emulator/API environments and supplied debug acceptance artifact. They add API-24/28 storage runtime proof to the earlier JVM/source controls without replacing those earlier scopes or completing #143.

Unverified boundaries include production Application/UI integration, credential capture/reuse/removal and fingerprint entry, Android JSch crypto/desugaring/network/exec/SFTP/cancellation, backup/device transfer, process restart and Keystore lifecycle across restart/uninstall/reinstall, crash/power-loss/directory-sync durability, physical/OEM behavior, hardware-backed key quality, privileged extraction and complete memory erasure. The configured `noBackupFilesDir` path and nonexportable key bytes do not prove those boundaries. No transport, native VPN, UI, release, live-server, DPI, speed or battery acceptance follows. Final documentation review/publication gates belong to the delivering PR.
