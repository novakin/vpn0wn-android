# Composed release artifact acceptance

Last updated: 2026-10-11 (Europe/Paris).

## Scope and source

Separately authorized host assembly and inspection cover the composed, clean source `15eb25239d876579ab60609efcc35413dde589f3`. Its tree equals the later PR #133 merge `6469ffb3d2f314261c78c9548dae4e79381ff867`. This includes current namespace, native/helper naming, provisioning assets, source notices, offline fork NOTICE and retained component/font credits. No source change, signing, installation, distribution or device trial occurred in this audit. #71 remains open.

WSL/JDK 21 ran `:ui:assembleRelease :ui:assembleGoogleplay --console=plain` with explicit linked-worktree Git paths. Gradle succeeded in 2m58s: 161 tasks, 135 executed and 26 from cache. Both variants enable code/resource shrinking. The actual outputs and complete R8 output directories were retained privately before later builds could replace them.

## Actual outputs

| Field | Release | Google Play |
| --- | --- | --- |
| Output | `ui-release-unsigned.apk` | `ui-googleplay-unsigned.apk` |
| Application ID | `com.vpn0wn.android` | `com.vpn0wn.android` |
| Label | `vpn0wn` | `vpn0wn` |
| Version / code | `1.0.20260315` / `519` | `1.0.20260315` / `519` |
| Bytes | 17,723,070 | 17,723,070 |
| SHA256 | `fd8d37a2cb9d80cc197db9da3cbdc289ca1079b49b433f0b124c5d8ff3ac2ab6` | Same exact bytes |

Actual output metadata, binary manifests and badging agree. No v1 signer entries or APK signing block exists; `apksigner verify` rejects the unsigned artifacts. No certificate, installability or signed release is claimed. Version values are the current source configuration, not a release announcement.

## Executed retention checks

- Binary manifests retain the current Application, private Licenses activity/Settings parent, private exported-log provider, dangerous tunnel-control permission/actions and VPN/tile service permissions. The app is not debuggable. The removed installer permission is absent.
- All 15 packaged main assets match the audited Git objects exactly: four license assets and the provisioning README plus ten scripts. Credits contain 29 whole notices; their first notice copies root NOTICE verbatim. Font/component license wording retains its source bytes.
- All three UI fonts resolve through the shrunk resource table to actual font entries and match the source and [official binary identities](../font-attribution.md#verified-upstream-binary-identity--2026-10-11). Resource filenames changed during shrinking; verification did not assume their source paths. Sora remains an artwork source font rather than a replacement UI font.
- All 16 native libraries are present: four libraries in each of four configured ABIs. ELF machines match their directories. Each `libwg-go.so` retains five current GoBackend JNI exports, non-debug private paths and current native tags. Go metadata reports Go 1.24.3 and pinned WireGuard/external modules. Each DataStore native library's allocated ELF sections match its exact cached 1.2.1 AAR despite stripping.
- Actual R8 9.1.31 mapping, configuration and removed-members output establish shrinking with the existing `-dontobfuscate` policy. All three exact JNI callback signatures survive. DEX inspection traces both version-click and Settings Licenses navigation, including the merged Settings click handler's target branch. App donation/updater classes and the removed donation preference are absent. This is static reachability, not executed navigation.

The independent audit reused existing SFNT/DEX and allocated-ELF-section readers, plus current namespace/log checks. Initial audit attempts rejected host-proof assumptions about Windows worktree pointers, numeric permission output and R8 handler merging; corrected inspection then passed both artifacts. No product/artifact defect was found. Failed logs remain retained.

Private evidence lives under `.artifacts/overnight-20261011/release-audit/`: per-variant `artifact-audit.json`, manifests/resources/DEX/native inspection, `summary.json`, and copied `retained-output/` artifacts/R8 files. Build log: `20261011-044330-954-cc60a0bc31d249b68ae75544394fab29.log`; successful audit log: `20261011-044931-957-7023e3a36dea4971bfb0e0ce47b87c4b.log`. Raw metadata and retained artifacts stay outside source distribution.

## Remaining limits

This closes only the recorded unsigned-artifact inclusion/retention checks. It proves no Android runtime, UI accessibility, physical VPN health, signed distribution, full migration or root-tools installation. Remaining source lineage/declarations, third-party/toolchain notice closure and applicable corresponding-source/relinking obligations stay under [attribution](../license-attribution.md) and #71. Compiler strings and complete asset copies do not settle those obligations. Font acquisition history and reproduced source builds remain unknown.
