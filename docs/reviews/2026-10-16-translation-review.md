# Translation source inventory and semantic review — #195

Last updated: 2026-10-16 (Europe/Paris).

## Verdict and scope

The mechanical inventory and model semantic reading pass cover default English and all **35 locale qualifiers**, totaling **7,982 explicit resource declarations and 8,595 text nodes**. One text node is one string value or one declared plural item; inherited/default fallback is not counted as translated local text. This is a bounded source translation review for [#195](https://github.com/novakin/vpn0wn-android/issues/195), not the separately requested post-merge whole-codebase/security audit.

**Preliminary author satisfaction: satisfied with the recorded source-reading coverage and confirmed-defect triage. Actual output verification is pending; full #195 delivery is not accepted.** No qualified native speaker certified these languages. Model confidence in literal technical/caller-backed defects does not certify grammar, idiom, preferred terminology or completeness. Tests, Android lint/resource compilation, original native output, accessibility and final-head independent acceptance remain separate.

The reviews began at `ee1cf84155ee752c462fed5452d3e4bc94c9e81a` with master `7ae1e3e8155d10be4af5974cdf1eb83eae0600b3` being integrated. The signed merge `60281d87e08271cbd5090af03d5d0e6e8efd2b19` is the later translation integration base. Review hashes below pin the actual pre-repair inputs; they are not a claim that subsequent edited files or a newer default branch were reviewed. At this checkpoint, the owner has prepared confirmed main/delegated corrections in uncommitted resources, including the Catalan singular diagnostic and Finnish key-length grammar follow-ups. Exact final edit totals await settled source readback. No final corrected-source revision or passing runtime result is claimed here.

## Mechanical inventory

The source inventory scanned every `res/values*/*.xml` under the `ui` and `tunnel` source sets. Definitions exist in UI main/debug; tunnel defines no strings/plurals/arrays. All 52 values XML files parsed, including 37 string/plural-bearing files. All 37 retain the exact leading fork modification notice; no duplicate declarations, locale-only extras, absent plural `other`, unresolved whole-value local resource reference or raw-percent anomaly was found in that input snapshot. XML/source scanning is not AAPT compilation or Android formatting.

Default main contains 540 declarations: 523 strings and 17 plurals, including 536 translatable and four intentionally nontranslatable values. Debug adds one nontranslatable `app_name` override and belongs to the mechanical inventory; the semantic totals above cover the 36 main default/locale files. The intentional main exclusions are `app_name`, `vpn0wn_home_wordmark`, `vpn0wn_peer_rx_label` and `vpn0wn_peer_tx_label`, without translated overrides.

French supplies all 536 translatable declarations in the reviewed snapshot. The other qualifiers directly omit **315–440** translatable default keys and therefore retain substantial fallback. Hindi-India inherits nine additional definitions from Hindi; 332 keys remain absent after that explicit parent. The inventory records same-language absence separately and does not simulate sibling-region Android resolution. A locale folder, an identical-default value or English fallback is not evidence of a complete translation.

Formatter comparison identified one non-count contract defect: Catalan `delete_error[one]` omits both the count and diagnostic argument. No current production plural caller was found, so this is retained-resource drift, not a reproduced deletion-screen failure. Ten other signature differences concern plural-count presence only; legitimate Arabic dual forms must not be rewritten mechanically. The inventory also records 2,613 formatter-bearing leaves, 262 leaves with backslashes, 32 with XML-decoded angle brackets, and no child markup or replacement character. These source observations require final edited-resource and platform checks before acceptance.

The original mechanical evidence remains in ignored `translation-inventory/inventory-baseline.json` and `locale-summary-baseline.csv`. The initial source inspection and narrow technical contract review ran no Gradle, lint, AAPT, JVM/render, APK/device or live operation. Later file drift is explicitly recorded rather than replacing the original input hashes. Regenerate the mechanical inventory after all repairs settle.

## Confirmed defect groups and current resolution

The main report's 22 finding groups and delegated findings produced narrow source-text corrections prepared by the owner. This records source preparation, not tested or delivered behavior. Existing keys, placeholders, plural ownership, protocol identifiers and runtime policy remain the contract.

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

The six delegated qualifiers add separate caller-backed findings: Arabic remote-control recommendation polarity, last-used tile semantics, key-access overstatement, error joining and Go identity; Persian `wg-quick`/Go identity, empty-TV Add guidance, config grammar and tile/toggle meaning; Hindi/Hindi-India section/endpoint terminology, UP/DOWN direction, partial-import counts, remote-control/permission meaning and technical installer literals; Punjabi section/keepalive/Go wording; Sinhala IP-versus-key meaning, executable/backend identity and networking/programming nouns. Their pre-repair hashes and complete reading counts are included below. The owner has applied the accepted main/delegated proposals; corrected-source verification and final review remain pending. Model-proposed wording is not native editorial approval.

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

The corrected-resource mechanical rerun preserves 540 default declarations and 35 qualifiers, with zero XML, duplicate, extra-key, missing-`other`, leading-notice, local-reference or non-count formatter defects. Ten plural-count-presence variations remain explicit. The original failed Catalan diagnostic signature is repaired; this rerun does not replace the pre-repair hashes or confer linguistic certification.

Independent ultra review identified 30 further positive-only keepalive explanations after the first semantic corrections. These now use the language-neutral inclusive range `0–65535`; the error context already identifies `PersistentKeepalive`. Arabic/Persian wrap that range with explicit LTR isolates through Android Unicode escapes. Existing correct localized explanations retain their disabled-zero guidance. An all-35-locale resource regression checks both bounds and the two RTL isolates; actual increasing layout order remains a native-output gate. This avoids uncertain bulk prose while keeping the supported numeric contract. The expanded class has eleven controls; final execution remains pending at this checkpoint.

Concrete remaining hardcoded copy stays under #195: editor actions and section headings in `tunnel_editor_peer.xml`/`tunnel_editor_fragment.xml`; topology and application-routing labels in `tunnel_detail_fragment.xml`, `TunnelDetailFragment.kt`, `TunnelThroughputView.kt` and `BindingAdapters.kt`; generated labels/statuses in `VpsDeployMapper.kt` and `VpsProvisioningFragment.kt`; and the Logs ALL filter in `log_viewer_activity.xml`. Protocol identifiers, user-supplied values and deliberate log severity codes are separate from these app-owned labels. This source slice does not claim that every visible app string is localized.

Preparing the actual MTU error probe also found a distinct parser diagnostic: negative MTU uses `Location.LISTEN_PORT`. [#206](https://github.com/novakin/vpn0wn-android/issues/206) owns that unfixed source defect. The translation probe uses overflowing integer input, whose existing parser identifies MTU correctly; it cannot establish negative-MTU correctness.

Local ignored evidence is retained under `.artifacts/continuation-20261016/translation-semantics/` and `translation-inventory/`: main `semantic-review.md`, `coverage.md`, `coverage.json` and per-node `resource-coverage.json`; mechanical `summary.md`/`contract-review.md`; delegated `semantics-ar-fa.md`, `semantics-hi.md`, `semantics-pa-si.md` and `semantics-six-coverage.json`. This maintained record summarizes findings and preserves input identity; raw local reports are not committed as a transcript.

The original six API-28 `LocalizedResourcesTest` controls and subsequent focused additions are regressions, not an exhaustive locale/placeholder/semantic gate. The owner will record actual corrected-source tests, lint/resource checks and output separately in [translation QA](../qa-test-plan.md#translation-resource-contracts--195). None ran for this documentation/source aggregation. Current lint warning settings and ordinary CI do not establish translation coverage.

UI review must retain the approved Inter Tight/JetBrains Mono roles, palette, native Material controls and Fragment/DataBinding ownership. Inspect actual localized text at supported phone/tablet sizes, portrait/landscape, normal/enlarged text and both themes, asserting actual locale and resolved RTL direction. Check complete warnings/errors/actions, bidi formatting, glyph/fallback coverage, native scrolling, 48dp targets and focus/inset reachability. JVM/manual native-view output still does not establish installed-device, IME, spoken TalkBack or real authentication behavior.

[#195](https://github.com/novakin/vpn0wn-android/issues/195) remains open for substantial missing translations/fallback, qualified per-language review, correction settlement, final source checks/review and applicable native output/runtime acceptance. No locale receives a perfect-quality, certified, release-ready or complete-localization verdict from this source pass.
