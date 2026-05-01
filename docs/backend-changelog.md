# Backend & Functional Changelog

Tracks non-UI changes: backend logic, data layer, permissions, networking, build config, and any functional behavior changes. Separate from `ui-redesign-roadmap.md` which covers visual/layout work only.

---

## In Progress

- **Android SSH provisioning integration**
  Server-side Tunnl provisioning scripts now exist under `third_party/wireguard-install/tunnl/`, and the VPS fleet now reads persisted non-secret records from `VpsServerStore`. Remaining work: concrete Android SSH transport, SSH credential capture/storage, remote script upload/execution, progress parsing, live server status persistence, and user-management flow wiring.

- **Provisioning backend QA blockers**
  Concrete SSH transport must be selected before live provisioning is considered stable. The remote-shell boundary now has timeout, hung-command, cancellation, and unreachable-host guard coverage through `GuardedRemoteShellClient`, but the real SSH implementation still needs the same behavior verified against its library/session API. Disposable VPS integration testing must be opt-in and destructive-only: it may install WireGuard, change Tunnl-managed server config, remove peers, uninstall WireGuard, and reinstall it on the target VM. These tests must never run in the default unit-test or local-build path.

- **Disposable VPS integration task**
  Root Gradle task `vpsIntegrationTest` runs `scripts/vps_integration_test.py` through key-only SSH when `TUNNL_VPS_HOST`, `TUNNL_VPS_USER`, and `TUNNL_VPS_DESTRUCTIVE=1` are set. The task uploads the Tunnl provisioning scripts to a temporary directory, runs detect/plan/install/sync/add-peer/revoke/remove/backup/uninstall/reinstall/uninstall, and removes uploaded scripts plus Tunnl test artifacts. It is not wired into `check` or any default build.

- **Disposable VPS integration use case**
  `docs/qa-test-plan.md` documents when and how to run `vpsIntegrationTest`, including required environment variables, Windows-vs-WSL key handling, expected cleanup output, and credential-safety rules. The documented target is a disposable Ubuntu/Debian VPS only; production VPN hosts and hosts with user-managed WireGuard configuration are explicitly out of scope.

- **Android SSH transport decision**
  A concrete Android SSH transport is still not implemented because the app currently has no SSH client dependency. The next backend decision is to choose and review a maintained Android-compatible SSH library, then implement `RemoteShellClientFactory` on top of it with host-key pinning, password/private-key auth, per-command timeout, cancellation that closes the active channel/session, and no credential logging.

### VPS provisioning implementation task list

- [x] Create dedicated `vps-configuration` branch.
- [x] Vendor pinned MIT upstream reference and Tunnl-owned server scripts.
- [x] Package Tunnl provisioning scripts into Android assets.
- [x] Add app-side provisioning domain models and JSON parsers.
- [x] Add remote shell abstraction for future SSH implementation.
- [x] Add provisioning client that uploads scripts, executes commands, and parses JSON results.
- [ ] Add concrete Android SSH transport.
- [x] Add timeout, hung-command, cancellation, and unreachable-host guard tests at the remote-shell boundary.
- [ ] Add timeout, hung-command, cancellation, and unreachable-host tests for the concrete SSH transport library/session implementation.
- [ ] Store SSH host fingerprints and encrypted private key/password material with Android Keystore.
- [x] Persist non-secret server fleet records locally.
- [x] Wire persisted non-secret server records into the server fleet UI.
- [ ] Wire live provision/status/add-peer/remove-peer/revoke flows into the server fleet UI.
- [x] Add native server fleet UI entry point and seeded UX flow.
- [x] Move native VPS UI display strings into Android resources and add French translations.
- [x] Extract VPS seed data and custom renderers out of the main fragment.
- [x] Remove prototype VPS seed data from runtime code.
- [ ] Import generated peer config into the existing tunnel manager.
- [ ] Add progress streaming from remote command stderr/stdout to the provisioning terminal UI.
- [x] Add opt-in destructive end-to-end test path against a disposable Ubuntu/Debian VPS.

## Done

### VPS provisioning

- **`third_party/wireguard-install` — vendored upstream reference**
  Added `Nyr/wireguard-install` from commit `3b85b2702ffcb2ce670c29bd90c6cae4a5bcc753` with MIT license attribution. The upstream interactive script is stored unchanged under `original/wireguard-install.sh`; `UPSTREAM.md` documents source, license, pinning, and update process.

