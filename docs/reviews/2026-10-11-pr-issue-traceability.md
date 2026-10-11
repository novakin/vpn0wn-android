# Historical PR issue traceability

Last updated: 2026-10-11 (Europe/Paris).

## Scope and evidence

The maintainer requested enforcing the existing [issue/PR workflow](../development.md#issue-links-and-resolution), reusing existing owners and creating issues only for genuine untracked work. The initial inventory covered all 95 closed PRs, all 46 issues then present and open draft [#103](https://github.com/novakin/vpn0wn-android/pull/103). Repository source was clean `master` at `47b86763130416bb48ac53b1e338163833f922e1`. Personal-account GitHub reads checked descriptions, issue timelines, resolving relationships and actual states. This is a tracking assessment, not a historical source or runtime review.

## Findings and correction

The initial inventory contained 94 merged PRs and one closed-unmerged PR, [#65](https://github.com/novakin/vpn0wn-android/pull/65), explicitly retired in favor of the replacement for #66. Ninety descriptions already referenced issues. Five lacked an issue reference: #1–#4 merged on 2026-05-01 before the October issue workflow; #104 instead named the maintainer's workspace-cleanup action.

| PR | Retrospective or remaining owner |
| --- | --- |
| [#1](https://github.com/novakin/vpn0wn-android/pull/1) | #13, later userspace service/lifecycle repairs. |
| [#2](https://github.com/novakin/vpn0wn-android/pull/2) | #13 lifecycle, #33 logs and #48 statistics. |
| [#3](https://github.com/novakin/vpn0wn-android/pull/3) | #10/#11 script safety, #12 result contracts, #17 harness; [#143](https://github.com/novakin/vpn0wn-android/issues/143) owns the unfinished C11/C12 integration. |
| [#4](https://github.com/novakin/vpn0wn-android/pull/4) | #9 later QA-guide reconciliation and #52 separate device acceptance. |
| [#104](https://github.com/novakin/vpn0wn-android/pull/104) | #7 contribution-guidance maintenance; the original requested workspace action is preserved. |

Dated non-closing references preserve the original descriptions and validation claims. They identify later follow-up owners, without implying the May PRs originally used those issues or fulfilled their later acceptance. Missing labels are added by scope; existing assignments and unrelated metadata are preserved. No completed-history tickets are created.

AGENTS.md, the development guide and PR template now explicitly require an owning issue reference for every PR. Existing owners cover bounded maintenance and documentation follow-ups; a named action remains context rather than a substitute. This closes the earlier issue/action alternative without introducing one ticket per mechanical edit or changing partial-delivery closure rules.

One genuine gap required [#143](https://github.com/novakin/vpn0wn-android/issues/143): the existing planned concrete SSH/protected-credential and live VPS/profile/QR integration lacked an issue owner. Its ordered C11 then C12 phases reuse the existing client/factory, Fragment, store/mapper, scripts and import path. Library selection and operational proof remain prerequisites. Creating the issue selects no library, authorizes no implementation or live operation, and does not expand the active E2–E7 goal.

## Verification and limits

Independent ultra review checked the exact issue body, retrospective owner mappings, description edits and additive metadata before publication. GitHub readback verifies published content, actual labels/assignments, unchanged historical heads/merge states, non-closing cross-references and unchanged issue states. Published reviews are scoped to the tracking correction at each historical head; same-account COMMENT reviews retain independent analysis without claiming second-account approval. The delivering documentation PR owns final documentation review, checks and publication acceptance. Public metadata receipts remain in ignored `.artifacts/overnight-20261011/pr-issue-audit/`; no credentials or device/config reports enter this record.

Ordinary non-closing timeline references establish discoverable ownership without a resolving Development relationship. Partial slices retain their broad acceptance issues; adding closing keywords or manual resolving links solely for visibility could close unfinished work. #52/#71/#73 and new #143 remain open, and #103 remains draft. #65 is not delivery. No source behavior, new runtime proof, APK, device/server operation or release results from these tracking changes.
