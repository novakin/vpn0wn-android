# Backend & Functional Changelog

Tracks non-UI changes: backend logic, data layer, permissions, networking, build config, and any functional behavior changes. Separate from `ui-redesign-roadmap.md` which covers visual/layout work only.

---

## In Progress

*(nothing active)*

## Done

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

- **Tunnel stats refresh without app-owned polling loops**
  `TunnelListFragment` and `TunnelDetailFragment` now observe `ObservableTunnel` state/statistics changes and request a fresh stats snapshot only when an UP tunnel is visible/selected, instead of running fragment-owned `while(true) { delay(1000) }` polling jobs.

- **`BindingAdapters` — resource-backed home summary**
  The home header summary now formats through `R.string.tunnl_home_header_summary` instead of embedding the brand/count/status string directly in the binding helper.

- **Gradle configuration cache**
  Enabled `org.gradle.configuration-cache=true` in `gradle.properties` so supported Gradle task graphs can reuse configuration between local runs.

## Pending

*(add items here as functional work is identified)*

---

## Notes

- `GoBackend.java` — VPN tunnel backend. Wraps the Go WireGuard library. Modify with care: state transitions (`setUp`, `tearDown`) are called from coroutines in `TunnelManager`; blocking calls must not run on the main thread.
- `TunnelManager.kt` — owns the tunnel list, persists config to disk, manages backend lifecycle.
- `ObservableTunnel.kt` — observable wrapper; `getStatisticsAsync()` hits the backend and fires `BR.statistics` notification; `getConfigAsync()` similarly for config.
