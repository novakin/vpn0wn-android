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

Stacked PRs targeting a repair branch do not receive automatic Development/closing links from description keywords. Keep explicit issue/PR references and state the pending relationship; a description reference alone is not Development metadata. Manual linking in GitHub's Development panel is possible when an authenticated browser is available; disclose when it is unavailable. Use merge commits for an ordered stack to preserve predecessor ancestry and avoid rewriting successor branches. After the predecessor merges, retarget to `master`, recheck the scoped diff, confirm the actual Development relationship and only then perform separately authorized merge. Verify issue closure afterwards. For PRs #23–#26, ordered retargeting created the verified closing links; manual UI edits were not assumed.

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

The Detekt configuration is compatible with pinned 1.23.8 after the merged #20 repair. Source remediation under [issue #22](https://github.com/novakin/wireguard-android/issues/22) now passes native analysis with zero findings; the [dated source evidence](#detekt-source-remediation--2026-10-06) separates that local result from publication and future CI enforcement. The inherited 538/546 findings remain historical evidence below.

Keep `maxIssues: 0`, analysis enabled and failures visible. Name meaningful durations, limits and state/protocol values; keep exact drawing geometry in its existing renderer. Declaration-scoped suppressions need an owner-specific WHY, such as drawing dimensions, a required Android vararg API or Backend/ConfigStore's generic Exception contract. Cancellation and unrelated Errors must propagate; admitted tunnel mutations still finish their existing noncancellable reconciliation before rethrowing. ReturnCount excludes guard clauses so clear early validation needs no artificial nesting. Generic catches have no exception-name bypass. Do not add a blanket baseline, disable rules wholesale, set `ignoreFailures` or compress correct control flow to satisfy a count.

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
| `ui/src/test` utility/binding tests | Formatting, empty binding summaries and secret-safe logging; Robolectric supplies resources/log capture. Log parser/filter and rendered-row regressions remain planned; focused detail-fragment selection/destruction coverage is recorded below. |
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

### Detekt configuration repair — 2026-10-06

