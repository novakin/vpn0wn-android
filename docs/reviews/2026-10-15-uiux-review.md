# UI/UX review — responsive VPS layout and app follow-ups

Last updated: 2026-10-15 (Europe/Paris).

## Scope and method

The maintainer requested full-width wizard steps and a UI/UX review. This review applies UI/UX Pro Max and Interface Design guidance to the existing Android Views/Fragment architecture, approved read-only prototype and shared tokens. It retains the navy engineering-console direction, Inter Tight labels/titles, JetBrains Mono data, outlined icons and established brand artwork. It does not propose a new design system.

The layout candidate starts at `2055a57a4a1507a165bd57b84d4fac3acfee611f`, plus the scoped Fragment, section-header, resource and test changes. The verified Fragment SHA-256 is `9f604a32d97e67c056b60c724a1a8c359b7edf298c75f4c24a8bc3231e924063`. The PR acceptance record owns its final published head and independent review. Historical Home/Settings/Add/editor evidence retains its original source and device scope; it does not establish current whole-app acceptance.

## Findings and resolution

- **Wizard steps:** ordinary 320dp and 640dp layouts now distribute five equal columns across the available width. Connector halves use spare space inside each badge row. Intrinsic native text measurement preserves complete labels; enlarged labels can overflow through the existing horizontal scroller, which reveals the active stage. No font reduction is used. Normal English/French order is SSH, Detect, Plan, Apply, Import from left to right. Forced RTL stress captures begin on the right and must be identified as such when presented.
- **Native label regression caught during review:** enabling single-line mode also enabled a very wide horizontal text layout. Native painted-bounds assertions and original PNGs exposed invisible labels. Explicitly disabling horizontal scrolling on the label keeps its single-line text within the actual cell. Assertions were retained; the repaired run passed.
- **Short screens and enlarged text:** the wizard header/tracker/form scroll together while Continue remains reachable. Authentication segments grow with their text; shared section headers move the hint below the title when necessary. Numeric port text remains visible in RTL layouts.
- **Interaction semantics:** Repaired VPS icon, footer, key-picker and Add/Apply controls have localized names and at least 48dp targets while preserving visible glyph sizes. Authentication segments retain at least 48dp height and expose checked/selected radio-button semantics; the existing preview switch exposes checked state. Native selectable foregrounds provide interaction-state support. These source/geometry checks do not establish the final composited focus/pressed contrast, spoken TalkBack output or acceptance of every unchanged VPS control.
- **Detail follow-up:** [#193](https://github.com/novakin/vpn0wn-android/issues/193) records the separate 32dp Back/Edit targets, transparent interaction backgrounds and inaccurate Back name found in `tunnel_detail_fragment.xml`. It remains separate from this VPS repair and #180's full-key copying.
- **Existing Home follow-up:** mixed tunnel-state aggregation can still prefer an UP tunnel over another tunnel's error/transition. The [UI roadmap](../ui-redesign-roadmap.md) already owns the aggregate/DEGRADED presentation gap; no duplicate issue was created.

## Executed proof

On 2026-10-15, the repaired layout candidate passed five focused native Robolectric tests on SDK 28: two wizard-layout tests, two shared-header tests and one temporary renderer. Detekt reported zero issues. The run covered English/French, LTR/forced RTL, both system theme modes, 320×640dp portrait and 640×320dp landscape, normal/2× text and both authentication modes. The all-stage layout checks used the existing preview actions; they do not establish real SSH or provisioning behavior.

The renderer produced 128 original-size PNGs with zero collected fit/reachability findings. Root inspected normal French portrait and enlarged English short-landscape originals; independent ultra visual review inspected additional representative originals. Both system-mode requests intentionally retain the approved navy app palette. Evidence stays under ignored `.artifacts/credential-preview-renders-rtl-20261015/` in the layout worktree. The successful command log is `20261015-222357-092-58604b5991aa4598ad638963f7993d1b.log`; failed preceding runs remain retained as failures.

Against `vpn0wn_paper_2`, checked text-role contrast is 17.27:1 for ink, 12.11:1 for ink_2, 7.88:1 for ink_3, 11.07:1 for warning and 7.87:1 for error. These five role checks exceed 4.5:1; they are not a whole-app accessibility certification. Decorative rule colors and native composited interaction states require separate role-specific assessment.

The separate, uncommitted C12 draft passed four repaired regressions: the enlarged/RTL Home matrices, busy-error settlement/reveal and confirmed saved-access removal. That run used injected operations and native test fixtures, not external SSH. Its log is `20261015-222004-710-7d24fc99225e40519289085a10b8df44.log`. The combined layout/C12 candidate still needs integration checks and its own final review.

## Preview and delivery limits

The layout screenshots show the existing preview, whose credential notes truthfully state that input is not saved. The separate C12 draft saves SSH credentials encrypted after successful detection and supports their reuse/removal; it is not represented by these preview screenshots. Its numeric-only SSH hint is already correct. The VPN endpoint field separately permits hostnames. Actual remote install/sync/user creation and usable generated profiles remain pending under [#143](https://github.com/novakin/vpn0wn-android/issues/143).

No APK, emulator, physical phone, real on-screen keyboard, TalkBack speech, live server or remote mutation was exercised for this layout run. Original native JVM graphics establish the checked view output and bounds; physical interaction, composed focus/pressed contrast, motion and final integrated runtime acceptance remain separate. This review supports the bounded layout repair, not a production-ready or 100/100 whole-app claim.
