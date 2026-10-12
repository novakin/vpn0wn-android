# UDP port release: scoped rooted Android acceptance

Last updated: 2026-10-12 (Europe/Paris).

## Scope and provenance

Executed on 2026-10-12 (Europe/Paris), under the maintainer's standing rooted-phone/APK authorization and fresh connected-phone readiness. This is a bounded [#61](https://github.com/novakin/vpn0wn-android/issues/61)/[#73](https://github.com/novakin/vpn0wn-android/issues/73) port-lifecycle trial on Pixel 7 Pro, Android 14/API 34/user 0. It supplements [the original rebind record](2026-10-10-native-rebind-acceptance.md) and [Linux host regressions](../qa-test-plan.md#native-rebind-controls--73); it does not replace their source/artifact boundaries.

| Input | Verified identity and boundary |
| --- | --- |
| Production source | `0edd0e9890c357bf50ff0f7bc9d4d83819aec1ed`, tree `268496b077ed6148519968131242124ddfe73f8b`. The subsequent host-only delivery through PR #172/`a3cec77d` changes tests/docs, not this production implementation. |
| Companion | Separate `local.vpn0wn.portprobe.debug` package/UID; imports the actual production `:tunnel`, `GoBackend` and exact `GoBackend.VpnService`. Only native package-path identity changes; Java/JNI source packages remain unchanged. min24/target36; all four native ABIs compiled, arm64 packaged. This is not the shipping APK or UI. |
| APK | SHA256 `a7c8c9cc1acb77ee440ce7cb0ad7405c2146c13430df79e0612cd631926ed6ab`; verified certificate SHA256 `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. Packaged service is nonexported and requires `BIND_VPN_SERVICE`; native UAPI path matches the companion. |
| Independent contender | Android arm64/API24 binary SHA256 `fd4cbbae1142d336e78d5b783a457dbcbb3c1e32363764ec244392bc30b3c479`. Numeric exact-address UDP bind, no packets, both reuse options verified zero, IPv6 V6ONLY verified one, bounded lifetime and close-error checks. Shell UID is distinct from the companion; network namespace matches. |

The fresh package/helper paths were absent before admission. All profiles use a known synthetic key, `192.0.2.2/32`, no peers/DNS/default route and an include list restricted to the companion. Android's effective VPN UID ranges include that app and its associated SDK sandbox; no original app UID is admitted. Imported `ListenPort = 0` is normalized by production `Config.parse`; it is not a direct-zero UAPI trial.

The existing personal kernel tunnel was left UP and remained UP in the captured snapshots. Its captured interface/index, address/MTU, runtime port/mark and IPv4/IPv6 rules/routes were preserved; the fixture port was distinct. No active Android userspace VPN agent/service or userspace TUN, always-on owner or lockdown conflict existed before admission. Original VPN app authorization modes were pinned before native consent. No personal tunnel toggle, configuration import, root grant/module change, server operation or external packet probe was performed.

## Accepted controls

Both IPv4 and IPv6 independently acquired and closed wildcard port 35000 before activation. Every later contender used the exact observed family/address/port, verified no reuse, required setup/close errno zero and distinguished `EADDRINUSE` from other failures.

| Profile/control | Result |
| --- | --- |
| Explicit 35000, session 1 | Both owned wildcard sockets use 35000. Contenders fail with `EADDRINUSE=98` while UP and after rebind. Rebind returns true, replaces both UDP inodes, removes old inodes globally, and retains the process/start identity, namespace, TUN FD/interface/link state and active session. After DOWN, owned UDP/TUN/UAPI are absent; both exact tuples are independently acquired and closed. |
| Omitted ListenPort, session 3 | Selected actual port 38152 is shared by both families and retained across rebind. Both active collision and post-DOWN independent reuse controls pass, with retired inodes absent and the TUN/session retained during replacement. |
| Imported-zero ListenPort, session 4 | Selected actual port 40445 is shared by both families and retained across rebind. The same collision, replacement, retirement and independent reuse controls pass. No warm-up packet claim follows from this peerless profile. |
| Genuine service destruction, session 5 | A `Context.stopService` request was accepted but the VPN stayed bound/UP for five seconds; that request proves no destruction. Later Android VPN Settings Disconnect selected the exact companion. The production PID-filtered log records `VpnService.onDestroy(); tunnel active=true`. The same process/start identity survives; session becomes inactive, backend/callback settle DOWN, owned UDP/TUN/UAPI disappear and both port-35000 tuples are independently reusable. This observes the production cleanup log and settled postconditions, not instrumented method entry/exit or concurrent destruction. |
| Fresh activation, session 6 | The same surviving process activates again with a new active session and both sockets at 35000. Independent contenders collide while UP; final DOWN releases both tuples for successful independent acquisition. |

The three accepted rebind profiles and lifecycle/fresh-activation controls pass. No release defect reproduced; no production behavior or port-policy change is justified. The earlier Linux regression improvements remain the code change delivered through [PR #172](https://github.com/novakin/vpn0wn-android/pull/172).

## Rejected setup and cleanup corrections

An initial preservation comparison treated raw ADB CRLF and text-file LF as different. Comparison of all captured command-output lines established identical kernel/route content; only newline encoding was normalized. PowerShell continued to a preliminary omitted activation after that failed native assertion. The fixture was stopped before diagnosis; preliminary session 2 is excluded from accepted omitted coverage. The accepted omitted/zero reruns use one failure-propagating Python sequence. This was harness failure, not a product route or socket failure.

The first native lifecycle-log filter retained the old tag and yielded no receipt. The corrected current `vpn0wn/GoBackend` filter recovered the actual PID-scoped production destruction log. Only that corrected evidence supports the lifecycle claim.

Normal DOWN followed by uninstall initially left the removed companion as Android's prepared VPN package. A second owned cleanup-only installation of the same pinned APK used native consent and exact-companion Android VPN Settings Disconnect, then immediate uninstall without reactivation. The prepared package returned to its original `[Legacy VPN]` state with inactive VPN type `-1`. No hidden binder/root policy call or original app reactivation was used. This supplemental cleanup cycle is distinct from the primary same-process session 5/6 controls.

## Preservation and remaining limits

Final readback verifies five original private file hashes, the original installed package/UID set, module list, always-on/lockdown values, links and captured kernel interface/port/addresses/rules/routes. Original `ACTIVATE_VPN`/`ACTIVATE_PLATFORM_VPN` modes for the three existing VPN apps match. The original legacy tunnel-list Activity is restored. Both owned installations/processes/data and the uploaded contender/UI-dump paths are absent; owned sockets/TUN/UAPI were retired before removal. The phone is no longer reserved for this test.

Android retains an inactive cached capability-owner UID from the removed cleanup probe, rather than its previous inactive cached UID. The prepared package is restored and no current VPN agent/service/TUN remains for the probe. This is explicitly not a byte-identical restoration of every framework dump, event history or package-allocation counter. Captured state preservation proves neither continuous VPN health nor every device/root-manager setting.

Private raw receipts, hashes, route/address/authorization inventories and harness sources remain ignored under `.artifacts/port-release-20261012/`. Independent review verifies their scoped assertions; current delivering PR owns final-head review, CI and publication/merge state.

This proves physical independent port collision/reuse and bounded rebind/retirement through a production-backend package-path variant on one rooted phone. Peerless trials establish no warm-up emission or FD-level send adjacency, authenticated traffic, actual OS protection denial, concurrent interruption/replacement/destruction, underlying-network handover, deep suspend, shipping APK/UI behavior, all-OEM coverage, DPI, speed or battery gain. Earlier packet/authenticated trials retain their own artifact scope. #61/#73 remain open for their wider acceptance.
