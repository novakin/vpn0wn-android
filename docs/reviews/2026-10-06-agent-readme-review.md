# Agent and README review — 2026-10-06

Last updated: 2026-10-10 (Europe/Paris).

[Documentation index](../../README.md#project-documentation) · Substantive documentation review, Europe/Paris.

The initial review sections below describe the two-file instruction layout at checkpoint `584d0999d787c5ab6ad3a7db75e321a0f34c8a15`. The [single-file follow-up](#single-instruction-file-follow-up) records the user's subsequent consolidation request; historical mirror results and scores retain their original scope.

## Scope and acceptance

[Issue #7](https://github.com/novakin/vpn0wn-android/issues/7) owns this review's acceptance. Integration base: `master` at `7e141dc39b256014cd5a8ce92a95ea5e4e2241a6`, the merged [PR #6](https://github.com/novakin/vpn0wn-android/pull/6). Review branch: `docs/agent-readme-review`. Seven intentional paths: `CLAUDE.md`, its verbatim `AGENTS.md` mirror, `README.md`, `docs/development.md`, `docs/improvement-plan.md`, `.github/PULL_REQUEST_TEMPLATE.md` and this record.

The review assesses complete agent instructions and README, with their owning contribution guidance, PR template and plan pointers. Related guides, build files, source, JSX and current Server-Audit process were read to check claims. Runtime code, broader guide remediation, CI, dependencies, release and live operations are outside this change. The existing mixed worktree contains unrelated work; it is preserved, not included in the PR.

The 100-point rubric below is a bounded documentation assessment, not a codebase score, security certification or guarantee against future drift. Full acceptance requires all criteria backed by source/checks and no unresolved material independent-review finding. The [original static ratings](../improvement-plan.md#scope-and-baseline) remain historical; unfinished runtime actions C2–C12 retain their separate acceptance boundaries.

## Findings and resolution

| Gap | Resolution and owner |
| --- | --- |
| README cloned upstream rather than Tunnl; entry point omitted agent/process guidance. | README names this fork, clones it with submodules and links canonical instructions, owning guides and existing templates. Official distribution remains attributed to upstream. |
| Groovy dependency example kept the version literal; Java/desugaring claims drifted. | Valid Kotlin DSL interpolation; Java 17 and desugaring 2.1.5 grounded in checkout. Published upstream artifact version is explicitly selected by the consumer, not inferred from the fork's version property. |
| Build/environment and current capability claims could imply unsupported runtime proof. | Setup distinguishes Gradle JDK/source compatibility, SDK/native prerequisites, POSIX/WSL execution, debug suffix and release state. README discloses incomplete SSH/credentials/live actions and placeholder VPS QR. |
| Agent backend headings, JSX symbols and presumed two-agent reading were stale. | Canonical instructions use actual changelog headings and design symbols, route reads by task, retain tokens/architecture and mirror verbatim. |
| Ignored design prerequisite was missing from onboarding. | UI fidelity requires the prototype or user-approved visual reference; missing reference does not block independent work. |
| Current base, issue acceptance/approval and live handoff were underspecified. | Agent/development rules refresh the authorized base, read live task discussion, preserve dirty/index state and disclose inaccessible GitHub status. |
| Metadata and issue linkage could imply automatic ownership or premature closure. | Actual fields own labels/assignees; preserve deliberate assignments/unrelated labels, use additive writes/readback and distinguish complete closing relationships from partial references. |
| PR evidence lacked current revision, honest self-review and related-documentation meaning. | Existing PR template and development guide require one current acceptance record, reviewed/tested revision, applicable CI, independent findings, owning/related source review and limits. |
| Dates/decisions and routine versus historical review ownership were unclear. | Owning guides record consequential decisions and supported dates; preserve historical evidence and superseded rationale. Routine acceptance stays in PR; substantive assessments belong in reviews. |
| Plan still described merged PR #6 as current draft and initial reuse search as exhaustive. | Plan links delivered #6 and current issue #7. Dated clarification preserves the stale-search history and records current Server-Audit assets. |

Readable-code and overengineering rules remain explicit: clear names/control flow, extend the responsible owner, reuse fitting native APIs/helpers, and justify layers/dependencies through a concrete requirement. No runtime or architecture rewrite was added to improve a documentation score.

## Source grounding

| Claim | Reviewed source and limit |
| --- | --- |
| Gradle 9.3.1; AGP 9.1.0; desugaring 2.1.5. | [Wrapper](../../gradle/wrapper/gradle-wrapper.properties), [catalog](../../gradle/libs.versions.toml). |
| SDK 36, minimum API 24, Java 17; debug ID `com.wireguard.android.debug`. | [UI build](../../ui/build.gradle.kts), [tunnel build](../../tunnel/build.gradle.kts), [properties](../../gradle.properties). Source declarations are separate from device proof. |
| Native POSIX tools and pinned Go 1.24.3; no tracked Windows wrapper or pinned NDK/CMake package version. | [CMake](../../tunnel/tools/CMakeLists.txt), [Makefile](../../tunnel/tools/libwg-go/Makefile), module build and Git file inventory. No new build was run. |
| Actual design symbols, tokens and changelog headings. | Local read-only `.design/` inspected against the corrected agent map; [colors](../../ui/src/main/res/values/colors.xml), [styles](../../ui/src/main/res/values/styles.xml), [UI roadmap](../ui-redesign-roadmap.md), [backend changelog](../backend-changelog.md). Design is not shipped with a clean clone. |
| Incomplete transport, protected credentials, live fleet actions and usable VPS QR. | [Operations](../operations.md), [architecture](../architecture.md), [contracts](../backend-contracts.md) and their referenced provisioning owners. Labels and demo payloads do not prove integration. |
| Upstream library/translation/license provenance. | Tunnel publication configuration, upstream translation script, [COPYING](../../COPYING), [fonts](../font-attribution.md) and [vendored reference](../../third_party/wireguard-install/UPSTREAM.md). External services/artifact availability were not exercised. |

## Server-Audit reuse

Reference only: `novakin/Server-Audit` main at `15d34dac6ba1faf3294e521365f4c24277911df3`. GitHub content blob IDs matched the locally read reference files:

| Reference | Git blob |
| --- | --- |
| [Agent instructions](https://github.com/novakin/Server-Audit/blob/15d34dac6ba1faf3294e521365f4c24277911df3/AGENTS.md) | `e9332b5f2c57f48bb3f37a572a52b920677fc08c` |
| [Development process](https://github.com/novakin/Server-Audit/blob/15d34dac6ba1faf3294e521365f4c24277911df3/docs/development.md) | `a42f19c29771c90e4abe75c65d93309daa27690e` |
| [PR template](https://github.com/novakin/Server-Audit/blob/15d34dac6ba1faf3294e521365f4c24277911df3/.github/pull_request_template.md) | `4c921cb6f1beb03b842eee5e3a346b0af7b46a7e` |

Reused: responsible-layer ownership, compatibility/failure/cancellation/privacy invariants, proportional evidence, documentation meaning/ownership, decision/history rules, live issue handoff, additive metadata, complete versus partial closure, filled draft/API PR descriptions, honest revision-specific acceptance and publication readback. Tunnl's existing bug/improvement templates already collect its environment/reproduction/reuse/acceptance; they remain the issue entry points. The reviewed Server-Audit PR structure extends the existing Tunnl template rather than adding another.

Adapted: Android/VPN/provisioning owners and test layers, actual existing GitHub labels, canonical/mirrored instructions, explicit APK/disposable-VPS boundaries and Europe/Paris for new human-written records. Excluded: Python/scanner/report schemas, read-only host-audit policy, blanket external-tool gate, CI runners/live lab, branch-enforcement claims, bots and project boards. This work does not modify Server-Audit.

## Documentation quality rubric

Each row contains four criteria worth five points each. Each criterion requires both correct guidance and the stated evidence; partial/unverified criteria do not receive full credit.

| Area | Four criteria | Evidence | Score |
| --- | --- | --- | --- |
| Source accuracy | Fork/upstream identity; current versus incomplete behavior; build/library facts; paths/headings/design symbols. | Source table, complete-file audit and source/example checks. | 20 / 20 |
| Onboarding and examples | Correct clone/submodules; setup/platform boundaries; valid dependency/build examples; contribution/design-reference entry. | README/setup review, source files and link checks. | 20 / 20 |
| Agent authority and routing | Canonical mirror; task-specific reads; owner/compatibility/simple-code rules; preserved authorization/privacy/worktree boundaries. | Mirror equality, instruction audit and preservation comparison. | 20 / 20 |
| Workflow and traceability | Current base/live acceptance; actual additive metadata; full/partial issue resolution; revision-specific PR evidence/publication/merge separation. | Development/template audit, live issue read and filled publication draft. | 20 / 20 |
| Ownership and maintainability | Owning/related semantic review; consequential decisions/history; no duplicate template/tracker/CI; current plan/delivery pointers. | Owning-guide review, reference provenance, link/fence/whitespace checks and final ultra review. | 20 / 20 |

Final assessment: **100/100** for the defined documentation boundary. Independent ultra reasoning review agreed that all 20 criteria have adequate evidence and found no material in-scope gaps. Publication and merge remain separate delivery states; this score does not validate unfinished runtime work or future documentation changes.

## Verification and review

Cold independent ultra reasoning audit found the source/process gaps above. Final-scope re-review covered all seven complete documents and the filled PR draft, including the scoped plan header and archive evidence. It found no material in-scope findings and independently confirmed local links/fragments, fences/whitespace, mirror equality, source assertions, preservation, clean unchanged submodules, current reference blobs and live issue #7 acceptance/metadata. The reviewer explicitly distinguished documentation quality from runtime proof and delivery. No formal GitHub approval or merge permission is implied by this local independent review.

Documentation check on Python 3.12.10/Windows passed for all seven documents: 129 local links/fragments, balanced fences, trailing whitespace, byte-identical instruction mirror and scoped `git diff --check`. Forty-eight assertions across 15 source files supported build, heading and design claims; Git inventory confirmed the wrapper/CI boundary. The temporary checker remains an ignored task artifact, not a new project dependency or maintained framework. Manual review checked example interpolation, publication/translation provenance, current capabilities, related guidance and filled PR text.

Preservation comparison covered 332 baseline paths outside the intentional documents: 329 file hashes matched individually, the pre-existing deletion remained absent and two submodule paths retained the base gitlinks with clean submodule worktrees. Baseline inventory and command evidence stay under ignored `.artifacts/codex-command-logs`; no raw logs or user files are published. The successful check log is `20261006-113428-783-d336073a7df04c1289d4d9301833fe64.log`. An initial checker run failed on reading a submodule directory as a file; the checker was corrected, rerun successfully and the failure log retained.

No new Gradle test, APK, device/emulator, native-root, SSH, host audit or live/destructive VPS run was performed. Earlier C1 test results remain in [historical development evidence](../development.md#c1-secret-safe-logging--2026-10-06) with their original mixed-worktree environment. No tracked GitHub Actions workflow exists here; Server-Audit CI results are not this project's verification. External documentation URLs were not all fetched, and README's published-version placeholder needs consumer selection before use.

Publication/merge state and the final reviewed/tested revision belong in the PR's current acceptance record. This record is a substantive documentation assessment, not a duplicate mutable delivery ledger.

## Single instruction file follow-up

2026-10-06 (Europe/Paris): the user requested removing CLAUDE.md and keeping only [AGENTS.md](../../AGENTS.md). AGENTS.md now owns project instructions and is edited directly. README, development guidance and the improvement plan use that owner; the old copy/mirror procedure and current mirror-check requirement were removed. All remaining CLAUDE.md/mirror mentions describe historical checkpoints, not active instructions or links to a removed file.

This follow-up changes six paths, including the removed file; PR #8 still spans seven documentation paths from its integration base because the PR template was already changed in the earlier slice. All substantive instruction rules remain intact. No runtime, dependency, CI, template framework or Server-Audit change was added.

The current rubric replaces the mirror criterion with sole AGENTS.md ownership and consistent active references/checks. Other criteria remain unchanged. Follow-up checks passed on Python 3.12.10/Windows: six live documents, 129 local links/fragments, fences/whitespace, absent duplicate file, sole ownership, 48 source assertions and scoped diff checks. Fresh preservation comparison passed for 333 unrelated paths, including 330 file hashes, one retained deletion and two unchanged gitlinks with clean submodule worktrees. Issue #7 acceptance was updated and read back for the requested ownership change.

Independent ultra reasoning re-review found no material consolidation findings and confirmed the current documentation assessment remains **100/100** under the sole-ownership criterion. It independently checked retained substantive rules, references/history, source/link/fence/whitespace checks, preservation, submodules, unchanged PR template and live issue acceptance/metadata. Final committed revision and publication readback belong in the PR acceptance record. No runtime/APK/live operation was run or merge approval granted.

## Documentation dating follow-up

2026-10-06 (Europe/Paris): the user required dated documentation, dates refreshed when files change, and affected documentation updated after any change when needed. AGENTS.md, development ownership/date rules, plan acceptance and the PR template now enforce that workflow. All 17 maintained Markdown guides/templates received a latest-edit marker; templates use a hidden source comment. Same-day edits keep the date; read-only checks do not refresh it. Event/test/review history remains separate. No date bot, dependency or CI workflow was introduced.

This pass changes metadata only in architecture, operations, backend contracts, fonts, UI roadmap, backend changelog, QA and the fork-owned provisioning/reference guides. It does not revalidate their complete runtime contents. The three pre-existing dirty guides retain their original bytes outside the new date line, and only those inserted metadata hunks belong in the commit. The initial latest-edit markers record this actual edit date rather than inventing earlier dates.

Dating/link/fence/whitespace checks passed for all 17 maintained Markdown files; 164 local links/fragments and 48 source assertions passed. Fresh preservation comparison covers 321 unrelated paths, including 318 matching file hashes, one retained deletion and two clean unchanged submodules. All 12 metadata-only files preserve original bytes outside the inserted marker/comment, including the three dirty guides. Independent ultra review found no material in-scope findings and confirmed the bounded documentation rubric remains **100/100**. It verified date placement, unchanged issue front matter, policy semantics, historical scope, preservation, metadata-only staging patch and live issue acceptance/metadata. Final staged/committed-scope proof and delivery evidence belong in PR #8; earlier results retain their checkpoint scope. No build, runtime or live validation is required for this metadata/process change.
