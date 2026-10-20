# Hostname bootstrap and lockdown evidence — #73/#75

Last updated: 2026-10-20 (Europe/Paris).

Scope: selected-Network hostname startup, numeric startup and an ordinary-app traffic canary on one owned API36 x86_64 emulator. Related to [#73](https://github.com/novakin/vpn0wn-android/issues/73) and [#75](https://github.com/novakin/vpn0wn-android/issues/75); both remain open. Independent ultra review accepts the bounded native, system-policy, packet-control and cleanup facts through separate offline reanalysis. The original controller remains exit 1 and aggregate INCOMPLETE.

## Revision and identities

Frozen production source is `ad03dda6ad0023b151ec42f856efb755076b89bb`, tree `a78485cab54b89cc69b412496215aed2f8a19d7e`. Relevant product source matches integration base `1418197613264d120b6a432d159bdf2bb1371c1f`. The separate provider APK retains production Application, manager, automation, backend and native-engine ownership; its debug overlay supplies instrumentation and package isolation. A separate canary APK has its own ordinary UID, INTERNET/ACCESS_NETWORK_STATE permissions and no VPN service, shared UID or device-admin role.

| Artifact | SHA256 |
| --- | --- |
| Provider `local.vpn0wn.hostname73probe.debug` | `7d9c02d2f2bce2e61536cfb6c852c4856be56ac997a94efb23c43d5880f92da0` |
| Canary `local.vpn0wn.lockdown75canary` | `cf5a9fd3467137f4d9dcfdc65c38341fcccc061b4aa690c2f796b265ed99d077` |
| Original sealed Radiotap capture | `aeecfbb8674e874ef182757dad579aabebb83deaaed6ecb235dbc8bf5cf20f41` |

Guest `Vpn0wn_Hostname73_API36_A4` connects to emulated Wi-Fi. DNS/HTTP fixtures use controlled host loopback endpoints and synthetic names/credentials; no external VPN peer or live server runs. Windows IPv4 DNS ownership, shared ADB and unrelated processes are preserved. Root accepts actual Android VPN consent and operates native Always-on/Block connections switches, with recorded hierarchy, screenshots, policy readback and live provider flags. Test flags do not substitute for system policy.

## Native and packet observations

Ten native stages retain selected Network, saved configuration, activation/session, native handle, TUN and transport-generation checks at their declared barriers. Four distinct activations cover baseline numeric startup, hostname startup under lockdown, numeric startup under lockdown and restored numeric startup. Provider DNS uses Android's actual `Network.getAllByName`; the saved hostname is preserved while the runtime endpoint becomes numeric.

| Control | Native observation | Retained capture/fixture observation |
| --- | --- | --- |
| Policy off, before lockdown | Canary default DNS, selected-Network DNS and literal-IP HTTP succeed. | Two queries per DNS route and the exact HTTP challenge appear. |
| Hostname startup under lockdown | Provider resolves its configured endpoint; both live policy flags are true. | Exactly two endpoint DNS queries, both inside the hostname-lockdown window. |
| Ordinary canary under lockdown | Both DNS operations and literal-IP HTTP fail. | No matching canary DNS or HTTP requests/packets; fixture health controls surround this negative phase. |
| Numeric startup | Endpoint configuration remains numeric. | Zero endpoint-name queries in baseline, numeric-observation and restored windows. |
| Policy restored | Both switches and live flags are off; all canary operations succeed again. | Both DNS-route positive controls and exact HTTP challenge return. |

The sealed capture contains 1,338 complete records, with equal captured/original lengths and maximum frame length 720 bytes. It yields 1,071 transport packets. Archive/member identity, raw/extracted byte equality, phase timing and normal daemon shutdown are retained. The original controller reports all sixteen resource/preservation checks true, including policy restoration, package/process/service absence, baseline restoration, fixture/guest/netsim shutdown, port release, drained commands and unchanged source/shared/DNS owners.

## Original failure and separate decoder correction

The original A4 command exits 1 after 514,468 ms. Its aggregate remains **INCOMPLETE**, despite native success and complete cleanup, because the packet checker rejects the PCAP snap-length header. The original error list, raw bytes and execution receipts remain unchanged.

Netsim emitted the Radiotap `0xffffffff` snap-length sentinel. The corrected decoder admits only that additional header form, retaining the 65,536-byte per-record bound and full-record checks. One complete frame outside all acceptance windows has an empty IPv4 payload. Recognized Radiotap IPv4/IPv6 frames with zero network payload contain no transport data; they are skipped. Nonempty short IP data, Ethernet equivalents, unsupported protocols and truncated/oversized records still fail.

Two regression methods reproduce the original failures, then the corrected checker passes all 31 offline controls. This correction changes neither APK, controller nor product source. Independent ultra review reran all 31 controls and checked the original sealed capture. The unmodified corrected validator passes every native, fixture, packet, timing, archive and cleanup-field assertion, then still rejects the exact historical checker-error entry. Review separately corroborates that sole error against its original command/traceback; no bypass or edited runtime receipt is used. All 804 original run files remain byte-identical. This accepts bounded facts, not a new runtime trial or a retrospective successful command.

## Evidence and remaining gates

Ignored evidence owner: `.artifacts/continuation-20261019-hostname-lockdown`; actual run `runs/run-20261019-192750-223771`. Executed input manifest SHA256 is `271405d8a3c762cf8a26490e449fd7de95d514087f2dde25b4985087504ca0b8`; corrected offline inputs are `0144ed00a16114ff9fda34ac154b867e011a91acef0ebc55742d9cb2e0a001f8`. Original command log: `20261019-212746-404-e6bb145e18744fb195440c30ec5ab91a.log`. Independent offline review receipt SHA256: `88912dca04c1478d124eb57ec1fd2d91ff236d84a7adf81ae701db6be95edff3`. Earlier baseline/capture failures retain their original outcomes.

This cohort establishes no authenticated handshake, VPN health, Internet reachability, natural exhaustion/recovery, delayed-DNS cancellation, alternate-family reachability or network handover. Private DNS, external/physical/OEM paths, full/split/LAN/exclusion matrices, established traffic, process death/reboot and general zero-leak claims remain outside scope. No DPI, throughput, battery or release claim follows. The [selected-network contract](../backend-contracts.md#selected-network-hostname-recovery--7374) and [full policy matrix](../qa-test-plan.md#android-system-vpn-policy-acceptance) retain those gates.
