# Tunnl UI Redesign Roadmap

## Design Source

The reference in `.design/` is a React prototype for "Tunnl", a WireGuard tunnel manager with an engineering-console visual language:

- Dark navy background, charcoal card surfaces, hairline rules, and azure signal accents.
- Monospace labels for tunnel state, keys, endpoints, rates, and other configuration data.
- Compact outlined cards with active/inactive state language instead of plain list rows.
- A home status strip, tunnel cards, add affordance, detail topology/throughput panels, section-numbered config groups, log filters, and settings groups.

Key design files: `screens-home.jsx` (home + tunnel card), `screens-detail.jsx` (detail, editor, logs, sheets), `screens-settings.jsx` (settings), `schematic.jsx` (wire animation hero), `data.jsx` (seed data + formatters), `app.jsx` (app shell + state), `styles.css` (tokens + utilities).

## Roadmap

### 1. Foundation
- Add Tunnl dark palette resources mapped onto the existing Material theme.
- Update default card, toolbar, and status/nav bar styling to the dark/signal language.
- Force the app onto the dark theme for the redesign branch; defer light mode.
- Keep the existing Android view/data-binding architecture.

### 2. Home / Tunnel List
- Replace plain list rows with card-like tunnel rows.
- Card: `◆ ACTIVE` / `○ INACTIVE` status label, conditional PSK badge, mono tunnel name, address→endpoint sub-text, left signal rail.
- Stats row (↓ IN / ↑ OUT / HANDSHAKE) visible only when tunnel is live.
- Animated packet/signal strip (`TunnelSignalView`) — 2 endpoint nodes, dashed rect encrypted segment overlay.
- Custom two-row top bar (no ActionBar): row 1 = `{n} CONFIGURED · {m} ACTIVE` sub + Search / Logs / Settings icons; row 2 = large `tunnels` title.
- PROTECTED / UNPROTECTED status strip (dot + status text + WAN IP) between header and list.
- Dashed `+ ADD TUNNEL` affordance at bottom (keep current flat bar, no FAB).

### 3. Detail Screen
- Wire-topology `TunnelThroughputView` hero: DEVICE node (actual tunnel address), PEER node (actual peer endpoint), animated bidirectional packets, dashed encrypted segment rect, eng-corners bracket marks.
- Connecting state: warn-color packet animating to midpoint only.
- Footer labels `↓`/`↑` (not `IN`/`OUT`).
- §01 Interface card: `ConfigRow` label/value blocks (NAME, PUBLIC KEY truncated, ADDRESSES, DNS SERVERS, LISTEN PORT `—` if absent, MTU in section header hint, EXCLUDED APPS `· {n}` conditional).
- §02 Peers card: peer label name in card header, live handshake badge (`◆ Ns ago`) in header, PSK as header badge only (no separate row), PUBLIC KEY truncated, RX/TX row visible only when live.

### 4. Add Tunnel Sheet
- Bottom sheet with drag handle and `ADD TUNNEL · CHOOSE SOURCE` label.
- 3 `ImportTile` outlined buttons (icon + title + subtitle + chevron):
  1. Scan QR code — accent tile (highlighted border) — "Camera · ~2s"
  2. Import .conf or .zip — "From files · multiple OK"
  3. Create from scratch — "Generate keypair · enter manually"
- Button order: QR first (accent), then file, then manual.

### 5. Editor Screen
- Custom TopBar (Close left, SAVE right); ActionBar hidden.
- §01 Interface: `FieldGroup` outlined card with Tunnl `Field` rows (small all-caps label above plain input) — NAME, PRIVATE KEY (regen pill button), PUBLIC KEY (read-only), ADDRESSES + LISTEN PORT (side by side), DNS SERVERS + MTU (side by side), EXCLUDED APPS (configure pill button).
- §02 Peers: per-peer `FieldGroup` card with remove button, PUBLIC KEY, PRESHARED KEY toggle, ENDPOINT, ALLOWED IPs, PERSISTENT KEEPALIVE.
- Dashed `+ ADD PEER` button at bottom.

### 6. Log Viewer
- 4-column mono entry row: timestamp (compact `HH:MM:SS.mmm`) · level · tag · message.
- Left 2dp severity border: warn color on W, error color on E, transparent otherwise.
- Per-level text coloring: I=ink-2, D=ink-3, W=warn, E=error.
- Dark ink entry background (not paper-2).
- LIVE/PAUSED toggle button alongside filter chips.
- Share action as TopBar icon button (not FAB).

### 7. Settings Screen
- §01 Connection: Restore on boot, Kernel module, Kill-switch, Always-on VPN, DNS leak protection, Allow LAN traffic, Pause on metered, Multi-hop.
- §02 Trusted Networks: app-wide SSID list with add/remove; used by per-tunnel on-demand rules.
- §03 Security: Biometric unlock, Default DNS, Excluded apps, Backup & export.
- §04 System: Quick tile, Logs link, Notifications.
- §05 About: Version, Engine, License, Source.
- Decide fate of Android-specific prefs not in JSX design: dark theme toggle, multiple tunnels, tools installer, remote control intents, donate.

