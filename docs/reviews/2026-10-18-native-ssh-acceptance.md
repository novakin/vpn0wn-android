# Native Android SSH transport acceptance

Last updated: 2026-10-18 (Europe/Paris).

## Scope and provenance

Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143), which remains open. This record accepts bounded physical Android SSH trials through the production `JschRemoteShellClientFactory` and guarded client: the original password/exec/SFTP trial and the distinct private-key follow-up below. The [transport contract](../backend-contracts.md#concrete-ssh-transport--c11) and [QA controls](../qa-test-plan.md#concrete-ssh-transport-controls--c11) retain the wider feature boundaries. No product source changed for these trials.

Original password/exec/SFTP trial source: clean `13fcc23fd5428fa289b42e9b76f08453c467ac88`, tree `1bc2525394941d37b197c92dfed778f21969c7c8`. Both accepted Build and Peer producers retained matching before/after receipts for all 637 source files, eight reviewed harness inputs and the exact two-file harness source inventory, plus successful final HEAD/tree/status/submodule admission. This runtime source is distinct from the later documentation integration base.

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

At this original checkpoint, native private-key/passphrase authentication remained unverified. The separate follow-up below accepts its named controls without promoting this older password/SFTP/cancellation trial to newer source. The wider algorithm/provider/API/OEM matrix, API24/28 transport/desugaring, IPv6/external paths, blocked connect/banner-write/upload boundaries, production Application/wizard, VPN/lockdown/protection, live mutation/client traffic and release remain outside this original trial. Its host-generated RSA/ECDSA host keys used password authentication, not client private-key authentication. #143 remains open.

## Native private-key acceptance — 2026-10-18

**VERIFIED, dynamic, within this isolated key-authentication scope:** a separate Pixel 7 Pro/API34 trial passed seven native controls, four complementary host postconditions and ten cleanup/preservation checks. These are observers of one bounded trial, not eleven independent transport scenarios. No production source or live server changed.

The clean trial source is `a0d3975104a82e4756441b0139f7e332808bb297`, tree `8068cbb56adbf3f0324dbfcf82bfa28de5f13bf1`. Build and Peer producers retained matching before/after receipts for all 652 source files, eight harness inputs, the exact two-file harness source inventory and both initialized native gitlinks. This is a different transport revision from the original `13fcc23f` password trial; that older trial does not verify password/SFTP/pin-mismatch/cancellation behavior at the newer revision.

The separate ARM64 APK SHA256 is `15f8850102ba5d6e20b98f994661de41ba3811917ac81b12c1f0893775cca105`, 18,680,922 bytes, package `local.vpn0wn.sshkeyprobe.debug`. Actual artifact review verified v2 signing, plain Application, INTERNET only, no application components/shared UID, one self-target instrumentation, production/runner DEX and native package identity, with no host fixture packaged. The initially absent package installed without replacement; its installed APK bytes and ordinary UID matched admission. Direct Build, artifact audit, native client and Peer wrapper all completed with exit 0.

| Native control | Observed boundary |
| --- | --- |
| Traditional RSA private key | RSA2048 plain PEM authenticated through the production factory/guarded transport and returned exact status/stdout/stderr. |
| Encrypted RSA private key | The encrypted representation authenticated with its synthetic passphrase and exact command result. |
| Traditional ECDSA private key | EC P-256 plain PEM authenticated and returned the exact command result. |
| Encrypted ECDSA private key | The encrypted representation authenticated with its synthetic passphrase and exact command result. |
| Wrong RSA passphrase | Cause/output-free INVALID_INPUT, with zero socket-creator callbacks or network connect. |
| Wrong ECDSA passphrase | The same safe refusal, with zero socket-creator callbacks or network connect. |
| Terminal teardown | Successful clients' owned sockets closed, actual JSch reader threads drained, repeated close stayed terminal and post-close exec could not reconnect. |

The fixture constructs an unconnected Java Socket before each client. Wrong-passphrase refusal is therefore before the socket-creator callback/connect, not before construction of every Socket object. Plain/encrypted fixtures share independently corroborated public keys. They exercise traditional JSch encrypted-PEM representations; other key formats, algorithms and sizes remain outside acceptance.

The source-pinned MINA peer used two exact-owned loopback listeners and two owned ADB reverse mappings. Its four postconditions verify both public-key identities observed, no password authentication, drained sessions and stopped fixture workers. Raw callbacks were four for RSA and four for EC, with zero password callbacks and zero remaining sessions. Callback counts include query/signature processing and are not counts of separate successful authentications; exact native command results establish authentication success. No host shell, system SSH daemon, personal server or VPS operation ran.

### Settlement, preservation and review

An initial preflight HOLD found that native `phaseSettled` could precede package stop and PID-absence checks. The retained correction always settles the native client, then checks owned stop and PID absence before COMPLETE, including nonzero/invalid-output paths. Seven injected host controls pass and reject the held runner. The actual successful trial confirms this ordering; it does not execute every failing installed-client path. Earlier HOLD, inputs and failure evidence remain preserved.

The raw native report contains exactly the seven Boolean-true checks, API34 and the native PID, followed by `INSTRUMENTATION_CODE: -1`; its accepted copy matches. Installed-package and separate host admission receipts establish the matching UID. All forty-four device command records meet their expected exits and bounds with empty stderr. The four host postconditions match the accepted host copy. Both Peer listeners, fixture JVM and producer wrapper are absent after completion. Both owned reverses were removed. All ten cleanup checks passed: owned package/path/process absence and sampled boot/package/global/secure/private/foreground/reverse preservation. Personal apps/configs were preserved; sampled equality is not every possible Android state or a post-uninstall private-key inspection.

Retained private evidence: `.artifacts/continuation-20261018/native-ssh-key-prep/`, Build `runs/20261018-083023-b060fa/` and its `device-attempt-01/`, Peer `runs/20261018-084445-e56f89/`. Reviewed fourteen-input pins SHA256: `a82a8d6b656948f92af409483cc080f296738fe13da18bf68f882c171c9ab74f`; executed runner: `745f2507bd307f2ed050cd9b3ccb60ba8addcecef83c57aad294e40fc5c4c904`. Independent ultra source, actual-artifact and actual-runtime reviews are CLEAR. The runtime review hashes to `db4f7852eab946b712afb211d1727873ba187abc0bb87e1f1af2a76c68b6b7bd`; it independently checks source/artifact/native/host bindings, key-pair equivalence, settlement and cleanup. Final documentation-head review/publication remain the delivering PR's gates.

Overall #143 remains **PARTIALLY_ASSESSED**. API24/28 transport/desugaring, provider/OEM and other-key coverage, IPv6/external paths, blocked connect/write/upload failure boundaries, production Application/wizard, progress callbacks, VPN/lockdown/protection, live server mutations/client traffic and release remain separate. No performance, battery, DPI, whole-issue closure, new grade or production-readiness claim follows.
