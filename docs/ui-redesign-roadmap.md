# Tunnl UI Redesign Roadmap

## Design Source

The reference in `.design/` is a React prototype for "Tunnl", a WireGuard tunnel manager with an engineering-console visual language:

- Dark navy background, charcoal card surfaces, hairline rules, and azure signal accents.
- Monospace labels for tunnel state, keys, endpoints, rates, and other configuration data.
- Compact outlined cards with active/inactive state language instead of plain list rows.
- A home status strip, tunnel cards, add affordance, detail topology/throughput panels, section-numbered config groups, log filters, and settings groups.

## Roadmap

1. **Foundation**
   - Add Tunnl dark palette resources mapped onto the existing Material theme.
   - Update default card, toolbar, and status/nav bar styling to the dark/signal language.
   - Force the app onto the dark theme for the redesign branch; defer light mode.
   - Keep the existing Android view/data-binding architecture.

2. **Home / Tunnel List**
   - Replace plain list rows with card-like tunnel rows.
   - Add active/inactive labels, PSK state, mono tunnel names, address-to-endpoint metadata, and a left signal rail.
   - Add an animated native packet/signal strip based on tunnel state.
   - Add a header/status strip above the tunnel list and a dashed add-tunnel affordance.

3. **Detail**
   - Re-skin interface and peer cards with Tunnl card styling.
   - Add section-style labels and stronger active-state presentation.
   - Add a native schematic/throughput component with live state and transfer rates.

4. **Editor / Add / Logs / Settings**
   - Rework add sheet options into outlined import tiles.
   - Apply sectioned field groups to editor screens.
   - Re-skin log viewer as mono stream with severity styling and level filters.
   - Re-skin settings groups and toggles with custom Tunnl preference rows.

## Progress

- [x] Created branch `ui-redesign`.
- [x] Inspected all `.design/` files and mapped the visual system.
- [x] Added this tracked roadmap.
- [x] Implement foundation resources.
- [x] Switch foundation to dark mode with azure primary accents.
- [x] Implement first-pass home/tunnel-list redesign.
- [x] Added Inter Tight and JetBrains Mono font resources from the JSX design.
- [x] Implement native animated tunnel signal strip.
- [x] Bind tunnel cards to config/statistics for PSK, route, transfer, and handshake details.
- [x] Rework add/import sheet, log viewer, detail/editor surfaces, and settings background into the dark azure system.
- [x] Add detail-screen native throughput/schematic hero.
- [x] Add real per-second RX/TX rate calculation from statistics deltas.
- [x] Add log level filters.
- [x] Add custom Tunnl preference row layout for settings.
- [x] XML-validate changed resources.
- [ ] Build-check UI resources; blocked by local Gradle daemon/cache lock and subsequent no-output hang.
