# G4 repeated network baselines

Last updated: 2026-10-10 (Europe/Paris).

## Scope and authorization

Partial evidence for [#52](https://github.com/novakin/vpn0wn-android/issues/52), G4 of the [active goal](../improvement-plan.md#active-enhancement-and-performance-goal). The maintainer made Pixel 10a available, confirmed a working VPN, authorized saved-tunnel toggling and the existing temporary benchmark server. After the Wi-Fi run, the maintainer confirmed the router also uses a VPN and requested cellular connectivity tests with no mobile-data limit.

No app code, APK, protocol, profile, ListenPort, MTU, routes, DNS or keepalive was changed. The installed userspace/debug candidate is source `5fa07cd0`, whose runtime source is unchanged in documentation merge `04889912`. [Candidate provenance](2026-10-07-settings-resource-id-review.md#candidate-phone-confirmation--2026-10-07-europeparis) owns artifact/signing/install evidence. The installed APK SHA256 is `04332f97f0013ea64e2ecca258f1f6d97c05689deeea61452a03361d825c4075`; no rebuild or reinstall was needed.

The authorized personal profile has one peer, explicit nonzero ListenPort, no explicit MTU, full IPv4/IPv6 routes and 25-second keepalive. It is not a synthetic performance fixture. Private archives, device identifiers, profile hashes, raw network/radio/logcat reports and benchmark addresses remain in ignored local evidence.

## Method and interpretation

Pixel 10a, Android 16/API 36, ARM64, native userspace backend. Foreground Home in landscape, screen on, USB/AC charging, fixed profile and same authorized TCP 5201 endpoint. Client iperf3 3.22 and packaged server 3.18. Each uncapped scenario uses one TCP stream, three omitted warm-up seconds and 15 measured seconds; five repetitions per path/direction, alternating path/direction order with four-second gaps. The client has a 35-second wall timeout and five-second connect/send/receive timeouts. Separate three-second, 1 Mbps controls use one omitted warm-up second and do not count toward baseline repetitions.

Receiver throughput is the primary reported observation. App CPU is the process user/system tick delta with `CLK_TCK=100`, over the recorded trial window; it covers Java/native app work during warm-up, transfer and the surrounding measurement interval. Client CPU and sender retransmissions/RTT are retained where available. Reverse sender RTT may be zero/unavailable and is not reported as zero latency. TCP retransmissions are not a direct IP-loss measurement. Battery/network snapshots precede each trial and thermal snapshots bracket it; radio snapshots accompany cellular trials. Unrelated background traffic was not isolated. These are within-build path baselines, not paired candidate optimization trials or controlled energy measurements.

The Wi-Fi router already uses VPN. All benchmark-server source observations in that session match the VPN egress. App-off client sockets are outside the phone tunnel subnet; app-on sockets use it. Historical raw filenames named `direct` therefore mean router VPN alone; `vpn` means router VPN plus phone VPN. Preserve those filenames and interpret them as router-only versus nested VPN. They do not establish clean direct Internet throughput or expose the phone's handshake to upstream DPI. This clarification applies to this repeated session; it does not reconstruct the router state of earlier [incident diagnostics](2026-10-07-g4-preflight-investigation.md).

Cellular checks require Wi-Fi disabled and the current Android default network identified as cellular before each trial. A unique small HTTP request observed by the authorized benchmark server establishes the phone's direct IPv4 source before opening its restricted listener. The VPN source is established by previous successful restricted exchanges. Successful iperf server output must match the expected source for each path. Ambient SSH sources, NAT-rewritten local ports and changing `ss` queue values are not phone source proof.

## Wi-Fi results

All 20 uncapped trials and three capped controls completed. Each row contains five uncapped repetitions.

| Path and direction | Median receiver Mbps | Min–max Mbps | Median app CPU seconds |
| --- | ---: | ---: | ---: |
| Router VPN alone, upload | 369.65 | 331.33–446.76 | 0.02 |
| Router VPN alone, download | 304.05 | 225.03–482.10 | 0.02 |
| Router plus phone VPN, upload | 34.05 | 12.40–46.59 | 11.20 |
| Router plus phone VPN, download | 30.55 | 20.13–37.47 | 11.11 |

These repeated observations expose a large path difference and substantial spread. They do not identify the limiting owner, isolate the phone tunnel from the router path or justify changing native transfer code. Router-only throughput is contextual, not a baseline without VPN.

## Cellular results

All 20 uncapped trials and three capped controls completed on verified cellular paths. Each row contains five uncapped repetitions.

| Path and direction | Median receiver Mbps | Min–max Mbps | Median app CPU seconds |
| --- | ---: | ---: | ---: |
| Cellular without phone VPN, upload | 52.72 | 43.99–57.21 | 0.04 |
| Cellular without phone VPN, download | 94.65 | 82.56–109.75 | 0.03 |
| Cellular with phone VPN, upload | 47.80 | 36.42–54.35 | 15.10 |
| Cellular with phone VPN, download | 39.50 | 28.80–45.23 | 15.36 |

Upload ranges overlap; VPN download is lower in this session. Whole-app CPU rises with the native VPN, but process counters cannot attribute that cost to UI, crypto, transport or a limiting owner. Extra path/server/mobile-radio effects are not isolated. This supports further focused profiling, not a selected transfer change or a performance gain. All captured cellular thermal statuses were 0; this does not exclude other constraints. Measured receiver payload totals approximately 2,228.4 MB across controls/trials, excluding omitted warm-up traffic and protocol overhead; this is not an operator billing estimate.

## Preservation and remaining gates

The Wi-Fi session's three app files remained byte-for-byte identical to its fresh initial archive. VPN returned OFF, and temporary stay-awake/rotation settings matched their saved values. The temporary client/captures, server listener, isolated firewall table and runtime directory were removed. One preference file already differed from an older session before this run; the fresh starting state was preserved rather than restoring stale preferences. Installed iperf3 packages remain, with the packaged service disabled/inactive.

Cellular automatic tunnel restoration failed after the completed trials; the retained command exited 1 and remains failure evidence. A subsequent observed native switch action reached OFF; an intermediate readback was ACTIVE when the maintainer resumed use. That intermediate archive changed only `enabled_configs`, the existing `UserKnobs` running-tunnel set, with config/runtime marker and all other preference entries unchanged. The maintainer explicitly requested the original OFF state at handoff. Final native/service readback was OFF, and all three app files matched the fresh pre-session archive byte for byte; a final private protobuf comparison found no changed preference entries. No prior archive was copied over current data. Successful final recovery does not change the original command's failed result.

Saved Wi-Fi OFF, stay-awake, auto-rotation and user-rotation values match final readback. Temporary phone client/hierarchies/captures were removed. Server cleanup readback found no listener/table and removed the owned runtime directory; its transient failed-unit state was reset. Packaged iperf3 remains disabled/inactive. Raw evidence and both failed/successful command logs remain local.

After the load run, the maintainer reported all animations missing. Two active Home captures had zero changed pixels in the packet strip. Android's animator-duration setting was absent; transition/window scales were 1.0 and battery saver was OFF. A temporary animator-scale write to 1.0 followed by restoration of its original absence preceded the maintainer's confirmation that animations returned. No app code/APK changed, and the exact cause of the earlier frozen effective animation state is unproven. Effective animation behavior was not verified for every transfer trial; these measurements cannot establish #47's animated foreground baseline or candidate benefit. Do not infer that the reported animation loss existed throughout either session.

Neither a successful local preflight send nor Android service state is an endpoint acknowledgment or a DPI guarantee. The 20 successful cellular VPN/direct exchanges prove their current paths, not why earlier blocking/recovery occurred. Charging, foreground CPU and thermal observations do not establish battery drain or savings.

No optimization target is frozen, transfer candidate selected, energy baseline delivered or performance gain claimed. [#47](https://github.com/novakin/vpn0wn-android/issues/47) still needs the prescribed visible/covered/hidden UI and unplugged idle-energy baselines before target selection. Its candidate must preserve visible eligible animations and target unnecessary hidden/covered work; removing visible motion is not its acceptance. Clean Wi-Fi direct/VPN context remains unavailable behind the router VPN. Cellular coverage is separate evidence, not a replacement for that missing context. [#28](https://github.com/novakin/vpn0wn-android/issues/28), [#34](https://github.com/novakin/vpn0wn-android/issues/34), [#35](https://github.com/novakin/vpn0wn-android/issues/35), [#36](https://github.com/novakin/vpn0wn-android/issues/36) and #52 retain their full physical/provider/peer/accessibility and optimization acceptance. The separate protocol experiment [#61](https://github.com/novakin/vpn0wn-android/issues/61) is neither implemented nor tested by these baselines.
