# Delivered codebase review — #208

Last updated: 2026-10-17 (Europe/Paris).

## Result and reviewed revision

The maintained production source review is complete at delivered `master` revision `56509a3ad8ed1c8a5e912b87b637990bf4d15135`, tree `919b0f0ad7ddb19d2db71af1ef8751de4c6d554b`, after [PR #207](https://github.com/novakin/vpn0wn-android/pull/207) merged. Its tree equals independently reviewed and CI-tested `b5db3cce80dda73d46e942d58076ce0045606638`. Draft #103 is unmerged and excluded. This is the requested post-delivery review under [#208](https://github.com/novakin/vpn0wn-android/issues/208).

Seven new bounded defects were established: one Medium maintenance/security defect, four P2/Medium correctness or safety defects, and two Low correctness defects. Six have new repair issues; the generator follow-up reuses #71. The pre-existing #206 MTU diagnostic is independently reconfirmed. The separate Go maintenance/reachability gap has its own issue. No new production repair is delivered by this report.

The [security reassessment](2026-10-12-app-root-security-audit.md#post-delivery-reassessment--208-2026-10-17) owns attacker identities, historical finding reconciliation and sign-off gates. No credible High/Critical security defect was established within this assessment. Broader runtime assurance remains partial; review completion does not establish production readiness.

## Scope and method

Three independent ultra source passes covered Android/app/IPC/data, provisioning/storage/server scripts, and tunnel/config/native/root ownership. The coordinator reviewed build/dependency/provenance, workflow and current guidance, reconciled the findings and executed bounded probes. All maintained production code owners were read; tests received complete method/fixture inventories and targeted assertion/ownership review. This is not independent cryptanalysis or a proof of every assertion.

| Inventory slice | Paths | Reviewed boundary |
| --- | ---: | --- |
| Android | 225 | 61 Kotlin owners, 137 resources/manifests, 18 runtime artworks and nine runtime notices; UI/resource integration, authentication, intents, providers, import/export and logs. |
| Provisioning | 33 | 21 Kotlin owners, eleven maintained scripts and their README; authenticated SSH/SFTP, credentials, retained profiles, pending operations, backup/restore, privileged writes and recovery. |
| Tunnel/native | 43 | 42 source/patch files and one manifest; manager/model, config/crypto, backends/root/tools, JNI/Go/UAPI/bind/overlay and cleanup. |
| Support | 277 | 103 tests/harnesses, 24 fixtures, 19 build/dependency inputs, two operator scripts, ten repository configuration files, 81 documentation/license/evidence files, generated references and dependency binaries/pins. |

The 578-path ledger has 295 VERIFIED, 242 PARTIALLY_ASSESSED, ten FAILED and 31 NOT_APPLICABLE dispositions, with no unassigned path. Each status applies to the row's stated criterion, not whole-file safety or a readiness percentage. FAILED paths can share one finding. Many partial criteria require runtime, output, historical or dependency proof beyond source reading. The 31 non-applicable source-review criteria are 28 generated reference outputs, two historical visual outputs and one external upstream script; their producers/provenance/consumers retain separate assessment.

The two native gitlinks expand to another 170 inventoried external files. Wholesale third-party line review, generated output reimplementation and font/wrapper decompilation are excluded; pins, notices, interfaces and maintained overlays/patches remain assessed. All eleven app-facing `third_party/wireguard-install/vpn0wn` scripts are maintained source and are included. The just-delivered complete model translation reading is reused across unchanged resource bytes; it is not qualified linguistic certification or a fresh rendered run.

Per-file criteria and private receipts remain under ignored `.artifacts/continuation-20261016/`. Current product contracts were checked against source; historical reports retain their original revisions and test limits. This review does not rerun or certify every historical statement.

## Findings and bounded repairs

| ID / priority | Responsible location | Confirmed behavior and repair owner |
| --- | --- | --- |
| SYNC-IMPORT-SHELL — Medium security | `sync-crowdin.sh:10` | Archive-controlled filenames enter `bash -c` source and can execute a maintainer-UID command. Callback-only malicious-path/control proof; full extraction unexecuted. Keep paths as arguments and preserve cleanup. [#209](https://github.com/novakin/vpn0wn-android/issues/209). |
| C-KERNEL-UNKNOWN-DOWN — Medium safety | `WgQuickBackend.java:63–80,128–137`; `TunnelManager.kt:253–264` | Failed observation becomes empty/DOWN. Shutdown can report success without teardown, and deletion can remove the recovery profile while the interface remains active. Actual backend/manager with stubbed commands reproduced both paths. Preserve unknown/error state and recovery ownership. [#210](https://github.com/novakin/vpn0wn-android/issues/210). |
| C-CONFIG-STALE — Medium safety | `TunnelManager.kt:301–303`; `ObservableTunnel.kt:101–105` | A delayed old load publishes after a later successful save, replacing the cache with the old profile while the file retains the new one. Actual manager/model with finite IO barriers reproduced it. Bind publication to current ownership/config generation without reentrant mutation-lock waits. [#211](https://github.com/novakin/vpn0wn-android/issues/211). |
| ANDROID-APPSEL-01 — P2 correctness | `AppListDialogFragment.kt:129–160`; editor result consumer | Apply before package-inventory publication emits an empty replacement for an existing routing selection. Actual dialog/button callback reproduced it; loaded control preserves selection. Guard Apply/bulk actions until inventory is loaded. [#212](https://github.com/novakin/vpn0wn-android/issues/212). |
| ANDROID-DNS-01 — P2 correctness | `PeerProxy.kt:172–183` | Exclude private IPs adds `/32` to a supported DNS search domain, making the draft invalid. Actual config/proxy pair reproduced it; numeric DNS control resolves. Derive routes only from numeric addresses without resolving domains. [#213](https://github.com/novakin/vpn0wn-android/issues/213). |
| C-CONFIG-EQUALITY — Low correctness | `Interface.java:107–119,199–209`; `KeyPair.java` | Separately parsed equal profiles compare unequal because key-pair equality is object identity. JVM pair confirms it; unnecessary backend updates are a source consequence, not a measured device result. Restore value equality/hash and prove the unchanged-save no-op. [#214](https://github.com/novakin/vpn0wn-android/issues/214). |
| GEN-NOTICE-01 — Low build/provenance | `docs/assets/vpnown-brand/generate.mjs:112–115` | The generator template omits the modification line retained in six inherited Android XML headers. A successful regeneration would remove it. Static producer/consumer trace only; current checked-in headers remain intact. [Existing #71 follow-up](https://github.com/novakin/vpn0wn-android/issues/71#issuecomment-6225617777). |

All seven new issue proposals, including the separate BD-G01 maintenance issue, received independent ultra review before publication. GitHub readback confirms personal author/assignee `novakin`, intended labels and null exposed app-attribution metadata. Recording findings does not fix or close them.

Existing [#206](https://github.com/novakin/vpn0wn-android/issues/206) owns negative MTU errors labelled as ListenPort. An editor activity-scope settlement hypothesis remains unconfirmed: normal navigation can detach the editor before a selection listener runs. It is a validation question, without a new defect issue or assigned security severity.

## Executed evidence

| Check | Actual result | Limit |
| --- | --- | --- |
| Final pre-merge CI [38970039632](https://github.com/novakin/vpn0wn-android/actions/runs/38970039632) | 938 UI debug tests, 16 tunnel tests and one release-startup test; zero failures/errors/skips; zero Detekt findings. Six native race-test packages, 18 offline VPS-harness and six cleanup tests pass. | Exact `b5db3cce` tree equals reviewed merge tree. Not signed release, physical Android or live-server proof. |
| New functional probes | Eleven unique valid JVM/Robolectric cases: six expected defect assertions fail and five normal controls pass; zero errors/skips. | Five functional findings; kernel observation has two failed cases. These are reproductions on unfixed source, not a passing repair gate. No OS/root/packet enforcement claim. |
| Shell callback pair | Ordinary filename creates no marker; command-substitution filename creates the marker. Both resources survive; UID 1000; no deletion branch entered. | Exact cleanup callback with a successful xmllint stub; no full extraction, credential access or privilege escalation. |
| Dependency resolution/advisories | Seven configurations resolve without failed components: 204 distinct Maven versions, 26 locked npm versions and six Go coordinates queried through OSV. Shipped Java graphs have no version matches; old Go pins and test-only BC 1.81 do. | Configuration/version inventory, not whole-APK contents or exploitable-call closure. |
| Native diagnostic | Existing Make builds arm64/API24 `libwg-go.so`; compiler/module metadata and hashes retained. Binary scanner completes but extracts no package symbols and falls back to conservative version candidates. | Direct Make caller flags differ from full CMake/Gradle packaging. Neither a precise linked-symbol scan nor release/runtime acceptance. [#215](https://github.com/novakin/vpn0wn-android/issues/215). |
| Provenance | Current wrapper JAR equals its official 9.8.1 checksum; native compiler archive equals the Make pin; current native gitlinks retained. | Provenance/pin checks do not establish uncompromised publishers or complete external-source safety. |

Failed attempts remain evidence: #207's old-head NDK setup failure and Settings fixture timeout; the deterministic old-order timeout and rejected pre-inflation injection; initial probe source-directory registration finding no tests; initial app-selector probe not dispatching the native button Handler; source scanner unable to load overlay-added methods; native preflight Linux Git unable to resolve the Windows worktree pointer. Corrected runs retain their distinct inputs and scopes. Overlapping test runs are not added together.

## Engineering assessment and next work

Architecture ownership is sound: keep Fragment/DataBinding presentation, existing model/backend transitions, transport channels and persistence owners. Resource protection, native teardown/generation checks, finite recovery admission, privileged script transactions and pending remote-outcome handling have meaningful boundaries and tests. The defects cluster around read-publication and failure-to-state conversion rather than requiring a foundation rewrite.

Correctness needs the bounded owner repairs above. Passing static checks and a large normal suite did not exercise these interleavings or generated-input cases; add those regressions with each fix. Avoid broad refactoring, clever compression or a new scheduler/abstraction for these slices.

Process and current guidance are strong: issue ownership, signed account-linked commits, exact-head independent reviews and actual CI/readback are enforced by the working process. GitHub reported `master` unprotected at this checkpoint; repository settings do not independently enforce those manual gates. This is a workflow/enforcement gap, not evidence of a compromised build. Changing repository protection requires its own selected policy.

Repair #209–#211 first, then #212/#213; carry #214 and generator notice retention through their owners. #215 remains a security-sign-off maintenance gate. Continue #143/#196's native/live/backup product acceptance and existing release/root/provider/UI/accessibility gates. Performance/battery work stays last under its measurement contract. No new protocol, live mutation or destructive target is authorized by this ordering.

The author is satisfied with the bounded source assessment and evidence distinctions. Source findings and incomplete runtime assurance remain explicit; no 100/100, clean-security-scan or production-readiness claim is made. Final report publication, independent ultra review and CI/merge readback belong to the delivering PR.
