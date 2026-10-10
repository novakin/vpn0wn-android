# Detail peer handshake observations: rooted Android acceptance

Last updated: 2026-10-10 (Europe/Paris).

## Scope and provenance

This record covers the presentation slice in [PR #113](https://github.com/novakin/vpn0wn-android/pull/113), related to [#73](https://github.com/novakin/vpn0wn-android/issues/73). The maintainer authorized build, data-preserving installation and rooted-phone acceptance on 2026-10-10. This adds no automatic recovery, DNS, endpoint rewriting or new polling. The phone was operated by the author; the evidence audit inspected retained provenance and hierarchy records without operating the device. Final visual inspection and restoration were author-performed. The PR owns final-head independent ultra review, CI and delivery.

| Boundary | Executed provenance |
| --- | --- |
| Target | Rooted Pixel 7 Pro, Android 14; debug application. Existing Wi-Fi traverses a VPN and no cellular path is available. No independent DPI or direct/VPN comparison is possible. The visible emulator was untouched. |
| Candidate | Clean source `854204ae4590c1e41f148912647cd1a999dad555`, based on master `b3d3591e047f9afe79b443007127ac1ce32bd990`. Authorized `:ui:assembleDebug` passed; `adb install -r` preserved application data. Installed APK pullback matched SHA256 `771c88471a5271223d6454918d5fd5410984bb1b41ae6c07eb7e43f46dd66d3a`. Later documentation commits do not turn this run into a new final-head runtime test. |
| Retained baseline | Original APK matches the [#111 installed acceptance](2026-10-10-detail-mtu-acceptance.md) binary built from `46e51c046746240e5f495ba5c0784de96cac0d8b`. That source differs from the candidate base only in documentation; the original binary was not built from the base SHA. |
| Synthetic path | Fresh two-peer userspace profile: one ordinary local WireGuard peer responds through loopback UDP; the other endpoint has no listener. A separate endpoint-free peer tests available-but-unobserved diagnostics. Explicit ports and narrow synthetic routes keep personal profiles out of these trials. |
| Evidence | APKs, configs, screenshots, hierarchy captures, settings bytes and system/network reports remain ignored under `.artifacts/issue113-device/`. No private profile name, endpoint, key, device identifier or raw system report is published. |

## Observed results

| Control | Result and boundary |
| --- | --- |
| Kernel backend | Personal kernel Detail displayed observations unavailable and `—` for observed endpoint/family. Kernel handshake-event telemetry is not invented from statistics. |
| Userspace authentication | Synthetic responding peer displayed `Authentication observed`, its numeric loopback endpoint and `IPv4`. HTTP through the synthetic peer returned the exact fixed `tunnl-native-peer` body before and after reconnect. This proves scoped authenticated traffic, not Internet reachability. |
| Other configured peer | The nonresponding peer displayed its own endpoint/family and `Initiation sent locally`; a later captured native retry exhaustion displayed `Retries exhausted`. It did not inherit the responding peer's authentication label. Local send does not establish remote delivery. Later native activity may replace the latest-event label; the model retains separate event-kind facts. |
| Manual DOWN | Both peer cards changed to observations unavailable; observed endpoint/family cleared to `—`. The control asserted both rows, rather than assuming a stopped switch cleared diagnostics. |
| Selection and reconnect | Selecting the separate DOWN profile showed one unavailable peer instead of copying prior diagnostics. Fresh explicit activation of the two-peer profile displayed new authentication and local-send observations; synthetic HTTP passed again. |
| Endpoint-free activation | An active userspace peer without Endpoint or PersistentKeepalive displayed `No handshake event observed` and unknown endpoint/family. No traffic was generated for this control. UP alone did not become authentication. |
| Output and recreation | Six full-Detail scrolling sequences covered portrait with system day/night requests, landscape, landscape at 2× text, Arabic RTL portrait at 2× text and French portrait at 2× text with a system day request. The fixed dark palette remained in both system modes. Retained physical output confirms rotation, enlarged text and mirrored RTL navigation; representative top, peer-event and bottom captures were visually inspected. New rows wrap, remain readable and scroll into view without ellipsis. Captures are 1080×2340 or 2340×1080 pixels at the device's existing density. |

The complete screen inspection also exposed an existing topology-header gap: in 2× RTL portrait, the UP marker touches the `FIG.01` title. The topology header was unchanged by this PR. The [UI roadmap](../ui-redesign-roadmap.md#fidelity-gaps--remaining) retains that unresolved output issue under the broader [#35](https://github.com/novakin/vpn0wn-android/issues/35) acceptance. This run does not establish complete Detail fidelity or close that issue.

Exact public-key/map attribution, rebuilt configurations, selection/session invalidation and generated binding cleanup retain the separate 35-test source proof. Physical public keys are elided; observed endpoint/status consistency does not replace those deterministic controls. A destroyed-view unbind prevents later notifications but cannot cancel rendering already queued for a discarded view.

## Capture failures and verification limits

Stock `uiautomator dump` timed out waiting for idle while app output continued updating. The retained zero-idle hierarchy helper initially failed because its legacy runner lacked Android test classes. Explicitly supplying the installed Android test/uiautomator classpath produced fresh captures without disabling app/system animations. A duplicate-bounds helper assertion also failed before a synthetic toggle; the retry targeted the same observed switch bounds. These are retained operator/capture failures, not product failures.

The matrix script requested settings/locale changes without checking every mutation exit status. Actual captured geometry, enlarged text and RTL output support the stated physical configurations; filenames alone do not. Not every scrolling frame received independent visual review. No distinct light palette, physical send-failure injection, IPv6 traffic, event counts, timing guarantee, TalkBack speech or exhaustive accessibility acceptance is claimed. Native/JVM tests retain their own environments and limits.

## Restoration and delivery boundary

Original two profile files remained byte-identical and the final inventory contained exactly those files. Settings DataStore was restored while the process was stopped, then read back byte-identical after restart and explicit original-state restoration. The original kernel backend and one-UP/one-DOWN set were verified through native interface state. All 13 captured system values, app locale and night mode matched their initial values, including animation, accessibility and VPN-policy values. This compares the recorded policy values; it does not exercise biometric/PIN prompts.

Owned synthetic profiles, peer process, fixture directory, helper files and test UDP sockets were removed and absence verified. The peer command ended after intentional termination. No download/export or personal config edit was involved. The reviewed candidate remains installed; the original APK is retained privately for rollback. No phone operation remains required for this slice.

The passing build, install and matrix logs are retained in ignored command-log directories. Final restoration ran through `restore.ps1` and passed its byte/state/settings assertions. CI run [38071519519](https://github.com/novakin/vpn0wn-android/actions/runs/38071519519) covers the candidate source: 451 UI plus 16 tunnel tests, zero failures/errors/skips and zero Detekt findings, with native/offline checks passing. The final documentation head requires its own normal CI/readback and independent ultra verdict in the PR.

This acceptance supports merging the bounded presenter after those final gates. #73 remains open for automatic recovery, bootstrap/DNS, address-family fallback, complete diagnostics and physical network/packet acceptance. #35 retains broader output/accessibility acceptance; #61 retains its remaining lifecycle/interoperability/DPI gates. No release, Internet health, DPI, transfer-speed or battery improvement is established.
