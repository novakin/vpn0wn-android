# Native warm-up packet acceptance — rooted Android

Last updated: 2026-10-11 (Europe/Paris).

## Scope and provenance

This is separately authorized, scoped #61 acceptance on 2026-10-11 (Europe/Paris). It observes native IPv4 loopback UDP packets and authenticated synthetic traffic for explicit, omitted and imported-zero ListenPort profiles. It changes no product code, protocol, personal profile or server. #61 remains open for its wider physical/interoperability/DPI boundary.

Device: rooted Pixel 7 Pro, Android 14/API 34, Magisk 30.7, SELinux Enforcing. USB remained connected; no energy or hardware-suspend measurement was attempted. The existing legacy debug installation and its kernel tunnel were preserved. A separate `com.vpn0wn.android.debug` installation owned only the three synthetic profiles and was removed after testing.

Candidate APK SHA256: `9c6c47a27cee9a4ed72730c7908150ce5ec7f2c8009eaf583097886a68455a4f`. Literal built/runtime source: `7ead52cdc71c06bda92b83c6b60e43a021790dbc`; subsequent namespace changes were documentation only. [Namespace acceptance](2026-10-11-namespace-acceptance.md) owns its build, manifest, ABI and earlier runtime provenance. Later helper/export source naming is outside this installed artifact's acceptance.

The synthetic peer binary SHA256 was `5637aad7d0649427be8985392c876fff10c6b1fe6ba40eb6158fc09a1d9b5958`. It uses a separately compiled pinned unmodified userspace engine with ordinary `StdNetBind`, built from an independent module cache without overlay or replacement. Retained build log `20261010-134817-744-fc7dda213ab9409fb15c2bea7da1a39d.log` verifies modules and matches the trial binary hash. Review compared 66 cached `device`, `conn` and `tun/netstack` source files byte-for-byte with the retained pinned module ZIP; all matched. This supplies successful same-phone IPv4 loopback interoperability only, not broader deployed/kernel/external-server acceptance. Its HTTP service is reachable only through the synthetic WireGuard exchange. Each peer run had a finite three-minute lifetime; the first expired during fixture correction, and its retained run record preceded a fresh peer for both disabled-port trials.

## Trial and evidence checks

All profiles used `Address = 10.7.0.2/32`, `AllowedIPs = 10.7.0.1/32`, `Endpoint = 127.0.0.1:51891` and no DNS/default route. The explicit profile used ListenPort 35000; the other ZIP entries omitted it or supplied zero. Native provider import created all three profiles, and fresh VPN consent was completed before the accepted capture trials. Stored synthetic fields matched their input after expected zero-to-absent normalization. Explicit 35000 remained stored; omitted and imported-zero profiles both stored no ListenPort.

Each capture used device tcpdump 4.99.4/libpcap 1.10.4 on `lo`, full snapshots, the exact IPv4 filter `ip and udp and src host 127.0.0.1 and dst host 127.0.0.1 and port 51891`, and a 25-second timeout with final exit/statistics collection. All three capture exits were 124, the expected finite timeout; completed PCAP/statistics and zero-drop checks supplied acceptance. No test traffic used the Internet or personal tunnel. Peer start was delayed during the explicit trial to observe multiple ordinary initiation attempts; no sleep/retry logic was added to the app.

The private parser validates complete classic PCAP/IPv4/UDP frames, filter scope, captured-record count and zero kernel drops. Each trial must have one outgoing UDP tuple and only its reverse incoming tuple. Explicit initiation classification requires type-1's four-byte header and 148-byte length; warm-up classification requires exactly one zero byte. Each explicit initiation must have a matching preceding outgoing warm-up, allowing incoming traffic to interleave, with equal cardinality and no unmatched warm-up. Disabled cases must contain an outgoing initiation and zero warm-ups. HTTP acceptance requires status 200 and expected synthetic text, independent of UI UP state.

The ADB helper UTF-8 decodes the curl response and strips outer whitespace. The HTTP helper retains that decoded text after UTF-8 re-encoding, then normalizes CRLF to LF for comparison with `tunnl-native-peer\n\n200`: the expected synthetic text and curl status suffix. This establishes expected text/status after those transformations, not raw ADB bytes or byte-exact HTTP-body preservation.

| Profile input | Observed client source port | PCAP records | Type-1 initiations | One-byte warm-ups | Authenticated HTTP | Kernel capture drops |
| --- | --- | --- | --- | --- | --- | --- |
| Explicit 35000 | 35000 | 22 | 4 | 4 | Expected text / 200 | 0 |
| Omitted | 57117 | 15 | 1 | 0 | Expected text / 200 | 0 |
| Imported zero | 36563 | 15 | 1 | 0 | Expected text / 200 | 0 |

