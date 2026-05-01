# Tunnl UI Redesign Roadmap

## Design Source

Reference: `.design/` — React/JSX prototype for **Tunnl**, a WireGuard tunnel manager with an engineering-console visual language. Read-only. Never modify.

| File | Contents |
|------|----------|
| `screens-home.jsx` | Home screen, `TunnelCard`, `TunnlMiniDiagram` (3 variants), multi-select mode, `AddSheet`, status strip |
| `screens-detail.jsx` | `DetailScreen`, `EditorScreen`, `LogsScreen`, `DiagnosticsScreen`, `OnDemandScreen`, `QRScanScreen`, `MoreSheet`, `ShareSheet` |
| `screens-settings.jsx` | `SettingsScreen` — 5 `SettingsGroup` sections |
| `screens-deploy.jsx` | `ServerFleetScreen`, `ServerDetailScreen`, `ServerProvisionScreen`, `VpnUserScreen`, `QROverlay` |
| `schematic.jsx` | `TunnelThroughputView` — animated SVG wire diagram used in detail hero |
| `data.jsx` | Seed tunnels + server data; `fmtBytes`, `fmtRate`, `fmtAgo` formatters |
| `app.jsx` | App shell + full routing state machine |
| `styles.css` | All design tokens, utility classes, CSS animations |
| `icons.jsx` | Complete icon set (hand-rolled SVG, single `<path>`) |
| `tweaks-panel.jsx` | Prototype-only floating tweaks panel |
| `android-frame.jsx` | Prototype-only device frame component |
| `index.html` | Main prototype (all screens) |
| `index-selection.html` | Selection-mode + multi-tunnel proposal |
| `index-deploy.html` | Server fleet / VPS deployment standalone |

---

## Design System

### Typography

| Role | Font family | Weight | Usage |
|------|-------------|--------|-------|
| UI labels, titles, body | `Inter Tight` | 400 / 500 / 600 | Screen titles, settings labels, action buttons |
| All data, keys, addresses, monospace fields | `JetBrains Mono` | 400 / 500 | Tunnel names, endpoints, keys, rates, IP addresses |

**CSS font features applied to mono:**
- `font-feature-settings: "ss01", "zero"` — stylistic alternates + slashed zero
- `font-variant-numeric: tabular-nums` — tabular numbers everywhere data is displayed
- `-webkit-font-smoothing: antialiased` on body

**Android equivalents:**
- `@font/inter_tight` — UI labels/titles
- `@font/jetbrains_mono_regular` / `jetbrains_mono_medium` — data fields

**Type scale (JSX):**

| Class / usage | Family | Size | Weight | Color token |
|---------------|--------|------|--------|-------------|
| `.label` | Mono | 10px | 400 | `--ink-3`, `text-transform: uppercase`, `letter-spacing: 0.08em` |
| `TopBar` title (big) | Mono | 26px | 500 | `--ink` |
| `TopBar` title (normal) | Mono | 18px | 500 | `--ink` |
| `TopBar` sub-label | `.label` | 10px | — | `--ink-3` |
| Tunnel name / prominent values | Mono | 16px | 500 | `--ink` |
| Config row value | Mono | 13px | 400 | `--ink` |
| Config row label | `.label` | 10px | — | `--ink-3` |
| Sub-text, hints | Mono | 10–11px | 400 | `--ink-3` |
| Stat value | Mono tabular | 18–26px | 500 | `--ink` / status color |
| Terminal / log output | Mono | 10px | 400 | varied |

**Android text styles (from `res/values/styles.xml`):**

| Style | Size | Font | Color |
|-------|------|------|-------|
| `TunnlLabelText` | 10sp | JetBrains Mono | `tunnl_ink_3` |
| `TunnlMonoValueText` | 15sp | JetBrains Mono | `tunnl_ink` |
| `TunnlMonoSubText` | 10sp | JetBrains Mono | `tunnl_ink_3` |
| `TunnlConfigRowLabel` | 10sp | JetBrains Mono | `tunnl_ink_3` |
| `TunnlConfigRowValue` | 13sp | JetBrains Mono | `tunnl_ink`, single-line, ellipsize end |
| `TunnlEditorField` | 13sp | JetBrains Mono | `tunnl_ink`, no background |
| `TunnlEditorFieldReadOnly` | 13sp | JetBrains Mono | `tunnl_ink_3`, not focusable |

---

### Color Tokens

All colors use `oklch()` color space. Dark theme is the default for Tunnl Android.

#### Light theme (`styles.css` root)

| Token | Value | Role |
|-------|-------|------|
| `--paper` | `oklch(0.972 0.008 80)` | Main background — warm off-white |
| `--paper-2` | `oklch(0.945 0.010 80)` | Card surface |
| `--paper-3` | `oklch(0.915 0.012 80)` | Recessed / pressed |
| `--rule` | `oklch(0.85 0.012 80)` | Hairline dividers |
| `--rule-strong` | `oklch(0.72 0.014 80)` | Borders, active dividers |
| `--ink` | `oklch(0.18 0.012 250)` | Primary text — deep blue-black |
| `--ink-2` | `oklch(0.34 0.012 250)` | Secondary text |
| `--ink-3` | `oklch(0.52 0.012 250)` | Labels, captions |
| `--ink-4` | `oklch(0.68 0.012 250)` | Hints, placeholder |

#### Dark theme (`[data-theme="dark"]`)

| Token | Value |
|-------|-------|
| `--paper` | `oklch(0.16 0.012 250)` |
| `--paper-2` | `oklch(0.20 0.012 250)` |
| `--paper-3` | `oklch(0.13 0.012 250)` |
| `--rule` | `oklch(0.28 0.012 250)` |
| `--rule-strong` | `oklch(0.40 0.012 250)` |
| `--ink` | `oklch(0.96 0.008 80)` |
| `--ink-2` | `oklch(0.86 0.008 80)` |
| `--ink-3` | `oklch(0.66 0.010 80)` |
| `--ink-4` | `oklch(0.50 0.010 80)` |

