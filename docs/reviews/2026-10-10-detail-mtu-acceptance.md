# Detail configured MTU acceptance

Last updated: 2026-10-10 (Europe/Paris).

## Scope and artifact

[#111](https://github.com/novakin/wireguard-android/issues/111) repairs configured-MTU presentation and current-config refresh. It changes no saved-profile contract, backend MTU policy, topology geometry, fonts, palette or animation. The installed debug APK was assembled from `46e51c046746240e5f495ba5c0784de96cac0d8b` against merged base `8fba177cc55ec90835225fb1cb49ca805a05ee79`, using WSL JDK 21 and the prepared Android SDK/NDK. SHA-256 `b4cf49af1c4c1bedc63b3caab7348cb469332ce6711c461be74b5d5cedb72c9e` matched the APK pulled back after data-preserving installation.

The authorized rooted Pixel 7 Pro ran Android 14 at its unchanged physical 1080×2340 portrait / 2340×1080 landscape size and 420 dpi. The maintainer's ongoing rooted-device/build authorization covered this run. The shared emulator was not operated. Screenshots, original APK/data backups, synthetic fixtures, command logs and restoration evidence remain ignored locally; no personal profile, key, endpoint, serial or raw host report is distributed here.

## Executed controls

The untouched production baseline failed the new explicit-MTU and current-config-refresh regressions. The repaired binding/lifecycle suites passed 26 tests with zero failures, errors or skips, and Detekt reported zero findings. Five native widget rasters separately covered explicit/unknown MTU, light/dark, landscape and 2× Arabic RTL. These fixture results retain their Robolectric API 28 scope.

Three collision-checked peerless fixtures used explicit 1280, explicit 1380 and omitted MTU. They had synthetic keys and documentation addresses, with no endpoint, peer, DNS, AllowedIPs or explicit ListenPort. Fixtures were inserted into the existing private config-store files while the app was stopped, then enumerated through the normal owner. This was a presentation/activation test, not import acceptance or VPN-health proof.

| Installed control | Observed result |
| --- | --- |
| Kernel, explicit 1280/1380, DOWN and UP | Diagram and Interface values matched the selected configuration. Root interface readback confirmed the corresponding UP interfaces and MTUs. |
| Kernel, omitted MTU, DOWN and UP | Diagram and Interface stayed `—`. The activated synthetic interface used 1420; that effective value did not replace unknown configured data. |
| Userspace, explicit 1280/1380, DOWN and UP | Diagram and Interface matched configuration. Root readback confirmed UP TUN MTUs of 1280 and 1380. |
| Userspace, omitted MTU, DOWN and UP | Diagram stayed `—`; the unobscured UP Interface row also showed `—`. Root TUN readback reported the existing 1280 fallback. A root toast obscured the lower row in the DOWN capture; source controls and the UP capture retain their respective proof scope. |
| Selection: 1280 → 1380 → omitted → 1280 | Diagram replaced the previous selected value; Interface hints and synthetic identity agreed. Some lower selection rows were toast-obscured; clean per-profile controls proved their corresponding values. |
| MTU-only editor save while DOWN | Changing the selected 1280 fixture to 1380 updated the returned Detail diagram, Interface hint and row. Saved synthetic config differed only by MTU; identity/address and all other fields were preserved. |
| Six physical-output configurations | Portrait system light/dark, landscape dark at 1×/2× text, and portrait Arabic RTL at 2× text in both system themes retained readable diagram MTU and the fixed navy/cyan palette. Additional scrolled landscape captures verified the Interface MTU row at both scales. |

Peerless UP displays waiting-handshake presentation and proves backend activation/rendering only. It establishes no authenticated peer traffic, endpoint reachability, DPI benefit, speed or battery result. Both system themes intentionally use the existing fixed app palette. Canvas retains its platform monospace font and dp-based text-size limitation; this check does not establish large-font accessibility or TalkBack acceptance.

The legacy hierarchy dump failed its idle wait. A bounded ignored runner probe also failed to obtain fresh XML; no stale hierarchy was accepted. Installed checks therefore used observed screen controls, actual screenshots, saved synthetic config and root interface readback. One additional capture intended as clean omitted-MTU Detail showed Home and was excluded. No resource-configuration dump was obtained; the applied settings and visible output, rather than unavailable configuration readback, support the layout cases.

## Preservation and restoration

Original app data and APK were backed up before installation. Kernel/multiple-tunnel preferences and original states were established before activation. The original kernel tunnel was temporarily stopped for userspace checks; backend selection was changed only while the app process was stopped. The repaired APK remains installed under the maintainer's authorization.

After testing, all synthetic interfaces were DOWN. Only the three owned fixture files and temporary device helpers were removed. The original two profile files matched their backups byte-for-byte; the settled settings protobuf matched all original 192 bytes. Kernel backend, original one-UP/one-DOWN states, all 12 captured system settings, per-app locale and captured authentication/window-policy fields matched their originals. Physical size/density were unchanged. Final Home output showed the original two profiles with the expected switch states; no authentication setting was changed or removed.

## Acceptance boundary

The installed evidence supports #111's scoped configured-MTU, selection/edit-refresh and visual-output acceptance alongside its source controls. [The presentation contract](../backend-contracts.md#persistence-and-presentation) owns configured-versus-effective semantics. [The UI roadmap](../ui-redesign-roadmap.md#fidelity-gaps--remaining) retains Canvas typography limits, and [#52](https://github.com/novakin/wireguard-android/issues/52) retains broader physical/accessibility acceptance. #73 recovery is separate. Issue #111 and its linked PR own final-head independent ultra review, CI, publication and merge state; neither artifact installation nor this record implies release.
