# WG Tunnel and Tunnl comparative source assessment

Last updated: 2026-10-10 (Europe/Paris).

## Scope and evidence

The maintainer requested a comparison of WG Tunnel and this codebase after the [feature assessment in #72](https://github.com/novakin/vpn0wn-android/issues/72). Tunnl is the current application; vpn0wn and `com.vpn0wn.android` remain proposed under [#71](https://github.com/novakin/vpn0wn-android/issues/71).

| Source | Reviewed revision | Boundary |
| --- | --- | --- |
| Tunnl original assessment | `6bb17579787ad0cff34213d6500332ea7731bc1d` | Merged Android/config/native/provisioning source at the original assessment. |
| Tunnl current-source update | `f4a4b147568e6aaa3c82a4307aa6690977e966bd` | Scoped reconciliation of merged #63 native protection/warm-up and #78 model statistics; see the update below. |
| WG Tunnel Android inspected release, 5.7.5 | `0dbf07c38ace5e05252b21a62e39ea88e91185a2` | Release tag resolved through GitHub; published 2026-09-26. Pins core 1.7.3. |
| WG Tunnel core 1.7.3 | `254733e4b9b8d494dde1ebcd2f486ed1c4a9266e` | Released app's selected backend/parser/runtime source. |
| WG Tunnel Android master | `e1fa22884302f32abd1c3f8f4185b1623cc3a92c` | Ahead of release: 42 changed files. Pins core 1.8.1. |
| WG Tunnel core 1.8.1 | `8998f922f7796d04068cca20ae172ce2c8636733` | Current-source changes; do not credit these as released 5.7.5 behavior. |
| WG Tunnel's resolved AmneziaWG fork | `c434976f60d478b42893cc84abb3a08a8705109e` | Selected by both inspected core Go module replacements; IPC/device source inspected for the teardown trace. |

Read-only source analysis traced automation, native socket/service/IPC lifetime, transitions, health/recovery, DNS/proxy/lockdown, persistence/import/export, external control, UI ownership, build/CI and documentation. Inventories span the checked-out repositories; deeper review targets consequential flows, not every dependency or line. This is not an exhaustive security audit or a measured app benchmark.

GitHub source/release/check metadata was read on 2026-10-08 (Europe/Paris). No app build, tests, install, traffic, phone/server mutation or upstream publication was performed for this comparison. Earlier tests retain their original scope. At the original assessment, [#63](https://github.com/novakin/vpn0wn-android/pull/63), head `be7150d36815dc82ef24d45210188658e359c5cd`, was draft/unmerged and was excluded from the original master comparison. The current-source update below supersedes that delivery limitation without relabeling the old evidence.

## Verdict by purpose

**WG Tunnel's inspected versions offer the broader client feature set. Tunnl is a smaller, more directly regression-tested base for the customized app and its retained kernel support. The current-source repairs remove two Tunnl findings below; end-to-end reliability, speed, battery and overall security have no established winner.**

The table compares the original pinned revisions. Read its native/statistics limitations with the current-source update; it does not claim a new review of later WG Tunnel releases.

| Criterion | Assessment | Decisive evidence or limit |
| --- | --- | --- |
| Network automation and endpoint recovery | WG Tunnel ahead | Released network rules, manual override, native failure-driven DDNS/address-family recovery. |
| AmneziaWG, advanced DNS and proxy capability | WG Tunnel ahead | Connected config/backend/UI paths; Tunnl rejects AWG fields and #72 is a proposal. |
| Targeted regression tests and normal PR checks | Tunnl ahead in inspected source | Meaningful JVM/native ownership/failure tests and unit/Detekt/native CI; WG's inspected first-party tests are sparse. |
| Native socket and teardown safety | Mixed | WG protects at socket creation but has JNI callback lifetime and accepted-client teardown gaps; Tunnl drains clients/queries but master protects native descriptors late and ignores failures. |
| Root/kernel and older Android compatibility | Tunnl ahead | Actual kernel backend/multiple-tunnel behavior; minimum API 24 versus WG API 26. Device/support coverage is separate. |
| App release/distribution and user feature guides | WG Tunnel ahead | Released APK/AAB/distribution workflows and public feature documentation. Tunnl CI currently documents debug artifacts and controlled delivery. |
| Local change/process traceability | Tunnl ahead for our workflow | Explicit owners/contracts, dated repair evidence, independent-review/readback and scoped authorization rules. This does not establish stronger runtime behavior. |
| UI quality and accessibility | Undecided | Different product designs; no fresh matched rendering/device/accessibility comparison. Compose versus DataBinding does not determine quality. |
| Transfer speed, battery and DPI effectiveness | Undecided | No matched A/B comparison of these apps. Extra features, stronger tests or successful builds do not prove these outcomes. |

No combined numeric grade is assigned: weighting feature breadth, kernel support, engineering assurance and performance depends on the intended product. A precise-looking total would conceal the unmeasured dimensions.

## Feature and architecture comparison

### Automation and recovery

Released core 1.7.3 has an [AutoTunnelEngine](https://github.com/wgtunnel/core/blob/254733e4b9b8d494dde1ebcd2f486ed1c4a9266e/backend/src/commonMain/kotlin/com/wgtunnel/backend/autotunnel/AutoTunnelEngine.kt#L26) with SSID/BSSID trust/mappings, cellular/Ethernet preferences and captive-portal/no-network handling. Its [reconciler](https://github.com/wgtunnel/core/blob/254733e4b9b8d494dde1ebcd2f486ed1c4a9266e/backend/src/commonMain/kotlin/com/wgtunnel/backend/autotunnel/AutoTunnelReconciler.kt#L113) retains manual override until the underlying network fingerprint changes and bounds retries. The Android service supplies network/settings flows to the shared coordinator. Tunnl's manager/boot paths restore saved running intent; on-demand automation remains pending in the UI roadmap.

Released [TunnelRecovery](https://github.com/wgtunnel/core/blob/254733e4b9b8d494dde1ebcd2f486ed1c4a9266e/backend/src/commonMain/kotlin/com/wgtunnel/backend/features/TunnelRecovery.kt#L142) responds to native handshake failures with DDNS resolution, IPv4 fallback and bounded transport bounce. [Android endpoint resolution](https://github.com/wgtunnel/core/blob/254733e4b9b8d494dde1ebcd2f486ed1c4a9266e/backend/src/androidMain/kotlin/com/wgtunnel/backend/dns/SystemDnsResolver.android.kt#L19) selects the underlying Network. Core 1.8.1 additionally changes wake/network recovery budgets and failed-restart runtime retention; those improvements are current-source behavior, not release 5.7.5 proof.

Tunnl's visible status uses handshake age and a connecting grace; one recent peer can influence aggregate protection and idle age can produce an error indication. WG consumes native status events, but the reviewed event wiring alone does not establish every peer's reachability or application Internet health. Neither status display is packet-delivery proof.

### Configurations, modes and platform boundaries

WG supports standard WG/AWG config fields through its parser and native engine, with split/encrypted DNS, SOCKS5/HTTP and custom Lockdown connected through actual coordinator/mode owners. Full AWG header/prefix/header-protection settings still require a compatible matching peer; extra client-only packets are not equivalent to full obfuscation. The resolved engine is a WG Tunnel fork, not the unmodified official AmneziaWG module named in the require line.

The [released coordinator](https://github.com/wgtunnel/android/blob/0dbf07c38ace5e05252b21a62e39ea88e91185a2/app/src/main/java/com/zaneschepke/wireguardautotunnel/core/orchestration/TunnelCoordinator.kt#L211) stops existing tunnels for exclusive activation. Retained `isMultiTunnelEnabled` and `autoTunnelApps` fields are not evidence of working simultaneous/app-triggered automation. Root/Shizuku Wi-Fi detection is not a kernel VPN backend. Android master adds groups/database version 40 and revises Wi-Fi detection; those deltas are not credited to 5.7.5.

Tunnl retains [WgQuickBackend](https://github.com/novakin/vpn0wn-android/blob/6bb17579787ad0cff34213d6500332ea7731bc1d/tunnel/src/main/java/com/wireguard/android/backend/WgQuickBackend.java#L123), kernel multiple-tunnel policy and userspace single-tunnel behavior. Supported WG parser round trips and file-replacement semantics already have focused coverage. AWG/advanced DNS/proxy support would require coordinated owner changes; a complete core replacement would also require explicitly retaining or removing kernel compatibility.

WG custom Lockdown adds a dedicated VPN TUN, HEV SOCKS forwarding, local proxy and netstack. It is a real feature, but its additional lifecycle/data-path contracts need profiling and leak tests. Proxy mode protects traffic sent through its proxies; it does not itself protect all device traffic. Tunnl's standard Android always-on/lockdown integration is a narrower starting point.

### Presentation, storage and permissions

WG uses Compose, ViewModels, repositories, Room and shared Kotlin core state. MainActivity and SharedAppViewModel coordination remains broad. Tunnl uses DataBinding/Fragments, observable models and existing manager/store/backend boundaries, with destroyed-view/selection guards. More layers and a newer UI framework do not independently prove better ownership or usability. Wholesale UI/storage migration would discard current behavior and evidence that still need preservation.

WG has exported Room schemas, migration history and optional password-encrypted whole-database backup/restore. [DatabaseModule](https://github.com/wgtunnel/android/blob/0dbf07c38ace5e05252b21a62e39ea88e91185a2/app/src/main/java/com/zaneschepke/wireguardautotunnel/di/DatabaseModule.kt#L48) builds an ordinary Room database and enables destructive fallback; missing migration paths can therefore erase records. Profiles/passwords/control keys are stored in normal database columns, without configured live-database encryption. Tunnl stores profiles in private files and fleet/settings in DataStore; its ZIP export contains configs, not complete settings/fleet backup. Both disable Android system backup. UI PIN/biometric/screen-capture gates do not encrypt persisted profiles or exported private configurations.

WG remote control requires an explicit enable setting and supplied shared key. Tunnl additionally uses the manifest's Android control permission plus its enable preference. These are different external-control contracts, not proof that either app's entire security design is superior.

Tunnl's VPS screens/scripts are a future product direction. Concrete SSH transport, protected credential persistence and full live action/QR wiring remain incomplete; they cannot be counted as delivered differentiation. See [operations](../operations.md#vps-feature-status).

## Concrete source findings and consequences

These are traced source paths, not newly reproduced device incidents. They prevent a blanket reliability/security winner.

| Project | Finding | Evidence and next boundary |
| --- | --- | --- |
| Tunnl at `6bb17579` | Native socket protection occurred after native startup; raw descriptor `protect()` results were ignored, and later native rebind lacked a protect-at-Open hook. | [GoBackend](https://github.com/novakin/vpn0wn-android/blob/6bb17579787ad0cff34213d6500332ea7731bc1d/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java#L478), [native bind owner](https://github.com/novakin/vpn0wn-android/blob/6bb17579787ad0cff34213d6500332ea7731bc1d/tunnel/tools/libwg-go/tunnelbind.go#L19). Protection failure for an available native socket could still publish UP; replacement descriptors could escape the one-time Java protection. No observed leak was established. #63 subsequently delivered native creation/rebind protection; this is a resolved historical source finding, with physical limits recorded below. |
| WG core 1.7.3/1.8.1 | Socket protection is correctly attempted at creation, but the JNI protector reference/method is read and replaced without owned callback lifetime or synchronization. | [bind hook](https://github.com/wgtunnel/core/blob/254733e4b9b8d494dde1ebcd2f486ed1c4a9266e/backend/native/backend/bind/bind_android.go#L20), [JNI callback/setter](https://github.com/wgtunnel/core/blob/254733e4b9b8d494dde1ebcd2f486ed1c4a9266e/backend/native/backend/jni/vpn_jni_android.c#L146), [service teardown](https://github.com/wgtunnel/core/blob/254733e4b9b8d494dde1ebcd2f486ed1c4a9266e/backend/src/androidMain/kotlin/com/wgtunnel/backend/service/VpnService.kt#L81). Rebind/bootstrap workers can race deletion of the global JNI reference during destroy/revoke. Source-level lifetime defect; no crash reproduction. A reused hook needs synchronized reference ownership and concurrency controls. |
| WG core 1.7.3/1.8.1 | UAPI listener close does not close accepted clients before device shutdown. | [accept/stop](https://github.com/wgtunnel/core/blob/254733e4b9b8d494dde1ebcd2f486ed1c4a9266e/backend/native/backend/vpn/vpn_shared.go#L112), pinned [listener Close](https://github.com/wgtunnel/amneziawg-go/blob/c434976f60d4/ipc/uapi_linux.go#L37), [IPC scanner](https://github.com/wgtunnel/amneziawg-go/blob/c434976f60d4/device/uapi.go#L235), [device Close](https://github.com/wgtunnel/amneziawg-go/blob/c434976f60d4/device/device.go#L427). A local accepted client sending `set=1` followed by newline, then withholding the request's terminating blank line, can retain the IPC mutex and block shutdown. This is not a demonstrated remote attack or normal-user incident. Tunnl's tracked-client close/drain and native-query shutdown tests cover this ownership boundary. |
| WG Android, including 5.7.5 | Downloads export can report success after a null output stream and lacks owned-row deletion on write failure/cancellation. | [FileUtils](https://github.com/wgtunnel/android/blob/0dbf07c38ace5e05252b21a62e39ea88e91185a2/app/src/main/java/com/zaneschepke/wireguardautotunnel/util/FileUtils.kt#L299) publishes/returns the row after nullable `openOutputStream()?.use`. Export coordinator treats the URI as success. Conditional provider/write failure path; no device reproduction. Tunnl's Downloads/export cleanup has targeted regression coverage. |
| Tunnl at `6bb17579` | Covered UI work and obsolete shared statistics were known gaps. | [#47](https://github.com/novakin/vpn0wn-android/issues/47), [#48](https://github.com/novakin/vpn0wn-android/issues/48) and [source investigation](2026-10-07-performance-source-investigation.md). #78 subsequently delivered the shared-statistics correctness repair and closed #48. #47 remains open; source fixes do not establish battery savings. |

Tunnl's tracked UAPI clients, terminal native bind closure, admitted-query drain and identity-checked statistics publication are strengths of the repaired native boundary. WG's admitted start/stop/bootstrap use deliberate cancellation settlement too, but a NonCancellable region does not cancel a blocked native call. Both projects still need physical proof for the relevant end-to-end lifetimes.

## Test, CI, release and documentation evidence

At Tunnl `6bb17579`, the original source inventory counted 249 JVM `@Test` methods and 18 top-level Go Test functions. These are historical source counts, not current totals, coverage percentages or this review's execution totals. Meaningful suites cover config compatibility, failed writes, transition/cancellation/teardown, UI lifecycle, export ownership, secret-safe handling and native IPC/query races. Some upstream/dependency and actual-device behavior remains outside them.

WG Android's inspected master has seven first-party test source files: six example/template tests and one schema migration test from version 6 to 7. Pinned core 1.7.3 and 1.8.1 have no tracked first-party test sources in the inspected test/source inventory. Upstream native dependencies have their own tests; this does not claim WG has no testing, users or external QA. Its exported database schemas are useful, but the inspected test does not prove current schema/data preservation.

Tunnl's [normal CI](../../.github/workflows/android.yml) runs unit tests, Detekt and native host/race controls. The [original master check run](https://github.com/novakin/vpn0wn-android/actions/runs/37677091955) succeeded at `6bb17579`; this comparison did not execute it. Later repair verification is linked below. WG's [PR workflow](https://github.com/wgtunnel/android/blob/e1fa22884302f32abd1c3f8f4185b1623cc3a92c/.github/workflows/pr.yml#L29) runs wrapper validation/formatCheck. Inspected build/release workflows assemble APK/AAB and publish artifacts; no equivalent normal JVM/native regression gate was found. Hosted CodeQL check metadata includes actions/ruby analysis, not proof of Kotlin/native analysis.

GitHub readback confirms successful APK/AAB jobs for WG release 5.7.5 and successful current-head nightly APK assembly. One current-head nightly publication job failed while its build succeeded; that is a publication outcome, not evidence that the app fails to compile. This review neither installed those artifacts nor audited distribution services. WG's release/product documentation is ahead of Tunnl's controlled debug-artifact workflow; Tunnl's agent/engineering instructions are better aligned with our existing review and delivery process.

## Recommended direction

For the existing custom UI, kernel compatibility and planned VPS product, keep Tunnl's current foundation. Native socket creation/rebind protection is now delivered. Assess the remaining bounded behaviors: genuine health/recovery events, DDNS/address-family recovery, explicit network-rule precedence/manual override and optional AWG. Preserve the targeted local mutation/export and IPC/query ownership controls. [#72](https://github.com/novakin/vpn0wn-android/issues/72) owns the feature proposal; it does not approve implementation.

If the priority becomes a broad ready-made client with automation/AWG/DNS/proxy support and kernel support is unnecessary, WG Tunnel is a serious alternative foundation. Its broader feature set can reduce new feature work, but its storage/UI/core contracts, identified defects, migration and licensing still need explicit acceptance. Replacing our whole codebase solely because WG has more features is not justified by this review.

#63's native protection and same-socket warm-up are adopted; their source contracts and remaining acceptance stay separate from AWG and later recovery experiments. Reused code must retain MIT/component notices and dependency-specific licenses; inspect the resolved fork, not only the public API or advertised module name. Advanced DNS, custom Lockdown, proxy and Xray remain separately scoped candidates.

A speed/battery/reliability winner needs authorized paired tests of comparable plain-WG configurations on the same phone/server/network: matched routing/DNS/keepalive/MTU, reversed test order, repeated transfer and screen-off/wake windows, and packet/energy evidence. Use the existing [QA protocol](../qa-test-plan.md#transfer-speed-and-battery-validation). Hardware suspend, carrier DPI outcomes and all-peer/application reachability need explicit proof beyond framework Doze and handshake status.

## Current-source update — 2026-10-08

This update reads Tunnl at `f4a4b147568e6aaa3c82a4307aa6690977e966bd` and the actual #63/#78 delivery records. It reconciles those two repairs only; it does not rerun the upstream review or comparative device/traffic tests.

- [PR #63](https://github.com/novakin/vpn0wn-android/pull/63) merged native protection inside `androidbind.Bind.Open` for creation/rebind, exact-service/JNI callback ownership and same-socket warm-up. Protection failure closes unpublished sockets and fails setup. The old late-protection finding is resolved in source. [Current contracts](../backend-contracts.md#wireguard-configuration-and-tunnel-state) and the [experiment record](2026-10-07-protocol-preflight-experiment.md) retain verification scope. [#61](https://github.com/novakin/vpn0wn-android/issues/61) remains open for physical lifecycle, unchanged-server interoperability and DPI acceptance.
- [PR #78](https://github.com/novakin/vpn0wn-android/pull/78) merged manager-owned equivalent statistics reads, cache-only bindings and generation/backend/name/membership validation. Reader cancellation, completed failures and retry retain their contracts; the original snapshot identity and freshness are preserved. #48 is closed. Its [final-head CI](https://github.com/novakin/vpn0wn-android/actions/runs/37740066483), independent reviews and [presentation contracts](../backend-contracts.md#persistence-and-presentation) own proof and limits.
- [#47](https://github.com/novakin/vpn0wn-android/issues/47) remains the covered-UI battery candidate. [#52](https://github.com/novakin/vpn0wn-android/issues/52) owns physical baselines and guardrails. Establish measured CPU/wakeup/energy baselines and freeze targets before implementation; statistics coalescing does not establish an energy benefit.

These repairs improve specific ownership boundaries, rather than establish an end-to-end reliability or security winner. The feature-breadth/kernel/product-direction verdict above remains bounded to the inspected revisions. No comparative speed, battery, DPI or security outcome is added.

### Refactoring implications

No wholesale UI/storage/core migration is justified by this assessment. #48 extends the existing manager/model owners without a new statistics service or scheduler. Keep those boundaries and the current DataBinding/Fragment architecture. Any future extraction must solve a concrete ownership or duplication problem in the approved slice and retain its cancellation, persistence, config and compatibility proof. The health/automation/AWG issues under #72 need their own decisions and implementation authorization; they are not permission for a general refactor.

## Review and documentation impact

Independent ultra review accepted the original assessment pinned to `6bb17579` on 2026-10-08 (Europe/Paris), with no remaining material findings. Review corroborated the conditional native/export findings, test/CI inventories, source pins and release/master distinction, including available-socket and owner-naming clarifications. That acceptance does not cover the later current-source update; the delivery PR owns its independent review and documentation checks. This establishes source-assessment quality, not runtime reproduction, measured coverage or comparative performance. Findings do not authorize upstream disclosure, implementation or APK/device/server activity.

The maintainer's merge-and-continue instruction on 2026-10-08 (Europe/Paris) resumes delivery of this already-requested assessment through the existing PR process. This change adds the assessment, links it from README and the plan, and records #48 delivery in the owning plan/changelog. Runtime contracts, setup and agent process are unchanged, so their guides need no content/date edits. Existing historical grades/tests are preserved, no priorities are silently promoted and no competing feature backlog is created. Publication/merge state belongs in the delivery PR; documentation does not authorize a runtime feature, APK or live operation.
