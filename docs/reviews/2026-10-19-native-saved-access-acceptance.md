# Native Android saved SSH access acceptance

Last updated: 2026-10-19 (Europe/Paris).

## Scope and current verdict

**VERIFIED, dynamic, within the bounded B4 saved-access cohort.** B4 passes eleven native checks, six complementary host facts, eight cleanup facts and five shutdown facts. Independent ultra review is satisfied with runtime, collection, preservation and target-feedback visibility; author and independent original-pixel reviews are satisfied within the selected English portrait dark-theme scope. The visible field-spacing gap remains open. This record covers one isolated API28 saved-password reuse, Clear-dialog Cancel, confirmed Clear and missing-access refusal flow under [#143](https://github.com/novakin/vpn0wn-android/issues/143), which remains open. B1/B2 failed; B3 retains passing raw receipts but failed collection and no transferred originals.

The isolated probe exercises the production Application, MainActivity, default Fragment/ViewModel/client, ordinary socket/JSch path and real Android Keystore/store against a fixed-response synthetic SSH peer. B4 performs two authenticated Detect cycles on the same saved server, preserves fleet/settings through Cancel and Clear, then refuses Reuse after credential removal without a third native SSH session. Dialog Cancel is distinct from in-flight operation cancellation. The peer verifies uploaded assets and command order; it executes no production server script or shell. Native and host facts observe one bounded cohort, not seventeen independent transport scenarios.

The earlier [positive wizard Detect acceptance](2026-10-18-native-wizard-detect-acceptance.md), [transport acceptance](2026-10-18-native-ssh-acceptance.md) and [credential-storage acceptance](2026-10-15-native-ssh-credential-storage-acceptance.md) retain their original scopes. The [wizard contract](../backend-contracts.md#read-only-ssh-detect-and-plan--143) owns product behavior; this report changes no product code or operational policy.

## Source and artifact boundaries

All four cohorts pin clean product revision `12e46e3d875e197451f6360679935670079bad00`, tree `b45d92a3903066cbae4e2043e135f452b90ef130`, with 677 tracked source files and both exact initialized native gitlinks. The separate probe package is `local.vpn0wn.savedaccessprobe.debug`; no VPN activation is requested. This runtime source differs from documentation integration base `22dc69149829ff64c7d3d7cb090d745d940220db`. The later AmneziaWG source receives no compatibility or runtime proof from these cohorts.

| Cohort | Artifact evidence | Outcome |
| --- | --- | --- |
| B1 | Build and static audit passed; APK SHA256 `85b8e836849b89ca02ac9eb0a7e25a647b1f3b9642b7b28c771d572cb15ed9cf`. | Controller and Peer failed; no native or host acceptance. |
| B2 | Build and static audit passed; APK SHA256 `847158fc6d7225869447fa0fa5b493eee062ca9941f2724e3d42ebab688b8605`, 19,235,645 bytes. | Controller failed; Peer passed six host facts. Native and pixel acceptance failed. |
| B3 | New Build and static audit passed; APK SHA256 `fa34c57575e887e719add0bce91ede2a7a1cb89e7daa785586a9ab8379499cb7`, 19,235,645 bytes. | Raw native eleven-check and six-fact host receipts pass; controller collection failed before PNG copying. Cleanup/shutdown pass; pixel acceptance unavailable. |
| B4 | Reuses the reviewed B3 APK and eight built inputs unchanged; no new Build. | Controller and Peer exit zero; eleven native, six host, eight cleanup and five shutdown facts pass. Independent runtime and five-original target-feedback review satisfied. |

B1/B2/B3 inputs, failure receipts and consumed guests remain retained. Later corrections do not replace their failed outcomes.

## B1 failure and bounded corrections

B1 native stdout reports five passing initial-Detect checks, then `TimeoutCancellationException`. It does not identify the timed-out wait. No complete eleven-check native result, six-check host acceptance or transferred original screenshots were obtained. No visual verdict is accepted.

Source review found two harness defects. Native readiness/click helpers required a clickable TextView, while production Reuse/Clear click listeners belong to parent rows. The controller's console-name comparison read ambient `$name`, which PowerShell's case-insensitive dynamic scope replaced with `Invoke-Recorded`'s command label. B2 resolves each exact visible label to its nearest enabled, shown clickable ancestor within the current root, requires one unique action target and compares the console name directly with the pinned target. Directly clickable text controls remain supported; actions still use the native UI.

B1 cleanup passed eight facts and owned-emulator shutdown passed five. Later readback also found its reserved listeners absent. Cleanup success does not accept the failed workflow, and no product defect was established.

## B2 failure, pixels and diagnosis limits

B2 native stdout reports `passed:false`, eight passing checks, `TimeoutCancellationException` and `currentStage: reuseClearedAccess`, with instrumentation code `0`. The external native command reached its 210-second deadline; the controller observed about 213.43 seconds and exit `-1`. The stage marker has no per-wait timing and does not prove that final Reuse executed.

The Peer completed with six passing host facts. All 144 controller command receipts settled; only the native command departed from its expected outcome. Cleanup passed eight facts and emulator shutdown passed five, with baseline preservation and matching source/harness/gitlink receipts. Later listener absence was separately checked. These are complementary host and cleanup observations, not native-flow acceptance.

Before uninstall, supplemental collection retained three original native PNGs and a current screen near 196 seconds. `wizard-detect.png`, `wizard-detect-saved.png` and `wizard-saved-reuse.png` are byte-identical: SHA256 `026c44ef9ef7101eb2d5c3d532c8f15a2f18f610d09d60677aadc1e98892a1d9`. The intended SAVED feedback is absent. Independent original-pixel review was **NOT SATISFIED**. The supplemental current screen shows CLEARED; it supplies no ACCESS_MISSING proof or evidence that final Reuse ran.

Source inspection establishes an invalid requirement to observe transient busy before the final missing-access result and a redundant unbounded `waitForIdleSync()` after a bounded draw/two-frame gate. Either observation alone does not establish the actual B2 timeout cause. Stale or replaced views remain a screenshot-cause hypothesis; no product overlay defect is demonstrated.

## B3 harness corrections and partial result

B3 clicks the final native Reuse row, then awaits terminal `!busy`, `ACCESS_MISSING` and `SSH`, retaining the unchanged-server/storage and no-third-native-session requirements. Capture removes the redundant idle wait, retains the bounded draw and two animation frames, then reacquires the exact target from the current Fragment root and checks identity, attachment, shown state and full visible bounds before taking the original screenshot. Unavailable or replaced targets retry. The 30-second coroutine envelope is cooperative; synchronous main-thread and screenshot calls still require the external command deadline. No renderer, screenshot transformation or product change is introduced.

Independent offline review was **SATISFIED**, with no actionable findings. Twenty input bindings, 47 prior references, the unchanged source pins and 33-file image vector were checked. The existing verifier passed nine groups; injected interface controls passed 22/22. These preparation checks and the separate successful Build/static audit retain their own evidence scope.

B3's installed APK bytes and ordinary UID matched its admission. Raw native stdout contains `passed:true`, eleven true checks and instrumentation code `-1`; the native command exited zero. The Peer completed with six passing host facts. All 138 external-command receipts settled with their expected outcomes. Cleanup passed eight facts and owned-emulator shutdown passed five; later reserved-listener absence was checked.

The controller nevertheless failed while parsing the native return, before copying any PNG. `Copy-OwnedBinary` returned two `VoidTaskResult` objects from uncast `GetAwaiter().GetResult()` calls in native finalization. Those objects entered the enclosing command's return alongside stdout; array-to-string conversion broke the anchored native-result match. The retained raw stdout matches once. A local MemoryStream reproduction matches zero times with the leaked objects and once after suppressing those outputs with `[void]`. This identifies a controller collection defect; it does not invalidate the raw native receipt or establish the correctness of unseen pixels.

No original B3 PNG was transferred. The native capture flags therefore supply no independent visual acceptance. B3 remains a failed collection with partial runtime evidence, not a completed native-flow-and-pixel acceptance cohort.

## B4 actual runtime and preservation

B4 adds `[void]` to the collector's four task-result calls and uses a fresh owned API28 x86_64 guest, preserving the reviewed B3 APK, its eight built inputs and product source. The task-result casts suppress unwanted success output while retaining failure propagation. The installed APK matches the reviewed bytes and ordinary isolated UID. The Peer run is `20261019-102734-e074e0`; Controller, Peer and the single Peer quiet-command wrapper all exit zero. All 148 external-command receipts have completion timestamps and their expected exits, including checked absence outcomes.

Independent review verified all 677 source hashes, both gitlinks, eight built inputs, matching before/after inventories and the initial, native-finalizer and cleanup installed-APK copies. The retained Controller completion timestamp records later readback, not its exact exit time. No separate Controller OS-PID receipt was retained; executed inputs, command receipts, owned-emulator identity and completion log supply that evidence. The Peer outer identity and handoff retain their separate process binding. The final independent review report has SHA256 `d62394908efdaa929b14b97aca3a6dd36e179a25fc9323a44235b9dc4e5040c8`.

Native stdout, collected result and acceptance agree on `passed:true`, eleven true checks and instrumentation completion code `-1`. The checks cover production/default-owner navigation; actual fields/action/busy/validated Detect; exact saved server, pin and Keystore-decrypted password; private ciphertext and sampled plaintext exclusion; native Detect capture; saved-access Detect on the same server without password re-entry; Clear-dialog Cancel preserving saved access; confirmed Clear removing only the credential; Reuse after Clear reporting missing access without repersistence; saved-access output capture; and Activity retirement with no sampled JSch reader thread.

Six host facts independently require exactly two Detect command cycles, all twelve uploaded asset bytes and mode 0700 in both cycles, operation-owned remote-directory removal after each cycle, two native password sessions with no third or key attempt, no privileged/mutation command, and stopped sessions/workers. The separate controller bridge prerequisite is not a third native session. Fixed synthetic Detect responses establish no real-server script result.

All eight cleanup facts pass: package/process/service absent, interface inventory unchanged, and original boot/package/settings/reverse baselines preserved. All five shutdown facts pass: owned backend/launcher/listeners absent, shared owners preserved and owned userdata retained. These are the checked postconditions for this guest, not universal lifecycle guarantees.

## B4 original output and review

All five original PNGs were copied successfully at 1080×1920. Author review is **SATISFIED**. Independent original-pixel review is also **SATISFIED** for the selected English portrait dark-theme targets: readable Detect output, complete initial-save and saved-reuse feedback, complete CLEARED feedback and fully visible missing-access warning, without target overlap. Palette, wordmark and Mono hierarchy remain consistent. Initial-save and saved-reuse feedback intentionally use the same expected text; their distinct execution sequence is established by receipts, not unequal image hashes. The independent reviewer applied Interface Design, UI/UX Pro Max and design-token guidance to the existing approved direction and native component roles. Font scale and density were not separately asserted; Clear-dialog interaction has native receipts but no dialog PNG.

| Private original | Bytes | SHA256 |
| --- | ---: | --- |
| `wizard-detect.png` | 128,812 | `000d5944cc1d2ff553b3ff2d47d98f107b206b07eaa6efc1ce6b157410263b37` |
| `wizard-detect-saved.png` | 127,012 | `9bc7db6053cec0a732169861fb1656b343cfd9aaedeb356405dbd17b17da0933` |
| `wizard-saved-reuse.png` | 126,915 | `7454292d95bd746fac84b0542bfee50b078c507f6d5a109cd27ef4c94975d6ed` |
| `wizard-saved-cleared.png` | 126,526 | `8325cc9656f945f93fd4c3f0fc18110344b656b170cffd404cc44244085e7201` |
| `wizard-saved-missing.png` | 115,538 | `91a2593da6b636ca6a9ea6122295bf6b2143d4855d2eb8322049dc1fe34bad07` |

## Remaining limits

After reviewing the captures, the user identified field text touching its native underline. That visible input-spacing gap remains a #143 follow-up for UI/UX audit, correction and affected-output verification. The satisfied feedback-target verdict does not accept the spacing, complete-screen polish or the wider configuration matrix.

Wrong-pin runtime refusal, in-flight cancellation, Activity/process recreation, wider API/OEM/provider/theme/font-scale/RTL/TalkBack coverage, private keys, real networks, live-server mutation, VPN traffic, installation/profile workflows, release and complete #143 acceptance remain separate. No cohort at source `12e46e3d` establishes later AmneziaWG behavior.
