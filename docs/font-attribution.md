# Font Attribution

Last updated: 2026-10-09 (Europe/Paris).

The UI redesign bundles the following fonts under `ui/src/main/res/font`, matching the families referenced by the read-only prototype. Versions and notices below come from the embedded SFNT name tables, inspected on 2026-10-06; they do not establish the original binary download revision or a reproducible build.

| Asset | Embedded version | Copyright and official project |
| --- | --- | --- |
| `inter_tight.ttf` | 3.004 | Copyright 2022 The Inter Project Authors; [Inter Tight](https://github.com/rsms/inter-tight). |
| `jetbrains_mono_regular.ttf`, `jetbrains_mono_medium.ttf` | 2.305 | Copyright 2020 The JetBrains Mono Project Authors; [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono). |

Both families use SIL Open Font License 1.1. Embedded short notices/URLs alone did not supply the full license. Full upstream notices now live in the conventional main asset directory so source distributions retain them and standard Android asset packaging can include them:

- [Inter Tight OFL](../ui/src/main/assets/licenses/inter-tight-OFL.txt), retrieved from [Google Fonts commit 87338f0](https://github.com/google/fonts/blob/87338f0add37763e05760424870846463a2ed1dd/ofl/intertight/OFL.txt).
- [JetBrains Mono OFL](../ui/src/main/assets/licenses/jetbrains-mono-OFL.txt), retrieved from [Google Fonts commit 2e05c1c](https://github.com/google/fonts/blob/2e05c1cf00a6e4f40a4b931600a90881c26e15cd/ofl/jetbrainsmono/OFL.txt).

These pinned license sources identify the notice copies, not the provenance of the existing font binaries. Preserve copyright and full license with redistributed fonts; follow reserved-name and modification terms when replacing them. Do not date or rewrite the externally owned license texts.

No APK was assembled for this attribution change. Verify both license assets are present in the actual APK before distribution; source placement is not executed packaging evidence.

Settings now includes both complete font notices in its offline Licenses & credits text. [Software attribution](license-attribution.md) owns that source slice and pending artifact/distribution gates; existing binaries and their provenance limits remain unchanged.
