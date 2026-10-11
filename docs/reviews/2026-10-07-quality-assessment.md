# Quality assessment after native lifecycle repair

Last updated: 2026-10-10 (Europe/Paris).

## Scope and basis

This updates the engineering assessment using the [initial full static review](../improvement-plan.md#scope-and-baseline), subsequent documented source repairs, current unresolved issues and the independently reviewed native candidate on `fix/native-lifecycle-build`, based on master `4c2e5850`. [#66](https://github.com/novakin/vpn0wn-android/issues/66)/[PR #67](https://github.com/novakin/vpn0wn-android/pull/67) own that candidate's acceptance. The installed app and master have not gained this candidate merely because its branch is verified. Experimental warm-up remains separate under #61/#63 and has no completed implementation/device/DPI result.

Scores are engineering judgments, not measured percentages, a security certification or a fresh whole-repository audit. Independent ultra review covers the changed native code, tests, owning guides, complete AGENTS/development/template instructions and PR description. Broad codebase/architecture scores combine the prior audit with subsequent recorded repairs; they remain provisional until another full audit. Historical ratings and the earlier bounded [100-point documentation rubric](2026-10-06-agent-readme-review.md#documentation-quality-rubric) retain their original scope.

## Updated grades

| Area | Grade | Evidence and remaining limit |
| --- | --- | --- |
| Agent/process contract | 9/10 | Explicit ownership, preserved work/configs, scoped authorization, meaningful verification and commit-specific independent review/readback. Publication now uses personal credentials and checks creator metadata. GitHub readback finds master unprotected. Ruleset access returns HTTP 403 requiring GitHub Pro or public visibility for this private repository; rulesets are not independently enumerated. Successful CI does not establish enforced merge policy. |
| Documentation | 9/10 | Maintained ownership, dates, source contracts, acceptance and historical evidence; native lifecycle/build and review-publication behavior are documented together. Link/date checks support semantic review. This change does not freshly re-audit every product guide or prove described runtime behavior. |
| Native repair | 8.5/10 | Direct standard Unix-listener ownership, client drain, coherent failure cleanup and cached build dependency evaluation. Seven race tests, four Android ABI compilations and incremental source-edit proof support the candidate. Physical Android and macOS execution remain unverified. |
| Whole codebase | 7/10, provisional | Privacy, persistence/config, lifecycle, static-analysis, export and UI/source repairs improve the original 5/10 baseline. [#48](https://github.com/novakin/vpn0wn-android/issues/48) statistics correctness, [#17](https://github.com/novakin/vpn0wn-android/issues/17) harness postconditions/redaction and incomplete real SSH/VPS integration remain material work. This is a cumulative judgment, not a new exhaustive code review. |
| Architecture | 8/10, provisional | Existing DataBinding/Fragment, model/backend, config-store and provisioning boundaries remain coherent. Native resource ownership now fits a standard library listener rather than upstream queue/watcher machinery. Concrete SSH transport/credential ownership and complete fleet operations remain unfinished; no foundation rewrite is justified. |
| Runtime/performance confidence | 6/10 | Bounded phone evidence and recorded transfer trials exist. [G4/#52](https://github.com/novakin/vpn0wn-android/issues/52) retains incomplete provider/accessibility/support-matrix checks, a clean Wi-Fi context, controlled idle-energy baselines and demonstrated optimization. Native candidate protection/traffic and experimental DPI benefit are not established. |

The whole project is stronger than the initial baseline, but a 100/100 overall claim would hide the remaining integration and runtime acceptance. Source quality, independent review, merge state and installed/device behavior are separate judgments.

## Review findings and next acceptance

The first native checkpoint left clients queued inside upstream `ipc.UAPIListener`; direct Unix-listener ownership resolves that P2 finding. The corrected review found no production blocker and identified a P3 host-test portability gap: long `t.TempDir` paths can exceed macOS Unix-socket limits. Tests now use short temporary prefixes; Linux race proof is rerun, while actual macOS execution remains unclaimed. Final-head review and CI results belong in the PR acceptance record.

Next: complete #66 source integration through the authorized review/check/merge process, then finish the isolated #61 protected-bind/warm-up draft. Keep #63 draft and unmerged. APK/device/network operations still require their own authorization. Do not substitute extra packets or source placement for demonstrated DPI benefit; retain baseline/no-change if compatibility or lifecycle regresses, or benefit remains inconclusive.

To improve the grades, verify/configure master CI protections only when the account supports them and configuration is authorized; do not change billing or repository visibility to satisfy a grade. Retain explicit manual gates meanwhile. Complete a demonstrated final-head review/merge/readback cycle, audit all current guides against source and retain a reproducible clean-clone documentation checker. Native confidence needs separately authorized Android startup/stop, partial-client/restart cleanup and actual macOS host-suite execution. Adding instructions or claiming 100/100 does not close these evidence gaps.
