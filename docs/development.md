# Development and contribution workflow

Last updated: 2026-10-06 (Europe/Paris).

[Documentation index](../README.md#project-documentation) · Setup/process substantively updated 2026-10-06 (Europe/Paris); historical runtime evidence retains its original scope.

Use focused changes, the existing Android architecture and explicit verification limits. [AGENTS.md](../AGENTS.md) is the sole project agent-instruction file; edit it directly when instructions change.

## Setup and source boundaries

The [Gradle wrapper](../gradle/wrapper/gradle-wrapper.properties) pins Gradle 9.3.1; the [version catalog](../gradle/libs.versions.toml) pins AGP 9.1.0 and dependencies. Both [UI](../ui/build.gradle.kts) and [tunnel](../tunnel/build.gradle.kts) modules compile against SDK 36, target Java 17 source compatibility and declare minimum API 24. Source compatibility is separate from the JDK running Gradle. Use a wrapper/AGP-compatible JDK; the historical local run below used OpenJDK 21.0.10, not proof of every JDK combination. These declarations are not an executed device support matrix.

Clone the fork with `git clone --recurse-submodules https://github.com/novakin/wireguard-android.git`, or run `git submodule update --init --recursive` in an existing checkout. Install Android SDK Platform 36, compatible Android build tools, NDK and CMake. This checkout does not pin an NDK/CMake package version; record the versions actually used instead of inventing a tested matrix. Keep SDK paths in ignored `local.properties`; do not commit machine-specific paths, signing material or credentials.

Native builds use the [CMake configuration](../tunnel/tools/CMakeLists.txt) and [Go Makefile](../tunnel/tools/libwg-go/Makefile): host C compiler, NDK make, POSIX shell/tools, `flock`, `curl`, `tar`, `patch` and SHA256 verification. The Makefile downloads pinned Go 1.24.3; downloading Gradle/dependencies and Go needs network access on a cold cache. macOS needs [flock](https://github.com/discoteq/flock) and the required command-line tools. Use a configured Linux/macOS or WSL Linux toolchain. The clean repository tracks `gradlew`, not `gradlew.bat`; native Windows execution is not established by opening Android Studio. A Windows SDK path is not necessarily usable inside WSL; check the environment that runs Gradle before diagnosing build failures.

From that environment, `./gradlew :ui:assembleDebug --console=plain` assembles a debug APK after setup. The debug application ID is `com.wireguard.android.debug`, derived from [Gradle properties](../gradle.properties) and the UI module's `.debug` suffix. Agent APK assembly requires an explicit request. Release signing, Maven publication and distribution are separate actions; version/publication configuration does not prove an artifact was released.

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
| `docs/reviews/` | Substantive historical assessments with scope, revision, evidence, findings/resolution and limits. |

Assess documentation impact after every code, test, build, configuration, UI, workflow or documentation change. Update the smallest affected owning set in the same change and search related summaries, examples, agent rules and limitations for contradictions. Update docs when their behavior, setup, API/contracts, operations, examples or process facts change; record reviewed sections and a specific no-impact reason when no update is needed. Update each edited document's date under the convention below. Links, dates and checked boxes do not prove meaning is correct; do not edit unaffected docs merely to refresh dates. Link detailed facts instead of duplicating long status blocks. Routine self-review/CI acceptance belongs in the PR; no separate report is required for each edit. Never commit raw chat, private reasoning or host/credential reports.

### Dates and decisions

Every maintained project Markdown guide uses `Last updated: YYYY-MM-DD (Europe/Paris).` near the top: README, AGENTS.md, docs (including historical review files when appended/corrected), the fork-maintained provisioning README and upstream-reference guide. Set it to the actual local date whenever that file is edited, including wording, link or metadata corrections. Same-day edits retain the same date; a read-only check does not refresh it. The field records the latest document edit, not whole-guide revalidation or the date of every described event. This initial dating pass starts on 2026-10-06; it does not invent earlier edit dates.

Issue/PR template source uses `<!-- Template last updated: YYYY-MM-DD (Europe/Paris). -->`; place it after issue-template front matter or before the first PR heading. Update it when the template changes. The hidden source date is not an issue/PR creation, event or verification date. Keep licenses, generated artifacts, read-only `.design/` and externally owned vendored content under their own contracts; do not stamp them as project-maintained guidance.

Use YYYY-MM-DD with Europe/Paris stated for new human-written records; retain machine timestamps in UTC or with an explicit offset. Distinguish substantive updates, scoped reviews, decisions and test execution. A narrow review is not whole-guide runtime revalidation. Preserve historical dates, revisions, evidence and limits; correct inaccuracies with a dated clarification. Do not invent missing event dates/timezones or refresh old tests to today's date.

Record consequential architecture decisions in the architecture guide and significant operational policy in its owning guide: problem, decision/status, rationale, alternatives, consequences, verification/migration impact and revisit condition. Include approval/date only when evidenced; identify missing approval rather than invent it. Preserve superseded rationale. Routine edits need no decision record or new ADR framework.

## Issues, branches and scoped commits

Use the [bug template](../.github/ISSUE_TEMPLATE/bug_report.md) for reproducible failures and [improvement template](../.github/ISSUE_TEMPLATE/improvement.md) for bounded work. Link the relevant plan ID. Describe observable acceptance, successful controls and relevant failure/recovery behavior. Do not include keys, passwords or raw secret-bearing output.

Apply actual GitHub labels matching scope, using existing `bug`, `enhancement` or `documentation` labels as relevant. Default new agent-created issues/PRs to `novakin`; preserve deliberate existing assignments and unrelated labels. Read current fields, prefer additive changes and verify the response/readback; disclose rejected or unverified fields. GitHub fields own mutable metadata. Issue templates provide defaults only for their submissions; API-created issues/PRs need explicit metadata. PR templates do not apply labels/assignees automatically. Do not invent labels, deadlines, assignment automation or a project board. Assignment and absence of flags do not grant approval.

Before editing, identify repository, branch, revision and intended integration base. Inspect working/staged scope and refresh the authorized remote base before branching or reviewing; a local reference can be stale. Group pre-existing changes by purpose; a dirty worktree is not permission to commit everything. Preserve unrelated tracked/untracked files and the user's index. Use a dedicated branch from the intended integration base, normally `master`, without stashing/resetting unrelated work merely for convenience.

The improvement plan owns priorities/action states; issues own concrete acceptance, approval and dependencies; PRs own current change-specific acceptance. Read current issue/PR discussion during tracked work, not chat memory alone. Check duplicates before creating/updating an issue for concrete unresolved work within the authorized GitHub workflow. Include observed/expected behavior, impact, bounded scope, approval source/dependencies and observable acceptance. Avoid speculative backlog and separate issues for small fixes already completed in the PR. Recording a proposal does not approve implementation or excuse an in-scope blocker.

Issues and PR discussions are absent from an ordinary clone. If GitHub is unavailable, report current action status as unverified and retain an explicitly unpublished handoff; do not infer an empty backlog or create a competing tracker. Handoff should identify acceptance, dependency/approval state and the next permitted action using local instructions plus authorized live reads.

Commit coherent, reviewable behavior with its relevant tests and owning docs. Stage explicit paths or intentional hunks; review the staged file list and diff. Existing changes in a touched file require additional care: a whole-file stage can include unrelated work. Use Conventional Commits subjects where appropriate. Do not amend, rebase, reset or force-push unless requested.

Use an account-linked author/committer email and check the published GitHub author mapping. The maintainer's GitHub-provided noreply address is appropriate for future commits. If older commits use another email, adding/verifying that address can restore account association without changing hashes. Do not rewrite history merely to repair attribution when email association resolves it.

Keep artifacts under ignored `.artifacts/`. Inspect personal/editor files before inclusion; do not add them merely because they are untracked. Retain current-task and failed-task command logs. Keep commit, issue and PR text free of connector attribution, generated-by footers and AI co-author trailers.

## Pull requests and merge instructions

### Issue links and resolution

Use `Fixes #N` in the PR description only when merging into the default branch fulfils the issue's full acceptance boundary. Prefer GitHub's Development relationship for resolving work and verify the actual link, or disclose unavailable access. Use non-closing `Related to #N` for partial delivery, historical evidence or separately required operator/settings/target proof; manually linked Development PRs can also auto-close issues. A branch name or plain reference alone is not a formal resolving relationship.

Before authorized merge, confirm acceptance and proof. Afterwards check the actual issue state; closure depends on repository settings/default-branch targeting. Close manually only when authorized and the whole boundary is met. Closed as not planned is not completed; a closed unmerged PR is not delivery. Keep material approval, blocker and resolution decisions in issue discussion without narrating every edit. See [GitHub linking semantics](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue).

### Description and acceptance

Every PR, including drafts and API-created PRs, needs a meaningful description using the [PR template](../.github/PULL_REQUEST_TEMPLATE.md). Fill it before opening; an empty checklist is insufficient. Explain the problem/result, link the issue/action, identify scope/compatibility, owning and related documentation checked, actual commands/results/environment/skips and remaining risks. Update title/body when scope changes.

Review the filled description before publishing: does it describe final behavior, link the right issue, name correct base/head, assess documentation meaning, record executed checks and disclose operational/verification limits? Remove stale prompts, placeholders and inapplicable sections. Completeness and accuracy are acceptance criteria; a subjective perfect score cannot replace evidence.

Keep one current acceptance record in the PR description: reviewed head, tested revision/environment and actual CI run when applicable, checks/results/skips, honest author self-review verdict, independent findings/resolution, documentation impact and unresolved issue links. Self-review is not independent approval or permission to merge. Use comments for meaningful findings or changed decisions, not mandatory duplicate acceptance. Reassess affected changes after edits; old results retain their original scope. Commit substantive docs before final checks; do not repeatedly commit a document solely to embed its own final SHA or newest routine CI count.

Push only the reviewed branch/commits within the user's requested workflow. A request to open a PR includes the necessary branch push; it does not authorize merging or deploying. A draft can expose a concrete checkpoint while work continues, provided completed and pending behavior are clearly distinguished. Keep the description current as verified commits arrive.

Before merge:

1. Confirm the intended base/head and inspect the complete PR diff, not only the latest commit.
2. Check linked acceptance criteria, compatibility and affected failure/cancellation/recovery behavior.
3. Require relevant local or applicable CI evidence on the current revision; disclose missing device/server proof and unresolved risks. A workflow file does not prove execution or branch enforcement. This repository has no tracked GitHub Actions workflow; Server-Audit CI is not Tunnl CI.
4. Confirm owning docs and AGENTS.md agree with source and no secret/evidence artifacts are staged.
5. Resolve review findings and conflicts without overwriting unrelated work.
6. Merge only when requested and the review requirements are satisfied. Keep release/deployment state separate.

For cross-cutting agent/process changes, obtain an independent review of the final instructions, guides and templates. Use the reviewer effort requested by the user, including ultra reasoning when specified. Record findings, fixes and unresolved limits in the verification record; re-review material corrections. The author still owns final consistency and the intentional staged scope.

Before reporting publication complete, read back the published revision, changed-file scope, body, actual metadata, issue/PR state, Development relationship and applicable checks. Verify settings or deployment separately when required; merging prose does not configure them. Missing access must remain disclosed.

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
| `ui/src/test` utility/binding tests | Formatting, empty binding summaries and secret-safe logging; Robolectric supplies resources/log capture. Log parser/filter, fragment and rendered-row regressions remain planned. |
| Focused lifecycle/state tests | Affected transition, selection, initialization and recovery behavior; add regressions when repairing those owners. |
| Existing Detekt configuration | Kotlin static checks; this is not runtime or device proof. |
| Device/emulator checks | VPN permission/service behavior, native backend and actual UI/navigation at affected sizes, themes, font scales and RTL. |
| [Offline provisioning tests](../scripts/tests/provisioning_safety_test.sh) | Real Bash script flow with synthetic keys, fake commands, private files and native locks inside an unprivileged Linux user namespace; no host root/network operations. |
| `vpsIntegrationTest` | Explicitly authorized disposable Ubuntu/Debian host only; see [QA use case](qa-test-plan.md#disposable-vps-integration-use-case). |

Broaden tests for shared state, public/script contracts or cross-cutting/high-risk changes. Use deterministic clocks, synthetic profiles, injected commands and temporary files where they answer the question. Do not introduce a framework, CI or release pipeline merely to record proof.

For documentation-only work, validate relative links, fences, examples/source claims, consistency with AGENTS.md, date presence/actual edit date and `git diff --check`. Keep historical event/test/review dates separate from the latest edit marker. A search with no matches returns `rg` exit 1; exit 2 is a search error. An executed test suite with disclosed skips differs from source-inspected tests, and neither proves unperformed device or live-server behavior.

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

2026-10-06 instruction ownership update (Europe/Paris): at the user's request, AGENTS.md became the sole project instruction file and CLAUDE.md was removed. Active onboarding, acceptance and verification guidance now use AGENTS.md directly. Earlier mirror checks below retain their original checkpoint scope; they are not a requirement for the current layout. Follow-up evidence is in the [agent/README review](reviews/2026-10-06-agent-readme-review.md#single-instruction-file-follow-up).

2026-10-06 planning checkpoint `aa5feb94`: README and improvement plan were committed. Fifty local links resolved, fences/trailing whitespace and `git diff --check` passed, instruction mirrors matched, and the unrelated pre-existing content inventory was unchanged. This was documentation proof; no APK, device or live-server check was performed.

2026-10-06 A1/D1/D2 workflow checkpoint: canonical/mirrored instructions, four owning guides, issue/PR templates and artifact exclusion were implemented for [issue #5](https://github.com/novakin/wireguard-android/issues/5) and [PR #6](https://github.com/novakin/wireguard-android/pull/6), then a draft. Fourteen touched Markdown files across Android and Server-Audit passed 127 local-link checks, balanced-fence and trailing-whitespace checks; instruction mirrors were byte-identical. The templates were reviewed for issue links, actual labels/assignee, scope/compatibility/privacy, executed evidence, remaining risks and delivery state. At that checkpoint GitHub metadata named `novakin` with `bug` and `documentation` labels. C1 runtime verification appears below; these are historical results, not new runs.

2026-10-06 delivery clarification: PR #6 later merged into `master` as `7e141dc39b256014cd5a8ce92a95ea5e4e2241a6` and issue #5 closed. [Issue #7](https://github.com/novakin/wireguard-android/issues/7) tracks the subsequent agent/README review; its [review record](reviews/2026-10-06-agent-readme-review.md) owns that bounded assessment and evidence.

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

### C2–C3 offline provisioning safety — 2026-10-06

Source/test work uses isolated `fix/provisioning-safety` from merged master `7d981e8b9e5ca80003900d60c6665d6bd63829f4`. It preserves the original dirty workspace and uses WSL Debian Bash with native `flock`/`unshare`. Namespace UID 0 maps to host UID 1000; the suite refuses actual host root. Privileged host tools are faked; real filesystem mutations stay inside private `/tmp` fixtures. No new dependency was installed.

Run from the repository root in a Linux environment supporting unprivileged user namespaces:

```bash
unshare --user --map-root-user bash scripts/tests/provisioning_safety_test.sh
```

Windows/WSL example; use the intended checkout path:

```powershell
Invoke-CodexQuiet -Command 'wsl -d Debian -- unshare --user --map-root-user bash /mnt/c/Users/Guillaume/Documents/Projets/wireguard-android/scripts/tests/provisioning_safety_test.sh' -WorkingDirectory $PWD.Path
```

The repaired suite passed 59 cases with zero failures/skips. In the final comparison, the same suite against the merged baseline passed seven cases and failed 52; unlocked baseline concurrency outcomes can vary with scheduling. Baseline scripts were exported privately, made executable to match upload mode 0700, and had only the legacy hardcoded forwarding-file path mapped to the fixture path to prevent host filesystem effects. `TUNNL_TEST_SCRIPTS` selects that test-only baseline bundle. `bash -n` passed all ten Tunnl script files plus the test runner/fixture helper. Cases cover ownership and input controls, concurrent allocation/add/remove and shared host-state races, failed copy/rename/strip/apply/restart/recovery, injected runtime key/port/peer/route/PSK/address mismatches, stopped peers, malformed metadata preserving other peers, absent tools/units versus unknown observations, install/rollback hook ordering, secure unique backups/archives, partial/residual purge, cleanup after commit and TERM recovery. A fixture control confirms unflagged legacy file commands delegate correctly to private files.

Retained command logs under ignored `.artifacts/codex-command-logs` include the repaired 59-case run `20261006-140949-601-6373b7ff152b44168e4898fa664e1056.log` and baseline comparison `20261006-140949-684-866df557cbcb413094dc321f75ac403a.log`. Earlier fixture/source failures and preliminary baseline comparisons remain retained and superseded; they are not final proof. The PR acceptance record owns the final reviewed/tested revision and independent findings/resolution.

No APK/Gradle, device, real WireGuard/systemd/apt, SSH or live/destructive VPS operation ran. Fake native observations prove Bash ordering/decisions rather than actual NAT, VPN traffic or real package rollback. SIGKILL/host loss and uncoordinated external tools remain outside transactional guarantees. C4 response/health/consumer alignment, C9 harness repairs and C11–C12 integration remain open.

2026-10-06 delivery clarification (Europe/Paris): [PR #19](https://github.com/novakin/wireguard-android/pull/19) merged C2–C3 into master as `37b3ad34f4df09a28b1ed6e7b5109faf3ff45789`; issues #10/#11 were verified closed. The historical run/limits above remain unchanged. C4 below supersedes its response/health gap.

### C4 provisioning contracts and observed health — 2026-10-06

Source/test work uses isolated `fix/provisioning-contracts` from merged master `37b3ad34f4df09a28b1ed6e7b5109faf3ff45789`; the original dirty workspace/index stays untouched. Environment: WSL Debian, OpenJDK 21.0.10, Gradle 9.3.1, installed Android SDK and Robolectric SDK 28 for focused command/logging/mapper checks. No dependency was added or installed.

| Run | Observed result and limits |
| --- | --- |
| New JSON/mapper regressions against unrepaired source | 19 tests ran; five failed as expected: nested shape, unsafe known/unknown remote errors and fresh/future timestamp health. |
| Five new Bash regressions against unrepaired source | Existing 59 passed; new force-plan, remove/revoke schema and unknown/stopped observation cases failed. |
| Repaired offline script suite after review | 69 passed, zero failed; native namespace locks/files and fake privileged tools. Twelve exact normalized responses are committed as synthetic fixtures and consumed by JVM tests. |
| Full UI JVM suite after review | 73 passed, zero failures/errors/skips; ten suites. No APK assembly or device proof. |
| Native `:ui:detekt` | Blocked before analysis by unchanged unsupported `AvoidReferenceToMutableVar` config property; [issue #20](https://github.com/novakin/wireguard-android/issues/20). |
| Task-local Detekt comparison of ten touched Kotlin files | Config copy omits only that unsupported rule. Exported baseline has 11 findings; repaired source has ten, all existing mapper/store ReturnCount/MagicNumber findings. The generic Throwable catch is removed; no new findings. This is not a clean repository-wide gate. |

Run the relevant suites from the intended checkout:

```bash
./gradlew :ui:testDebugUnitTest --tests 'com.wireguard.android.provisioning.*' --console=plain
./gradlew :ui:testDebugUnitTest --console=plain
unshare --user --map-root-user bash scripts/tests/provisioning_safety_test.sh
```

Command-contract tests need Bash in the JVM test environment. They run the exact constructed command through Bash with fake id/sudo/script stubs, covering root/sudo dispatch, shared force arguments, quotes, spaces, substitution syntax, newlines, remote paths, upload contents/mode and structured nonzero script failure/cleanup. Script cases compare actual outputs after normalizing private fixture paths and random archive suffixes; only synthetic keys/configs are committed. The generated profile also passes upstream Config parsing. To regenerate fixtures intentionally after a reviewed contract change, run the offline suite with `TUNNL_UPDATE_CONTRACT_FIXTURES=1`, review the JSON diff, then rerun without that variable and run JVM tests. Never capture real host responses into these resources.

JSON tests validate nested/missing/wrong-type success fields, actual Booleans, bounded decimal counters, null observations, known/unknown/malformed errors, recovery enums, conditional revoke archives and cleanup metadata. Captured logs/exception stacks exclude synthetic secrets. Store tests cover legacy/malformed running fields, true/false/null round trips and existing transactions. Mapper tests separate freshness from observed running, reject future/stale evidence, check thresholds and retain unknown peer values. See [contracts](backend-contracts.md) for deliberate schema/health semantics and compatibility.

Independent ultra review identified unintended Unicode damage, a wrong C9 issue reference, failed dump stdout being treated as observed counters and optional archive acceptance for revoked success. Text/reference corrections restored the intended content. Failed dumps now discard stdout even when it looks valid; a synthetic partial-output/secret-stderr case fails against the unadapted ten-script export of merged master and passes after repair. Revoked responses now require a nonblank string archive, with safe missing/blank/wrong-type failures and remove/revoke controls. Final revision-specific verdict belongs in PR acceptance; this is not formal GitHub approval or live proof.

Evidence remains under ignored `.artifacts/codex-command-logs`. Initial baseline JVM/Bash logs: `20261006-143649-972-5e96fb9d303340059eda9f5f253e52d4.log` and `20261006-143630-630-3c4fb357bbcd4b25b2acece15c4b1c16.log`. Earlier repaired 68-case/72-test runs remain historical: `20261006-144559-094-60ffc6b4716246fd8a249255d37306bb.log` and `20261006-145025-117-0ae19fc5464849c5b5004d7bebb7a3c6.log`. Final 69-case script proof and 73-test UI proof are `20261006-150149-399-52b32714747f4c23b77ee6e3c13b829f.log` and `20261006-150350-514-1c65ea299cd54fa6ae6b5462dbefd3a6.log`. The partial-dump baseline regression's expected exit 1 is retained in `20261006-150300-931-f6be2dff775242c685e7e763af514177.log`; its test assertions emit no payload. The initial static-config failure, exported-baseline comparison and final scoped comparison are `20261006-145025-214-cf9dd15277864157a0dd51247cc71fd3.log`, `20261006-145457-284-594f70233601455394e1755826bd6534.log` and `20261006-150401-019-150ac02628af429980cf2d472020d1de.log`. Earlier fixture/test setup failures remain retained and superseded. Final reviewed/tested revision belongs in PR acceptance.

No APK, device, real root/WireGuard/systemd/apt, SSH or live/destructive VPS operation ran. Native observation fixtures establish decisions, not VPN packets or real OS reliability. Legacy records lose no connection metadata but deliberately start with unknown running health. C9 harness assertions/redaction, C11 concrete transport, C12 live UI/recovery presentation and issue #20's native static gate remain open. Existing AGENTS.md/README/templates already enforce this workflow; no instruction/template/process change is needed for this functional slice.
