# Codebase reassessment after delivery repairs

Last updated: 2026-10-17 (Europe/Paris).

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