Isolated `fix/detekt-gate` starts from merged master `de521d8ef45431e65dd05162ea951cd30c42269f`. Native `./gradlew :ui:detekt --console=plain` first reproduced exit 1 before analysis with the unsupported `potential-bugs>AvoidReferenceToMutableVar` property. Removing only that property preserves the other rules and zero-issue threshold. The second run completed source analysis and exited 1 with 538 weighted findings across 16 rule types. Generated reports remain under ignored `ui/build/reports/detekt/`; [issue #22](https://github.com/novakin/wireguard-android/issues/22) records the breakdown, owners and acceptance for source remediation. No baseline, rule-wide suppression, dependency change or `ignoreFailures` was introduced.

Environment: WSL Debian, OpenJDK 21.0.10, Gradle 9.3.1, pinned Detekt 1.23.8. Retained baseline and repaired logs are `20261006-152341-741-4ea75a4e57ce4cb8af9955942d65de6c.log` and `20261006-152356-254-de4c2d2340c84a16b54ae8cce18fc53d.log` under ignored `.artifacts/codex-command-logs`. Configuration acceptance is satisfied; repository-wide source acceptance remains open under #22. No APK, JVM suite, device, SSH or live operation was needed or run for this two-line configuration repair. C4 merged in PR #21 as `de521d8ef45431e65dd05162ea951cd30c42269f`; issue #12 was verified closed. Earlier C4 runs retain their original limits.

### Atomic local profile replacement — 2026-10-06

Isolated `fix/atomic-config-store` stacks on the reviewed #20 branch; the original worktree/index is untouched. WSL Debian, OpenJDK 21.0.10, Gradle 9.3.1, Android SDK and Robolectric API 28. Run `./gradlew :ui:testDebugUnitTest --tests com.wireguard.android.configStore.FileConfigStoreTest --console=plain`. Focused tests cover successful create/save/rename/delete, conflicting names, partial-write and rename failure preserving prior bytes, failed creates leaving no usable profile and concurrent create/save/delete ordering. Test failure injection uses the existing Context and a native-rename shadow; it performs no real Android/native VPN operation. The first test run exposed an object-equality fixture assertion, corrected to compare serialized profile content; retained logs preserve that failed run and subsequent proof. Final results and reviewed revision belong in the PR acceptance record.

Full UI JVM suite passed 83 tests with zero failures/errors/skips. Ultra review found a stale architecture statement, a missing QA inventory update and insufficient contender timing in race tests. Owning guidance now agrees with the implementation; race tests keep only the first writer blocked and require the started contender to remain unsettled before release. Final focused run passed all ten store tests; native Detekt completed analysis with the same 538 findings as #20, adding no rule-count findings. The intermediate shadow-class constructor finding was fixed rather than suppressed. Retained full-suite log is `20261006-153413-014-64e75e37122f4666ac7b381da9ab206a.log`; final focused/static log is `20261006-153755-090-1dfb19fcbe334fbba8068d534c50f763.log` under ignored `.artifacts/codex-command-logs`. The combined command exits 1 because the separately tracked static source gate fails, not because the focused tests failed.

Source inspection established the truncating-write defect; no real disk-full or process-kill reproduction was performed. No APK, device, SSH, server or user-file migration ran. Native Detekt source findings remain separately tracked under #22. README/AGENTS.md/templates keep their existing format, workflow and verification boundaries; this owner-specific contract changes architecture/contracts/changelog/plan/QA guidance only. No new CI or agent instruction was added.

### Tunnel lifecycle and transition settlement — 2026-10-06

Isolated `fix/tunnel-lifecycle` stacks on #14's reviewed branch; the original worktree/index remains untouched. Environment: WSL Debian, OpenJDK 21.0.10, Gradle 9.3.1, Android SDK and Robolectric API 28. Native command: `./gradlew :ui:testDebugUnitTest :ui:detekt --continue --console=plain`. Focused fixtures inject existing Backend/ConfigStore responses and finite latches; no root, SSH, native VPN activation or process exit runs. The suite exercises inventory cancellation/retry, admitted cancellation settlement, queued UP/DOWN/delete, failed save/delete recovery, stale state snapshots/native notifications, restoration intent, backend-switch reservation/rollback, reversed/cleared detail selection and destruction and failed preference shutdown. Userspace shadows prove prompt destruction, dead-service rejection/future replacement, cleanup identity/callback ordering and endpoint-free/explicit-port/protection/interruption controls.

Independent ultra review found queued DOWN short-circuiting, stale cleanup callbacks, stale manager/native snapshots, backend-switch admission gaps, no-op restoration-state persistence and swallowed backend-init cancellation. The owner fixes and focused regressions address those findings. Spontaneous service loss updates observed state while retaining persisted restoration intent; explicit requests own that intent. Final full UI JVM suite passed 112 tests with zero failures/errors/skips. Native Detekt completed analysis and exited 1 with 546 findings: inherited 538 plus eight TooGenericExceptionCaught findings in owner reconciliation around the Backend generic Exception contract. No baseline, rule suppression or ignoreFailures was introduced; #22 retains source-gate remediation. Final retained log: `20261006-163003-760-a3162567e77348229ad5a90d5a55c4da.log` under ignored `.artifacts/codex-command-logs`. An earlier interleaved compile included the prior callback persistence implementation and failed the restoration-intent regression; that log is retained, and the final settled-source run passes it.

Delayed statistics guards are source reviewed without a dedicated regression. This establishes scoped JVM/state-ordering proof, not successful native activation/reacquisition, VPN traffic, process restart, real service scheduling or root/kernel behavior. DNS retry interruption propagates, while UDP retry specifically preserves the thread flag. Kernel transition serialization remains #15; Detekt source remediation remains #22. Contracts, architecture decision, operations, changelog, QA inventory, roadmap and plan are updated together. README/AGENTS.md/templates retain their existing workflow and build boundaries; this change adds no new process, CI or APK assembly.

### DNS round trips and prefix validation — 2026-10-06

Isolated `fix/config-compatibility` stacks on #13 commit `5b0d0e5dba668379977fa848e445de1d541cb96a`; the original workspace remains untouched. Six focused JVM config tests reproduce domain-only DNS loss and explicit negative prefix acceptance in the existing serializers/parsers. After correcting fixture comparisons to use profile content rather than Interface object identity, baseline controls pass and three regression methods fail. Two production condition changes repair those failures; omitted host masks, valid limits/default routes, mixed IPv4/IPv6/search domains, endpoint-free and two-peer profiles remain controls. No new dependency, user-file migration or alternate parser is introduced.

Executed command: `./gradlew :tunnel:testDebugUnitTest :ui:testDebugUnitTest --console=plain` under WSL Debian, OpenJDK 21.0.10, Gradle 9.3.1 and the existing Android SDK/Robolectric environment. Final XML confirms 16 tunnel and 112 UI tests passed, zero failures/errors/skips (128 total). Retained successful log: `20261006-164106-104-c6a1f9d5cfea49a1a53cfd58be30517a.log` under ignored `.artifacts/codex-command-logs`; retained baseline log `20261006-163927-828-b541e27e36e24c29a85665ddacbb23cc.log` confirms six focused methods with three regression failures before repair. No APK, device, root, network DNS lookup, VPN or server operation runs. Native Detekt's inherited 546 findings remain #22; this Java parser/test change does not alter Detekt's Kotlin source set or configuration.

Contracts, changelog, QA inventory and issue status are updated. Development also records the reviewed stacked-PR issue-link limitation and pre-merge verification; manual links for #24/#25 are left to the maintainer. README/AGENTS.md/architecture/templates retain their owner, workflow and date contracts; no new top-level instruction or template field is needed.

### Kernel transition serialization — 2026-10-06

Isolated `fix/kernel-transitions` follows #16 commit `026782182396bd81acdc87140478179c198dcea5`. Simulated RootShell retains per-command serialization; a ToolsInstaller availability latch pauses the first transition after its empty state/config snapshot. A started contender must remain unsettled until release. Baseline six methods have three expected failures: both concurrent-mode admission checks and hidden rollback failure. Repairs extend existing synchronized Java methods and attach recovery failures, with no new lock layer/dependency.

`./gradlew :ui:testDebugUnitTest :tunnel:testDebugUnitTest --console=plain` passed 118 UI and 16 tunnel tests, zero failures/errors/skips (134 total), under WSL Debian, OpenJDK 21.0.10, Gradle 9.3.1, Android SDK and Robolectric API 28. New six-method suite covers disabled/enabled simultaneous UP, restoration after activation/config failure, failed shutdown retaining ownership and rollback failure with observed DOWN. Retained final log is `20261006-164914-035-c5ea2e1a97374eccbab0bf98d8fab804.log`; baseline is `20261006-164648-716-e72c8438e4f748eeaee4c249830ab9cd.log`, under ignored `.artifacts/codex-command-logs`.

Ultra review identified Main-thread contention in the live mode collector. The seventh regression executes actual Application initialization/preferences with simulated support/root startup and a held backend monitor. Before IO collection, that regression failed the lock-holder deadline while six transition controls passed (`20261006-170305-494-88cdaf0c0f804e26b7bf9d0a4e82360b.log`). After the collector fix, the full combined gate passed 119 UI and 16 tunnel tests, zero failures/errors/skips (`20261006-170415-984-cd912f16fec24fbbab68c6792ef8d67b.log`).

Native Detekt includes Kotlin tests. It exposed an empty fixture callback and later two support-shadow findings; the callback now records asserted state, support is setup-controlled and the shadow binds/asserts its real backend identity. Nonpublic constructor attempts failed Robolectric setup and remain in retained failed logs. Final fixture-only delta passed all seven focused tests with zero failures/errors/skips; fresh Detekt reports the inherited 546 findings, none added by #15 (`20261006-171140-508-2192ca6a7ae14b07a07f28c25dd04366.log`). The combined command exits 1 solely for the tracked #22 source gate. No suppression, baseline or ignoreFailures was introduced. Earlier gates retain their original scope; the full suite preceded the final shadow-only refinement.

No actual root shell, native tool, kernel interface, APK, device, SSH, VPS or live traffic was used. Command simulation does not prove native partial-effect rollback, unreachable-shell observation or coordination with external tools/another backend instance. Contracts, decision, operations, changelog, QA inventory, plan and roadmap reconcile these guarantees and merged predecessor repairs. README/AGENTS.md/templates retain existing contracts; merge-commit stack ancestry is documented in the owning contribution guide.

The maintainer authorized ordered merges on 2026-10-06. PR #23 merged as `c586f5ef77aa093e02975da412a9c711334416d7` and closed #20; #24 as `e3dea3ef871850936da3099192d715f7d9dfe868` closed #14; #25 as `023fe3848121a1deea58b89e6db856a9eff3d395` closed #13; #26 as `7e2492dec2abbae18ec237cc5a02939ef249ce02` closed #16; #27 as `bf532ce994b50b85bc89e0ad431d3a85677eee94` closed #15. Each successor was retargeted to master, its unchanged reviewed head/scoped diff checked, actual closing relationship verified and final merge/closure read back. All five priority repairs are merged. No branch deletion, rebase, reset, amend or force-push was used.

### Detekt source remediation — 2026-10-06

Isolated `fix/detekt-source` starts from merged master `bf532ce994b50b85bc89e0ad431d3a85677eee94`; the original dirty worktree/index remains untouched. Native `./gradlew :ui:detekt --console=plain` reproduced 546 weighted findings. Remediation fixes owner defects and uses narrow explained exceptions for genuine drawing/API/contract boundaries; zero findings does not mean 546 runtime bugs were repaired. `maxIssues: 0` remains, no baseline/disabled analysis/ignoreFailures is added, guard clauses are excluded from ReturnCount and generic catches no longer bypass checks by variable name. Existing admitted manager reconciliation remains intact.

Three comparator regressions reproduce one baseline failure; four initial QR controls reproduce four baseline failures; three importer controls reproduce cancellation consumption, raw exception-payload logging and repeated callback delivery. Coroutine stacktrace recovery can clone exceptions, so tests check type/message, cause/suppressed chains and attempt/callback counts rather than requiring identity across dispatcher boundaries. A mixed ZIP control adds ignored/nested/invalid/duplicate/valid entries and proves both successful configs, unchanged duplicate ownership and one final partial-result callback. QR allocation exhaustion has a recoverable IOException control; unrelated Errors still escape. ZIP fault tests use a read-only provider descriptor plus thrown delete failure, and hold an entered config load through cancellation before release/join. Fixtures retain their Application through teardown; no real user file/provider is changed.

Environment: WSL Debian, OpenJDK 21.0.10, Gradle 9.3.1, pinned Detekt 1.23.8, existing Android SDK, Robolectric APIs 28/29. Focused comparator/QR/import command passed 12 tests with zero failures/errors/skips and zero native Detekt findings (`20261006-182218-215-79e663f48c74475fbaaff299e7369ea8.log`). An initial full `./gradlew :ui:testDebugUnitTest :tunnel:testDebugUnitTest :ui:detekt --console=plain` passed before the final ZIP tests and catch-scope delta (`20261006-182330-392-3577fb32e2bf43bab8812c06edfc6163.log`). Earlier baseline, fixture and compile failures remain retained under ignored `.artifacts/codex-command-logs`; the unsigned-constant kapt failure was fixed with a signed 100 MiB constant, preserving the limit rather than changing dependencies. The settled full command then passed 133 UI and 16 tunnel tests (149 total), zero failures/errors/skips, and zero Detekt findings (`20261006-183734-426-496fd2353d9d4584a439d9265033b8b5.log`). The full command executed the UI suite; unchanged tunnel tests and the settled static gate reused prior passing outputs as UP-TO-DATE. Final focused detail/export proof passed five tests before that full gate (`20261006-183605-912-562b7bca0d3745858b410a0ea9fac696.log`). Reviewed head and publication readback belong in the PR acceptance record.

Independent ultra review corrected an automated license-header replacement, remaining wizard state literals, TV launch denial feedback, exhausted QR allocation behavior, missing valid ZIP proof and broad catch scopes around success callbacks/rendering. Expected Backend/ConfigStore failures retain feedback/recovery; successful editor/naming callbacks and detail/TV rendering run outside those catches. Existing detail selection/destruction controls bracket that change; callback/rendering boundaries outside the importer are source reviewed without dedicated UI defect injection. ZIP regressions address returned-output cleanup and loading cancellation only. [Issue #28](https://github.com/novakin/wireguard-android/issues/28) tracks saver ownership transfer/deletion gaps; blocking writes and device/provider cleanup remain separate limits.

AGENTS.md, development policy, contracts, architecture ownership, operations, changelog, plan, roadmap and QA inventory are reviewed together. README and issue/PR templates retain valid ownership/workflow links and need no edit; historical reviews/test dates retain their original scope. Final independent verdict belongs in PR acceptance. No APK assembly, CI workflow, device/screenshot, actual root/kernel/VPN, SSH, VPS or deployment proof is supplied. All five priority issues are already closed; #22 publication/closure and subsequent APK CI are separate delivery steps.