- **`third_party/wireguard-install/tunnl` — non-interactive command surface**
  Added Tunnl-owned Bash commands for `detect`, `plan`, `install`, `add-peer`, `remove-peer`, `revoke-peer`, `sync-status`, `backup`, and `uninstall`. The MVP supports Ubuntu/Debian, `wg0`, IPv4 `/24` pools, IPv4-only peer default routes, JSON stdout for app parsing, explicit non-interactive arguments, and iptables NAT. `uninstall.sh` removes Tunnl-managed config without disabling global IPv4 forwarding.

- **`ui/build.gradle.kts` — package provisioning scripts as app assets**
  Added `third_party/wireguard-install/tunnl` as a main asset source so the Android app can upload the exact Tunnl-owned script bundle to a VPS over SSH.

- **`ui/src/main/java/com/wireguard/android/provisioning` — app-side provisioning core**
  Added typed models, JSON parsing, script bundle loading, non-secret server fleet persistence, a remote shell interface, guarded execution wrappers, and a `TunnlProvisioningClient` that uploads scripts to a per-run remote temp directory before running `detect`, `plan`, `install`, `add-peer`, `remove-peer`, `revoke-peer`, `sync-status`, `backup`, and `uninstall`.

- **`VpsProvisioningFragment` — native server fleet flow**
  Added the Android server fleet entry point, persisted non-secret server rendering through `VpsDeployMapper`, the SSH wizard UI, translated VPS flow strings, and custom deploy widgets in `VpsDeployViews`. Runtime prototype seed data was removed from production code; the fleet now renders stored records and empty states until concrete SSH transport and live actions are connected.

- **Provisioning safety hardening**
  User-facing provisioning exceptions no longer include raw command stdout/stderr or malformed JSON snippets. `VpsServerStore` clamps corrupted SSH ports and drops stored server records missing required connection fields. `VpsCredential.Password` stores password material in a clearable `CharArray`, and the VPS provisioning form clears retained password buffers before reset/replacement. `GuardedRemoteShellClientFactory` rejects untrusted-host connections without a pinned host fingerprint and invalid auth-attempt limits.

### VpnService stale-reference mitigation

- **`GoBackend.setStateInternal` — recover from VpnService killed without `onDestroy`**
  When Android kills the `VpnService` during idle/memory pressure without firing `onDestroy`, `vpnServiceRef` can remain completed with a dead service reference. Subsequent `setState(UP)` calls then skip `startService` because the future is already done, obtain the dead service, and may see `builder.establish()` return `null`, causing repeated `TUN_CREATION_ERROR` until process/device restart. Mitigation: on `establish() == null`, reset the stale `vpnServiceRef` to a new incomplete future and call `startService` immediately, so the next connect attempt can obtain a fresh service.

- **`GoBackend.resetVpnServiceFuture` — race-safe stale future reset**
  The `establish() == null` recovery compares against the exact `CompletableFuture<VpnService>` used for the failed connection attempt before replacing it. This avoids overwriting a newer service future if Android restarts the `VpnService` concurrently. The failed attempt still throws `TUN_CREATION_ERROR`; the next attempt should use the refreshed service reference.

- **`GoBackend.VpnService.onDestroy` — exact-service CAS reset**
  `onDestroy()` now resets `vpnServiceRef` only when the current completed future resolves to the service instance being destroyed. This avoids replacing a newer service future if Android starts another `VpnService` while the old instance is tearing down.

- **`GoBackend` — diagnostic logging for service lifecycle failures**
  Added diagnostic log points for stale service recovery, failed `protect()` preflight, `establish() == null`, and `VpnService.onDestroy()` so this failure can be confirmed from the in-app log viewer.

### Statistics

- **`ObservableTunnel` — per-second RX/TX rate deltas**
  Home and detail screens now refresh from `ObservableTunnel` state/statistics callbacks and request snapshots for visible UP tunnels. Prior to this redesign work, home-screen stats only updated after visiting the detail screen.

