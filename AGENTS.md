# Tunnl — WireGuard Android Redesign

## Source of Truth — Prompt Instructions

**`CLAUDE.md` is the single source of truth for agent instructions.**
`AGENTS.md` is an exact mirror kept in sync for Codex compatibility.

When instructions change:
1. Edit `CLAUDE.md`
2. Copy verbatim to `AGENTS.md`
3. Never edit `AGENTS.md` directly — changes will be overwritten

If they diverge, `CLAUDE.md` wins.

## Project Context

Branch: `ui-redesign` on WireGuard Android. Goal: pixel-faithful port of the "Tunnl" engineering-console UI from the React/JSX prototype in `.design/` onto the existing Android view/data-binding architecture.

- Design reference: `.design/` — **read-only, never modify**
- UI progress: `docs/ui-redesign-roadmap.md` — **read before every UI task**
- Backend/functional changes: `docs/backend-changelog.md` — **read before every non-UI task**
- Architecture: existing DataBinding + Fragment pattern — keep it, don't refactor the foundation

## Before Starting Any Task

1. Read `docs/ui-redesign-roadmap.md` — check Done, Gaps, and Pending
2. Read the relevant `.design/*.jsx` file for the screen you're touching
3. Identify the exact JSX component/prop being implemented before writing any Android code
4. Check `res/values/colors.xml` and `res/values/styles.xml` for existing tokens before inventing new ones

## After Every Change

**UI change → update `docs/ui-redesign-roadmap.md`:**
- Completed item → move to `### Done` with a one-line description of what changed and where
- Permanent limitation → add to `### Fidelity Gaps — Remaining` with the technical reason
- Removed from scope → delete from `### Pending`

**Backend/functional change → update `docs/backend-changelog.md`:**
- New behavior or fix → add to `### Done` with file, method, and what changed
- In-progress work → add to `### In Progress`, move to Done when complete

Both agents read both files to know the current state. Stale docs = duplicated work or regressions.

## Code Standards

- **State of the art.** No deprecated APIs, no legacy patterns when a modern equivalent exists.
- **Kotlin-first:** coroutines (`lifecycleScope`, `suspend`) over threads/callbacks; `sealed class` for state; extension functions over static utils.
- **DataBinding:** `@BindingAdapter` in `BindingAdapters.kt` for all custom binding logic; keep binding expressions in XML simple.
- **No comments** unless the WHY is non-obvious (hidden platform constraint, subtle invariant, deliberate workaround). Never explain what code does.
- **No dead code.** No `_unused` prefixes, no compatibility shims for replaced code, no half-implemented stubs.
- **No premature abstraction.** Don't extract a helper for one caller. Don't add parameters for hypothetical future use.
- **No scope creep.** Implement exactly what the JSX spec shows. Don't add features not in the roadmap.

## Build Command

**Run only when explicitly asked.**

```
wsl -- bash -c "cd /mnt/c/Users/Guillaume/Documents/Projets/wireguard-android && ./gradlew :ui:assembleDebug 2>&1 | tail -80"
```

Fix all errors before reporting done. DataBinding errors appear as `error: cannot find symbol` on generated binding classes — check XML attribute names and types first.

## Architecture Map

```
ui/src/main/java/com/wireguard/android/
  fragment/         TunnelListFragment, TunnelDetailFragment, TunnelEditorFragment, LogViewerActivity
  widget/           TunnelSignalView (home mini-diagram), TunnelThroughputView (detail hero)
  databinding/      BindingAdapters.kt — all @BindingAdapter + @JvmStatic formatters
  model/            ObservableTunnel.kt — state, config, statistics (BaseObservable)
  activity/         MainActivity, LogViewerActivity
  preference/       KernelModuleEnablerPreference (settings screen)

ui/src/main/res/
  layout/           tunnel_list_item.xml, tunnel_detail_fragment.xml, tunnel_editor_fragment.xml
                    tunnel_editor_peer.xml, tunnel_detail_peer.xml, log_viewer_activity.xml
  values/           colors.xml (Tunnl palette), styles.xml (Tunnl styles), strings.xml
  drawable/         tunnl_list_item_background, tunnl_active_rail, ic_action_*
  font/             inter_tight, jetbrains_mono_regular, jetbrains_mono_medium

tunnel/src/         GoBackend.java — VPN backend (touch carefully)
.design/            JSX prototype — reference only
docs/               ui-redesign-roadmap.md
```

## Design System Tokens

**Colors** (`res/values/colors.xml`):
- Backgrounds: `tunnl_paper` (darkest) → `tunnl_paper_2` → `tunnl_paper_3` (lightest surface)
- Text: `tunnl_ink` → `tunnl_ink_2` → `tunnl_ink_3` (dimmed)
- Accent: `tunnl_signal`, `tunnl_signal_deep`
- Borders: `tunnl_rule` (hairline), `tunnl_rule_strong`
- State: `tunnl_warn`, `tunnl_error`

**Fonts:**
- UI labels, titles: `@font/inter_tight`
- Data, keys, values, monospace fields: `@font/jetbrains_mono_regular` or `jetbrains_mono_medium`

**Styles** (`res/values/styles.xml`):
- `TunnlLabelText` — 10sp mono caps, ink_3 (field labels, section prefixes)
- `TunnlMonoValueText` — 15sp mono, ink (tunnel names, prominent values)
- `TunnlMonoSubText` — 10sp mono, ink_3 (sub-labels, hints)
- `TunnlDetailRow` — row container (match_parent, paper_2 bg, 14dp h-padding, 10dp v-padding)
- `TunnlConfigRowLabel` — wraps TunnlLabelText for ConfigRow label
- `TunnlConfigRowValue` — 13sp mono, single line, ellipsize end
- `TunnlEditorField` — plain EditText (no background, jetbrains mono, 13sp, ink)
- `TunnlEditorFieldReadOnly` — TunnlEditorField + ink_3 + not focusable

**Cards:** 0dp elevation, 1dp `tunnl_rule` stroke, 8dp corners, `paper_2` background.

**Section headers:** `§01`, `§02`… `TunnlLabelText` in `rule_strong` + mono title + horizontal 1dp rule divider.

## JSX Reference Quick Map

| Screen | JSX file | Key components |
|--------|----------|----------------|
| Home / Tunnel list | `screens-home.jsx` | `TunnelCard`, `MiniDiagram`, `Stat`, `StatusStrip` |
| Detail | `screens-detail.jsx` | `WireDiagram`, `ConfigRow`, `PeerCard` |
| Editor | `screens-detail.jsx` | `FieldGroup`, `Field` |
| Add sheet | `screens-detail.jsx` | `ImportTile` |
| Log viewer | `screens-detail.jsx` | `LogEntry`, filter chips |
| Settings | `screens-settings.jsx` | `SettingsGroup`, `SettingsRow` |
| Wire animation | `schematic.jsx` | `TunnelThroughputView` source |
| Data/formatters | `data.jsx` | `formatBytes`, `formatRate`, `formatAge` |
| Palette/tokens | `styles.css` | CSS custom properties → Android color resources |