### 8. Extended Screens & Sheets *(design complete in JSX, implementation pending)*
- `DiagnosticsScreen` — §01 Readouts (state, handshake, endpoint, resolved IP, MTU, last error) + §02 Actions (re-resolve, ping, reset stats with inline terminal output panel).
- `OnDemandScreen` — per-tunnel auto-connect rules (untrusted Wi-Fi, cellular); links to trusted-SSID list in §07; warns when no trusted networks configured.
- `QRScanScreen` — full-screen dark camera viewfinder, corner bracket overlays, scan-line animation; auto-advances to EditorScreen on capture.
- `MoreSheet` — tunnel overflow bottom sheet: Rename, Duplicate config, On-demand rules (→ OnDemandScreen), Diagnostics (→ DiagnosticsScreen), Delete (danger section).
- `ShareSheet` — export bottom sheet: Show QR code, Export `.conf`, Copy public key to clipboard.

### 9. Theming Extensions *(low priority — app stays dark azure)*
- Accent preset system (azure / cobalt / cyan / amber): prototype has 4 presets; Android wires azure only. Deferred.
- Density mode (comfortable / compact): deferred.
- Hero metaphor selector (wire / graph / minimal): deferred.

---

## Progress

### Done
- [x] Created branch `ui-redesign`.
- [x] Inspected all `.design/` files and mapped the visual system.
- [x] Added tracked roadmap.
- [x] Foundation: Tunnl color resources (paper, ink, signal, rule, warn, error tokens), MaterialCardView style (0dp elevation, 8dp corner, tunnl_rule stroke), AppThemeBase (dark M3, Inter Tight font, azure primary).
- [x] Dark mode forced for redesign branch; azure primary accents wired.
- [x] Inter Tight (UI) and JetBrains Mono (data/keys) font resources added.
- [x] Home tunnel card: card structure, signal rail, Tunnl-styled background, mono tunnel name, address→endpoint sub-text, PSK badge row, stats (↓ IN / ↑ OUT / HANDSHAKE) with real data binding.
- [x] Tunnel statistics binding: real per-second RX/TX rate deltas from `ObservableTunnel`.
- [x] `TunnelSignalView` implemented (home card mini-diagram): animated packet, state-driven colors.
- [x] Add tunnel bottom sheet: 3 outlined `MaterialButton` tiles, Tunnl-styled (paper_2 background, signal icon tint, rule border).
- [x] Log viewer: `ChipGroup` level filters (ALL / DEBUG / INFO / WARN / ERROR), dark background, JetBrains Mono entries.
- [x] Detail/editor/settings surfaces: `tunnl_paper` backgrounds applied.
- [x] `TunnelThroughputView` implemented (detail hero): dot grid, dimension lines, wire path with dashed encrypted segment rect, bidirectional animated packets, node labels, footer rates.
- [x] Detail screen: `§01 interface` and `§02 peers` section headers with horizontal rule dividers.
- [x] Detail interface card: `ConfigRow` label/value block structure (NAME, PUBLIC KEY, ADDRESSES, DNS SERVERS, LISTEN PORT, MTU, APPLICATIONS).
- [x] Detail peer card: `ConfigRow` block structure (PUBLIC KEY, PRE-SHARED KEY, ENDPOINT, ALLOWED IPs, KEEPALIVE) + transfer/handshake rows.
- [x] Custom Tunnl preference row layout (`tunnl_preference_item.xml`) applied to all settings items.
- [x] XML-validated all changed resources.
- [x] Build-checked with `./gradlew :ui:assembleDebug` in WSL.
- [x] Home stats live polling: `TunnelListFragment` polls all UP tunnels every 1s (`onResume`/`onStop`) — mirrors `TunnelDetailFragment` pattern; stats row now updates live without visiting detail screen.
- [x] Home stats row hidden when tunnel is down (visibility binding on `tunnel_stats` LinearLayout).
- [x] `TunnelSignalView` reworked to match JSX: 2 endpoint nodes, dashed rect overlay, solid/dashed rail, node stroke border, no glow halo.
- [x] `TunnelThroughputView` reworked: real address/endpoint via `topologyConfig` binding, footer `↓`/`↑`, eng-corners brackets, connecting-state animation.
- [x] `BindingAdapters.shortKey()` added — truncates base64 keys to `wOs2…BVkA` format.
- [x] Detail §01 interface card: MTU hint in section header, truncated PUBLIC KEY, LISTEN PORT + MTU always visible with `—`, `EXCLUDED APPS · {n}` label.
- [x] Detail peer card: PUBLIC KEY truncated, separate PRE-SHARED KEY row removed (PSK as header badge only).
- [x] Editor screen rewritten: FieldGroup/Field Tunnl style — §01/§02 headers, plain EditText fields, REGEN button for private key, side-by-side port/MTU, EXCLUDED APPS row, dashed-style ADD PEER button.
- [x] Editor peer card rewritten: FieldGroup/Field style, all fields as plain EditText, exclude private IPs checkbox preserved.
- [x] Home stats value font fixed: 15sp → 13sp to match JSX `Stat` component (`fontSize: 13`).
- [x] Home inactive card `minHeight="176dp"` removed — cards now wrap content naturally (inactive card no longer forced to same height as active).
- [x] Home stats layout reworked: vertical wrapper adds 1dp top border + `tunnl_paper` background; columns span full card width (no side margins); 1dp `tunnl_rule` dividers between OUT and HANDSHAKE columns; each column padding `8dp` vertical `12dp` horizontal — matches JSX `Stat` grid spec exactly.
- [x] Font audit pass — all screens corrected to match JSX spec: tunnel name 16sp (was 15sp), tunnel sub-text 11sp (was 10sp), detail stat rate 11sp (was 10sp), peer transfer/handshake values 13sp (was 15sp), log entry rows 11sp explicit (replaced `textAppearanceBodySmall` ~12sp).
- [x] Log viewer redesigned to JSX layout: four-column mono entries (`HH:MM:SS.mmm`, level, tag, message), severity left rail + per-level text colors, dark paper row background, LIVE/PAUSED toggle, top-bar share action, and full Android verbosity filters (`VERBOSE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`).