- **`BindingAdapters` — stat formatters**
  Added: `tunnelRx`, `tunnelTx`, `tunnelRxRate`, `tunnelTxRate`, `tunnelHandshake`, `tunnelCipher`, `tunnelRoute`, `tunnelPeerBadge`, `shortKey`.
  - `tunnelRxRate` / `tunnelTxRate`: compute per-second delta from previous snapshot stored on `ObservableTunnel`; display as `KB/s` / `MB/s`.
  - `shortKey(key)`: truncates base64 keys to `wOs2…BVkA` (6 chars + ellipsis + 6 chars).

### Binding Adapters

- **`topologyConfig` binding adapter** (`BindingAdapters.setTopologyConfig`)
  Reads first interface address and first peer endpoint from `Config`; pushes to `TunnelThroughputView` so the detail-screen wire diagram shows real tunnel data instead of placeholders.

- **`isConnecting` binding adapter** (`BindingAdapters.setIsConnecting`)
  Passes `tunnel.state == State.TOGGLE` to `TunnelThroughputView`; triggers warn-color connecting animation.

### Runtime hardening

- **Binding and log safety pass**
  `BindingAdapters.tunnelMode()` now synchronizes all access to the `connectingSince` `WeakHashMap`, home header/protection adapters share a single-pass tunnel mode aggregate, home exit labels avoid `InetEndpoint.resolved` DNS lookups on the main thread, and frequently updated status dot/rail drawables are reused through view tags. `LogViewerActivity` now marks `logProcess` volatile and revokes shared-log byte buffers with an explicit URI key guard.

- **Formatter and binding cleanup**
  `QuantityFormatter.formatBytes()` now uses integer scaling before formatting instead of chained floating-point division. `QuantityFormatter.formatEpochAgo()` now pads day counts consistently. Tunnel status/detail label mappings are centralized in `BindingAdapters`, and the single-tunnel mode helper snapshots state/statistics before computing status.

- **Tunnel stats refresh lifecycle hardening**
  `TunnelListFragment` and `TunnelDetailFragment` now combine `ObservableTunnel` state/list observer wiring with lifecycle-bound 1 s refresh jobs. Visible/selected UP tunnels keep receiving fresh snapshots for live rates and handshake state, newly inserted tunnels are picked up through the list callback, and stats refresh failures are logged instead of swallowed.

- **Kernel-backend connect responsiveness**
  `TunnelManager.setTunnelState()` now moves tunnels into `TOGGLE` before the blocking backend transition starts, `ObservableTunnel.onStateChange()` marshals backend callbacks back to the main dispatcher, and home/detail stats refreshes pause while a tunnel is toggling. Home header aggregation now reads cached config/stat snapshots instead of triggering extra opportunistic backend reads during binding.

- **`LogViewerActivity` — restart race guard**
  `stopLogStream()` now advances the generation token before destroying the active logcat process, and the streaming reader rechecks the token immediately after `readLine()` before mutating log buffers. Manual refresh can clear and restart the stream without stale lines from the previous process leaking into the new buffer.

- **`LogViewerActivity` — live stream recovery**
  The log viewer now flushes pending buffered lines when `logcat` ends and automatically restarts the stream with a 50-line tail window, so new log entries keep appearing without leaving and reopening the screen.

- **`BindingAdapters` — resource-backed home summary**
  The home header summary now formats through `R.string.tunnl_home_header_summary` instead of embedding the brand/count/status string directly in the binding helper.

- **Gradle configuration cache**
  Enabled `org.gradle.configuration-cache=true` in `gradle.properties` so supported Gradle task graphs can reuse configuration between local runs.

- **Gradle worker cap**
  Added `org.gradle.workers.max=24` and raised the Gradle daemon heap to `-Xmx4g` in `gradle.properties` so local Android builds can use WSL's available CPU parallelism on 64 GB RAM machines without trying to occupy all 32 logical CPUs.

## Pending

*(add items here as functional work is identified)*

---

## Notes

- `GoBackend.java` — VPN tunnel backend. Wraps the Go WireGuard library. Modify with care: state transitions (`setUp`, `tearDown`) are called from coroutines in `TunnelManager`; blocking calls must not run on the main thread.
- `TunnelManager.kt` — owns the tunnel list, persists config to disk, manages backend lifecycle.
- `ObservableTunnel.kt` — observable wrapper; `getStatisticsAsync()` hits the backend and fires `BR.statistics` notification; `getConfigAsync()` similarly for config.
