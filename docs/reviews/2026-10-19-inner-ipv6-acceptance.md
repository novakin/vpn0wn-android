# Ordinary WireGuard inner-IPv6 acceptance — #61

Last updated: 2026-10-19 (Europe/Paris).

## Scope and result

The separately authorized API24 x86_64 A1 cohort passes seven native stages and eleven cleanup/preservation checks through production Application, TunnelManager, GoBackend and VpnService. A request before activation times out. Explicit and omitted ListenPort then carry exact framed IPv6 HTTP with fresh handshakes through ordinary WireGuard to an unmodified official userspace peer. Independent ultra runtime/cleanup review passes with no actionable blocker. [#61](https://github.com/novakin/vpn0wn-android/issues/61) stays open for its broader physical, interoperability and DPI boundary.

This is one isolated local guest/host path over outer IPv4. The earlier [IPv4 warm-up capture](2026-10-11-native-warmup-packet-acceptance.md), [standard kernel peer](2026-10-11-kernel-peer-acceptance.md) and [outer-IPv6 kernel-peer](2026-10-11-kernel-peer-ipv6-acceptance.md) records retain their own source, observations, failures and limits. This cohort changes no product source, configuration contract or operational policy.

## Exact source, artifacts and preparation

Frozen product source is `ad03dda6ad0023b151ec42f856efb755076b89bb`, tree `a78485cab54b89cc69b412496215aed2f8a19d7e`. Independent artifact reviews verify the clean source, initialized gitlinks, eighteen consumed inputs and eleven source owners. The isolated debug package is `local.vpn0wn.inneripv6probe.debug`, minimum API24 and target36, with x86_64 libraries. Actual manifest/DEX retain the production owners, reject Amnezia settings for this probe, select ordinary `wgTurnOn`, and validate the local/remote IPv6 addresses and framed response.

| Artifact | Size | SHA256 |
| --- | --- | --- |
| A1 APK | 19,751,811 bytes | `eecaa41dc4cd363ee0272f7b1ff00ad53b09d6e59ee4f3c4e93356a2e45b7eb1` |
| B2 Windows AMD64 peer | 13,813,760 bytes | `82c4660d75b919d65a580dd79c11cc5817777c835de03ecfd47958373c026ebc` |

The APK has one valid v2 debug signer and passes 16-KiB alignment. All four packaged native libraries match their stripped producer bytes. The combined library retains both product engines; the probe's ordinary selection does not exercise AmneziaWG. Its generated native inputs and pinned source archives match the independently audited producer. Installed and cleanup APK copies both match the accepted A1 hash.

B2 uses Go 1.26.9 and official `golang.zx2c4.com/wireguard v0.0.0-20250521234502-f333402bd9cb`, with no replacement, vendor tree or product overlay. All 107 WireGuard files and all eight linked module trees match the cached archives and original module sums. The selected graph has one main module and 95 dependencies; selected modules and linked executable dependencies are distinct scopes.

Peer B1 remains a failed offline selected-module metadata lookup: exit 1 before compilation, empty graph and no executable. The diagnosis found 87 absent exact-version `.info` entries. The bounded remedy reused 43 exact cached entries and fetched only the remaining 44 from the official Go proxy. The subsequent offline graph preserved all 96 identities and unchanged `go.mod`/`go.sum`; B2 then built successfully. This did not substitute module-source versions. Exact archive/source identity and original sums do not independently authenticate checksum-database downloads. The artifact audit also retains the missing historical process-environment snapshot and absence of an independent reproducible rebuild.

An earlier input review found that forced emulator-backend or launcher termination could still satisfy aggregate cleanup acceptance. The ignored controller was corrected before build/runtime admission to record either fallback as a cleanup error. Three offline controls cover normal PASS and both forced paths failing even when resource-absence flags pass; the retained pre-correction control fails as expected. The final independent input review accepts that correction. This cohort uses normal guest/peer shutdown; the original finding and failed control remain retained.

## Native traffic and cleanup

The client uses `fd61:19::2/128`, peer/route `fd61:19::1/128`, MTU 1280 and outer IPv4 endpoint `10.0.2.2:51894`. Routing includes only the ordinary probe app UID. There is no DNS, default route, keepalive, AWG setting, recovery or handover in the cohort. The peer HTTP listener exists only inside the virtual IPv6 netstack at `[fd61:19::1]:8080`; the host UDP bind is wildcard, not loopback-only. The request must arrive from the expected inner IPv6 address and carry the private per-run challenge and allowed phase. Response verification checks status, framing and exact body bytes, without publishing the challenge or full configs.

| Phase | Runtime source port | Native RX / TX | Observed result |
| --- | --- | --- | --- |
| DOWN negative | No active tunnel | Not a positive traffic case | `SocketTimeoutException` before activation |
| Explicit ListenPort | 35000 | 716 / 788 bytes | Fresh handshake and exact IPv6 HTTP |
| Omitted ListenPort | 56017 | 716 / 788 bytes | Fresh handshake and exact IPv6 HTTP |

The seven recorded stages are `down-negative`, `explicit-up`, `explicit-traffic`, `explicit-down`, `omitted-up`, `omitted-traffic` and `omitted-down`. Both native and independent peer observations establish fresh handshakes; the two peer handshake values are distinct within the same second. Native counters include protocol traffic; they are not application payload counts or speed measurements. Port 56017 describes this activation only. Native cleanup settles before the host force-stops the app; host stop is not the native DOWN proof. Actual system consent and installed identity were checked on an ordinary app UID.

All eleven cleanup checks pass: peer stopped, peer port released, package absent, process absent, service absent, UDP absent, baseline restored, guest stopped, owned ports released, shared owners preserved and source inputs unchanged. Before/after preservation snapshots match. Raw package/process/service/UDP readbacks corroborate cleanup; a separate post-settlement host observation confirms the target ports are absent. This is sampled preservation, not continuous reachability or a complete host inventory. Owned-process absence and shared-owner preservation rely on the reviewed controller receipts.

## Evidence and review

Raw evidence stays ignored/private under `.artifacts/continuation-20261019-inner-ipv6/runs/run-20261019-170604-4dd3d5/`. The actual runtime command settled exit 0 after 220,816 ms at `2026-10-19T17:09:50.4045175Z`; retained log `20261019-190559-123-61ea39b498214c878bfc3e27a22965e0.log`. All 107 command receipts settled. Frozen preparation, failed B1 evidence, artifact audits and final runtime review remain in the parent evidence directory; consumed inputs were not rewritten after execution.

| Independent evidence | SHA256 |
| --- | --- |
| APK A1 ultra review | `f239b980b6257c2ebe77e857631e31e829afd524760ad2cbde657022e36cae42` |
| APK A1 machine audit | `19783c3886be326e18f120155cb799c1d920e3948dac1a1b30ca42599ea3da88` |
| Peer B2 machine audit | `699b23bb577cfe31bc1d6f3eda0397b133fc9698029941a5546a3b49acddc030` |
| A1 runtime ultra review | `cf2ca03d2680219ca73fcc519857b6830d7ce985770ad0946660c6671751fc1e` |
| A1 runtime machine audit | `74005d47bf253065c5dae2d1cb7650ed77255a1657547ef49e1551309e7773c5` |

The independent runtime review reads retained evidence and runs only the offline checker. Its first inspection used an incorrect collection-count assertion; the corrected inspection passes and its failed review log remains retained. This is a reviewer correction, not a runtime retry. Final documentation review, CI and delivery belong to the documentation PR; local runtime acceptance alone does not establish publication or merge.

## Remaining limits

No new packet capture, warm-up ordering, descriptor/generation race coverage, protection-return/timing, external or physical IPv6 path, kernel peer, hardware suspend, lifecycle race, recovery/handover, DPI, performance, battery or release result is established. Explicit/omitted ListenPort traffic does not prove warm-up emission or its disabled state. Broader inner-IPv6 configurations and unchanged-server deployments remain separate, as do #73 recovery, #71 identity/release and #52/#47 measurements. #61 remains open.
