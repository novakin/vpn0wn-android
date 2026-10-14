# Scoped rooted Settings acceptance — #34

Last updated: 2026-10-14 (Europe/Paris).

## Scope and provenance

This record adds scoped Pixel 7 Pro/API 34 Settings evidence for [#34](https://github.com/novakin/vpn0wn-android/issues/34) and [#52](https://github.com/novakin/vpn0wn-android/issues/52). The maintainer reserved the phone for sequential checks; standing APK/root-test authorization applied. Personal apps, profiles, preferences and the preexisting kernel runtime remained outside the test mutation scope.

The existing audited isolated debug APK uses source `cc0c3ea8f85cb0530991904db4a8dcd128ac5723`, application ID `local.vpn0wn.recoveryprobe.debug` and SHA256 `5ec7923b7836f1d17f0a0c1594ee4e7a990c5e4e167d66da00c2e8f0cd7f3071`. Production inputs are unchanged through master `2e81ad34778cd9af991e7201fcce06e8b434f858`; the tracked comparison from the source pin changes documentation only. Fresh installation and installed-byte checks belong to each session. No new APK assembly, shipping-package, upgrade or migration acceptance follows.

The candidate stayed empty. Before its first launch, the fresh UID received one temporary Magisk DENY row; no privileged app shell, root module, tools installation, saved profile or VPN activation was admitted. The existing ownership/census/retirement helpers preserve unrelated policies and retire only the owned denial before uninstall. Private receipts, hierarchies and images remain under ignored `.artifacts/settings-acceptance-20261014/`.

## First session: actions and normal portrait output

Session `stable-20261014T132707285249Z` used frozen `settings.py`, SHA256 `ab43f7050f862f40c8dfcd78a60ff83a48e867268900fe13f0597712ed417c5f`, after twenty host controls and independent ultra preflight. Actual native Settings rows settled under denied root. Kernel, boot, multiple-tunnel, tools and Appearance rows were absent as expected for the observed userspace/API conditions; this is filtering proof, not privileged-action acceptance.

| Control | Accepted observation | Boundary |
| --- | --- | --- |
| Remote-control preference | Unchecked to checked, persisted through actual landscape rotation and reopening; restored unchecked with byte-identical 101-byte preferences. | One reversible preference control; no remote intent execution. |
| Native tile Cancel | Inventory unchanged and setup remained available. | No tile VPN toggle. |
| Native tile Add/removal | Only the owned candidate tile was added; eligibility refreshed; exact removal restored original inventory. | Existing unrelated tiles preserved. |
| Normal English portrait | Native top/bottom output under system light/dark requests independently passed visual inspection. | Both modes retain the fixed navy app palette. |

The large-text landscape admission failed after successful font/rotation commands: all ten retained hierarchy samples remained actual rotation `0` with portrait bounds. Four enlarged/RTL cases received no acceptance from this session. There was no retained WMS/cache/raw snapshot at the failed request, so neither a product defect nor the reason for the rejected rotation is established. A command exit of zero was not counted as actual configuration proof.

## Restoration and independent review

Initial cleanup failed: owner 12/14 and composite 13/15. The recorder census rejected the valid transient process row `20589 [Shutdown thread]`; scratch/hierarchy cleanup was consequently incomplete. Its original failure remains retained. The transient exited, and unchanged reconciliation completed separately.

Independent raw-receipt review passed all fourteen owner checks, fifteen composite checks and seven additional Settings preservation fields: tools, modules, personal preference hashes, tile inventory, accessibility services/enabled state and sampled kernel-support presence. Actual default-display rotation/cache/native/raw state restored to rotation `0`, free mode and original raw settings. The stopped fresh UID's denial census/retirement, zero pending requests, stable daemon and unchanged unrelated policies were independently checked. This is sampled preservation, not a complete storage audit or the broader personal-kernel snapshot used by the motion transition owner.

The reconciliation verdict SHA256 is `5792a6b0d81b56dae008881cadf625787ae34048562cfeb67c8dbcb83547fd3e`. Original `cleanup.json`, separate `cleanup-reconciled-20261014T133545668726Z.json`, its owner counterpart and `settings-restoration-reconciled-20261014T133545668726Z.json` retain their distinct scopes. Two portrait cases independently pass; no failed or missing matrix case is relabeled as passing.

## Four-case follow-up: installer admission failure

Fresh session `stable-20261014T135426723640Z` used a separately reviewed four-case wrapper, SHA256 `cf5912ee511badea263569abb6dbf886cd8c46cd0a09622fe46102235a8574f5`. Seven focused host controls replayed the retained rotation failure and required actual default-display/cache/native/raw agreement before and after output, alongside hierarchy/configuration/PNG checks. The original Settings and transition owners remained frozen; successful switch/tile and normal portrait controls were not replayed.

This attempt stopped at `adb install --no-streaming` after its 90-second deadline, before candidate launch, denial admission or UI testing. The command's remote outcome was recorded as unknown. Sampled cleanup and the seven extra Settings fields passed, but Android still retained active installer session `1632069400`; package removal alone did not establish installer settlement.

Later native logs record Android backup-restore completion followed by this session's `INSTALL_FAILED_PACKAGE_CHANGED` result: the package had been removed before post-install handling completed. Completion occurred about 4 minutes 26 seconds after session creation. Two later package-manager snapshots place it in history with destroyed state and final status `-23`, with no active/finalized session. Independent review recomputed fourteen composite checks, thirteen owner checks and seven extra preservation fields; terminal installer settlement is a separate gate from those sampled checks. The earlier delay's precise cause remains unproved; the observed backup-completion sequence is not a diagnosed app failure. No reboot, PMS reset or global backup-policy change occurred. This failed attempt supplies no additional matrix acceptance.

## Four-case follow-up: native rotation rejection

Session `stable-20261014T141118862684Z` used a separately copied wrapper, SHA256 `3e54ad3673661e931dc0f980d35638c7ddf7e3e608fb19916dcf56af4a3a68d0`. Its only installation change extended the exact owned non-streaming call's deadline from 90 to 360 seconds, following the observed late completion. Ten focused host controls and independent ultra preflight verified the bounded delta; it introduced no retry, sleep, backup-policy or APK change. Installation returned success in 3.219 seconds, and installed bytes/identity matched the pinned artifact.

The first landscape case again failed admission. Command `0072` requested `lock 1` successfully; the first native observation had locked rotation `1` in native/raw/cache state, while actual display rotation remained `0`. All twenty-one retained display observations remained actual `0`, and later native/raw/cache state returned to locked `0`. These native/raw/cache/display reads are sequential samples, not an atomic snapshot. No owned rotation setter reversed the target before failure diagnosis; the next owned `lock 0` belonged to cleanup. The reason remains unproved, and no remaining output case passed.

The same valid multiword-process parser defect interrupted scratch/hierarchy cleanup: owner 12/14 and composite 13/15. Original failure remains retained. Unchanged reconciliation at `20261014T141341127398Z` independently passed fourteen owner checks, fifteen composite checks and all seven extra Settings fields, including exact denial retirement and stable unrelated policies. The separate reconciliation verdict SHA256 is `d00c64ec3bbe57c7f2fd18d1f1f57f670b4d373053a0048fc26f9736fe109524`.

## Ordered follow-up: accepted English 2× dark landscape

Session `stable-20261014T143637492483Z` used separately reviewed ordered wrapper `67c363dc16ba4252fc8d74fb99fcec095f615a5b52dfd87452f1af561968659d`. Sixteen focused host controls and a composed-factory check verified the final preparation. Its setup followed the existing admitted case order and required actual default-display/cache/native/raw agreement before and after output. The separate recorder copy corrected only the multiword process-row split; existing ownership, finite-wait and cleanup guards remained. Installation returned success in 3.25 seconds, and installed bytes matched the pinned APK.

The first case admitted English, font scale `2.0`, system night mode and actual landscape output at 2340×1080. Actual/default, cached, native and raw rotation agreed at `1` with locked mode. Independent original-pixel inspection accepted the complete header/Back, supported row titles and summaries, wrapping and scroll reachability without observed product overlap or clipping. Clean top/final images replace captures obscured by transient host root toasts; the toasts establish no candidate root authority. The fixed navy palette remains. This is presentation/scroll proof, not execution of the displayed export/import or privileged actions.

The next Arabic 2× light portrait case failed admission: requested rotation `0` did not replace actual/native/cache/raw landscape `1`. These sequential readbacks establish the rejected configuration, not an atomic snapshot or its cause. No new RTL output was accepted. Raw independent review passed fourteen owner checks, fifteen composite checks and seven additional Settings preservation fields, including restored actual/default/cache/native/raw rotation, exact denial retirement and unchanged unrelated policies. HTTP/holder-process absence is vacuous because no such process launched; this session retains no broader per-interface personal-kernel snapshot.

Separate retained verdicts are `.artifacts/yellow-motion-analysis-20261014-ultra/settings/settings-ordered-runtime-independent-verdict.json`, SHA256 `6b3e933ff90e871ff1f1eaccee7d3538e9dcf64ad1352ee136b2c5aca6674f4a`, and `visual-landscape-20261014T143637492483Z.json`, SHA256 `0d162073a836ee530e141545f26148db28945d76ab08642381d05a9bd63f751b`. The raw verdict's visual-pending checkpoint is resolved by the separate visual review without rewriting frozen evidence. Together with the first session's two normal portrait cases, this establishes **3/6 accepted configurations**; the earlier failed admissions and reconciliations retain their original scope.

## Singleton RTL follow-up: 360-second installer timeout

Session `stable-20261014T144913785332Z` selected only Arabic 2× light portrait through separately reviewed wrapper `17087b3549c0c04b3a6ce1af5cbac7f10f4e4ed97f5dad3b35528682ce2cd2d0`. Twenty focused host controls verified the singleton selection/pin delta and retained ordered setup, ownership and output/restoration gates. No earlier accepted case or switch/tile action was replayed.

The exact owned `adb install --no-streaming` call timed out after 360 seconds. Its remote outcome remained unknown; installed UID/base-APK identity, temporary DENY admission, explicit app launch and UI output were never admitted. Independent retained-evidence review passed fourteen composite checks, thirteen owner checks and all seven extra Settings preservation fields. Those sampled comparisons do not establish installer settlement, and this attempt supplies no new output acceptance.

Installer session `1000676129` was matched to the owned interval, shell UID 2000, user 0, unique active session and exact APK size 25,975,494 bytes. Its package name was unresolved. The exact owned abandon request returned `Success`, but the retained after-abandon dump still listed it active and destroyed, with `stageDirInUse=true` and final status `0`. At this checkpoint terminal settlement remained unverified; package absence and accepted abandon alone did not admit a blind retry. No reboot, PMS reset or global backup-policy change occurred. The delay's cause remains unproved. The pre-abandon independent receipt `settings-case-install-timeout-review.json`, SHA256 `114fa8220f51a58ab18c9239d8bab8cf1e299541154ec15666bc4843da20dae0`, retains its original HOLD; later terminal evidence is separate below.

## Installer terminal settlement — 2026-10-14

Later native evidence places the exact owned session only in history: destroyed, `stageDirInUse=false`, final status `-115` and message `Session was abandoned`. No active/finalized session remains; separate readbacks establish owned stage and package absence. Independent review `.artifacts/yellow-motion-analysis-20261014-ultra/installer/installer-verification-terminal-review.json`, SHA256 `eef9ef8f62c9d21e2d04b221601a47544811ad2350aee75cdc02b302637ddb79`, PASSes this terminal boundary and supersedes the retry HOLD. It reviews one terminal package-manager snapshot plus separate stage/package readbacks, not two terminal snapshots.

The original timeout, interim HOLD, accepted abandon and sampled cleanup receipts remain unchanged. Completed abandonment supplies no successful installation, new Settings output or diagnosed delay cause. No reboot or global policy change resolved this gate. Fresh singleton execution was re-admitted under existing authorization with a new baseline and unchanged installation, DENY, output and cleanup guards.

## Fresh RTL entry rejection — 2026-10-14

Session `stable-20261014T154131721549Z` reused the reviewed singleton wrapper `17087b3549c0c04b3a6ce1af5cbac7f10f4e4ed97f5dad3b35528682ce2cd2d0`. Installation and installed-byte/UID admission passed. The native launch reported `Status: ok` and 602ms, but the first retained hierarchy belonged to Launcher3 and contained no owned Home Settings target. This was rejected before Settings output; no new matrix case or product-crash cause was established.

Initial cleanup retains owner 13/14 and composite 14/15: only original foreground restoration failed. Direct reopening of its private, non-exported Activity returned a security rejection with exit `255`; all other sampled comparison fields passed. Separate focus of the existing original task restored exact native foreground readback. That checkpoint does not establish its PID, recreation or a new independent complete cleanup verdict. No seven-extra-field Settings preservation receipt was produced for this attempt. Original failed receipts remain intact.

A separate host-only Home-admission copy later PASSed independent ultra preflight with 24 injected controls. It requires an owned Home hierarchy before traversal; it changes validation preparation, with no product repair or fresh RTL runtime acceptance established by that host verdict.

## Singleton RTL runtime and restoration — 2026-10-14

Three fresh singleton sessions used the separately frozen Home-admission copy `settings_scoped_home.py`, SHA256 `951339e2f6f07311887c0b10dd0d043b506d6d33dc8b502586ec4138044375bd`. Its bounded entry guard requires the exact owned Main foreground and a unique enabled, clickable Home Settings target before traversal. Independent host preflight, SHA256 `6e8fe1fa22726852aaa20b67a30f42ec61ac14db077af3651e8358a85178d8f2`, covered 24 injected controls; existing installation, DENY, output and cleanup owners stayed unchanged. Earlier successful switch/tile actions and accepted English cases were not replayed.

| Session | Admitted native output | Initial cleanup | Separate reconciliation |
| --- | --- | --- | --- |
| `stable-20261014T161446171494Z` | Arabic, font scale `2.0`, system light request, portrait 1080×2340; actual/default/cache/native/raw rotation `0` agreed. | PASS: composite 15/15, owner 14/14 and seven extra Settings fields. | Not needed. |
| `stable-20261014T162016723512Z` | Arabic, font scale `2.0`, system dark request, portrait 1080×2340; actual/default/cache/native/raw rotation `0` agreed. | PASS: composite 15/15, owner 14/14 and seven extra Settings fields. | Not needed. |
| `stable-20261014T162756301504Z` | Arabic, font scale `2.0`, system dark request, landscape 2340×1080; actual/default/cache/native/raw rotation `1` agreed. | FAIL: composite 10/15 and owner 10/14. Native/raw free rotation did not settle; scratch/hierarchy removal was withheld. No original seven-extra-field receipt exists. | PASS: composite 15/15, owner 14/14 and seven extra Settings fields. |

Between the successful dark portrait and landscape sessions, `stable-20261014T162254320027Z` failed at receipt `0077` with ADB exit `1` during Windows ADB daemon/transport startup. No output matrix was admitted. Initial cleanup independently passed composite 15/15, owner 14/14 and seven extra Settings fields. The host transport failure establishes no app or rotation cause and supplies no visual acceptance.

Landscape reconciliation used separate `cleanup-reconciled-20261014T163551993722Z.json`, `owner-cleanup-reconciled-20261014T163551993722Z.json` and `settings-preservation-reconciled-20261014T163551993722Z.json`. It restored actual/default rotation `0`, native/cache free mode, cached/raw rotation `0` and automatic rotation `1`. Its independent verdict SHA256 is `bb99d1526fd5c615f80ff6b1659d67344c6d152bd8df4aa814518d8683f57811`. The original failure remains unchanged; later restoration does not relabel it as a first-pass success.

Final independent raw review `.artifacts/yellow-motion-analysis-20261014-ultra/settings/settings-rtl-final-raw-independent-verdict.json`, SHA256 `0534333ff591d60a2ec82e9bf3fd57b39f6234f195d22d0a34a2df473b1ad0fd`, accepts all three RTL runtime configurations and final sampled preservation. Each successful installation matched the pinned artifact and received exact fresh-UID DENY before launch; denial retirement, zero pending requests and unchanged unrelated policies were checked. No candidate-granted root or VPN activation was admitted. HTTP/holder absence is vacuous, and no broader personal-kernel per-interface snapshot or complete storage audit was captured.

## RTL original-pixel review: capture gaps — 2026-10-14

Independent review of all 30 native PNGs at original dimensions retained **HOLD** for complete visual acceptance at this checkpoint. `.artifacts/yellow-motion-analysis-20261014-ultra/settings/rtl-native-visual-verdict.json`, SHA256 `a18a8eefe9b877a1d87dbac12ac6d705c6349f95238ee9836d7faee8c81f6493`, separates the three capture gaps from demonstrated product defects:

- Both portrait system-mode requests lack a complete unobscured ZIP tunnel-export summary. Top captures retain its beginning; bottom captures retain its tail. Natural viewport clipping leaves full wrapping unverified.
- Landscape positions the complete Quick Settings setup title/summary only in a toast-obscured capture. No clean later raster revisits that row.

Other inspected complete rows, header/Back, RTL placement, wrapping and bottom reachability pass their visible scope. All 27 paired raw/owned XML copies match and correspond to the visible text/bounds; runtime provenance and restoration belong to the separate raw review. Reviewed complete content shows no internal overlap, horizontal clipping or ellipsis. The fixed navy palette remains under both system-mode requests. The narrow light-portrait follow-up below resolves one gap without replaying successful actions or whole matrices.

## Light portrait gap resolution — 2026-10-14

The first pixel-gap candidate was held by independent ultra preflight: English exact-text selectors could not find the requested Arabic native rows. That candidate was never executed. The separately frozen corrected runner uses the reviewed Arabic title/summary pairs; source and retained-native host controls reject wrong-language selection. Its SHA256 is `6b53720d2732674eb1d85d674df7ba7d8b829810dcc2537b500779bc3f7c5b65`. Independent corrected-candidate preflight `.artifacts/yellow-motion-analysis-20261014-ultra/settings/settings-pixel-gap-ar-preflight-verdict.json`, SHA256 `0d5eb0456b335a1ebd11a6d25271a15889338e061522cd9e8d48a22c1e0426ef`, PASSed 32 controls. Only target matching changed; existing ownership, capture and cleanup behavior remained.

Fresh session `stable-20261014T171045185200Z` admitted the pinned isolated APK, checked installed bytes/UID and set fresh-UID DENY before launch. Arabic, font scale `2.0`, rotation `0`, system night `no` and 1080×2340 output agreed before/after captures. `large-rtl-portrait-light-gap-2-private.png` shows the complete ZIP export title and summary without toast, overlap, clipping or ellipsis. The three native PNGs were inspected at original dimensions; the complete target row stays inside the scrolling viewport. Partial unrelated content at viewport edges establishes no target-row defect.

Independent visual record `.artifacts/yellow-motion-analysis-20261014-ultra/settings/rtl-portrait-light-gap-visual-verdict.json`, SHA256 `7f487eb57c66383794e0ae0d1ad22039b49e772a4f3a9d05a71a1afea4472cc7`, PASSes the missing row and its combination with prior light-portrait visual scope. Source/APK/configuration provenance matches; separate sessions and installation UIDs remain distinct. The original three-gap HOLD retains its original scope. This resolves complete light-portrait visual acceptance only; dark portrait and landscape gaps remain.

## Light gap cleanup failures and separate observations — 2026-10-14

Both actual cleanup operations remain **FAIL**, despite matched sampled postconditions:

| Operation | Retained failure | Independently recomputed sampled postconditions |
| --- | --- | --- |
| Original `cleanup.json` / `owner-cleanup.json` | Foreground restoration attempted direct start of a private Recents Activity; receipt `0403` returned exit `255`. | Composite 15/15 and owner 14/14 matched; the operation still reports `restored=false`. |
| Separate `cleanup-reconciled-20261014T172201231504Z.json` / owner counterpart | With foreground matching, scratch cleanup reread an already removed `.owner`; receipt `0455` returned exit `1`. | Composite 15/15 and owner 14/14 matched; the operation still reports `restored=false`. |

The final independent raw record `.artifacts/yellow-motion-analysis-20261014-ultra/settings/gap-light-final-runtime-preservation-verdict.json`, SHA256 `bef85c3e634d3effd9380982c7712c151cae5ddf1d53977f543c8a7e5275014a`, accepts light-gap capture provenance and separately timed observations. Exact denial retirement, zero pending requests, sampled unrelated policies and owned-resource absence were checked. This does not relabel either cleanup failure or establish continuous full restoration/current-baseline equality.

After the maintainer authorized wireless ADB and real-app Main navigation, separate read-only receipt `settings-preservation-wireless-20261014T174329515261Z.json` independently PASSed seven fields: tools, modules, personal preferences, tiles, accessibility services/enabled state and sampled kernel-support presence. These later seven reads are distinct from the earlier 15/15 and 14/14 observations. Storage/kernel sampling is incomplete; HTTP/holder absence remains vacuous, and no personal-kernel per-interface snapshot was captured.

Native TLS wireless ADB was verified against the same reserved hardware under current-network authorization; the retained approval left Always allow unchecked. USB cable release and subsequent real vpn0wn Main foreground were authorized context changes. The first failed dozing/no-top Main producer's raw paths were overwritten before freezing evidence; only the later successful native Main snapshot is retained and accepted. No earlier failed raw snapshot, unchanged/recreated Activity or continuous foreground restoration is claimed. Private network/device identifiers and raw reports remain ignored.

## Later dark entry rejection and installation timeout — 2026-10-14

Dark-portrait gap attempt `stable-20261014T175121206222Z` stopped before baseline capture or installation: Dozing/AOD supplied no unique foreground. It establishes no dark output acceptance or product cause. For subsequent reserved-phone checks, user 0's `screen_off_timeout` was temporarily extended from retained original `30000` to `900000`, with the original saved in ignored `.artifacts/wireless-adb-20261014/screen-timeout-before-private.json`.

Fresh dark-gap attempt `stable-20261014T175733236350Z` timed out at the exact owned `adb install --no-streaming` call's 360-second deadline, receipt `0034`. The host command joined, but its remote outcome remained unknown. No candidate launch, fresh-UID root DENY or dark pixels were admitted. Original `cleanup.json` and `owner-cleanup.json` remain **FAIL** with `restored=false`: composite 13/14 and owner 12/13 matched, while the late-installed package violated package absence. These sampled fields do not relabel the failed operation or settle the installer.

Separate guarded late removal checked the stopped, never-launched package's UID and pinned APK identity before removing only that installation. `late-install-removal-20261014T180810750004Z.json` records its removal; `settings-preservation-late-install-20261014T180810750004Z.json` separately matches all seven extra Settings fields. Neither receipt supplies new dark output or changes the original cleanup status.

Independent review `.artifacts/yellow-motion-analysis-20261014-ultra/settings/dark-late-install-terminal-independent-verdict.json`, SHA256 `48a9075ba1ce75c8073fdcf88c920ee53ef10b1ec4c314e7655968f252fc256b`, PASSes guarded removal, seven-field preservation and separate terminal settlement. Native snapshot `20261014T182742327817Z` has empty active/finalized installer sections, no live target-package block and empty target package/path readbacks. Historical session `381590786` is destroyed, applied and final status `1`, while historical `stageInUse=true` remains retained. That flag establishes neither an active session nor stage-path absence. Terminal stdout/stderr snapshots lack per-call exit-code receipts; complete native package-manager sections corroborate target absence. The install-delay cause remains unproved, and the original timeout/cleanup failure remains unchanged.

## Temporary timeout restoration — 2026-10-14

Separate native readback `.artifacts/wireless-adb-20261014/screen-timeout-restoration-20261014T183121806Z-private.json` verifies user 0's original `screen_off_timeout` presence and value `30000` milliseconds after restoring the temporary `900000` value. The terminal review above independently matches the recorded readback to the saved original; wireless debugging was preserved under the authorized wireless test context. This restores one named setting; it does not establish continuous full restoration or replace earlier cleanup evidence. No dark portrait or landscape gap was resolved at this checkpoint.

## Paired dark follow-up: landscape accepted, portrait held — 2026-10-14

Fresh wireless session `stable-20261014T183954037831Z` used the same pinned APK and separately reviewed paired wrapper. Independent raw/original-pixel verdict `.artifacts/yellow-motion-analysis-20261014-ultra/settings/settings-dark-pair-runtime-stable-20261014T183954037831Z-verdict.json`, SHA256 `8da402dfec71fa04a309854d8a9be2b2d15b652b7657078319e95d16d05092ac`, accepts landscape's missing Arabic Quick Settings setup row. All six original PNGs were inspected; decisive `large-rtl-landscape-dark-gap-9-private.png` shows the complete unobscured title, summary and row with bottom clearance. Arabic, font scale `2.0`, system night `yes`, 2340×1080 and actual/default/cache/native/raw rotation `1` agree before/after output. This resolves the prior landscape capture gap without replaying tile actions.

Portrait captured zero gap PNGs and retained **HOLD**: all ten native observations contained real Toast windows and three to five queued host-shell superuser toasts. This is an actual obscured-output admission boundary, not a demonstrated product-layout defect or an empty-toast-header false positive. Toast text does not establish candidate root authority or the queue's replenishment cause. The pair did not pass as a whole. Independent restoration passed composite 15/15, owner 14/14, all seven extra Settings fields and exact original timeout `30000` milliseconds. Those results cover sampled recorded surfaces; HTTP/holder absence is vacuous and supplies no all-process census.

## Dark portrait singleton follow-up — 2026-10-14

A separate singleton wrapper, SHA256 `2b388566577e360952d81c4494278404af811e796995ba39d7f818e3ff2a4e86`, narrows to dark portrait and increases only the bounded native toast observation count to 40, with scoped wording. Independent host preflight `.artifacts/yellow-motion-analysis-20261014-ultra/settings/dark-portrait-retry-offline-preflight-verdict.json`, SHA256 `8d94c0ab135e053ad238a1028a991ea5e4aa6a28289ca9537594eb6f1b5b3e9f`, PASSed twelve mocks. Existing ownership, source/artifact pins, command timeouts, cleanup and exact timeout restoration remain; exhausting the observation count still holds acceptance. The wrapper adds no queue, notification or policy mutation.

Fresh native session `stable-20261014T185120764859Z` completed with exit `0` and retained three original dark-portrait gap PNGs. Independent raw/original-pixel record `.artifacts/yellow-motion-analysis-20261014-ultra/settings/dark-portrait-singleton-final-independent-verdict.json`, SHA256 `a2a87f89063813aabd4c4b7684f99a5cea8552013b0505029c0aa48d187c8a9a`, PASSes the complete unobscured Arabic ZIP title/summary in `large-rtl-portrait-dark-gap-2-private.png`. All three original 1080×2340 PNGs were inspected; raw PNG/XML provenance, Arabic font scale `2.0`, system night `yes` and actual rotation `0` match. Matching source/APK/configuration tuples combine this missing-row proof with prior accepted dark-portrait output while retaining distinct sessions and installation UIDs.

The same independent record verifies exact installed APK/UID, prelaunch DENY and retirement, composite cleanup 15/15, owner 14/14 and seven extra fields. Outer raw timeout reads retain `30000` → `900000` → `30000` milliseconds with original presence preserved. The prior paired portrait HOLD remains unchanged. This completes its missing-row visual proof and sampled preservation only; it establishes no preference/action, granted-root, TalkBack, complete storage or continuous health acceptance.

## Remaining acceptance

| Physical configuration | Current acceptance |
| --- | --- |
| English 1× portrait, system light | Accepted in the first session. |
| English 1× portrait, system dark | Accepted in the first session. |
| English 2× landscape, system dark | Accepted by separate raw and visual reviews of the ordered session. |
| Arabic 2× portrait, system light | Complete visual acceptance PASS from separate matching sessions. Light-gap raw provenance and separately timed sampled preservation accepted; both cleanup operations remain FAIL. |
| Arabic 2× portrait, system dark | Complete visual acceptance PASS from prior output and the separately accepted singleton ZIP gap; singleton restoration PASS within sampled scope. |
| Arabic 2× landscape, system dark | Complete visual acceptance PASS from prior output and the separately accepted paired gap; paired restoration PASS within sampled scope. |

Independent case-specific records now establish **6/6 selected physical visual configurations** across distinct sessions on matching source/APK/configuration tuples. Both light-gap cleanup failures, the later dark installation/cleanup failure, the paired portrait HOLD and separately scoped preservation/restoration remain explicit above. Native hierarchy labels, roles and checkable state do not establish spoken TalkBack traversal/actions/announcements, translation or conditional Android/admin/OEM acceptance. Root-granted backend switching, multiple tunnels, tools actions and actual boot restoration remain separate. Tile membership does not prove tile VPN activation. Physical authentication/export/provider/write/cancellation acceptance belongs to the corresponding action owners.

Settings source and policy behavior are unchanged. [QA](../qa-test-plan.md#remaining-g4-device-acceptance) owns remaining checks; [the roadmap](../ui-redesign-roadmap.md#pending) retains incomplete acceptance. Neither #34 nor #52 closes from this scoped record. No performance, protocol, release or whole-E2 acceptance follows.