#### Accent + state (both themes)

| Token | Value | Role |
|-------|-------|------|
| `--signal` | `oklch(0.74 0.15 245)` | Azure — tunnel UP, active state |
| `--signal-deep` | `oklch(0.50 0.18 250)` | Deep azure — borders, text on active |
| `--signal-glow` | `oklch(0.74 0.15 245 / 0.28)` | Glow ring on status dot |
| `--accent` | `oklch(0.55 0.14 245)` | Indigo — alternative accent |
| `--warn` | `oklch(0.72 0.14 70)` | Ochre — connecting / provisioning |
| `--error` | `oklch(0.62 0.16 30)` | Rust — no signal / attention / revoked |

#### Accent presets (prototype tweaks panel only)

| Key | Signal | Deep | Glow |
|-----|--------|------|------|
| `azure` (default) | `oklch(0.74 0.15 245)` | `oklch(0.50 0.18 250)` | `/ 0.28` |
| `cobalt` | `oklch(0.62 0.20 260)` | `oklch(0.42 0.20 263)` | `/ 0.30` |
| `cyan` | `oklch(0.82 0.13 215)` | `oklch(0.55 0.15 220)` | `/ 0.28` |
| `amber` | `oklch(0.85 0.16 78)` | `oklch(0.58 0.16 60)` | `/ 0.28` |

#### Android color resources (`res/values/colors.xml`)

| Resource | Mapped from |
|----------|------------|
| `tunnl_paper` | `--paper` dark |
| `tunnl_paper_2` | `--paper-2` dark |
| `tunnl_paper_3` | `--paper-3` dark |
| `tunnl_ink` | `--ink` dark |
| `tunnl_ink_2` | `--ink-2` dark |
| `tunnl_ink_3` | `--ink-3` dark |
| `tunnl_rule` | `--rule` dark |
| `tunnl_rule_strong` | `--rule-strong` dark |
| `tunnl_signal` | `--signal` |
| `tunnl_signal_deep` | `--signal-deep` |
| `tunnl_warn` | `--warn` |
| `tunnl_error` | `--error` |

---

### Spacing & Shape

| Token | Value | Usage |
|-------|-------|-------|
| `--radius-sm` | 4px | Input fields, terminal blocks, small pills |
| `--radius` | 8px | Cards, panels, action tiles |
| `--radius-lg` | 14px | Bottom sheets, QR overlay modal |
| `--density-pad` (comfortable) | 16px | Default horizontal padding |
| `--density-pad` (compact) | 12px | Dense layouts |
| Card horizontal padding | 14px | Used consistently in all panels |
| Card vertical padding | 10–12px | Top/bottom padding within rows |
| Section gap | 14px | Between section blocks |
| Icon button size | 32×32px | `IconBtn` |
| Toggle switch | 44×24px | `.sw` |

---

### CSS Utility Classes

| Class | Purpose |
|-------|---------|
| `.mono` | `font-family: var(--font-mono); font-feature-settings: "ss01", "zero"` |
| `.tabular` | `font-variant-numeric: tabular-nums` |
| `.label` | 10px mono caps, `--ink-3`, `letter-spacing: 0.08em` |
| `.tag` | Outlined pill badge (10px mono, uppercase, 999px radius) |
| `.tag.up` | Signal-deep color + border |
| `.tag.error` | Error color + border |
| `.tag.warn` | Warn color + border |
| `.eng-corners` | L-shaped tick marks at top-left and bottom-right via `::before`/`::after` |
| `.hr` | 1px `--rule` hairline |
| `.sw` | Toggle switch (44×24, animated thumb, signal when on) |
| `.cursor` | Blinking block cursor (`▍`) via `::after` |
| `.scanline` | Moving light-sweep animation for provisioning/connecting state |
| `.schematic-grid` | Dot-grid radial background (12px × 12px, `--rule` dots) |
| `.scroll` | Styled scrollbar (4px thumb, `--rule-strong`) |

---

### CSS Animations

| Name | Description | Used by |
|------|-------------|---------|
| `packet-flow` | `offset-distance` 0→100%, opacity fade in/out | `TunnelThroughputView` packets |
| `pulse-ring` | Scale 0.9→1.6, opacity 0.7→0 | Status dot glow ring |
| `blink` | 1s steps, 50% duty cycle | `.cursor` blinking caret |
| `scan` | `translateX(-100%→100%)` | `.scanline` shimmer |
| `dash-flow` | `stroke-dashoffset` 24→0 at 0.8s | Topology SVG active line |
| `dash-flow-slow` | Same at 1.6s | VPS→WAN segment (slower) |
| `dash-flow-warn` | Same at 1.4s | Attention state line |

---

### Icon Set (`icons.jsx`)

All icons: 24×24 viewBox, `fill="none"`, `stroke="currentColor"`, `strokeWidth=1.5`, `strokeLinecap="round"`, `strokeLinejoin="round"`. Single `<path>` per icon.

| Component | Description / usage |
|-----------|---------------------|
| `IconPlus` | Add tunnel / add server / add peer |
| `IconChevron` | Right-pointing — list row disclosure |
| `IconChevronL` | Left-pointing — back navigation |
| `IconChevronD` | Down-pointing — expand/collapse |
| `IconClose` | ✕ — exit selection mode, close overlays |
| `IconCopy` | Copy to clipboard — config values |
| `IconQR` | QR code — pair device / scan to import |
| `IconFile` | File — import `.conf` |
| `IconKey` | Key — private key, SSH credential |
| `IconSearch` | Search — tunnel list filter |
| `IconLogs` | Three lines — log viewer |
| `IconSettings` | Gear — settings screen |
| `IconTrash` | Delete — tunnel / peer / user |
| `IconEdit` | Pencil — editor screen |
| `IconShare` | Upload arrow — export / share |
| `IconArrowU` | Up arrow — transfer out |
| `IconArrowD` | Down arrow — transfer in |
| `IconLock` | Padlock — PSK badge, secure |
| `IconWifi` | WiFi — on-demand / network |
| `IconRefresh` | Circular arrows — sync / repair |
| `IconImport` | Down arrow + line — import |
| `IconKbd` | Keyboard — QR scan prompt |
| `IconMore` | Vertical `⋮` (stroke 2.5) — overflow menu |
| `IconServer` | Terminal window `>_` — server fleet navigation |

