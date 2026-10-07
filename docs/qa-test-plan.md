# Tunnl QA Test Plan

Last updated: 2026-10-07 (Europe/Paris).

This document tracks the automated and manual QA coverage required for the Tunnl Android redesign.

The layer lists below are target requirements, not a passing coverage report. [Source-inspected coverage](#source-inspected-coverage) inventories current source, including explicitly identified pending changes; [development evidence](development.md#recorded-verification) records actual executions and their scope. The original documentation audit at `11d4a9b1` ran no runtime suites. #13 adds focused detail-fragment lifecycle tests; broader rendered-row, instrumented navigation and screenshot suites remain absent.

## Test Layers

The [2026-10-06 phone follow-up](development.md#phone-ui-and-throughput-follow-up--2026-10-06) records a scoped physical Android 16 run. It does not install an Espresso/screenshot framework or replace the full support matrix below. Keep captures local: real profiles can expose private metadata even with secret fields masked. Before automation, confirm the current package/screen and protect unsaved user drafts; use synthetic inputs for writes. Restore temporary device settings and per-app locales after checks.

### CI coverage and APK boundary

The [Android workflow](../.github/workflows/android.yml) executes all UI/tunnel debug unit tests and Detekt on the actual PR head, `master` pushes and manual dispatch. Check the final-head run and XML/Detekt reports; a declared workflow or old passing revision is insufficient. Reports expire after seven days and may be unavailable after setup failure. The [development guide](development.md#android-ci-and-debug-apk-artifacts) owns toolchain, cancellation, artifact provenance and signing/install instructions.

PR/push runs and default manual dispatch do not assemble an APK. Explicit `build_apk=true` dispatch runs checks before debug assembly/upload; authorized artifact/install execution is recorded in the [G4 investigation](reviews/2026-10-07-g4-preflight-investigation.md) and [candidate-device follow-up](reviews/2026-10-07-g4-candidate-device-review.md). CI does not run instrumented/device, benchmark, native VPN/root or live/destructive VPS checks. It does not establish the support matrix below or configure branch protection. Keep #28/#34/#35/#36 physical acceptance open independently of CI success.

### Host native Go tests

Run `bash tunnel/tools/libwg-go/test-host.sh` in the configured Linux/macOS/WSL native toolchain. The script prepares pinned Go 1.24.3 through the existing Makefile and runs `go test -race -count=1 -timeout=90s ./uapiserver`. Controls cover idle accepted clients, complete handler drain, partial requests through the real pinned WireGuard UAPI handler and concurrent admission/repeated close. CI runs this host suite in addition to JVM/static checks. It does not compile Android native ABIs, assemble an APK or establish device/network behavior. For build-owner changes, separately compile the affected Android ABIs and verify an incremental source edit changes the library without APK assembly; [executed #64 proof](development.md#native-uapi-lifecycle-and-incremental-builds--2026-10-07) retains its original scope.

### JVM Unit Tests

The #33 log recovery adds `LogLineParserTest` (threadtime/UID, continuation/malformed headers, dates and severity sets), `LogcatStreamTest` (sparse/continuous deadlines, bounded bursts/EOF, blocked/full-channel cancellation, destroy-induced I/O masking and genuine I/O/programming failure), `LogBufferTest` (filtered/unfiltered eviction, oversized batches, continuation character bounds, snapshots and refresh) and Robolectric `LogViewerPublicationTest`/`LogExportTest` (stale generation, actual adapter notifications, overlapping share ownership/revocation, UTF-8, write/close I/O and cancellation/defect propagation). Stream fixtures use synthetic text; a native `/bin/sh` process control is skipped when that executable is absent. [Executed evidence and limits](development.md#log-pipeline-recovery--2026-10-06) distinguish these controls from Android logcat, actual pipe/chooser behavior and rendered-screen proof.

Use for deterministic logic that does not require a device.

`TunnelPresentationTest` exercises manager-resolved pending UP/DOWN, observer ordering during statistics clearing, failed-disconnect restoration, burst/idle/burst rates, repeated snapshot reads, minimum sample interval, counter rollback and cleared-statistics reconnect baselines. `TunnelEditorHeaderTest` exercises the actual labeled Save click, actual Creator RESULT_OK/message publication on finish and failed-save preservation. The caller's activity-result/Snackbar path is source reviewed. `TunnlSnackbarTest` injects a contrasting Material tint and asserts the actual Material-owned tint is cleared; the negative control fails when the production clear is removed. These controls use synthetic state/configuration and do not establish native VPN traffic or rendered/keyboard layout.

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

- `SettingsActivityTest`: native preference groups after API/admin/backend filtering, pending-backend header hiding, kernel action/root-probe eligibility, unchanged switch keys/defaults and values across recreation, log/Back navigation and a single restored fragment. A synthetic tile-success callback removes only the tile and leaves sections expanded; a binding control verifies the native WireGuard artwork has no generic row tint. Insets exercise all four sides, repeated dispatch, consumed system bars/cutouts and retained IME insets. Native Robolectric raster previews exercise 320dp English portrait and 2× Arabic RTL text under both night configurations, plus 640dp landscape in night mode; inspect previews locally. These controls use synthetic preferences and shadowed kernel/tools/tile checks, with no root, VPN, APK install, real tile request or export picker. Physical system bars, TalkBack and the full support matrix remain pending; see [Settings recovery evidence](development.md#settings-presentation-recovery--2026-10-06).

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

## Transfer-speed and battery validation

[G4/#52](https://github.com/novakin/wireguard-android/issues/52) tracks final validation for the [active enhancement/performance goal](improvement-plan.md#active-enhancement-and-performance-goal). The [initial G4 record](reviews/2026-10-07-g4-preflight-investigation.md) establishes authorized CI artifact/install proof and bounded incident observations; the [repeated network record](reviews/2026-10-07-g4-network-baselines.md) owns subsequent path measurements and their limits. #28/#34/#35/#36 physical acceptance, clean Wi-Fi context, controlled energy baselines and demonstrated optimization remain incomplete. Issues own the selected device/endpoint, primary metric, numeric target and guardrails after baseline measurement. APK assembly/install and a real benchmark target need their own explicit authorization. Start with Wi-Fi; mobile-data and root/kernel coverage are conditional on the authorized environment.

The [candidate-device follow-up](reviews/2026-10-07-g4-candidate-device-review.md) adds #55 warning confirmation and bounded Add/Home action/output proof on source `4e2f473d`. It preserves personal config/preferences and restores checked device settings; the changed Android runtime marker is identified separately. TalkBack, remaining peer/Settings/provider boundaries and complete physical acceptance stay open. The phone used LTE while charging; no speed/energy baseline was attempted.

Record both baseline and candidate app revisions. Use the same phone, Android version, build type, test endpoint, network, backend and synthetic test profile for a paired comparison. Record signal/network quality, thermal state, charging state, foreground/screen state, MTU, routes, keepalive and background load. Keep private profiles/raw device reports local; commit only sanitized methodology and aggregates. Use existing Android profiling and benchmark tools rather than creating a performance framework.

Confirm whether the Wi-Fi router already uses VPN before calling app-off traffic direct. App-off/app-on measurements behind that router compare router-only and nested VPN paths; they are not clean direct Internet or upstream phone-handshake/DPI proof. For connectivity diagnosis, use an authorized cellular path with Wi-Fi disabled and the current default network verified, or an authorized Wi-Fi path without the router VPN. Record mobile-data approval/limit, establish the test source before restricting the temporary listener and verify the expected server-observed source in each successful exchange. Preserve historical raw filenames while correcting their interpretation in sanitized aggregates. Restore each session's fresh starting state and verify personal file hashes and temporary-device/server cleanup.

For foreground/battery comparisons, verify actual animation behavior and effective system scale before measuring; a stored setting alone is insufficient. Record whether each temporarily changed Android setting existed, and restore an absent setting by deletion rather than writing the string `null`. If the maintainer resumes use during cleanup and the desired final tunnel state is unclear, confirm it before further toggles. Distinguish temporary running-state preference changes from personal config/settings edits; report final preservation and any failed automatic restoration separately. Do not copy a stale archive over current data or classify a failed cleanup command as successful because its load trials passed or later recovery succeeded.

Verify the profile again after interruptions or user-reported recovery. A changed ListenPort invalidates a fixed-profile comparison; preserve the new value and separate the runs. `UDP preflight sent` and active Android VPN-service state do not prove endpoint delivery or sustained health. A failed/incomplete iperf run is failure evidence, not a throughput baseline. Separate capped diagnostic traffic from uncapped baseline scenarios and stop traffic when the maintainer reports the path blocked. Obtain a restored authorized path and confirm its state before resuming the repeated protocol.

The [G2 source investigation](reviews/2026-10-07-performance-source-investigation.md) proposes #47 as the single battery candidate and retains current transfer code until G4 proves a bottleneck. Include covered single-pane Home, visible two-pane Home/Detail and hidden attached animations in the baseline; resumed/attached state does not establish actual visibility. #48's shared-read/late-publication defect is separately tracked. Four temporary characterization controls establish source behavior, not permanent suite coverage, device frame frequency or energy/throughput benefit.

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

Source contains 36 test classes and 246 test methods: UI 33/230, tunnel 3/16, including merged #14/#13/#16/#15, the merged #22 suites, the phone follow-up, #33 log recovery, merged #34 Settings source, #35 Add/peer source, #36 Home source, #28 Downloads ownership, #55 debug-warning controls and the Settings structure-ID regression. The shared TunnelTestEnvironment fixture is not a test suite. This is an inventory, not an execution total. Guard tests use fake execute calls for timeout, I/O failure and cancellation propagation; they do not prove an entered SSH command, upload timeout or actual channel/session closure. Robolectric also covers detail-fragment selection/destruction, kernel-preference failure recovery and bounded Settings/Add/peer/Home navigation/rendering; whole-app/device proof remains separate.

`TunnelManagerTest` covers admitted/queued transitions, deletion/config failure recovery, cancelled initialization, stale state snapshots/native notifications, restoration intent and backend-switch admission/rollback. `ApplicationBackendLifecycleTest` proves cancellation before initializer execution settles backend waiters. `TunnelDetailLifecycleTest` covers reversed/cleared config selections, delayed peer statistics after selection/destruction, disconnect clearing without waiting for an in-flight fetch and failure clearing. `KernelModuleEnablerPreferenceTest` checks failed shutdown restores its setting/control. `GoBackendLifecycleTest` covers prompt destruction while its lock is held, dead-service rejection/future replacement, cleanup identity/callback ordering and preflight compatibility/protection/interruption. Synthetic owner/native-call shadows exercise no successful native activation, real VPN traffic, process exit or device lifecycle. See [original lifecycle evidence](development.md#tunnel-lifecycle-and-transition-settlement--2026-10-06) and [peer follow-up](development.md#add-sheet-and-peer-presentation-recovery--2026-10-06).

The [#37 compatibility review](reviews/2026-10-07-udp-compatibility-review.md) retains the merged backend and rejects the preserved mandatory/random-port experiment. Its eight existing controls were rerun for that decision; bind/send exhaustion and DNS failure/sleep interruption remain source traced rather than injected. Native port/NAT handoff, successful activation, packet traffic and reconnect need separate physical proof. No speed/battery benefit is established by retaining source.

[#55 controls](reviews/2026-10-07-g4-settings-add-debug-review.md) observe preflight tag 0 at socket protection and restoration of tagged/untagged caller state after rejected protection and a successful one-byte loopback send. Settings records whether its kernel-support probe runs on Main and retains the existing preference eligibility controls. These JVM tests do not certify native socket accounting or candidate-APK StrictMode output. The same scoped record covers actual Settings/Add phone captures across portrait, landscape, system light-mode, 200% text and Arabic RTL, with unchanged app files and restored checked settings. Its physical acceptance limits retain their original scope; no repeated performance result is added.

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
- `SettingsActivityTest` — #34 native preferences, recreation, scoped navigation/insets and native raster layout controls described above.
- `AddPeerPresentationTest` — #35 source actions/accessibility, peer snapshots and native content layout controls described above.
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
- Repaired mutations reject unmanaged config, but harness cleanup remains broad and destructive; C9 still owns its safety/evidence limits.
- Use key-only SSH. Do not put passwords or private keys in the repo; optional local settings belong in ignored `.tunnl-vps-test.env`.

## Disposable VPS Integration Use Case

Use `vpsIntegrationTest` when provisioning scripts or app-side provisioning command contracts change and unit tests are not enough to prove the scripts still work on a real server.

This harness is for an explicitly authorized disposable Ubuntu/Debian VPS only. It uses system OpenSSH/scp and requires an actual root session (`id -u`), not Android transport or sudo. It uploads scripts, forces installation, creates/revokes/removes peers, receives a secret-bearing backup response, uninstalls, repeats installation/uninstall and attempts cleanup. C2–C3 script mutations reject unmanaged config, but harness cleanup still recursively removes `/etc/wireguard/tunnl` and removes `/etc/wireguard` only if empty. Do not run it on production or user-managed WireGuard hosts.

Current `run_json` checks parseability and `ok:true`; it logs most outcome fields without asserting running health, exact peer removal, final config absence or requested package purge. Failure paths can print raw command/stdout/stderr or malformed JSON snippets despite success-payload redaction. C9 must repair postcondition assertions and failure diagnostics with synthetic failures before another live run. A successful exit does not currently prove those postconditions or Android integration.

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

With default `TUNNL_VPS_PURGE=1` on a clean disposable target, intended final observations are `wireGuardInstalled=false`, `configExists=false`, `wg-absent` and `etc-wireguard-absent`. With purge disabled, packages may remain. C9 must assert these postconditions; current printed output does not guarantee them.

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
