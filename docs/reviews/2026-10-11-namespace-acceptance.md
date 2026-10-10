# vpn0wn namespace: scoped source and Android acceptance

Last updated: 2026-10-11 (Europe/Paris).

## Scope and provenance

This record covers the source/application namespace slice of [#71](https://github.com/novakin/vpn0wn-android/issues/71). Namespace source is represented by `7ead52cdc71c06bda92b83c6b60e43a021790dbc`. Local debug/host checks retain their source-equivalent precommit scope; release startup, APK and runtime checks used that literal revision. This is partial acceptance, not complete identity migration, final-head independent approval, merge, release or issue closure. [The identity contract](../backend-contracts.md#application-and-source-identity--71) owns retained protocol, persisted, control-action and tools-admission boundaries.

Root operated the authorized local build and rooted Pixel 7 Pro session. This documentation pass reparsed retained JVM/Detekt reports and artifact metadata; runtime observations below use the operator's retained evidence. The legacy app remained installed alongside `com.vpn0wn.android.debug`. No production-server migration, system-wide tools installation or release forms part of this run.

| Boundary | Executed provenance |
| --- | --- |
| Android target | Rooted Pixel 7 Pro, Android 14/API 34; new debug application `com.vpn0wn.android.debug` alongside the preserved legacy application. |
| Android artifact | Four-ABI debug APK from the source revision above; SHA-256 `9c6c47a27cee9a4ed72730c7908150ce5ec7f2c8009eaf583097886a68455a4f`. Installed artifact readback matches. |
| Synthetic input | Python-generated full-transfer ZIP containing a synthetic profile, five portable settings and one non-secret synthetic saved server. Import used the new app's native document-provider flow and confirmation. It was not exported by the legacy app. |
| Traffic path | Synthetic WireGuard peer on the same phone; authenticated inner HTTP before/after manual rebind and after explicit reconnect. This does not establish Internet, independent-host or broader server interoperability. |
| Rebind invocation | Debugger manually invoked the public rebind primitive and observed replacement-socket protection results and selected activation identities. Debugger scheduling changes execution timing. |

Private profiles/archives, keys, endpoints, screenshots, device metadata, debugger output and restoration reports remain ignored under the repository root's `.artifacts/overnight-20261011/namespace/` and `.artifacts/codex-command-logs/`. No raw private evidence is committed. `artifact-check.json`, `installed-candidate.json` and `restoration-result.json` retain artifact/source/preservation readbacks locally; their contents are not copied into source distribution.

## Executed source and artifact checks

| Check | Result and boundary |
| --- | --- |
| Debug JVM suites | 483 tests across 53 UI/tunnel suites; zero failures, errors or skips. The report begins at `2026-10-10T22:48:12Z`, before namespace commits; this is source-equivalent precommit proof. Current `com.vpn0wn.*` selectors execute the renamed inventory. |
| Detekt | Zero findings. |
| Targeted release startup | One `ApplicationBackendLifecycleTest` control passed with zero failures, errors or skips; report timestamp `2026-10-10T22:59:53Z`, after literal `7ead52c`. It exercises non-debug startup without native activation; it is not a full release-suite or release-artifact run. |
| Host native suite | Six Go race groups and seven JNI host controls passed through `test-host.sh` against the source-equivalent precommit namespace tree. The first invocation's unset `JAVA_HOME` setup failure remains retained; the configured rerun passed. Host tests do not prove Android lifecycle behavior. |
| Debug assembly and native payload | Debug APK assembly passed for all four configured ABIs. Artifact inspection found the exact five renamed `Java_com_vpn0wn_android_backend_GoBackend_` exports in each ABI and the debug private package paths. Non-debug native artifacts were not built in this run. |
| Manifest and generated references | Three debug/release/googleplay merged manifests and 46 generated binding classes inspected for coherent identity. The independent control permission/provider authorities, renamed components and sole legacy REFRESH alias agree with the contract. |
| Fork POM | Generated metadata declares `com.vpn0wn.android:tunnel:1.0.20260315`, `vpn0wn Tunnel Library` and the fork source location. No Maven publication occurred. |

The debug report predates `ca00b549d0fe11da52147c28c151a66fedf9bf78` at `2026-10-11T00:56:45+02:00`; `7ead52c` followed at `00:56:55+02:00`. Artifact metadata pins literal `7ead52c`. This record does not relabel precommit checks as commit-executed tests, turn subsequent documentation edits into a new source/build run or replace final-head CI/review readback.

## Installed Android observations

| Control | Observed result and limit |
| --- | --- |
| Independent new installation | Fresh empty sandbox and successful startup beside the legacy installation. Native Settings and JNI backend-version reporting opened under the new identity. No inherited legacy private data is claimed. |
| Native full-transfer import | Document-provider selection and explicit confirmation imported the synthetic archive. Process recreation retained its profile DOWN. Persisted DataStore readback verifies five imported settings and one saved synthetic server; all synthetic profile fields are preserved. Serialized profile bytes differ through canonical formatting, so byte-preservation is not claimed. This proves the bounded new-ID import path, not old-app export or complete old/new-app migration. |
| Fresh VPN authorization | Android presented consent for the new application ID; accepted activation completed and authenticated inner HTTP passed. Legacy authorization was not treated as inherited. |
| One manual rebind | Both replacement-socket protection callbacks returned `true` during one successful manual rebind, selected activation identities remained retained within the debugger observations, and authenticated HTTP passed afterward. No TUN/socket descriptor or native bind-generation snapshots bracket this call; retained-TUN/socket replacement proof is not claimed for this renamed artifact. |
| Detail configuration | Generated Detail output shows the synthetic profile's configured MTU `1280`. This is scoped rendering/configuration evidence, not effective-MTU or full theme/font/RTL/accessibility acceptance. |
| Normal DOWN/reconnect/DOWN | Normal deactivation, explicit reconnect with authenticated inner HTTP, and final DOWN state passed. This is sequential manager/VPN-state evidence, not observed Android service destruction or a concurrent lifecycle race. |

Two service-destruction observer attempts timed out. `stopservice` and app-op actions did not establish `VpnService.onDestroy` or complete native retirement. The candidate's temporary `ACTIVATE_VPN=ignore` was restored to `allow` before normal DOWN; uninstall restores its original absence. Their failed/unproven evidence remains retained. A duplicate switch locator was test-helper ambiguity, not an observed product defect. Normal DOWN must not be substituted for the missing destruction callbacks/postconditions.

The earlier [legacy-ID rebind record](2026-10-10-native-rebind-acceptance.md) has separate descriptor/retained-TUN and actual service-destruction proof. Those results keep their original source/artifact scope; they do not fill this renamed artifact's gaps.

## Restoration and preservation

Final operator readback reports all 12 preservation fields equal to the captured originals, with zero mismatches. Fields cover the legacy APK path/hash, saved config and preference hashes, module/tool paths, system/accessibility/VPN settings, interfaces/MTUs, Magisk policy table, tile inventory and legacy-app locale. A separate runtime audit confirmed all 12 exact matches and the original `MainActivity`. The documentation pass checked the retained summary's field count, empty mismatch list and absence results. Private field values remain local.

Owned peer, JDWP forwarding, device scratch/upload/hierarchy resources and the session-owned new debug package are absent. Only that new package and its added UID Magisk denial were removed; the final policy table matches its original. Cleanup first halted safely because `/sdcard` resolves through `/storage/emulated/0`; it completed after validating the exact owned canonical path. This failed admission is retained, not treated as successful cleanup.

Final restored output shows the original Home and personal kernel tunnel still UP. Personal profiles were not toggled; original saved config/preferences and device settings remain preserved. This bounded restoration is complete. It does not supply missing renamed service-destruction callbacks or an old-app export/new-app migration control.

## Acceptance boundary

The checks and installed controls establish coherent renamed JVM/generated/native identity, debug startup, synthetic new-ID import and activation, authenticated same-phone peer traffic, bounded manual rebind/protection results and sequential DOWN/reconnect. They do not complete old-app exporter/new-app migration, retained-TUN/descriptor proof or actual service-destruction acceptance for this artifact.

First-party Tunnl helper/resource/log/export naming, [draft #103](https://github.com/novakin/vpn0wn-android/pull/103) ownership-safe tools integration and root/kernel proof, fork NOTICE/modification audit, actual release/googleplay payload/signing/distribution obligations, complete transfer/rollback and remaining lifecycle/device gates keep #71 open. New-ID tile/boot/always-on, control-permission enforcement, full UI/theme/font/RTL/TalkBack, protection denial, concurrency/cancellation, handover, packet/IPv6/unchanged-server and broader physical acceptance are not established here. No DPI, leak protection, battery, speed, hardware-suspend or timing claim follows from these controls.

The current PR acceptance must separately identify final reviewed head, independent findings/resolution, executed checks/CI, documentation impact and actual publication/integration state. This local report authorizes no new operation and closes no existing device/performance issue.
