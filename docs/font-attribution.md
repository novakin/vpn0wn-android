# Font Attribution

Last updated: 2026-10-11 (Europe/Paris).

The UI redesign bundles the following fonts under `ui/src/main/res/font`, matching the families referenced by the read-only prototype. Versions and notices below come from the embedded SFNT name tables, inspected on 2026-10-06; they do not establish the original binary download revision or a reproducible build.

| Asset | Embedded version | Copyright and official project |
| --- | --- | --- |
| `inter_tight.ttf` | 3.004 | Copyright 2022 The Inter Project Authors; [Inter Tight](https://github.com/rsms/inter-tight). |
| `jetbrains_mono_regular.ttf`, `jetbrains_mono_medium.ttf` | 2.305 | Copyright 2020 The JetBrains Mono Project Authors; [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono). |

## Verified upstream binary identity — 2026-10-11

Read-only comparisons matched all three existing UI fonts and the Sora artwork source font to downloaded immutable official repository files. SHA256 and Git blob comparisons establish exact upstream binary identity. The original local acquisition history and a reproduced source build remain unknown; embedded version metadata alone does not establish the binary revision.

| Local font | Official binary | SHA256 |
| --- | --- | --- |
| `inter_tight.ttf` | [Google Fonts `fac60545`](https://github.com/google/fonts/blob/fac60545cc0de9d4eff6eb5b1ab88bcdfc9ecc69/ofl/intertight/InterTight%5Bwght%5D.ttf), 2022-12-09 | `b81b73dcb64df3c230cabade7df6c5773bf863233f24c9ee51087519f1f88b6f` |
| `jetbrains_mono_regular.ttf` | [JetBrains Mono `02bb50b0`](https://github.com/JetBrains/JetBrainsMono/blob/02bb50b082dad9ef8a0f33ac393839202b760223/fonts/ttf/JetBrainsMono-Regular.ttf), 2024-08-08 | `e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1` |
| `jetbrains_mono_medium.ttf` | [JetBrains Mono `02bb50b0`](https://github.com/JetBrains/JetBrainsMono/blob/02bb50b082dad9ef8a0f33ac393839202b760223/fonts/ttf/JetBrainsMono-Medium.ttf), 2024-08-08 | `d16e6dc99672734698d629705f617c79f6eb6040f5113efe3a145204dc988109` |
| `docs/assets/vpnown-brand/fonts/Sora-Variable.ttf` | [Google Fonts `69109d1f`](https://github.com/google/fonts/blob/69109d1f48319d1d474a8d2f70018efc2346a3a7/ofl/sora/Sora%5Bwght%5D.ttf), 2020-06-11 | `84ff7096ae3ec6c8be47d906d1a0ba4de7f2ce78c615275c77301964a316e16c` |

The JetBrains binaries match the 2024 “Built fonts” commit. Both differ from the earlier 2023 v2.305 repository binaries despite identical embedded version text. This identifies repository bytes, not a v2.305 release archive. The three UI fonts remain unchanged since local introduction `669dd68d`; Sora remains unchanged since brand-reference introduction `1aa852ba`. These local revisions establish checked-in continuity, not the acquisition procedure.

Inter Tight and both Sora OFL copies match their corresponding pinned official notices exactly. The retained Google Fonts JetBrains notice and the 2024 project notice differ only in FAQ wrapping, one blank line and trailing whitespace; complete copyright/license wording matches. Existing notice-copy pins below remain valid and separate from binary identity. No font or external notice bytes changed in this assessment.

## Full license copies

Both families use SIL Open Font License 1.1. Embedded short notices/URLs alone did not supply the full license. Full upstream notices now live in the conventional main asset directory so source distributions retain them and standard Android asset packaging can include them:

- [Inter Tight OFL](../ui/src/main/assets/licenses/inter-tight-OFL.txt), retrieved from [Google Fonts commit 87338f0](https://github.com/google/fonts/blob/87338f0add37763e05760424870846463a2ed1dd/ofl/intertight/OFL.txt).
- [JetBrains Mono OFL](../ui/src/main/assets/licenses/jetbrains-mono-OFL.txt), retrieved from [Google Fonts commit 2e05c1c](https://github.com/google/fonts/blob/2e05c1cf00a6e4f40a4b931600a90881c26e15cd/ofl/jetbrainsmono/OFL.txt).

These pinned license sources identify the notice copies, not the provenance of the existing font binaries. Preserve copyright and full license with redistributed fonts; follow reserved-name and modification terms when replacing them. Do not date or rewrite the externally owned license texts.

The original full-license-copy addition had no APK assembly. The later [2026-10-11 composed unsigned release audit](reviews/2026-10-11-release-artifact-acceptance.md) verifies actual OFL assets and all three resource-resolved UI font binaries against source and the official identities above. That scoped retention proof supplies no signed distribution or reproduced source build.

Settings includes these complete UI-font notices in its offline Licenses & credits text, alongside the Sora artwork notice described below. [Software attribution](license-attribution.md) owns that source slice and pending artifact/distribution gates; font bytes remain unchanged, and the verified binary identity above is separate from the remaining acquisition/source-build limits.

## Outlined brand artwork

The approved [vpnØwn reference](assets/vpnown-brand/README.md) derives its outlined wordmark from Sora Variable, Copyright 2019 The Sora Project Authors, under SIL Open Font License 1.1. Android uses those outlines as logo artwork; Sora is not installed as a UI font. Inter Tight and JetBrains Mono text roles remain unchanged.

The [packaged Sora OFL copy](../ui/src/main/assets/licenses/sora-OFL.txt) preserves the [reference's original notice](assets/vpnown-brand/fonts/OFL.txt) exactly, including its existing whitespace. The complete wording also appears in offline Licenses & credits with the bundle's established whitespace normalization. The [Android adoption record](ui-redesign-roadmap.md#android-brand-asset-adoption--2026-10-10) owns output and artifact acceptance; the earlier font-asset claims retain their original scope.
