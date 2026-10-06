# Full documentation review — 2026-10-06

Last updated: 2026-10-06 (Europe/Paris).

[Documentation index](../../README.md#project-documentation) · Substantive content audit, Europe/Paris.

## Scope and baseline

[Issue #9](https://github.com/novakin/wireguard-android/issues/9) owns acceptance. Integration base: clean master `11d4a9b1dfdab834844c997e6ef2cc4f83c6bd43`, merged PR #8. Branch: `docs/full-documentation-review`, isolated from the original dirty worktree. Review covers all 17 previously maintained Markdown guides/templates plus this new record, referenced source/build/test files and the read-only local prototype. The prior [agent/README assessment](2026-10-06-agent-readme-review.md) retains its bounded historical score; it did not fully validate runtime/design guides.

This change reconciles documentation and includes full font-license notices as assets. Runtime repairs, dependencies/frameworks, CI, APK builds, device/root/SSH tests, destructive host changes and deployment are outside this PR. Server-Audit remains a process reference; its templates, metadata/readback, acceptance and decision/evidence conventions already adapted in PR #8 remain intact. No Server-Audit file is changed.

## Findings and resolution

| Finding | Resolution and responsible owner |
| --- | --- |
| Mandatory/random-port UDP preflight described dirty work rather than committed master. | Contracts/operations now describe optional explicit-port preflight, skipped endpoints and logged failures; plan separates initial findings from current C5 teardown/policy work. |
| Maintenance invariants sounded like implemented guarantees for every store/transition. | Architecture identifies required invariants and distinguishes DataStore transactions from direct FileConfigStore writes. |
| Config contract omitted accepted fields and unsupported wg-quick attributes. | Contracts lists actual Interface/Peer fields, optional/bracketed IPv6 endpoints and rejected hooks/SaveConfig; source-confirmed DNS/prefix defects remain explicit. |
| Script README claimed exact planning, health, archives, idempotence and owned uninstall. | Script guide now describes each observed command, defaults/environment overrides, config-derived endpoint, static plan, secret-bearing JSON backup/revocation archive, missing ownership/recovery and non-JSON failure paths. |
| C1 prose implied global parser redaction and stable server errors. | Contracts/changelog limit the repair to tested outer JSON/exit/cleanup paths; nested wrong-type peers and arbitrary error text remain C4. Historical C1 evidence is preserved. |
| Harness output/exit status implied revocation/removal proof and safe failures. | QA/operations distinguish parse/ok checks from missing postconditions, actual-root OpenSSH from Android SSH, first-key acceptance, explicit env export, purge controls and unsafe failure diagnostics. C9 retains required repairs. |
| QA omitted merged logging/config suites and overstated quoting/real-session coverage. | QA lists 12 source-inspected classes/64 methods, adds C1/config suites and labels requirements separately from executions. Development removes unsupported log-parser coverage. |
| Roadmap catalog, status grace/threshold, settings order and theme guarantees drifted. | Roadmap uses actual JSX symbols, current settings groups, first-observation grace and ≥180s threshold, actual hex resources, Application/TV mode and DynamicColors policy. |
| Historical Done items contradicted later polling/animation work or implied global fidelity. | Superseded entries are annotated; rate ownership, Canvas monospace/dp/static-MTU gaps and unscoped old build claims are explicit. |
| VPS prototype controls/security/pseudo-QR implied live Android implementation. | Fleet specification is marked as a target; ZXing placeholder payload, preview apply, missing SSH/Keystore/live action wiring and health heuristics remain explicit. |
| Font guide lacked asset versions/notices and full redistributed license texts. | Embedded metadata inventory and pinned official license sources are documented; full OFL notices are added under main assets. Binary download provenance and actual APK inclusion remain unverified. |
| Delivery pointers still treated merged #8/#7 as pending. | Plan links delivered workflow and current full-guide work; confirmed runtime issues retain separate acceptance/dependencies. |

## Source and evidence

Backend audit read architecture/operations/contracts/changelog and responsible config, Go/WgQuick, manager/store and provisioning sources at the clean baseline. UI audit read the full roadmap/QA/attribution and committed resources, tests, Application/TV, bindings, fragments/custom views and local JSX. Root review covered all provisioning scripts, Android orchestration/models, destructive Python harness, source/build/environment contracts, historical record, agent instructions and issue/PR templates. This was source inspection, not a runtime reproduction.

Decisive owners: [GoBackend](../../tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java), [Interface](../../tunnel/src/main/java/com/wireguard/config/Interface.java), [InetNetwork](../../tunnel/src/main/java/com/wireguard/config/InetNetwork.java), [BindingAdapters](../../ui/src/main/java/com/wireguard/android/databinding/BindingAdapters.kt), [Application](../../ui/src/main/java/com/wireguard/android/Application.kt), [ProvisioningModels](../../ui/src/main/java/com/wireguard/android/provisioning/ProvisioningModels.kt), [script guide](../../third_party/wireguard-install/tunnl/README.md) and [harness](../../scripts/vps_integration_test.py). Owning guides route remaining detail; no secret-bearing output or prototype files enter this change.

## Acceptance and quality boundary

Assess five equally weighted dimensions: current-source accuracy; separation of requirements/history/executions; ownership/discoverability; examples/metadata/attribution; and review/verification/handoff. Full acceptance requires source/check evidence in every dimension and no unresolved material review finding. A perfect documentation rubric does not establish production readiness, device fidelity, operational safety or a complete codebase repair.

Author assessment: no in-scope blockers. Source-supported corrections and structural checks pass; independent ultra re-review found no material remaining findings. Published commit/PR acceptance belongs in the PR description; do not embed this record's own final SHA here.

Independent ultra review identified five corrections: C4 acceptance must cover arbitrary `ok:false` messages; its parser name is `parseStatus`, not client `syncStatus`; DEGRADED presentation belongs in BindingAdapters; selection work must reuse ActionModeListener without invented prototype ALL/NONE controls; and the no-credentials-leave-device pledge must distinguish local private keys from encrypted SSH password authentication. All were corrected and re-reviewed before publication. All eight runtime issue drafts were independently accepted as source-grounded and bounded; this is not formal GitHub approval or permission to merge.

## Runtime handoff and limits

Next source slice is C2 ownership/input safety followed by C3 serialized mutations/recovery, each with synthetic/injected offline proof and owning docs. Confirmed contract/health/parser, transition/lifecycle, file replacement, kernel concurrency, DNS/prefix and harness defects are published as issues #10–#17 under [plan tracking](../improvement-plan.md#runtime-issue-tracking). Actual issue body/label/assignee readback matches the reviewed drafts. C7–C8 and C10–C12 remain planned; rendered screens and source-inspected tests are not proof of completed integrations.

Preservation checks compare all 338 original-worktree baseline paths individually, including retained missing paths/gitlinks. The semantic-candidate task checker and independent run pass 18 Markdown documents, 192 local links/fragments, 52 source assertions, dates/fences/whitespace, sole AGENTS.md ownership, wrapper/CI inventory and the 12-class/64-method test-source inventory. Final issue links are checked again in PR acceptance without refreshing historical runtime evidence.

Three official pinned blob comparisons match the local vendored Nyr installer and both full font notices. Binary download provenance and APK packaging remain unverified; other external links are not a full live-link audit. No fresh Gradle/APK/device/native/root/SSH/live VPS run is claimed.

The full staged whitespace check reports one trailing space at Inter Tight OFL line 21, present in the pinned upstream text. It is retained verbatim rather than rewriting an externally owned notice; the maintained Markdown whitespace check passes. This is a known source-format exception, not runtime proof.
