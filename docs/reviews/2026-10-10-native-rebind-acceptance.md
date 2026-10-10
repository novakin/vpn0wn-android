# Retained-TUN userspace rebind: rooted Android acceptance

Last updated: 2026-10-10 (Europe/Paris).

## Scope and provenance

This record covers the manually invoked userspace primitive in [PR #107](https://github.com/novakin/wireguard-android/pull/107), related to [#73](https://github.com/novakin/wireguard-android/issues/73) and the remaining native warm-up acceptance in [#61](https://github.com/novakin/wireguard-android/issues/61). Tests were executed on 2026-10-10 under the maintainer's existing ongoing rooted-phone/APK authorization. Root operated the phone; a separate audit checked retained socket, interface, probe, traffic, screenshot and restoration artifacts. No emulator operation or production-server trial forms part of this record.

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

## Restoration and delivery boundary

The synthetic profile, owned local peer, temporary Download item and JDWP forwarding were removed. Two original saved profile checksums remained unchanged; no extra profile remained. Settings DataStore before/after files were byte-identical at `192` bytes, proving semantic restoration too. The original kernel backend preference and one-UP/one-DOWN VPN state were restored; the active original profile showed fresh authentication. The emulator was untouched. Private restoration evidence stays local.

The initial kernel shutdown failure occurred before native rebind and belonged to [#109](https://github.com/novakin/wireguard-android/issues/109). [PR #110](https://github.com/novakin/wireguard-android/pull/110) delivered that separate repair; merge and issue closure were read back on 2026-10-10. It is not a failed native rebind.

The earlier `bbfb3afa` CI checkpoint retains its scope: run `38049341272` attempt 1 failed an unchanged tile test with `UncompletedCoroutinesError`; the focused tile rerun and [attempt 2](https://github.com/novakin/wireguard-android/actions/runs/38049341272/attempts/2) passed without suppression. The scheduling hypothesis remains unproved. [PR #107](https://github.com/novakin/wireguard-android/pull/107) owns current final-head independent review, required CI, publication and delivery; this record establishes no merge or release.

## Remaining acceptance

This run proves bounded manual rebind, actual protection returns, retained TUN/session, synthetic authenticated traffic and sequential retirement/fresh activation. It does not exercise failed protection, interruption/cancellation, concurrent recovery/replacement, service destruction, endpoint refresh, family fallback or underlying-network handover on the phone. Host/JVM controls retain their separate scope under [QA](../qa-test-plan.md#native-rebind-controls--73).

No packet capture, leak/lockdown enforcement, IPv6 traffic, warm-up emission/adjacency, hardware suspend, wake timing, broader unchanged kernel-server interoperability, DPI, battery or speed claim is established. #73 remains open for automatic recovery, bootstrap policy, diagnostics presentation and full network/packet acceptance; #61 retains its remaining lifecycle/interoperability/DPI gates. This primitive adds no automatic trigger, DNS or endpoint/config rewrite.
