# Tunnl — WireGuard Android

## Source of Truth — Prompt Instructions

**`CLAUDE.md` is the single source of truth for agent instructions.**
`AGENTS.md` is an exact mirror kept in sync for Codex compatibility.

When instructions change:
1. Edit `CLAUDE.md`
2. Copy verbatim to `AGENTS.md`
3. Never edit `AGENTS.md` directly — changes will be overwritten

If they diverge, `CLAUDE.md` wins.

## Project Context

Tunnl ports the engineering-console UI in `.design/` onto WireGuard Android and adds the documented VPS provisioning flow. Discover the active branch from Git; do not assume a branch name or a clean worktree.

- Design reference: `.design/` — **read-only, never modify**
- UI progress: `docs/ui-redesign-roadmap.md` — **read before every UI task**
- Backend/functional changes: `docs/backend-changelog.md` — **read before every non-UI task**
- Architecture: existing DataBinding + Fragment pattern — keep it, don't refactor the foundation
- Improvement priorities and acceptance: `docs/improvement-plan.md`
- Contribution, verification and merge workflow: `docs/development.md`
- Current ownership and consequential decisions: `docs/architecture.md`
- User/operator handling: `docs/operations.md`
- Config, transition and provisioning contracts: `docs/backend-contracts.md`

## Before Starting Any Task

1. Inspect Git branch, status, responsible source and relevant tests. Preserve unrelated tracked/untracked changes and retained evidence.
2. Read README, the improvement plan and the owning guide. Read the UI roadmap for UI work and backend changelog for functional work; read both when the task spans both.
3. Define the smallest useful outcome, compatibility requirements and acceptance checks. Trace the failing layer before editing.
4. For UI work, read the relevant `.design/*.jsx`, identify the exact component/prop and check existing `ui/src/main/res/values/colors.xml` and `styles.xml` tokens first.

The JSX prototype owns visual intent, not runtime truth. Never treat demo data, rendered screens or planned actions as working integration.

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
- New behavior or fix → add to `### Done` with file, method, and what changed
- In-progress work → add to `### In Progress`, move to Done when complete

Both agents read both files to know the current state. Stale docs = duplicated work or regressions.

Update the owning guide in the same change when behavior, setup, contracts or workflow changes. Link to detailed facts instead of duplicating them. Mechanical changes need no prose update.

Record consequential decisions in `docs/architecture.md`: problem, decision/status, alternatives, consequences/limits and verification or migration impact. Routine edits need no decision record. Revisit decisions when requirements change; keep the superseded rationale.

## Code Standards

- **Architecture fit.** Prefer supported platform APIs. Do not modernize unrelated code or replace the foundation without a task requiring it.
- **Kotlin-first for touched Android behavior:** lifecycle-bound coroutines over ad hoc threads/callbacks; preserve upstream Java/native ownership and existing contracts.
- **DataBinding:** `@BindingAdapter` in `BindingAdapters.kt` for all custom binding logic; keep binding expressions in XML simple.
- **No comments** unless the WHY is non-obvious (hidden platform constraint, subtle invariant, deliberate workaround). Never explain what code does.
- **No dead code.** No `_unused` prefixes or half-implemented stubs. Compatibility code needs an actual supported caller, documented reason and removal condition.
- **No premature abstraction.** Reuse fitting helpers; consolidate duplicated ownership. Do not add parameters or layers for hypothetical future use.
- **Readable code, no code golf.** Prefer clear names and straightforward control flow over dense one-liners, clever compression or nested conditional expressions. Optimize total complexity and ownership, not line count.
- **No overengineering.** Add a helper, type, dependency or refactor only when the requested behavior or a concrete invariant needs it. Do not invent extension points, settings, adapters or services for hypothetical use.
- **No scope creep.** Implement the requested behavior and documented scope. UI fidelity follows JSX; functional fixes follow runtime contracts and the improvement plan.

## Verification and Completion

