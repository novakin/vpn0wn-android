# App-data transfer acceptance — legacy-ID preparation

Last updated: 2026-10-09 (Europe/Paris).

## Scope and provenance

Scoped synthetic AVD acceptance for [#71](https://github.com/novakin/wireguard-android/issues/71), through [PR #102](https://github.com/novakin/wireguard-android/pull/102). This records the original transfer APK and corrective working source; final reviewed/tested commit and current integration state belong in the PR acceptance record. At this handoff, #71 remains open and PR #102 remains draft. No new-ID migration, release or physical-phone acceptance is delivered.

The maintainer authorized APK assembly and installation while preserving existing AVD data, using synthetic fixtures only. Every device operation targeted Android 16/API 36 x86_64 `emulator-5580`, initially 1080×2400 at density 420. The physical phone received no commands. Private baseline data and evidence remain ignored under `.artifacts/transfer-avd/`.

| Artifact | Provenance |
| --- | --- |
| Original installed transfer APK | Source `8ab6a8062d66180c1c0b327fd51346e27ba3fa4b`; SHA256 `E361D0D5D43B1F20A6EABC4DC35DE7258C416630B0121D9B2FDCC34B74E68A07`. Includes `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. |
| Revised installed APK | Corrective working source based on the original revision; SHA256 `8C3C8F8F9DAEFE8121842E11175B5DBA5BDBE9574EE3FDDFBE1162C43D0064C2`. |
| Signing continuity | Both APKs match the preserved baseline signer SHA256 `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. |

Installed APK pullbacks establish the hashes above. Signer continuity establishes compatibility with this preserved debug installation, not production signing or execution of every packaged ABI.

## Original APK observations

Native screenshots and hierarchy XML establish the picker, Settings, confirmation and system credential surfaces. `preference-readback.json` compares captured DataStore maps and reports all checks passed.

| Action | Observed result and boundary |
| --- | --- |
| Picker and confirmation cancellation | Returned to Settings without import; original preferences remained unchanged. |
| Malformed archive | A declared profile was missing. Fixed transfer-error feedback appeared; original preferences remained unchanged and no profiles imported. |
| Full archive | Two profiles and one saved fleet record imported. Canonical stored configs matched the fixture; new profiles were DOWN. Portable settings, imported selection and fleet matched. Nonportable preferences remained preserved. |
| Repeat and conflicting imports | Existing data remained unchanged; the conflict's extra profile C was absent. Persistent readback supports preservation. Visible repeat/conflict result feedback was not captured, so those messages are not accepted here. |
| Settings-only archive | Native success reported zero new profiles. Readback changed exactly `dark_theme` and `allow_remote_control_intents`; existing profiles, fleet, selection and nonportable values remained preserved. |
| Export without a configured lock | Native export succeeded. Archive CRCs, exact inventory, manifest and canonical profile bytes passed readback. |
| Export with a synthetic PIN | Native prompt cancellation created no additional output; later correct-PIN success produced an archive. Original PIN-export CRCs, inventory, manifest and canonical profile bytes passed readback. |

An extra Enter during the original PIN sequence briefly brought synthetic profile A UP, then DOWN; retained backend evidence records both transitions. The fixture had no peers or endpoints, so no server traffic followed. Settled captures show DOWN and subsequent service readback found no current VPN service. This prevents a claim that no tunnel activation occurred. It does not prove preservation of an unrelated active VPN.

## Corrective source and revised APK

`BiometricAuthenticator.authenticate` and its export/private-key callers now distinguish explicit no-lock absence from authentication failure. Only a present, platform-reported nonsecure Keyguard together with `BIOMETRIC_ERROR_NONE_ENROLLED` or `BIOMETRIC_ERROR_NO_HARDWARE` permits the action without a prompt. Configured authentication requires prompt success. Other preflight failures and runtime authentication errors deny authorization; cancellation remains cancellation and a failed biometric sample remains nonterminal.

The revised APK displayed Android's PIN prompt. Cancellation created no additional file; correct-PIN success exported. After removing the temporary PIN, revised no-lock export also succeeded. All four original/revised exports passed CRC, exact inventory, profile-byte and manifest readback. Earlier UP selection legitimately made A the current `last_used_tunnel`; revised manifests matched their actual source DataStore, rather than the earlier import fixture. The post-full and settings-only snapshots independently prove imported B selection at those boundaries.

The revised ordinary file-import path displayed `Use Settings → Import app data for this full transfer ZIP.` Persistent preferences remained byte-for-byte unchanged, and only the two existing synthetic profiles were present.

Confirmation now labels counts separately as `Profiles: 2. Saved servers: 1.` Restarted Home showed two inactive profiles. Recreating a pending confirmation dismissed it without importing. Native Settings and confirmation output were inspected in light portrait, light landscape at 2400×1080, dark theme with font scale 2, and dark 320dp width at 840×1920. Real Arabic app locale and Android `ldrtl` established the RTL run; a shell force-RTL setting that remained `ldltr` is excluded. New transfer copy fell back to English, so this is layout evidence, not translation acceptance.

## Local checks

The full gate initially exhausted the default 512 MiB test-worker heap in unchanged `LogcatStreamTest`. `ui/build.gradle.kts` now gives UI test workers a 1 GiB heap; no APK runtime memory setting or coverage was reduced. The 12 real-helper authentication executions retain their SDK controls, including API 24/29/30 paths.

`./gradlew :ui:testDebugUnitTest :tunnel:testDebugUnitTest :ui:detekt --console=plain` completed successfully after that repair. Retained log: `.artifacts/codex-command-logs/20261009-221510-645-36b9fa3650664ec6b916b913d4543667.log`. Reports contain 361 UI and 16 tunnel tests, with zero failures, errors or skips; Detekt has zero findings. In this final invocation, tunnel tests and Detekt were up-to-date; their retained results are distinct from the rerun UI suite. These local checks cover corrective working source, not a later commit automatically.

## Restoration and acceptance limits

The app was stopped before restoration. Current profiles matched the owned fixture bytes; the preference map contained only expected transfer/selection changes, with original nonportable values preserved. Only the two owned profile files were removed, and the original DataStore bytes and 0600 mode were restored. Restarted Home showed the original zero-profile state. Final preferences still matched the baseline SHA256 `2b7cf7cb7198dcaacf06cffad79c72c308bd861f3d8e0f122477912481591032`; no saved synthetic fleet or selection remained. The runtime-generated `profileInstalled` marker was retained rather than replacing the whole sandbox with a stale archive.

Four exports and the uploaded fixture files were removed only after remote/host hash agreement. Their owned MediaStore rows and empty directory were removed and read back absent. The temporary PIN was removed; native verification confirmed no credential and the original disabled lock state. Font scale 1, night mode, sensor rotation, user rotation 0, absent global force-RTL setting, false force-RTL property, empty app-locale override and original display size were restored and read back. The revised signer-compatible APK stays installed. Private baseline backups and host evidence remain retained; no unrelated app, AVD or phone data was cleared.

Physical biometrics/OEM behavior, API <29 legacy permission, provider acquisition/write faults, administrator/non-owner denial, actual TalkBack, unrelated active-VPN preservation, kernel/root paths, power loss/process-death recovery and old/new-ID migration remain unproved. No DPI, performance, speed or battery result follows from these checks. [QA](../qa-test-plan.md#full-app-data-transfer-acceptance), [operations](../operations.md#app-data-transfer) and [contracts](../backend-contracts.md#full-app-data-transfer) own those boundaries.

Only this sanitized historical assessment belongs in source. PINs, private keys, configurations, export archives, raw logs, screenshots and device reports remain private ignored evidence. This record adds scoped evidence; it changes no runtime policy or architecture.

## Manual smoke follow-up — 2026-10-09

The later authorized AVD session used source `e4efaea0d424e279d04ceee5dee3e8e396115d33` and the same installed revised APK SHA256 above. No APK was rebuilt. A fresh baseline on `emulator-5580` again showed zero profiles, no package VPN service, the original DataStore hash, launcher foreground and restored device values. Private evidence is retained under `.artifacts/transfer-smoke-20261009-2333/`; the previous session's backup was not used to restore this run.

Native ordinary ZIP import created two uniquely named synthetic profiles with only a private key and a documentation-range interface address. Both had no peers, endpoints, DNS, ListenPort or default routes. Home and Detail rendered both identities; Home, Detail and Editor were recreated in portrait and 2400×1080 landscape. An unsaved Editor MTU of 1410 survived rotation, then cancellation preserved the canonical stored configs. Actual output was inspected; this is scoped rotation and state proof, not a complete size, theme or accessibility matrix.

A planned synthetic UP established the native GoBackend VPN service. It remained running while the app was backgrounded, and returning to Home showed the owned tunnel still enabled with NO SIGNAL, as expected without a peer. The tunnel was then returned DOWN. Active drawing prevented a settled hierarchy capture after resume; the screenshot and native service readback establish that boundary. Animation settings were not changed. No remote endpoint, peer traffic or nonzero live statistics were tested.

Native logs opened, ERROR and ALL filters changed the selected chip, and the system share chooser presented `wireguard-log.txt`. Cancel returned to logs without selecting a recipient or sending a message. Native Home deletion confirmation listed exactly the two owned profiles; completion reported two deleted, Home showed zero profiles, and persistent config inventory was empty.

After deletion, every app file and mode matched the fresh two-file baseline exactly, including the original DataStore bytes and runtime marker. No preference rewrite was needed. The uploaded fixture and its exact MediaStore row were removed after hash and identity checks. Original rotation, font, timeout, theme, display, locale, RTL and tile settings were compared and restored where changed; launcher foreground and no package VPN service were verified. The existing APK and VPN authorization remained installed. Screenshots, ZIPs and raw reports remain ignored.

### Fresh-install VPN consent

A separate new private API 36 x86_64 AVD used only the preserved system-image/device configuration, with newly created userdata under `.artifacts/transfer-consent-20261009-2342/`. No existing userdata was copied or reset. Every explicit device command selected `emulator-5584`; the visible AVD and physical phone were preserved. The first 45-second boot wait expired before the emulator's recorded 52.31-second startup completed; a subsequent boot-property check confirmed readiness before installation. Emulator startup's unqualified timeout-adjustment attempts were rejected because multiple emulators were present.

The target package was initially absent. Installing the retained candidate required no assembly, and its installed hash matched the revised APK above. Initial `ACTIVATE_VPN` state was default `ignore`. A native ordinary import created one new endpoint-free synthetic profile. Android's actual Connection request showed the legacy debug identity; Cancel left the switch off, authorization `ignore` and no package VPN service. Retry and OK changed authorization to `allow`, enabled the owned tunnel and established the native GoBackend service. The tunnel was returned DOWN and deleted through the native confirmation; zero profiles were read back.

The private fixture and owned MediaStore row were removed after hash/identity checks. Original timeout and rotation values were verified, and uninstall restored the initially absent package, removing its app data and permission identity. No package service remained. Only the owned private emulator was stopped; its frontend and ports were read back absent. The original AVD configuration stayed byte-identical, and `emulator-5580` remained online with the original DataStore hash. Private AVD files and evidence were retained rather than recursively deleted.

### Remaining smoke boundaries

Nonzero live statistics remain untested: endpoint-free UP establishes backend activation and presentation, not authenticated traffic or VPN health. VPS-wizard rotation was unavailable through production navigation; this source contains the `VpsProvisioningFragment` declaration without a caller or Home route. No route was injected and no provisioning action was taken. These two smoke boundaries remain explicit; the run does not complete physical, root/kernel, provider-fault, TalkBack, full identity-migration, DPI, performance or energy acceptance. Existing guides already own those gates; this evidence-only update changes no behavior, setup, contract or process.
