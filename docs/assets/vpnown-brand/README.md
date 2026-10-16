# vpnØwn refined identity

Last updated: 2026-10-17 (Europe/Paris).

The supplied navy/cyan identity is preserved. This reviewed brand reference replaces approximate raster colors with exact SVG geometry and palette values, gives the wordmark deliberate optical spacing, and adds a dedicated 16px symbol.

This directory owns the reviewed brand artwork, its authoring inputs and the deterministic Android artwork export. The [UI roadmap](../../ui-redesign-roadmap.md) owns Android visual intent and implementation status. The ten palette values match the existing Android color resources. The approved symbol and outlined wordmark now supply Android launcher, tile, Home and TV artwork; broader identity migration remains in [#71](https://github.com/novakin/vpn0wn-android/issues/71).

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

Sora Variable is the artwork font: weight 800 for the outlined wordmark, 600 for headings, 500 for labels, and 400 for body copy. Android uses the approved outlined wordmark as artwork and retains Inter Tight and JetBrains Mono for interface text. Optical gaps are explicitly set in `generate.mjs`. SVG and Android wordmarks contain outlines, so they do not require installed Sora fonts. The source font and its SIL Open Font License are in `fonts/`; retain the license when distributing the font.

Font source: <https://github.com/google/fonts/tree/main/ofl/sora>.

To reproduce the assets from the repository root, run:

```powershell
Push-Location docs/assets/vpnown-brand
npm ci
node generate.mjs
Pop-Location
```

Dependencies stay in this artwork directory and are ignored by Git. The generator checks every palette swatch's rendered center against its declared hex value. [Development](../../development.md) owns Android build and verification commands.

## Android export

From the repository root, after installing the locked artwork dependencies, run:

```powershell
node docs/assets/vpnown-brand/generate.mjs --android
```

The optional flag also writes the resources below into [`ui/src/main/res`](../../../ui/src/main/res), using the same frame, slash, gradient, font outlines and optical gaps as the reference assets. It leaves the reference artwork byte-identical. Running without the flag writes only the reference artwork and palette records. Android export uses no additional dependency, font installation, SVG parser or small-PNG resampling.

Known generator follow-up under [#71](https://github.com/novakin/vpn0wn-android/issues/71#issuecomment-6225617777): the shared upstream-header template omits the modification notice currently retained in six inherited Android XML outputs. Preserve/reapply that existing notice when reviewing an Android export until the template is repaired, keeping the declaration, original copyright and SPDX intact under the [source-notice contract](../../license-attribution.md#fork-source-notices). The audit traced the template statically; it did not regenerate or alter the current artwork.

| Resource | Geometry and use |
| --- | --- |
| `drawable/vpnown_symbol.xml` | Transparent 96 × 112 main-master gradient symbol for standalone artwork at 24dp and above. |
| `drawable/vpnown_wordmark.xml` | Transparent plain outlined wordmark, 730 × 206 viewport and 146 × 41.2dp intrinsic size; interface code controls displayed size. |
| `drawable/ic_launcher_foreground.xml` | Bare gradient symbol on a 108 × 108dp adaptive layer. Main-master scale 0.5, translation (30, 26); visible bounds (35..73, 29..79). The furthest slash tip is 31.4dp from center, inside the central 66dp safe circle. |
| `drawable/ic_launcher_monochrome.xml` | Separate solid white alpha mask with identical adaptive geometry. Both existing adaptive wrappers reference this mask. |
| `values/ic_launcher_background.xml` | Existing background name now uses `vpn0wn_paper_2`, the approved app-icon navy `#0A1727`. |
| `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher*.png` | Native 48, 72, 96, 144 and 192px exports. The square variant retains the approved 200/1024 corner radius; the round variant uses the approved navy circle. Both retain the approved 650/1024 logical-height symbol. |
| `drawable/ic_tile.xml` | Transparent solid white main master in a 24 × 24dp viewport; existing system tint and OFF-state treatment remain with the tile owner. |
| `drawable/tv_logo_banner.xml` | Same transparent plain outlined wordmark and viewport as `vpnown_wordmark`; preserve its aspect ratio. |
| `mipmap-xhdpi/banner.png` | 320 × 180px navy banner with the complete plain wordmark centered in a 272px-wide area. Its 24px side margins exceed one quarter of the rendered lowercase height. No small WireGuard descriptor. |

All Android artwork disables automatic mirroring. No current Android consumer needs a 16dp standalone mark, so the separate optical 16px master remains in the reference assets. The original [visual acceptance](ACCEPTANCE.md) retains its static-reference scope; Android source checks, native output and device acceptance are separate evidence. Artwork generation does not assemble or install an APK.

Brand copy is retained from the reference. This work covers visual identity and color usage; it does not verify VPN service behavior.
