# Android and real-server revocation acceptance

Last updated: 2026-10-21 (Europe/Paris).

Scope: C1 exercises native Refresh, Revoke Cancel and one confirmed Revoke through production Android owners and real JSch/scripts on an authorized disposable Ubuntu VM. Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143), which remains open. Native/controller, server postconditions, fresh client controls and owned cleanup pass. Independent ultra outcome review passes with no actionable findings.

## Source and artifact

Production source is `b675b6460e362847910836b90a5d47bc266ae1cc`, tree `e1218b6883ea93e1eba368546fa77c94e9f1eac9`, identical to merged master `05085129c349f40960b3f893b065f3c9fe6d9aae`. The separately built `local.vpn0wn.revokeprobe.debug` APK runs on one isolated API36 x86_64 emulator. Its producer confirms unchanged source/inputs; static artifact audit and independent ultra review verify signing, package isolation, production owners, native Revoke instrumentation, x86_64 libraries and all twelve packaged server-script bytes. No product behavior changed.

| Binding | SHA256 |
| --- | --- |
| C1 APK | `85fca5bca2d8a687e4351473da05aee048543cea70e2370492770c6a38d4519e` |
| Input manifest | `dec58d6c4c0cf60ab16412ca24136d4d2f4862ac88de6d9a4e0a27fce595eab7` |
| Independent runtime/artifact review | `2f6cbda10c0d401bccc802b3fe2bb6de0aee0c1d3844195a56659a86aee391be` |
| Independent outcome review | `896e4ae990a8dc1c7a8f3299ab05c091926245c7d3306082c6faa5619b59a4d7` |

Fixture setup creates two active clients and earlier revoked history, verifies both original clients' traffic, then seeds actual server records, encrypted SSH access and retained client profiles through production stores. A temporary ECDSA credential, independently checked host identity and one owned ADB reverse connect Android to the destination VM. Fixture setup is not native installation or user-creation acceptance.

## Observed controls

All three native phases and thirteen checks pass:

| Boundary | Observation |
| --- | --- |
| Seed and Refresh | Production Fragment/ViewModel, server store, Android Keystore and encrypted profile vault open the actual fixture. Native Refresh loads the real server before victim selection. |
| Cancel | Native Revoke preparation displays the exact operation identity. Cancel preserves remote config/history, encrypted profile-vault bytes and absence of a pending removal. Production read-only backup calls provide the before/after observations; these calls are not native backup-export acceptance. |
| Confirm and settle | A new confirmation uses a distinct operation UUID. The result matches the exact server/client identity and archive basename; configured/live victim absence and earlier history are checked. Only the victim's retained profile is deleted, a fresh canonical store reopens the surviving control profile within the same process, the pending fence clears and native success feedback appears. |

Revoke uses its existing confirmation dialog, without Keyguard authentication. The reused controller configures and later removes an isolated synthetic PIN; no PIN-authentication acceptance is claimed. Functional actions invoke actual native views, without replacing production owners. This establishes orchestration, not visual or accessibility acceptance.

Eight host phases pass, including preparation and both original-client controls. Independent host verification correlates the native operation UUID and exact archive, confirms ordered nonempty server configuration lines equal the original minus only the victim block, and checks earlier revoked history bytes remain unchanged. The new archive contains the original server peer block, not a generated private client profile. No archive belongs to the cancelled operation.

A fresh client interface using the revoked victim's original identity times out without a handshake. A separate fresh interface using the unchanged control identity establishes a fresh handshake, positive counters and the exact token-authenticated HTTP response. Android remote scratch is absent and its SSH sessions drain.

All thirteen Android cleanup predicates pass with no errors and actual launcher/backend exits 0. The isolated package, process, service, native outputs, temporary PIN and reverse mapping are removed; baseline, lock state, shared owners and source remain preserved. Destination VM cleanup passes, original SSH authorized-key bytes are restored and the exact VM process stops. WireGuard packages, retained managed history and product uninstall recovery backups remain on disposable disk; full disk restoration and secret erasure are not claimed. The source VM stays stopped; physical phone configuration and production servers were not changed.

## Evidence and limits

Private evidence remains under `.artifacts/continuation-20261021-native-revoke-143-c1/` and `D:/Vpn0wn-Acceptance-20261021/android-guests/Vpn0wn_Revoke143_API36_C1_20261021`. APK provenance is in `runs/20261021-134950-build-d7eba57f`. Retained quiet command receipts record exit 0 for build, audit, VM prepare, controller, VM verify and VM cleanup. Admission gates close after the attempt. Credentials, private profiles and raw host evidence stay outside source distribution.

This adds combined Android/JSch/real-server revocation evidence alongside the separate [B9 fresh-restore cohort](2026-10-21-android-server-restore-acceptance.md) and [A8/A9 profile-access cohorts](2026-10-20-native-retained-profile-acceptance.md). Their accepted scopes remain unchanged.

Client helpers preserve private identities but redirect endpoint/routing to guest-local HTTP. They do not prove unchanged full-profile Internet routing or phone VPN traffic. Native installation/create/rotation/removal, real transport-loss/process-death reconciliation, public DNS, broader physical/OEM/provider/accessibility and shipping acceptance remain separate issue gates. This record establishes neither complete #143 acceptance nor production readiness.
