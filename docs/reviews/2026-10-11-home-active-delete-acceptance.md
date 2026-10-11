# Home active-delete failure and recovery acceptance

Last updated: 2026-10-11 (Europe/Paris).

## Result and scope

This partial [#36](https://github.com/novakin/vpn0wn-android/issues/36) / [#52](https://github.com/novakin/vpn0wn-android/issues/52) record adds native userspace UP recovery after a real config-file deletion failure, then successful confirmed retry after exact directory-permission restoration. It exercises existing behavior without a product fix, new APK build, physical-phone operation or performance result. Both issues retain their remaining acceptance.

The ongoing goal's standing emulator/device-test authorization covered one fresh disposable AVD and one session-owned synthetic profile. The operator ran the session; independent read-only review inspected retained receipts, source, hierarchy, screenshots and HTTP/log evidence without issuing device commands.

## Environment and artifact

The fresh AVD used the Android 16/API 36 Google APIs x86_64 image and Pixel 8 profile. The scoped output was portrait 1080×2400 at density 420, default 1× text and English labels, with the system day-mode baseline. The app retained its fixed dark palette. This is one native configuration, not a physical or expanded output matrix. The existing migration emulator and shared ADB server were preserved.

The installed debug APK came from source `66390e5ae3190b4741871d70eaaadacedec50943`, package `com.vpn0wn.android.debug`, version code 519, SHA256 `86f8231b31b9eb9dc234fcb3507e6b9f3887b85c33253fdd59a79399e25f0a31`. Its retained [#152 artifact acceptance](https://github.com/novakin/vpn0wn-android/pull/152) records manifest/signature provenance. Fresh installed pullback matched. Product comparison with integration base `0dd7c93b619e054b8ed8a34c078823a8145f92c3` found no differences in `ui/src/main`, `tunnel/src/main` or `tunnel/tools`; later tests/docs supply no additional runtime result.

Only `native-observe` was seeded through the app UID before launch. Actual `FileConfigStore` enumeration/loading supplied the Home row. Import/export was outside this trial. The synthetic profile used a narrow `10.7.0.1/32` route, client `10.7.0.2/32`, MTU 1280, no DNS and emulator-host endpoint `10.0.2.2:51890`.

The retained finite Windows peer reused the standard WireGuard device/netstack design pinned at `v0.0.0-20250521234502-f333402bd9cb`. Its SHA256 matched `4ae9f2f94f3d64e6a20197cea27d58fe7e1c8282d58f296e687889b8e6d36f34`. Readiness and socket ownership were checked for its 15-minute lifetime. UDP bound wildcard IPv4/IPv6; HTTP existed only inside its virtual TUN. No host route/firewall/service or external-server change occurred. App-UID HTTP controls used the retained, hash-checked probe and required HTTP 200 with exact `tunnl-native-peer` body, including its trailing newline.

## Native failure, reconciliation and retry

Initial Home contained exactly the synthetic name, with a checked tunnel switch and authenticated private HTTP. The operator recorded the app-owned `files` directory path, UID/GID and mode `700`, plus the config hash and sole-file inventory. Removing only directory write bits produced mode `500` while retaining read/traversal access. The actual Home long-selection and confirmation named only the owned active tunnel and displayed the stop warning.

The confirmed attempt triggered real `FileConfigStore.delete` failure. Own-UID logs recorded backend DOWN, the store deletion attempt, backend UP and the surfaced `IOException`. The immediate hierarchy contained the exact feedback: “Removed 0 of 1 selected tunnels. 1 remain. Cannot delete configuration file native-observe.conf”. The original helper stopped at its strict unique-row assertion; its outcome remains **FAILED**, not relabeled as a passing command.

That hierarchy contained two selected cards at identical bounds: one childless and one with descendants. `TunnelListFragment.updateSelectionUi` calls `notifyItemRangeChanged`; a RecyclerView change animation is consistent with this transient overlap, but its precise mechanism was not instrumented. A later fresh hierarchy and PNG showed exactly one checked survivor, “1 OF 1 SELECTED”, an enabled Delete action and the ACTIVE badge. Fresh app-UID HTTP returned 200 with the exact body. The same admitted operation was reconciled without repeating the permission change or failed deletion.

PNG capture preceded hierarchy capture. The immediate PNG still showed the closing confirmation/deleting transition; it does not prove rendered snackbar output. Failure text is supported by the later hierarchy and exception log. The settled capture supports the single selected ACTIVE survivor, not a second mutation or a broader rendering claim.

The config-hash/inventory comparison passed in the operator's scoped reconciliation assertion and command journal. The original hash and inventory are retained; no separate raw post-failure command-output snapshot was saved. Config preservation therefore retains that evidence limit. No pre-mutation preference-byte baseline was captured, so preference byte equality is not claimed.

The helper's `finally` restored mode `700` and read back the exact original directory path and UID/GID before return. The later retry reconfirmed that identity, then deleted only the same selected name through the native confirmation. Success feedback, empty Home, absent config and final backend DOWN were observed. The post-delete HTTP probe returned 12 non-HTTP bytes with exit 0; it contained neither HTTP 200 nor the expected body. Command exit alone was not treated as connectivity proof.

## Failed setup controls and restoration

Retained operator failures include shell output adding CRLF to the initial config read, a cleanup path validator rejecting Android's `~~` installation-path prefix, and JSON timestamp coercion during peer/AVD ownership checks. Raw `exec-out` confirmed seed bytes without reseeding. The path-validator failure preceded cleanup mutation. The timestamp failures preceded any stop; preserving strings in the two receipt reads received independent delta review before graceful AVD stop. These are operator framing failures, not product defects or successful cleanup evidence.

Device cleanup rechecked the current installed APK and directory identity, removed only the three owned hierarchy/probe/output paths, then removed the freshly installed package. All 16 captured baseline fields matched: font/rotation/timeout, three animation settings, size/density/rotation mode/night mode, three always-on/lockdown policy fields and absence of current/legacy debug packages. This is a scoped comparison, not an exhaustive device snapshot. Android retained a cached VPN package/UID entry with active VPN type `-1` (inactive) after uninstall in disposable userdata; that framework state lies outside the captured baseline and is not described as original cached-state restoration.

The exact owned finite peer was deliberately terminated after PID/start/executable ownership checks. Its process and UDP listener were absent afterwards; its command session was joined with expected exit `-1`. Graceful owned AVD stop verified launcher/backend, serial and port absence, preserved shared processes and unchanged AVD config. Its launcher session was joined with exit 0. The AVD and private userdata remain retained; only the preserved migration emulator remains running. No required command from this trial remains active.

Raw APKs, configs, captures, HTTP responses, logs, journals, failed attempts and receipts remain ignored under `.artifacts/overnight-20261011/home-active-delete-20261011/`. The final stop receipt, rather than earlier pending-cleanup prose, supplies the shutdown evidence. Final documentation acceptance, independent review and any PR publication belong to the delivering PR.

## Remaining acceptance and documentation impact

This proves the scoped native userspace rollback/retry boundary. Partial deletion progress, admitted cancellation, other backend/environment recovery, physical output/system bars, TalkBack, provider/export cleanup, kernel/root and release acceptance remain open under the [QA runbook](../qa-test-plan.md#remaining-g4-device-acceptance). No Internet/DNS health, DPI, packet protection, throughput or energy result follows. #36/#52 remain open.

Only this record and minimal QA/E2/UI-roadmap references change. AGENTS, README, development, architecture, operations, backend contracts/changelog and templates need no edit: existing product behavior, ownership, setup and operational policy are unchanged. Earlier tests, failed commands and historical review records retain their original revisions, dates and limitations.
