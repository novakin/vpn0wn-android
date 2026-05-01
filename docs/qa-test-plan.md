# Tunnl QA Test Plan

This document tracks the automated and manual QA coverage required for the Tunnl Android redesign.

## Test Layers

### JVM Unit Tests

Use for deterministic logic that does not require a device.

- Provisioning JSON parsing:
  - `detect`, `plan`, `install`, `sync-status`, `add-peer`, `remove-peer`, `revoke-peer`, `backup`, `uninstall`
  - invalid JSON
  - `ok:false` error payloads
- Provisioning command failures:
  - exit `0`
  - nonzero with stderr
  - nonzero with stdout fallback
  - nonzero with empty output
- Remote shell execution boundaries:
  - command timeout when SSH connects but the process never exits
  - coroutine cancellation closes the remote command/session
  - unreachable server maps to a typed provisioning failure, not a generic crash
- Provisioning request args:
  - `VpsInstallRequest.installArgs()`
  - optional `--force`
  - `VpsAddPeerRequest.args()` with and without DNS
  - shell quoting is covered at the command-construction boundary, not deferred as "handled later"
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

Use Paparazzi, Shot, or an equivalent renderer once selected. Screenshots should use fixed data and fixed font scale.

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
- Private keys are not copied through generic copy handlers.
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

## Initial Implemented Coverage

- `ProvisioningJsonTest`
- `ProvisioningCommandOutputTest`
- `GuardedRemoteShellClientTest`
- `VpsDeployMapperTest`
- `VpsProvisioningRequestTest`
- `VpsServerStoreTest`
- `BindingAdaptersTest`
- `QuantityFormatterTest`

## Next Implementation Roadmap

This roadmap covers the whole app, not only the VPS fleet. Do not move items from this section into active coverage until the tests exist and run in the expected test layer.

### Phase 1 — Stabilize Current Baseline

- Keep runtime fixes and QA test additions separated by branch or commit so regressions can be bisected cleanly.
- Run `:ui:testDebugUnitTest` after test or runtime changes that affect app logic.
- Run full `:ui:assembleDebug` only when explicitly requested, per project build policy.
- Treat remaining untested behavior as residual regression risk; the current suite reduces risk but does not prove no regressions exist.

### Phase 2 — Complete Missing Automated Coverage

- Add timeout and hung-command tests for SSH sessions that connect but never complete.
- Add cancellation tests that verify coroutine cancellation closes the remote command/session.
- Add unreachable-server tests that map connection failure to a typed provisioning error and render the expected UI state.
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
- Always document that the VM path may remove WireGuard and Tunnl-managed config from the target host.
- Use key-only SSH. Do not put passwords or private keys in the repo; optional local settings belong in ignored `.tunnl-vps-test.env`.

## Disposable VPS Integration Use Case

Use `vpsIntegrationTest` when provisioning scripts or app-side provisioning command contracts change and unit tests are not enough to prove the scripts still work on a real server.

This test is for a disposable Ubuntu/Debian VPS only. It logs in as a privileged SSH user, uploads the local `third_party/wireguard-install/tunnl/` scripts, installs WireGuard, creates and removes test peers, backs up state, uninstalls WireGuard, reinstalls it, uninstalls it again, and removes Tunnl test artifacts. It may remove `/etc/wireguard` when only Tunnl test artifacts remain. Do not run it on a production VPN host or any server with user-managed WireGuard configuration.

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

Expected success output includes `detect-final` with `wireGuardInstalled=false` and `configExists=false`, followed by `cleanup-state` showing `wg-absent` and `etc-wireguard-absent`.

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
- Add concrete SSH transport tests against the selected SSH library; current guard-level timeout/unreachable/cancellation behavior is covered by `GuardedRemoteShellClientTest`.
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
