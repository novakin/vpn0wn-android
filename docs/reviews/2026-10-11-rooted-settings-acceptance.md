# Rooted Settings acceptance

Last updated: 2026-10-11 (Europe/Paris).

## Scope and artifact

This partial [#34](https://github.com/novakin/vpn0wn-android/issues/34) / [#52](https://github.com/novakin/vpn0wn-android/issues/52) record covers existing Settings presentation, one persisted switch and navigation on the authorized rooted Pixel 7 Pro, Android 14/API 34. It adds no runtime behavior, APK installation, backend change, root-tools installation or performance result. The previously reviewed header APK remains installed: executable checkpoint `f1b570bbe2ba7b6b6879dd2e7b713f5323d9d202`, SHA-256 `478ce448776619fce41c591e3d156ca03d394cada3468a57a55f85f399dea734`. [PR #120](https://github.com/novakin/vpn0wn-android/pull/120) merged that source and its documentation at `7c3289816601a52b9085500251402282ab385f9e`; later commits in that slice changed documentation only.

The maintainer authorized continued improvements and tests on this rooted phone while sleeping, including APK builds. This slice uses the retained artifact. The delivering documentation PR owns final-head independent ultra review, checks and publication/merge readbacks.

## Actions and persistence

The four Boolean preference keys used during Settings initialization already existed. This matters because AndroidX can persist absent defaults when the screen opens. Fresh snapshots preceded each attempt; no stale app backup was restored.

- Home's actual Settings action opened the screen. The kernel backend exposed Connection, Control, Data and About groups, with boot/multiple-tunnel controls and optional-tools presentation. Appearance was absent on this API. The Android system VPN row correctly described kernel setup as unsupported; no backend or system VPN policy was changed.
- The actual Restore on boot switch changed once. A changed persisted DataStore file was observed before rotation or Back. Its changed checkbox survived a physical landscape capture, header Back to Home and reopening Settings. Returning it to the observed original checkbox value restored byte-identical preferences. This tests persistence/recreation, not actual reboot behavior.
- The existing View application log row opened LogViewerActivity. Android Back returned to Settings; the Settings header Back returned to Home. Raw log output remains private and supplies no network-health claim.

## Physical output and setup limits

Settled English portrait output at 1× text was inspected under both system day/night requests. The app retained its fixed dark palette, readable header/rows and reachable About/credits rows. Actual physical output was 1080×2340 at the original density. These are two configurations, not a complete Settings matrix or a distinct light palette.

Four matrix attempts rejected the requested enlarged landscape case because readback/captures were portrait, including the attempts using native screenshots alone and WindowManager rotation locking. Their completed switch/navigation controls retain their scope, while the mislabeled landscape captures are excluded. Every attempt verified restoration. An isolated configuration probe and the switch's recreation control produced actual 2340×1080 landscape output, but the probe's transient root-notification overlay is excluded from settled visual acceptance. The rotation-reset cause remains unproven; neither an automation-specific cause nor a product defect is established. Remaining large-text/RTL matrix cases were not completed. A separate missing optional image-comparison dependency failed before device actions; it was removed without installing a dependency.

## Preservation and remaining acceptance

Each completed action attempt compared 12 fresh fields: app locale; physical size; physical density; accelerometer/user rotation; font scale; system night mode; profile hashes; settings DataStore hashes; interface inventory; system-tool paths; WireGuard/Magisk module inventory; and app-UID Magisk policy fields. All matched their original values. No tunnel, profile, authentication, accessibility, animation, tile, installed module or command path was intentionally changed. Owned hierarchy helper/output removal was read back; the app returned to Home. Original APK/data recovery evidence remains ignored locally.

Screenshots, raw hierarchy/log output, device snapshots and failed command evidence remain private under ignored artifacts. This does not close #34 or #52. Other switches, tile membership/actions, authentication/export/import, administrator/OEM conditions, installer/kernel switching, actual boot restoration, spoken TalkBack and the remaining supported API/output matrix retain their separate gates. [The QA runbook](../qa-test-plan.md#remaining-g4-device-acceptance) owns those requirements. AGENTS, backend contracts/changelog, operations, architecture, README and templates need no edit: source, runtime behavior, setup, ownership and operational policy are unchanged.
