# Backend & Functional Changelog

Tracks non-UI changes: backend logic, data layer, permissions, networking, build config, and any functional behavior changes. Separate from `ui-redesign-roadmap.md` which covers visual/layout work only.

---

## In Progress

*(nothing active)*

## Done

### Statistics

- **`ObservableTunnel` — per-second RX/TX rate deltas**
  Stats polling added to `TunnelListFragment` (`onResume`/`onStop` coroutine, 1 s interval, calls `getStatisticsAsync()` on all UP tunnels). Mirrors the existing `TunnelDetailFragment` polling pattern. Prior to this, home-screen stats only updated after visiting the detail screen.

- **`BindingAdapters` — stat formatters**
  Added: `tunnelRx`, `tunnelTx`, `tunnelRxRate`, `tunnelTxRate`, `tunnelHandshake`, `tunnelCipher`, `tunnelRoute`, `tunnelPeerBadge`, `shortKey`.
  - `tunnelRxRate` / `tunnelTxRate`: compute per-second delta from previous snapshot stored on `ObservableTunnel`; display as `KB/s` / `MB/s`.
  - `shortKey(key)`: truncates base64 keys to `wOs2…BVkA` (6 chars + ellipsis + 6 chars).

### Binding Adapters

- **`topologyConfig` binding adapter** (`BindingAdapters.setTopologyConfig`)
  Reads first interface address and first peer endpoint from `Config`; pushes to `TunnelThroughputView` so the detail-screen wire diagram shows real tunnel data instead of placeholders.

- **`isConnecting` binding adapter** (`BindingAdapters.setIsConnecting`)
  Passes `tunnel.state == State.TOGGLE` to `TunnelThroughputView`; triggers warn-color connecting animation.

## Pending

*(add items here as functional work is identified)*

---

## Notes

- `GoBackend.java` — VPN tunnel backend. Wraps the Go WireGuard library. Modify with care: state transitions (`setUp`, `tearDown`) are called from coroutines in `TunnelManager`; blocking calls must not run on the main thread.
- `TunnelManager.kt` — owns the tunnel list, persists config to disk, manages backend lifecycle.
- `ObservableTunnel.kt` — observable wrapper; `getStatisticsAsync()` hits the backend and fires `BR.statistics` notification; `getConfigAsync()` similarly for config.
