# Tunnl QA Test Plan

Last updated: 2026-10-06 (Europe/Paris).

This document tracks the automated and manual QA coverage required for the Tunnl Android redesign.

The layer lists below are target requirements, not a passing coverage report. [Source-inspected coverage](#source-inspected-coverage) inventories current source, including explicitly identified pending changes; [development evidence](development.md#recorded-verification) records actual executions and their scope. The original documentation audit at `11d4a9b1` ran no runtime suites. #13 adds focused detail-fragment lifecycle tests; broader rendered-row, instrumented navigation and screenshot suites remain absent.

## Test Layers

The [2026-10-06 phone follow-up](development.md#phone-ui-and-throughput-follow-up--2026-10-06) records a scoped physical Android 16 run. It does not install an Espresso/screenshot framework or replace the full support matrix below. Keep captures local: real profiles can expose private metadata even with secret fields masked. Before automation, confirm the current package/screen and protect unsaved user drafts; use synthetic inputs for writes. Restore temporary device settings and per-app locales after checks.

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

Required before merging UI or backend provisioning work.

- Import a WireGuard config.
- Toggle tunnel up/down.
- Visit detail while live stats update.
- Open logs, filter logs, share logs.
- Rotate device on home/detail/editor/VPS wizard.
- Background and foreground the app while a tunnel is up.
- Delete a tunnel.
- Exercise Android VPN permission flow on a clean install.

## Source-inspected Coverage

The phone presentation, Creator result and Snackbar tint suites are described under [JVM unit tests](#jvm-unit-tests); they are included in the inventory below.

Source contains 34 test classes and 210 test methods: UI 31/194, tunnel 3/16, including merged #14/#13/#16/#15, the merged #22 suites, the phone follow-up, #33 log recovery, merged #34 Settings source and #35 Add/peer source. The shared TunnelTestEnvironment fixture is not a test suite. This is an inventory, not an execution total. Guard tests use fake execute calls for timeout, I/O failure and cancellation propagation; they do not prove an entered SSH command, upload timeout or actual channel/session closure. Robolectric also covers detail-fragment selection/destruction, kernel-preference failure recovery and bounded Settings/Add/peer navigation/rendering; whole-app/device proof remains separate.

`TunnelManagerTest` covers admitted/queued transitions, deletion/config failure recovery, cancelled initialization, stale state snapshots/native notifications, restoration intent and backend-switch admission/rollback. `ApplicationBackendLifecycleTest` proves cancellation before initializer execution settles backend waiters. `TunnelDetailLifecycleTest` covers reversed/cleared config selections, delayed peer statistics after selection/destruction, disconnect clearing without waiting for an in-flight fetch and failure clearing. `KernelModuleEnablerPreferenceTest` checks failed shutdown restores its setting/control. `GoBackendLifecycleTest` covers prompt destruction while its lock is held, dead-service rejection/future replacement, cleanup identity/callback ordering and preflight compatibility/protection/interruption. Synthetic owner/native-call shadows exercise no successful native activation, real VPN traffic, process exit or device lifecycle. See [original lifecycle evidence](development.md#tunnel-lifecycle-and-transition-settlement--2026-10-06) and [peer follow-up](development.md#add-sheet-and-peer-presentation-recovery--2026-10-06).

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
- `ZipExporterPreferenceTest` — failed writes retain the primary failure and thrown cleanup error; loading cancellation settles children and restores controls without success. No saver-acquisition, zero-delete, interrupted blocking-write or device/provider cleanup proof; #28 owns remaining Downloads boundaries.
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