- Follow `docs/development.md` and `docs/qa-test-plan.md`. Run focused meaningful tests first; broaden for shared state, contracts or cross-cutting/high-risk changes.
- Prefer injected command responses and temporary files to real root/network operations. Exercise failure, cancellation, compatibility and redaction boundaries when affected.
- For UI changes, inspect actual output at supported sizes/themes and relevant font-scale/RTL configurations. JVM/static proof does not establish device or live-server behavior.
- For documentation-only changes, check local links, fences, examples, source claims and the instruction mirror. Do not run unrelated runtime operations merely to update prose.
- Use `Invoke-CodexQuiet` for noisy commands. Retain current-task and failed-task logs under ignored `.artifacts/codex-command-logs`; inspect failures progressively. Report status or at most 40 relevant failure lines.
- Follow contribution/merge instructions and templates in `docs/development.md`. Commit only intentional files; preserve the user's existing index. Do not amend, rebase, reset or force-push unless requested.
- Every PR, including a draft, needs a meaningful description based on `.github/PULL_REQUEST_TEMPLATE.md`: problem/result, linked issue or action, scope/compatibility, actual verification and remaining limits. Update its title and description when scope changes.
- Apply existing GitHub labels matching the issue/PR scope and assign the responsible maintainer, normally `novakin`. Set actual metadata; a name or label written in the description is insufficient. Review the filled template against the change before publishing or updating it.
- Use an account-linked author/committer identity and verify published commits map to the intended GitHub account. Resolve historical email association without rewriting history when possible.
- Cross-cutting agent/process changes need an independent review of the final instructions, owning docs and templates. Follow the user's requested reviewer effort; record findings and their resolution before marking the work verified.
- Keep commit, issue and PR text free of connector attribution, generated-by footers and AI co-author trailers.
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
ui/src/main/java/com/wireguard/android/
  fragment/         TunnelListFragment, TunnelDetailFragment, TunnelEditorFragment, VpsProvisioningFragment
  widget/           TunnelSignalView (home mini-diagram), TunnelThroughputView (detail hero)
  databinding/      BindingAdapters.kt — all @BindingAdapter + @JvmStatic formatters
  model/            ObservableTunnel.kt — state, config, statistics (BaseObservable)
  activity/         MainActivity, LogViewerActivity
  preference/       KernelModuleEnablerPreference (settings screen)
  provisioning/     models, RemoteShellClient, TunnlProvisioningClient, VpsServerStore, VpsDeployMapper

ui/src/main/res/
  layout/           tunnel_list_item.xml, tunnel_detail_fragment.xml, tunnel_editor_fragment.xml
                    tunnel_editor_peer.xml, tunnel_detail_peer.xml, log_viewer_activity.xml
  values/           colors.xml (Tunnl palette), styles.xml (Tunnl styles), strings.xml
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
- `TunnlLabelText` — 10sp mono caps, ink_3 (field labels, section prefixes)
- `TunnlMonoValueText` — 15sp mono, ink (tunnel names, prominent values)
- `TunnlMonoSubText` — 10sp mono, ink_3 (sub-labels, hints)
- `TunnlDetailRow` — row container (match_parent, paper_2 bg, 14dp h-padding, 10dp v-padding)
- `TunnlConfigRowLabel` — wraps TunnlLabelText for ConfigRow label
- `TunnlConfigRowValue` — 13sp mono, single line, ellipsize end
- `TunnlEditorField` — plain EditText (no background, jetbrains mono, 13sp, ink)
- `TunnlEditorFieldReadOnly` — TunnlEditorField + ink_3 + not focusable

**Cards:** 0dp elevation, 1dp `tunnl_rule` stroke, 8dp corners, `paper_2` background.

**Section headers:** `§01`, `§02`… `TunnlLabelText` in `rule_strong` + mono title + horizontal 1dp rule divider.

## JSX Reference Quick Map

| Screen | JSX file | Key components |
|--------|----------|----------------|
| Home / Tunnel list | `screens-home.jsx` | `TunnelCard`, `MiniDiagram`, `Stat`, `StatusStrip` |
| Detail | `screens-detail.jsx` | `WireDiagram`, `ConfigRow`, `PeerCard` |
| Editor | `screens-detail.jsx` | `FieldGroup`, `Field` |
| Add sheet | `screens-detail.jsx` | `ImportTile` |
| Log viewer | `screens-detail.jsx` | `LogEntry`, filter chips |
| Settings | `screens-settings.jsx` | `SettingsGroup`, `SettingsRow` |
| Wire animation | `schematic.jsx` | `TunnelThroughputView` source |
| Data/formatters | `data.jsx` | `formatBytes`, `formatRate`, `formatAge` |
| Palette/tokens | `styles.css` | CSS custom properties → Android color resources |
