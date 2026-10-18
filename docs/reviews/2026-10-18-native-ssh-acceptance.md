# Native Android SSH transport acceptance

Last updated: 2026-10-18 (Europe/Paris).

## Scope and provenance

Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143), which remains open. This record accepts a bounded physical Android SSH transport trial through the production `JschRemoteShellClientFactory` and guarded client. The [transport contract](../backend-contracts.md#concrete-ssh-transport--c11) and [QA controls](../qa-test-plan.md#concrete-ssh-transport-controls--c11) retain the wider feature boundaries. No product source changed for this trial.

Executed source: clean `13fcc23fd5428fa289b42e9b76f08453c467ac88`, tree `1bc2525394941d37b197c92dfed778f21969c7c8`. Both accepted Build and Peer producers retained matching before/after receipts for all 637 source files, eight reviewed harness inputs and the exact two-file harness source inventory, plus successful final HEAD/tree/status/submodule admission. This runtime source is distinct from the later documentation integration base.

The separate arm64-v8a debug acceptance APK has SHA256 `4bb03a0a039338b076ca257ad11d9ad8e64cfa0625d0fd7578f31b2a1a58e9ad`. Its audited package is `local.vpn0wn.sshprobe.debug`, minimum/target APIs 24/36, with plain `android.app.Application`, INTERNET only, no Activity/service/receiver/provider or shared UID, and one self-target instrumentation. APK v2 verification passed with debug certificate SHA256 `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. The audit checked actual production/runner DEX bodies, native package identity and absence of the JVM-only MINA peer/runner. Installed APK bytes matched the admitted artifact; this is not a production-app or release-artifact acceptance.

Private retained evidence root: `.artifacts/continuation-20261018/native-ssh-prep/`. Accepted Build `runs/20261018-021804-8454c4` owns the artifact/audit and `device-attempt-01`; accepted Peer `runs/20261018-022417-883734` owns the independent host result and successful producer exit. The instrumented physical device was Pixel 7 Pro, Android API34. Raw device/host reports and identifiers remain outside tracked documentation.

The runner used the production factory's existing socket seam, numeric IPv4 loopback and four owned ADB reverse mappings to an authenticated synthetic host fixture. JSch 2.28.8 ran on Android; unchanged test-only MINA SSHD 2.20.0 generated RSA/ECDSA host keys and supplied fixed exec/SFTP responses on the host. Its temporary POSIX filesystem and in-memory credentials were disposable. No system SSH daemon, remote shell, personal server or VPN traffic was used.

## Executed controls

Native instrumentation returned `passed=true`, all seven required checks true and `INSTRUMENTATION_CODE: -1`. Its host command completed with exit zero within the external 90-second deadline. The independent peer returned `passed=true`, all six required postconditions true, then its producer completed with exit zero. Native and host receipts are separate observations of the same bounded trial, not thirteen independent transport scenarios.

| Native check | Observed boundary |
| --- | --- |
| `nativeRsaPinnedExecAndSeparateStatusOutputs` | Real pinned RSA-host-key/password connection; exact separate stdout/stderr and exit statuses 0 and 17 through one reused client. |
| `nativeSftpReturnedSuccess` | Real production SFTP upload returned success for the exact synthetic UTF-8 payload and requested mode 0700. |
| `nativeEcdsaPinnedExec` | A separate ECDSA-only host-key peer completed pinned password-authenticated exec. |
| `wrongPinRejected` | Safe wrong-pin failure retained the exact independently supplied actual host-key fingerprint; an unreachable peer cannot satisfy that check. |
| `wrongPasswordRejectedSafely` | Wrong password failed with the required sanitized, cause-free error and no retained synthetic secret marker. |
| `enteredAuthenticatedCommandCancellationSettled` | An authenticated command entered the actual production polling boundary; cancellation settled with the exact owned cancellation object and stopped captured reader threads. |
| `ownedSocketsAndReaderThreadsStopped` | Every owned raw socket closed, all JSch reader threads stopped, repeated close remained terminal and post-close exec could not allocate another socket. |

| Independent host postcondition | Observed boundary |
| --- | --- |
| `exactSftpUtf8And0700` | Uploaded bytes matched the expected UTF-8 payload; POSIX permissions were exactly owner read/write/execute. |
| `cancelCommandEntered` | The dedicated authenticated hung-command fixture actually entered. |
| `pinMismatchDidNotAuthenticate` | The dedicated wrong-pin peer saw zero authentication and zero commands. |
| `expectedAuthenticationAttempts` | Exact expected password-attempt counts matched; every peer saw zero private-key attempts. |
| `allSessionsDrained` | All fixture sessions drained before peer completion. |
| `fixtureWorkersStopped` | All fixture owners closed and their workers stopped before successful producer completion. |

Decisive retained evidence hashes:

| Evidence | SHA256 |
| --- | --- |
| Build `audit/acceptance.json` | `c01dbeaff6b4887349d7d538c3cd46c73e2dc3b995899dbc37f671cfca1ee08b` |
| Build `device-attempt-01/native.stdout.txt` | `2788cd8472c9cde13d508bca28ba2ff999264220864adf5a1d42afa58b1925cf` |
| Peer `peer/peer-result.json` | `87ddfa4d50334db30694b46adeffec2a39d38fa4e41fc184f5fe5957dbffe86f` |
| Build `device-attempt-01/cleanup.json` | `c7835f773e2ca3ea6299524499238354aae13e51fd95367990c846fa78ddd015` |
| Build `device-attempt-01/acceptance.json` | `2196fff5a6fba45eca48fddb7cc2516cd6b026dd2940957834ffb66bf295deb9` |

## Earlier failure, correction and cleanup

The first Build/audit, `runs/20261018-015255-630aae`, completed with the original host input. Its subsequent Peer producer, `runs/20261018-021217-0121dc`, failed `compileDebugUnitTestKotlin` because two `Files.readString` calls and one `Files.writeString` call were unavailable in that Android unit-compilation API. No peer listener or native SSH command ran in that failed attempt; the earlier Build/audit does not accept the corrected eight-input harness.

The host-only correction uses `String(Files.readAllBytes(...), Charsets.UTF_8)` and `Files.write(..., text.toByteArray(Charsets.UTF_8), CREATE_NEW)`. Independent ultra re-review confirmed exactly those three replacements, preserved UTF-8/no-overwrite behavior and unchanged transport assertions, session/worker drain and scratch ownership. Corrected host SHA256: `63cd6bdfcb52daab2129a015f7df0069819f09a7597344f6fa4659fdea7cfd6d`. Fresh accepted Build and Peer receipts share that corrected eight-input inventory. The one-off runner and artifact/build inputs also received separate independent ultra preflight; static review did not substitute for compilation or execution.

Both the failed attempt and accepted trial report ten cleanup/preservation checks true and an empty error list. They confirm absent owned package path/list/process, unchanged boot, complete package inventory, global/secure settings, the fixed sampled private-file hashes, foreground fields and reverse mappings. The accepted runner SHA256 is `c4e8c0c00badac519d510bb2131b30e39e134fa2385722d9d63be22c47004ec0`. It installed only an initially absent isolated package without replacement, removed only its owned reverse mappings and installation, and retained full before/after evidence. Root access served fixed read-only preservation hashes; no root tools, settings, personal app/configuration or live server mutation was part of this trial. Sampled preservation is not proof of every possible device state.

## Acceptance limits

This trial adds physical API34 pinned-password SSH exec/SFTP, negative-authentication and entered-command cancellation proof. The existing JVM controls and separate [API24/28 credential-storage record](2026-10-15-native-ssh-credential-storage-acceptance.md) retain their own scope; storage results do not establish transport compatibility on those APIs.

Still open: native private-key/passphrase authentication and the wider algorithm/provider/API/OEM matrix; API24/28 transport/desugaring; IPv6 and external network paths; blocked connect/banner-write/upload cancellation and deadlines; production Application/wizard, credential capture/reuse and UI integration; ordinary VPN/lockdown routing and socket-protection behavior; live SSH/VPS installation, mutation and client-traffic postconditions. This probe used host-generated RSA/ECDSA host keys with password authentication, not client private-key authentication. It establishes no live-server, native VPN, release, DPI, speed or battery acceptance. #143 remains open; final documentation review and publication belong to the delivering PR.
