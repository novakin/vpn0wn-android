# Native warm-up acceptance with a kernel peer

Last updated: 2026-10-11 (Europe/Paris).

## Scope and provenance

This separately authorized synthetic trial observes the userspace Android client against the phone's existing Linux kernel WireGuard implementation. It adds same-phone IPv4 loopback kernel interoperability to the earlier [userspace-peer packet record](2026-10-11-native-warmup-packet-acceptance.md). It modifies no protocol, kernel driver, global tool/module, personal profile or remote server. #61 remains open for wider lifecycle, network and DPI acceptance.

Device: rooted Pixel 7 Pro, Android 14/API 34, Magisk 30.7, SELinux Enforcing. USB remained connected. The original legacy debug installation and active kernel tunnel were preserved. A fresh `com.vpn0wn.android.debug` installation owned only `kernel-explicit` and `kernel-omitted`, then was removed.

Installed source: `7d0e87629d868402c2c19846d82e6875e2d074b9`; APK SHA256 `081a920703e2a3e172dc9daa611a60392c1c5fa76529320020e81b7db118c865`. Retained artifact inspection verifies package, signature, current native exports/paths and source provenance. Subsequent composed source and [unsigned release inspection](2026-10-11-release-artifact-acceptance.md) have separate revisions and supply no new installed-device proof here.

## Isolated standard peer

The running kernel driver was already registered and serving the original UP interface. A finite, exclusively owned namespace holder received a new `vpkn61` interface through one exclusive native `RTM_NEWLINK` request with its pinned `IFLA_NET_NS_FD`. The kernel records the original namespace as the WireGuard UDP socket's birthplace while the interface lives in the private peer namespace. Exact observed-kernel source analysis at `12f3388846c3` supports that path through [rtnetlink](https://android.googlesource.com/kernel/common/+/12f3388846c3/net/core/rtnetlink.c), [WireGuard device](https://android.googlesource.com/kernel/common/+/12f3388846c3/drivers/net/wireguard/device.c) and [socket](https://android.googlesource.com/kernel/common/+/12f3388846c3/drivers/net/wireguard/socket.c) ownership; this trial does not attest the executing driver against a reproduced binary or another deployment.

Peer inner address `10.7.0.1/32` and a finite standard-library HTTP listener exist only inside that namespace. The client uses `10.7.0.2/32`, AllowedIPs `10.7.0.1/32`, endpoint `127.0.0.1:51891`, MTU 1280 and no DNS/default route. This prevents an original-namespace local-route shortcut to the peer HTTP service. No userspace WireGuard peer/netstack, global route/rule/firewall/sysctl edit, interface move, module load or SELinux change is used.

The independent ultra-reviewed frozen harness is SHA256 `86f259d35e5f680d1084972f3d7965fba922114986a82588682c49535f54f18f`; its final manifest is `02da3ae3a3d6da34bde4ed3b367f7a5aaa73212a33f2532624d018949dd65d4d`. Review covered namespace/interface/process/FD ownership, partial failure cleanup and the sequential launcher. Host controls passed 83 mocked Python cases and nine shell checks; unchanged native-helper proof retains its earlier seven Go test functions.

Before finite resources or captures, setup granted only the newly admitted package/user-0 `ACTIVATE_VPN` operation from ignored to allowed, with recorded UID/intent and readback. No UID-wide grant, original-app operation or always-on/lockdown change occurred. This is test consent setup; it adds no product permission shortcut. Cleanup verifies ignored mode before owned full uninstall. AppOps-record removal follows the [Android 14 package-removal contract](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/appop/AppOpsService.java); the fixture does not directly query that record after uninstall.

## Actual packet and traffic results

Both 25-second `lo` captures use the earlier parser's full PCAP/IPv4/UDP/filter/tuple/count/zero-drop checks. Explicit ordering requires exactly one matching one-byte-zero warm-up before each 148-byte type-1 initiation on the same outgoing tuple. Omitted ListenPort requires an initiation and zero warm-ups. Capture exit 124 is the expected finite timeout, with complete exit/statistics/file collection.

| Profile | Client source port | PCAP records | Initiations | Warm-ups | Kernel RX/TX increase | HTTP | Kernel drops |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Explicit 35000 | 35000 | 14 | 1 | 1 | 836 / 588 bytes | Expected text / 200 | 0 |
| Omitted | 57510 | 13 | 1 | 0 | 836 / 588 bytes | Expected text / 200 | 0 |

The explicit warm-up/initiation are records 1/2; destination port is 51891. Filter receipts are 28/26 and captured counts match the 14/13 complete PCAP records. Both peer snapshots independently establish a fresh handshake and positive RX/TX deltas. Each bounded curl probe binds to the recorded synthetic TUN and verifies its address/identity. HTTP text/status comparison retains the prior UTF-8, outer-whitespace and CRLF normalization boundary; it is not a raw-body byte-equality claim. Automatic source port 57510 describes this activation only.

## Restoration and retained failures

Both synthetic activations settled DOWN. Captures completed and were retained; only the owned private interface/resources and candidate installation were removed. Finite HTTP/namespace processes expired without PID signals. Cleanup verifies absent owned paths/processes/listener/TUN, settled package authorization, zero errors/mismatches, all 12 preservation fields, network equality and original kernel equality. The original app was returned to the foreground; a separate fresh post-return sample again matches all 12/network/kernel fields and confirms candidate absence.

Earlier rejected setup sessions remain separate: absent-package observation, Android shell FD parsing, old iproute2 alias/type parsing, and VPN consent inside the finite trial window. Direct private-namespace creation and consent setup corrected only fixture prerequisites. Each archived session has checked cleanup/post-return preservation; none supplies successful traffic proof.

Accepted raw evidence stays ignored/private at `.artifacts/overnight-20261011/kernel-peer-runtime-accepted-20261011-0450/`: both captures/statistics/summaries, HTTP and kernel snapshots, consent receipt, cleanup and post-return preservation. Frozen helper/review records remain under `kernel-peer-prep/`. Successful whole-session log: `20261011-045014-060-3c5d207765474078afaec3bdb89d8f31.log`. No secret-bearing configs, phone identifiers or raw device reports are committed.

## Remaining limits

This proves only the observed IPv4 loopback kernel peer with synthetic traffic. External/IPv6 servers, other kernels/devices, descriptor/protection timing, concurrent lifecycle races, hardware deep suspend, underlying-network handover, DPI benefit, speed and energy remain unverified. The USB/ambient router-VPN environment supplies no energy or DPI baseline. No #61/#71/#103 closure, signed release, personal data migration, root-tools installation or live-server change follows.
