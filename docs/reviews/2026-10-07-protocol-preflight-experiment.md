# Protocol-level preflight experiment

Last updated: 2026-10-07 (Europe/Paris).

## Request and status

On 2026-10-07, the maintainer requested UDP preflight in the protocol itself and a separate experimental branch, then selected **unchanged standard servers**. [#61](https://github.com/novakin/wireguard-android/issues/61) owns this investigation on `experiment/protocol-preflight`, refreshed to master `4c2e5850`. That compatibility choice selects sender-side native warm-up before an ordinary WireGuard handshake. A new recognized or acknowledged protocol message would require receiver support and is excluded. The maintainer then approved the first implementation draft: one best-effort one-byte zero datagram before every outgoing initiation, with WireGuard retaining retry/rekey timing. Implementation has started locally; no usable experimental build, APK or device result is delivered. G4's installed build and measurements remain separate.

On the same date, the maintainer instructed that all experiment code, tests and documentation stay on this branch and [PR #63](https://github.com/novakin/wireguard-android/pull/63) remain draft and unmerged. A future merge requires an explicit instruction; G4's earlier autonomous merge authority does not apply to this experiment.

The earlier [#37 compatibility decision](2026-10-07-udp-compatibility-review.md) rejected the preserved mandatory/random-port variant. The new request does not authorize that variant's config, retry or failure-policy changes.

## Current source boundaries

[GoBackend.sendUdpPreflight](../../tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java) sends a one-byte zero datagram from a Java socket protected before bind/send. It uses an explicit nonzero ListenPort, skips absent endpoints and absent/zero ports, retries expected I/O best effort, then closes the socket before native activation. Local send does not establish remote arrival, classifier behavior or continuity with the later native socket.

[api-android.go](../../tunnel/tools/libwg-go/api-android.go) creates the device with `conn.NewStdNetBind`, applies settings and calls `device.Up`. Java obtains and protects native socket descriptors after `wgTurnOn` returns. A native experiment must resolve protection and first-send ordering at those existing owners; moving warm-up into an earlier native call alone does not prove protection before its send.

Inspected the installed module cache for the exact [go.mod](../../tunnel/tools/libwg-go/go.mod) pin `v0.0.0-20250521234502-f333402bd9cb`. Its `device/noise-protocol.go` declares message types 1–4: initiation, response, cookie reply and transport. `device/receive.go` drops datagrams below `MinMessageSize` (32 bytes), checks known message sizes and discards unknown types. The current one-byte warm-up is discarded before a normal message is processed. Linux [v6.12 `receive.c`](https://github.com/torvalds/linux/blob/v6.12/drivers/net/wireguard/receive.c#L28) rejects payloads shorter than `struct message_header`; that [header](https://github.com/torvalds/linux/blob/v6.12/drivers/net/wireguard/messages.h#L64) is a four-byte type. These are source observations for the named implementations, not executed kernel/device interoperability proof or a claim about every server implementation.

## Compatibility decision and alternatives

| Alternative | Peer and compatibility boundary | What it can establish |
| --- | --- | --- |
| Native send-only warm-up before the ordinary handshake — selected compatibility direction | A modified sender emits a one-byte zero datagram; the inspected standard receivers discard it. Valid WireGuard messages and crypto stay unchanged. Other peers and socket/protection lifecycle still require tests. | Controlled packet ordering and native socket ownership. A successful send is not a response, health check or DPI bypass. |
| Recognized or acknowledged preflight message — excluded | Requires matching receiver support, contrary to the maintainer's unchanged-server choice. Unknown-message handling does not supply an acknowledgment. | A different protocol/peer requirement; not this experiment. |
| Retain the current behavior | Preserves the existing reviewed compatibility and activation contracts. | No new protocol feature or improvement claim. |

The proposed observable change is socket continuity: warm-up and handshake use the same protected native UDP socket, rather than a Java socket followed by a separate native bind. This changes sender transport behavior; it does not add a message to the standard WireGuard protocol. No server installation, configuration change or new packet format is required by the selected direction.

## Native ownership and implementation boundary

Reuse wireguard-go's existing `conn.Bind` seam around `conn.NewStdNetBind`; do not fork the full module or replace its endpoint parser, packet batching or handshake implementation. The pinned `device.upLocked` opens the bind before starting peers. `device.BindUpdate` also calls the bind's `Open` during a rebind. The dependency's `listenConfig` and socket-control list are unexported, so no supported socket-open callback is available directly.

The proposed Android-owned bind protects every available IPv4/IPv6 socket synchronously inside `Open`, before returning receive functions or allowing an outgoing packet. Its JNI callback must retain the exact owning `VpnService`, handle Java/native thread attachment, settle callback exceptions/failures and release that retained reference after native users stop. Failed protection closes the sockets and fails native activation/rebind; it must never send an unprotected warm-up or handshake. This native protection requirement is distinct from a best-effort warm-up send failure. Java's current ignored protection results must not remain the experiment's success boundary.

Protection ordering requires a bind-owned send boundary, not reliance on device locking alone. The pinned `Peer.SendBuffers` takes `device.net.RLock`, but `SendHandshakeCookie` calls `bind.Send` without that lock. Specify socket-generation ownership, readiness during `Open`, concurrent sends, close/reopen and descriptor/reference lifetime before implementing the wrapper. No send may reach a newly opened socket before protection succeeds; cleanup must unblock pending I/O/waits and prevent old operations from using a replacement socket. Exercise cookie sends during open/rebind as well as normal peer traffic.

Remove the separate Java warm-up only when the native replacement and cross-language cleanup are verified. Preserve the service-identity checks around native activation and the transition owner. Specify and test cancellation during startup, the selected send policy, service destruction and bind closure; no thread may remain waiting for protection or an acknowledgment. The existing Java five-attempt, 200 ms expected-I/O retry policy remains current behavior. The recommended native policy below deliberately changes that timing and needs the maintainer's selection before implementation. Do not introduce a user setting or multiple production strategies to avoid that choice.

## Port and deep-sleep behavior

Warm-up and the following handshake use the native socket's local port. An explicit nonzero `ListenPort` remains that port. The destination address and port come from each peer's `Endpoint`. Absent or zero `ListenPort` keeps warm-up disabled and lets standard WireGuard select its port. Do not force a port, change a persisted profile or use a second warm-up socket. Reopening or changing networks does not authorize substitution of a configured port; NAT may expose a different public source port.

Port recovery is an implementation gate: the pinned `BindUpdate` resets `netc.port` to zero when `Open` fails. The selected design uses a narrow version-checked build overlay at that owner, publishing the actual port only after successful bind setup. A matching UAPI hook reports explicit ListenPort changes to the Android bind; automatic reopens do not change warm-up eligibility. Preserve intentional zero/nonzero updates and the pinned module/cache. Verify protection/open failure followed by retry before accepting this repair.

The maintainer asked whether warm-up would run after phone deep sleep. Sleep alone does not imply a bind reopen or a new handshake. Android scheduling, network/NAT state and WireGuard timers determine whether traffic resumes, a handshake initiates or the backend is restarted. A local source design cannot prove when a physical phone will wake or transmit.

| Timing option — maintainer selected per-initiation draft | Behavior and limits |
| --- | --- |
| Before every outgoing handshake initiation — approved for first draft | Attempt one warm-up in the same send path before each type-1 initiation, including ordinary retries and rekeys after wake when an initiation is generated. Do not prepend it to responses, cookies, keepalives or data packets. It does not wake the phone or guarantee a handshake on every resume. The selected best-effort policy below adds no separate retry loop or deliberate delay. |
| Only at tunnel startup | Emit warm-up during initial activation for eligible configured endpoints before peers can send. No automatic repeat on wake, rekey or handshake retry. Later socket opens still need protection. This preserves the existing activation-only eligibility. |

The timing/send-policy choice is approved. Independent ultra design review accepted the direction with required UAPI client drain, incremental native rebuild and cancellation/generation recheck controls. Shared production controls are implemented separately under [#66](https://github.com/novakin/wireguard-android/issues/66)/[PR #67](https://github.com/novakin/wireguard-android/pull/67); they do not include warm-up. The experimental implementation still requires final code/tests review. Kernel backend behavior is unchanged.

## Approved draft send policy and bounded delivery

The recommendation is one one-byte zero datagram attempt before each eligible outgoing initiation, followed by the unchanged initiation on the same protected socket generation and destination. Expected warm-up I/O failure must still allow the ordinary initiation; failed protection, service cancellation or a closed socket must not be converted into success. Unexpected programming defects retain their error meaning. WireGuard's existing handshake timers own subsequent retries and rekeys; do not add sleeps, acknowledgment waits, a timer, a wake lock or a separate retry worker. No deliberate delay does not mean the underlying send cannot block: closure/cancellation must release any affected I/O.

This approved draft changes activation-only Java warm-up with up to five attempts and 200 ms retry sleeps. Repeating that loop inside every native initiation would add latency and teardown contention. Keep the change entirely on the experiment branch; it does not authorize an experimental APK, network trial or merge.

Ordering is per initiation send path: the warm-up attempt precedes its corresponding ordinary initiation. Concurrent peer/control traffic may interleave on a shared socket; do not promise global packet adjacency or add global send serialization solely to make that claim. Socket generation and endpoint identity must remain the same across the pair, including during rebind/close races.

Deliver in order, entirely on the experimental branch:

1. Retain the approved timing/send policy and implement the reviewed bind readiness, generation ownership, JNI reference/exception teardown and configured-port recovery controls. Resolve independent ultra findings; shared UAPI/build controls belong to #66/#67.
2. Implement at the existing Android native bind/JNI owners and replace the Java warm-up once the replacement is complete. Keep the pinned handshake/crypto, config/persistence and kernel backend intact; add no mode selector or server change.
3. Run meaningful native failure/concurrency tests, ordinary-peer authenticated loopback traffic, Android JNI/native compilation and affected JVM/static checks. Update contracts, architecture decision and changelog with the actual implementation; obtain final-head ultra review and CI before reporting source verification.
4. Build/install an experimental APK only after an explicit request. Separately verify Android protection, startup/wake/rebind, standard-server interoperability and authorized cellular comparisons against the unchanged build. Freeze trial/rejection criteria before traffic tests; a compatibility/lifecycle regression or inconclusive benefit retains the baseline.

Local/source verification does not complete physical or DPI acceptance. Keep #61 open while its acceptance remains unmet, keep PR #63 draft and unmerged, and preserve personal configs, retained APKs and the G4 baseline throughout.

## Experiment gates

Before accepting implementation, verify the selected policy and reviewed lifecycle controls. Use the existing one-byte payload, explicit-port/endpoint-free compatibility and secret-safe diagnostics. Keep standard authenticated messages and config/persistence contracts intact.

Focused proof must cover protection before the first send and every rebind, both address families and unavailable-family handling, failed protection cleanup, explicit/absent/zero ports, endpoint-free profiles, selected packet order/cardinality, expected warm-up failure with ordinary initiation preserved, interruption/service destruction and close/reopen settlement. For the recommended policy, prove one attempt per eligible initiation, no extra sends for other message types and no separate sleep/retry worker; include retry/rekey and concurrent cookie/control sends. A real userspace loopback peer must complete the standard handshake and exchange authenticated traffic after discarding warm-up. Test pure native logic with the pinned Go toolchain and compile the Android JNI/native boundary without APK assembly; JVM tests alone cannot prove that boundary. Actual Android VPN protection, deep-sleep behavior and kernel-server interoperability remain separately authorized device/network proof.

DPI effectiveness needs repeated, authorized observations on a path where the classifier can see the relevant handshake. Before running, record the unchanged-build control, fixed configuration/path, trials, observed handshake/connectivity outcomes and rejection criteria. Reject a compatibility/lifecycle regression or a result that does not reproduce above baseline variation; successful local sends alone never pass the gate. An extra datagram may affect a classifier that only considers the first packet; subsequent ordinary WireGuard packets remain visible. Moving code or naming a message cannot bypass an existing IP block.

The completed [G4 network record](2026-10-07-g4-network-baselines.md) separates router-only versus nested Wi-Fi VPN trials from cellular direct/phone-VPN trials. Cellular is the relevant authorized path for future phone-handshake observations; those completed unchanged-build throughput measurements did not test this extension or establish a DPI cause/effect.

No experiment APK/install, live peer/protocol change or traffic trial follows from this proposal. Keep this branch separate from the measured baseline. Require independent ultra review, a meaningful described/assigned/labeled PR and actual checks before any authorized integration. Reject unproven benefit or broken compatibility/lifecycle behavior; retain no-change as an available outcome.

## Documentation impact and limits

The improvement plan links this separately requested investigation; the backend changelog records its proposal status. Existing architecture, operations, backend contracts, README, AGENTS.md, templates and UI roadmap need no change for this documentation-only slice: implemented behavior, module ownership and process remain intact. The consequential implemented transport decision and affected contracts must be updated with eventual code. Source review is not a packet/device/server/DPI run; #61 owns the unresolved timing/design and experiment acceptance, while #52 retains G4's unfinished physical/energy/optimization gates.
