# vpnØwn visual acceptance — 10 October 2026

Last updated: 2026-10-10 (Europe/Paris).

Scope: reviewed identity refinement from the supplied brand board, maintained in `wireguard-android/docs/assets/vpnown-brand`. No Android runtime source, launcher resources, user state, or live system was changed. Skills applied: design-system, UI/UX Pro Max, frontend-design, and imagegen's source/asset guidance. Verification follows the focused verify-and-stop approach. Interface/code audits were outside this static-artwork scope.

Revision: final v3 brand reference, imported into clean `wireguard-android` branch `master` at base `09e7eed8`. The artwork and owning-doc reference are local uncommitted changes. Environment: Windows, PowerShell, Node.js v26.7.0.

## Evidence

- Source: `generate.mjs`, exact palette in `tokens.json`, licensed Sora source in `fonts/`.
- Render: `node generate.mjs`, using locked `fontkit` and `@resvg/resvg-js` dependencies on Windows with Node.js.
- Final board: 1600 × 1200 PNG and outlined SVG.
- All ten rendered swatch centers match the supplied hex values exactly. `verification.json` records the samples.
- Those ten palette values also match the current Android color resources. Sora is limited to this artwork; Android typography, launcher adoption, and broader identity migration remain separate.
- Regeneration passed from this repository's artwork directory. Final placement checks passed all ten PNG/Android palette comparisons, SVG parsing, 54 local documentation links, dates/fences, and `git diff --check`. The earlier misplaced Vpn0wn asset directory and reference paragraph were removed from that project.
- Main, solid, white, navy, and wordmark symbols reuse one geometry. The 16px optical master intentionally widens the counter and retains both slash terminals.
- Native 16/24/32px samples were visually inspected on dark and light backgrounds. Enlarged views contain actual PNG samples, not newly rendered larger vectors.
- Final board and outlined typography were visually inspected at native output size. Optical spacing, variant alignment, labels, and footer identity were reviewed independently.
- Independent PowerShell/System.Drawing verification passed all ten exported PNG palette samples, parsed all 18 SVGs, found no font-dependent text elements, confirmed identical main variant paths, and checked native 16/24/32/1024px export dimensions.
- A fresh review after the footer and raster-proof fixes found no remaining material visual faults in this scope.
- The original standalone delivery archive contains 35 files. The repository reference adds a local `.gitignore` for authoring dependencies. A project-local archive is retained under ignored `.artifacts/brand`; dependencies and private command logs are excluded.

## Color usage

Declared primary/secondary/muted text on navy measures 18.02:1, 12.64:1, and 8.22:1. Navy text on cyan measures 8.86:1. Off-white text on cyan measures 2.03:1, so it is not the button-text pairing. Active border on navy measures 2.66:1; cyan is the focus token.

## Limits

Native rasterization was verified with resvg, not every browser or operating-system icon renderer. No actual application interaction, screen-reader flow, or VPN capability was tested. Independent agent review supplies technical/design review, not human approval or a trademark assessment.