---

### Shared Components (JSX prototype)

#### `TopBar`
Two-row header. Row 1: `left` slot + `sub` label (10px mono caps) + `right` slot (icon buttons). Row 2: mono `title` (18px normal, 26px when `big`) + optional `titleRight`.
- Background: `--paper-2`
- Bottom border: `1px --rule`

#### `IconBtn`
32×32px touch target. Transparent background, no border; active state: `--paper-3` bg + `--rule-strong` border. Color: `--ink-2`.

#### `SectionHeader`
Engineering section label. `§NN` prefix in `--rule-strong` + mono section title + horizontal `1px --rule` divider. Hint text right-aligned.

#### `DeployPanel`
Outlined card container. `1px --rule` border, `--radius` corners, `--paper-2` bg, `overflow: hidden`. Used for all config panels in server screens.

#### `ConfigLine`
Label + value row. `10px 14px` padding, `1px --rule` bottom border. Optional `copy` prop → right-aligned `IconCopy`.

#### `EditField`
Editable input row. Same structure as `ConfigLine` but renders `<input>` with bottom-border-only style (no box), focus changes border to `--signal-deep`, `caretColor: --signal`.

#### `DeployField`
Read-only display field. Same layout as `EditField` without interactivity. Supports `split` prop for 50% side-by-side layout.

#### `MiniMetric`
3-column grid cell — 8px label (`.label`) above 10px tabular mono value. Used in server cards and user hero.

#### `HeroStat`
Larger 3-column cell — label (9px) + value (12px mono) + sub (9px `--ink-3`). Used in topology hero grid.

#### `ActionTile`
`eng-corners` tappable tile, min 94px height, flex column with icon top and label+sub bottom. Variants: default / `accent` (signal-deep border) / `danger` (error border+color) / `disabled` (opacity 0.45, pointer-events none).

#### `Segmented`
Multi-option toggle row. Grid of N equal-width buttons, `--rule-strong` border, active = `--ink` bg + `--paper` text.

#### `ToggleLine`
Row with label + description + `.sw` toggle. Used for binary settings.

#### `TerminalStream`
Animated terminal block (`oklch(0.11 0.012 250)` bg, 10px mono, 1.6 line-height). Streams lines at 340ms intervals when `running`. Line color: `$` prompt → signal, `✓` → signal-deep, `!` → warn, `✕` → error, else dim cream. Auto-scrolls to bottom.

#### `QROverlay`
Full-screen modal (backdrop blur, `oklch(0.08 0.012 250 / 0.92)` scrim). Contains: header with label + user name + close button; SVG pseudo-QR grid (25×25, `10px` cells, finder patterns, timing patterns, deterministic data fill); 2-col info grid (SERVER, IP, ENDPOINT). Closes on outside tap or Escape.

#### `ServerTopologyHero`
SVG topology diagram (`280×64` viewBox) on `.schematic-grid` background. Three `TopoNode` elements (DEVICE, VPS, WAN) connected by animated dashed lines. Active = animated `dash-flow` lines + blue node rings; attention = error-color warn lines; offline = static gray.

---

### Tunnel Status System

| Mode | Condition | Label | Color |
|------|-----------|-------|-------|
| `active` | `state === UP` + handshake < 180s | `◆ ACTIVE` | `--signal-deep` |
| `connecting` | `state === UP` + no handshake OR first 30s | `◑ CONNECTING` | `--warn` |
| `nosignal` | `state === UP` + handshake ≥ 180s | `✕ NO SIGNAL` | `--error` |
| `inactive` | `state === DOWN` | `● INACTIVE` | `--ink-3` |

### Server Status System

| Status | Label | Color | Card border |
|--------|-------|-------|-------------|
| `online` | `◆ ONLINE` | `--signal-deep` | `--rule` |
| `provisioning` | `◑ PROVISIONING` | `--warn` | `--warn` |
| `attention` | `✕ ATTENTION` | `--error` | `--error` |
| `offline` | `● OFFLINE` | `--ink-3` | `--rule` |

### VPN User Status System

| Status | Label | Color |
|--------|-------|-------|
| `active` | `◆ ACTIVE` | `--signal-deep` |
| `idle` | `◑ IDLE` | `--warn` |
| `revoked` | `✕ REVOKED` | `--error` |

### Health Check Badges

Three-state: `ok: true` → `◆` signal-deep pill; `ok: false` → `✕` error pill (+ error-tinted background); `ok: null` → `◑` warn pill (running/unknown).

---

### Seed Data

**Tunnels (`data.jsx`):**
- `home-lab` — UP, 10.7.0.4/24, peer `router.lan` at `198.51.100.42:51820`, PSK, live stats
- `us-west-edge` — DOWN, 10.13.13.5/32, peer `sfo-01` at `203.0.113.71:51820`
- `office-ro` — DOWN, 172.20.4.18/24, peer `gw-bucharest`, PSK

**Servers (`screens-deploy.jsx`):**
- `ams-01` — Hetzner Amsterdam, ONLINE, Ubuntu 24.04, kernel, 3 peers
- `fra-01` — Netcup Frankfurt, PROVISIONING, Debian 12, userspace, 0 peers
- `nyc-01` — DigitalOcean New York, ATTENTION (port blocked + stale peers), Ubuntu 22.04, kernel, 1 peer

**Formatters (`data.jsx`):** `fmtBytes(n)` — `KB/MB/GB`; `fmtRate(kbps)` — `KB/s / MB/s`; `fmtAgo(sec)` — `Ns`, `Nm Ss`, `Nh Mm`

