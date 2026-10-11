# Native Home partial deletion acceptance

Last updated: 2026-10-11 (Europe/Paris).

## Scope and artifact

This E2 follow-up adds one isolated Android API 36 DOWN-only partial-deletion, continuation and retry trial for [#36](https://github.com/novakin/vpn0wn-android/issues/36) and [#52](https://github.com/novakin/vpn0wn-android/issues/52). It supplements the [earlier active-delete recovery trial](2026-10-11-home-active-delete-acceptance.md); neither record completes those issues.

The fresh userdebug AVD used the pinned debug APK from source `66390e5ae3190b4741871d70eaaadacedec50943`. Its production inputs are unchanged at integration base `932eb9ced7bdfd55f544ab3a02b960b8cbe8b6d2`; intervening changes affect documentation and test sources. Package `com.vpn0wn.android.debug`, version code 519, APK SHA256 `86f8231b31b9eb9dc234fcb3507e6b9f3887b85c33253fdd59a79399e25f0a31` and signer certificate SHA256 `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580` identify the tested artifact. Fresh installed pullback matched the APK bytes; installed-pullback signature verification also passed. No APK was rebuilt for this trial.

Both app IDs were initially absent. The isolated AVD preserved the existing ADB server and shared migration emulator. No physical phone, personal profiles, external VPN endpoint or live VPS was used. Retained local evidence lives under ignored `.artifacts/overnight-20261011/home-partial-delete-20261011/`; raw profiles, commands and device evidence are not source-distributed.

## Executed controls

Two valid endpoint-free profiles, `partial-alpha` and `partial-bravo`, were seeded through the app UID with exact retained fixture bytes and mode 600. Both native Detail screens rendered their parsed names, IPv4/IPv6 interface addresses, interface public-key rows, omitted ListenPort and DOWN switches. Home showed exactly those two DOWN profiles; no app service ran. Both preference-store directories were checked absent before the admitted deletion.

Before touching a config flag, a fixed non-config fixture in the same app files directory proved native immutable support. Root added only `i`; app-UID unlink failed while bytes, identity and other reported flags stayed unchanged. Removing the owned flag restored the exact original reported flags, device/inode, mode, UID/GID, size, link count and hash. App-UID unlink then succeeded and fixture absence was checked. Effective adbd UID and SELinux mode were restored. No remount, SELinux change, capability override or fallback fault injection was used.

| Control | Observed result |
| --- | --- |
| Protect only alpha; preserve bravo and directory | Only alpha gained `i`; both original config bytes and identities matched. |
| Confirm alpha first, bravo second, exactly once | Own-UID native logs show one alpha deletion attempt, its actual store failure, then one bravo attempt. No UP transition was observed. |
| Continue after first failure | Bravo was absent; alpha retained its protected bytes, full flag vector and identity. Persisted inventory contained only alpha. |
| Report partial progress and retain retry | Home kept alpha selected with enabled Delete. Feedback stated “Removed 1 of 2 selected tunnels. 1 remain.” |
| Restore before retry | Unconditional recovery restored alpha's exact original reported flags and tracked identity/byte fields, preserving its directory. Effective adbd UID and SELinux mode matched. |
| Retry survivor once | One-name confirmation succeeded; feedback stated “Successfully deleted 1 tunnel.” Both config paths and persisted inventory were empty, with no app service. |

Root inspected both feedback PNGs. The partial frame renders the complete progress/count and selected survivor; its two-line snackbar ellipsizes the error tail. The full `Cannot delete configuration file partial-alpha.conf` text is retained in fresh XML and native logs, not claimed as fully rendered in the PNG. Retry success, empty guidance and Add were visible. PNG and XML are sequential observations. Both trial feedback assertions passed on their first capture; no deletion was repeated to overcome a capture failure.

## Failed checks and restoration

Initial manual Detail assertions wrongly required globally unique `tunnel_switch` and `public_key_text` IDs. Retained Home and peer nodes share those IDs. Those three failed assertions occurred before any action in their checks and remain recorded. Corrected checks scope the switch to `tunnel_status_card` and interface fields to `tunnel_detail_card`; both profiles then passed. This changes the validation query, not product behavior. The earlier bootstrap's unsupported rotation query and stale caller exit-code check also remain separate failed evidence; later native readbacks do not relabel them as passes.

Both preference-store directories remained absent after the trial. Cleanup checked config/fixture absence, no app services, owned upload/capture hashes and subsequent path absence, then removed only the fresh owned installation. All 16 captured settings/package baseline fields matched. Effective shell UID returned to 2000 and SELinux remained Enforcing. Android's `service.adb.root` property changed from empty to `0`; property equality is explicitly false. No ctime equality, full-device equality or remote-upload inode equality is claimed.

The owned AVD stopped gracefully. Its launcher/backend, listening ports and serial were absent; private AVD configuration and userdata were retained, and shared process identities stayed unchanged. The original start-command wrapper also terminated; its unavailable outer exit code is not inferred. All required trial, cleanup and stop commands joined before completion.

## Review and remaining acceptance

Independent ultra evidence review returned PASS for this bounded native trial, recovery, cleanup and owned stop, with no blocking findings. It checked unchanged helpers, installed artifact identity, native parsing, feedback PNGs and joined command receipts while retaining the failed checks and preservation limits. Final-head documentation review and publication/check status belong in the owning PR acceptance. The reviewed preparation alone did not establish runtime success; actual receipts, direct filesystem readbacks, inspected PNGs and restoration above supply this trial's bounded evidence.

This proves one API 36 native DOWN partial-success environment. Admitted deletion cancellation, other APIs/backends, broader active-state recovery, physical/TalkBack and provider/export failure or cancellation acceptance remain open under [the G4 runbook](../qa-test-plan.md#remaining-g4-device-acceptance). It does not establish VPN health, kernel-backend behavior, physical deep suspend, performance or battery gains. #36/#52 remain open.

No production source, UI design, operational policy or agent/process rule changed. The owning QA, development and improvement-plan summaries carry this new evidence without refreshing unrelated historical tests or expanding their acceptance.
