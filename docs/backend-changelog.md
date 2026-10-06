# Backend & Functional Changelog

Last updated: 2026-10-06 (Europe/Paris).

Tracks non-UI changes: backend logic, data layer, permissions, networking, build config, and any functional behavior changes. Separate from `ui-redesign-roadmap.md` which covers visual/layout work only.

---

## In Progress

- **Android SSH provisioning integration**
  Server-side Tunnl provisioning scripts now exist under `third_party/wireguard-install/tunnl/`, and the VPS fleet now reads persisted non-secret records from `VpsServerStore`. Remaining work: concrete Android SSH transport, SSH credential capture/storage, remote script upload/execution, progress parsing, live server status persistence, and user-management flow wiring.

- **Provisioning backend QA blockers**
  Concrete SSH transport must be selected before live provisioning is considered stable. `GuardedRemoteShellClientTest` covers fake execute timeout, I/O failure and cancellation propagation, not actual session closure or upload deadlines. Real-library proof remains required. Disposable integration is opt-in and destructive; repaired mutations reject unmanaged config, but harness cleanup remains broad. It must never run in the default unit-test/local-build path; current harness assertions and failure redaction remain C9.

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

- **Phone presentation and throughput follow-up — 2026-10-06**
  `TunnelManager.changeTunnelState` publishes the resolved target through `ObservableTunnel.onTransitionStarted`; state changes precede statistics notifications. Pending disconnect now renders off rather than yellow CONNECTING, while failure restores observed state and controls. `BindingAdapters.tunnelRate` distinguishes repeated bindings from fresh idle snapshots and clears its baseline when statistics disappears. Existing switch-mode binding owns checked/enabled/tint together. See [contracts](backend-contracts.md#persistence-and-presentation) and [verification](development.md#phone-ui-and-throughput-follow-up--2026-10-06). Shared safe-area and status-background corrections are recorded in the UI roadmap.

- **Save completion feedback ownership — 2026-10-06**
  `TunnelEditorFragment.onFinished` shows successful saves on MainActivity's persistent content after leaving the editor. New-profile creation returns `TunnelCreatorActivity.EXTRA_SAVE_MESSAGE` with RESULT_OK before finish; `TunnelListFragment` displays it through its registered activity-result callback. Validation/failure keeps the editor and existing profile. Visible phone preferences and app-list loading errors reuse styled feedback; native fallbacks are documented in [operations](operations.md#import-edit-and-connect).

- **Detekt source remediation and import/resource ownership — 2026-10-06**
  `config/detekt/detekt.yml` keeps the zero-issue gate, excludes guard clauses from ReturnCount and removes catch-name exemptions. Meaningful timing/limits/state/parser positions are named; drawing geometry retains narrow explained exceptions. Typed failures and cancellation handling preserve existing Backend/ConfigStore recovery. `TunnelComparator.compare` now orders distinct normalized names consistently. `QrCodeFromFileScanner.doScan` reopens input, recycles bitmaps and reports exhausted allocation pressure as recoverable input failure. `TunnelImporter.importTunnel` parents creation jobs, aggregates valid/invalid ZIP results once and logs categories only. `ZipExporterPreference.exportZip` parents loads, preserves primary write/cleanup failures and restores controls; Downloads acquisition/provider limits remain. `Updater.downloadAndUpdateWrapErrors` restores its busy flag in `finally` and preserves retry timing. The unused search handler is removed and its button disabled pending real filtering. See [contracts](backend-contracts.md#import-ordering-and-export), [UI gaps](ui-redesign-roadmap.md#fidelity-gaps--remaining) and [verification](development.md#detekt-source-remediation--2026-10-06). #22 publication remains separate from local proof.

- **Complete kernel transitions — 2026-10-06**
  `WgQuickBackend.setState`, `getRunningTunnelNames` and `setMultipleTunnels` share the existing backend instance monitor. Application collects live mode updates on IO so lock contention leaves Main responsive. The owner serializes snapshots, shutdown/apply/rollback and map/callback settlement; restoration errors attach to the primary failure. Controlled simultaneous-UP tests preserve disabled/enabled multiple-mode behavior and injected failures preserve/report observed recovery. #15 merged in PR #27; the issue is closed. See [contracts](backend-contracts.md#wireguard-configuration-and-tunnel-state) and [verification](development.md#kernel-transition-serialization--2026-10-06). Real root/kernel traffic remains unproved.

- **DNS round trips and network-prefix validation — 2026-10-06**
  `Interface.toWgQuickString` retains DNS search domains when no DNS server is present. `InetNetwork.parse` distinguishes omitted masks from explicit negative masks and preserves valid IPv4/IPv6 controls. #16 merged in PR #26, with no new parser, format migration or user-file rewrite. See [contracts](backend-contracts.md#wireguard-configuration-and-tunnel-state) and [evidence](development.md#dns-round-trips-and-prefix-validation--2026-10-06).

- **Tunnel lifecycle and settled transitions — 2026-10-06**
  `TunnelManager` serializes mutations/state snapshots, finishes admitted reconciliation before cancellation, rejects stale references and reserves backend switching until restart/rollback. Backend notifications preserve restoration intent after spontaneous service loss. Inventory/backend futures settle failure/cancellation; failed inventory retries. `ObservableTunnel.setStateAsync` delegates queued decisions to the manager. `TunnelDetailFragment` binds config/statistics work to view/selection identity. `KernelModuleEnablerPreference` prevents duplicate clicks and restores its setting/control after failure. `GoBackend.VpnService.onDestroy` marks liveness and resets acquisition immediately, with identity-checked cleanup/callback ordering off the main thread; UDP protection/interruption policy is explicit. See [contracts](backend-contracts.md#wireguard-configuration-and-tunnel-state) and [evidence](development.md#tunnel-lifecycle-and-transition-settlement--2026-10-06). #13 merged in PR #25; successful native VPN, process restart and device teardown remain unproved. Kernel transition serialization is implemented under #15 as recorded above.

- **Atomic local profile replacement — 2026-10-06**
  `FileConfigStore.create`/`save` stage private writes, sync/close before native atomic rename and propagate commit errors. All store operations serialize through the existing owner; rename no longer creates a destination marker. Previous valid configs survive injected partial-write/rename failures, failed creates publish no profile, and conflicting mutations retain ownership. See [contracts](backend-contracts.md#persistence-and-presentation) and [development evidence](development.md#atomic-local-profile-replacement--2026-10-06). Android filesystem/power-loss proof remains separate.

- **Detekt configuration validation — 2026-10-06**
  `config/detekt/detekt.yml` removes the unsupported `AvoidReferenceToMutableVar` property for pinned Detekt 1.23.8. At that checkpoint native analysis ran and reported 538 existing source findings instead of failing config validation. The source remediation entry above supersedes that failed gate; [original development evidence](development.md#detekt-configuration-repair--2026-10-06) retains its historical scope.

### VPS provisioning

- **`third_party/wireguard-install` — vendored upstream reference**
  Added `Nyr/wireguard-install` from commit `3b85b2702ffcb2ce670c29bd90c6cae4a5bcc753` with MIT license attribution. The upstream interactive script is stored unchanged under `original/wireguard-install.sh`; `UPSTREAM.md` documents source, license, pinning, and update process.

- **`third_party/wireguard-install/tunnl` — non-interactive command surface**
  Added Tunnl-owned Bash commands for `detect`, `plan`, `install`, `add-peer`, `remove-peer`, `revoke-peer`, `sync-status`, `backup`, and `uninstall`. The MVP targets Ubuntu/Debian, default `wg0`, IPv4 `/24` pools, IPv4-only peer default routes and iptables NAT. At the original checkpoint, uninstall lacked ownership gating and mutation failures could be hidden. The C2–C3 entry below supersedes those safety limits; [current script behavior](../third_party/wireguard-install/tunnl/README.md) owns remaining contracts.

- **`common.sh`, plan/install/add/remove/revoke/uninstall — C2–C3 provisioning safety (2026-10-06, Europe/Paris)**
  Reused the existing management marker for pre-effect ownership gates and validated endpoint/DNS/routes/options. Native interface locks serialize allocation/write/apply; a host lock protects shared install/uninstall effects. Private same-filesystem staging, runtime snapshots, explicit stop/apply/postcondition checks and observed recovery prevent fabricated success. Forced reinstall tears down old hooks before config replacement. Remove/revoke share one owner; archives publish after checked removal. Uninstall retains a unique private backup, rejects shared purge, verifies named package absence and removes no unrelated packages through autoremove. Post-commit cleanup reports private residue without rollback. Package/global forwarding effects remain nontransactional. At this checkpoint C4 response/health alignment remained open; the C4 entry below supersedes that gap. C9 harness repairs remain open. See [offline evidence](development.md#c2c3-offline-provisioning-safety--2026-10-06); no APK or live operation was run.

- **`ui/build.gradle.kts` — package provisioning scripts as app assets**
  Added `third_party/wireguard-install/tunnl` as a main asset source so the Android app can upload the exact Tunnl-owned script bundle to a VPS over SSH.

- **Provisioning models/client/scripts/store/mapper — C4 contracts and observed health (2026-10-06, Europe/Paris)**
  `ProvisioningJson` validates consumed success fields and nested shapes, maps supported remote failures to fixed categories/messages and preserves recovery enums without untrusted paths or parser causes. `TunnlProvisioningClient.run` consumes structured nonzero script failures. Plan accepts the shared force flag without making writes or promising install feasibility; remove/revoke emit name/Boolean fields. `sync-status.sh` reuses native interface observation and emits null for unavailable counters; backup counts unique and legacy revoke archives. `VpsServerStore.updateStatus` persists nullable running, migrates legacy records to unknown and avoids payload-bearing corrupt-store warnings. `VpsDeployMapper` requires fresh observed running for ONLINE, separates freshness from health and preserves unknown counters. See [schema and health semantics](backend-contracts.md) and [verification evidence](development.md#c4-provisioning-contracts-and-observed-health--2026-10-06). Concrete SSH, live UI wiring and real-server proof remain separate.

- **`ui/src/main/java/com/wireguard/android/provisioning` — app-side provisioning core**
  Added typed models, JSON parsing, script bundle loading, non-secret server fleet persistence, a remote shell interface, guarded execution wrappers, and a `TunnlProvisioningClient` that uploads scripts to a per-run remote temp directory before running `detect`, `plan`, `install`, `add-peer`, `remove-peer`, `revoke-peer`, `sync-status`, `backup`, and `uninstall`.

- **`VpsProvisioningFragment` — native server fleet flow**
  Added the Android server fleet entry point, persisted non-secret server rendering through `VpsDeployMapper`, the SSH wizard UI, translated VPS flow strings, and custom deploy widgets in `VpsDeployViews`. Runtime prototype seed data was removed from production code; the fleet now renders stored records and empty states until concrete SSH transport and live actions are connected.

- **Provisioning safety hardening**
  Command-exit failures and malformed outer JSON no longer include raw output/snippets. The C4 entry above extends this to nested response-shape errors and structured server error text. `VpsServerStore` clamps corrupted SSH ports and drops records missing required connection fields. `VpsCredential.Password` uses a clearable `CharArray`; the form clears retained buffers before reset/replacement. Factory checks reject untrusted connections without a fingerprint and invalid auth-attempt limits; they do not establish actual transport verification.

### VpnService stale-reference mitigation

- **`GoBackend.setStateInternal` — null-establishment service-future mitigation**
  On `establish() == null`, attempt to reset the service future and request service startup for a subsequent connection attempt. The failed attempt still throws `TUN_CREATION_ERROR`. Null establishment does not identify the underlying cause; stale-reference/memory-pressure diagnosis and device recovery were not established by this source audit.

- **`GoBackend.resetVpnServiceFuture` — race-safe stale future reset**
  The `establish() == null` recovery compares against the exact `CompletableFuture<VpnService>` used for the failed connection attempt before replacing it. This avoids overwriting a newer service future if Android restarts the `VpnService` concurrently. The failed attempt still throws `TUN_CREATION_ERROR`; the next attempt should use the refreshed service reference.

- **`GoBackend.VpnService.onDestroy` — exact-service CAS reset**
  `onDestroy()` now resets `vpnServiceRef` only when the current completed future resolves to the service instance being destroyed. This avoids replacing a newer service future if Android starts another `VpnService` while the old instance is tearing down.

- **`GoBackend` — diagnostic logging for service lifecycle failures**
  Added diagnostic log points for stale service recovery, failed `protect()` preflight, `establish() == null`, and `VpnService.onDestroy()` so this failure can be confirmed from the in-app log viewer.

### Statistics

- **`ObservableTunnel` snapshots and `BindingAdapters` rate samples**
  Home/detail refresh from observable callbacks plus lifecycle-bound jobs. `ObservableTunnel` owns cached snapshots/notifications; `BindingAdapters` owns per-tunnel RX/TX/time samples in a `WeakHashMap` and derives rates from deltas. The earlier idle-rate defect is repaired in the 2026-10-06 phone follow-up above; broader C7 device/traffic proof remains separate.

- **`BindingAdapters` — stat formatters**
  Added: `tunnelRx`, `tunnelTx`, `tunnelRxRate`, `tunnelTxRate`, `tunnelHandshake`, `tunnelCipher`, `tunnelRoute`, `tunnelPeerBadge`, `shortKey`.
  - `tunnelRxRate` / `tunnelTxRate`: use `BindingAdapters.tunnelRate` and its per-tunnel samples; display as `KB/s` / `MB/s`.
  - `shortKey(key)`: displays the first six and last six characters separated by an ellipsis; copy/export still needs the full key.

### Binding Adapters

- **`topologyConfig` binding adapter** (`BindingAdapters.setTopologyConfig`)
  Reads first interface address and first peer endpoint from `Config`; pushes to `TunnelThroughputView` so the detail-screen wire diagram shows real tunnel data instead of placeholders.

- **`isConnecting` binding adapter** (`BindingAdapters.setIsConnecting`)
  Passes `tunnel.state == State.TOGGLE` to `TunnelThroughputView`; triggers warn-color connecting animation.

### Runtime hardening

- **`RootShell.run/start`, `ProvisioningCommandOutput.requireSuccess`, `ProvisioningJson.requireOk`, `TunnlProvisioningClient.withClient` — C1 secret-safe diagnostics**
  Removed raw root-shell command/stdout/stderr and startup UID/error logging, provisioning failure output, malformed JSON payloads/parser causes and cleanup exception payloads. Returned output, successful profiles, marker protocol and exit behavior remain intact. Six synthetic logging regressions reproduced five baseline failures; repaired logging plus existing JSON/output tests passed all 21 tests without skips. See [development evidence](development.md#c1-secret-safe-logging--2026-10-06) for commands, environment and device/live limits. Previously written logs are not sanitized.

- **Binding and log safety pass**
  `BindingAdapters.tunnelMode()` now synchronizes all access to the `connectingSince` `WeakHashMap`, home header/protection adapters share a single-pass tunnel mode aggregate, home exit labels avoid `InetEndpoint.resolved` DNS lookups on the main thread, and frequently updated status dot/rail drawables are reused through view tags. `LogViewerActivity` now marks `logProcess` volatile and revokes shared-log byte buffers with an explicit URI key guard.

- **Formatter and binding cleanup**
  `QuantityFormatter.formatBytes()` now uses integer scaling before formatting instead of chained floating-point division. `QuantityFormatter.formatEpochAgo()` now pads day counts consistently. Tunnel status/detail label mappings are centralized in `BindingAdapters`, and the single-tunnel mode helper snapshots state/statistics before computing status.

- **Tunnel stats refresh lifecycle hardening**
  `TunnelListFragment` and `TunnelDetailFragment` now combine `ObservableTunnel` state/list observer wiring with lifecycle-bound 1 s refresh jobs. Visible/selected UP tunnels keep receiving fresh snapshots for live rates and handshake state, newly inserted tunnels are picked up through the list callback, and stats refresh failures are logged instead of swallowed.

- **Kernel-backend connect responsiveness**
  `TunnelManager.setTunnelState()` now moves tunnels into `TOGGLE` before the blocking backend transition starts, `ObservableTunnel.onStateChange()` marshals backend callbacks back to the main dispatcher, and home/detail stats refreshes pause while a tunnel is toggling. Home header aggregation now reads cached config/stat snapshots instead of triggering extra opportunistic backend reads during binding.

- **Backend initialization and GoBackend state locking**
  `Application` now completes `futureBackend` exceptionally if backend initialization fails, so callers of `Application.getBackend()` receive the startup error instead of suspending forever. `GoBackend` now serializes `currentTunnel`, `currentConfig`, and `currentTunnelHandle` access across normal state transitions, statistics reads, and `VpnService.onDestroy()` so service teardown cannot race the active tunnel handle.

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

2026-10-06 committed-source reconciliation (Europe/Paris): the [full documentation review](reviews/2026-10-06-full-documentation-review.md) corrects guarantees and ownership without rerunning historical tests. Remaining runtime repairs stay in the improvement plan and linked issues.

- `GoBackend.java` — VPN tunnel backend wrapping Go WireGuard. Actual transitions use `setState`/`setStateInternal`; blocking work stays off the main thread. PR #25 schedules identity-checked service cleanup away from Main; successful native activation/reacquisition and device traffic remain unproved.
- `TunnelManager.kt` — owns the tunnel list, persists config to disk, manages backend lifecycle.
- `ObservableTunnel.kt` — observable wrapper; `getStatisticsAsync()` and `getConfigAsync()` can return cached values, otherwise request backend/store data and update notifications.
