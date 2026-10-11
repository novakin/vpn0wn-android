# IPv6 endpoint repair and native warm-up acceptance with a kernel peer

Last updated: 2026-10-11 (Europe/Paris).

## Scope and provenance

This separately authorized synthetic follow-up repairs the Android IPv6 endpoint failure discovered during [#61](https://github.com/novakin/vpn0wn-android/issues/61) acceptance. The native observation callback belongs to [#73](https://github.com/novakin/vpn0wn-android/issues/73); [#141](https://github.com/novakin/vpn0wn-android/issues/141) owns the bounded defect. [PR #142](https://github.com/novakin/vpn0wn-android/pull/142) owns final-head review/checks and delivery. This record adds same-phone outer IPv6 interoperability to the earlier [IPv4 userspace-peer](2026-10-11-native-warmup-packet-acceptance.md) and [IPv4 standard kernel-peer](2026-10-11-kernel-peer-acceptance.md) records; their historical facts and limits remain unchanged.

Device: rooted Pixel 7 Pro, Android 14/API 34, Magisk 30.7, SELinux Enforcing. USB remained connected. The original legacy debug installation and active kernel tunnel were preserved. A fresh `com.vpn0wn.android.debug` installation owned only `ipv6-explicit` and `ipv6-omitted`, then was removed.

Installed source: `20ed2f66f7ffa89fcf5071d27c51728d227d8a22`; tree `dad23bae4029a08750e6b68122f5a7d67521413b`. APK SHA256: `e7ffab8f3c63483faf5daee49392a75d2c1896cda392f803352019ee2f425ca4`; certificate SHA256: `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. The fresh artifact audit is SHA256 `12b0398275e83f18b3b1f6106f23c27ea2ef6019aec1ef754bb7eb31acbf0628`. Package/signature inspection and the installed APK hash tie this trial to the repaired candidate, not the later documentation head.

Independent source review passes this exact checkpoint after resolving missing direct malformed-numeric-callback coverage. Android-faithful JVM controls reproduce the old failure and cover parser/callback compatibility; detailed execution and source-equivalent precommit scope remain in the PR review. Independent artifact/harness review also passes: actual DEX uses the bare numeric host on success and original URI host on unresolved fallback, retaining the port on both paths. All four packaged `libwg-go.so` libraries are byte-identical to the baseline APK; packet scheduling and native transport are unchanged.

## Confirmed Android parser boundary and repair

Baseline source `e0a1831362cb61b266b853ab70ff5b8f8e9a694c`, APK SHA256 `bc8e595ede320b469208a8bfa58c7d585af6895397ccde481056ef10973279ee`, opened native sockets but aborted activation when `GoBackend.captureHandshakeObservation` reparsed the bracketed numeric endpoint. Java threw `IllegalArgumentException: Invalid native numeric endpoint` through `VpnService.onNativeHandshakeObservation` and `wgTurnOn`; the switch returned DOWN. No authenticated baseline traffic is established.

The numeric-only API 34 platform probe confirms that `URI.getHost()` returns `[::1]`, Android numeric parsing rejects that bracketed host, and bare `::1` succeeds as `Inet6Address`; the IPv4 control remains accepted. The helper opened no sockets, resolved no DNS and changed no app/tunnel state. Its source/JAR were inspected and hash checked; the owned upload and directory were removed with readback.

`InetEndpoint.parse` now strips only URI host brackets before numeric validation and stores the bare host only on success. Numeric spelling and accepted scope suffixes remain intact; rejected numeric/scoped candidates retain the exact original URI host on the existing unresolved path. Existing serialization restores `[address]:port`. Hostnames, ports, URI/input errors and DNS resolution policy are unchanged. Native observations still require a numeric address; malformed callbacks retain startup-failure discard. The [configuration contract](../backend-contracts.md#wireguard-configuration-and-tunnel-state) and [QA requirements](../qa-test-plan.md#native-handshake-observation-controls--73-draft) own this behavior.

## Isolated standard peer and reviewed fixture

The phone's existing standard kernel WireGuard implementation listens at outer `[::1]:51891`. A finite, exclusively owned namespace holder receives a new interface through direct exclusive creation with its pinned namespace FD, preserving the UDP socket's original-namespace birthplace. This reuses the ownership described in the [IPv4 kernel-peer record](2026-10-11-kernel-peer-acceptance.md#isolated-standard-peer); no interface move occurs.

Peer/client inner addresses remain `10.7.0.1/32` and `10.7.0.2/32`, with IPv4 HTTP, AllowedIPs `10.7.0.1/32`, MTU 1280 and no DNS/default route. The HTTP listener exists only in the private peer namespace, preventing an original-namespace local-route shortcut. No module load, SELinux change, global route/rule/firewall/sysctl edit or remote-server mutation is used. Only the newly admitted package/user-0 `ACTIVATE_VPN` operation is temporarily allowed; cleanup verifies authorization settlement and candidate removal, preserving the original app's operation and system VPN policy.

Frozen harness SHA256: `6a09db057c100ed321e1eb1b0301dc2844ce162a03b30aad7f3a7880a0028f0c`; launcher SHA256: `3b2d10579e623e0e04b8adedfd8e5ec12b542d697e3042aa92eeae664f28a9ec`; manifest SHA256: `3ef67b8b8faa956898583f74d4a36787dc6baae9cd25634097ef0ffc62ed26e2`; affected independent ultra review SHA256: `89ff1ae2bdad2c4a760beca9d759101e3175eb5edaf3851316d845c099267cb0`. Fresh host controls pass 60 mocked cases. All 66 runner function ASTs, decoder/test files and launcher flow preserve the corrected predecessor; only seven provenance/output assignments and associated input pins change. Those host checks establish fixture readiness; the actual results below establish this trial's transport acceptance.

## Actual packet and traffic results

Both 25-second `lo` captures pass complete PCAP/IPv6/UDP/filter/tuple/count/zero-drop checks. Explicit ordering requires one matching one-byte-zero warm-up before each observed 148-byte type-1 initiation on the same outgoing IPv6 tuple. Omitted ListenPort requires an initiation and zero warm-ups. Capture exit 124 is the expected finite timeout, with complete exit/statistics/file collection.

| Profile | Client source port | PCAP records | Initiations | Warm-ups | Kernel RX/TX increase | HTTP | Kernel drops |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Explicit 35000 | 35000 | 14 | 1 | 1 | 836 / 588 bytes | Expected text / 200 | 0 |
| Omitted | 45943 | 13 | 1 | 0 | 836 / 588 bytes | Expected text / 200 | 0 |

The explicit warm-up/initiation are records 1/2; destination port is 51891. Filter receipts are 28/26 and captured counts match the 14/13 complete PCAP records. Both kernel snapshots independently establish a fresh handshake and positive RX/TX deltas. Each bounded curl probe binds to the recorded synthetic TUN and verifies its address/identity. Inner HTTP remains IPv4; text/status comparison preserves the existing UTF-8, outer-whitespace and CRLF normalization boundary, not raw-body byte equality. Automatic source port 45943 describes this activation only. Captured tuple ordering does not measure descriptor/generation identity or promise global adjacency.

## Restoration and retained stopped sessions

Both synthetic activations settled DOWN. Captures completed and were retained; owned interface/process/path/listener/TUN resources and the candidate installation were removed. Finite HTTP/namespace processes settled without PID signals. Cleanup verifies authorization settlement, zero errors/mismatches, all 12 preservation fields and the sampled original kernel/interface/tool snapshot. That kernel comparison covers interfaces, links and the tool SHA256. The original app returned Home; a separate fresh post-return sample again matches all 12 fields and the same kernel snapshot and confirms candidate absence.

Both network comparisons pass sampled semantic equality, excluding only naturally aging finite seconds after `valid_lft`, `preferred_lft` and `expires`. Every other structural field remains exact; raw samples are retained. This is not raw-byte equality or continuous original-tunnel reachability.

Two earlier stopped setups remain separate. `kernel-peer-ipv6-runtime-stopped-names-20261011-0738` used names exceeding the existing 15-character limit: the app imported zero profiles, and no activation/capture began. `kernel-peer-ipv6-runtime-stopped-activation-20261011-0747` used valid corrected names but failed at the confirmed Android parser boundary; omitted-port and authenticated-traffic acceptance never ran. Both retain checked cleanup and fresh post-return preservation, original kernel/network state, candidate absence and owned-resource removal. The later numeric parser probe was separately cleaned. Neither stopped trial supplies successful transport proof.

Accepted raw evidence stays ignored/private under `.artifacts/overnight-20261011/kernel-peer-ipv6-repair-runtime/`: captures/statistics/summaries, HTTP/kernel snapshots, authorization, installed-artifact hash, cleanup and fresh post-return preservation. Frozen inputs/reviews remain under `kernel-peer-ipv6-repair-prep/`; parser platform and artifact evidence remain in their adjacent preparation directories. Successful whole-session log: `20261011-081114-733-7b65fe3d2c6a4bf2b6294a2dcc609d59.log`, exit 0. No secret-bearing profiles, phone identifiers, raw device reports or private captures are committed.

## Remaining limits

This proves the repaired client's observed same-phone outer IPv6 loopback path to a standard kernel peer, with synthetic IPv4 inner HTTP. External IPv4/IPv6 paths, inner IPv6, imported/direct-zero over IPv6, other kernels/devices, descriptor/generation/protection timing, concurrent lifecycle races, hardware deep suspend, underlying-network handover, broader unchanged-server interoperability and DPI acceptance remain open. USB/ambient router VPN supplies no energy or DPI baseline. No DPI, speed or battery gain, full #61/#73/#71/root-tools/migration/distribution acceptance, signed release or live-server change follows. #141's integration status and final-head review/checks belong to PR #142; this runtime record alone is not integration.
