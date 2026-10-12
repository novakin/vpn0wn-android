# Rooted empty-Home centering acceptance — #80

Last updated: 2026-10-12 (Europe/Paris).

The [empty-state repair](../ui-redesign-roadmap.md#empty-home-visible-centering--2026-10-11) is verified on the available rooted Pixel 7 Pro/API 34. This record adds physical geometry/output and installation/preservation evidence. It does not complete [#80](https://github.com/novakin/vpn0wn-android/issues/80) or [G4/#52](https://github.com/novakin/vpn0wn-android/issues/52); populated Home, spoken TalkBack and other device/navigation/action gates remain separate.

## Artifact and device scope

The device was identified anew after connection: Pixel 7 Pro, Android 14/API 34, Magisk root and native three-button navigation. Only the legacy `com.wireguard.android.debug` was installed initially; `com.vpn0wn.android.debug` was absent. The standing maintainer authorization covers APK assembly/installation and rooted-device testing until revoked. No legacy uninstall, data clear, personal tunnel import/export, VPN toggle or server operation occurred.

Clean source `d54cf2b171d882a98ccf9d099ba2b2d1823bfc0d` was assembled through the documented WSL command on Gradle 9.8.1/OpenJDK 21.0.10. Assembly exited 0 in 21.955 seconds: 74 tasks, 10 executed, one cached and 63 up-to-date; configuration cache was reused. This is incremental assembly, not clean-build or release reproduction. Prior APK/output metadata were retained first; tracked source, submodules and eleven build inputs stayed unchanged. The later #160 merge changed documentation only.

The retained APK is 26,016,651 bytes, SHA256 `be6f115ec8e07cc45f92aa6233dd8c87c17930b1959277106ea821d2d07eead5`. Independent binary-manifest/AAPT and APKSigner checks identify `com.vpn0wn.android.debug`, version `1.0.20260315-debug`/519, debuggable, minimum API 24 and target API 36. One valid signer has certificate SHA256 `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. Installation into the separate sandbox succeeded; actual installed-APK pullback matched the retained APK exactly. Rooted device access did not require restarting shared ADB or changing adbd's UID.

## Actual output and geometry

Uncompressed native hierarchies, PNGs and the resumed activity's actual configuration are retained per frame. Density is 420dpi, giving 2.625px/dp; the symbol view stays 294px/112dp and Add stays 126px/48dp. The group is the union of the symbol and guidance bounds, centered against the clipped visible empty viewport above Add. This is group centering; existing start-aligned wrapped guidance is preserved.

| Actual frame/state | Result |
| --- | --- |
| Normal French portrait, expanded header | Group center error `(0, -0.5)`px; complete guidance and Add visible. |
| Normal French portrait, collapsed header | Error `(0, 0)`px. |
| Normal French landscape, collapsed header | Error `(0, 0)`px; native side navigation/cutout clearance retained. |
| 2× French portrait after requested downward gesture | Actual header remained collapsed; error `(0, -0.5)`px. No expanded-state claim from its requested label. |
| 2× French portrait, settled fresh startup | Actual expanded header; error `(0, 0)`px and complete guidance. |
| 2× Arabic app-locale portrait, night mode | Actual RTL configuration; fitting centering/clearance passed. |
| 2× Arabic app-locale portrait, day mode | Actual RTL/day configuration; fitting centering/clearance passed. |
| 2× RTL/day landscape, expanded header | Guidance clipped; no fitting-centering claim. Add remains visible. |
| Same compact landscape after actual header collapse | Whole group visible; error `(-0.5, -0.5)`px. |

All nine actual configurations agree with native PNG orientation and 420dpi. Every Add target retains 48dp height and its top matches the empty viewport's bottom. Across fitting frames, maximum center error is **0.5px**, below the one-dp contract. This does not assert simultaneous fit under an expanded compact header; native collapse provides the required space. Actual rendered normal portrait/landscape and large text were inspected. No design asset, font, palette, source layout or binding was changed by this session.

## Operator failures and preservation

The first legacy-file collector suppressed missing-path errors and its file proof is not accepted. Before installation, a strict replacement inspected each store directory, propagated failures and retained the exact four file hashes. The original incomplete capture remains local. A too-early capture immediately after process restart failed foreground verification before producing an accepted frame. A waited start and settled configuration supplied the subsequent valid expanded-header evidence; failed output was not relabeled as success.

Temporary mutations affected system font scale, rotation, night mode and the new app's locale only. After testing, all **eleven checked original setting values/presence states** matched, and the app-locale override was restored to `[]`. All four legacy store-file hashes matched the strict pre-install collection. Two owned remote hierarchy files were removed and their absence checked. The verified new APK remains installed alongside the retained legacy app, with the original French/system settings restored and the new empty Home reopened. A final restored PNG is retained.

These checks establish their stated settings/file boundaries, not equality of all Android system state or complete cross-app migration. No personal configs, raw hash paths, screen notifications or host reports are committed. The session adds no authentication/provider, tunnel transition/deletion, root-tools installer, protocol/DPI, actual hardware suspend, transfer-speed or battery proof. The phone was USB-powered; no energy result follows.

## Retained evidence and owning process

Build material is under `.artifacts/overnight-20261012/current-master-apk-20261012/`: build-provenance receipt SHA256 `03046ddb3ccc86ed11fa0a07129cf6366b991a871bcff8d91517c65e59bbe650`; independent APK-verification receipt `ee61fd5f588fac88f9a466a75c4d96a6630a96ed33f8b0a0643833f3a9ea1ddb`. Device proof is under `.artifacts/overnight-20261012/rooted-home-center-20261012/`: strict baseline, installation/pullback, actual-frame records and restoration. Private artifacts stay local.

Main `.artifacts/codex-command-logs/` retains the terminal quiet runs: strict baseline `20261012-091643-307-13ed86bbbf83465ba091aea0ca3260c2.log`, installation `20261012-091728-032-85164b363fcb4c309c1309ff3e4854e3.log`, installed pullback/start `20261012-091833-597-0aa3fc52480343c186c4ba573d2a1b31.log` and restore `20261012-093944-102-48ede33afd014123a110ee8592ec0553.log`. Each enclosing command exited 0. This phone controller retained scripts, output, wrapper results and local timestamps, rather than a receipt for each ADB child. `installation.json` has no observation timestamp; do not infer stronger command-level chronology or independent live remeasurement from the retained file audit.

The scoped documentation PR references #80 without closing it and retains #52's broader action gates. Final-head independent ultra review, actual checks and publication/merge state belong in that PR acceptance record; this historical runtime record is not independent approval or new release authorization.
