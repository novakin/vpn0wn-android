# Private AVD Android VPN policy acceptance

Last updated: 2026-10-10 (Europe/Paris).

## Scope and artifact

This record covers the setup/status slice in [PR #96](https://github.com/novakin/wireguard-android/pull/96), related to [#75](https://github.com/novakin/wireguard-android/issues/75). Source revision `89d338cde88f5ec1849e5c33d7610d9da32a93f0` was assembled locally with Gradle 9.8.1, WSL/OpenJDK 21 and the documented Android/native toolchain. Debug APK SHA256: `492c7823dc1312656fe56b52d31fa9aa23a5b155c821d10f8bb963c769849f0a`. Pulling the installed base APK confirmed the same checksum. The later documentation-only acceptance update does not change that application source.

The authorized target was a fresh private API 36 Google APIs x86_64 AVD using SwiftShader, density 420, hardware keyboard and actual animation scales 1.0. The existing phone, shared emulator and live servers were untouched. The test app retained its legacy `.debug` application ID and one session-owned synthetic profile. Existing exact-head CI records source/unit/static acceptance; this local APK run supplies distinct native evidence.

## Executed native checks

- Before VPN service acquisition, Settings reported unknown status and unavailable service. Reading status did not request VPN consent or activate the synthetic profile.
- After acquiring the existing userspace service, Settings reported Always-on disabled and Block connections disabled. The action opened Android's native VPN Settings.
- Enabling Always-on through Android Settings produced an enabled native switch and the matching app summary after foreground return. Block connections remained disabled.
- Enabling Block connections through Android's confirmation produced two enabled native switches, the matching app summary and the corresponding system owner/lockdown values. Native Settings, rather than the app action, changed policy.
- A request under the app UID returned the synthetic `tunnl-native-peer` HTTP response through the active full-route WireGuard profile. The peer used the pinned, unmodified standard userspace implementation. This is bounded authenticated reachability, not leak-protection proof.
- Native screenshots and hierarchies covered 320×640dp in both system night modes at normal text, 640×320dp in dark system mode at normal and 2× text, and 320×640dp Arabic RTL in both system night modes at 2× text. Actual activity configuration confirmed dimensions, font scale and RTL. The app retained its fixed palette; text wrapped and native navigation remained available. Long preferences require scrolling in the short landscape viewport.
- Policy was restored through native Settings. The app returned to unknown status when Android retired the service. Originally absent owner/lockdown settings were restored to absence, and the synthetic profile remained present and DOWN. Presentation overrides were restored.

Raw screenshots, hierarchy, install/checksum records and command logs remain ignored local evidence. Animated active Home prevented UIAutomator from reaching idle in two attempts; those failed captures are retained and are not passing hierarchy evidence. Policy screenshots and Settings hierarchies were captured from the settled native preference screen.

## Limits and remaining acceptance

The policy action observes Android flags; it does not implement or certify a kill switch. This run did not exercise Android 7–9, OEM or administrator restrictions, missing/denied Settings activities, kernel backend, physical reboot/handover, TalkBack speech, endpoint DNS bootstrap, or an IPv4/IPv6/DNS/excluded-app/split-route/LAN/Private-DNS packet-leak matrix. Dedicated source controls retain their original scope. A matching summary and successful traffic do not prove absence of underlying traffic.

[#75](https://github.com/novakin/wireguard-android/issues/75), [#73](https://github.com/novakin/wireguard-android/issues/73), [#74](https://github.com/novakin/wireguard-android/issues/74) and broader G4 acceptance remain open. The PR owns final reviewed head, independent ultra verdict, CI and merge state; this record grants no release, physical-device or live-operation authorization.
