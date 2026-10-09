# G4 isolated native acceptance follow-up

Last updated: 2026-10-09 (Europe/Paris).

## Scope and provenance

Partial native Android framework evidence for [#28 Downloads](https://github.com/novakin/wireguard-android/issues/28), [#34 Settings](https://github.com/novakin/wireguard-android/issues/34), [#35 Add/peer](https://github.com/novakin/wireguard-android/issues/35) and [#36 Home](https://github.com/novakin/wireguard-android/issues/36), under [G4/#52](https://github.com/novakin/wireguard-android/issues/52). This isolated emulator run does not replace physical acceptance or complete any of these issues.

The checkout was clean at `b1eb7c47be143090ce60ad1f23eba09057296334`. A fresh private headless AVD ran Android 16/API 36, Google APIs x86_64 system image revision 7, with the Pixel 8 profile and 1080×2400 output at density 420. Every device command targeted `emulator-5582`; the existing visible `emulator-5580` and physical Pixel received no commands. The shared ADB server remained intact.

The private AVD initially had no debug package. It received the existing authorized APK from source `f4a4b147568e6aaa3c82a4307aa6690977e966bd`, SHA256 `5bcb432c58d88bf6a7f3d8ac9891566a047061182addd8524bec6ae9502a71bb`; [the earlier provenance record](2026-10-08-g4-ui-cpu-baselines.md#build-and-installation-provenance) owns its build/signing/install history. No APK was assembled, and no tracked source, network, VPN/backend, server or energy test was performed. Synthetic profiles had no Endpoint, ListenPort or default routes and stayed DOWN.

Screenshots and current hierarchy captures established the recorded native controls and settled output in the app's dark palette, with portrait/landscape recreation where stated. This is API 36 emulator output, not an expanded physical size/theme/font/RTL matrix. Raw captures, app archives and reports remain local under ignored `.artifacts/g4-private-native-20261009/`.

## Observations and acceptance limits

| Surface | Observed result | Boundary still open |
| --- | --- | --- |
| Camera permission, #35 | Deny left CAMERA ungranted and created no tunnel. The scanner displayed misleading camera-failure/restart advice. After dismissal, retry requested permission again; Grant opened the scanner, and Back returned to Home. | The denial feedback is an unresolved finding below. Scanner opening does not establish QR lens decoding, physical camera recovery or camera absence. |
| Quick tile, #34 | Initial native inventory lacked the app tile. Cancel preserved inventory and the Add row. Retry/Add installed exactly one tile and removed the row. Later Settings reopening offered Add again while inventory still contained the tile; choosing it hid the row without another native add dialog. | Stale row eligibility is an unresolved finding below. The exact service callback/process sequence was not recorded. Tile VPN toggling and physical/process-restart acceptance were not exercised. |
| Settings switch, #34 | Remote control was enabled, survived landscape recreation and portrait return/reopening, then restored OFF. A repeated ON/reopen/OFF sequence also retained the selected value. | Other controls, conditional API/admin/root/kernel paths, TalkBack and remaining physical output configurations. |
| Mixed ZIP import, #35 | Four entries contained two valid synthetic profiles, one invalid config and one duplicate. Exactly `g4avd9-alpha` and `g4avd9-bravo` imported DOWN, with “Imported 2 of 4 tunnels” feedback. Stored configs supplied the canonical comparison. | Other providers, unreadable streams and broader failure/recovery acceptance. Earlier image-QR and keyboard checks retain their original physical scope. |
| Downloads export, #28 | Empty inventory produced no output. Two native exports each contained exactly both stored canonical `.conf` entries, with valid CRCs, UTF-8 and byte equality to stored configs. The second replaced MediaStore row 19 with row 20, leaving one completed app-owned 648-byte output; shell-owned fixture row 18 remained separate. | Authentication Cancel, export cancellation and production provider acquisition/write/failure cleanup remain unproved. This is API 29+ MediaStore success/overwrite evidence, not API 28 filesystem permission proof. |
| Home selection/delete, #36 | Settled captures showed one selected, zero selected with Delete absent, and two selected. Landscape/portrait recreation retained both identities/count. Confirmation listed exactly both synthetic names. Cancel preserved both canonical stored config bytes; later confirmed deletion removed exactly those profiles, leaving no `.conf` files. | Rename/insertion and admitted-operation cancellation/partial failures retain source controls, not new device proof. TalkBack and physical gesture/system-bar acceptance remain open. |
| DOWN detail/peer, #35 | Settled output used dashes for unavailable speed, ListenPort and MTU, pending endpoint text and one peer's allowed IPs including IPv6. No live counters were fabricated. | Clipboard actions, independent live RX/TX equality, missing-row transitions and physical/accessibility acceptance. |

The two ZIP-container hashes differ; canonical entry bytes match in both archives. The cause of container-byte differences was not inspected. Container byte equality is not required for content preservation. Manual hash-confirmed output cleanup below does not establish the application's production cancellation/failure cleanup.

## Unresolved findings

[Camera-denial feedback (#84)](https://github.com/novakin/wireguard-android/issues/84): CAMERA denial displayed “Sorry, the Android camera encountered a problem. You may need to restart the device.” The denied permission was the observed cause; later granting it opened the scanner. Feedback should identify missing access or cancel the scan without camera-malfunction/restart advice. The existing ZXing missing-permission path supplies that generic message; no source repair or dependency change is delivered here.

[Tile-membership eligibility (#85)](https://github.com/novakin/wireguard-android/issues/85): Settings offered Add tile while native inventory still contained the installed component. The subsequent already-added handling remained idempotent. Current tile eligibility depends on a transient service flag; the exact preceding teardown or process-reset event was not captured and is not inferred. Installed membership and service lifetime need separate treatment in a bounded repair. Both issue drafts received independent ultra review before personal-account publication; their observable acceptance remains open. This record does not authorize their implementation or close #34/#35/#52.

Rejected launcher namespace/direct non-exported Settings launch, an invalid archive path, unsupported MediaStore column and a guessed nonexistent `shared_prefs` location were operator errors. Their failed evidence is retained and excluded from app-failure findings. Corrected commands and fresh readbacks supply the accepted results.

## Restoration and remaining gates

After confirmed synthetic deletion, remote control was OFF; CAMERA returned to denied with neither USER_SET nor USER_FIXED; rotation matched original values 1/0; and native tile inventory matched the original exactly. Fresh hashes identified session-owned Downloads fixtures/exports before removal; their files and MediaStore rows were absent on final readback. Remote capture removal completed successfully, without a separate absence readback before emulator shutdown. The debug package was uninstalled to restore its original absence.

Final restoration was recorded at `2026-10-09T09:25:43+02:00`. The private emulator process exited; ADB then listed only the untouched visible emulator. No required device command remained active. No personal configuration or physical-phone state was part of this isolated run.

Applicable physical camera/peer/Settings/Home acceptance, actual TalkBack traversal and speech, older-API permission/storage, conditional root/kernel coverage, authentication/export cancellation and provider failures remain pending under the [device runbook](../qa-test-plan.md#remaining-g4-device-acceptance). New findings also remain unresolved. Clean network, controlled energy and paired optimization proof are separate G4 gates; no issue closure, performance target, battery gain or no-change optimization verdict follows from this run.

Fresh ignored energy helpers in `.artifacts/g4-energy-preparation-20261009/` passed 50 offline synthetic controls and independent ultra review. They await a new exclusive physical-phone handoff under the [#47 baseline procedure](../qa-test-plan.md#controlled-47-baseline-preparation); injected responses and local subprocess controls establish no real ADB transport, counter cadence, restoration or energy baseline.

## Documentation impact

Development, QA, the active plan and UI roadmap link this native result while retaining physical and unresolved acceptance. README, AGENTS.md, backend changelog/contracts, architecture, operations and templates need no edits: runtime behavior, ownership, setup and contribution policy are unchanged. Earlier physical/CPU/energy records retain their original dates, revisions and limits. The documentation PR owns final checks, independent review and publication state.
