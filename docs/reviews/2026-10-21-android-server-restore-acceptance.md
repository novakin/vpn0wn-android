# Android and real-server fresh-restore acceptance

Last updated: 2026-10-21 (Europe/Paris).

Scope: B9 joins the native Android fresh-restore workflow to actual script execution on two authorized disposable Ubuntu VMs and retained-client traffic. Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143) and [#196](https://github.com/novakin/vpn0wn-android/issues/196); both remain open. Native/controller, server postconditions and cleanup pass. Independent ultra outcome review passes with no actionable finding.

## Source and artifact

Production source is `12075b73ccd1d4516c440dae2252a092500ddaef`, tree `d6d9110622d3e20f507c6f8926959277c88cd6d7`, identical to merged master `67fa7ee01a6c50a95a708dbffca21ad1405ca118`. B9 reuses the exact B8-built `local.vpn0wn.backupcompareprobe.debug` APK on an isolated API36 x86_64 emulator. Its producer confirms unchanged source/inputs; the static audit checks signing, package identity, production owners, corrected native instrumentation and all twelve packaged server-script bytes. B9 changes host fixture/cohort inputs; it does not claim a new APK build.

| Binding | SHA256 |
| --- | --- |
| Reused B8 APK | `ea58d99f73eb32bcb57868cba9b93f4afc682ee636cf99d88476baef3599a1fb` |
| B8 producer input manifest | `f29fb3a895cc8b49570d892f6637d5a5ede35bc6314f66b26ed5a649c9c63bd4` |
| B9 runtime input manifest | `579deadfc623f4b397b7aeed9967b925cdb94c040c868b5e5c015c6c796f22c9` |
| B9 independent input/artifact-reuse/runtime review | `0c09dd1f2511af32cf56c8bcb56f80b25e054ea29300ec2ab932dc8c48eaf49c` |
| B9 independent outcome review | `44caef57326f1d4ebf8a90506ea5d64c3e5a50ba99a2ad2368d0d7cd37fc14a9` |

The production Application, Fragment/ViewModel, archive codec, JSch transport, stores and Android Keystore remain intact. The source snapshot is acquired by the VM fixture and encrypted through the production codec; native source export is not exercised. A temporary synthetic ECDSA SSH credential, independently checked destination host key and one owned ADB reverse connect Android to the real destination.

## Observed controls

All five native phases and thirteen checks pass:

| Boundary | Observation |
| --- | --- |
| Seed and inspect | Open the saved source and select the encrypted archive through actual DocumentsUI. Configured-PIN authentication precedes the application document read. |
| Detect and CHECK | Real JSch Detect identifies the fresh destination, saves encrypted access and reopens it through production stores. Real CHECK returns the expected server identity, endpoint, port, CIDR, DNS and active/revoked counts. |
| Confirm and verify | A second configured-PIN authentication and one explicit confirmation run the real restore script. Production VERIFY succeeds. Destination metadata persists and no pending restore admission remains. |
| Native preservation | Protected private files remain unchanged; the test creates no Android tunnel or Downloads output. Private fixtures are removed and JSch threads drain. |

The run records two successful PIN authentications, one picker visit, one application document read and zero picker-provider reads. The provider shares the test UID; these observations do not establish cross-UID document grants or biometric acceptance.

Seven host phases pass, including preparation and original-client traffic. Independent host checks confirm the destination service, server key and active peer, ordered nonempty configuration lines with only the selected endpoint marker changed, and exact revoked history. A fresh interface using the retained client's original key establishes a fresh handshake, positive counters and the exact destination HTTP response. A fresh snapshot-retired client remains denied. The source configuration/history remain unchanged; Android remote scratch is absent and its SSH sessions are drained.

All thirteen Android cleanup predicates pass with no errors and actual launcher/backend exits 0. Package, process, service, temporary PIN and reverse mapping are removed; baseline, lock state, shared owners and source are preserved. Both VM cleanups pass, original SSH authorized-key bytes are restored and both exact VM processes stop. Installed packages, retained history and uninstall recovery backups remain on the disposable disks; full disk-baseline restoration and secret erasure are not claimed. Physical phone configuration and production servers were not changed.

## Preserved failures and evidence

B6 failed VM admission because daemonized QEMU changes its working directory; separate exact-owner reconciliation stopped that VM. B7 failed a DocumentsUI click before authentication or Android SSH and its native teardown timed out; parent cleanup restored the environment, while its native-output flag remains false. B8 passed selection, PIN and Detect but failed CHECK's expected-plan equality: the fixture inherited the installer's two-DNS default while native assertions expected one address. B8 cleanup passed. These outcomes remain failed.

B8 corrected only private picker/cleanup handling. B9 explicitly supplies the fixture's expected DNS and uses separate operation, retained-history, key and guest identifiers. No product behavior changed and no failed receipt was rewritten.

Private evidence remains under `.artifacts/continuation-20261021-android-vm-restore-196-b9/` and `D:/Vpn0wn-Acceptance-20261021/android-guests/Vpn0wn_Backup196_API36_RealVM_B9_20261021`. APK provenance remains in the original B8 build directory. Credentials, archives and raw host evidence are excluded from source distribution.

## Limits

This provides combined Android/JSch/real-server fresh-restore evidence, beyond the separate [B5 synthetic-peer workflow](2026-10-21-native-fresh-restore-acceptance.md) and [R5 CLI restore/traffic](2026-10-21-disposable-server-restore-acceptance.md). Their passed scopes, including cancellation/reconciliation and same-server CLI restoration, remain valid and were not repeated.

The client runs inside a VM namespace with its endpoint redirected to the guest-local peer and its route narrowed to the test HTTP address. This proves retained identity and authenticated traffic after Android-driven restoration, not unchanged full-profile Internet routing or phone VPN traffic. Native source export, Android same-server rollback, other C12 mutations, real transport-loss/process-death recovery, public DNS cutover, broader providers/OEM/accessibility and shipping-artifact acceptance remain separate issue gates. This record does not establish complete issue acceptance or production readiness.
