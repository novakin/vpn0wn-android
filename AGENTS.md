# vpn0wn — WireGuard Android fork

Last updated: 2026-10-11 (Europe/Paris).

## Source of Truth — Prompt Instructions

**`AGENTS.md` is the single source of truth for project agent instructions.** Edit this file directly when instructions change. Keep detailed workflow and runtime facts in their owning guides and update affected references in the same change.

## Project Context

vpn0wn ports the engineering-console UI in `.design/` onto WireGuard Android and adds the documented VPS provisioning flow. Discover the active branch from Git; do not assume a branch name or a clean worktree. Current source packages use `com.vpn0wn.*`; configured release/debug IDs are `com.vpn0wn.android` and `com.vpn0wn.android.debug`. Legacy visual resource keys/basenames and native/test helper names still await the remaining #71 rename. The [namespace contract](docs/backend-contracts.md#application-and-source-identity--71) owns current export names and retained protocol, persisted and compatibility identities; source changes do not establish renamed artifact/device or cross-app migration acceptance.

- Design reference: `.design/` — **read-only, never modify**
- Brand reference: `docs/assets/vpnown-brand/` — approved artwork; the [Android adoption contract](docs/ui-redesign-roadmap.md#android-brand-asset-adoption--2026-10-10) owns logo use. Outlined Sora logos do not replace Inter Tight/JetBrains Mono UI font roles.
- UI progress: `docs/ui-redesign-roadmap.md` — **read before every UI task**
- Backend/functional changes: `docs/backend-changelog.md` — **read before every non-UI task**
- Architecture: existing DataBinding + Fragment pattern — keep it, don't refactor the foundation
- Improvement priorities and acceptance: `docs/improvement-plan.md`
- Contribution, verification and merge workflow: `docs/development.md`
- Current ownership and consequential decisions: `docs/architecture.md`
- User/operator handling: `docs/operations.md`
- Config, transition and provisioning contracts: `docs/backend-contracts.md`

## Before Starting Any Task

1. Identify repository, active branch, revision and intended integration base. Inspect working/staged scope and refresh the authorized remote base before branching or reviewing a PR; do not assume a local reference is current. Preserve unrelated tracked/untracked changes and retained evidence.
   Put linked worktrees under ignored `.artifacts/worktrees/` by default; follow [workspace preservation and relocation rules](docs/development.md#issues-branches-and-scoped-commits).
2. Read README, the improvement plan and the owning guide. Read the UI roadmap for UI work and backend changelog for functional work; read both when the task spans both. For tracked work, read the relevant live issue/PR discussion, acceptance, approval and dependencies; disclose unavailable GitHub state instead of inferring no work or permission.
3. Define the smallest useful outcome, compatibility requirements and acceptance checks. Trace the failing layer before editing.
4. For UI work, read the relevant `.design/*.jsx`, identify the exact component/prop and check existing `ui/src/main/res/values/colors.xml` and `styles.xml` tokens first.

The JSX prototype owns visual intent, not runtime truth. Never treat demo data, rendered screens or planned actions as working integration.

`.design/` is ignored and can be absent from a clean clone. A UI fidelity change needs the relevant prototype or another user-approved visual reference. If it is missing, request the reference and continue independent work; do not invent the design or block unrelated functional/documentation tasks.

## Ownership and Invariants

- Fragments, activities and binding adapters present state. Models/backends own tunnel state and transitions. Provisioning client owns remote orchestration; transport owns its channels/sessions; stores own persistence.
- Extend the responsible owner and existing parser/helper first. Use native platform APIs or installed dependencies where they fit. Justify new dependencies or abstractions with a concrete requirement and maintenance tradeoff.
- Preserve explicit prerequisites and sequencing; do not add a scheduler for a fixed flow.
- Preserve supported WireGuard config behavior, persisted records and script/JSON contracts. Update producers, consumers, tests and owning docs together for intentional changes.
- Handle expected I/O, parsing and command failures where their meaning is known. Do not swallow programming defects or convert denied access, invalid output or unreachable hosts into empty/successful state.
- Settle waiting callers on success, failure and cancellation. Release operation-owned resources, propagate cancellation and reconcile uncertain remote outcomes before retrying or claiming completion.
- Never log private keys, PSKs, passwords, full secret-bearing configs or raw provisioning output. Use synthetic credentials in fixtures/docs. Ordinary evidence can still contain sensitive metadata; keep it out of source distribution.
- Successful command execution does not prove VPN health. A recent sync is not ONLINE. Revocation, install and uninstall need the requested postconditions.
- Mutations must preserve unrelated configuration and recovery. Ownership checks and input validation happen before privileged writes. Keep destructive VPS tests out of default checks.

## After Every Change

**UI change → update `docs/ui-redesign-roadmap.md`:**
- Completed item → move to `### Done` with a one-line description of what changed and where
- Permanent limitation → add to `### Fidelity Gaps — Remaining` with the technical reason
- Removed from scope → delete from `### Pending`

**Backend/functional change → update `docs/backend-changelog.md`:**
- New behavior or fix → add to `## Done` with file, method, and what changed
- In-progress work → add to `## In Progress`, move to Done when complete

Read the records relevant to the task. Keep completed, in-progress and deferred work distinct; record scope removals with their reason rather than hiding unresolved work as completed.

Assess documentation impact after every code, test, build, configuration, UI, workflow or documentation change. Update affected owning guidance in the same change and search related summaries, examples, agent rules and limitations for contradictions. Give a specific no-impact reason when no documentation update is needed; do not edit unaffected docs just to refresh dates. Links, dates and checked boxes do not replace review of meaning. Link to detailed facts instead of duplicating them.

Date every maintained project Markdown guide, including README and AGENTS.md, with `Last updated: YYYY-MM-DD (Europe/Paris).` near the top. Update that date whenever the file is edited, including small corrections; same-day edits keep the same date. Templates use the equivalent hidden `Template last updated` comment in their source. A read-only review does not change the last-edit date. Follow `docs/development.md` for date scope and historical evidence; do not overwrite original event/test dates or date read-only design, generated or externally owned material.

Record consequential architecture decisions in `docs/architecture.md` and operational policy in its owning guide: problem, decision/status, alternatives, consequences/limits and verification or migration impact. Record approval/date only when evidenced. Routine edits need no decision record. Revisit decisions when requirements change; keep the superseded rationale. Preserve historical event/test dates and revisions; date new substantive records in Europe/Paris and distinguish a scoped review from a new runtime run.

## Work Tracking and Handoff

- For an explicitly authorized ongoing goal, follow the named phase scope, acceptance and merge gates in `docs/improvement-plan.md`. Autonomous merging applies only to that authorized scope after independent review and required checks cover the final head. Track concrete new findings as issues; do not silently add them to the goal. APK, device and live-operation boundaries still apply.
- The improvement plan owns priorities and bounded action states; issues own actionable acceptance, approval and dependencies; PRs own current change-specific acceptance. Link them instead of creating a competing backlog.
- During the authorized GitHub workflow, check for duplicates and create/update an issue for concrete unresolved work. Include observed/expected behavior, impact, bounded scope, approval/dependencies and observable acceptance. Do not create speculative work or a separate issue for each small fix completed within the current PR.
- Default new issues/PRs to `novakin`; preserve deliberate existing assignments and unrelated labels. Use existing repository labels matching scope. Read current fields, prefer additive changes and verify the write/readback; metadata belongs in GitHub fields, not duplicated mutable prose. Report rejected/unverified fields.
- Use a closing PR-description keyword only when merge into the default branch fulfils the issue's full acceptance boundary. Use a non-closing reference for partial work or separate operational proof. Verify the Development link and actual post-merge issue state, or disclose the access limit; a reference or closed unmerged PR is not delivery.
- Keep substantive historical assessments in `docs/reviews/` with scope, revision, evidence, findings/resolution and limits. Routine self-review/CI acceptance stays in the PR. Never commit raw chat, private reasoning, credentials or host reports.
- If GitHub is unavailable, disclose current action status as unverified and retain an explicitly unpublished handoff. Issue/template metadata and an earlier unrelated approval do not authorize new operations.

## Code Standards

- **Architecture fit.** Prefer supported platform APIs. Do not modernize unrelated code or replace the foundation without a task requiring it.
- **Kotlin-first for touched Android behavior:** lifecycle-bound coroutines over ad hoc threads/callbacks; preserve upstream Java/native ownership and existing contracts.
- **DataBinding:** `@BindingAdapter` in `BindingAdapters.kt` for all custom binding logic; keep binding expressions in XML simple.
- **No comments** unless the WHY is non-obvious (hidden platform constraint, subtle invariant, deliberate workaround). Never explain what code does.
- **No dead code.** No `_unused` prefixes or half-implemented stubs. Compatibility code needs an actual supported caller, documented reason and removal condition.
- **No premature abstraction.** Reuse fitting helpers; consolidate duplicated ownership. Do not add parameters or layers for hypothetical future use.
- **Readable code, no code golf.** Prefer clear names and straightforward control flow over dense one-liners, clever compression or nested conditional expressions. Optimize total complexity and ownership, not line count.
- **No overengineering.** Add a helper, type, dependency or refactor only when the requested behavior or a concrete invariant needs it. Do not invent extension points, settings, adapters or services for hypothetical use.
- **Static checks stay meaningful.** Keep Detekt's zero-issue gate. Fix defects at their owner; name meaningful durations, limits and protocol/state values. A narrow rule suppression needs a WHY at the affected declaration, such as unchanged drawing geometry or an existing generic Backend/ConfigStore contract. Do not hide findings with a blanket baseline, disabled analysis, `ignoreFailures` or exception-variable names. Keep cancellation/Error semantics and admitted mutation recovery intact; never reshape correct behavior merely to lower a rule count.
- **No scope creep.** Implement the requested behavior and documented scope. UI fidelity follows JSX; functional fixes follow runtime contracts and the improvement plan.

## Verification and Completion

- Follow `docs/development.md` and `docs/qa-test-plan.md`. Run focused meaningful tests first; broaden for shared state, contracts or cross-cutting/high-risk changes.
- Prefer injected command responses and temporary files to real root/network operations. Exercise failure, cancellation, compatibility and redaction boundaries when affected.
- For UI changes, inspect actual output at supported sizes/themes and relevant font-scale/RTL configurations. JVM/static proof does not establish device or live-server behavior.
- For documentation-only changes, check local links, fences, examples, source claims and consistency with `AGENTS.md`. Do not run unrelated runtime operations merely to update prose.
- Use `Invoke-CodexQuiet` for noisy commands. Retain current-task and failed-task logs under ignored `.artifacts/codex-command-logs`; inspect failures progressively. Report status or at most 40 relevant failure lines.
- Follow contribution/merge instructions and templates in `docs/development.md`. Commit only intentional files; preserve the user's existing index. Do not amend, rebase, reset or force-push unless requested.
- Every PR, including drafts and API-created PRs, needs a meaningful description based on `.github/PULL_REQUEST_TEMPLATE.md`: problem/result, linked issue or action, scope/compatibility, actual verification and remaining limits. Review the complete final diff and filled description before publication; update title/body when scope changes and remove stale prompts.
- Keep one current PR acceptance record: reviewed head, tested revision/environment and CI run when applicable, checks/results/skips, honest author self-review verdict, independent review findings/resolution, documentation impact and unresolved issue links. Reassess the affected delta after edits. Self-review is not independent approval or permission to merge; prior results retain their original scope. Do not commit prose merely to embed its own final SHA or newest routine CI count.
- Set and verify actual GitHub metadata under the work-tracking rules. Before reporting publication complete, confirm published revision, changed-file scope, issue/PR state and applicable checks. Existing CI may run automatically; a workflow file does not prove execution or enforcement, and Server-Audit's CI is not part of this project.
- Use an account-linked author/committer identity and verify published commits map to the intended GitHub account. Resolve historical email association without rewriting history when possible.
- Cross-cutting agent/process changes need an independent review of the final instructions, owning docs and templates. Follow the user's requested reviewer effort; record findings and their resolution before marking the work verified.
- Publish independent ultra review findings and verdicts as GitHub PR reviews anchored to the reviewed commit; use inline comments when the affected lines are in the PR diff. Link the submitted review from the current PR acceptance record and verify its author, state, commit and findings after publication. Reviews submitted through the PR author's account use `COMMENT`; they do not satisfy a required approval from another GitHub account. Keep independently performed analysis distinct from author self-review, and retain earlier reviews with their original scope after edits.
- Keep commit, issue and PR text free of connector attribution, generated-by footers and AI co-author trailers.
- Publish GitHub issues, PRs and reviews through personal account credentials. Do not use connector/GitHub-app credentials for these writes: GitHub adds creator/app attribution outside the body. Verify the published account and app-attribution metadata when exposed; disclose unavailable fields. Read-only connector access is allowed.
- Do not push, publish, merge, deploy or alter live systems unless requested. APK builds require an explicit request; destructive VPS validation requires an explicitly authorized disposable target.
- Finish or deliberately stop required commands before the final answer. State behavior/files changed, actual checks/results/skips and unresolved risks. Distinguish implementation, local verification, commit, merge and release/deployment.

## Build Command

**APK assembly runs only when explicitly asked.** Focused unit/static checks follow the development guide.

```
wsl -- bash -c "cd /mnt/c/Users/Guillaume/Documents/Projets/wireguard-android && ./gradlew :ui:assembleDebug --console=plain"
```

Fix all errors before reporting done. DataBinding errors appear as `error: cannot find symbol` on generated binding classes — check XML attribute names and types first.

Run noisy commands through `Invoke-CodexQuiet`; preserve the real exit code rather than piping Gradle output through `tail`.

## Architecture Map

```
ui/src/main/java/com/vpn0wn/android/
  fragment/         TunnelListFragment, TunnelDetailFragment, TunnelEditorFragment, VpsProvisioningFragment
  widget/           TunnelSignalView (home mini-diagram), TunnelThroughputView (detail hero)
  databinding/      BindingAdapters.kt — all @BindingAdapter + @JvmStatic formatters
  model/            ObservableTunnel.kt — state, config, statistics (BaseObservable)
  activity/         MainActivity, SettingsActivity, LogViewerActivity
  preference/       KernelModuleEnablerPreference (settings screen)
  provisioning/     models, RemoteShellClient, Vpn0wnProvisioningClient, VpsServerStore, VpsDeployMapper

ui/src/main/res/
  layout/           tunnel_list_item.xml, tunnel_detail_fragment.xml, tunnel_editor_fragment.xml
                    tunnel_editor_peer.xml, tunnel_detail_peer.xml, log_viewer_activity.xml
  values/           colors.xml (legacy palette keys), styles.xml (vpn0wn styles), strings.xml
  drawable/         tunnl_list_item_background, tunnl_active_rail, ic_action_*
  font/             inter_tight, jetbrains_mono_regular, jetbrains_mono_medium

tunnel/src/         GoBackend.java — VPN backend (touch carefully)
.design/            JSX prototype — reference only
third_party/wireguard-install/tunnl/  app-facing non-interactive server scripts
docs/               architecture, development, operations, backend contracts, plan, roadmap, changelog, QA
```

## Design System Tokens

**Colors** (`res/values/colors.xml`):
- Backgrounds: `tunnl_paper` (darkest) → `tunnl_paper_2` → `tunnl_paper_3` (lightest surface)
- Text: `tunnl_ink` → `tunnl_ink_2` → `tunnl_ink_3` (dimmed)
- Accent: `tunnl_signal`, `tunnl_signal_deep`
- Borders: `tunnl_rule` (hairline), `tunnl_rule_strong`
- State: `tunnl_warn`, `tunnl_error`

**Fonts:**
- UI labels, titles: `@font/inter_tight`
- Data, keys, values, monospace fields: `@font/jetbrains_mono_regular` or `jetbrains_mono_medium`

**Styles** (`res/values/styles.xml`):
- `Vpn0wnLabelText` — 10sp mono caps, ink_3 (field labels, section prefixes)
- `Vpn0wnMonoValueText` — 15sp mono, ink (tunnel names, prominent values)
- `Vpn0wnMonoSubText` — 10sp mono, ink_3 (sub-labels, hints)
- `Vpn0wnDetailRow` — row container (match_parent, paper_2 bg, 14dp h-padding, 10dp v-padding)
- `Vpn0wnConfigRowLabel` — wraps Vpn0wnLabelText for ConfigRow label
- `Vpn0wnConfigRowValue` — 13sp mono, single line, ellipsize end
- `Vpn0wnEditorField` — plain EditText (no background, jetbrains mono, 13sp, ink)
- `Vpn0wnEditorFieldReadOnly` — Vpn0wnEditorField + ink_3 + not focusable

**Cards:** 0dp elevation, 1dp `tunnl_rule` stroke, 8dp corners, `paper_2` background.

**Section headers:** `§01`, `§02`… `Vpn0wnLabelText` in `rule_strong` + mono title + horizontal 1dp rule divider.

## JSX Reference Quick Map

| Screen | JSX file | Key components |
|--------|----------|----------------|
| Home / Tunnel list | `screens-home.jsx` | `HomeScreen`, `TunnelCard`, `MiniDiagram`, `Stat` |
| Detail | `screens-detail.jsx` | `DetailScreen`, `ConfigRow`, `PeerCard`; diagram uses `schematic.jsx` |
| Editor | `screens-detail.jsx` | `FieldGroup`, `Field` |
| Add sheet | `screens-detail.jsx` | `AddSheet`, `AddOption` |
| Log viewer | `screens-detail.jsx` | `LogsScreen`, inline rows and filter chips |
| Settings | `screens-settings.jsx` | `SettingsGroup`, `SettingsToggleRow`, `SettingsActionRow` |
| VPS fleet/detail/provision/user | `screens-deploy.jsx` | `ServerFleetScreen`, `ServerDetailScreen`, `ServerProvisionScreen`, `VpnUserScreen` |
| Wire animation | `schematic.jsx` | `SchematicDiagram`, `WireDiagram`; Android `TunnelThroughputView` reference |
| Data/formatters | `data.jsx` | `fmtBytes`, `fmtRate`, `fmtAgo`; verify Android units/contracts before reuse |
| Palette/tokens | `styles.css` | CSS custom properties → Android color resources |
