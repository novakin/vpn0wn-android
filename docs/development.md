# Development and contribution workflow

[Documentation index](../README.md#project-documentation) · Reviewed 2026-10-06

Use focused changes, the existing Android architecture and explicit verification limits. [CLAUDE.md](../CLAUDE.md) owns agent instructions; [AGENTS.md](../AGENTS.md) is its verbatim mirror.

## Setup and source boundaries

The Gradle wrapper pins Gradle 9.3.1; the version catalog pins AGP 9.1.0 and dependencies. Both Android modules compile against SDK 36, target Java 17 source compatibility and support API 24 or newer. Use a wrapper/AGP-compatible JDK and an Android SDK/NDK installation matching the native build requirements. These source declarations are not an executed device support matrix.

Initialize the existing Git submodules when preparing a checkout. Keep SDK paths in ignored `local.properties`; do not commit machine-specific paths, signing material or credentials. Windows work can use Android Studio or the documented WSL Gradle invocation. A Windows SDK path is not necessarily usable inside WSL; check the responsible environment before diagnosing build failures.

`.design/` is a read-only, locally supplied visual reference. It is ignored and may be absent from a clean checkout. Do not modify or publish it as part of runtime work. Keep native/backend ownership described in [architecture](architecture.md), config and script behavior in [backend contracts](backend-contracts.md), and user handling in [operations](operations.md).

## Document ownership

| Owner | Content |
| --- | --- |
| [README](../README.md) | Entry point and current capabilities. |
| [Architecture](architecture.md) | Module boundaries, data flow and consequential decisions. |
| This guide | Setup, checks, issue/PR/merge workflow and dated verification evidence. |
| [Operations](operations.md) | User/operator workflows, diagnostics and recovery limits. |
| [Backend contracts](backend-contracts.md) | Config/transitions, provisioning arguments/results and compatibility. |
| [Improvement plan](improvement-plan.md) | Action priority, state and acceptance. |
| [UI roadmap](ui-redesign-roadmap.md) | Visual intent, progress and fidelity details. |
| [Backend changelog](backend-changelog.md) | Functional change history and integration gaps. |
| [QA plan](qa-test-plan.md) | Required coverage by layer and opt-in VPS validation. |

Update the smallest affected set in the same change. Link to detailed facts instead of duplicating long status blocks. Preserve dated historical evidence; never present old test totals as a permanent guarantee.

## Issues, branches and scoped commits

Use the [bug template](../.github/ISSUE_TEMPLATE/bug_report.md) for reproducible failures and [improvement template](../.github/ISSUE_TEMPLATE/improvement.md) for bounded work. Link the relevant plan ID. Describe observable acceptance, successful controls and relevant failure/recovery behavior. Do not include keys, passwords or raw secret-bearing output.

Apply actual GitHub labels matching scope, using existing `bug`, `enhancement` or `documentation` labels as relevant. Assign the responsible maintainer, normally `novakin`. Issue templates provide defaults; PR templates do not apply labels/assignees automatically. Use GitHub fields or CLI `--label`/`--assignee`, and verify the resulting metadata.

Before editing, inspect branch, working tree and staged scope. Group pre-existing changes by purpose; a dirty worktree is not permission to commit everything. Preserve unrelated tracked/untracked files and the user's index. Use a dedicated branch from the intended integration base, normally `master`, without stashing/resetting unrelated work merely for convenience.

Commit coherent, reviewable behavior with its relevant tests and owning docs. Stage explicit paths or intentional hunks; review the staged file list and diff. Existing changes in a touched file require additional care: a whole-file stage can include unrelated work. Use Conventional Commits subjects where appropriate. Do not amend, rebase, reset or force-push unless requested.

Use an account-linked author/committer email and check the published GitHub author mapping. The maintainer's GitHub-provided noreply address is appropriate for future commits. If older commits use another email, adding/verifying that address can restore account association without changing hashes. Do not rewrite history merely to repair attribution when email association resolves it.

Keep artifacts under ignored `.artifacts/`. Inspect personal/editor files before inclusion; do not add them merely because they are untracked. Retain current-task and failed-task command logs. Keep commit, issue and PR text free of connector attribution, generated-by footers and AI co-author trailers.

## Pull requests and merge instructions

Every PR, including a draft, needs a meaningful description using the [PR template](../.github/PULL_REQUEST_TEMPLATE.md). Fill it before opening; an empty checklist is insufficient. Explain the problem and resulting behavior, link the issue/action, identify scope and compatibility, list actual commands/results/environment/skips, and disclose remaining risks. Update both title and description when implementation scope changes.

Review the filled description before publishing: does it describe the final behavior, link the right issue, name correct base/head and metadata, record only executed checks, and disclose operational/verification limits? Remove stale prompts, placeholders and inapplicable sections. Completeness and accuracy are the acceptance criteria; a subjective perfect score cannot replace evidence.

Push only the reviewed branch/commits within the user's requested workflow. A request to open a PR includes the necessary branch push; it does not authorize merging or deploying. A draft can expose a concrete checkpoint while work continues, provided completed and pending behavior are clearly distinguished. Keep the description current as verified commits arrive.

Before merge:

1. Confirm the intended base/head and inspect the complete PR diff, not only the latest commit.
2. Check linked acceptance criteria, compatibility and affected failure/cancellation/recovery behavior.
3. Require relevant local or CI evidence; disclose missing device/server proof and unresolved risks.
4. Confirm owning docs and instruction mirrors agree with source and no secret/evidence artifacts are staged.
5. Resolve review findings and conflicts without overwriting unrelated work.
6. Merge only when requested and the review requirements are satisfied. Keep release/deployment state separate.

For cross-cutting agent/process changes, obtain an independent review of the final instructions, guides and templates. Use the reviewer effort requested by the user, including ultra reasoning when specified. Record findings, fixes and unresolved limits in the verification record; re-review material corrections. The author still owns final consistency and the intentional staged scope.

If a historical branch has no unique commits relative to the integration base, no merge is needed. Delete it only when branch cleanup is requested and its work is accounted for. Never infer that a remote branch contains uncommitted local changes.

## Verification commands and ownership

Run focused repository-native checks first. JVM test tasks compile their dependencies but do not assemble an APK. APK assembly still requires an explicit request. Commands below run from the repository root in an appropriate configured environment:

```bash
./gradlew :ui:testDebugUnitTest --tests com.wireguard.android.provisioning.ProvisioningJsonTest --console=plain
./gradlew :tunnel:testDebugUnitTest --tests com.wireguard.config.ConfigTest --console=plain
./gradlew :ui:detekt --console=plain
```

For Windows/WSL, keep the real exit status:

```powershell
Invoke-CodexQuiet -Command 'wsl -- bash -c "cd /mnt/c/Users/Guillaume/Documents/Projets/wireguard-android && ./gradlew :ui:testDebugUnitTest --console=plain"' -WorkingDirectory $PWD.Path
```

`Invoke-CodexQuiet` is an existing local PowerShell helper, not a project dependency. Other environments should redirect noisy output to an ignored task log while preserving the command's exit code. Do not hide Gradle failure behind `tail` or a successful shell pipeline.

| Test/check owner | Relevant proof |
| --- | --- |
| `tunnel/src/test` config tests/fixtures | Valid and malformed WireGuard configuration and parser compatibility. |
| `ui/src/test` provisioning tests | JSON/result contracts, command failures, request arguments, guarded timeout/cancellation, store transactions and mapper states. |
| `ui/src/test` utility/binding tests | Formatting, log parsing and binding behavior; Robolectric adds Android resources, logs and lightweight view behavior. |
| Focused lifecycle/state tests | Affected transition, selection, initialization and recovery behavior; add regressions when repairing those owners. |
| Existing Detekt configuration | Kotlin static checks; this is not runtime or device proof. |
| Device/emulator checks | VPN permission/service behavior, native backend and actual UI/navigation at affected sizes, themes, font scales and RTL. |
| Offline provisioning tests | Script syntax/contracts and failure injection without changing a host. |
| `vpsIntegrationTest` | Explicitly authorized disposable Ubuntu/Debian host only; see [QA use case](qa-test-plan.md#disposable-vps-integration-use-case). |

Broaden tests for shared state, public/script contracts or cross-cutting/high-risk changes. Use deterministic clocks, synthetic profiles, injected commands and temporary files where they answer the question. Do not introduce a framework, CI or release pipeline merely to record proof.

For documentation-only work, validate relative links, fences, examples/source claims, mirror equality and `git diff --check`. A search with no matches returns `rg` exit 1; exit 2 is a search error. An executed test suite with disclosed skips differs from source-inspected tests, and neither proves unperformed device or live-server behavior.

## Evidence and completion

Record revision/worktree scope, platform/tool versions when relevant, exact commands, results, skips and unresolved limits. Keep synthetic command output and failed logs locally; never attach raw credential/config payloads. Check actual postconditions instead of treating command exit as sufficient.

Review implementation for clarity and architectural fit. Avoid code golf: use clear names and straightforward control flow rather than clever compressed expressions. Avoid overengineering: reuse existing owners/native APIs and add layers, dependencies or refactors only for a concrete requirement or invariant. Judge total complexity and ownership, not line count.

Use these meanings consistently:

- Implemented: bounded source/test/documentation changes exist.
- Locally verified: relevant checks ran with recorded results and limits.
- Committed/pushed: exact intentional commits exist locally/remotely.
- Merged: integration branch contains the reviewed change.
- Released/deployed: the requested distribution/operational action completed with its own evidence.

Do not end required work while commands or reviewers are still running. State behavior/files changed, commit/PR state, actual verification and residual risk. Update plan action states by linking here; keep broader unimplemented actions visible.

## Recorded verification

2026-10-06 planning checkpoint `aa5feb94`: README and improvement plan were committed. Fifty local links resolved, fences/trailing whitespace and `git diff --check` passed, instruction mirrors matched, and the unrelated pre-existing content inventory was unchanged. This was documentation proof; no APK, device or live-server check was performed.

2026-10-06 A1/D1/D2 workflow: canonical/mirrored instructions, four owning guides, issue/PR templates and artifact exclusion were implemented for [issue #5](https://github.com/novakin/wireguard-android/issues/5) and [draft PR #6](https://github.com/novakin/wireguard-android/pull/6). Fourteen touched Markdown files across Android and Server-Audit passed 127 local-link checks, balanced-fence and trailing-whitespace checks; instruction mirrors were byte-identical. The templates were reviewed for issue links, actual labels/assignee, scope/compatibility/privacy, executed evidence, remaining risks and delivery state. GitHub issue/PR metadata names `novakin` with `bug` and `documentation` labels. Runtime verification is recorded separately below when complete.

Independent ultra reasoning review covered all 14 scoped Markdown files in both repositories. It found one source-contract error: the add-peer input table omitted the app's required `--allowed-ips`. The table now matches `VpsAddPeerRequest.args()`, including required `--name`/`--allowed-ips` and optional `--dns`. Targeted re-review confirmed the correction, readability/overengineering rules and review records; no material findings remain. The reviewer repeated all 127 local-link/fragment, fence, whitespace and mirror checks successfully. This review did not execute runtime, device or live checks.

### C1 secret-safe logging — 2026-10-06

The focused tests ran in the existing mixed worktree based on checkpoint `aa5feb94`, using WSL Debian, OpenJDK 21.0.10, Gradle 9.3.1 and Robolectric SDK 28 with a plain Android Application. Unrelated UI/backend changes were present; this is not an isolated-checkout or device result. Workflow documentation was subsequently committed as `7de79f82`.

| Run | Result |
| --- | --- |
| Six new regressions against unrepaired logging | Five failed as expected; the valid-profile control passed. |
| Repaired logging plus existing JSON/command-output tests | 21 passed; zero failures, errors or skips. |

```bash
./gradlew :ui:testDebugUnitTest --tests com.wireguard.android.util.RootShellLoggingTest --tests com.wireguard.android.provisioning.ProvisioningLoggingTest --tests com.wireguard.android.provisioning.ProvisioningJsonTest --tests com.wireguard.android.provisioning.ProvisioningCommandOutputTest --console=plain
```

Synthetic root-shell streams exercise command framing, returned config/stdout and nonzero exit status without launching root. Captured logs exclude synthetic private keys, PSKs and command/stderr payloads. Provisioning regressions cover failed stdout/stderr, malformed JSON without secret-bearing parser causes, unchanged valid profiles, and cleanup failure without hiding the completed result or logging exception payloads. No production API or new dependency was added for these tests.

The baseline failure and repaired command logs remain under ignored `.artifacts/codex-command-logs` (`20261006-100618-779-362b25d93d07469e9eecda40458afe02.log` and `20261006-102200-351-eb832d8e95684eea8e05f84e02831ff9.log`). XML test reports confirm the four suite totals: 2, 4, 10 and 5. Gradle reported existing deprecation warnings; no APK assembly, device/native-root session, real SSH or live/destructive VPS check was run. Historical logs remain potentially sensitive, and this repair does not establish global sanitization of arbitrary server/transport messages.

Final independent ultra reasoning review found no material findings in the C1 owning-doc additions or four filled issue/PR descriptions. It checked 15 documentation files plus those four drafts: 130 local links/fragments, fences, whitespace and the instruction mirror passed. Source and retained test evidence supported the stated baseline failures and repaired results. Eighteen external links were not fetched by that reviewer. This was a read-only documentation review, not a runtime rerun or formal GitHub approval.
