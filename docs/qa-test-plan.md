# Tunnl QA Test Plan

Last updated: 2026-10-10 (Europe/Paris).

This document tracks the automated and manual QA coverage required for the Tunnl Android redesign.

The layer lists below are target requirements, not a passing coverage report. [Source-inspected coverage](#source-inspected-coverage) inventories current source, including explicitly identified pending changes; [development evidence](development.md#recorded-verification) records actual executions and their scope. The original documentation audit at `11d4a9b1` ran no runtime suites. #13 adds focused detail-fragment lifecycle tests; broader rendered-row, instrumented navigation and screenshot suites remain absent.

## Test Layers

The [2026-10-06 phone follow-up](development.md#phone-ui-and-throughput-follow-up--2026-10-06) records a scoped physical Android 16 run. It does not install an Espresso/screenshot framework or replace the full support matrix below. Keep captures local: real profiles can expose private metadata even with secret fields masked. Before automation, confirm the current package/screen and protect unsaved user drafts; use synthetic inputs for writes. Restore temporary device settings and per-app locales after checks.

### CI coverage and APK boundary

The [Android workflow](../.github/workflows/android.yml) executes all UI/tunnel debug unit tests and Detekt on the actual PR head, `master` pushes and manual dispatch. Check the final-head run and XML/Detekt reports; a declared workflow or old passing revision is insufficient. Reports expire after seven days and may be unavailable after setup failure. The [development guide](development.md#android-ci-and-debug-apk-artifacts) owns toolchain, cancellation, artifact provenance and signing/install instructions.

PR/push runs and default manual dispatch do not assemble an APK. Explicit `build_apk=true` dispatch runs checks before debug assembly/upload; authorized artifact/install execution is recorded in the [G4 investigation](reviews/2026-10-07-g4-preflight-investigation.md) and [candidate-device follow-up](reviews/2026-10-07-g4-candidate-device-review.md). CI does not run instrumented/device, benchmark, native VPN/root or live/destructive VPS checks. It does not establish the support matrix below or configure branch protection. Keep #28/#34/#35/#36 physical acceptance open independently of CI success.

### Host native Go tests

Run `bash tunnel/tools/libwg-go/test-host.sh` in the configured Linux/macOS/WSL native toolchain. Set `JAVA_HOME` to the host JDK and provide a host C compiler. The script prepares pinned Go 1.24.3 through the existing Makefile, generates the version-checked overlay and runs the UAPI, Android-bind, overlay and production handle-owner tests with `-race -count=1 -timeout=90s`. It then runs the JNI host harness with `-Xcheck:jni`. Controls use the production Unix-listener helper and cover idle accepted clients, complete handler drain, partial requests through the real pinned WireGuard handler, concurrent admission/repeated close, active-path refusal, stale-path/private-mode recovery, unlink-on-close and preservation of non-socket paths. CI runs this host suite in addition to JVM/static checks. It does not compile Android native ABIs, assemble an APK or establish device/network behavior. For build-owner changes, separately compile the affected Android ABIs and verify an incremental source edit changes the library without APK assembly; [executed #66 proof](development.md#native-uapi-lifecycle-and-incremental-builds--2026-10-07) retains its original scope.

### JVM Unit Tests

The #33 log recovery adds `LogLineParserTest` (threadtime/UID, continuation/malformed headers, dates and severity sets), `LogcatStreamTest` (sparse/continuous deadlines, bounded bursts/EOF, blocked/full-channel cancellation, destroy-induced I/O masking and genuine I/O/programming failure), `LogBufferTest` (filtered/unfiltered eviction, oversized batches, continuation character bounds, snapshots and refresh) and Robolectric `LogViewerPublicationTest`/`LogExportTest` (stale generation, actual adapter notifications, overlapping share ownership/revocation, UTF-8, write/close I/O and cancellation/defect propagation). Stream fixtures use synthetic text; a native `/bin/sh` process control is skipped when that executable is absent. [Executed evidence and limits](development.md#log-pipeline-recovery--2026-10-06) distinguish these controls from Android logcat, actual pipe/chooser behavior and rendered-screen proof.

Use for deterministic logic that does not require a device.

`TunnelPresentationTest` exercises manager-resolved pending UP/DOWN, observer ordering during statistics clearing, failed-disconnect restoration, burst/idle/burst rates, repeated snapshot reads, minimum sample interval, counter rollback and cleared-statistics reconnect baselines. `TunnelEditorHeaderTest` exercises the actual labeled Save click, actual Creator RESULT_OK/message publication on finish and failed-save preservation. The caller's activity-result/Snackbar path is source reviewed. `TunnlSnackbarTest` injects a contrasting Material tint and asserts the actual Material-owned tint is cleared; the negative control fails when the production clear is removed. These controls use synthetic state/configuration and do not establish native VPN traffic or rendered/keyboard layout.

For [#48](https://github.com/novakin/wireguard-android/issues/48), `TunnelStatisticsTest` supplies synthetic blocking backend controls. Require cache-only binding reads, shared explicit readers with original snapshot identity, freshness at/after 900ms, DOWN/TOGGLE and reconnect rejection, config/rename/delete/backend invalidation, worker-thread DOWN/UP before queued publication, one/all reader cancellation, caller/application/backend cancellation, failure/Error propagation and retry, and a still-resumed Detail poll after owner invalidation. Run the manager, presentation, Detail lifecycle and binding suites alongside it; broaden to UI/tunnel unit tests and Detekt for this shared contract change. Actual executions and final-head review belong in the PR acceptance record. These controls prove no blocking-JNI interruption, physical VPN behavior or energy/throughput gain.

- Provisioning JSON parsing:
  - `detect`, `plan`, `install`, `sync-status`, `add-peer`, `remove-peer`, `revoke-peer`, `backup`, `uninstall`
  - invalid JSON
  - `ok:false` error payloads
- Provisioning command failures:
  - exit `0`
  - nonzero with stderr
  - nonzero with stdout present and no stderr; payload-free failure summary
  - nonzero with empty output
- Remote shell execution boundaries:
  - command timeout when SSH connects but the process never exits
  - coroutine cancellation closes the remote command/session
  - unreachable server maps to a typed provisioning failure, not a generic crash
- Provisioning request args:
  - `VpsInstallRequest.installArgs()`
  - optional `--force`
  - `VpsAddPeerRequest.args()` with and without DNS
  - add shell-quoting regressions at the command-construction boundary; current request tests cover argument lists only
- Store behavior:
  - empty store
  - upsert insert
  - upsert replace by ID
  - remove one record
  - update status
  - corrupt JSON fallback
  - concurrent `upsert`, `remove`, and `updateStatus` operations preserve DataStore transaction semantics
- Formatters:
  - byte thresholds
  - compact handshake age
  - short key truncation
- Tunnel state helpers:
  - inactive
  - connecting
  - active with fresh handshake
  - no signal with stale handshake

### Robolectric View Tests

`HomeSelectionTest` uses synthetic managed tunnels and actual Home row/header/dialog actions for sorted insertion/rename, zero-selected/select-all/Back, recreation, active confirmation cancellation, mixed-delete restoration/progress, complete deletion, admitted and pre-entry cancellation, view recreation during admitted deletion, inventory replacement, programming-defect attempt ordering and checkable row semantics/inset restoration. Native content previews cover six theme/size/large-text/RTL configurations, one-line status badges and scrolling confirmation actions. Physical provider, system-bar and TalkBack proof remains required under #36/G4. See [Home recovery evidence](development.md#home-selection-and-safe-delete-recovery--2026-10-07); native content previews are separate from device acceptance.

Use for Android resources, data binding, programmatic fragment UI, and lightweight view behavior.

- `AddPeerPresentationTest`: real QR/file/manual row clicks and one result after dismissal, native button semantics, camera-feature absence, cancellation/collapse and recreation/reopen. Nullable peer snapshots exercise independent RX/TX totals, localized units, actual value copying with named clipboard metadata, missing/zero clearing and independent handshake visibility. Native content previews cover small portrait in both night configurations, landscape at normal/2× text in night mode and 2× Arabic RTL in both configurations, with actual inherited direction, measured 48dp targets and a positive scroll range/reachable final action. These fixtures do not open camera/pickers, exercise real permission/error callbacks or prove keyboard/system-bar/TalkBack/VPN behavior. See [Add/peer evidence](development.md#add-sheet-and-peer-presentation-recovery--2026-10-06).

- `QrScannerPermissionTest`: real Home/Add QR launch configures the existing ZXing capture activity, unlocked orientation, silent scanning, prompt and app-owned missing-permission message. Injected API 28 permission-denial callbacks check accurate dialog text, cancelled/missing-permission results, exit/reopen and preserved stored profiles; ordinary cancellation skips import and a valid synthetic result retains the naming dialog. French message selection and the separate genuine camera-error callback are controlled without opening a camera. These fixtures do not establish native Android permission prompts, granting/retry, lens decoding or rendered device behavior. Separate [repaired-APK API 36 AVD checks](reviews/2026-10-09-g4-private-native-acceptance.md#camera-repair-acceptance--2026-10-09) prove native denial/exit/grant/retry and scoped advice rendering for #84; physical decoding and broader #35/#52 acceptance retain their own gates.

- `SettingsActivityTest`: native preference groups after API/admin/backend filtering, pending-backend header hiding, kernel action/root-probe eligibility, unchanged switch keys/defaults and values across recreation, log/Back navigation and a single restored fragment. Tile controls record successful/already-added results, preserve cancellation/request-error feedback and missing confirmation, hide setup during loading or unavailable reads, update visibility after removal while Settings stays open and persist a late result after Settings destruction. Deferred successful/already-added results after actual removal preserve false membership with Settings both alive and destroyed. They preserve expanded control sections and recreation/reopening eligibility; a binding control verifies the native WireGuard artwork has no generic row tint. Insets exercise all four sides, repeated dispatch, consumed system bars/cutouts and retained IME insets. Native Robolectric raster previews exercise 320dp English portrait and 2× Arabic RTL text under both night configurations, plus 640dp landscape in night mode; inspect previews locally. These controls use synthetic preferences and shadowed kernel/tools/tile checks, with no root, VPN, APK install, real tile request or export picker. Physical system bars, TalkBack and the full support matrix remain pending; see [Settings recovery evidence](development.md#settings-presentation-recovery--2026-10-06).

- `QuickTileServiceTest`: supported add/remove callbacks write nullable confirmation through the real settings owner; service/listening teardown and recreation do not create or clear it. Temporary-file DataStore recreation checks committed true/false and missing state, a delayed add write followed by removal checks transaction ordering, and injected persistence failure preserves unknown state. These JVM controls establish no actual native tile inventory or VPN toggling. Separate [repaired-APK API 36 AVD controls](reviews/2026-10-09-g4-private-native-acceptance.md#tile-membership-repair-acceptance--2026-10-09) verify native Cancel/Add/already-added, actual removal, independent add/remove callbacks and inventory/row eligibility across Settings, rotation and app-process reopening. Earlier private-AVD observations retain their original APK and historical defect scope; physical/accessibility and tile-toggle VPN acceptance remain under #34/#52.

- `LicensesActivityTest`: actual local payload, unchanged full source/font text, relevant copyleft/Go/native notices, selectable LTR text for each complete notice, private manifest activity, recreation and header Back. API 36 controls compare actual accessibility-node text to each rendered notice, require every notice below Android's 100,000-character limit and concatenate them to the exact original asset. Native raster controls cover 320dp portrait and 2× Arabic RTL in both themes, plus 640dp landscape, with 48dp Back, wrapping and reachable first/last text. `SettingsActivityTest` checks nonpersistent credits and version navigation after recreation on API 28 and API 33 with its independent live tile observer, without a browser intent or changes to stored preferences. Separate [repaired-APK API 36 AVD acceptance](reviews/2026-10-10-license-avd-acceptance.md) records all 27 complete native notice nodes, six output configurations, Settings reopening and selection controls, including UIAutomator's form-feed serialization limit. Actual TalkBack/clipboard/physical coverage and complete distribution obligations remain [#71 gates](license-attribution.md#artifact-and-distribution-gates).

- `VpsProvisioningFragment`:
  - empty fleet renders empty card and add-server button
  - step 1 renders host, port, user, and key fields
  - switching key/password preserves host, port, and user
  - password mode masks input
  - back from step 0 returns to fleet
  - back from later step decrements the step
  - apply preview cannot advance through bottom action before completion
  - apply job is lifecycle-scoped and canceled on view destruction
  - revoked users disable QR, rotate, and revoke actions
  - unreachable server renders the provisioning error state
- Data binding:
  - tunnel list status label updates for every mode
  - PSK badge visibility follows peer PSK presence
  - detail peer endpoint/keepalive rows hide when absent
  - detail peer titles render as `PEER #01`, `PEER #02`, ...
  - server detail renders the empty peer list state before any VPN users are added

### Instrumented Espresso Tests

Use for full navigation and Android framework integration.

- App launches to home screen.
- Home server icon opens VPS fleet.
- Add tunnel sheet opens from home.
- Add tunnel actions appear in design order.
- Logs screen opens and filters switch without crashing.
- Settings opens from home.
- Tunnel detail opens from tunnel list.
- Editor opens from detail.
- VPS wizard next/back navigation works across all steps.
- QR overlay opens and closes.
- VPS provisioning unreachable-server flow displays the expected error state.

### Screenshot Regression Tests

No screenshot framework is selected or installed. Reuse existing fixtures first; select Paparazzi, Shot or another renderer only when required coverage justifies it. Use fixed data/font scale for baselines and separate large-font variants.

Baseline requirements:
- dark mode goldens are mandatory because the redesign is dark-first
- RTL variants are required for custom rows, rails, headers, and overlay layouts
- fixed font scale and deterministic test data

- Home tunnel card: active, connecting, no signal, inactive
- Home empty list
- Add tunnel sheet
- Detail hero/status card
- Detail peer card with PSK
- Detail peer card without endpoint/keepalive
- Editor interface card
- Log row severity variants
- Settings preference row
- VPS empty fleet
- VPS server card: online, provisioning, attention, offline
- VPS wizard key mode
- VPS wizard password mode
- VPS topology hero
- VPS QR overlay
- Tunnel list row states, including rail color and stats-row visibility
- Detail peer card transfer/handshake visual states

### Security Regression Tests

- Passwords are not included in snackbars, logs, exceptions, or copied text.
- Generic copy handlers do not accidentally expose private keys; deliberate authorized profile export/import retains the full required credentials.
- Password/session secret is cleared on cancel and view destruction.
- `trustHost=false` does not persist fingerprints.
- Uninstall, revoke, remove peer, and delete tunnel require confirmation.

### Manual Smoke Tests

Required before merging UI or backend provisioning work, except the explicitly authorized source-only recovery integration in G1 of the [active goal](improvement-plan.md#active-enhancement-and-performance-goal). That narrow exception requires meaningful local regression checks, native content inspection for affected UI and independent ultra review; physical/device-provider acceptance stays open and must pass in G4 before delivery or goal completion. It does not waive live provisioning acceptance or authorize APK/device operations.

- Import a WireGuard config.
- Toggle tunnel up/down.
- Visit detail while live stats update.
- Open logs, filter logs, share logs.
- Rotate device on home/detail/editor/VPS wizard.
- Background and foreground the app while a tunnel is up.
- Delete a tunnel.
- Exercise Android VPN permission flow on a clean install.

## Full app-data transfer acceptance

The [2026-10-09 synthetic AVD record](reviews/2026-10-09-app-data-transfer-acceptance.md) records executed native import/export, PIN, persistence and layout checks and retains the remaining physical, conditional-environment and cross-app gates. Reuse its scoped results only for matching source/artifact boundaries; they do not complete #71 or other G4 issues.

Run `BiometricAuthenticatorTest` for the real shared helper with injected AndroidX/platform responses: configured credentials, explicit no-lock absence, unavailable/unknown/unsupported status, missing Keyguard, runtime hardware/enrollment failures, native cancellation and failed-sample then success. No error may become export/key-reveal authorization. These controls complement Settings's caller-level authentication shadow; they do not establish actual biometric hardware behavior.

For the #71 legacy-ID preparation slice, run `AppDataTransferTest`, `AppDataTransferManagerTest`, `VpsServerStoreTest`, `TunnelImporterTest`, `DownloadsFileSaverTest`, affected Settings/export tests and zero-issue Detekt. Regressions must cover full/settings-only roundtrips, typed/unsupported/corrupt input, duplicate/path/missing-profile/expanded-size rejection, original stored-fleet compatibility, biometric failed-sample retry and stale terminal callbacks, private-payload errors, identical-data reuse, profile/fleet conflicts before writes, I/O/metadata failure, owned rollback and failed recovery, cancellation before/after admission, startup restoration and delayed-selection races, existing active-state preservation, and ordinary ZIP partial-result compatibility. Prove API <29 non-overwrite preserves existing output and cleanup owns only a newly reserved file.

After explicit APK authorization, inspect the Settings actions in supported themes/sizes, large text and RTL. Verify administrator export denial, biometric success/denial/cancellation, legacy storage permission and actual Downloads/provider failure cleanup; import through the native document picker with confirmation dismissal/recreation, success/persistence and malformed/conflicting archives. Use synthetic profiles and saved servers; retain no secret archive or credentials in source evidence. Confirm Settings reloads imported preferences and new profiles stay off while unrelated active state remains unchanged. JVM controls are separate from this output/provider proof.

Full identity migration additionally needs the signer-compatible old-ID exporter update and separately built new-ID importer, both installations preserved. Verify exported synthetic configs/settings/fleet, fresh Android VPN/permission/tile setup and recovery with both apps installed; enabling one userspace VPN affects Android's active VPN. Do not uninstall/reset the old app as cleanup. Namespace/JNI/tools/updater/distribution and physical/kernel gates remain part of #71. No process-death atomicity, DPI, speed or battery claim follows from this slice. [Transfer operations](operations.md#app-data-transfer) and [contracts](backend-contracts.md#full-app-data-transfer) define the supported boundary.

## Remaining G4 device acceptance

This runbook sequences the existing acceptance in [#28](https://github.com/novakin/wireguard-android/issues/28), [#34](https://github.com/novakin/wireguard-android/issues/34), [#35](https://github.com/novakin/wireguard-android/issues/35) and [#36](https://github.com/novakin/wireguard-android/issues/36); it does not replace their acceptance or close [#52](https://github.com/novakin/wireguard-android/issues/52). The [physical import record](reviews/2026-10-08-g4-import-device-acceptance.md) and [isolated native follow-up](reviews/2026-10-09-g4-private-native-acceptance.md) own completed checks and their distinct environments/revisions. Reuse endpoint-free synthetic configs, mixed ZIP, valid QR PNG and blank PNG; use fresh unique names and verify destinations absent before uploading. Standard QR import asks for a name; file imports use filenames.

1. Establish a new exclusive interval and fresh state: package/APK provenance, current personal tunnel intent, config/preferences hashes, draft/screen, device settings with presence/absence, locale, current tile inventory and any existing export output. Preserve unrelated data and accessibility services. The expired interval and its restoration snapshot cannot authorize or initialize a later run.
2. Repeat reversible Settings switch/rotation/reopen/Back/log actions, then restore the observed original value. Inspect the remaining insets/large-text/RTL cases without counting earlier output as new proof. Record actual eligible groups; API/admin/kernel rows unavailable on this device remain explicitly conditional.
3. Scan the known synthetic QR through the physical camera and save a uniquely named DOWN profile. A second screen or print supplies the target; image import does not establish lens decoding. Revoking an already granted camera permission can kill the process that owns the userspace VPN: denial/recovery belongs in an isolated synthetic environment. Do not manufacture it on the working personal-UP package.
4. Check physical peer RX/TX copy actions against the captured displayed strings without copying keys. Inspect missing/zero rows on a synthetic DOWN peer and return to the original selection. Clipboard action proof does not establish equality with an independent native counter snapshot; retain that separate boundary.
5. Capture settled selected/unselected/zero Home states and check recreation/count/identity and confirmation cancellation. Delete only the exact session-owned names shown in confirmation. Existing `HomeSelectionTest` and `TunnelTestEnvironment` provide deterministic insertion/rename, blocked admission, partial failure and cancellation controls; do not modify personal files or activate a synthetic userspace tunnel to manufacture failure on the phone.
6. Exercise actual TalkBack traversal, button/switch/checkbox actions, selected counts, labels and announcements with coordinated spoken-output confirmation. Preserve and restore existing accessibility services/settings. Installed service, hierarchy labels and JVM semantics alone do not prove speech.
7. Keep authentication Cancel separate from export-operation cancellation. [Native API 36 Cancel/success checks](reviews/2026-10-09-g4-private-native-acceptance.md#api-36-credential-authentication-follow-up) cover absent and existing outputs; applicable physical authentication remains a separate gate. Current `ZipExporterPreference` exports every saved tunnel to fixed `wireguard-export.zip` with overwrite enabled; it has no destination picker or selected-profile export. Real provider failure/write/cancellation tests need isolated synthetic data where necessary. Reuse `DownloadsFileSaverTest` and `ZipExporterPreferenceTest` for deterministic source controls; their provider fixtures and manual output deletion are not physical production-cleanup proof. Keep [native API 28 permission/filesystem checks](reviews/2026-10-09-g4-private-native-acceptance.md#api-28-legacy-storage-follow-up) distinct from API 29+ MediaStore results.
8. Restore only session-owned changes and the intended current tunnel state. Verify fresh app-file comparisons, settings/locale, removal of hash-confirmed owned uploads/outputs and MediaStore rows, stopped commands and phone release. Never restore a stale archive over live data. Record actual results, unavailable cases and issue-state readback before claiming complete acceptance.

An isolated AVD may exercise native framework/provider/permission callbacks with the unchanged APK and synthetic data. Set up only its owned configuration; target its explicit serial, preserve the existing ADB server and never issue unqualified ADB mutations. Camera emulation and host-powered execution do not establish physical camera, Pixel cutout/gesture, root/kernel, TalkBack speech or battery behavior. An API 36 result supplies no pre-29 permission/filesystem proof; matching API 28 checks below retain their own scope. Native and physical results remain separately scoped; obtain applicable missing proof or an explicit scope decision before issue closure.

The [2026-10-09 private AVD runs](reviews/2026-10-09-g4-private-native-acceptance.md) establish bounded native camera-permission retry, tile Cancel/Add, repeated switch/recreation, canonical export/MediaStore overwrite and settled Home selection/cancellation proof. Separate API 28 checks add storage-permission denial/retry and filesystem success/overwrite; API 36 credential checks add authentication Cancel/success and preservation of existing output after Cancel. The [API 28 export open-failure follow-up](reviews/2026-10-09-g4-private-native-acceptance.md#api-28-export-open-failure-follow-up) adds actual pre-ownership `FileOutputStream` acquisition failure: `FileNotFoundException` (`Is a directory`) surfaced, the preexisting directory/sentinel and stored configs/preferences survived, the export action was enabled again, and retry produced the canonical two-config ZIP. The later [#84 repaired-APK session](reviews/2026-10-09-g4-private-native-acceptance.md#camera-repair-acceptance--2026-10-09) proves corrected native denial/exit/retry advice; it does not complete physical QR or broader Add/peer acceptance. The [#85 repaired-APK session](reviews/2026-10-09-g4-private-native-acceptance.md#tile-membership-repair-acceptance--2026-10-09) verifies retained tile membership and row eligibility across native actions, independent callbacks, Settings/rotation and process reopening. Physical gates above, real QR camera decoding and tile VPN toggling, export-operation cancellation, after-acquisition legacy write/cancellation cleanup, MediaStore acquisition/write failures, admitted deletion partial success/cancellation and active-state recovery remain unproved; successful export and manual owned-output removal do not discharge cleanup failure acceptance. Secure-prompt hierarchy/callback proof does not establish rendered credential-screen fidelity. #28/#52 remain open; the follow-up record owns session restoration evidence separately from these action checks.

The [API 28 admitted-delete failure follow-up](reviews/2026-10-09-g4-private-native-acceptance.md#api-28-admitted-delete-failure-follow-up) proves a narrower native failure boundary: app-UID directory write denial made both confirmed synthetic DOWN deletions fail, while configs, settings-file absence, selection/counts and retry survived. Restoring the exact original mode allowed confirmed deletion and empty Home. Use only an owned isolated environment for such permission changes; preserve current path/mode/UID and persistent-file evidence, restore mode before retry/uninstall and verify cleanup/termination. This does not replace partial-success, admitted cancellation, active-state recovery or physical acceptance; #36/#52 stay open.

## Transfer-speed and battery validation

[G4/#52](https://github.com/novakin/wireguard-android/issues/52) tracks final validation for the [active enhancement/performance goal](improvement-plan.md#active-enhancement-and-performance-goal). The [initial G4 record](reviews/2026-10-07-g4-preflight-investigation.md) establishes authorized CI artifact/install proof and bounded incident observations; the [repeated network record](reviews/2026-10-07-g4-network-baselines.md) owns subsequent path measurements and their limits. #28/#34/#35/#36 physical acceptance, clean Wi-Fi context, controlled energy baselines and demonstrated optimization remain incomplete. Issues own the selected device/endpoint, primary metric, numeric target and guardrails after baseline measurement. APK assembly/install and a real benchmark target need their own explicit authorization. Start with Wi-Fi; mobile-data and root/kernel coverage are conditional on the authorized environment.

The [candidate-device follow-up](reviews/2026-10-07-g4-candidate-device-review.md) adds #55 warning confirmation and bounded Add/Home action/output proof on source `4e2f473d`. It preserves personal config/preferences and restores checked device settings; the changed Android runtime marker is identified separately. TalkBack, remaining peer/Settings/provider boundaries and complete physical acceptance stay open. The phone used LTE while charging; no speed/energy baseline was attempted.

The [2026-10-08 import/device follow-up](reviews/2026-10-08-g4-import-device-acceptance.md) uses the existing `f4a4b147` APK. It adds controlled mixed-ZIP/image-QR import, portrait keyboard clearance and synthetic-only Home deletion with exact app-file preservation/restoration. Earlier switch/tile/export observations have weaker ownership evidence; export cancellation remains unproved. At that checkpoint, camera QR/denial, TalkBack, peer copying/live counters, remaining Settings/provider callbacks and admitted-deletion failure/cancellation still needed their own proof. The native follow-ups above retain their separate scope. Transition captures do not establish settled selection fidelity or extend the earlier output matrix.

Record both baseline and candidate app revisions. Use the same phone, Android version, build type, test endpoint, network, backend and synthetic test profile for a paired comparison. Record signal/network quality, thermal state, charging state, foreground/screen state, MTU, routes, keepalive and background load. Keep private profiles/raw device reports local; commit only sanitized methodology and aggregates. Use existing Android profiling and benchmark tools rather than creating a performance framework.

Before a timed device measurement, establish one chat/runner as phone owner and coordinate other ADB clients/tools so they stay idle during the agreed interval. If concurrent use is discovered, stop the owned run, retain incomplete/diagnostic evidence and agree the handoff before restoration or another measurement.

Confirm whether the Wi-Fi router already uses VPN before calling app-off traffic direct. App-off/app-on measurements behind that router compare router-only and nested VPN paths; they are not clean direct Internet or upstream phone-handshake/DPI proof. For connectivity diagnosis, use an authorized cellular path with Wi-Fi disabled and the current default network verified, or an authorized Wi-Fi path without the router VPN. Record mobile-data approval/limit, establish the test source before restricting the temporary listener and verify the expected server-observed source in each successful exchange. Preserve historical raw filenames while correcting their interpretation in sanitized aggregates. Restore each session's fresh starting state and verify personal file hashes and temporary-device/server cleanup.

For foreground/battery comparisons, verify actual animation behavior and effective system scale before measuring; a stored setting alone is insufficient. Record whether each temporarily changed Android setting existed, and restore an absent setting by deletion rather than writing the string `null`. If the maintainer resumes use during cleanup and the desired final tunnel state is unclear, confirm it before further toggles. Distinguish temporary running-state preference changes from personal config/settings edits; report final preservation and any failed automatic restoration separately. Do not copy a stale archive over current data or classify a failed cleanup command as successful because its load trials passed or later recovery succeeded.

Verify the profile again after interruptions or user-reported recovery. A changed ListenPort invalidates a fixed-profile comparison; preserve the new value and separate the runs. `UDP preflight sent` and active Android VPN-service state do not prove endpoint delivery or sustained health. A failed/incomplete iperf run is failure evidence, not a throughput baseline. Separate capped diagnostic traffic from uncapped baseline scenarios and stop traffic when the maintainer reports the path blocked. Obtain a restored authorized path and confirm its state before resuming the repeated protocol.

The [G2 source investigation](reviews/2026-10-07-performance-source-investigation.md) proposes #47 as the single battery candidate and retains current transfer code until G4 proves a bottleneck. Include covered single-pane Home, visible two-pane Home/Detail and hidden attached animations in the baseline; resumed/attached state does not establish actual visibility. #48's shared-read/late-publication repair has separate source acceptance under [JVM unit tests](#jvm-unit-tests); it establishes no measured battery or transfer gain. The four original temporary characterization controls retain their historical scope; they are not device frame-frequency or energy/throughput evidence.

The [2026-10-08 presentation CPU record](reviews/2026-10-08-g4-ui-cpu-baselines.md) records the post-#48 baseline's verified APK provenance, three accepted Home/covered-Detail/Settings/background windows per scenario and separate scheduling traces. CPU and scheduling evidence from a charging phone remains diagnostic; controlled energy, targets and #47 implementation are separate gates. Preserve exact counter endpoints, process identity, elapsed time and current presentation proof when collecting new windows. Retain excluded/mislabeled raw windows and their reasons; count only the verified scenario, and keep instrumented traces separate from process-counter results. Compare a later candidate against a baseline with the same unrelated source changes; never attribute an earlier build's statistics-policy difference to #47.

The [unplugged energy pilots](reviews/2026-10-08-g4-energy-pilots.md) retain uncontrolled diagnostic endpoints under a different Wi-Fi/wireless-ADB context. Another controller's ADB use was confirmed during testing; its contribution is unquantified. A changed Detail viewport cannot be counted as a fixed-view repeat, and the deliberately interrupted first screen-off window has no endpoint result or product-failure verdict. Measurements stopped and authorized cleanup was verified through unlocked USB access: personal configuration/preferences match, test-app VPN, Wi-Fi and wireless debugging are OFF, automatic brightness is applied and timeout restored. The dynamic numeric brightness value was preserved; eleven of twelve final saved checked values match exactly. The record distinguishes a stalled cleanup command from later successful readback. Preserve raw observations/exclusions, record uncontrolled traffic and distinguish whole-device estimates from app energy. Boundary pairs do not establish counter resolution/cadence, exclusive use or a matched workload. These diagnostics complete no controlled baseline or target gate. For controlled repeats, verify viewport as well as activity identity and keep observed interruptions separate from inferred causes.

### Controlled #47 baseline preparation

Refresh the retained CPU/energy helpers into a new ignored session directory before execution. Their old transport, display values, network label and restoration snapshots belong to completed pilots. Supply the current agreed target/context and preserve exact CPU tick endpoints, process identity and separate monotonic CPU/battery intervals. This is a reuse of measurement tooling, not a new performance framework or an accepted baseline.

Begin with a separate counter-capability diagnostic: timestamp charge-counter readings at five-second intervals for an initial ten minutes, extending if changes are sparse. Report observed increments and plateau intervals under that instrumentation; they bound observed granularity/cadence, not true resolution or accuracy. Do not pool these sampled windows with the quiet energy repeats. If counter behavior cannot resolve scenario depletion and variability, improve the measurement/duration before freezing a target; CPU proxies alone cannot establish battery savings or a verified no-change result.

The provisional quiet order is Home → covered Detail → ordinary screen-off, then Detail → screen-off → Home, then screen-off → Home → Detail. Initial holds are 300 seconds for each foreground view and 600 seconds for screen-off: 60 minutes total, excluding capability checks, setup, thermal settling and restoration. Lengthen holds before accepting repeats if counter steps/noise are too large. Fix the Detail viewport, actual animation behavior/effective scales, brightness, profile, network/router context and background workload; boundary activity identity alone does not prove stable presentation or uninterrupted residence.

Physically unplug USB and require every reported power source OFF, discharging and no battery simulation. Wireless ADB/network conditions must match later comparisons; ordinary noninteractive screen-off with wireless debugging is not hardware deep-suspend proof. No transfer load is part of this idle/foreground baseline. Reject controlled acceptance for observed concurrent use, charging, interruptions or changed viewport/network/profile/process; retain excluded records and traffic/thermal context rather than discarding inconvenient depletion. Three baseline repeats precede separate paired candidate trials. Freeze the primary metric, numeric target and regression limits in #47 only after controlled spread is known; retain configuration/connectivity/rates/appearance/accessibility guardrails and the original-state restoration protocol above.

| Scenario | Measures and control |
| --- | --- |
| Transfer | Compare direct and VPN download/upload against the same authorized endpoint. Use fixed warm-up, duration and stream count; inspect sustained throughput, latency/loss and CPU cost. Run at least five paired A/B trials, varying A/B order to reduce thermal/time bias, and report median plus spread. Increase repetitions only when noise prevents a decision. A direct result is context, not an optimization comparison between app revisions. |
| Idle/screen-off | Compare equal-duration idle-connected windows with the same network/profile/keepalive. Run at least three paired windows long enough to resolve the chosen energy measure; record wakeups, CPU activity and available energy evidence. Android modeled estimates, charge-counter deltas and battery percentage have different limits. A charging/USB-powered session does not prove reduced battery drain. |
| Foreground UI | Profile list/detail polling, request overlap, drawing/animation, allocations and state refresh under equal conditions. Check hidden/stopped/foreground transitions. CPU/wakeup reductions are proxies; call them battery savings only when the corresponding controlled energy comparison establishes that result. |
| Sustained traffic | Compare energy for the same amount of transferred data and a fixed-rate workload; report duration, thermal behavior and throughput separately. Do not interpret a faster run's lower total energy as an idle-power reduction. |

Before selecting an optimization, repeat each baseline transfer scenario at least five times and each idle-energy scenario at least three times to establish variability. These baseline repeats precede the paired candidate trials above; they do not count as a comparison between app revisions. Before each optimization, freeze its primary target and allowed regressions in the issue based on measured baseline variability. Require repeated paired benefit beyond the observed noise and all declared guardrails. Inconclusive results require more focused evidence or a no-change decision. JVM formatting/lifecycle tests and a successful APK build do not establish speed or energy gains.

Preserve configuration parsing/persistence, activation/teardown and cancellation, DNS/routes, connectivity/NAT behavior, accurate statistics and UI/accessibility. MTU, endpoint or keepalive experiments must identify the specific path and restoration method; do not change global defaults from one phone/server result. Userspace is the baseline unless the authorized environment supports kernel testing. Keep device/native limitations and untested network/backend combinations explicit.

## Source-inspected Coverage

The phone presentation, Creator result and Snackbar tint suites are described under [JVM unit tests](#jvm-unit-tests); they are included in the inventory below.

The source inventory below includes merged #14/#13/#16/#15, the merged #22 suites, the phone follow-up, #33 log recovery, merged #34 Settings source, #35 Add/peer source, #36 Home source, #28 Downloads ownership, #55 debug-warning controls, the Settings structure-ID regression, shared statistics teardown controls from #68 and #48 model-statistics regressions. The shared TunnelTestEnvironment fixture is not a test suite. This is an inventory, not an execution total; historical counts retain their original reviewed revisions in `docs/reviews/`. Guard tests use fake execute calls for timeout, I/O failure and cancellation propagation; they do not prove an entered SSH command, upload timeout or actual channel/session closure. Robolectric also covers detail-fragment selection/destruction, kernel-preference failure recovery and bounded Settings/Add/peer/Home navigation/rendering; whole-app/device proof remains separate.

`TunnelManagerTest` covers admitted/queued transitions, deletion/config failure recovery, cancelled initialization, stale state snapshots/native notifications, restoration intent and backend-switch admission/rollback. `ApplicationBackendLifecycleTest` proves cancellation before initializer execution settles backend waiters. `TunnelDetailLifecycleTest` covers reversed/cleared config selections, delayed peer statistics after selection/destruction, disconnect clearing without waiting for an in-flight fetch and failure clearing. `KernelModuleEnablerPreferenceTest` checks failed shutdown restores its setting/control. `GoBackendLifecycleTest` covers prompt destruction while its lock is held, dead-service rejection/future replacement, cleanup identity/callback ordering and native callback liveness, originating-thread interruption, exact-service/descriptor protection and Error propagation, including state/DOWN/service settlement when native teardown rethrows an Error. Config serialization controls retain explicit/absent ports; native-owner packet/port tests are separate. Synthetic owner/native-call shadows exercise no successful native activation, real VPN traffic, process exit or device lifecycle. See [original lifecycle evidence](development.md#tunnel-lifecycle-and-transition-settlement--2026-10-06) and [peer follow-up](development.md#add-sheet-and-peer-presentation-recovery--2026-10-06).

The [#37 compatibility review](reviews/2026-10-07-udp-compatibility-review.md) retains the merged backend and rejects the preserved mandatory/random-port experiment. Its eight existing controls were rerun for that decision; bind/send exhaustion and DNS failure/sleep interruption remain source traced rather than injected. Native port/NAT handoff, successful activation, packet traffic and reconnect need separate physical proof. No speed/battery benefit is established by retaining source.

Historical baseline [#55 controls](reviews/2026-10-07-g4-settings-add-debug-review.md) observed preflight tag 0 at socket protection and restoration of tagged/untagged caller state after rejected protection and a successful one-byte loopback send. Settings records whether its kernel-support probe runs on Main and retains the existing preference eligibility controls. These JVM tests do not certify native socket accounting or candidate-APK StrictMode output. The same scoped record covers actual Settings/Add phone captures across portrait, landscape, system light-mode, 200% text and Arabic RTL, with unchanged app files and restored checked settings. Its physical acceptance limits retain their original scope; no repeated performance result is added.

The [Settings structure-ID regression in `SettingsActivityTest`](reviews/2026-10-07-settings-resource-id-review.md) binds and lays out every real preference adapter row on API 36, dispatches Android's framework assist capture and checks roots/descendants for invalid ID zero before and after recreation. Each adapter enables stable IDs and each bound holder retains its non-`NO_ID` item identity; identity equality across separate adapter instances is not asserted. Synthetic kernel support exposes the full applicable modern Settings rows. The owner fix changes no geometry, keys, values or action. Separate phone confirmation on Pixel 10a reproduced nine errors on the previous candidate; three new Settings opens and landscape recreation had zero after verified in-place replacement. Native Back/log navigation and settled portrait/landscape output were checked; personal config/preferences were unchanged and nine device values restored. Full switch/tile/export/accessibility behavior and the precise capture caller remain unverified; #34/#52 stay open. The explicit launch capture does not cover complete package-replacement startup or a newly established preflight path.

`FileConfigStoreTest` covers temporary local files, successful profile round trips, partial-write/native-rename failure preserving prior content, failed creates with no usable profile, conflicting names and concurrent create/save/delete ordering. The Context writer and native-call shadow inject failures without modifying user data. The reviewed suite does not prove Android filesystem durability, actual disk-full/power-loss behavior or coordination with other processes/store instances; [development evidence](development.md#atomic-local-profile-replacement--2026-10-06) separates executed results and limits.

C4 extends the existing provisioning suites with exact normalized responses from real scripts, successful profile parsing through upstream Config, strict nested/missing/wrong-type shapes, safe remote categories/recovery, nonzero script failure dispatch, secret-safe logs/store corruption and legacy/null running migration. Command tests require Bash in the JVM test environment: fake id/sudo/script stubs exercise both constructed dispatch branches and preserve hostile argument/path bytes without root or SSH. Native running/stopped/unknown and missing/malformed counters are separate Bash cases. Mapper controls cover stopped, legacy/unknown, stale/future and threshold observations; ONLINE does not establish connectivity. [Development evidence](development.md#c4-provisioning-contracts-and-observed-health--2026-10-06) records actual runs.

The separate [Bash provisioning safety suite](../scripts/tests/provisioning_safety_test.sh) exercises real scripts with fake commands and synthetic keys in private `/tmp` fixtures. It requires namespace UID 0 mapped to an unprivileged host UID and refuses host root. Coverage includes unmanaged/symlink preservation, input injection, interface/host lock races, staged write/apply/rollback faults, exact runtime key/port/peer/routes/PSKs, malformed peer metadata, forced reinstall ordering, private archives/backups, stopped-interface controls, partial purge, post-commit cleanup and TERM recovery. [Development evidence](development.md#c2c3-offline-provisioning-safety--2026-10-06) records executed totals and limits; these are separate from JUnit inventory. No installed WireGuard/systemd/apt command, SSH session or live network is exercised.

- `ProvisioningJsonTest`
- `ProvisioningCommandOutputTest`
- `ProvisioningLoggingTest` — failed output, malformed outer JSON, cleanup redaction and valid-profile controls.
- `RootShellLoggingTest` — synthetic root-shell framing, output and secret-safe logging.
- `GuardedRemoteShellClientTest`
- `VpsDeployMapperTest`
- `VpsProvisioningRequestTest`
- `VpsServerStoreTest`
- `BindingAdaptersTest`
- `QuantityFormatterTest`
- `FileConfigStoreTest`
- `TunnelManagerTest`, `ApplicationBackendLifecycleTest`, `TunnelDetailLifecycleTest`, `KernelModuleEnablerPreferenceTest`, `GoBackendLifecycleTest` — scoped #13 owner/lifecycle regressions.
- `TunnelStatisticsTest` — #48 shared model reads, session validity, cancellation/failure settlement, retry and continued visible polling, as described under [JVM unit tests](#jvm-unit-tests).
- `SettingsActivityTest` — #34 native preferences, recreation, scoped navigation/insets and native raster layout controls described above.
- `LicensesActivityTest` — #71 full local notices, private activity, per-notice selection, recreation/Back and native wrapping/scrolling controls; #100 full accessibility-node text regression. APK/distribution and device acceptance remain separate.
- `AddPeerPresentationTest` — #35 source actions/accessibility, peer snapshots and native content layout controls described above.
- `QrScannerPermissionTest` — #84 Home scanner options, injected denial/dialog callbacks, cancelled/no-import retry, valid-result naming and separate genuine camera-error feedback; native/physical acceptance is scoped separately above.
- `WgQuickBackendTransitionTest` — concurrent UP with disabled/enabled multiple mode, failed activation/shutdown/config update and visible rollback failure through a simulated command-serialized RootShell; the real Application mode collector stays responsive on Main while the backend monitor is held. No real tools or root run.
- `TunnelComparatorTest` — natural-order controls, equal normalized-name antisymmetry and distinct inventory lookup.
- `QrCodeFromFileScannerTest` — reopened/recycled retries, allocation recovery/exhaustion, first Reader failure and cancellation/programming errors.
- `TunnelImporterTest` — cancellation/error propagation, category-only logs, one callback and mixed ZIP traversal/partial success with unchanged duplicate ownership.
- `DownloadsFileSaverTest` — synthetic API 29 insertion/open/metadata/null-response/overwrite/fallback controls, descriptor/cursor/stream closure, primary failure/Error and cleanup-error retention, zero-result deletion and cancellation; API 28 successful output, failed open preserving a pre-existing path, write/cancellation cleanup, false deletion, repeated permission denial, cancelled permission-wait retry and rejected permission-launch settlement. Physical storage-provider behavior and blocking-write interruption remain unproven; see [Downloads evidence](development.md#downloads-output-ownership--2026-10-07).
- `ZipExporterPreferenceTest` — unchanged two-profile archive contents and restored controls, primary write/cleanup failures, and loading cancellation settling children without success. Saver ownership is tested separately above; no actual device/provider or interrupted blocking-write proof.
- `ConfigCompatibilityTest` — domain-only/mixed/address-only/empty DNS round trips, valid/omitted IPv4/IPv6 masks and typed rejected masks through both config fields.
- `ConfigTest` and `BadConfigExceptionTest` — configuration/parser fixtures and error descriptions.

## Next Implementation Roadmap

This roadmap covers the whole app, not only the VPS fleet. Do not move items from this section into active coverage until the tests exist and run in the expected test layer.

### Phase 1 — Stabilize Current Baseline

- Keep each runtime fix with its focused regression controls and affected documentation. Separate unrelated QA framework or broad coverage expansion into another branch or commit.
- Run `:ui:testDebugUnitTest` after test or runtime changes that affect app logic.
- Run full `:ui:assembleDebug` only when explicitly requested, per project build policy.
- Treat remaining untested behavior as residual regression risk; the current suite reduces risk but does not prove no regressions exist.

### Phase 2 — Complete Missing Automated Coverage

- Extend fake-wrapper proof to upload/deadline cases as needed; test hung execute/upload against the actual SSH library when C11 lands.
- Prove entered-command cancellation closes owned real channels/sessions; the immediate-cancel fake test proves propagation only.
- Test real unreachable-server mapping and the wired UI error state; fake execute I/O coverage proves neither.
- Add broader `VpsServerStore` stress tests if future fields introduce multi-record invariants; current transaction coverage includes racing `upsert`, `remove`, and `updateStatus` operations.
- Add empty peer-list coverage for server detail before any VPN users are added.
- Add Robolectric coverage for VPS wizard state, password masking, back/apply behavior, lifecycle cancellation, and revoked-user actions.
- Reuse `VpsDeployFixtureData` for deterministic VPS fleet/detail/user test states: online, provisioning, attention, empty peers, active peer, idle peer, and revoked-user action disabling.

### Phase 3 — Add Visual And Smoke Coverage

- Select the screenshot framework and add deterministic fixtures with fixed data and fixed font scale.
- Add mandatory dark-mode goldens for redesigned screens because Tunnl is dark-first.
- Add RTL goldens for custom rows, rails, headers, overlays, and bottom sheets.
- Add Espresso smoke coverage for home, detail, editor, logs, settings, add-tunnel sheet, VPS wizard navigation, QR overlay open/close, and unreachable VPS provisioning.

### Phase 4 — Add Opt-In VM Integration Tests

- `./gradlew vpsIntegrationTest` runs the disposable Ubuntu/Debian VPS integration path for install, sync, add peer, revoke peer, remove peer, backup, uninstall, reinstall, and final cleanup.
- Gate the suite behind explicit environment variables and a destructive-test guard: `TUNNL_VPS_HOST`, `TUNNL_VPS_USER`, and `TUNNL_VPS_DESTRUCTIVE=1`.
- Never run VM integration tests as part of default unit tests or default local builds.
- Repaired mutations reject unmanaged config. C9 requires a clean target, checks postconditions and limits failure cleanup to the owned upload; authorized real-server evidence remains separate.
- Use key-only SSH. Do not put passwords or private keys in the repo; optional local settings belong in ignored `.tunnl-vps-test.env`.

## Disposable VPS Integration Use Case

Use `vpsIntegrationTest` when provisioning scripts or app-side provisioning command contracts change and unit tests are not enough to prove the scripts still work on a real server.

This harness is for an explicitly authorized clean disposable Ubuntu/Debian VPS only. It uses system OpenSSH/scp and requires an actual root session (`id -u`), not Android transport or sudo. Existing `wg0.conf` or `/etc/wireguard/tunnl` metadata is rejected. It exclusively creates a fresh upload directory, uploads scripts, forces installation, creates/revokes/removes peers, receives a secret-bearing backup response, uninstalls, repeats installation/uninstall and checks cleanup. No cleanup is admitted before root and upload-directory ownership are confirmed. After admission, failure cleanup removes only that upload and retains server config, backup/revoke/recovery artifacts. Final artifact deletion follows both successful verified uninstalls and the initially absent metadata check. Do not run it on production or user-managed WireGuard hosts.

C9 checks JSON object/Boolean success, supported clean-target detection/planning, running `wg0`, exact saved/live peer membership after every mutation, backup shape/revoked count and both cycles' uninstall postconditions. Uninstall independently checks config/symlink and native-interface absence, requested named-package purge or kept tools, then final upload/Tunnl-directory absence. Unknown observations, malformed/extra/missing peers, partial package residue and cleanup failure return failure. Logs contain fixed local progress/category/exit text; remote fields, command arguments, streams, JSON and exception payloads are omitted. These checks observe interface/config/peer/package state, not traffic, handshake or Android integration.

Run the standard-library injected offline controls without VPS variables or network/root operations:

```sh
python3 -m unittest discover -s scripts/tests -p test_vps_integration_test.py -v
```

The suite covers valid purge/keep-packages flows, false/unknown/malformed postconditions, exact saved/live membership, direct removal/package checks, key-only options, guard/root/upload ownership, payload-free command/JSON/nested-output failures and primary/cleanup failure preservation. See [executed proof](development.md#c9-offline-harness-postconditions--2026-10-09). This separate offline command is not wired into the default Android or destructive integration tasks. Real OpenSSH/systemd/package/packet proof still requires its own authorized disposable target.

Required environment:

```sh
TUNNL_VPS_HOST=203.0.113.10
TUNNL_VPS_USER=root
TUNNL_VPS_DESTRUCTIVE=1
```

Optional environment:

```sh
TUNNL_VPS_SSH_KEY=/path/to/private/key
TUNNL_VPS_PORT=22
TUNNL_VPS_WG_PORT=51820
TUNNL_VPS_CIDR=10.88.0.0/24
TUNNL_VPS_DNS="1.1.1.1, 1.0.0.1"
TUNNL_VPS_PURGE=1
TUNNL_PYTHON=python3
```

Run from a shell that has key-based SSH access to the VPS:

The examples show operator commands, not authorization to execute them. `.tunnl-vps-test.env` is ignored but neither task nor harness loads it automatically; export settings explicitly. OpenSSH uses `StrictHostKeyChecking=accept-new`, accepting a first-seen key rather than checking an app-pinned fingerprint.

```sh
TUNNL_VPS_HOST=203.0.113.10 \
TUNNL_VPS_USER=root \
TUNNL_VPS_DESTRUCTIVE=1 \
./gradlew vpsIntegrationTest
```

On Windows, if the key is available to Windows OpenSSH but not WSL, run the Python harness directly from PowerShell:

```powershell
$env:TUNNL_VPS_HOST = '203.0.113.10'
$env:TUNNL_VPS_USER = 'root'
$env:TUNNL_VPS_DESTRUCTIVE = '1'
python scripts/vps_integration_test.py
```

With default `TUNNL_VPS_PURGE=1`, successful completion requires `wireGuardInstalled=false`, `configExists=false`, absent `wg0`/Tunnl/upload paths and absence/not-installed status for the three purged packages. `TUNNL_VPS_PURGE=0` requires `packagesPurged=false` and retained `wg` availability while still checking config/interface/artifact removal. Final success prints `cleanup: uploaded scripts and Tunnl test artifacts removed` only after absence checks pass. Cleanup failure is reported separately and keeps the primary failure; inspect retained state before another authorized run.

Security rules:

- Use key-only SSH; the harness disables password authentication.
- Never commit server passwords, private keys, generated peer configs, or local env files.
- Rotate temporary root credentials after any manual password-based setup.
- Keep `.tunnl-vps-test.env` local and ignored if you use it to store non-secret test host settings.

### Phase 5 — Expand Beyond VPS Fleet

- Add regression coverage for non-VPS home, detail, editor, log viewer, and settings flows.
- Cover remaining redesign screens as they are implemented: diagnostics, on-demand, QR scan, more sheet, and share sheet.
- Keep whole-app navigation smoke tests separate from provisioning-specific tests so failures identify the affected surface quickly.

## Remaining Automation Work

- Add Robolectric fragment tests for `VpsProvisioningFragment`.
- Add data-binding view tests for tunnel rows and peer cards.
- Choose screenshot test framework and add baseline fixtures.
- Add Espresso smoke suite for navigation.
- Add remote shell timeout/cancellation tests when the concrete SSH transport lands.
- Add real-library SSH tests; `GuardedRemoteShellClientTest` covers fake-wrapper execute timeout/I/O/cancellation propagation only.
- Add unreachable-server UI error tests when provisioning errors are wired to screen state.
- Add additional concurrent `VpsServerStore` mutation tests only when new persisted fields or cross-record invariants are introduced.
- Add dark-mode and RTL screenshot goldens.
- Run `vpsIntegrationTest` against a disposable VPS after provisioning script changes.

## Pending Until Feature Work Exists

These are required regression tests, but they must not be counted as active coverage until the underlying feature is implemented.

- Host fingerprint mismatch blocks connection after SSH transport and fingerprint persistence exist.
- `trustHost=false` avoids fingerprint persistence after host-key storage exists.
- Password/session secret is cleared by the real credential store after Keystore-backed storage exists.
- Rotation preserves VPS wizard step and form state after saved-state support is implemented.
- Generated peer config imports into the tunnel manager after add-peer import wiring exists.

## Native warm-up source and physical acceptance

The shared #68 repair merged through PR #69 at `6bb17579`. Native warm-up's existing overlay includes actual Android descriptor guards; host-only aliases and virtual tests exercise both single-family cases, denied protection, unopened and closed sockets through `androidbind.ProtectSockets`. These controls do not use fake descriptor methods. Only their host `conn` command disables Go 1.24.3's module index to include virtual test files; production overlays/builds keep normal settings. Reused `GoBackendStatisticsTest` adds the shared DOWN, service cleanup and delayed-result regressions alongside the native lifecycle suite.

The host race suite covers protected readiness at creation/rebind before concurrent cookie sends, both/one/no available families, failed protection cleanup, explicit/absent/zero port intent, requested-port retry recovery, mark-failure closure, one best-effort one-byte zero attempt per eligible initiation, expected I/O versus cancellation/closure/defect handling, destruction between sends, close/replacement generation isolation, terminal shutdown and concurrent teardown. Warm-up is enabled only by explicit nonzero ListenPort and leaves the WireGuard protocol unchanged. An ordinary `StdNetBind` peer exchanges authenticated traffic in both directions over real localhost UDP after warm-up; both peers use the globally overlaid device package. This does not establish separately compiled unmodified-peer interoperability. The actual handle owner covers a partial UAPI request blocking a query during teardown, draining admitted reads, duplicate close, late lookup and non-reused IDs. Android-side shadows verify disconnect/service cleanup can cancel a blocked statistics query and that replacement invalidates delayed results. Overlay tests reject missing/duplicate pinned-source matches and verify the three inspected cached upstream source files are unchanged; module download may populate an empty cache. JNI host tests attach/detach worker callbacks, clear startup-thread ownership at the boundary, preserve protection denial/destruction, retain unexpected Error identity and preserve interruption.

Host/JVM/native compilation do not establish Android VPN protection, socket accounting, wake timing or a kernel server. On 2026-10-08 (Europe/Paris), the maintainer authorized adoption through PR #63. PR #63 records exact-head ultra review/checks and actual delivery state. Separately authorized phone acceptance must cover startup, stop/restart, service destruction, network change/rebind, deep-sleep/resume and unchanged-server traffic while preserving profiles/APKs. Freeze repeated unchanged-build cellular control, fixed profile/path, success/failure observations and rejection criteria before DPI trials. Preserve the retained baseline for recovery and keep inconclusive benefit unclaimed. Source adoption and source tests do not close remaining physical, unchanged-server or DPI acceptance; #61 stays open and G4/#47/#48 remain separate. No DPI, speed or battery gain is established.

The [forced deep Doze record](reviews/2026-10-07-protocol-preflight-experiment.md#forced-deep-doze-recovery--2026-10-07) covers one 195-second Pixel 10a/API 36 idle-and-wake trial on the installed experimental APK. For an authorized repeat, record the initial idle/battery/screen state, VPN UID coverage and underlying path. Simulate unplugging, turn the screen off and allow its state to settle before forcing deep idle. Confirm `mState=IDLE`, `mForceIdle=true`, `mScreenOn=false` and `mCharging=false` at entry and before wake; run no traffic probes during the hold. Exit forced idle, wake without toggling the tunnel, then check IP traffic, DNS, VPN ownership and persisted-file preservation. Always clear forced idle and reset battery simulation, verifying both restoration outcomes. Retain failed setup attempts separately from product failures. Forced Doze over USB does not prove hardware suspend, a new handshake, warm-up emission, rebind or DPI benefit; packet/timing acceptance needs its own evidence.

The maintainer's 2026-10-08 report of sleep/wake recovery without manual action is a reported observation, not a new independently measured trial. It does not add hardware-suspend, warm-up capture, rebind or unchanged-server/DPI proof to the earlier forced Doze evidence. Merge authorization applies to source adoption; #61's remaining acceptance stays open.
