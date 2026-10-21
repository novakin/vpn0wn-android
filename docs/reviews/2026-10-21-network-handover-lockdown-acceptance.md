# Network handover and lockdown acceptance

Last updated: 2026-10-21 (Europe/Paris).

Scope: H10 exercises ordinary WireGuard on one isolated API36 x86_64 emulator with real Android network callbacks and native VPN policy controls. Related to [#61](https://github.com/novakin/vpn0wn-android/issues/61), [#73](https://github.com/novakin/vpn0wn-android/issues/73), [#74](https://github.com/novakin/vpn0wn-android/issues/74) and [#75](https://github.com/novakin/vpn0wn-android/issues/75); all remain open. Independent ultra review accepts the bounded native, Settings and packet evidence. The original runner remains **INCOMPLETE** because its Wi-Fi collector expected a ZIP; separate post-run review accepts the retained raw capture without rewriting that result.

## Source and artifacts

Frozen source is `ad03dda6ad0023b151ec42f856efb755076b89bb`, tree `a78485cab54b89cc69b412496215aed2f8a19d7e`. The provider retains production Application, model/manager, network-automation, backend and native owners. A separate ordinary-UID canary has no VPN preparation. The private instrumentation packages do not establish shipping-APK equivalence.

| Binding | SHA256 |
| --- | --- |
| Reused H8 provider APK | `1cc8e06d36b16d54eb9d0a477d76ad76f2f8c24391312bdb6295f37b2c44c3d3` |
| Reused H9 canary APK | `9a8802676cf19f3a5ccdd45b6969a1ab467d1aec553bb59424f291a5c9c73182` |
| H10 input manifest | `3c24ec601658a96e4b7cdfd0d55ed3f5226a00a75ea9cc55845477aa4d80e48f` |
| Independent post-run review | `f6ed31c9e51ed2283cfea5469974fe48f90c6f75ee8b0cee1f826b33802153f9` |

The original APK producer records remain separate from H10's host/controller manifest. Official emulator 37.2.12 is installed alongside the existing SDK. The numeric endpoint, omitted ListenPort, MTU1280 and app-only route are fixed test inputs; enabled preflight is not exercised.

## Observed controls

The native receipt passes 28 stages and records 36 ordered callbacks. Five authenticated HTTP phases correlate peer responses, selected Network/interface, original local port and bidirectional WireGuard transport packets: initial Wi-Fi, cellular, restored cellular, returned Wi-Fi and Legacy handling after disabling automation.

| Boundary | Evidence |
| --- | --- |
| Handover | Real callbacks replace UDP socket generations across Wi-Fi/cellular changes while retaining activation, native handle, service, TUN, port and saved configuration. Wi-Fi return selects `wlan0` in this run. |
| Full underlay loss | No-network state suppresses admission and the expected HTTP probe fails; restored cellular subsequently carries authenticated traffic. |
| Disable automation | Production Disable returns to Legacy handling with authenticated traffic. Subsequent network callback noise does not restart automation. |
| Native lockdown | Settings screenshots and system observations show Always-on and Block enabled, then restored off. Operator acknowledgements match the current requests. |
| Ordinary traffic | Default DNS, selected-Network DNS and literal-IP HTTP pass before and after lockdown. All fail during the three locked Wi-Fi/cellular/Wi-Fi-return canary phases; fixture and packet observations corroborate the bounded negatives. |
| Terminal policy removal | Removing native policy retires the original activation, service, native handle, TUN and sockets before the restored canary selects an independently observed public Network. The original local port is reusable. |

All 490 recorded commands settle and drain without timeout; expected nonzero commands retain their allowed failure semantics. Launcher, exact retained backend and network-simulator processes report actual integer exit 0. Native client, fixture and peer settle normally. Eighteen original cleanup fields pass, including policy/package/service/network/source/shared-owner restoration and Ethernet capture closure. The remaining field records Wi-Fi archive packaging failure, not a running resource or failed Android phase. Physical phones and production hosts were untouched.

## Collector failure and separate packet acceptance

Run `run-20261021-091916-4c664d` retains `captureSealed:false` and `netsim-capture: One completed owned netsim archive required`. This pinned runtime left raw PCAPs and statistics after normal daemon exit, without the expected archive. The earlier prelaunch attempt stopped on a temporarily occupied simulator port before creating test resources; its occupant remains unidentified. Earlier H1–H9 failures retain their original scopes.

The exact Wi-Fi path was admitted while the owned daemon was alive. Independent review matches its process/parent/executable/creation identity through normal exit, verifies the original file and directories, opens the closed file exclusively, and confirms stable bytes across complete parsing and packet checks. The raw PCAP contains 23,674 complete records, 3,583,568 bytes, SHA256 `e8a5a29006b56db6f37e3bcbcf0c9255b6bc246c7fd3351c0ff44724f95b0b5c`. Its final packet precedes the recorded daemon exit.

The separate offline review reuses the sealed checker's original native/barrier and packet/peer/DNS/canary assertions, using that closed raw file and actual daemon-exit upper bound. All meaningful assertions pass, including the unchanged Ethernet and host-exit checks. It writes no replacement cleanup or fabricated archive receipt. If this private harness is reused, its collector must support the pinned runtime's raw output while retaining ownership, closure and packet guards; a new run is unnecessary for this accepted evidence.

Private inputs, receipts and reproducible offline review remain under `.artifacts/continuation-20261021-network-handover/h10/`. Raw captures and host metadata are not distributed with source.

## Limits

These results apply to the retained `ad03dda6` artifacts, not a run of integration base `ea90e1235760abd58ef0b6991fae40e7c4974b06`. Later broadcast-lifetime and TV-consent repairs are outside this direct model/automation flow. The separate [recovery/cancellation cohort](2026-10-21-hostname-recovery-lockdown-acceptance.md) owns natural exhaustion and held-DNS cancellation; H10 does not retest recovery.

Packet correlation is bounded by phase and independently observed UID/network state; capture alone does not attribute a UID or establish universal zero leakage. Physical carriers/OEMs, external servers, alternate address families, Private DNS/DNS64, full/split/LAN/exclusion matrices, hardware sleep, enabled preflight, DPI benefit, speed, battery and complete issue/release acceptance remain separate.