### Fidelity Gaps — Remaining
- [~] Add tunnel bottom sheet: structure done — **gaps:** button order is file→QR→empty (should be QR first as accent, then file, then manual); no subtitle text under each option; no `ADD TUNNEL · CHOOSE SOURCE` header label; no drag handle.
- [~] Detail peer card: **remaining gaps:** no peer label name in card header (peers have no label in WireGuard config format — not fixable without format extension); no live handshake badge in header (`TunnelDetailFragment` updates these imperatively post-bind, wiring to XML binding requires a larger refactor).
- [~] Editor ADD PEER button: solid border — true CSS `dashed` border requires custom `ShapeDrawable` with `DashPathEffect`; MaterialButton `strokeWidth` only does solid.

### Pending

**Home:**
- [ ] Hide Material3 ActionBar (`Theme.Material3.Dark` keeps it active — switch to `NoActionBar` variant); rebuild fragment header as custom two-row bar — row 1: `{n} CONFIGURED · {m} ACTIVE` sub + Search / Logs / Settings icon buttons; row 2: large `tunnels` title.
- [ ] Add drawables for Search (`IconSearch` = magnifier) and Logs (`IconLogs` = three horizontal lines) — neither exists in Android yet.
- [ ] Home status strip: PROTECTED/UNPROTECTED row (dot indicator + status text + WAN IP) between header and list.
- [ ] Tunnel card: prefix ACTIVE/INACTIVE with `◆`/`○`; make PSK badge + separator conditional on peer having PSK.

**Detail:**
- [ ] Add Share button to detail toolbar: `ic_action_share_white` drawable exists but is not in `tunnel_detail.xml` menu — add it alongside Edit.
- [ ] Add More button to detail toolbar: no drawable for three-dots vertical icon exists — add drawable and wire to `MoreSheet`. JSX uses `IconMore` (vertical `⋮`).
- [ ] `MoreSheet` bottom sheet (see §8).
- [ ] `ShareSheet` bottom sheet (see §8).

**Add Sheet:**
- [ ] Reorder buttons: QR first (accent/highlighted), then file, then manual.
- [ ] Add subtitle text under each option (e.g. "Camera · ~2s", "From files · multiple OK").
- [ ] Add `ADD TUNNEL · CHOOSE SOURCE` header label and drag handle.

**Editor:**
- [ ] Custom TopBar with explicit × Close button (left) and SAVE pill button (right); hide ActionBar.
- [ ] ADD PEER button: MaterialButton stroke is solid — true dashed border requires custom drawable background (minor gap).

**Settings:**
- [ ] Rebuild as 5 `SettingsGroup` sections with `§` section headers (see §7 in Roadmap above).
- [ ] Resolve fate of Android-specific prefs not in JSX: dark theme toggle, multiple tunnels, tools installer, remote control intents, donate.

**New screens (§8):**
- [ ] `DiagnosticsScreen` — new screen.
- [ ] `OnDemandScreen` — new screen.
- [ ] `QRScanScreen` — new screen. Add drawable: `IconQR` already present as `ic_action_scan_qr_code`.
- [ ] `MoreSheet` — new bottom sheet.
- [ ] `ShareSheet` — new bottom sheet.

**Notes on prototype-only files:**
- `android-frame.jsx` — Material 3 device frame for the web prototype only. No Android implementation needed.
- `tweaks-panel.jsx` — floating design-tool tweaks panel for the web prototype only. No Android implementation needed.
