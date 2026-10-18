# Native backup picker and Downloads acceptance

Last updated: 2026-10-18 (Europe/Paris).

## Scope and provenance

Related to [#196](https://github.com/novakin/vpn0wn-android/issues/196) and [#28](https://github.com/novakin/vpn0wn-android/issues/28); both remain open. One physical Pixel 7 Pro/API34 trial exercises the real MainActivity/Fragment, DocumentsUI and MediaStore. No production source changed. [Inspection contract](../backend-contracts.md#local-encrypted-backup-inspection--196) and [QA](../qa-test-plan.md#native-backup-picker-and-downloads-controls--19628) retain wider acceptance.

Executed clean source: `0bc2dfef1ed1cca53e2b0c0b8b10b70b7908324f`, tree `7a2ce6a76881a6bbf3af3d572e982944bdc0e043`. Matching before/after receipts cover 355 scoped APK-source inputs, five compiled harness inputs, ten producer inputs and two initialized clean native gitlinks. This source differs from the later documentation head.

APK SHA256: `644db4b44095e2b6f8de86c81fd00bd3b74ca33dc1947ca3334282f8b3ff4d9c`. Actual manifest, APK-v2 signature, asset, native identity and meaningful DEX checks pass. The arm64-v8a package `local.vpn0wn.backuppickerprobe.debug` declares minimum/target API24/36, the real Application, non-exported MainActivity, one read-only signature-protected DocumentsProvider and self-target instrumentation. USE_BIOMETRIC is its only permission; no INTERNET, VPN service or receiver. Installed bytes match the artifact.

The provider shares the isolated consumer UID; this proves real system-picker routing/read behavior, not cross-UID grant enforcement. The actual authenticator uses its no-configured-authentication branch. No configured lock, personal file or server is involved. The synthetic encrypted fixture is 489 bytes.

Private evidence: `.artifacts/continuation-20261018/backup-picker-prep/runs/20261018-025410-360dcd/`. `audit-r2/` owns artifact proof; `device-attempt-04/` owns accepted native/action/cleanup receipts. Raw UI, device identifiers and private preservation hashes stay outside tracked source.

## Executed controls

Host and native exits are zero. Exactly five ordered DocumentsUI checkpoints and one `INSTRUMENTATION_CODE: -1` occur. All eight native Boolean facts pass, including cleanup; these are not eight independent scenarios.

| Boundary | Observed result |
| --- | --- |
| Actual picker cancellation | Action/fence retired; import re-enabled; no archive read or transport. |
| Recreation with picker outstanding | Actual stale selection discarded by replacement Activity; no password window/read. |
| Password dismissal | Secure native form/editor/action/URI retired without reading. |
| Password recreation | Old editor/form retired; actual saved-state bytes exclude the synthetic password; replacement has no inspection/action. |
| Exact-file inspection and Close | Ciphertext hash, decrypted snapshot/config/revoked record and safe summary match; Close clears state. No Restore or Compare action. |
| Actual MediaStore success/injected failure | Exact success bytes; original IOException after acquisition; descriptor closed, failed row absent and control row preserved before cleanup. |
| Post-acquisition cancellation | Callback held then released; actual cancellation drains; original cancellation, closed descriptor and row absence verified before cleanup. |
| Native cleanup | Owned jobs, descriptors, fixture and recorded output rows settle; local stores unchanged. |

The app UID reads the fixture once; zero fixture opens are attributed to DocumentsUI; transport calls remain zero. The driver uses 173 bounded explicit-device commands and observed exact synthetic selectors. No result-registry injection or substitute picker is used.

## Cleanup and earlier failures

All thirteen host checked facts pass; cleanup errors are empty. Before uninstall, independent root checks find no owned MediaStore row, private fixture or matching descriptor in the isolated UID's live processes. Identity checks precede owned uninstall; package/path/process absence follows. The exact original task returns. Sampled original app private hashes, packages, global/secure/system settings, VPN state, ADB mappings and boot identity match. These are sampled observations, not complete immutability or heap-erasure proof.

The original auditor rejected a guessed 64-class bound against 71 generated harness classes. A separately reviewed supplemental auditor preserves every original source/signature/manifest check, uses the existing 96-total-owner capacity and writes fresh output. All 89 relevant DEX bodies are retained; meaningful control-flow review covers the exercised harness and product owners, not every unrelated ViewModel method. The original ten producer inputs and APK remain unchanged.

Three device attempts remain overall failures: equivalent relative/full Activity names rejected before picker input; English navigation text rejected after successful cancellation; duplicate breadcrumb/provider title rejected after opening the drawer. Each passes all eleven sampled cleanup/preservation checks with no cleanup errors. Narrow independently reviewed checker corrections normalize equivalent components, use the observed French ImageButton and apply the existing roots-list constraint before uniqueness. Earlier partial progress does not replace the accepted complete trial.

## Limits

This accepts one same-UID/no-configured-authentication API34 trial per case. Cross-UID grants, configured biometrics/PIN, arbitrary-provider timeouts/query/open failure, deletion refusal, pre29 storage/permissions, broader OEM/platform behavior, screenshots/IME/spoken TalkBack, heap erasure and production/release acceptance remain separate. Write failure is injected, not organic. No latency, battery or reliability cohort, live restoration/cutover/client traffic, same-server replacement policy or full #196/#28 closure follows.