## Roadmap

### 1. Foundation
- Add Tunnl dark palette resources mapped onto the existing Material theme.
- Update default card, toolbar, and status/nav bar styling to the dark/signal language.
- Force the app onto the dark theme for the redesign branch; defer light mode.
- Keep the existing Android view/data-binding architecture.

### 2. Home / Tunnel List
- Replace plain list rows with card-like tunnel rows.
- Card: `◆ ACTIVE` / `◑ CONNECTING` / `◑ NO SIGNAL` / `● INACTIVE` status label, conditional PSK badge, mono tunnel name, address→endpoint sub-text, left signal rail.
- Stats row (↓ IN / ↑ OUT / HANDSHAKE) visible only when tunnel is live.
- Animated packet/signal strip (`TunnelSignalView`) — 2 endpoint nodes, dashed rect encrypted segment overlay.
- Custom two-row top bar (no root ActionBar): row 1 = `Vpn0wn · {n} CONFIGURED · ACTIVE/INACTIVE` + Search / Logs / Settings icons; row 2 = large `tunnels` title.
- PROTECTED / UNPROTECTED status strip (dot + status text + active peer endpoint host) between header and list; no WAN IP lookup.
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
- LIVE/PAUSED toggle button aligned right on the translated log title row.
- Refresh and Share actions as TopBar icon buttons (not FAB); share uses the JSX upload/share outline.

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
- **Self-hosted server fleet** — full VPN control plane. See §Server Fleet Feature below for complete screen specs.
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
- [x] Home stats refresh wired: `TunnelListFragment` observes tunnel state/statistics changes and requests a fresh stats snapshot for visible UP tunnels without owning a repeating polling loop.
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
- [x] Home/detail typography completion — shared Tunnl mono styles now disable Android font padding and cover the home TopBar/status/empty state, and `TunnelDetailFragment` now renders a custom JSX-style mono TopBar while `MainActivity` suppresses the stock `WireGuard` ActionBar on detail.
- [x] Log viewer redesigned to JSX layout: four-column mono entries (`HH:MM:SS.mmm`, level, tag, message), severity left rail + per-level text colors, dark paper row background, custom `STREAM · TUNL0` header with back/share actions, and LIVE/PAUSED moved into the title row; filters now use `ALL`, `DEBUG` (`V` + `D`), `INFO`, `WARN`, `ERROR` (`E` + `ASSERT`).
- [x] Home header/status strip updated: custom JSX-style top surface now shows `Vpn0wn · {n} CONFIGURED · ACTIVE/INACTIVE`, large mono `tunnels` title, and local `PROTECTED`/`UNPROTECTED` strip. `EXIT IP` shows the active peer endpoint host when connected; no pre/post VPN WAN IP lookup is performed.
- [x] Home header/status strip binding fixed: `TunnelListFragment` now invalidates the custom header when any tunnel changes `state` or `config`, so `ACTIVE/INACTIVE` and `PROTECTED/UNPROTECTED` stay in sync with live tunnel state instead of only updating on list changes.
- [x] Connected tunnel card footer fixed: the live stats section now sits inside the outer card treatment with its own inset bottom-corner background, and the active rail stops above the stats block instead of visually breaking the lower card border.
- [x] Home/editor fidelity pass: inactive tunnel rows now use the JSX-height 24dp mini-diagram strip, and remaining editor controls (`regen`, `configure`, `remove`, `ADD PEER`, exclude-private-IPs`) now use explicit JetBrains Mono button/checkbox styles instead of default Material typography.
- [x] Home/log text pass: handshake age now uses compact neutral formatting (`7s`, `01m 23s`, `02h 04m`) instead of localized prose, the home status strip now shows the active peer endpoint host under `EXIT IP` without WAN lookup, and the log header now matches the JSX row structure more closely with back + `STREAM · TUNL0` on row 1, refresh/share on the right, and a translated `logs` title on row 2 with LIVE/PAUSED aligned right.
- [x] Log filter behavior fixed: switching log categories now jumps to the latest matching line instead of leaving the list at an arbitrary older position.
- [x] Home header controls added: the stock `WireGuard` action bar is hidden on the root list screen, and the custom summary row now carries the JSX-style Search (no-op), Logs, and Settings icon buttons.
- [x] Launch/splash background aligned to Tunnl theme: the adaptive icon background color used on the launch screen now matches `tunnl_paper` instead of the old WireGuard red.
- [x] Home top inset handling moved to code: the custom home header now applies status-bar padding internally via `WindowInsets`, instead of reserving a separate empty band above the header with `fitsSystemWindows`.
- [x] Log header controls refined: refresh restart is generation-safe to avoid crashes when restarting `logcat`, and the share action now uses the JSX-style upload/share outline icon.
- [x] **4-state tunnel status system** — implements the full design legend (`app.jsx` lines 319-323): `◆ ACTIVE` (blue, healthy handshake), `◑ CONNECTING` (amber, TOGGLE or first 30s of UP-without-handshake), `✕ NO SIGNAL` (error/rust after no initial handshake or stale handshake > 180s), `● INACTIVE` (ink-3, DOWN). Status label, active rail color, top status strip, detail status card, and `TunnelSignalView` animation all driven by the same `tunnelMode()` helper in `BindingAdapters`. `TunnelSignalView` gains `TunnelMode.ERROR`; connecting animation runs at 900ms and travels only half the path, while no-signal is static red.
- [x] Tunnel list row state invalidation fixed: signal animation, rail, status label, stats visibility, and switch tint now explicitly bind to `item.state` and `item.statistics`, preventing recycled/stale rows from keeping the old tunnel's active animation or stats after switching servers.
- [x] Home mini-diagram connected animation refined: `TunnelSignalView` now mirrors the detail topology motion language at card scale with endpoint glow plus multiple bidirectional packets for healthy tunnels.
- [x] Mode-colored tunnel switch: `ToggleSwitch` now tints the active track from tunnel mode (`UP`, `CONNECTING`, `ERROR`) while keeping the checked thumb dark like JSX `.sw[data-on="true"]::after`; it stays visually on during `TOGGLE`/connecting state.
- [x] Detail screen state invalidation fixed: topology mode, status card stroke/dot/title/subtitle, stats visibility, throughput animation, and detail switch tint now explicitly bind to `tunnel.state` and `tunnel.statistics` instead of relying on a single `tunnel` object reference.
- [x] Home protection semantics fixed: `PROTECTED` and `ACTIVE` now require a healthy WireGuard handshake, not just `Tunnel.State.UP`; connecting/no-signal states color the top summary, status dot, and protection label amber/red.
- [x] Tunnl transient message styling added: app Snackbars now use the dark prototype toast treatment (`paper_2` surface, rule border, 8dp radius, mono text, signal dot, signal action text) via shared `TunnlSnackbar.tunnlStyle()`.
- [x] Design prototype added for self-hosted VPN server fleet/deployment/user management: `.design/screens-deploy.jsx` plus standalone `.design/index-deploy.html` matching the `index-selection.html` presentation shell. Flow now starts at `ServerFleetScreen`, then drills into `ServerDetailScreen`, `ServerProvisionScreen`, and `VpnUserScreen`.
- [x] Native Android self-hosted server fleet UI added: `VpsProvisioningFragment` implements the JSX server fleet, server detail, 5-step provisioning wizard, VPN user profile, animated topology view, streaming terminal output, filter tiles, action tiles, and QR overlay using Tunnl colors/fonts. Home TopBar now includes a server icon entry point.
- [x] VPS UI strings moved to Android resources: new server fleet labels, wizard copy, action labels, snackbar messages, statuses, QR footer, and section titles are in `res/values/strings.xml`, with French coverage added in `res/values-fr/strings.xml`.
- [x] VPS UI quality pass: server/user/status/action UI models moved into `VpsDeployUiModels`; animated topology, QR rendering, terminal stream, and switch glyph moved into `VpsDeployViews`. `TerminalStreamView` now removes pending callbacks on detach.
- [x] VPS demo data removed from runtime: `VpsProvisioningFragment` now starts with an empty live-data state instead of seeded providers, endpoints, users, keys, counters, and fixed dates.
- [x] Mono font feature fidelity added app-wide: Tunnl mono text styles now enable `ss01`, slashed zero, and tabular-number OpenType features via `android:fontFeatureSettings` in `res/values/styles.xml`.
- [x] Detail peer index labels fixed: `TunnelDetailFragment.updateStats()` now assigns `PEER #NN` per rendered peer instead of every card showing `PEER #01`.
- [x] Animation draw-loop performance pass: `TunnelThroughputView` and `TunnelSignalView` now cache density, colors, path effects, packet/dimension paints, and reusable geometry instead of resolving resources or allocating `Paint`/`RectF` objects during `onDraw`.
- [x] Home header summary moved to Android string resources and `TunnelThroughputView` derived alpha colors now refresh in `syncColors()` instead of being recomputed every animation frame.
- [x] Log viewer live/filter pass: LIVE/PAUSED labels moved to string resources, live toggle state is initialized from code, and filtered log insertion now appends matching rows incrementally instead of rebuilding all visible positions for every batch.
- [x] VPS custom view performance/security pass: `VpsTopologyView` now caches density/typeface, avoids repeated grid `dp()` work, and only animates non-offline servers; `VpsQrCodeView` now renders a real ZXing QR matrix instead of a pseudo-QR pattern; `VpsProvisioningFragment` clears SSH form secrets when leaving the wizard, reuses cached bold typefaces, and uses the shared key-shortening helper.
- [x] VPS deploy fidelity pass: fleet filter strip now sits under the TopBar with active bottom rails, server card borders follow health/provisioning escalation, normal deploy TopBars use 18sp titles, More action icon is restored, `CHECKS` and empty-peer states are rendered, listen port shows `UDP`, add rows use plus icons and dashed treatment, provisioning has the step tracker, key/password fields use boxed prototype inputs, apply bottom action disables until apply completes, QR info uses the 2-column layout, deploy panels/action cards draw `eng-corners`, and topology lines use separate animation speeds with the VPS outer ring.
- [x] VPS fleet store wiring: `VpsProvisioningFragment` now collects `VpsServerStore.servers` and renders persisted non-secret server records through `VpsDeployMapper`; mapper/test fixtures cover online, provisioning, attention, active peer, idle peer, and empty-peer states for future visual regression tests.
- [x] Stats refresh lifecycle pass: `TunnelListFragment` and `TunnelDetailFragment` observe `ObservableTunnel` state/statistics changes for binding invalidation and run lifecycle-bound 1-second refresh jobs so visible/selected UP tunnels keep live rates and handshake state current.
- [x] Binding status labels moved to resources: tunnel mode labels, detail status titles/subtitles, and home protection labels now use Android strings instead of hardcoded adapter text.

### Fidelity Gaps — Remaining

- [~] **Backend-specific error reasons not surfaced** — the UI now has red `✕ NO SIGNAL` for unreachable/no-handshake, but `BackendException` is still thrown/caught without being stored on `ObservableTunnel`; there is no `State.ERROR` carrying causes like VPN authorization or tun creation failure.
- [~] Add tunnel bottom sheet: structure done — **gaps:** button order is file→QR→empty (should be QR first as accent, then file, then manual); no subtitle text under each option; no `ADD TUNNEL · CHOOSE SOURCE` header label; no drag handle.
- [~] Detail peer card: **remaining gaps:** no peer label name in card header (peers have no label in WireGuard config format — not fixable without format extension); no live handshake badge in header (`TunnelDetailFragment` updates these imperatively post-bind, wiring to XML binding requires a larger refactor); peer live stats is combined text `rx: X, tx: Y` rather than JSX 2-column `↓ RX` / `↑ TX` grid; PSK badge missing `IconLock` icon (plain `PSK` text only).
- [~] **eng-corners bracket decorations outside VPS** — `VpsCornerLayout` now implements the JSX 8×8px corner ticks for VPS deploy cards/action tiles. Some older XML surfaces still use plain card backgrounds and need a reusable XML-friendly wrapper or foreground drawable before this is complete app-wide.
- [~] Editor ADD PEER button: solid border — true CSS `dashed` border requires custom `ShapeDrawable` with `DashPathEffect`; MaterialButton `strokeWidth` only does solid.
- [~] **Self-hosted fleet data is partially live** — native screens now render persisted `VpsServerStore` records, but live SSH detect/plan/install/sync/add-peer/revoke/remove results are not yet connected to the UI action handlers.
- [~] **VPS QR payload is not a generated WireGuard profile yet** — QR rendering now uses a real QR matrix, but the encoded content remains placeholder deployment data until live `add-peer` returns an importable WireGuard config with the client private key and server peer metadata.
- [~] **VPS translation coverage partial** — default English and French strings exist for the new VPS flow. Other existing locale folders fall back to English until translations are added for the new `tunnl_*` server fleet keys.
- [~] **Static values that must become dynamic in VPS flow** — demo server rows have been removed from runtime and persisted server records now render in the fleet. Remaining work is to populate live health checks, transfer/handshake counters, terminal logs, first peer import values, public keys, and generated WireGuard configs from `TunnlProvisioningClient.detect/plan/install/syncStatus/addPeer` once SSH transport is connected.

### Pending

**Home:**
- [ ] Make PSK badge + separator conditional on peer having PSK.
- [ ] **Multi-select / bulk delete** — long-press any tunnel card (600ms) enters selection mode: checkboxes appear on all cards with slide-in animation, toggle switch and FAB hidden, TopBar transforms to show selected count + `[ALL/NONE]` button + trash icon (trash only visible when count > 0), status strip shows `N OF M SELECTED`. Tap cards to toggle selection. Trash icon opens confirmation bottom sheet (drag handle, tunnel name list with error-color left rail, CANCEL + DELETE buttons). On confirm, deletes all selected tunnels and exits selection mode. Tap `✕` or backdrop exits selection mode. Implementation: add `selectionMode: Boolean` and `selectedTunnels: Set<ObservableTunnel>` state to `TunnelListFragment`; set `View.setOnLongClickListener` on card root in `tunnel_list_item.xml`; add `CheckBox` view to `tunnel_list_item.xml` (hidden by default, shown via `app:selectionMode` binding adapter); delete via `lifecycleScope.launch { tunnel.deleteAsync() }` (`ObservableTunnel.deleteAsync()` line 140) for each selected tunnel; confirmation via `MaterialAlertDialogBuilder` or custom `BottomSheetDialogFragment`; topbar selection state drives visibility of existing header views. Design reference: `.design/index-selection.html`.
- [ ] **Multi-tunnel active count in header** — when kernel module is enabled, multiple tunnels can be UP simultaneously; header sub-text must show `N ACTIVE` (count) and `· M ERROR` if any tunnel is `TunnelMode.ERROR`, instead of binary `ACTIVE/INACTIVE`. When kernel module off, keep binary. Implementation: in `BindingAdapters.homeHeaderSummary()` (lines 252–261), read `UserKnobs.enableKernelModule` (`Flow<Boolean>`, `UserKnobs.kt` line 18) — if true, count tunnels in each mode instead of boolean check; collect the flow in `TunnelListFragment` and re-invalidate header binding on change. Design reference: `.design/index-selection.html`.
- [ ] **DEGRADED status for multi-tunnel partial failure** — when kernel module is on and ≥1 tunnel is `TunnelMode.UP` but ≥1 other tunnel is `TunnelMode.ERROR` or `TunnelMode.CONNECTING`, status strip shows `DEGRADED` (amber `tunnl_warn` dot) instead of `PROTECTED`. Rationale: apps routed through the broken tunnel are not protected; `PROTECTED` would be semantically wrong. Single-tunnel mode unaffected. Implementation: add `DEGRADED` branch to the protection-status logic in `TunnelListFragment` header binding (same location as `PROTECTED`/`UNPROTECTED` currently set); guard behind `UserKnobs.enableKernelModule` check. Design reference: `.design/index-selection.html`.

**Detail:**
- [ ] **Detail TopBar Share/More completion** — detail now uses a custom mono TopBar with back + edit actions. Remaining JSX parity: add Share + More icon buttons alongside Edit, then wire ShareSheet and MoreSheet behavior.
- [ ] Add Share button to detail toolbar (Edit icon already exists — place Share + More alongside it in the redesigned TopBar above).
- [ ] Add More button to detail toolbar: add `ic_tunnl_more` drawable (vertical `⋮`, 3 dots); wire to `MoreSheet`.
- [ ] **Status card subtitle = session timer** — JSX shows `session MM:SS` counting up from when the tunnel went UP (`LiveTimer` component, 1s interval). Android `setDetailStatusSubtitleTunnel` shows `tunnelHandshake()` = handshake age. Different data. Implementation: track `tunnelUpSince: Long?` in `TunnelDetailFragment`; set to `SystemClock.elapsedRealtime()` when `tunnel.state` transitions to `UP`; launch a 1s coroutine in `onResume` that formats `(now - tunnelUpSince)` as `MM:SS` and sets `binding.tunnelStatusSubtitle.text = "session $formatted"`; cancel in `onPause`. When not UP, fall back to current `setDetailStatusSubtitleTunnel` binding.
- [ ] **Peer live stats 2-col grid** — current `tunnel_detail_peer.xml` shows a combined `TRANSFER` row (`rx: X MB, tx: Y KB`). JSX `PeerCard` shows separate `↓ RX` / `↑ TX` cells as a 2-column grid (matching the detail `BigStat` pattern). Rework the stats section of `tunnel_detail_peer.xml` to a horizontal `LinearLayout` with two equal-weight columns: left = `↓ RX` label + `fmtBytes(rx)` value; right = `↑ TX` label + `fmtBytes(tx)` value. Keep existing `latest_handshake` row alongside.
- [ ] **Copy icon button UX** — JSX `ConfigRow` with `copy` prop renders a dedicated 28×28 icon button (right-aligned) that flashes `tunnl_signal` background for 1100ms on press. Android uses `ClipboardUtils::copyTextView` on the value `TextView` itself (no visual indicator). Consider adding a small copy icon `ImageView` to `TunnlDetailRow` style rows or at minimum a ripple + `TunnlSnackbar` confirmation consistent with existing snackbar styling.
- [ ] **PSK badge lock icon** — `tunnel_detail_peer.xml` shows plain `PSK` text. JSX shows `<IconLock size={10}/> PSK` pill badge. Add `ic_tunnl_lock` (or use existing Material lock drawable) as a leading `ImageView` inside the PSK badge container.
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
- [x] Self-hosted server fleet screens — Android flow added in `VpsProvisioningFragment` for `ServerFleetScreen`, `ServerDetailScreen`, `ServerProvisionScreen`, and `VpnUserScreen`.
- [ ] `MoreSheet` — new bottom sheet.
- [ ] `ShareSheet` — new bottom sheet.

**Redesign QA:**
- [ ] Add screenshot regression coverage for the main redesigned surfaces: home tunnel list, add tunnel sheet, detail, peer cards, editor, log viewer, settings, and VPS fleet/provision/detail/user views. VPS mapper fixtures now provide deterministic online/provisioning/attention/user states for this work.
- [ ] Add mandatory dark-mode goldens with fixed font scale and deterministic data.
- [ ] Add RTL screenshot variants for custom rows, active rails, section headers, bottom sheets, and QR overlays.
- [ ] Add Espresso smoke coverage for whole-app navigation across home, add tunnel, detail, editor, logs, settings, VPS wizard, QR overlay, and unreachable VPS provisioning error state.
- [ ] Keep visual fidelity gaps in `Fidelity Gaps — Remaining`; do not list missing implementation as passing test coverage.

**Notes on prototype-only files:**
- `android-frame.jsx` — Material 3 device frame for the web prototype only. No Android implementation needed.
- `tweaks-panel.jsx` — floating design-tool tweaks panel for the web prototype only. No Android implementation needed.

---

## Server Fleet Feature

JSX source: `.design/screens-deploy.jsx`. Standalone prototype: `.design/index-deploy.html`. Integrated into main prototype via `index-selection.html` (tap server icon in home TopBar → `DeployControlPlane`).

### Navigation Flow

```
HomeScreen (index-selection.html)
  └─ [⊟ server icon] → DeployControlPlane
       ├─ ServerFleetScreen (default)
       │    └─ [tap card] → ServerDetailScreen
       │         ├─ [tap user row] → VpnUserScreen
       │         │    └─ [show qr] → QROverlay (modal, same screen)
       │         └─ [back] → ServerFleetScreen
       ├─ [+ add server] → ServerProvisionScreen (5-step wizard)
       │    └─ [finish] → ServerDetailScreen (newly provisioned)
       └─ [back] → HomeScreen
```

### `ServerFleetScreen`

**TopBar:** `IconChevronL` back · sub: `N CONFIGURED · M PEERS` · right: `IconLogs`, `IconSettings`

**Stat/Filter Strip (single row, replaces separate chip row):**
4 clickable tiles in a `gridTemplateColumns: repeat(N, 1fr)` grid. Each tile:
- 9px `.label` with status dot prefix (`◆ ◑ ✕`) and status name
- 20px tabular mono numeral
- Active state: `2px solid statusColor` bottom border, `--paper-3` tinted background
- Tap toggles filter; tap again clears
- Tiles shown: `ALL` (always), `ONLINE`, `PROV`, `ATTN` (only when count > 0)

**Server list:** `§01 server fleet · SELF-HOSTED` (hint changes to `FILTER: STATUS` when filtered). Vertical stack of `ServerCard` components with 10px gap. Dashed `+ add server` button at bottom.

**`ServerCard`:**
- `1px --rule` border (escalates to `--error` border when any health check fails, `--warn` when provisioning)
- Left `3px` solid rail in status color
- Header row: status label (colored) left, `PROVIDER · REGION` right (`.label`)
- Server name: 16px mono
- Sub-text: `endpoint:port · N PEERS · ENGINE`
- 3-col mini-metric grid: CIDR / UPTIME / SYNC
- Health badge row: per-check pills — `◆ label` (signal-deep), `◑ label` (warn), `✕ label` (error, red-tinted bg)
- `eng-corners` L-tick decoration

### `ServerProvisionScreen` (5-step wizard)

**TopBar:** `IconChevronL` back (step 0 → exits, steps 1+ → previous step) · sub: `STEP N OF 5 · STEP_LABEL`

**Progress bar:** Full-width, 5 equal segments. Past steps: `--signal-deep`. Current: `--signal`. Future: `--rule`.

**Step tracker:** Row of 5 step nodes (22×22px rounded-rect) with horizontal connectors. Done = `✓` in signal-deep; active = step number in warn; pending = step number in `--ink-4`.

**Bottom action bar:** Full-width primary button. Steps 0–3: `continue` (dark ink fill). Step 3 label: `confirm + apply`. Step 4: `finish — open SERVER_NAME` (signal-deep fill). Disabled state when step 3 apply not yet run.

#### Step 1 — SSH HANDSHAKE
- `§01 SSH ACCESS · NO CLOUD RELAY`
- `EditField` rows: HOST (placeholder `192.0.2.1`), PORT (inline 50% split) + USER (inline 50% split)
- `AUTHENTICATION` section with `Segmented` control: SSH KEY / PASSWORD
- **SSH KEY mode:** `PRIVATE KEY` label + 5-row `<textarea>` (mono 10px, paper-3 bg, signal-deep focus ring) + `↑ pick key file` button. Footer note: "Stored in Android Keystore — never leaves the device."
- **PASSWORD mode:** `<input type="password">` (14px mono, paper-3 bg, signal-deep focus) + amber warning: "Used once for bootstrap. Not persisted after session closes."
- `ToggleLine`: Trust server key

#### Step 2 — DETECT SERVER
- `§02 DETECT · DRY RUN`
- `ConfigLine` rows: DISTRO, KERNEL, PUBLIC IP, FIREWALL, WG STATUS, IP FWD
- Assessment block: `--paper-2` bg, `--ink-2` mono text, 11px, 1.55 line-height

#### Step 3 — PLAN
- `§03 DEPLOYMENT PLAN · REVIEW BEFORE APPLY`
- `TerminalStream` with dry-run output (not animated, all lines visible)
- "Files that will be written" section: `+ create` prefix (signal-deep) + file path

#### Step 4 — APPLY
- `§04 APPLY · READY / ◑ RUNNING / ✓ COMPLETE`
- Pre-apply: placeholder text
- On "apply plan" button click: `TerminalStream` animates live (14 lines, 340ms each)
- Post-complete: `◆ SERVER online · wg0 running` confirmation row
- Apply button: warn-colored border + tinted bg (not shown after apply starts)

#### Step 5 — IMPORT DEVICE
- `§05 IMPORT DEVICE · FIRST PEER`
- `ConfigLine` rows: DEVICE NAME, ASSIGNED IP, DNS, ALLOWED IPS, KEEPALIVE

### `ServerDetailScreen`

**TopBar:** `IconChevronL` back · title: `server-name` · sub: `SERVER · REGION` · right: `IconRefresh` (syncs, flashes toast), `IconMore`

**`ServerTopologyHero`:**
- `.eng-corners` card on `.schematic-grid` dot-grid background
- SVG `280×64` viewBox, 3 `TopoNode` components at cx=30, cx=140, cx=250
- DEVICE (r=14, left) → VPS (r=18, center, big=true) → WAN (r=14, right)
- Lines: animated `dash-flow` (0.8s) left segment; `dash-flow-slow` (1.6s) right segment; both colored `--signal-deep` when online
- WAN node: `active={server.status === 'online'}`, `color="--signal-deep"` (same blue as DEVICE when online)
- Below SVG: 3-col `HeroStat` grid — SERVER / ENDPOINT / CIDR

**§01 HEALTH · STATUS_LABEL**
- `ConfigLine` rows: ENDPOINT (copy icon), DISTRO, ENGINE + version, UPTIME, LAST SYNC
- CHECKS section: health badge pills (see badge spec above)

**§02 VPN USERS · N PEERS**
- `UserListRow` per peer: `3px` rail in user status color, `USER #NN` label, status label, name (14px), IP · RX · TX · HS (10px)
- `+ add vpn user` row at bottom

**§03 NETWORK · WG0**
- `ConfigLine` rows: VPN CIDR, DNS, LISTEN PORT, ENGINE

**§04 ACTIONS · REMOTE**
- 2×2 `ActionTile` grid:
  - Repair (`IconRefresh`) — triggers sync toast
  - Backup (`IconShare`) — `accent` variant
  - Terminal (`IconLogs`) — dry-run output
  - Uninstall (`IconTrash`) — `danger` variant

### `VpnUserScreen`

**TopBar:** `IconChevronL` back · title: user name · sub: `USER · SERVER_NAME` · right: `IconShare` (export, toast)

**Hero card (`eng-corners`):**
- Status label (colored), 22px IP address, 11px device + created date
- 3-col `MiniMetric` grid: RX / TX / HANDSHAKE

**§01 PROFILE · WIREGUARD**
- `ConfigLine` rows: SERVER, PUBLIC KEY (truncated `XXXXXX···XXXXXX`, copy icon), ENDPOINT, DNS, ALLOWED

**§02 ACTIONS · DEVICE**
- 2×2 `ActionTile` grid:
  - Show QR (`IconQR`) — `accent` · `disabled` when revoked → opens `QROverlay`
  - Export (`IconShare`) — `.conf` profile, toast
  - Rotate key (`IconRefresh`) — replaces peer key, toast · `disabled` when revoked
  - Revoke (`IconTrash`) — `danger` · `disabled` when already revoked · sets state to `revoked`

### `QROverlay`

Full-screen modal within `VpnUserScreen` container.

- Backdrop: `oklch(0.08 0.012 250 / 0.92)` + `blur(6px)`
- Card: `--paper` bg, `--radius-lg` (14px) corners, `1px --rule` border, `0 24px 60px rgba(0,0,0,0.5)` shadow
- Header: `SCAN TO PAIR` label + user name + `IconClose` button
- QR SVG: 25×25 grid, 10px cells, 16px padding, `oklch(0.99 0.005 80)` bg, `oklch(0.14 0.016 250)` cells, `1.5px` cell border-radius
- QR algorithm: deterministic pseudo-QR from `${endpoint}#${publicKey}` — real finder patterns (3 corners, 7×7), timing patterns, data fill from string hash
- Info grid: SERVER, IP (2-col), ENDPOINT (full-width)
- Footer: "Scan with a WireGuard app to import this profile."
- Dismiss: tap backdrop or Escape key

### Security Model (documented in prototype)

- SSH credentials never leave device — stored in Android Keystore
- Password mode: one-session bootstrap only, not persisted
- SSH firewall rules on the server are **never touched** (explicitly called out in dry-run output: `keep: existing ssh rules unchanged`)
- Host fingerprint saved only if "Trust server key" toggle is on
- Public key fingerprint is shown before each privileged action
- Dry-run plan step is mandatory before apply — user must see what will change
