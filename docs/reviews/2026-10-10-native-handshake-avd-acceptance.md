# Native handshake observations: private Android acceptance

Last updated: 2026-10-10 (Europe/Paris).

## Scope and provenance

This record covers the userspace observation slice in [PR #101](https://github.com/novakin/wireguard-android/pull/101), related to [#73](https://github.com/novakin/wireguard-android/issues/73). On 2026-10-10, read-only GitHub checks confirmed the published draft head `861a7cd89f13ec5676d2c8b5fe4af3102bd03c79`, base `master`, and both PR and issue OPEN. This partial scope uses a non-closing reference: recovery, presentation and the issue's broader acceptance remain unfinished. This record is evidence analysis, not final-head independent ultra approval, merge, release or full issue delivery.

The maintainer authorized a private synthetic AVD/peer session. Root executed installation and runtime actions; a separate scout inspected source, live tracking and the retained reader/state results. No phone, shared AVD, host/Android root or live VPS was used. Raw profiles, captures, object-read logs and host reports remain ignored under `.artifacts/runtime-acceptance-20261010/` and `.artifacts/codex-command-logs/`; this document contains scoped facts only.

| Boundary | Executed provenance |
| --- | --- |
| Android artifact | Debug APK from the source head above; SHA256 `641CAE679AD657894C93B37B6099F28B28EED78838B19E1E485D602879BC50E9`. Installed APK pullback has the same checksum. |
| Private Android target | API 36 Google APIs/x86_64 image revision 7; `Tunnl_Private_API36_20261010`, `emulator-5584`; package `com.wireguard.android.debug`. The final reader attached to current app PID 8775. |
| Independent standard peer | Rootless Windows/amd64 peer, unmodified `golang.zx2c4.com/wireguard` pin `v0.0.0-20250521234502-f333402bd9cb`, standard bind/netstack, Go 1.24.3; executable SHA256 `4AE9F2F94F3D64E6A20197CEA27D58FE7E1C8282D58F296E687889B8E6D36F34`. Standard UDP sockets use wildcard addresses; this is not a host-loopback-only listener. |
| Synthetic route and reachability | One peer, client `10.7.0.2/32`, IPv4 default route, numeric endpoint `10.0.2.2:51890`, keepalive 25 seconds, no DNS. App-UID HTTP to virtual-TUN endpoint `10.7.0.1:8080/health` returned HTTP200 and fixed `tunnl-native-peer` body. This is private peer reachability, not Internet reachability. |
| Reader | Standard JDI, four exact service/model entry breakpoints; selected primitive arguments and model fields only. No application methods were invoked. Reader class SHA256 `C32B11561B11C429412D183E325033D1C08F3CF4C2412D768DA4D41F070D2242`. |

The existing protocol overlay, [`GoBackend`](../../tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java), [`HandshakeSession`](../../tunnel/src/main/java/com/wireguard/android/backend/HandshakeSession.java) and [`ObservableTunnel`](../../ui/src/main/java/com/wireguard/android/model/ObservableTunnel.kt) remain the observation/activation owners. No recovery transport, binding adapter, UI consumer, resolver, dependency or product logging was added for this runtime proof. Source meanings and null/empty guards remain in [the contract](../backend-contracts.md#native-handshake-observations--73-draft).

## Callback, publication and retirement results

The final bounded reader log is `20261010-111709-031-4a239004d34d481eb4c5385878d88308.log`. Root reported command exit 0. Independent parsing found four armed breakpoints, 20 entry events, 42 model snapshots and normal `duration_limit` completion, with no error/read-error records. The controlled sequence was UP/private HTTP/DOWN/UP/private HTTP/DOWN on the same model and profile.

| Observation | Actual result and limit |
| --- | --- |
| Android native socket protection entry | Four `protectNativeSocket` entry calls captured real FDs: two before each activation's native initiation observations. This proves the JNI callback was invoked; no return value was read, so `protect()` returning true is not directly proved. Full-route private authenticated exchange complements the entry evidence. |
| Native protocol callbacks | Activations 1 and 2 each delivered event 1 ATTEMPT, event 2 SENT and event 4 AUTHENTICATED, retry false, for the same synthetic public peer and numeric IPv4 endpoint. Local SENT remains distinct from remote authentication and private HTTP. No runtime SEND_FAILED/EXHAUSTED result is claimed. |
| Model delivery/publication | Model identity 34 received both activations. Session 1 identity 35 and session 2 identity 133 were active and identical to their cached sessions. Five complete, untruncated map snapshots contained exactly one peer, with lastAttempt sequence 1, lastSendResult sequence 2 and lastAuthenticated/lastObservation sequence 3; lastExhausted remained null. |
| Reader budget | The first active snapshot explicitly reported `suspension_budget` and truncation at 256ms. Later complete active snapshots took 144–197ms. The truncated read is not complete map proof; the five later complete reads establish the stated single-peer facts. |
| Manual DOWN and replacement | Both session-change callback arguments showed the old session active=false. Thirty-six snapshots after the two DOWN transitions showed current/cached sessions null, diagnostics unavailable and cached map null. Reconnect used a different activation/session ID and published fresh facts. Root's retained HTTP outputs confirm private exchange after each UP. |

This actual Android run establishes callback delivery, successful single-peer map publication and observed manual retirement/replacement. It does not deliberately create a queued obsolete callback, close a TUN underneath Java, deny initial VPN consent, exhaust retries, inspect multiple Android peers, assert UAPI/FD destruction or prove native rebind. Existing deterministic model/backend/native close controls retain those exact synthetic scopes; they do not become new device results.

Earlier attempts remain failed instrumentation. The first reader armed four breakpoints but recorded nine generic read failures and no model snapshots. The diagnostic reader then exposed `InconsistentDebugInfoException`/JDWP35 `INVALID_SLOT` in standard JDI `getArgumentValues`, while 24 selected model snapshots succeeded. JDI's JVM argument-slot assumption mismatched ART's DEX registers. The final reader used exact visible-variable names and ART-provided slots; its successful primitive reads required no product source fix. Debugger suspension changes scheduling, handshake/teardown timing and power behavior. None of these attached runs establishes latency, natural retry timing, battery use or Doze behavior.

## Uninstrumented idle and packet boundaries

Root separately exercised explicit ListenPort 35000 and private app-UID HTTP200 without the debugger. An independent offline Ethernet/IPv4/IPv6 parse of one 235881-byte emulator capture snapshot found 687 complete records with no truncation, including background DNS/mDNS UDP but no UDP port 51890 or IPv4 traffic involving `10.0.2.2`. Valid framing and unrelated capture traffic do not establish coverage of this host-alias peer path. Packet cardinality, one-byte warm-up order/same socket and leak-protection proof are unavailable from this capture; zero peer packets is not a passing absence result. The growing capture's counts describe that snapshot only.

The first no-probe interval lasted 90.0213349 seconds. Its direct forced-idle snapshot had `mState=IDLE`, `mForceIdle=true` and `mCharging=false`, but **`mScreenOn=true`**. The debugger forward inventory was empty beforehand, and post-wake app-UID HTTP200 succeeded without reconnect. After cleanup, direct state showed `mForceIdle=false`, ACTIVE, screen on and charging; battery updates were not stopped. This remains limited forced-IDLE while screen on; it is not relabeled as a passing screen-off run.

Root corrected the setup and ran a separate uninterrupted **90.0084591-second** interval with temporary explicit ListenPort 35000. Both entry and end-of-hold direct idle state showed `mState=IDLE`, `mForceIdle=true`, `mScreenOn=false`, `mCharging=false`; both power snapshots showed `mWakefulness=Asleep`. No debugger or operator traffic probe ran during the hold. After unforce/wake, app-UID HTTP200 and the private body succeeded without reconnect. Cleanup readback showed ACTIVE, screen on, charging, `mForceIdle=false`, no stopped battery updates and restored stay-on value 1. Final native detail readback showed absent ListenPort (`—`), and Home's switch was unchecked after manual DOWN. The retained `101-screenoff-doze-*` records own this corrected run; the original run remains separate.

No session map was read during the uninterrupted idle interval. Private traffic after wake cannot by itself prove retained session identity, rebind or a new recovery implementation. AVD forced idle is not physical hardware suspend, and natural keepalive/protocol traffic is distinct from operator probes.

## Remaining acceptance and verdict

Actual observations/protection-entry/manual retirement/replacement and corrected forced screen-off idle/private wake reachability pass their stated private single-peer IPv4 scope. Packet coverage/cardinality remains unavailable. Broader physical suspend, Wi-Fi/cellular handover/rebind, hostname refresh/bootstrap DNS, IPv6-only paths/fallback, kernel-backend observations, unchanged kernel-server interoperability, comprehensive always-on/lockdown leaks, DPI, throughput and battery are unproved. The independent unchanged userspace peer proves only its pinned userspace interoperability boundary.

PR #101 contains no recovery or presentation implementation. Keep #73 open and keep #61/#75/#52's wider gates separate. A merge verdict still requires the final published source/documentation head, independent ultra analysis with these results/limits, required CI and root's authorized publication/merge/readback. Historical source-refresh reviews and CI retain their original scope; they are not runtime acceptance or another GitHub account's approval.

Documentation changes link this record from the owning contracts, architecture, operations, QA, development, changelog and E5 plan. Historical 2026-10-09 source records remain unchanged. No UI roadmap update is needed: product UI behavior and presentation are unchanged. No runtime operation was repeated merely to edit prose.
