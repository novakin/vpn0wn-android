# G4 APK and UDP preflight investigation

Last updated: 2026-10-07 (Europe/Paris).

## Scope and authorization

[#52](https://github.com/novakin/wireguard-android/issues/52), G4 of the [active goal](../improvement-plan.md#active-enhancement-and-performance-goal). Executed source revision: `39bb2c3cf6941a8b4ee0da8e666ca46e2f155031`. The maintainer explicitly authorized CI APK build/device testing, temporary toggling of the single saved tunnel, and installation of iperf3 on the named benchmark VPS with a temporary TCP 5201 listener restricted to the observed test source IPs. This did not authorize provisioning changes, permanent services or destructive VPN-server validation.

The original dirty checkout, installed APK and private profile backups remain local. This record publishes sanitized observations only. No app source, WireGuard packet format, MTU, routes or keepalive policy changed during the investigation. The maintainer confirmed changing ListenPort during recovery: an earlier port change failed, and the final change did not recover connectivity immediately. Snapshots do not establish exact port state at every trial or why delayed recovery occurred.

## Artifact and preservation proof

- Authorized [CI run 37576390608](https://github.com/novakin/wireguard-android/actions/runs/37576390608) succeeded on the source revision above, including checks, APK assembly and upload. Artifact `11462148338` was downloaded before its seven-day expiry, `2026-10-14T05:31:58Z`; extracted source revision and SHA256 matched. This is a debug APK, not a release.
- Test phone: Pixel 10a, Android 16/API 36, ARM64, `com.wireguard.android.debug`. The installed APK was pulled for rollback. A corrected app-data tar backup was validated and privately hashed; the initial archive containing a nonexistent `shared_prefs` path was invalid and remains failed evidence.
- The CI APK used a different ephemeral signing key. A separate copy was signed locally with the existing debug key whose certificate matched the installed app. Signature verification and non-`META-INF` ZIP-entry comparison passed; the original CI artifact was retained untouched. No key was uploaded and no uninstall/data-clear occurred.
- `adb install -r` succeeded. Immediately afterward the profile and preference files matched their backup hashes. The Android `profileInstalled` marker changed; it is not tunnel configuration. Later snapshots retained identical preferences and all profile fields except ListenPort. Preserve that newer profile rather than blindly restoring the earlier backup.
- `apkanalyzer` exports of the entire old/new `GoBackend` class were byte-for-byte identical. The ARM64 `libwg-go.so` entries also matched. These comparisons rule out a changed preflight/backend implementation in this APK update; they do not prove every app/resource was identical or certify the network path.

## Bounded network observations

Wi-Fi/userspace/debug build; portrait screen on and USB charging. The original profile had an explicit nonzero ListenPort, no explicit MTU and per-peer keepalive 25 seconds. It was a maintainer-authorized existing profile, not a synthetic performance fixture. Direct and VPN public sources were observed separately and kept private. No mobile-data/kernel coverage or controlled background-load isolation was performed.

The Android iperf3 client was built locally from official ESnet 3.22 source with its published SHA256 checked, using the existing Android NDK. Server packages were Debian's iperf3/libiperf0 `3.18-2+deb13u2` and libsctp1 `1.0.21+dfsg-1`, with no upgrades. TCP trials used one stream, three omitted warm-up seconds and 15 measured seconds. These are isolated diagnostics, not the repeated baseline required by QA.

| Observation | Result and limit |
| --- | --- |
| Direct upload, one trial | Receiver 356.36 Mbps; no sender retransmits. No repeated variability result. |
| Direct download, one trial | Receiver 137.51 Mbps. No repeated variability result. |
| Uncapped VPN upload | Data initially flowed, then stalled after roughly six measured seconds with retransmits. Client reported `control socket has closed unexpectedly`. Incomplete/error result is not a sustained throughput baseline. |
| Later VPN connectivity controls | TCP 22/5201 timed out while the benchmark listener was active. The temporary TCP 5201 accept/drop counters stayed zero during that control: no matching test traffic reached that listener. This does not locate the loss or prove DPI. |
| Capped VPN control after reconnect | One 20 Mbps upload completed: receiver 19.98 Mbps over 15.05875 seconds, no sender retransmits. This capped diagnostic cannot replace the uncapped baseline or establish a gain. |
| Maintainer report | The maintainer reported DPI/IP blocking, then reported that connectivity worked again. The maintainer confirmed a ListenPort edit, an earlier unsuccessful port edit and delayed recovery after the final edit. No router, classification or endpoint packet-capture evidence independently established the cause. Do not attribute recovery to a port, APK/code or preflight fix, or claim one fixed profile throughout. |

Selected app evidence showed no fatal exception and the captured thermal status was 0. These narrow observations do not exclude other device, path or server failures. An intermittent SSH closure also occurred; a later authenticated SSH read succeeded. No app fix or replacement APK was built after the reported failure.

## Preflight finding

The [current owner](../../tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java) protects and binds its warm-up socket, sends one zero byte to each eligible resolved endpoint, logs completion, and closes the socket before native activation. The explicit ListenPort makes this profile eligible. In one scoped app capture, three `UDP preflight sent` events and zero preflight-failure events were observed. Later scoped logs contained repeated handshake initiation/timeouts. Missing receive events are an observation of those captures, not a complete endpoint history.

The local preflight send path executed. A success log is produced after `DatagramSocket.send`; it is not an acknowledgment from the server. Packet arrival, later native socket/NAT handoff and DPI effectiveness remain unproven. Preflight does not obscure the native WireGuard protocol. The observed port change alters the UDP flow and prevents a fixed-profile recovery comparison; it does not establish why the path recovered.

The [#37 source compatibility decision](2026-10-07-udp-compatibility-review.md) remains a separate historical boundary. The retained random-port/multiple-packet experiment was not present in either inspected installed APK. Do not copy it over lifecycle/config repairs or call it a proven DPI fix. A changed network-compatibility requirement needs bounded evidence and an explicit policy decision preserving supported profiles and configured-port behavior.

## Cleanup, limits and remaining work

The listener ran as an owned transient systemd unit with a 20-minute maximum lifetime and firewall-table removal on stop. Its isolated nft table allowed TCP 5201 only from the observed direct/VPN sources; unrelated firewall tables and SSH rules were not edited. Final readback found no TCP 5201 listener or temporary table. The transient failed-unit state was reset, its runtime log/directory removed after local capture, and the phone benchmark executable removed. Installed iperf3 packages remain; the packaged service is disabled/inactive. Original APKs, private evidence and failed command logs remain retained.

Final Android VPN-service state was active, and the maintainer reported recovery; service state alone is not a health check. The temporary stay-awake override was restored to its saved value. No other device preference/locale/theme/rotation change was made. Do not claim an attempted UI tap stopped the tunnel: the subsequent state check remained active.

Dark portrait Home/Settings output was inspected locally at the phone's real size. No complete Settings/Add/peer/Home/Downloads, theme/rotation/font-scale/RTL/TalkBack or storage-provider acceptance was completed. #28/#34/#35/#36 stay open. USB-powered observations do not prove battery drain or savings. Repeated transfer/idle baselines, frozen targets, #47 selection and demonstrated optimization remain pending in #52.

Next proof requires a stable authorized test path and fixed recorded profile. Agree the recovery/port state before repeated tests, preserve subsequent user changes, and collect endpoint arrival/response evidence only within an authorized operation. No speed gain, battery gain, DPI guarantee, completed G4 or full goal completion is asserted.
