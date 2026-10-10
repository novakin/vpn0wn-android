# vpnØwn refined identity

Last updated: 2026-10-10 (Europe/Paris).

The supplied navy/cyan identity is preserved. This reviewed brand reference replaces approximate raster colors with exact SVG geometry and palette values, gives the wordmark deliberate optical spacing, and adds a dedicated 16px symbol.

This directory owns the reviewed brand artwork and its authoring inputs. The [UI roadmap](../../ui-redesign-roadmap.md) owns Android visual intent and implementation status. The ten palette values match the existing Android color resources; these SVG/PNG files have not replaced launcher resources or completed the identity migration in [#71](https://github.com/novakin/vpn0wn-android/issues/71).

## Files

- `assets/brand-board.svg` and `.png`: final 1600 × 1200 identity board.
- `assets/symbol.svg`: display gradient mark.
- `assets/symbol-solid.svg`, `symbol-white.svg`, `symbol-navy.svg`: the same master geometry in solid cyan and monochrome.
- `assets/symbol-micro.svg` and `.png`: optical 16px master. Navy/white variants are also included.
- `assets/symbol-24.*` and `symbol-32.*`: native-size solid cyan exports.
- `assets/wordmark-dark.svg` and `wordmark-light.svg`: outlined wordmarks for dark/light backgrounds.
- `assets/wordmark-*-wireguard.svg`: optional WireGuard lockups.
- `assets/wordmark-mono-white.svg` and `wordmark-mono-navy.svg`: complete monochrome wordmarks.
- `assets/app-icon.svg` and `.png`: 1024 × 1024 app icon.
- `assets/size-proof.svg` and `.png`: actual 16/24/32px raster samples on dark/light backgrounds, with nearest-neighbor 4× inspection.
- `tokens.json` and `tokens.css`: exact palette and usage values.

## Use

Use the optical master at 16px. Use the normal master at 24px and above. Prefer these native sizes over arbitrary resampling. Both masters retain the Ø slash terminals; the 16px counter is wider.

The gradient runs from `#68C6FD` at the top of the body to `#2ABFF7` at its bottom, with stops at 0% and 100%. Use it only in display symbols and the wordmark Ø. All palette swatches and small icons are flat.

Choose navy or white monochrome marks when a cyan mark lacks contrast against its background. Use navy text on cyan controls. The active-border color is a subtle divider/hover color; use cyan for visible focus instead of relying on that border alone.

Keep surrounding content at least one quarter of the symbol body height away from standalone symbols. For wordmarks, keep at least one quarter of the lowercase letter height clear on each side. Favicon canvases have their own optical padding.

Use the plain wordmark below 360px width; the WireGuard descriptor becomes too small at narrower widths. Logos are artwork, not body-text replacements. Keep interface labels as real text and give meaningful logo images an accessible product name.

## Typography and reproduction

Sora Variable is the artwork font: weight 800 for the outlined wordmark, 600 for headings, 500 for labels, and 400 for body copy. Android's existing Inter Tight branding contract remains with the UI roadmap. Optical gaps are explicitly set in `generate.mjs`. SVG exports contain outlines, so they do not require installed fonts. The source font and its SIL Open Font License are in `fonts/`; retain the license when distributing the font.

Font source: <https://github.com/google/fonts/tree/main/ofl/sora>.

To reproduce the assets from the repository root, run:

```powershell
Push-Location docs/assets/vpnown-brand
npm ci
node generate.mjs
Pop-Location
```

Dependencies stay in this artwork directory and are ignored by Git. The generator checks every palette swatch's rendered center against its declared hex value. [Development](../../development.md) owns Android build and verification commands.

Brand copy is retained from the reference. This work covers visual identity and color usage; it does not verify VPN service behavior.
