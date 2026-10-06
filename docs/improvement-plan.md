# Tunnl improvement plan

[Documentation index](../README.md#project-documentation) · Reviewed 2026-10-06

## Scope and baseline

Improve agent instructions, documentation/process and code reliability while preserving the Android DataBinding/Fragment architecture and WireGuard configuration compatibility.

The review covered Android UI/model/services, VPN/config/native integration, provisioning scripts, resources, tests and build configuration. Baseline: branch `master`, HEAD `6658cbea30c1a2ae8fc0ff0aae365da34cff9f59`, with substantial existing tracked and untracked work. Findings describe the reviewed worktree, not a clean release snapshot. Recheck the responsible source and current Git state before each implementation task.

| Area | Static-review rating | Main reason |
| --- | --- | --- |
| Agent instructions | 7/10 | Useful visual guidance and canonical mirror, but incomplete functional ownership, failure and evidence rules. |
| Documentation | 6/10 | Strong design detail and QA intent, but incomplete current-runtime contracts and contribution workflow. |
| Codebase | 5/10 | Useful architecture and reusable components, with privacy, provisioning, lifecycle and correctness defects. |
| Overall | 6/10 | Foundations are worth preserving; confidence needs repairs and relevant verification. |

These are engineering judgments from static review. They are not measured test scores or a security certification. Ratings remain unchanged until implementation has supporting evidence.

The initial checkpoint documented the plan and linked it from README. Implementation is now tracked in the delivery table and [development evidence](development.md#recorded-verification). [Issue #5](https://github.com/novakin/wireguard-android/issues/5) and [draft PR #6](https://github.com/novakin/wireguard-android/pull/6) cover the first workflow/privacy slice; other actions remain open.

Preserve unrelated work, `.design/` as read-only reference, upstream attribution and existing architecture. APK builds run only when explicitly requested. Live/destructive VPS validation requires explicit authorization for a disposable environment and must remain outside default checks. A local change does not imply a commit, push, merge, release or deployment.

## Reuse decisions

### Existing project assets

| Asset | Decision | Boundary |
| --- | --- | --- |
| DataBinding, Fragments, observable models and backend interfaces | Keep and extend responsible owners. | Repair transitions and lifecycle defects without replacing the foundation. |
| [WireGuard configuration types](../tunnel/src/main/java/com/wireguard/config/Config.java), peer and endpoint parsers | Reuse for imported/generated profiles. | Preserve multi-peer profiles, endpoint-free configurations and supported fields. |
| [Palette](../ui/src/main/res/values/colors.xml), [styles](../ui/src/main/res/values/styles.xml), fonts, icons and Canvas widgets | Reuse the visual system and drawing primitives. | Mock payloads and missing interactions do not become runtime truth. |
| Snackbar, import, error, quantity and log helpers | Reuse established paths. | Repair affected behavior before wider reuse; preserve deliberate formatting differences. |
| [Provisioning client](../ui/src/main/java/com/wireguard/android/provisioning/TunnlProvisioningClient.kt), models, script bundle and remote-shell boundary | Keep orchestration and transport separation. | Shell quoting does not replace config-input validation; guard tests do not prove real transport cleanup. |
| [VPS store](../ui/src/main/java/com/wireguard/android/provisioning/VpsServerStore.kt) | Reuse DataStore transactions and non-secret fleet records. | A recent sync timestamp is not proof of a running VPN. Credentials require their own protected storage. |
| Existing JUnit, Robolectric, coroutine tests and fixtures | Extend existing test infrastructure. | Test actual contracts and failure paths; do not add a second framework without a concrete gap. |
| Gradle wrapper/version catalog, Detekt, editor settings and attribution | Retain existing tooling and maintenance boundaries. | Keep the [pinned upstream update process](../third_party/wireguard-install/UPSTREAM.md) and [font attribution](font-attribution.md). Do not invoke the upstream interactive installer from Android. |

### Reference projects

Reference paths below identify sibling-repository material reviewed locally. They are provenance, not runtime dependencies or automatic transfers of those repositories' policies.

| Source | Reuse | Adapt or exclude |
| --- | --- | --- |
| `server-security-audit/AGENTS.md` | Ownership, responsible-layer changes, explicit failures, cancellation cleanup, privacy, compatibility, scoped verification and completion evidence. | Replace Python/scanner/report examples with Android/VPN/provisioning invariants. Do not import its read-only runtime policy or blanket dependency-approval gate. |
| `server-security-audit/docs/development.md` | Test ownership, change checklist, dated environment/results/skips and explicit proof limits. | Separate JVM, device/native-backend and server evidence. |
| `server-security-audit/docs/architecture.md`, `docs/roadmap.md`, `docs/report-format.md` | Data flow, consequential decisions, scope/start/acceptance/evidence, and precise success semantics. | Keep existing Android domain states; do not copy audit status enums or collector architecture. |
| `VPS_Migration/mkdocs-site/docs/blueprints/` | Change record, QA checklist, incident and rollback structure where provisioning/recovery needs them. | Omit VPS metadata, badges, Nginx sections, monitoring catalogs and MkDocs infrastructure. |
| `VPS_Migration/verify-docs.ps1` and drift validator | Fence validation, accumulated failures and useful status/evidence consistency checks. | Port only checks justified by Tunnl's documentation contract. A standalone link checker was not found in the searched sources. |
| `Vpn0wn/docs/DEVELOPMENT.md` and Markdown lint configuration | Document ownership, prototype/runtime distinction, hygiene and command-exit handling. | Adapt lint rules; do not copy permissive settings blindly or install tooling merely for this plan. |
| `Vpn0wn` persistence, WireGuard import and preflight tests | Cancellation, failed replacement, concurrent updates, IPv6/endpoints, config injection and failure-reason scenarios. | Translate behaviors into existing Android tests. Do not import its single-peer restriction, WinUI/MVVM, SQLite/DPAPI or WireSock implementation. |
| `loft/docs/TESTING.md` | Accessibility/visual/fallback checklists and honest device-only proof limits. | Use Android sizes, font scales, RTL and supported themes. |
| Other reviewed sibling guides | Staged validation before promotion, focused changes and version/changelog consistency. | These mostly overlap the selected sources; no extra process framework is needed. |

No existing issue, PR/MR or CONTRIBUTING templates were found in the searched reference repositories. Contribution templates below must be authored from these conventions.

## Delivery order and action states

This file owns the improvement priorities and action states. The UI roadmap owns visual specifications and fidelity details; the QA plan owns coverage requirements; the backend changelog records functional history and integration gaps. Link detailed work to its owner instead of maintaining duplicate completion lists.

| Order | Actions | Status | Start condition | Acceptance boundary |
| --- | --- | --- | --- | --- |
| 0 | Document plan and README entry | Documented | Completed repository review. | Plan is discoverable, links resolve and unrelated work is preserved. |
| 1 | A1, D1/D2 workflow | Implemented and locally verified | Requested implementation begun. | Canonical instructions, owning guides, contribution templates, labels/assignees and mandatory PR descriptions are established. |
| 2 | C1–C3 privacy and provisioning safety | C1 in progress; C2–C3 planned | Reproduce each defect with synthetic inputs or controlled failure injection. | Secrets stay out of logs; unmanaged state survives; mutations report honest outcomes and preserve recovery. |
| 3 | C4–C6 contracts and state/lifecycle | Planned | Affected behavior and compatibility requirements are traced. | Producer/consumer contracts agree; transitions settle; no orphaned VPN or main-thread shutdown wait. |
| 4 | C7–C10 UI correctness and proof | Planned | Relevant behavior fixes and deterministic fixtures are available. | Focused regressions pass; affected output is checked at supported sizes and configurations. |
| 5 | C11–C12 real SSH and feature wiring | Planned | Library decision recorded; provisioning safety/contracts repaired. | Real transport owns deadlines/cancellation; UI and QR/import use actual validated results. |
| Throughout | Maintain D1/D2 with affected changes | Ongoing | An owning fact or workflow changes. | Source, tests and owning documentation agree in the same change. |

Minimal process work comes first. Urgent safety fixes need not wait for the entire documentation set. Tests and documentation travel with each fix rather than becoming a later cleanup phase.

## A1 — Agent instruction contract

Edit [CLAUDE.md](../CLAUDE.md) first, then copy it verbatim to [AGENTS.md](../AGENTS.md). Preserve the design map and token guidance, but route UI-specific requirements to UI tasks.

The revised contract must:

- Discover the active branch and worktree instead of relying on the stale `ui-redesign` branch claim.
- Keep Fragments/adapters responsible for presentation, models/backends for VPN state, provisioning client for remote orchestration and stores for persistence.
- Extend existing owners and native APIs first. Explain a new dependency or abstraction through a concrete requirement and maintenance tradeoff.
- Avoid code golf and overengineering. Use clear names and straightforward control flow; add a helper, type, setting or layer only for a concrete requirement or invariant. Optimize total complexity and ownership rather than line count.
- Apply Kotlin-first guidance to touched Android behavior without requiring incidental rewrites of upstream Java/native code.
- Preserve config, persisted-data and script/JSON compatibility. Replace the blanket ban on compatibility shims with a rule against unused code and unjustified permanent shims.
- Handle expected failures where their meaning is known. Never turn denied access, parsing failure or an unreachable server into empty/successful state.
- Release operation-owned resources on cancellation. Propagate cancellation and reconcile uncertain remote outcomes before retrying or reporting success.
- Exclude private keys, PSKs, passwords and raw secret-bearing payloads from logs, errors, fixtures and documentation. Use synthetic secrets in tests.
- Define success by the requested behavior. SSH exit status is not VPN health; a sync timestamp is not ONLINE; revocation requires effective removal.
- Preserve explicit prerequisites and sequencing. Do not introduce a scheduler for a fixed operation flow.
- Run focused meaningful checks first, then broader checks for shared state, contracts or high-risk changes. Report actual environment, results, skips and gaps.
- Validate documentation-only changes through links, examples and source claims. Do not run unrelated device or server operations to update prose.
- Keep consequential decisions and their alternatives/limits in architecture documentation. Revisit them when requirements change.
- Link to contribution and merge instructions instead of repeating templates in agent files.
- Preserve dirty worktrees, explicit-build/live-system restrictions, bounded command output and `Invoke-CodexQuiet` evidence handling.
- State changed behavior/files, verification and residual risk at completion. Distinguish local work, commits, merges and releases.

Acceptance: instruction files are byte-for-byte identical; every named path/command is valid; UI tasks retain JSX/token checks; functional tasks have an explicit route; no copied Server-Audit rule contradicts Tunnl behavior or existing authorization.

## D1 — Documentation ownership

| Document | Ownership and planned change |
| --- | --- |
| [README.md](../README.md) | Entry point, current Tunnl capabilities and documentation index. Preserve upstream attribution; distinguish official WireGuard distribution links from this fork's delivery state. |
| [Architecture](architecture.md) | Module ownership, UI/model/backend and provisioning data flows, persistence/privilege boundaries and consequential decisions with alternatives and limits. |
| [Development](development.md) | Setup, repository-native checks, test ownership, contribution/merge workflow and dated verification evidence. |
| [Operations](operations.md) | Import/connect/export, permissions, backend selection, diagnostics and recovery. Label incomplete VPS workflows accurately. |
| [Backend contracts](backend-contracts.md) | Tunnel transitions, supported config behavior, provisioning arguments/JSON, failure/cancellation meaning and compatibility boundaries. |
| [UI roadmap](ui-redesign-roadmap.md) | Keep visual specification, design tokens, fidelity gaps and feature detail. Distinguish rendered screens from working backend actions. |
| [Backend changelog](backend-changelog.md) | Keep functional history and explicit integration gaps. Replace duplicated improvement status with links to action IDs when implemented. |
| [QA test plan](qa-test-plan.md) | Keep layer/coverage requirements and opt-in VPS use case. List implemented coverage only when tests exist; record execution evidence in development documentation. |
| This plan | Own audit/remediation priorities, action status and acceptance. Link to source/tests and owning guides as actions land. |

Write current facts from source. Label proposed behavior, known gaps and dated evidence separately. Do not invent supported versions, successful verification, release infrastructure or operational owners. Avoid dedicated ADR files until decision history needs them.

Acceptance: each detailed fact has one owner; README reaches every guide; examples agree with source; planned features are distinguishable from working behavior; local Markdown links/fences are valid; no real credentials are included.

## D2 — Contribution templates, merge workflow and hygiene

The following GitHub templates are implemented:

| File | Required fields |
| --- | --- |
| `.github/ISSUE_TEMPLATE/bug_report.md` | Revision, Android/API/backend/environment, reproduction, expected/observed behavior, sanitized evidence and impact. Include server OS/script context only when relevant. |
| `.github/ISSUE_TEMPLATE/improvement.md` | Concrete problem, smallest useful scope, existing assets to reuse, compatibility concerns, acceptance and relevant verification. |
| `.github/PULL_REQUEST_TEMPLATE.md` | Problem and resulting behavior, linked issue/action ID, affected scope, commands/results/environment/skips, owning docs and remaining risks. Include rollback or migration details when relevant. |

Keep merge instructions in `docs/development.md`. The PR template supplies the merge-request checklist; do not add a second platform template unless the repository uses that platform.

The workflow is: inspect baseline and dirty scope; define bounded acceptance; reproduce the affected behavior; fix the responsible layer; run proportionate checks; update owning docs; review final diff/staged scope; record evidence. Merge requires reviewable scope, relevant proof and disclosed gaps. Commits and external actions follow the user's task and existing project rules.

Add `.artifacts/` to ignore rules when implementing this action. Retain current-task and failed-task logs; do not clean unrelated artifacts. Use existing tools before introducing lint dependencies. Adapt useful fence checks from the VPS verifier; add link/mirror checks only as needed for repeatable maintenance.

Acceptance: all three templates render correctly and collect useful evidence without requesting secrets; merge instructions name the relevant checks and limits; no default workflow invokes APK builds or destructive VPS tests; evidence artifacts cannot enter the intended source change accidentally.

## Codebase actions

The locations below identify responsible sources. Trace their current callers and invariants before editing; prior review findings may change with unrelated ongoing work.

| ID | Scope and responsible sources | Required behavior and focused proof |
| --- | --- | --- |
| C1 | Secret-safe logging: [RootShell](../tunnel/src/main/java/com/wireguard/android/util/RootShell.java), [ProvisioningJson](../ui/src/main/java/com/wireguard/android/provisioning/ProvisioningModels.kt). | Kernel dumps and malformed provisioning output never reach logs with private keys/PSKs. Test synthetic secret-bearing stdout, malformed/truncated JSON and ordinary diagnostic failures; inspect captured log content as well as exception text. |
| C2 | Ownership/input safety: [uninstall](../third_party/wireguard-install/tunnl/uninstall.sh), [install](../third_party/wireguard-install/tunnl/install.sh) and shared script validation. | Reject unmanaged `wg0` before stopping services or changing files. Validate endpoint/DNS inputs and reject line/control injection before privileged writes. Test unmanaged config preservation, valid supported inputs and injected config sections/hooks without running them. |
| C3 | Transactional mutations: [install](../third_party/wireguard-install/tunnl/install.sh), [add](../third_party/wireguard-install/tunnl/add-peer.sh), [remove](../third_party/wireguard-install/tunnl/remove-peer.sh), [revoke](../third_party/wireguard-install/tunnl/revoke-peer.sh), [uninstall](../third_party/wireguard-install/tunnl/uninstall.sh). | Serialize per-interface read/modify/apply operations with a native lock. Forced reinstall must align returned config with runtime. Failed apply must preserve/restore prior state or report incomplete recovery. Revocation, service stop and package purge cannot swallow failures. Test concurrent mutations, failed writes/apply/restart, retryability and actual removal postconditions. |
| C4 | Producer/consumer contracts and health: [ProvisioningModels](../ui/src/main/java/com/wireguard/android/provisioning/ProvisioningModels.kt), [client](../ui/src/main/java/com/wireguard/android/provisioning/TunnlProvisioningClient.kt), [plan script](../third_party/wireguard-install/tunnl/plan.sh), [store](../ui/src/main/java/com/wireguard/android/provisioning/VpsServerStore.kt), [mapper](../ui/src/main/java/com/wireguard/android/provisioning/VpsDeployMapper.kt). | Align mutation name/Boolean fields with actual script responses; handle `force` consistently in plan/install. Persist/map actual observed running health rather than inferring ONLINE from sync time. Add command-construction and real-script-response fixtures, stopped/unknown/stale states and unchanged contract controls. |
| C5 | Config-compatible activation and shutdown: [GoBackend](../tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java). | Endpoint-free profiles remain valid; explicit ListenPort is respected. Preserve intentional preflight behavior for applicable profiles and make failure policy explicit. Long activation cannot hold a lock needed by main-thread service destruction. Test configuration controls, startup failure/cancellation, teardown during activation and service reacquisition. Native/device limits must be disclosed. |
| C6 | Settled transitions and view ownership: [TunnelManager](../ui/src/main/java/com/wireguard/android/model/TunnelManager.kt), [detail fragment](../ui/src/main/java/com/wireguard/android/fragment/TunnelDetailFragment.kt), [kernel preference](../ui/src/main/java/com/wireguard/android/preference/KernelModuleEnablerPreference.kt). | Delete during TOGGLE cannot orphan a VPN. Initialization failure settles waiting callers. Delayed config A cannot overwrite selected B or a destroyed view. Failed backend switching restores usable preference/UI state. Test reversed completions, transition/delete races, load failure and retry. |
| C7 | Accurate throughput: [BindingAdapters](../ui/src/main/java/com/wireguard/android/databinding/BindingAdapters.kt). | New idle snapshots show zero rate; a later burst uses the right sample interval. RX/TX bindings share one result for a snapshot. Test burst/idle/burst, rapid binding reads, counter reset and reconnect with deterministic time/statistics. |
| C8 | Import, copy and editing correctness: [detail layout](../ui/src/main/res/layout/tunnel_detail_fragment.xml), [peer layout](../ui/src/main/res/layout/tunnel_detail_peer.xml), [clipboard](../ui/src/main/java/com/wireguard/android/util/ClipboardUtils.kt), [naming dialog](../ui/src/main/java/com/wireguard/android/fragment/ConfigNamingDialogFragment.kt), [QR scanner](../ui/src/main/java/com/wireguard/android/util/QrCodeFromFileScanner.kt), [key filter](../ui/src/main/java/com/wireguard/android/widget/KeyInputFilter.kt), [name filter](../ui/src/main/java/com/wireguard/android/widget/NameInputFilter.kt). | Copy full keys while displaying abbreviated values. Invalid/duplicate names retain import and editable error state. QR retries reopen input. Replacements account for selected text length. Add focused clipboard/dialog/retry/select-all and single-character replacement regressions. |
| C9 | Honest integration harness: [vps_integration_test.py](../scripts/vps_integration_test.py). | Assert running state, exact expected peer presence/absence, final config removal and package removal when purge is requested. Inject negative responses to prove incorrect postconditions fail before any live run. Keep key-only disposable-host/destructive gates and cleanup; never wire it into default checks. |
| C10 | Visual, accessibility and localization proof: [QA plan](qa-test-plan.md), [UI roadmap](ui-redesign-roadmap.md), affected resources/views. | Reuse fixtures for deterministic dark-mode checks; add RTL/font-scale and supported-size coverage for affected surfaces. Audit missing locale resources, including `tunnel_udp_preflight_failure`; document intentional fallback instead of inventing translations. Preserve focus, labels, touch targets and error recovery. Select additional screenshot tooling only for a demonstrated coverage gap. |
| C11 | Concrete SSH transport and protected credentials: [RemoteShellClient](../ui/src/main/java/com/wireguard/android/provisioning/RemoteShellClient.kt) and the existing client/factory boundary. | Record maintained Android-compatible library choice and alternatives. Implement host-key pinning/trust handling, password/private-key auth, real deadlines and cancellation that closes owned channel/session resources. Use Android Keystore-backed credential protection. Test hung execute/upload, unreachable hosts, wrong fingerprint, cancellation and cleanup against the real library API. Wrapper-only tests are insufficient. |
| C12 | Real VPS workflow: existing provisioning Fragments, mapper/store, import path and QR view. | Wire detect/plan/install/sync/add/revoke/remove to actual results and progress. Preserve wizard state and lifecycle ownership. Generated profiles pass existing WireGuard parsing/import; QR encodes the same usable profile. Render actual health and peer counters. Test empty/error/stopped states, navigation/recreation, user actions and generated-profile round trips. |

C1–C10 are repairs or proof for reviewed behavior. C11–C12 complete existing documented integration scope. Additional prototype screens and cosmetic fidelity items remain in the UI roadmap; this plan does not automatically expand into every unimplemented design feature.

## Verification and definition of done

Use existing repository checks appropriate to the touched owner:

- Documentation: validate links, balanced fences, examples/claims and instruction mirror when affected; run `git diff --check`.
- UI logic: focused tests within `:ui:testDebugUnitTest`; use Robolectric or existing coroutine-test facilities for affected Android/lifecycle behavior.
- Config/backend logic: focused tests within `:tunnel:testDebugUnitTest`; retain existing config fixtures and controls.
- Kotlin static analysis: existing Detekt task/config when affected. Broaden tests for shared helpers, contracts or cross-cutting changes.
- APK/device: `:ui:assembleDebug` only when explicitly requested; device/emulator evidence remains separate from JVM/static checks.
- Provisioning scripts: offline syntax/contract and injected-command tests first. A shell syntax check does not prove mutation safety.
- Live VPS: `vpsIntegrationTest` only for an explicitly authorized disposable target after offline safety/contracts pass. Passing fixtures do not establish real SSH, systemd or packet behavior.

Use `Invoke-CodexQuiet` for noisy commands and retain evidence under `.artifacts/codex-command-logs`. Record exact command, revision/worktree scope, environment, result, skips and unresolved limits. Never include credentials or unrestricted raw output.

An action is implemented when its bounded behavior and owning documentation are changed. It is locally verified only when its required checks have run with recorded results. Merge and release/deployment are separate states and must never be inferred from local success.

Before closing an action:

1. Confirm the root cause and preserve surrounding compatibility/invariants.
2. Prove the relevant failure and successful control behavior with proportionate tests or direct inspection.
3. Update owning docs in the same change and resolve contradictory claims.
4. Review the final diff and intentional file scope; preserve unrelated work and evidence.
5. Record status, actual proof and remaining device/server gaps here by reference to the owning verification record.

Do not claim full completion while required commands are running or relevant proof remains unperformed. If an operational check is unavailable, state the missing proof and retain the action's verification gap.

## Conditional work

| Opportunity | Trigger | Smallest next decision |
| --- | --- | --- |
| Android CI | A requested need for repeatable PR checks. | Reuse native unit/static tasks in one appropriate environment; keep APK/live/destructive checks separately scoped. Vpn0wn's Windows/.NET workflow is an example, not a portable implementation. |
| Repeatable documentation checker | Repeated link/mirror/fence drift makes manual checking unreliable. | A small native script or existing lint tool covering the actual contract; no documentation platform migration. |
| Focused refactor | Added behavior exposes duplicated ownership or an obstructive structure. | Consolidate the responsible code while preserving behavior; no automatic Compose/MVVM, DI framework or controller rewrite. |
| Packaging/release process | A requested distribution or rollback need. | Explicit artifacts, version locations, attribution and validation; no inferred publication or deployment. |

## Recorded evidence and next task

2026-10-06: repository-wide static audit and local reuse review completed. Earlier review checks passed `git diff --check` and parsed 135 resource/manifest XML files. Translation review identified `tunnel_udp_preflight_failure` absent from 34 locale files. These checks are historical review evidence, not a build or device result. Source-inspected tests do not count as executed tests. No fresh Gradle, device or live-server validation was performed for the review.

Planning verification on 2026-10-06: 50 local Markdown/source links resolved; both changed documents passed fence and trailing-whitespace checks; `git diff --check` passed. `CLAUDE.md` and `AGENTS.md` remained byte-for-byte identical. A before/after content inventory confirmed 327 pre-existing paths outside README and command artifacts were unchanged. Only README and this new plan were edited; runtime fixes remain planned.

Current delivery: A1/D1/D2 workflow plus C1 logging proof/fix in issue #5 and draft PR #6. Next after this slice: C2–C3 ownership/input and mutation safety repairs with focused regressions and owning documentation. Keep broader feature wiring behind repaired provisioning contracts and the recorded SSH transport decision.
