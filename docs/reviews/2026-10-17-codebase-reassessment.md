# Codebase reassessment after delivery repairs

Last updated: 2026-10-18 (Europe/Paris).

**Latest delivered-source engineering assessment: 85/100 at `29bc2a8d`.** The [2026-10-18 follow-up](#post-repair-assessment--2026-10-18) records the repaired owners, current review and evidence limits. The original assessment, tables and findings below retain their 2026-10-17 revision and historical 80/100 judgment.

## Result and revision

**Overall engineering assessment: 80/100.** The architecture is worth keeping and the failure-handling/test infrastructure is substantial. Four current P2 Android state defects and one P3 native build-maintenance defect still need repair. Production readiness is not established.

This is the maintainer-requested whole-codebase reassessment after [PR #243](https://github.com/novakin/vpn0wn-android/pull/243). Reviewed delivered `master`: `5882460d4d2bff47b6725e581594f010810329ff`, tree `4d1f256ecd872fd9e92e05ebe0c9cb9502d44187`. That tree equals reviewed/CI-tested `99be54b4e4a26f7cf4e682cc8ec52a353214b34a`. The earlier [#208 assessment](2026-10-17-delivered-code-review.md) describes `56509a3a` and remains historical; it is not silently re-rated or reopened. Draft #103 remains excluded.

The findings below are supported by source/caller traces. They are **not newly executed reproductions**, field-frequency measurements or proof of an ordinary-app exploit. No new High/Critical security vulnerability was established within this static pass. The [app/root audit](2026-10-12-app-root-security-audit.md) retains its separate actor, artifact and runtime limits.

## Ratings

Scores are bounded engineering judgments, not coverage percentages, completion percentages or security certificates. They consider supported behavior, failure/cancellation semantics, ownership, maintenance cost and available evidence. The overall judgment is not a mechanical average: state-policy defects matter more than a large test count.

| Area | Rating | Main reason |
| --- | ---: | --- |
| Architecture | **85/100** | Fragment/DataBinding, manager/backend, transport and store ownership fit the application. Startup and continuation policy still crosses those owners inconsistently. |
| Code quality and reliability | **80/100** | Clear validation, cancellation settlement and recovery boundaries; four concrete composed-state defects remain. |
| Maintainability | **75/100** | Reuse is generally sound, but coordinating files and duplicated status prose increase change/review cost. Extract only for a demonstrated ownership benefit. |
| Security engineering | **80/100** | Host pins, protected sockets, bounded imports, authenticated disclosure and encrypted private stores are meaningful. Native enforcement, release and live operational proof remain partial. |
| Tests and verification | **80/100** | Broad owner, failure and native concurrency checks; missing composed-sequence regressions and incomplete Android/live/release acceptance prevent a higher grade. |
| Documentation | **80/100** | Dated source contracts and honest evidence boundaries are strong. Current README/plan delivery summaries drifted behind merged rotation/restore work; this documentation follow-up corrects those statements. |
| Agent/process contract | **90/100** | Scoped issues, signed commits, exact-head ultra reviews, CI and readback are explicit and practiced. GitHub reports `master` unprotected, so repository settings do not enforce those manual gates. |

Security engineering and production assurance are different judgments. Supported runtime/release proof is incomplete; no numerical security grade waives those gates. Independent slice judgments span 78–85 for provisioning maintenance/architecture, 81–85 for Android testing/architecture and 83–85 for native reliability/build quality. The combined table applies one rubric across those scopes.

## Scope and method

Three independent ultra source passes cover Android/state/IPC/data, provisioning/storage/server operations, and tunnel/native/root/build ownership. The coordinator cross-checked the finding paths, current GitHub issue/merge state, documentation, CI reports and scope. The checkout's 620 tracked entries were inventoried; a tracked-entry count is not a claim that every binary, generated output or dependency was line-reviewed.

| Area | Reviewed boundary |
| --- | --- |
| Android | Complete reads of 64 Kotlin owners covering application entry, activities, non-VPS fragments/widgets, binding, models, preferences, config stores and utilities. Manifest/resource callers received targeted review. |
| Provisioning | Complete maintained provisioning-owner reads, including 22 Kotlin owners, VPS Fragment/widgets and twelve app-facing scripts; transport, vaults, mutation intent, backup/restore and disclosure paths. |
| Tunnel/native/build | Config/crypto types, userspace/kernel backends, root/tools integration, Go/JNI handles/bind/UAPI/overlays/runtime preparation, maintained patches, Gradle/CI and dependency boundaries. |
| Tests | Full file/method inventories with targeted fixture/assertion reads around ownership, interleavings and historical repairs. The 114 tracked Android/JVM test/support files are not an assertion-by-assertion review claim; Go and Python controls retain their own scope. |
| Documentation/resources | Current entrypoints, ownership/contracts, agent/process/template rules, coverage records and relevant resource wiring. Earlier translation and original-size UI reviews are reused within their recorded scopes. No fresh complete linguistic or visual audit is claimed. |
| External/generated material | Dependency pins, interfaces, notices, producer/consumer paths and prior diagnostics; no wholesale third-party source audit, independent cryptanalysis, font/wrapper decompilation or review of generated compiler trees. |

The main Android source contains 90 Java/Kotlin files and approximately 20,067 lines; the inspected tunnel/native source-and-test inventory contains 53 Java/Kotlin/Go/C-family files and approximately 9,230 lines. Twelve maintained server-script files contain approximately 1,845 lines. These are inventory context, not complexity or coverage scores. The largest coordinating owners are the VPS Fragment (2,647 lines), VPS ViewModel (1,734), TunnelManager (952), BindingAdapters (920) and VPS store (886). Size alone is not a defect or a reason for a foundation rewrite.

## Current findings

### C244-BOOT — P2: disabled boot restoration is bypassed

At `Application.kt:128–129`, every application creation starts manager initialization. Successful `TunnelManager.onCreate` then calls `restoreState(true)` at `:335–337`; `force=true` bypasses the preference at `:378–385`. A boot broadcast cold-starts the application before `BootShutdownReceiver` handles the event. Its separate `restoreState(false)` cannot cancel the already scheduled forced path.

With an eligible installation, accessible storage, a selected healthy kernel/root backend and saved running names, this can bring a tunnel UP despite **Restore on boot** being disabled. The setting defaults false and its disabled summary promises no boot activation. Android and native reviewers independently corroborated this source path. No reboot was performed.

Repair owner: [#244](https://github.com/novakin/vpn0wn-android/issues/244). Separate inventory initialization from caller-authorized restoration; prove false/true boot controls and preserve supported interactive/process/always-on behavior. A personal-phone reboot is not implied.

### C244-TILE — P2: failure fallback can reverse a completed transition

`QuickTileService.kt:93–125` sends TOGGLE, then launches `TunnelToggleActivity` on any non-cancellation failure. `TunnelManager.changeTunnelState:522–540` can successfully change the backend before a later observation/persistence failure is reported. The fallback activity submits a new relative TOGGLE, so a successful UP can become DOWN, or a successful DOWN can become UP.

The supported path includes API 24–33 and API 34+ when overlay permission permits the fallback. A transient post-transition observation failure suffices; no new physical tile reproduction was run. This is distinct from tile-membership repair #85.

Repair owner: [#247](https://github.com/novakin/vpn0wn-android/issues/247). Preserve admitted tunnel identity and an absolute target, restrict permission recovery to its actual case, and test failure after successful UP/DOWN with no inverse replay.

### C244-RENAME — P2: running-name persistence is not migrated on rename

`TunnelManager.setTunnelName:438–475` renames the config/object and reactivates an UP tunnel without updating the running-name record. `saveState:402–403` persists names, and `restoreState:378–390` filters current profiles against them. State-notification reconciliation does not save the set.

If the userspace process dies after an active `old` to `new` rename and before another state save, startup sees profile `new` but retained marker `old`, so that profile is not restored. Another later mutation can heal the marker; that narrower trigger is part of the finding. Existing rename controls omit persisted restoration identity.

Repair owner: [#246](https://github.com/novakin/vpn0wn-android/issues/246). Settle the running-name record at the existing rename owner, preserving unrelated names, failed-mutation recovery and lock ordering. Prove fresh-manager restoration after UP rename and no activation after DOWN rename.

### C244-SELECTION — P2: late restored selection is not published to existing views

`BaseActivity.kt:58–72` awaits inventory while `created=false`. Restored Detail/Editor views can bind the initial null selection. The later nonnull assignment is suppressed by the setter's `created` guard at `:30–40`; setting the flag afterward sends no listener notification. `TunnelDetailFragment.onViewStateRestored` and the editor's restored-state owner have no inventory-completion rebind.

The trigger is cold/recreated selection with slow inventory resolving after view restoration; ordinary warm recreation can avoid it. Existing inventory tests assert the eventual activity field without proving attached-fragment publication. This is distinct from #177's VPN-consent continuation.

Repair owner: [#245](https://github.com/novakin/vpn0wn-android/issues/245). Publish one current selection after view readiness. Cover delayed/already-loaded inventories and missing/error outcomes with actual attached fragments; preserve drafts and avoid premature subclass callbacks.

### C244-RUNTIME — P3: runtime preparation ignores tracked patch changes

`tunnel/tools/libwg-go/Makefile:44–54` makes `.prepared` depend only on the compiler archive, then exits whenever the marker exists. Editing `goruntime-boottime-over-monotonic.diff` with the same Go version leaves the generated runtime unchanged. Forcing the library recipe does not invalidate that prepared compiler/runtime.

This is a deterministic preparation-dependency/guard defect, not a claim that the current Go 1.26.9 patch or APK is wrong. It is distinct from #66's repaired library-input rebuild and #215's verified fresh maintenance run.

Repair owner: [#248](https://github.com/novakin/vpn0wn-android/issues/248). Track actual preparation inputs and replace only the owned generated runtime when they change; a new prerequisite alone cannot overcome the existing marker shortcut. Preserve archive verification, locking and valid cache reuse.

## Reconciled and rejected concerns

The earlier #209–#215 owners are CLOSED on GitHub and their responsible repairs are present. Translation filenames no longer become shell source; kernel observation failure is not empty/DOWN; delayed config publication is generation/name/membership guarded; app selection is gated until inventory loads; DNS search domains are not converted to IPv4 routes; config key equality is by value; native compiler/module maintenance is updated. Do not count these historical defects as current findings. MTU location and generated modification-notice follow-ups are also present.

Two plausible candidates were rejected rather than converted to issues:

- Local ARM64 test APKs use `intermediates/apk`, while CI uses `outputs/apk`. Actual cached AGP 9.1.0 bytecode selects deployment output when an injected ABI/API property exists; the retained local build had `android.injected.build.abi`, and ordinary CI does not. This does not establish a workflow-path bug. Conditional APK execution still needs its own actual run.
- VPS elapsed/health labels recalculate on rendering/re-entry under the documented manual-refresh contract. No background ticker or polling was promised; an idle label alone is not a new defect. Privileged actions perform their own current admission/verification.

Large-route RootShell backpressure and the earlier editor-navigation race lack a confirmed production trigger in this review and are not new findings. No inverse provisioning-store/TunnelManager lock order was established.

## Evidence and limits

Existing exact-head [CI run 39051900010](https://github.com/novakin/vpn0wn-android/actions/runs/39051900010) passes on the source-equivalent `99be54b4` tree: **1,244 UI debug tests, one release-startup test and 23 tunnel tests**, zero failures/errors/skips and zero Detekt findings. UI debug/release and Detekt executed; tunnel tests were FROM-CACHE. Supporting checks passed: eighteen offline VPS controls, five translation-import controls, six kernel-cleanup controls and six Go groups/seven JNI controls. Only conditional APK assembly/artifact steps skipped. The full reports/task log were independently parsed during #243 acceptance; they are reused here, not newly executed by this review.

The newly identified paths lack their composed regression controls. A green existing suite therefore does not contradict their source traces. No new test, build, APK installation, benchmark, privileged command, phone action or live VPS operation was run for this assessment.

Historical native evidence remains useful and bounded: selected API-24/28 Keystore/storage controls, scoped separate-app URI/component enforcement, synthetic/kernel peer transport and selected Pixel UI checks do not establish native SSH, every provider/authentication path, managed-user/DPC timing, external IPv4/IPv6/DPI behavior, crash durability, all ABIs or signed release acceptance. No destructive restore target was supplied. No speed or battery gain is measured by this review.

The native reviewer freshly checked official Go download metadata, supporting the current 1.26.9 maintained line. This pass ran no new complete dependency/advisory resolution or binary scanner. Earlier #215 symbol-aware and stripped-binary limits remain in the security audit; they are not a present blanket clean bill of health.

## Recommended order and refactoring boundary

1. Repair boot-policy bypass (#244) and inverse tile replay (#247), with allowed/denied and success-after-failure controls.
2. Repair rename restoration identity (#246) and late selection publication (#245), testing complete owner-to-consumer sequences.
3. Repair runtime preparation invalidation (#248), then finish existing native/provider/SSH/restore/release acceptance under its own issue gates.
4. Keep Canvas/localization and feature decisions with #80/#143/#195/#196. Speed/battery work remains last under #47/#52 measurement gates.

Keep the current foundation. Use the responsible owner, existing coroutine/transaction boundaries and native APIs. A targeted extraction from the large VPS Fragment/ViewModel or manager is justified only by a concrete duplicated responsibility or reviewable invariant; file length alone does not justify a broad refactor, new framework, scheduler or abstraction layer. No production repair is delivered by this report.

The coordinator is satisfied with the bounded source assessment and honest evidence distinctions. Independent final-report review and publication/merge acceptance belong to the documentation PR; recording findings does not close their repair issues or establish release readiness.

## Post-repair assessment — 2026-10-18

**Overall engineering assessment: 85/100.** Delivered source is `29bc2a8ddd77c88fa7f4bbc6fee0851ddf826716`, tree `7de00d016b4d7f7bfda1f0c93648465baf4743bc`, identical to independently reviewed and CI-tested `07259b598847fbf954ab56106e6aa6755c68a4a9`. This follow-up preserves the original 80/100 assessment at `5882460d` above. Scores are bounded engineering judgments, not completion percentages, defect probabilities or security certificates.

The four Android state findings and native preparation finding above have delivered owner repairs. Current source separates boot/inventory/interactive restoration, publishes delayed selection to attached views, persists renamed running identities, carries an absolute original tile target and invalidates native preparation when its actual inputs change. The corresponding regressions exercise those composed outcomes. Earlier #209–#215 repairs remain present. No new confirmed actionable product defect was found within the follow-up source/caller scope; no additional product correction is proposed from this pass. Known incomplete feature and assurance work retains its existing issue ownership.

### Current ratings

| Area | Rating | Basis and practical limit |
| --- | ---: | --- |
| Architecture | **87/100** | Existing presentation, manager/backend, transport and persistence owners fit. Repaired startup/continuation policy composes more consistently; multi-stage orchestration remains complex. |
| Code quality and reliability | **85/100** | Confirmed state/build defects are repaired with meaningful failure/ownership controls. Broad platform, interruption and remote-outcome behavior remains incompletely evidenced. |
| Maintainability | **79/100** | Narrow owner changes and existing helpers avoid unnecessary layers. The large VPS Fragment/ViewModel, manager and reflection-heavy fixtures still carry substantial review cost. |
| Security engineering | **82/100** | Permission, consent, host-pin, authenticated storage/disclosure and privileged transaction boundaries are credible source controls. Actor-matched, signed-artifact, provider, root/user and live assurance remains partial. |
| Tests and verification | **86/100** | Composed regression controls and bounded native evidence improved. Test inventory is not assertion coverage; process-death restoration and broader physical/release/live matrices remain open. |
| Documentation | **88/100** | Current delivery/grades and native evidence are reconciled with historical failures, exact sources and remaining gates. Large historical guides still impose maintenance cost; qualified language and complete UI/accessibility proof remain separate. |
| Agent/process contract | **90/100** | Issue ownership, signed commits, final-head independent ultra, CI and readback are practiced. A fresh branch query still reports `master` unprotected; manual gates are not server enforcement. |

The overall judgment weights sensitive composed behavior and evidence quality; it is not a mechanical average. Scoped independent judgments are Android reliability 86/security 85, provisioning quality 82/security 80 and coordinator test/build quality 88. These local judgments do not independently rate the whole project or waive release gates.

### Review coverage and reuse

The current inventory has 650 tracked entries. The original 578-entry ledger was validated against its pinned modes/object IDs. Relative to that original source, 460 entries are unchanged, 118 changed and 72 added; relative to rated `5882460d`, 553 are unchanged, 67 changed and 30 added. File counts identify scope; they do not certify every binary, generated file, test assertion or dependency.

| Area | Current review boundary |
| --- | --- |
| Android/state/IPC | A 248-path fingerprint ledger binds fourteen fresh owner/composed-delta reads, fifty unchanged owners reused from the prior complete Android pass, three provisioning boundaries, the manifest, nine changed/147 unchanged resources, fourteen test sources and ten guides. Depth is recorded per entry; unchanged resource fingerprints do not become a new linguistic or visual review. |
| Provisioning/security | Forty-four maintained source inputs and forty-four test-source fingerprints cover all 25 provisioning Kotlin owners, twelve maintained scripts and related presentation/manager/authentication/output consumers. Nine added production owners and current removal, rotation, endpoint, secret-output and progress composition received fresh attention. Nine of twelve prior bounded backup fingerprints match; the three changed consumers were reassessed. |
| Native/root/build | A 52-input fingerprint record binds maintained source/build inputs and caller boundaries; forty original non-UI native paths match the rated baseline. Existing backend, JNI/Go and root/tools criteria are reused at matching inputs, with fresh preparation/build-delta inspection. The prior 170-file external inventory retains its interface/exclusion scope; no new complete third-party audit is claimed. |
| Tests/CI/dependencies | A 180-input coordinator inventory includes 49 changes since the rated baseline. Fresh targeted assertion/fixture reads cover the repaired owners and progress, plus the actual workflow, preparation recipe and its eight controls. Other tests retain earlier review depth; no assertion-by-assertion review of every test or fresh complete advisory resolution is claimed. |

These inventories overlap and are not additive coverage totals. The reviews use actual ownership/caller paths and exact committed/source fingerprints. Historical FAILED criteria stay at their original revisions; unchanged files can participate in a repaired composition, as with Config equality repaired through Key ownership. Current source review does not rerun historical controls or convert their actors/artifacts into current whole-app proof. Draft #103 remains excluded.

### Actual evidence and remaining gates

Successful exact-head [CI 39094646970](https://github.com/novakin/vpn0wn-android/actions/runs/39094646970) retains **1,407 UI debug, 23 tunnel and one release-startup test**, zero failures/errors/skips and zero Detekt findings. UI debug, release startup and Detekt executed; tunnel tests were FROM-CACHE. Eighteen offline VPS, eight preparation, five translation-import, six cleanup, six Go race groups and seven JNI controls passed. Conditional APK steps skipped. These are the delivering feature run's results, reused at the identical tree, not newly executed by this report.

The [native record](2026-10-18-native-consent-transfer-acceptance.md) adds independently reviewed R8 selection/replacement/removal and A4 equal-version upgrade/transfer/rollback outcomes at their own pinned debug artifacts. It retains the original failures, incomplete process-death restoration, normalized VPN-metadata limits and the original failed A4 stop wrapper with separate read-only reconciliation. Five R8 consent-owner blobs are unchanged through this delivered source; that parity does not make its probe a whole-current-master or release APK.

The [current security follow-up](2026-10-12-app-root-security-audit.md#delivered-source-follow-up--2026-10-18) retains stable findings, original actors and sign-off gaps. No fresh complete advisory query or binary/source scan ran for this assessment; prior maintained-toolchain/linked-symbol results keep their exact artifact and OpenPGP-advisory limits. No new test, build, device or live-server operation was run by the source reviewers.

Continue the existing issue-owned gates: physical/provider/configured-authentication and spoken accessibility acceptance; root-tool coexistence and signed/release migration; native wizard/SSH/private-key and disposable-server restore/fault/client-traffic proof; external transport, system VPN policy and recovery boundaries. Protocol/automation/bootstrap choices remain unselected where recorded. Speed/battery stays last and needs the controlled real unplugged baseline. No 100/100, whole-app security sign-off, DPI/performance gain or production-ready claim follows.

The coordinator is satisfied with this bounded post-repair source assessment and its evidence distinctions. Keep the existing foundation; change or extract an owner only for a demonstrated requirement or invariant. Final independent review of the maintained reports, documentation checks and publication/merge readback belong to the documentation PR.
