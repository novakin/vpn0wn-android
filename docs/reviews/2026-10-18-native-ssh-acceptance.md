# Native Android SSH transport acceptance

Last updated: 2026-10-18 (Europe/Paris).

## Scope and provenance

Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143), which remains open. This record distinguishes the original physical API34 password/exec/SFTP and private-key trials, the failed API24 D2 diagnostic, and the later API24 E1/API28 F1 and API25 G2 compatibility trials below. Each section retains its own source, artifact, observations and limits. The [transport contract](../backend-contracts.md#concrete-ssh-transport--c11) and [QA controls](../qa-test-plan.md#concrete-ssh-transport-controls--c11) retain the wider feature boundaries. The original physical trials changed no product source; E1/F1 exercise the separately implemented API24/25 ECDSA compatibility source.

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

These limits describe the original API34 password cohort. The later E1/F1 section records the additional API24/28 compatibility scope; it does not change these earlier observations.

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

At this API34 private-key checkpoint, #143 remained **PARTIALLY_ASSESSED**. API24/28 transport/desugaring, provider/OEM and other-key coverage, IPv6/external paths, blocked connect/write/upload failure boundaries, production Application/wizard, progress callbacks, VPN/lockdown/protection, live server mutations/client traffic and release remained separate. The later E1/F1 section adds only its named scope. No performance, battery, DPI, whole-issue closure, new grade or production-readiness claim follows.

## API24 disconnect diagnostic D2 — 2026-10-18

**FAIL, with bounded diagnostic evidence accepted.** Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143) and [PR #280](https://github.com/novakin/vpn0wn-android/pull/280); #143 remains open. This separate x86_64/API24 emulator trial used source `0aad2d3eb03d09f65f3a223cedb0233fdf2f51dc`, tree `67ea170dd61a415878e44a3e8a87ffd2c7b91bb0`. The isolated `local.vpn0wn.sshprobe.debug` APK SHA256 is `1198a363af7f10f30c2e17c0088b25fd52ab5b9c8122d4ad7c67c1e1a4f45662`, 19,028,214 bytes. Its installed bytes and ordinary separate UID matched admission. Current-source and installed-artifact checks do not promote the older physical password/private-key trials or accept the production app.

Controller and fresh Peer producers both actually exited `1`. Native ADB host exit was `0`, its sole instrumentation code was `0`, and its report was `passed:false`. Only `nativeRsaPinnedExecAndSeparateStatusOutputs` and `nativeSftpReturnedSuccess` were true. ECDSA reached FACTORY, FACTORY_RETURNED and EXECUTE without OUTPUT_ASSERTION. Its primary failure remained ProvisioningException / COMMAND_FAILED / SSH_OPERATION_FAILED. Both closes and POST_CLOSE_PASSED completed, with null cleanup failure, closed owned socket and stopped observed readers. All three post-failure ECDSA initialization probes returned INITIALIZED; initialization is not successful key exchange, host-key verification or an ECDSA operation.

The ECDSA peer recorded `seen:true`, reason `3`, category `NO_SUCH_ALGORITHM`. Its finite public-callback classifier matched an allowlisted direct/wrapped NoSuchAlgorithmException description prefix; no raw description, description hash, requested algorithm or stack was retained. ECDSA password/key attempts were zero and command entry false. EXECUTE includes connection setup, so this establishes failure before the output assertion and before observed server user authentication/command entry, not before host-key authentication or at a measured key-exchange step. The exact missing algorithm, failing call site, provider mechanism and underlying cause remain unobserved.

Separate offline inspection of pinned JSch 2.28.8 identifies a compatibility candidate: `SignatureECDSAN.setPubKey` requests `AlgorithmParameters.getInstance("EC")`, while the official SDK36.1 source's historical support table lists EC AlgorithmParameters from API26. The post-failure probes call only construction and `init`, not `setPubKey`. The hash-bound static report `.artifacts/continuation-20261018/ssh-api24-algorithm-static.json` has SHA256 `8bcf425abc26ef23735ca6e52f9bed08f17a24820b6f779ed853a0b90b187f65`. This is static library/support-table evidence, not an API24 provider-registry measurement or an observed D2 call site/request; it does not establish that this candidate caused D2.

Host observations reached AUTH_ASSERTIONS after `drainCompleted:true`. RSA had one password attempt and command entry true; ECDSA/cancel/pin had zero password attempts and no command entry. Every peer had zero key attempts and active sessions. The retained Peer stack points to `NativeSshPeer.kt:51`, the original two-RSA-password-attempt assertion: the native sequence had already stopped before its later wrong-password case. This downstream Peer failure is distinct from the missing-algorithm mechanism; six full-trial host success postconditions were not accepted.

All 118 recorded device commands settled with their expected exits, zero command-cleanup failures and 119 empty retained stderr files. All six guest cleanup and five shutdown checks passed: owned package/process/reverses removed, sampled boot/packages/settings restored, owned emulator/launcher/listeners absent, shared owners preserved and userdata retained. Native stop/PID absence preceded COMPLETE; that marker proves settlement only. Retained post-settlement Windows/WSL absence checks corroborate teardown. The 655 source bindings, 47 preparation inputs, current/original artifact31 freezes, earlier 23/21 inputs and 25 protected prior evidence files remained unchanged. Sampled preservation and retained absence observations do not prove every possible state.

Private evidence root: `.artifacts/continuation-20261018/native-ssh-api24-disconnect-prep/`, Build `runs/20261018-105647-45931f`, Peer `runs/20261018-111844-3da2ae`, guest `guests/api24-d2`. Runtime pins bind 385 guest files, and the outcome binds 24 external receipts. Different independent actual-failure ultra review is CLEAR for the truthful failed-run record, not runtime success; all required commands were settled.

| Evidence | SHA256 |
| --- | --- |
| Runtime input pins | `3fdbd13294a633f1a89af3a37a80c6558d32f1b29ca1117d826c13b07baab84a` |
| Diagnostic outcome | `bbd370fe6d9e13e2818938b83b4a4ad8e515799c0635614dc0334e4850a69d6b` |
| Independent actual-failure review | `89a2384a5a52768f228635bb0ad7675a50f35acde320afed77ff54e36585a6c0` |

Earlier API24 A1/D1 failures retain their original unknown-cause scopes; D2 does not retroactively classify them. API28, wider key/provider/OEM coverage, production UI, VPN/live-server/traffic and release remain unaccepted by this trial. The separate CI callback-test contract correction and its JVM/CI gates do not repair this native failure. No new runtime, product fix, grade or full-issue acceptance follows from this documentation record.

## API24 E1 and API28 F1 ECDSA compatibility — 2026-10-18

**PASS within the named isolated transport matrix.** Both one-shot x86_64 Google APIs emulator trials use signed source `54399d3286e807a78a94b558bae748c9f08b7fe0`, tree `c5965629a6bd528c308e9aae4a2555e79fe0bf9d`. The API24/25 compatibility owner supplies session-local host verifiers and a client-owned EC signer; API26+ retains its ordinary JSch paths. E1 executes API24; F1 executes API28. API25 is not a native-tested platform here. Success of these changed-source trials does not identify D2's missing algorithm, call site or provider mechanism.

One fresh E1 Build produced the 19,028,214-byte APK SHA256 `50e22ffa39d6fd17e4fc083d72179139b49941968711c1addf43442fb359708b`. The ordinary APK audit and different actual-artifact ultra review passed. The artifact remains in E1 Build `runs/20261018-121900-57480a`; F1 reused those exact original bytes and receipts after different preparation/admission review, without another Build, copied APK or audit rerun. Both installations matched the admitted bytes under separate ordinary app UIDs. The isolated package has INTERNET only, plain `android.app.Application` and one self-target instrumentation; these are neither production-Application nor release/shrinking acceptance.

| Cohort | Retained evidence | Result |
| --- | --- | --- |
| API24 E1 | `native-ssh-api24-ecdsa-prep/guests/api24-e1`; Peer `runs/20261018-123621-988949` | Fifteen native checks and six host postconditions true; controller and Peer direct host/inner exits 0. |
| API28 F1 | `native-ssh-api28-ecdsa-prep/guests/api28-f1`; Peer `runs/20261018-125736-888d81` | Same fifteen/six checks true with the original E1 APK; controller and Peer direct host/inner exits 0. |

Paths above are relative to ignored `.artifacts/continuation-20261018/`. Both raw native reports have exactly fifteen true keys, `passed:true`, their admitted API/PID and `INSTRUMENTATION_CODE: -1`; accepted copies are identical. The matrix retains RSA-host/password exec with separate status/stdout/stderr, exact SFTP success, P-256 host/password exec, wrong pin/password refusal, authenticated-command cancellation and terminal socket/reader settlement. It adds P-384/P-521 host/password execution and all six combinations of P-256/P-384/P-521 traditional EC PEM with plain/encrypted private-key authentication. These trials do not add API24/28 RSA-private-key acceptance or another key format.

The complementary host result verifies exact UTF-8 upload/mode `0700`, entered cancellation, no wrong-pin authentication/command, expected authentication attempts, drained sessions and stopped workers. Each EC peer retains one password attempt from its explicit password case, excluding password fallback for its two successful key cases. Public-key callback counts include query/signature processing; they are not separate successful-authentication counts. The fixture uses only numeric IPv4 loopback and six owned reverse mappings, without a host shell, system SSH daemon, VPS or VPN traffic.

The controllers rehashed 45 API24 or 33 API28 saved image inputs before launch, then verified actual API/fingerprint/ABI, fresh AVD/serial/port ownership, package absence, ordinary UID and installed APK bytes before instrumentation. Fresh Peer execution followed real device readiness. Native stop/PID absence preceded exact `COMPLETE\n`, then host result, completed Peer receipt and completed controller receipt. Six fixed synthetic-private-key markers appear in ordinary command receipts while original execution arguments remain unchanged. No failed native attempt or retry occurred in either cohort; earlier setup/static/test-fixture and metadata-comparison failures remain retained in their own evidence.

All six cleanup checks passed: package/process absence and sampled boot/packages/settings/reverses preservation. All five shutdown checks passed: owned backend/launcher/ports absent, shared owners preserved and userdata retained. E1 additionally retains its scoped post-run Windows process/port snapshot; an initial timestamp-type comparison defect and corrected read-only snapshot remain separate from the already passing controller result. F1 absence/shared-owner conclusions use controller-recorded shutdown evidence only; no supplemental post-trial live query was made. Neither sampled equality nor a retained snapshot proves every possible state.

The 657 tracked input hashes and both producer source/harness before/after vectors match runtime source `54399d3`. E1's 270 preparation inputs, 54 artifact inputs, 431 runtime files and D2's 47 inputs/17 preservation references remain frozen. F1's closed 894-entry preparation manifest was independently reviewed before its exact status/admission-only delta; its 494-file runtime freeze retains that history. Later signed integration changes documentation only: the two production and two test blobs remain byte-identical to the tested source. Historical tracked inputs are verified against the `54399d3` Git tree when current documentation advances; no current-head 657-file equality or rebuilt final-head APK is claimed.

| Independent evidence | SHA256 |
| --- | --- |
| E1 actual-artifact ultra | `1b86685a90e3b5d9114f12c90480b9bddbdcb75751a41ef100aa5c56056c63ff` |
| E1 runtime freeze, 431 files | `1281f3101a1fb5a965edfae2385801e3788a233ea476af3ca83831d4135f1e1f` |
| E1 actual-runtime ultra, 52 checks | `4cf9654a5b4a141906814aba4e4d09d7a516eadd27a2aa36c82b66a4e6d6e023` |
| F1 runtime freeze, 494 files | `c8c044750907be88fad524ea661cc7dc566aaf36828620136a237fe699437b58` |
| F1 actual-runtime ultra, 50 checks | `c8039dd33ea670e5f46d0589fc71aadb2701c092ef226e8461736998576be409` |

Different source/preparation, artifact and actual-runtime reviews are CLEAR within their recorded scopes. Final integrated-head source/documentation/body review, CI and publication remain the delivering PR's gates. #143 remains open for API25/physical/OEM and wider key/provider/network coverage, IPv6/external paths, blocked connect/write/upload boundaries, production Application/wizard/progress/profile integration, VPN/lockdown/protection, actual disposable-server mutation/client traffic and release. No performance, battery, DPI, production-readiness or whole-issue acceptance follows.

## API25 G1 host failure and G2 compatibility — 2026-10-18

**G2 PASS within one named isolated API25 transport cohort; G1 remains FAILED before native execution.** Different actual-runtime ultra review accepts G2 with no material findings. The Google APIs x86_64/API25 revision-18 guest reuses the original E1 source `54399d3286e807a78a94b558bae748c9f08b7fe0`, tree `c5965629a6bd528c308e9aae4a2555e79fe0bf9d`, and unchanged 19,028,214-byte APK SHA256 `50e22ffa39d6fd17e4fc083d72179139b49941968711c1addf43442fb359708b`. Installed bytes and ordinary separate UID matched admission. No new APK, Build, artifact-audit rerun or production/fixture source change occurred. The isolated plain Application/self-target instrumentation remains separate from production-Application, wizard and release acceptance.

The sole fresh G2 Peer run, `native-ssh-api25-ecdsa-g2-prep/runs/20261018-183550-f7b6ac`, follows real guest readiness. Direct controller and Peer hosts and inner Gradle/quiet execution all exit zero. The raw/accepted native reports match: exactly fifteen true controls, `passed:true` and one terminal `INSTRUMENTATION_CODE: -1`. The unchanged matrix covers RSA-host/password exec with separate output/nonzero status, SFTP, P-256/P-384/P-521 host execution, six plain/encrypted traditional EC private-key cases, wrong-pin/password refusal, entered authenticated-command cancellation and owned socket/reader teardown. It adds no API25 RSA-private-key or other-format proof. Six complementary host postconditions pass, including exact UTF-8/0700 upload, authentication/cancellation observations, drained sessions and stopped workers; these observers of one trial are not additional independent scenarios.

All 129 recorded commands have expected exits and no command-cleanup failure. Six private-fixture arguments use exactly six fixed redaction markers; private descriptor values are absent from the ordinary retained receipts/logs and sanitized result. Native stop/process absence precede command finalization, exact `COMPLETE\n`, host result and completed Peer/controller receipts. No temporary completion marker remains. All six guest cleanup checks pass: package/process absence and sampled boot/packages/settings/reverses preservation. All five shutdown checks pass, including retained userdata: owned backend, launcher and ports absent, shared owner preserved and guest data retained. Recorded post-terminal Windows readback corroborates these facts; this documentation work makes no new runtime query. Sampled equality and recorded absence do not prove every possible state.

G1's ordinary host launch failed because the outer NoProfile child lacked `Invoke-CodexQuiet`; both direct hosts exited one. No Peer run, Gradle/fixture or native trial began. The unchanged controller rejected the root-authorized invalid directory handoff and settled normal cleanup. All six cleanup and all five shutdown facts, including retained userdata, pass with empty errors and recorded terminal absence/shared-owner proof. G1 remains consumed FAILED with zero API25 native acceptance; G2 is its separately admitted successor, not a successful reinterpretation of G1.

The first G2 collector assertion compared blob-enriched source-pin objects with producer path/SHA256 receipts. Its retained correction changes only that projection; the ordered comparison passes without another runtime attempt or weakened control. The runtime's 657 source, 1,496 preparation and 45 image inputs match their pinned inventories, and source/harness before/after receipts match. Later documentation integration does not claim current-document 657-file equality or a rebuilt final-head APK. D2, E1/F1 and physical API34 histories retain their original outcomes and limits.

Private evidence paths below are relative to ignored `.artifacts/continuation-20261018/`; no raw device/host report or private descriptor is tracked.

| Evidence | SHA256 |
| --- | --- |
| G1 frozen failed-run evidence | `a0368ba9f85bf4f0145ac4d1370814b934b893c920312adbfe23f8d1a3587161` |
| G2 `api25-g2-runtime/frozen-evidence.json`, 8,043 files | `1c55f348bdadf74ae93dbe26f80c0362038fe25eece09269435e6981cf015b63` |
| G2 sanitized result | `b354e131492da2d4a6535328bba36b3da849e7e701cd598f2a6c67a05bfda167` |
| G2 native accepted receipt | `373b10a8b53a0d16f452b641570de77e2eeb4129bf986616d57a74c85e1bd2af` |
| G2 host accepted receipt | `c2574cafa486e7758d95b59da981582fc06209e7339d6ac6073dd7e5c6d63935` |
| Different G2 actual-runtime ultra review | `d50a2746d53c1463576b11d8de12ee1542af30ac5735f0c2747ec13c925ddea5` |

#143 remains open for physical/OEM and wider provider/key/algorithm/network coverage, IPv6/external paths, blocked connect/write/upload boundaries, production Application/wizard/progress/profile/store integration, VPN/lockdown/protection, actual disposable-server mutations/client traffic and release. No performance, battery, DPI, whole-issue completion or grade follows. Different final documentation-head review, CI and publication remain the delivering PR's gates.
