# Translation source inventory and semantic review — #195

Last updated: 2026-10-17 (Europe/Paris).

## Verdict and scope

The 2026-10-16 sections below retain their original resource, check and output scopes. The [2026-10-17 workflow-copy follow-up](#final-workflow-copy-follow-up--2026-10-17) records the later resource inventory, evidence reuse and pending verification without replacing those historical receipts.

The mechanical inventory and model semantic reading pass cover default English and all **35 locale qualifiers**, totaling **7,982 explicit resource declarations and 8,595 text nodes**. One text node is one string value or one declared plural item; inherited/default fallback is not counted as translated local text. This is a bounded source translation review for [#195](https://github.com/novakin/vpn0wn-android/issues/195), not the separately requested post-merge whole-codebase/security audit.

**Scoped author satisfaction: satisfied with the confirmed source repairs and inspected localized text/font output; full #195 delivery is not accepted.** The executed checks and output boundaries are recorded below. No qualified native speaker certified these languages. Model confidence in literal technical/caller-backed defects does not certify grammar, idiom, preferred terminology or completeness. Installed Android, accessibility and final published-head acceptance remain separate.

The reviews began at `ee1cf84155ee752c462fed5452d3e4bc94c9e81a` with master `7ae1e3e8155d10be4af5974cdf1eb83eae0600b3` being integrated. The signed merge `60281d87e08271cbd5090af03d5d0e6e8efd2b19` is the later translation integration base. Review hashes below pin the actual pre-repair inputs; they are not a claim that subsequent edited files or a newer default branch were reviewed. Later integration retains the delivered archive/restore source through master `fb458e7b97d7f89fcacbcb7475713ee099090790`. The current PR acceptance record owns the final published revision, independent review and CI; the source and output receipts below retain their own input identities.

## Mechanical inventory

The source inventory scanned every `res/values*/*.xml` under the `ui` and `tunnel` source sets. Definitions exist in UI main/debug; tunnel defines no strings/plurals/arrays. All 52 values XML files parsed, including 37 string/plural-bearing files. All 37 retain the exact leading fork modification notice; no duplicate declarations, locale-only extras, absent plural `other`, unresolved whole-value local resource reference or raw-percent anomaly was found in that input snapshot. XML/source scanning is not AAPT compilation or Android formatting.

Default main contains 540 declarations: 523 strings and 17 plurals, including 536 translatable and four intentionally nontranslatable values. Debug adds one nontranslatable `app_name` override and belongs to the mechanical inventory; the semantic totals above cover the 36 main default/locale files. The intentional main exclusions are `app_name`, `vpn0wn_home_wordmark`, `vpn0wn_peer_rx_label` and `vpn0wn_peer_tx_label`, without translated overrides.

French supplies all 536 translatable declarations in the reviewed snapshot. The other qualifiers directly omit **315–440** translatable default keys and therefore retain substantial fallback. Hindi-India inherits nine additional definitions from Hindi; 332 keys remain absent after that explicit parent. The inventory records same-language absence separately and does not simulate sibling-region Android resolution. A locale folder, an identical-default value or English fallback is not evidence of a complete translation.

Formatter comparison identified one non-count contract defect: Catalan `delete_error[one]` omits both the count and diagnostic argument. No current production plural caller was found, so this is retained-resource drift, not a reproduced deletion-screen failure. Ten other signature differences concern plural-count presence only; legitimate Arabic dual forms must not be rewritten mechanically. The inventory also records 2,613 formatter-bearing leaves, 262 leaves with backslashes, 32 with XML-decoded angle brackets, and no child markup or replacement character. These source observations require final edited-resource and platform checks before acceptance.

The original mechanical evidence remains in ignored `translation-inventory/inventory-baseline.json` and `locale-summary-baseline.csv`. The initial source inspection and narrow technical contract review ran no Gradle, lint, AAPT, JVM/render, APK/device or live operation. Later file drift is explicitly recorded rather than replacing the original input hashes. Regenerate the mechanical inventory after all repairs settle.

## Confirmed defect groups and current resolution

The main report's 22 finding groups and delegated findings produced narrow source-text corrections implemented by the owner. Existing keys, placeholders, plural ownership, protocol identifiers and runtime policy remain the contract. Source checks and scoped output are separate from merge, complete localization and installed-device acceptance.

| Main-report groups | Confirmed mismatch and narrow repair |
| --- | --- |
| S01–S04 | Repair/Backup feedback must not claim queued work or an exported backup when those actions remain unavailable. Current authenticated profile guidance must reflect delivered actions without bypassing fresh verification. Empty fleet copy must describe the actual SSH entry point. French cancellation must warn that admitted remote effects cannot be undone. |
| S05 | FR/ES/pt-BR/Indonesian/Italian boot summaries must describe reactivating previously active tunnels, rather than displaying, creating or activating every configured tunnel. |
| S06 | Configuration diagnostics must retain the caller's section/attribute argument roles in FR/Catalan/Spanish/Greek/Polish/Ukrainian. |
| S07, S17 | Export restrictions must not promise inaccessible keys; permission text must retain traffic-misdirection risk. These are meaning repairs, not security implementation acceptance. |
| S08, S15 | Indonesian exclusion and application-selection actions, plus Chinese Select/Clear All, must describe the actual inclusion/exclusion/toggle selection operation. |
| S09–S11 | Preserve 32-byte key sizes, hexadecimal/Base64 and WireGuard identities, and the supported keepalive range 0–65535, with zero disabled. Remaining locale-specific terminology needs separate review. |
| S12–S14 | Preserve Go as an implementation name, exact `wgTurnOn`/shell identifiers, export-versus-import direction and the user's authentication purpose. |
| S16, S19, S21 | Vietnamese diagnostic operations, Slovenian export tense and Dutch missing-config wording must describe the actual failure or future action. |
| S18 | Backend summaries may describe a possible stability benefit; they must not promise observed stability. |
| S20, S22 | Repair confirmed retained-resource and grammar defects without claiming a current UI consumer for dormant German/Dutch labels. French interval, Danish backend and Romanian attribute wording retain their intended roles. |

The six delegated qualifiers add separate caller-backed findings: Arabic remote-control recommendation polarity, last-used tile semantics, key-access overstatement, error joining and Go identity; Persian `wg-quick`/Go identity, empty-TV Add guidance, config grammar and tile/toggle meaning; Hindi/Hindi-India section/endpoint terminology, UP/DOWN direction, partial-import counts, remote-control/permission meaning and technical installer literals; Punjabi section/keepalive/Go wording; Sinhala IP-versus-key meaning, executable/backend identity and networking/programming nouns. Their pre-repair hashes and complete reading counts are included below. The owner applied the accepted main/delegated proposals; later verification is scoped below. Model-proposed wording is not native editorial approval.

Retain uncertainties separately: Turkish/Vietnamese/Swedish networking terminology, Sinhala binary-unit abbreviations and transliterations, plural agreement and legacy DNS-search labels without current consumers. An unchanged English title or technically correct literal is not automatically defective. Historical tool-installer success text can be wrong while current fork IDs still reject installation; source wording does not establish a successful privileged operation.

## Reviewed input manifest

Group A is the default plus 29 locale files read in the main semantic report. Group B is the six-file delegated Arabic/Persian, Hindi and Punjabi/Sinhala pass. All explicit values and plural items were read; none of these counts is a rendered sample count. The earlier coverage Markdown retained its six-pending checkpoint; the completed delegated reports and aggregate JSON establish subsequent source-reading completion without rewriting that history.

Each SHA-256 below belongs to `ui/src/main/res/<qualifier>/strings.xml` **before owner repairs**. Do not refresh these hashes to the edited files.

| Qualifier | Resources | Text nodes | Group | Pre-repair SHA-256 |
| --- | ---: | ---: | --- | --- |
| `values` | 540 | 557 | A | `ceaa96bc4a556fa2b828c097b5a54405d0f0e1d7b1c8ba36d9d1e57b65280ed8` |
| `values-ar-rSA` | 221 | 288 | B | `5f802faee91599e2a30112c41bf46526cf9847b96f3b56a6035a6c756eee78a1` |
| `values-ca-rES` | 221 | 236 | A | `1fefb25314fe35b1109351daa76b0f7a60fcf26c144ed2762e62eb139b0943f0` |
| `values-cs-rCZ` | 221 | 262 | A | `8798c77cec36f48ce267ce1bb70a99f51757f302565eb564741ab904b999e330` |
| `values-da-rDK` | 221 | 236 | A | `c4e3b4f5cec486aab4318577cdeeeccf9eb25d54077598c8931f285e482c7b2f` |
| `values-de` | 221 | 236 | A | `13114ab65631c65b7e860b939721fc17fd88e7ee3ce70e7e1bcb99fa52b554bf` |
| `values-el-rGR` | 113 | 128 | A | `6e0682be3e185965b8981e735391ff321e168b6eefd5f8238fbd568cd35884b0` |
| `values-es-rES` | 221 | 236 | A | `889b29c7f8b5bb3df14c76f9c1adcc36bcb3b386dcfd92ee13c4cd0873b5bccf` |
| `values-et-rEE` | 218 | 233 | A | `1bf8b4eacc930b77fb3675122dd10192dc6a0dcf0d0f77a11dfd6ee320f337ac` |
| `values-fa-rIR` | 221 | 236 | B | `8e65c6de4dfa15b86300d1dae7815fb8da000d9eb4faac9c65d823df64788222` |
| `values-fi-rFI` | 96 | 101 | A | `05d550ce5e38bf68ae8a12c79b9d653c23aef362beb9b3a0e9fa81ebc7f35179` |
| `values-fr` | 536 | 553 | A | `e9abc6153329b3820b13e56480008fba7270d0d3ba2c9c80054773da6def701b` |
| `values-hi` | 172 | 180 | B | `6b083850aa1ae43a5d298863bda212dcef7f12adb1b11e5f1e1f86fff633ac7b` |
| `values-hi-rIN` | 195 | 210 | B | `f43d53c0117532ebf6c10a2f23eb292407ac04582fe779457f36e739de2fb3c6` |
| `values-hu-rHU` | 117 | 119 | A | `b0342e19ee7df662e72dee3cc970ff0429b40f57fe897ebb9ce25903a8553c42` |
| `values-in` | 221 | 223 | A | `e8e2f94abb0ecbafb474ee5c4847f54312fb70a3de64ec87ec09cf5dfc4a3d77` |
| `values-it` | 221 | 236 | A | `6df7c2f12c8872c23d5191604be98591a4accd4d677d749bbddf2c114bdbc81f` |
| `values-ja` | 221 | 223 | A | `9393ef529cdf5c213fde93fe11235d0ca7c3904fc87f3a473c0a86aac4e4471c` |
| `values-ko-rKR` | 221 | 223 | A | `7f60b92601e020871f8ea13950f3049cac8e9f3f49c042a873ade903713ac71b` |
| `values-nl-rNL` | 213 | 228 | A | `7713e103fb7c420f0fbea54105dbecbd1c7f69e77bab1ad6168b19e5828d615d` |
| `values-no-rNO` | 221 | 236 | A | `72d98206cb66a456ed84f577803059913434fcbc5f5ed09282aecfc2f7ec12bb` |
| `values-pa-rIN` | 221 | 236 | B | `2b21c6d56e3beea327dd2d3e8923d8c965d21f74fbbeffa20047b13a086da59c` |
| `values-pl-rPL` | 221 | 262 | A | `51e93ad4d1b1a511b22edf152e4ba188566ab5893f56eb1230b4d3bc8ee93a80` |
| `values-pt-rBR` | 221 | 236 | A | `ffd3083a21490631f00a784eacbf98431febc40029ef2674fc5d292989a3a6b0` |
| `values-pt-rPT` | 210 | 225 | A | `cac819a64e4fe2639c2f8dd03af9a3e4fea6d04431953b72284d9164bf334ca0` |
| `values-ro-rRO` | 218 | 246 | A | `2663a377bc6d8f0c6249989f30c41be74ff10b585064263381bef669b6c9ffa5` |
| `values-ru` | 221 | 262 | A | `ec018fa5c08773d021e80736e93b5a7d3e929e5f448d961fd813c5f465ea8dd1` |
| `values-si-rLK` | 195 | 205 | B | `557bac4643bab8d6c7eb8c193fd8d16fb23d22926ee5cbb033f14b5b741256e2` |
| `values-sk-rSK` | 173 | 175 | A | `598d48b66a069e732f889391ab1f67c65c059992c2d971b406ab378f0aa4036e` |
| `values-sl` | 221 | 262 | A | `3681f9a65e6ed62d1d47d2961f493b762f3ceba197131062fb4b5fa260f74735` |
| `values-sv-rSE` | 221 | 236 | A | `625cc6c1ffe7fc8b3b5568800b91c4729beaba6966e4175d9833cd7c2a096dbd` |
| `values-tr-rTR` | 221 | 236 | A | `cf20a01ac0742c3d964e5152ebf98029ffbadb0ccdd60258b1b2c32f64ba2980` |
| `values-uk-rUA` | 221 | 262 | A | `d02c9640a80238e6878974511f8ce53be82873a2b3557a0d70f9d27e914cde3a` |
| `values-vi-rVN` | 124 | 126 | A | `9fc8ecb871b22119f500a52ddbb3626dadbf65c96ad194d9ea611f616ad3e64d` |
| `values-zh-rCN` | 221 | 223 | A | `741f880649325958f076206466ab2bd500c638ee37acf1a06fb402ff048b01a7` |
| `values-zh-rTW` | 221 | 223 | A | `e89865e160f09252d27844f5b44a73453ec9ae46565a3922e73ad59c523d4de5` |

## Evidence, verification and remaining acceptance

The final corrected-resource mechanical rerun preserves 540 default declarations, 536 translatable keys and 35 qualifiers, with zero XML, duplicate, extra-key, missing-`other`, leading-notice, local-reference or non-count formatter defects. Main resources retain 7,982 declarations and now contain 8,629 text nodes after adding 34 plural items. Thirteen plural-count-presence variations remain explicit, including the added Arabic implicit one/two forms. The original failed Catalan diagnostic signature is repaired; this rerun does not replace the pre-repair hashes or confer linguistic certification.

Independent ultra review identified 30 further positive-only keepalive explanations after the first semantic corrections. These now use the language-neutral inclusive range `0–65535`; the error context already identifies `PersistentKeepalive`. Arabic/Persian wrap that range with explicit LTR isolates through Android Unicode escapes. Existing correct localized explanations retain their disabled-zero guidance. An all-35-locale resource regression checks both bounds and the two RTL isolates; actual increasing layout order also passed the scoped output check below. This avoids uncertain bulk prose while keeping the supported numeric contract.

Concrete remaining hardcoded copy stays under #195: editor actions and section headings in `tunnel_editor_peer.xml`/`tunnel_editor_fragment.xml`; topology and application-routing labels in `tunnel_detail_fragment.xml`, `TunnelDetailFragment.kt`, `TunnelThroughputView.kt` and `BindingAdapters.kt`; generated labels/statuses in `VpsDeployMapper.kt` and `VpsProvisioningFragment.kt`; and the Logs ALL filter in `log_viewer_activity.xml`. Protocol identifiers, user-supplied values and deliberate log severity codes are separate from these app-owned labels. This source slice does not claim that every visible app string is localized.

Preparing the actual MTU error probe also found a distinct parser diagnostic: negative MTU uses `Location.LISTEN_PORT`. [#206](https://github.com/novakin/vpn0wn-android/issues/206) owns that unfixed source defect. The translation probe uses overflowing integer input, whose existing parser identifies MTU correctly; it cannot establish negative-MTU correctness.

Local ignored evidence is retained under `.artifacts/continuation-20261016/translation-semantics/` and `translation-inventory/`: main `semantic-review.md`, `coverage.md`, `coverage.json` and per-node `resource-coverage.json`; mechanical `summary.md`/`contract-review.md`; delegated `semantics-ar-fa.md`, `semantics-hi.md`, `semantics-pa-si.md` and `semantics-six-coverage.json`. This maintained record summarizes findings and preserves input identity; raw local reports are not committed as a transcript.

The original six API-28 `LocalizedResourcesTest` controls expanded to thirteen focused regressions. They do not form an exhaustive locale/placeholder/semantic gate. Executed checks and remaining boundaries are recorded below and in [translation QA](../qa-test-plan.md#translation-resource-contracts--195). Current lint warning settings and ordinary CI do not establish complete translation coverage.

UI review must retain the approved Inter Tight/JetBrains Mono roles, palette, native Material controls and Fragment/DataBinding ownership. Inspect actual localized text at supported phone/tablet sizes, portrait/landscape, normal/enlarged text and both themes, asserting actual locale and resolved RTL direction. Check complete warnings/errors/actions, bidi formatting, glyph/fallback coverage, native scrolling, 48dp targets and focus/inset reachability. JVM/manual native-view output still does not establish installed-device, IME, spoken TalkBack or real authentication behavior.

[#195](https://github.com/novakin/vpn0wn-android/issues/195) remains open for substantial missing translations/fallback, qualified per-language review, hardcoded copy and wider native/runtime acceptance. No locale receives a perfect-quality, certified, release-ready or complete-localization verdict from this source pass.

## Plural, font and focused verification follow-up

Focused localization lint first failed with 16 `MissingQuantity` errors: the Home selection and delete-sheet plurals in Slovak, Romanian, Czech, Polish, Russian, Slovenian, Ukrainian and Arabic lacked required categories. The repair adds 34 items and corrects integer agreement. Czech/Slovak decimal-only `many` entries preserve the existing integer `other` fallback; the actual collection-size consumers accept integers, so no decimal-output support is claimed. Arabic singular/dual deletion forms encode their count grammatically. Ukrainian keepalive `one` now retains `%d` when that category selects 21 or 101 seconds.

The final `LocalizedResourcesTest` adds 34 locale/count cases for actual Android quantity resolution and separate Ukrainian 1/21/101 checks. A focused 43-test gate passed with zero failures/errors/skips and zero Detekt findings. Focused localization lint passed with zero errors and **600 warnings**: 491 `MissingTranslation`, 92 `MissingQuantity` and 17 `ImpliedQuantity`. The latter categories include newer Romance million forms, hidden keepalive-zero branches and retained unused suffixes; their existence is disclosed rather than suppressed or called translated. The transient lint selector enabled nine verified localization checks only; this is not a full Android lint pass, and repository severities/configuration were unchanged.

Native font diagnostics also confirmed a real [#34](https://github.com/novakin/vpn0wn-android/issues/34) defect: an inflated preference summary selected Inter Tight rather than its declared JetBrains Mono. Typeface identity, native advances and nonempty outlines matched Inter Tight and differed from Mono. AppCompat's inherited support `fontFamily` took precedence over local `android:fontFamily`. Clearing the competing support family in `AppThemeBase`, while retaining Android's Inter Tight default, restores existing explicit font roles without duplicating attributes or changing assets/palette. Compat font loading retains API-24 support. The focused four-class gate then passed **70 tests**, zero failures/errors/skips, with zero Detekt findings and the same passing localization lint. A subsequent test-only strengthening asserts actual Activity day/night configuration; final-head CI owns execution of that final assertion delta. These overlapping 43/70 totals must not be added together.

## Scoped native-view output

The successful ignored probe at `a9b6b98b` plus the retained eleven-file source/test delta produced **188 PNGs across 20 actual configurations**, one passing test and no recorded layout findings. Before/after revision, status, index and all 578 input-hash entries matched. The source per-file hashes and retained runner identify the exact tested bytes; the final published PR revision remains a separate acceptance record.

The matrix covers FR, AR-SA, zh-TW and Indonesian in both resource theme buckets at 320×640dp/1× portrait and 640×320dp/2× landscape; FR also covers both 800×1280dp tablet buckets, and FA-IR adds portrait/day and enlarged landscape/night. Actual roots assert locale, scale, layout direction and `uiMode`. Actual Settings/editor/transfer/VPS consumers use synthetic data and a fake peer; privileged mutation commands are rejected. Long text is checked without ellipsis, with scrolling, complete line reachability, successful focus requests and 48dp checked targets. Focus captures can be byte-identical to the unfocused frame; no distinct visible keyboard-focus styling is established. Arabic/Persian layout runs and original glyphs show increasing `0–65535` order. This is a generic-Application fixture with forced resource configurations, not verification of the product's theme-preference policy.

Root inspected 20 successful-run originals, one from every configuration: Arabic/Persian range errors, Indonesian key diagnostics, Chinese export failure, French warnings/confirmations, Settings and tablet fleet output. Text, glyph fallback, numeric order, approved font roles, contrast and checked scrolling are satisfactory within that sample. Independent output/evidence acceptance is recorded in the final PR. Not every PNG was inspected by root. Interface Design, Design System, Frontend Design and UI/UX Pro Max informed the bounded review; approved native owners and visual intent remain.

Six earlier failed runs remain retained, with 0/0/0/0/8/12 PNGs. The first setup failures concerned synthetic stat metadata and the wrong two-state-summary getter. The two font failures established the real product defect above. Later dialog failures concerned missing traversal settlement before capture and queued dismissal before an assertion; normal looper settlement and actual attachment assertions fixed the harness without removing indicators, substituting fonts or weakening checks. Partial captures are not promoted to a successful matrix.

Manual dialog compositing and synthetic filesystem metadata do not establish installed WindowManager/insets, OS authentication/Keystore/provider behavior, physical touch, IME, spoken TalkBack, complete glyph/locale/size coverage, live SSH/VPN or release behavior. Existing English fallback, hardcoded editor chrome and wider RTL/fidelity/accessibility work remain under #195 and their UI owners, including #34/#35. This partial slice does not close those issues, provisioning #143 or backup #196. The post-delivery whole-code/security review remains separately owned by [#208](https://github.com/novakin/vpn0wn-android/issues/208).

## Final workflow copy follow-up — 2026-10-17

This follow-up reviews source based on guarded-restore head `1ba888c596f2a28dd19cd081bc118c3515391ca5` plus the scoped resource/test/documentation changes described here. That restore source was delivered in PR #239 as `87770c0e`; native/live acceptance and #196 completion remain separate. The current signed source also incorporates the reviewed shared-header/Canvas predecessor, with PR #241 still awaiting final CI and merge; the original local test-input identities below remain unchanged. Live #195 was read as OPEN with its original final-workflow, fallback, language-quality, native-output and independent-review criteria. This records implementation, scoped local checks and synthetic output generation, not final published-head acceptance.

### Corrected behavior and pending proof

Indonesian `persistent_keepalive_seconds_unit[other]` now uses `%d` instead of a literal ten seconds. `tunnel_detail_peer.xml` supplies the configured value as quantity and format argument. The new `LocalizedResourcesTest.indonesianKeepaliveUsesTheConfiguredInterval` resolves the actual Android plural at 25 seconds and requires `setiap 25 detik`; it passed in the scoped gate below. Existing Ukrainian repeated-one and Arabic implicit-count forms remain unchanged.

Default/French `vpn0wn_user_removed_stopped` and `vpn0wn_user_remove_observed_stopped` now say removal/suppression instead of revocation. `VpsProvisioningFragment.addUserRemovalStatus` selects these for REMOVE on a stopped interface, while REVOKE has separate archived-operation messages. Its polite accessibility live region uses the same corrected text. `vpn0wn_restore_confirmation` now names `%12$d` Revocation records/Archives de révocation, matching `vpn0wn_backup_revoked_count` and the actual `plan.revokedCount`. IDs, argument positions/types, count categories, persisted records and remote behavior are preserved.

Scoped author source verdict: satisfied with these caller-backed corrections and the evidence-reuse boundary; independent bounded source/output review is complete, while final integrated-head review/CI, publication and full-issue acceptance remain separate. Interface Design, Design System, Frontend Design and UI/UX Pro Max informed precise actions, complete warnings and preserved component roles. No fonts/layouts, new localization framework or general grammar normalization are included. Root's frozen eight-file gate passed 77 tests across five complete classes, with zero failures/errors/skips and zero Detekt findings, using in-process Java 21 offline execution. Fresh XML reports and source hashes were independently checked; `LocalizedResourcesTest` contributes 15, `QuantityFormatterTest` 2, `AddPeerPresentationTest` 10, `VpsProvisioningWorkflowTest` 49 and `VpsFreshRestoreLayoutTest` 1. Thirty fresh restore originals retain that eight-file scope. Six confirmation originals were inspected: EN day/night shows the corrected count label, full warning and native actions; the French stress top/bottom is legible, but the changed middle term is outside those two views. The earlier fixture requested RTL without establishing effective native resource/view direction; those images are not relabeled as settled RTL proof.

The later ninth-file test-only supplement passes the complete one-test `VpsFreshRestoreLayoutTest` with zero failures/errors/skips and zero Detekt findings. It produces 33 originals, including actual revocation-record middle frames in EN day/night and enlarged French stress. Robolectric 4.16 reapplies locale direction after combined qualifiers; applying additive direction last, before Activity/dialog creation, preserves the selected locale and makes the actual resource and settled/captured view-direction assertions meaningful. Controlled qualifiers/font scale are restored; no manual dialog-decor direction assignment or product change is needed. This repeats one existing control and is not 78 distinct tests.

A separate ignored temporary method derived from the current `VpsProvisioningWorkflowTest` passes one native control and emits 32 originals for actual fixture REMOVE and read-only observed stopped settlement: EN/FR, day/night, 320×640 at 1× LTR and 640×320 at 2× RTL, with top/copy frames for both outcomes. Resource locale/font/night/direction and settled frame/Fragment direction are asserted without manually assigning view direction. Exact tracked WorkflowTest bytes and all nine frozen source hashes are restored/verified afterward; the temporary method is not committed. Its first runner attempt stopped before Gradle because PowerShell converted the null `File.Replace` backup argument to an empty path. Failed preparation evidence remains retained; a disposable-file proof verified the typed-null correction before the successful run. Independent ultra accepted all thirty-two distinct originals across the two fresh sets: nineteen restore and thirteen removal images, with the other thirty-three files verified as exact duplicates. The changed English/French terminology is visible, including the complete French revocation-record label. This scoped synthetic output verdict does not establish installed behavior or qualified language review.

Selected localization lint passes with zero errors and 694 warnings: 585 `MissingTranslation`, 92 `MissingQuantity` and 17 `ImpliedQuantity`. The selected `StringFormatInvalid`, `StringFormatCount`, `StringFormatMatches`, `StringFormatTrivial`, `ExtraTranslation` and `BidiSpoofing` checks report no findings. All 109 plural-warning signatures match the historical 600-warning report after normalizing checkout prefixes and shifted line numbers; exact ID, severity, message, source text and locale remain equal. They are disclosed unresolved plural/caller-boundary limits, not automatically harmless findings. The 94 additional missing-translation keys exactly match the 94 added workflow declarations already inventoried below; no prior missing-translation warning key disappeared. No new format defect is identified by these nine selected checks, which are not a full Android lint pass or qualified language review. No APK, ADB or live operation ran. Historical test/lint/image results retain their own source scopes; installed/accessibility, wider output and final-head review/CI remain separate.

### Historical input and semantic evidence reuse

All 36 original pre-repair resource SHA-256 values in the retained coverage manifest match commit `60281d87e08271cbd5090af03d5d0e6e8efd2b19` exactly. The later corrected resources were accepted through PR #207 at `b5db3cce80dda73d46e942d58076ce0045606638`, merged by `56509a3ad8ed1c8a5e912b87b637990bf4d15135`. The initial `ee1cf841` checkpoint matches only the 34 non-default/non-French original files; the recovered complete snapshot removes ambiguity about the integrated reading inputs without rewriting old hashes.

Compared with accepted b5db3cce resources, 33 locale files remain byte-identical. Indonesian changes only the confirmed interval leaf. Default and French each add 94 declarations and change three retained keys, with none removed; 537 English/533 French retained declarations remain unchanged. The 97 added/changed EN/FR workflow keys were independently model-read against backup/import, remove/revoke, rotation and restore callers; their positional/typed signatures match, and this follow-up rechecks its affected terminology delta. Existing dormant `vpn0wn_peer_mutation_unavailable` remains stale after rotation integration but has no production caller; it is retained-resource drift, not a reproduced visible failure. No language receives native-speaker certification from model reading.

### Current locale and fallback inventory

Static inspection parses all 52 UI/tunnel source-set values XML files, with no parse/duplicate errors and all 37 leading text-file modification notices preserved. UI main now contains 634 declarations/630 translatable defaults; all main default/locale files total 8,170 declarations and 8,817 text nodes. Tunnel defines no text resources. French explicitly supplies all 630 translatable defaults; all 34 other qualifiers omit the 94 added workflow keys. No locale-only extra key is present. Format comparison retains twelve legitimate plural count-presence differences; this mechanical scan is not Android compilation, native quantity selection or a comprehensive format parser.

Each row records explicit declarations, directly absent translatable defaults, keys supplied by an explicitly available same-language parent, remaining absence after that parent, exact-default text candidates and semantic status. `Carried model` means historical model reading/corrected-source evidence reused across unchanged file bytes, not a new language review. `Model delta` adds the independent EN/FR workflow reading; `Interval fix` adds the Indonesian caller-backed repair. Qualified native review is unperformed for every row. Same-default candidates include legitimate technical literals and are not automatically defects or completed translations. Remaining absence uses English fallback where Android selects the default; these counts do not simulate sibling-region Android resolution. Only Hindi-India has the explicit Hindi parent here.

| Qualifier | Explicit | Direct absent | Parent keys | Absent after parent | Same-default candidates | Semantic status |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `values-ar-rSA` | 221 | 409 | 0 | 409 | 2 | Carried model |
| `values-ca-rES` | 221 | 409 | 0 | 409 | 10 | Carried model |
| `values-cs-rCZ` | 221 | 409 | 0 | 409 | 13 | Carried model |
| `values-da-rDK` | 221 | 409 | 0 | 409 | 15 | Carried model |
| `values-de` | 221 | 409 | 0 | 409 | 13 | Carried model |
| `values-el-rGR` | 113 | 517 | 0 | 517 | 9 | Carried model |
| `values-es-rES` | 221 | 409 | 0 | 409 | 11 | Carried model |
| `values-et-rEE` | 218 | 412 | 0 | 412 | 9 | Carried model |
| `values-fa-rIR` | 221 | 409 | 0 | 409 | 9 | Carried model |
| `values-fi-rFI` | 96 | 534 | 0 | 534 | 8 | Carried model |
| `values-fr` | 630 | 0 | 0 | 0 | 43 | Model delta |
| `values-hi` | 172 | 458 | 0 | 458 | 11 | Carried model |
| `values-hi-rIN` | 195 | 435 | 9 | 426 | 2 | Carried model |
| `values-hu-rHU` | 117 | 513 | 0 | 513 | 3 | Carried model |
| `values-in` | 221 | 409 | 0 | 409 | 11 | Interval fix |
| `values-it` | 221 | 409 | 0 | 409 | 14 | Carried model |
| `values-ja` | 221 | 409 | 0 | 409 | 9 | Carried model |
| `values-ko-rKR` | 221 | 409 | 0 | 409 | 9 | Carried model |
| `values-nl-rNL` | 213 | 417 | 0 | 417 | 18 | Carried model |
| `values-no-rNO` | 221 | 409 | 0 | 409 | 11 | Carried model |
| `values-pa-rIN` | 221 | 409 | 0 | 409 | 9 | Carried model |
| `values-pl-rPL` | 221 | 409 | 0 | 409 | 11 | Carried model |
| `values-pt-rBR` | 221 | 409 | 0 | 409 | 12 | Carried model |
| `values-pt-rPT` | 210 | 420 | 0 | 420 | 13 | Carried model |
| `values-ro-rRO` | 218 | 412 | 0 | 412 | 9 | Carried model |
| `values-ru` | 221 | 409 | 0 | 409 | 2 | Carried model |
| `values-si-rLK` | 195 | 435 | 0 | 435 | 6 | Carried model |
| `values-sk-rSK` | 173 | 457 | 0 | 457 | 7 | Carried model |
| `values-sl` | 221 | 409 | 0 | 409 | 2 | Carried model |
| `values-sv-rSE` | 221 | 409 | 0 | 409 | 10 | Carried model |
| `values-tr-rTR` | 221 | 409 | 0 | 409 | 8 | Carried model |
| `values-uk-rUA` | 221 | 409 | 0 | 409 | 12 | Carried model |
| `values-vi-rVN` | 124 | 506 | 0 | 506 | 3 | Carried model |
| `values-zh-rCN` | 221 | 409 | 0 | 409 | 11 | Carried model |
| `values-zh-rTW` | 221 | 409 | 0 | 409 | 5 | Carried model |

Direct absence outside French is 409–534 keys. Hindi-India inherits nine keys from Hindi, reducing 435 directly absent keys to 426. These are explicit fallback/completeness limits, not a full-localization or release-ready verdict.

### Workflow-follow-up hardcoded-consumer audit — historical input under #195

The final workflow audit rechecked the following actual consumers at its reviewed source. This is the pre-extraction inventory; the resource-backed follow-up below owns the later source status. They are app-owned language that still bypasses locale resources; they are not silently included in resource-node reading totals. The smallest future correction belongs at each existing owner, preserving technical IDs, actual user/server values and approved style/layout roles. This bounded numeric/terminology slice does not extract those consumers or close #195.

| Existing owner | Confirmed remaining display text / reuse boundary |
| --- | --- |
| `layout/log_viewer_activity.xml:138` | ALL filter; existing `vpn0wn_filter_all` already provides a resource owner. DEBUG/INFO/WARN/ERROR remain deliberate severity codes. |
| `layout/tunnel_editor_fragment.xml:167,246,409,429`; `tunnel_editor_peer.xml:73,89` | LOCAL SIDE, regen, EXCLUDED APPS, configure, remove and paste Base64 key. Field captions in both files also remain hardcoded; reuse existing name/key/address/DNS/port/endpoint/AllowedIPs/keepalive resources where they fit. |
| `layout/tunnel_detail_fragment.xml:153,290,327,538`; `tunnel_detail_peer.xml` | Topology title, download/upload, field captions and included/excluded app counts. Count and translated phrase need one existing resource/plural owner; technical identifiers and actual values remain. |
| `fragment/TunnelDetailFragment.kt:190`; `widget/TunnelThroughputView.kt:260–262` | Runtime PEER #NN title and DEVICE/ENCRYPTED/PEER Canvas labels. XML placeholder changes cannot localize the generated title; localized Canvas labels require their own glyph/width/RTL output proof. CHACHA20-POLY1305/MTU remain technical identities. |
| `databinding/BindingAdapters.kt:622–629` | NO PEER, NO PSK, address pending and endpoint pending are rendered through actual bindings. Preserve PSK identity and user/configuration addresses. |
| `provisioning/VpsDeployMapper.kt:28–29,67–68,85–91`; `fragment/VpsProvisioningFragment.kt:834` | Awaiting detect, unknown, generated Device NN/device fallbacks, server record, endpoint, recent sync, wg running and created metadata. Extend current presentation resources; do not rename persisted/server/user values or claim sync proves VPN health. |

Technical constants, examples in `tools:text`, required legal/protocol text and identical technical words are not automatically localization defects. New workflow UI uses resources; `51820` remains a technical hint. Actual output at compact/enlarged portrait/landscape, both themes, real RTL and relevant accessibility/focus/scroll paths remains required. The prior 188 synthetic originals are not current installed or complete-screen acceptance. #195 remains open for fallback, hardcoded copy, qualified per-language review and wider native/runtime criteria; #238 owns the separate whole-screen consistency work.

## Resource-backed native copy follow-up — 2026-10-17

Source candidate based on `0f2e58bad506740eb27c9beba7d44dac5edf4b8f`, with live #195 read as OPEN before editing. This bounded extraction follows the confirmed inventory above. It changes resource ownership and localized presentation; it does not establish full translation, native output, published-head or release acceptance.

`log_viewer_activity.xml` reuses `vpn0wn_filter_all`; DEBUG/INFO/WARN/ERROR retain their severity identities. Editor and Detail field captions reuse existing translated name/key/address/DNS/port/endpoint/AllowedIPs/keepalive resources and their existing Mono/all-caps styles. The editor application section reuses the neutral Applications caption because its existing selector supports both inclusion and exclusion. `BindingAdapters.applicationsLabel` resolves the existing included/excluded application plurals for the complete count-and-phrase label instead of concatenating English in XML. The small editor actions/hints, topology title and download/upload labels have EN/FR resources. MTU, PSK, section numbers, actual configuration values and tools-only examples remain technical or design text.

`TunnelDetailFragment.updateStats` formats the real peer number through `vpn0wn_peer_title`; changing only the XML placeholder would leave the runtime title hardcoded. `BindingAdapters.tunnelPeerBadge/tunnelRoute` take their presenting Context and localize missing peer/PSK/address/endpoint text while retaining present PSK and actual addresses/endpoints. Both Home and Detail route callers use that owner. `VpsDeployMapper.fromStored/fromStatus` take presenting Resources for absent detect/engine/device text and health labels. User/server names, keys, peer IDs, timestamps, unknown markers, sync/running predicates and persisted records remain unchanged. `VpsProvisioningFragment.userScreen` formats the existing device/creation metadata through one typed resource; an unknown creation time remains `—`.

Twenty-two new resource declarations have English and French values, with matching typed positional signatures. Resource XML scanning passes all 52 values files and the six affected layouts, preserves the exact leading notices and finds no unresolved affected string reference. Default main now contains 656 declarations, including 652 translatable defaults. French supplies all 652 translatable defaults; the 34 other locale qualifiers intentionally receive the 22 new keys through fallback. Across all main default/locale text files, this adds 44 declarations and 44 text nodes to the prior inventory, for 8,214 declarations and 8,861 text nodes. The prior all-language model reading and language-quality limits retain their original scope.

| Qualifier | Direct absent translatable defaults | Explicit same-language parent keys | Absent after parent |
| --- | ---: | ---: | ---: |
| `values-ar-rSA` | 431 | 0 | 431 |
| `values-ca-rES` | 431 | 0 | 431 |
| `values-cs-rCZ` | 431 | 0 | 431 |
| `values-da-rDK` | 431 | 0 | 431 |
| `values-de` | 431 | 0 | 431 |
| `values-el-rGR` | 539 | 0 | 539 |
| `values-es-rES` | 431 | 0 | 431 |
| `values-et-rEE` | 434 | 0 | 434 |
| `values-fa-rIR` | 431 | 0 | 431 |
| `values-fi-rFI` | 556 | 0 | 556 |
| `values-fr` | 0 | 0 | 0 |
| `values-hi` | 480 | 0 | 480 |
| `values-hi-rIN` | 457 | 9 | 448 |
| `values-hu-rHU` | 535 | 0 | 535 |
| `values-in` | 431 | 0 | 431 |
| `values-it` | 431 | 0 | 431 |
| `values-ja` | 431 | 0 | 431 |
| `values-ko-rKR` | 431 | 0 | 431 |
| `values-nl-rNL` | 439 | 0 | 439 |
| `values-no-rNO` | 431 | 0 | 431 |
| `values-pa-rIN` | 431 | 0 | 431 |
| `values-pl-rPL` | 431 | 0 | 431 |
| `values-pt-rBR` | 431 | 0 | 431 |
| `values-pt-rPT` | 442 | 0 | 442 |
| `values-ro-rRO` | 434 | 0 | 434 |
| `values-ru` | 431 | 0 | 431 |
| `values-si-rLK` | 457 | 0 | 457 |
| `values-sk-rSK` | 479 | 0 | 479 |
| `values-sl` | 431 | 0 | 431 |
| `values-sv-rSE` | 431 | 0 | 431 |
| `values-tr-rTR` | 431 | 0 | 431 |
| `values-uk-rUA` | 431 | 0 | 431 |
| `values-vi-rVN` | 528 | 0 | 528 |
| `values-zh-rCN` | 431 | 0 | 431 |
| `values-zh-rTW` | 431 | 0 | 431 |

`BindingAdaptersTest` adds French missing-versus-present route/PSK controls and resolves complete application labels in English, French and Arabic, including the existing Arabic implicit-count forms. `VpsDeployMapperTest` adds actual French fallback/health projection with unchanged stored names, technical values and unknown creation time. `LocalizedResourcesTest` adds real inflated French editor/Detail/Logs label/action/hint controls and typed metadata/numbering with explicit Arabic English fallback. Existing native mapper tests now supply the current presentation resources; the health-layout matrix remaps its real checks after each locale is selected. The pure store test keeps storage assertions, while absent endpoint/listener presentation remains covered by the dedicated mapper controls.

Author verdict: source corrections are satisfactory within the bounded inventory; UI/UX verification remains incomplete until focused unit/static/localization checks and affected native output are inspected. UI/UX Pro Max informed visible labels and complete action names; Interface Design and Design System informed preserving hierarchy, existing tokens/fonts, native controls and layout ownership. Frontend Design was consulted for precise, consistent copy; its direction-changing workflow is inapplicable because no visual direction is changed. No Gradle, lint, APK, device or live-server command ran during this extraction. The final PR must record actual executed checks and output/review results before acceptance.

The confirmed remaining hardcoded production text in this inventory is `TunnelThroughputView` DEVICE/ENCRYPTED/PEER Canvas labels; CHACHA20-POLY1305 and MTU remain protocol/technical identities. Canvas extraction needs its own glyph/width/RTL output proof and stays with the bounded #143/#195/#238 Canvas work. Qualified semantic review remains unperformed for every language; direct missing-key fallback outside French is 431–556, with Hindi-India reduced from 457 to 448 by its explicit Hindi parent. Wider installed, compact/enlarged portrait/landscape, both-theme, real RTL and accessibility/focus/scroll acceptance remains open. #195 remains open.