All captured explicit outgoing packets retained source port 35000 and destination port 51891. Warm-up/initiation pairs were records 1/2, 3/4, 5/6 and 7/8. Captured filter receipts were 44/30/30 respectively; loopback receipts exceed parsed records, while captured counts match each PCAP and reported kernel drops are zero. Automatic source-port values describe these fresh activations, not a fixed-port policy.

Executed private harness actions were snapshot, fixture preparation/install/native import, scoped Detail state changes, finite capture/peer start, TUN-bound curl, collection/parser and cleanup. Curl used `--max-time 8 --interface tun0` against `http://10.7.0.1:8080/health`; the guard checked the synthetic TUN address before each probe. Final parsers passed all three collected trials. Raw PCAPs, synthetic configs, screenshots, preservation snapshots and command logs remain ignored/private under `.artifacts/overnight-20261011/warmup-packets` and `.artifacts/codex-command-logs`; they are not source-distribution artifacts.

## Fixture corrections and review

Failed setup evidence remains separate: absent global `wg` returned 127 before a snapshot was recorded; Windows stdout encoding failed after the initial entry capture; Magisk waited for an internally backgrounded capture, producing an empty capture before any UP attempt; the first HTTP comparator rejected CRLF despite the expected body/200; and a hidden Home row locator left the previous Detail selected, so its state guard refused before toggling and the capture was empty. Fresh consent was an expected setup boundary, not a VPN failure.

Corrections use the existing app-private `wg` only for read-only interface observation, include its hash in preservation checks, enable Python UTF-8 output, background the redirected `su` client from the outer shell, refuse prior capture files/records, retain the decoded outer-whitespace-stripped curl response before CRLF normalization, and require return to Home before row selection. No product fix or failed connectivity result is inferred from those fixture failures.

Independent pre-run and delta review accepted the bounded fixture. Retained private parser controls passed 17/17, including incoming interleaving, absent/extra warm-up, absent initiation, tuple mismatch, truncation/drop and HTTP-failure rejection; recomputation matched all three trial summaries. Final offline locator controls passed 19/19 in retained log `20261011-020801-043-7fbf191ceac2456989f5a3ad560307ef.log`, including shared hidden Home/Detail ownership, wrong/missing/duplicate names/switches, settled state and HTTP ownership. Read-only guard-source inspection additionally covers app-private tool path/hash, DOWN/error handling, HTTP transformations, outer-shell backgrounding, redirected descriptors, finite capture/filter, prior-path/record refusal and readiness. No retained execution receipts establish separate private-tool/HTTP/launch control totals; no such totals are claimed. Executed parser/locator controls are private harness proof, not new CI/product tests.

## Restoration and remaining limits

After each accepted trial, only its synthetic profile was set DOWN. Cleanup confirmed no TUN remained, stopped or confirmed expiry of the owned peer using executable/start-time identity, collected completed finite captures, removed the owned candidate package and canonical scratch/upload/hierarchy paths, and retained private host evidence. No unrelated Magisk policy/module or WireGuard tool was changed. The original installation/home was restored.

All 12 original preservation fields matched both after cleanup and after returning to the legacy Home: original APK, config/preference hashes, shared tools/modules, device/accessibility/locale/tile settings, root policies and network interfaces. Original kernel interfaces remained UP in the checked snapshots, with unchanged interface/link observations and app-private `wg` hash. Zero mismatches were recorded; candidate package and owned paths were absent. This is sampled restoration proof, not continuous original-tunnel reachability monitoring.

This supplies previously missing wire-level warm-up ordering/source-port and disabled-port controls for one native Android IPv4 loopback path. It does not identify socket descriptors/generations or bracket TUN retention, and imported zero does not exercise a direct explicit-zero native UAPI hook. It establishes no IPv6/external endpoint, new Doze/hardware sleep, rebind/handover/destruction/concurrent-phone race, sustained stability, physical QR/TalkBack, full old/new-app export migration, broader deployed/kernel/external-server, DPI, speed, CPU or battery proof. Source generation/lifecycle controls and [earlier rebind acceptance](2026-10-10-native-rebind-acceptance.md) retain their own scope. This record closes neither #61 nor #71 and does not complete G4 or #47. #48's delivered correctness repair retains its original scope.

Final documentation-head independent ultra review, publication, applicable CI and integration belong to the delivering PR; pre-run fixture review and observed packet acceptance are distinct from those gates.
