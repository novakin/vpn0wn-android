# Retained-TUN userspace rebind: rooted Android acceptance

Last updated: 2026-10-12 (Europe/Paris).

## Scope and provenance

This record covers the manually invoked userspace primitive in [PR #107](https://github.com/novakin/vpn0wn-android/pull/107), related to [#73](https://github.com/novakin/vpn0wn-android/issues/73) and the remaining native warm-up acceptance in [#61](https://github.com/novakin/vpn0wn-android/issues/61). Tests were executed on 2026-10-10 under the maintainer's existing ongoing rooted-phone/APK authorization. Root operated the phone. The initial audit checked retained socket, interface, probe, traffic, screenshot and restoration artifacts. The follow-up audit checked runtime/resource readbacks, saved-profile hashes, preferences and system policy; the same-process claim uses retained operator readbacks corroborated by the continuous observer connection. Final follow-up screenshots, handshake presentation and fixture cleanup were root-observed, not independently revalidated by that audit. No emulator operation or production-server trial forms part of this record.

| Boundary | Executed provenance |
| --- | --- |
| Android target | Rooted Pixel 7 Pro, Android 14, debug application. Its VPN-backed Wi-Fi and absent cellular path provide no independent direct/VPN or DPI comparison. |
| Installed integration | Local integration `2db34843be8167827e15e9ec2c2e6d86409ffde0`, combining the unchanged native candidate with the Home candidate and kernel recreation repair. Installed pullback matched APK SHA256 `0246203e6c4bae291c80ca71c81a509f2b58495b787661ac2d193ca3cd8fd4e4`. |
| Native source | Native/rebind source from `bbfb3afa8f2b41e13b376e4f83a816617dd2c6f3`. Later master integration and documentation do not turn this runtime run into final-head PR verification. |
| Synthetic path | One synthetic profile and unchanged local standard WireGuard peer. ListenPort `35000`, numeric loopback endpoint, IPv4 peer-TUN HTTP health request with fixed expected body. No personal profile was used for rebind. |
| Invocation | A local JDI helper invoked public `GoBackend.rebind(Tunnel)` at `GoBackend.getStatistics` entry on its IO worker, before Java state-lock/JNI entry. It recorded native result, public result, selected activation identity and actual socket-protection return values. Debugger suspension/invocation changes scheduling. |

Raw configs, profile hashes, private screenshots, descriptor snapshots, debugger logs and host reports remain ignored under `.artifacts/native-rebind-runtime/` and `.artifacts/codex-command-logs/`. Personal profile names, keys and endpoint metadata are excluded here.

## Observed results

| Control | Result and boundary |
| --- | --- |
| First active rebind | Native `wgRebind` returned `0`; public rebind returned `true`. Both replacement socket-protection calls returned `true`, with no observed helper error. |
| UDP replacement | IPv4 descriptor/inode changed `152/5893457` to `133/5960753`; IPv6 changed `159/5893458` to `134/5960754`. Old inodes were absent afterward; new inodes were absent beforehand. Both families retained local port `35000`. This observes IPv6 socket ownership/protection, not IPv6 traffic. |
| Retained activation | TUN FD `130`, interface `tun1`, ifindex `58`, complete link snapshot, native handle `0`, active handshake session `1` and the selected activation identities remained unchanged across the first invocation. Saved profile checksums also remained unchanged. |
| Traffic after first rebind | Before/after HTTP controls returned `200` and exact `tunnl-native-peer` body. Screenshots show RX `700` bytes becoming `1.27 KB`, TX `804` bytes becoming `1.63 KB`, and a fresh handshake. This establishes authenticated synthetic peer traffic, not Internet reachability. |
| Sequential retirement | Manual DOWN left no app TUN descriptor, `tun` interface or port-35000 UDP socket in the captured snapshots. This is completed sequential retirement, not a concurrent DOWN/rebind or service-destruction race. |
| Fresh activation and second rebind | Reactivation created interface ifindex `59`, a new TUN descriptor and new UDP socket inodes. The second helper observed active session `2`, native handle `1`, native result `0`, public `true` and protection `true` for both descriptors. A further HTTP control returned `200` with the expected body. |

Debugger object IDs are local to each attachment; their reused numbers do not establish identity across activations. Reactivation FD snapshots were captured before the second rebind: descriptors `138/144` there must not be correlated with the second probe's `129/134`. No post-second descriptor snapshot is claimed.

## Protection-failure and service-destruction follow-up — 2026-10-10

The follow-up reused the installed integration and APK checksum above, the rooted Pixel 7 Pro / Android 14 and the same private standard loopback peer. It required no new APK build or install. Ignored JDI helpers exercised one selected invocation-thread fault and separately observed actual Android lifecycle callbacks. This is debugger-injected protection failure, not an Android OS denial or timing proof.

| Control | Result and boundary |
| --- | --- |
| One forced protection failure | The target reported `canForceEarlyReturn=true`. The helper forced exactly one selected-thread `protectNativeSocket` invocation to return `false`, for FD `115`; its paired `MethodExitEvent.returnValue` was `false`. Native `wgRebind` returned `-5`, and public rebind threw `java.io.IOException`. No actual platform protection return is claimed for this forced invocation. |
| Retained failed activation | Within that attachment, service/config/activation/session identities were unchanged; session `1` remained active and native handle `0` remained owned. TUN FD `147` and the complete link snapshot were unchanged, but `/proc` showed no port-35000 UDP socket. All three saved profile hashes were unchanged. Retained activation therefore did not establish usable transport. |
| No-fault retry | A later invocation returned native `0` and public `true`; both observed platform protection returns were `true`. The TUN/link/session stayed retained, and a subsequent HTTP control returned `200` with exact `tunnl-native-peer` body. This proves bounded retry recovery against the synthetic peer. |
| Actual service destruction | Android VPN Settings Disconnect targeted the exact debug app and synthetic profile. A read-only observer captured selected `VpnService.onDestroy` entry and exit, then `destroyed=true`, cleared service owner, inactive old session, backend handle `-1` and null current tunnel/config/service/activation. PID `20221` survived. The app settled DOWN; `/proc` showed no app TUN descriptor, `tun` interface, port-35000 UDP socket or UAPI listener. This is settled system-initiated destruction, not concurrent destruction during rebind. |
| Fresh explicit activation | Explicit UP in the same surviving PID created a live service, active session `2` and native handle `1`. A subsequent rebind returned native `0`, public `true` and both platform protection returns `true`; HTTP returned `200` with the exact expected body. No debugger object ID is compared across attachments. |

Private evidence remains under the repository root's ignored `.artifacts/native-rebind-runtime/phone/fault-*` and `.artifacts/codex-command-logs/`. The decisive command logs are `20261010-160312-633-715043e04304498ab6d05eba0528e0f9.log` (forced failure), `20261010-160412-987-945900e6505340c0998a0058071dc171.log` (retry), `20261010-160441-968-1cf8e0ddddb24542b16ed8d5502f5b24.log` (destruction observer) and `20261010-160636-360-edfcfd476a51409a8fe5c04942178691.log` (fresh activation/rebind).

## Restoration and delivery boundary

The synthetic profile, owned local peer, temporary Download item and JDWP forwarding were removed. Two original saved profile checksums remained unchanged; no extra profile remained. Settings DataStore before/after files were byte-identical at `192` bytes, proving semantic restoration too. The original kernel backend preference and one-UP/one-DOWN VPN state were restored; the active original profile showed fresh authentication. The emulator was untouched. Private restoration evidence stays local.

The follow-up independently restored both original saved profile hashes and the original one-UP/one-DOWN set. The synthetic profile and Download fixture were deleted, the owned peer stopped, test UDP ports `35000`/`51890` disappeared and JDWP forwarding was removed. Settings UI restored the original kernel preference; the original UP profile showed a recent authenticated handshake. The settled Settings DataStore matched the original byte-for-byte at `192` bytes; an earlier in-flight read was `129` bytes and did not establish restoration. Android always-on package remained null, lockdown remained `0` and the whitelist remained empty, matching the captured baseline. Authentication, display, locale, theme and animation settings were unchanged. Final evidence remains private under `fault-final-*` and `fault-settings-after-private.pb`.

The initial kernel shutdown failure occurred before native rebind and belonged to [#109](https://github.com/novakin/vpn0wn-android/issues/109). [PR #110](https://github.com/novakin/vpn0wn-android/pull/110) delivered that separate repair; merge and issue closure were read back on 2026-10-10. It is not a failed native rebind.

The earlier `bbfb3afa` CI checkpoint retains its scope: run `38049341272` attempt 1 failed an unchanged tile test with `UncompletedCoroutinesError`; the focused tile rerun and [attempt 2](https://github.com/novakin/vpn0wn-android/actions/runs/38049341272/attempts/2) passed without suppression. The scheduling hypothesis remains unproved. [PR #107](https://github.com/novakin/vpn0wn-android/pull/107) was draft/unmerged at the 2026-10-10 pre-publication checkpoint; the linked PR owns current final-head review, checks, publication and delivery state. This record establishes no issue closure, merge or release.

## Remaining acceptance

### Port-release evidence clarification — 2026-10-12

The original physical controls already observed retired UDP inodes after rebind and no owned UDP sockets after DOWN, injected protection failure and actual service destruction. Saying all physical port-release testing is unverified understates those scoped observations. They did not independently acquire the released IPv4/IPv6 tuples with another socket. At the host-only checkpoint that physical bind/reuse boundary remained unverified. The [later same-day rooted trial](2026-10-12-udp-port-release-acceptance.md) adds bounded separate-UID production-backend collision/reuse evidence; the older source/APK and event dates above are unchanged.

New Linux host controls in `androidbind/socket_linux_test.go` use actual production bind/device owners and independent contenders with reuse disabled. The full host race/JNI gate passed for collision while open, port reacquisition after close/shutdown/failed protection/DOWN/device teardown, and same-port rebind/retry with explicit/omitted/direct-zero intent. Synthetic initiation-byte sends check both-family ordering/source ports and disabled warm-up; no authenticated traffic or actual Android protection return is established by this new host run. No release defect reproduced, so no production behavior or port-policy change is justified. [QA](../qa-test-plan.md#native-rebind-controls--73) owns these regressions. The subsequent physical record retains its separate artifact scope; shipping-artifact acceptance and the wider #61/#73 gates remain open.

The original rooted Android runs prove bounded manual rebind, actual successful protection returns, retained TUN/session, synthetic authenticated traffic, debugger-injected protection failure/retry, sequential retirement and actual system-initiated service destruction followed by fresh activation. They do not exercise actual Android OS protection denial, interruption/cancellation, concurrent recovery/replacement/destruction, endpoint refresh, family fallback or underlying-network handover on the phone. Host/JVM race controls retain their separate scope under [QA](../qa-test-plan.md#native-rebind-controls--73); debugger scheduling establishes no timing bound.

No packet capture, leak/lockdown enforcement, IPv6 traffic, warm-up emission/adjacency, hardware suspend, wake timing, broader unchanged kernel-server interoperability, DPI, battery or speed claim is established. #73 remains open for automatic recovery, bootstrap policy, diagnostics presentation and full network/packet acceptance; #61 retains its remaining lifecycle/interoperability/DPI gates. This primitive adds no automatic trigger, DNS or endpoint/config rewrite.
