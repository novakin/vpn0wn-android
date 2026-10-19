# Native Android SSH host-trust acceptance

Last updated: 2026-10-19 (Europe/Paris).

## Scope and verdict

**T2 and T3 functional behavior VERIFIED dynamically; scoped visual acceptance uses the explicit T2/T3 matrix below.** Each isolated API28 synthetic-server cohort passes 25 native booleans and 11 complementary host facts. T2 supplies the saved-success/reuse originals; T3 supplies the settled trust dialog. Neither cohort alone covers every named visual target. These assertions observe one workflow; their counts do not represent independent security scenarios.

This record covers [#143](https://github.com/novakin/vpn0wn-android/issues/143): explicit first-use confirmation/cancellation, trust retained after failed authentication, fresh-process Keystore reopening, saved-password reuse, credential clearing and changed-key refusal at the same endpoint. The default production Application, MainActivity, Fragment/ViewModel/client, ordinary JSch sockets and real encrypted store are exercised. The fixed-response peer validates uploads and command order but executes no server script or privileged mutation.

The [host-trust contract](../backend-contracts.md#confirmed-ssh-host-trust--143) and [decision](../architecture.md#confirm-and-retain-ssh-host-trust--143-2026-10-19) own behavior. Source was delivered through [PR #304](https://github.com/novakin/vpn0wn-android/pull/304); this evidence changes no product source, schema, protocol or policy. Earlier [saved-access](2026-10-19-native-saved-access-acceptance.md), [transport](2026-10-18-native-ssh-acceptance.md) and [storage](2026-10-15-native-ssh-credential-storage-acceptance.md) records retain their original revisions and scope.

## Source, artifact and environment

The frozen runtime source is clean `3f6dbfa467b9fd0c82e8d2bfb936c2e82dd4fb52`, tree `00e668b943a4c763d5547d5796239060588203d3`, with 689 tracked files and two initialized native gitlinks. Source and consumed harness inventories match before and after execution. T3's 21 current inputs match their reviewed pins. Documentation written afterward cannot extend that runtime revision.

The successful T1 Build supplies the unchanged 19,765,115-byte APK, SHA256 `fde929774a55dbcaf6e0ce6638bcda795f89a647f42606343b5b8850bd05fe57`. Its isolated package is `local.vpn0wn.hosttrustprobe.debug`, INTERNET-only, with production Application/MainActivity/private VPN service, executable three-phase instrumentation and x86_64 native libraries. Signature, manifest, DEX, native identity and twelve source-exact script assets pass audit; MINA host-peer classes are absent. T2 and T3 reuse these exact bytes after equality checks on source and APK-producing manifest/init/instrumentation inputs. Historical Build receipts remain validated against T1, while each Peer receipt uses its own preparation. Host/controller corrections require no new APK assembly. Each accepted cohort retains eight matching installed-APK copies.

The host recipe uses Windows SDK/ADB/emulator and Debian WSL/JDK21/Gradle. Each runtime uses fresh owned API28 Google x86_64 revision11 userdata at 1080×1920, density420, English portrait dark theme. All 33 image files are pinned. The host peer is loopback-only and bridged into the exact guest through one owned ADB reverse. Synthetic credentials/keys and the separate UID avoid personal app/config replacement. No workstation elevation, physical-phone operation or live VPS access occurs. Tool access itself is unrestricted; isolation is enforced through package/guest/process/artifact identity and scoped cleanup.

## Cohort outcomes and corrections

| Cohort | Result | Meaning |
| --- | --- | --- |
| T1 | FAILED overall; native phases and host phase assertions pass. | Final host shutdown and parent receipt reporting fail; original screenshots are not collected. Cleanup and owned shutdown pass. |
| T2 | Functional PASS; visual PARTIALLY_ASSESSED. | Both outer commands exit 0; native 25/host 11, eight cleanup and five shutdown facts pass. All eight originals collected; seven satisfactory target views, one unsettled-modal capture. |
| T3 | Functional PASS; visual PARTIALLY_ASSESSED. | Both outer commands exit 0; the same native/host matrix and cleanup/shutdown pass. Six of eight named visual targets pass, including the settled modal; two saved-feedback captures show the top Detect viewport. |

T1's host fixture queries MINA `activeSessions` after `stop(true)` clears its acceptor, causing a null dereference. T2 checks actual server closure, tracked session identities/closure and terminated command workers; stop remains outside the tracking monitor. T1's parent wrapper also parses a UTC timestamp into a PowerShell DateTime and compares it with the original string, losing literal identity through timezone conversion. T2 preserves JSON strings and writes the actual producer outcome before handoff validation. Four injected copied-wrapper controls cover success, producer failure, handoff alteration and combined failures, retaining the original error. T1's failed handoff is reconciled only after actual process disappearance and tool exit 1; it remains failed.

Independent ultra preflight covers source/harness hashes, these corrections, original APK reuse and exact guest admission. T2's eleven offline groups, 22 interface controls, seven Peer-settlement controls and standalone host compilation pass. T3 adds owned-emulator capture/restoration controls: twelve offline groups, fifteen animation/restoration controls and the retained interface/settlement controls pass. Successful preparation does not replace runtime evidence. Consumed inputs, failed receipts and original pixels remain retained separately.

T3 changes only the owned guest's `window_animation_scale` from its observed `1.0` to `0` before capture, with restoration armed before mutation and checked in outer cleanup. Its exact original `1.0` is restored and read back before baseline comparison. `transition_animation_scale` and `animator_duration_scale` remain unchanged. This establishes settled static output; it does not accept entrance-animation timing or motion.

## Native and complementary host proof

1. **First trust:** the actual dialog shows the complete endpoint/fingerprint. Cancel leaves unknown-host trust and credentials absent. Explicit Trust server persists trust before a deliberately failed password attempt; no credential or fleet record is saved.
2. **Reopen and reuse:** an exact-package force-stop retires the first process. A fresh Application/Keystore/store reads the accepted trust; correct credentials complete Detect without another confirmation. A second Detect reuses encrypted access without rewriting it. Clear Cancel preserves access; confirmed Clear removes the credential while preserving trust, fleet and settings. Reuse afterward reports missing access without a new native SSH session.
3. **Changed key:** after all six earlier sessions close, the peer changes only its RSA2048 host key on the same listener/address/port. Another fresh app process rejects it with terminal CONNECTION_FAILED, no replacement-trust dialog, no credential/fleet/trust alteration and no authentication sent to the changed key.

Each accepted cohort records three distinct native processes under one ordinary UID, exact installed APK copies, and ciphertext continuity across forced process retirement. The native phases pass six, thirteen and six booleans respectively, including teardown without residual JSch reader threads. T3 uses UID10086 and processes 5665, 5780 and 5905. Force-stop/reopen is distinct from in-flight cancellation, crash/power-loss durability or hardware suspend.

Each accepted host run sees exactly seven sessions: raw bridge, two unauthenticated discoveries, one failed password, two successful Detect sessions and changed-key refusal. It records three password attempts, zero key attempts, eight commands, two complete twelve-asset/0700 verification cycles and removal of both owned remote directories. All seven sessions and workers close. Discovery and changed-key sessions have no user-auth service start or USERAUTH_REQUEST, including `none`.

Changed-key evidence records a signed K2 reply using the same negotiated `rsa-sha2-512` profile. The fixture observes successful ordered server NEWKEYS write after the signed reply and nonempty exchange hash. This establishes server-side output, not client-completed key exchange; JSch rejects the changed host before client NEWKEYS. MINA's earlier KexCompleted event is not used as proof of that boundary.

## Original-pixel review

Author and independent ultra reviewers inspected the unedited 1080×1920 originals using Interface Design, Design System, Frontend Design and UI/UX Pro Max, against existing tokens/font roles and the read-only ServerProvisionScreen reference. The scoped satisfaction verdict is **satisfied with the named static feedback targets through the following combined evidence**, with capture limitations retained. It does not certify every scroll position, action, animation or configuration.

| Original filename | Accepted cohort | Observed target and limitation |
| --- | --- | --- |
| `trust-confirmation.png` | T3 | Opaque settled surface, complete endpoint/pin/explanation and both actions. T2's underlying-form bleed-through remains an unsettled capture. |
| `trust-auth-failed.png` | T3, also T2 | Readable failed-authentication feedback, retained complete pin and field clearance. |
| `trust-changed-key.png` | T3, also T2 | Readable refusal feedback and unchanged trusted pin; runtime/host records establish pre-auth refusal. |
| `wizard-detect.png` | T3, also T2 | Readable observed Detect data and complete wrapped fingerprint. |
| `wizard-detect-saved.png` | T2 only | Visible saved-access success feedback. T3 repeats the top Detect viewport and does not prove this target. |
| `wizard-saved-reuse.png` | T2 only | Visible saved-access reuse feedback. T3 repeats the top Detect viewport and does not prove this target. |
| `wizard-saved-cleared.png` | T3, also T2 | Visible credential-clear feedback and honest unavailable-action guidance. |
| `wizard-saved-missing.png` | T3, also T2 | Visible missing-access refusal and unavailable-picker guidance. |

T2's full endpoint, fingerprint, explanation and actions are visible, but its modal capture waits only for drawing and two animation frames while `window_animation_scale` remains `1.0`. Source inspection found an opaque surface and no dialog alpha override; an entrance-animation capture is plausible, not proven. T3 resolves the static surface proof at the identical source/APK. Its three Detect PNGs are byte-identical, exposing the two omitted saved-feedback targets. No product translucency or saved-feedback defect is established by these capture failures. Reusing T2's valid originals avoids claiming a successful new capture where none exists.

## Cleanup, retained evidence and limits

All three cohorts pass eight cleanup facts: owned package, process and service absent; interfaces, boot, package inventory, settings and ADB reverses unchanged. Five owned-stop facts pass: emulator backend, launcher and listeners absent, shared owners preserved and userdata retained. T2 and T3 have no final cleanup errors. All 189 T2 and 199 T3 controller child-command receipts match expected exits. Cleanup success does not accept T1's failed workflow or either cohort's omitted visual target.

Local evidence remains under ignored `.artifacts/continuation-20261019-trust/`: immutable preparation pins, original successful Build/audit, separate T1/T2/T3 runs/guests, actual outer-tool receipts, native/host phase results, original PNGs and cleanup/shutdown records. T3's peer run is `native-wizard-host-trust-prep-v3/runs/20261019-161622-0733aa`; its guest record is `native-wizard-host-trust-prep-v3/guests/api28-t3`. Raw host reports, synthetic private material and screenshots are not part of source distribution. The PR acceptance record owns final documentation-head review, CI and delivery.

Complete #143 remains open. These cohorts do not establish release/physical/OEM behavior, IME/TalkBack, full theme/size/font/RTL coverage, private-key authentication in this wizard, stale/cancelled admitted operations, every storage migration/corruption case, external IPv4/IPv6/SSH paths, VPN/lockdown routing, actual server installation/profile/user mutation, authenticated client traffic or production readiness. Source/unit checks and other native records retain their separate boundaries.
