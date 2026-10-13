# Bounded automatic recovery — Android acceptance

Last updated: 2026-10-13 (Europe/Paris).

## Scope and status

The original explicit/omitted acceptance checkpoint pinned the [#73](https://github.com/novakin/vpn0wn-android/issues/73) native candidate [PR #162](https://github.com/novakin/vpn0wn-android/pull/162) at `e7dd03754548879f7097eb874439c8c1f07d67ce` and the controller candidate [PR #164](https://github.com/novakin/vpn0wn-android/pull/164) at `e383ff39bdfc8ea55e6f7abf67330967e299e067`. Both were draft/unmerged at that historical checkpoint. Actual explicit-port and omitted-port execution, settlement and sampled cleanup passed independent ultra adjudication; complete physical controller-budget proof remained pending.

The native primitive subsequently merged through PR #162 at `b3640ebabe046fcdf17004b1514a9f1287ab78d2`. Controller production owners match documentation composition `5993b39c6ad900a410eaeeb7708679bdd8d523dd`; this adds no new APK execution. Fresh corrected two-attempt budget execution and four live TUN identity samples now pass independent ultra adjudication. At this 2026-10-13 checkpoint, final controller-head review/CI and integration delivery remain pending under PR #164. This record separates isolated runtime proof, source review and shipping delivery; it does not close #73.

## Artifact and environment

The full production app was built from integrated source `0d85fdf58171a532bd051be26bb2621c45d43a1f`, with production owners unchanged at the candidate heads above. Test-only application identity `local.vpn0wn.recoveryprobe.debug`, label and native package path isolate it from personal installations. Original Application, TunnelManager, ObservableTunnel, GoBackend, JNI and native device/bind owners are retained. Artifact inspection verifies minSdk 24/targetSdk 36, four native ABIs and signature/package-path ownership; the physical trial runs arm64 on rooted Pixel 7 Pro/API 34.

| Input | SHA256 |
| --- | --- |
| Installed APK | `eaf9a4fed57328d9c00b5e604a843cdce88ebd4df4c5cdd5d581ec1fe6c8b596` |
| Arm64 native library | `b94fa7582bb39df11f8821982fc218424f3863ab18aa71e6ce377c2c10ad5023` |
| Signing certificate | `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580` |

Fresh synthetic profiles use IPv4 loopback outer transport and a separate network-namespace standard WireGuard kernel peer on the same phone. Only the disposable app and its SDK sandbox UID ranges enter the Android VPN. Profiles have numeric endpoints, a bounded synthetic inner destination, no DNS/default route/keepalive and fresh synthetic credentials. Captured guards and preservation samples show the personal kernel VPN remains UP; continuous health is not established. This is same-phone interoperability; no independent Internet or DPI path is exercised.

## Verified explicit-port execution

The observer attaches after activation and native consent. One expected inner HTTP timeout against a silent peer triggers ordinary WireGuard retries. No synthetic callback, direct recovery invocation or forced protocol timer substitutes for natural native EXHAUSTED, manager admission and automatic backend/JNI entry. A finite 35-second test-only hold at the first automatic backend entry allows fixture setup before JNI. This controlled scheduling establishes no production recovery-latency measurement. An earlier 20-second hold proved too short for host fixture setup; that failed evidence remains preserved.

The fresh explicit35000 trial observes native exhaustion attributed to the current session/peer, matching manager admission, conservative charge 0→1 and actual automatic `GoBackend.recover`. JNI returns 5 and Java reports `INITIATION_STARTED` with current/reusable context. Both new socket protection wrappers return true. Native handle, model/config, backend, service, activation/session and Android TUN identities remain retained; UDP socket inodes change and runtime port remains 35000. Controller settlement reports one confirmed admission/charge/backend entry, zero unknown settlements and no pending request.

The frozen packet parser validates two outgoing initiation packets, each preceded by the explicit-only one-byte warm-up from the preserved source port. A fresh standard kernel handshake, bidirectional counter deltas and exact synthetic HTTP 200/body establish authenticated traffic within this loopback scope. Later authentication and traffic do not establish recovery causation or socket-generation-specific authentication.

## Verified omitted-port execution

Fresh V6 run `c43478c1c85345a8ae34a75048345ef0` exercises the same isolated production-equivalent APK, controller/native owners and loopback prototype with ListenPort omitted. Its 193-second execution passes natural exhaustion/automatic recovery, native result 5, two true protection returns and retained activation/session/TUN/config. Both UDP socket inodes change while the same automatically selected port 38795 is retained. Two captured outgoing initiations have zero warm-ups; fresh standard-kernel handshake/counter evidence and exact synthetic HTTP 200/body pass. This uses the same finite entry hold and establishes no natural recovery-latency measurement.

Independent ultra receipt `54af8f7b9be7764c3b97c2c1142184c8a64670423ab959b852aaef9cd39a126d` passes exact V6 artifact admission, pulled-APK identity, the pure checker over actual joined observer/forward settlement and independently replayed raw cleanup. One actual automatic entry, JNI result 5 and retained-session/socket observations are confirmed. The observer's 33 exact DDMS 199/1 diagnostics pass the same bounded reviewed classification; raw diagnostics remain preserved. This is actual V6 execution, separate from the explicit run's V5 adjudication.

| Trial | Runtime port before → after | Initiations / warm-ups | Protection / native outcome | Acceptance at this checkpoint |
| --- | --- | --- | --- | --- |
| Explicit `ListenPort=35000`, actual V5 execution | 35000 → 35000 | 2 / 2 | 2 true / 5 (`INITIATION_STARTED`) | Corrected V6 adjudication and sampled cleanup independently PASS |
| Omitted ListenPort, fresh V6 execution | 38795 → 38795 | 2 / 0 | 2 true / 5 (`INITIATION_STARTED`) | Actual V6 execution and sampled cleanup independently PASS |

Both rows describe the same-phone synthetic IPv4 kernel-peer prototype, not a shipping-package or independent external-server trial. Literal `ListenPort=0` is not separately exercised here; source and older zero-port evidence retain their original scope.

## Checker correction and independent review

The successful Android execution used V5 manifest `e622820845dd40618d4da4bb70a6bef054048a9f78852510e0b9062462d9276d`. Its original checker rejected legitimate trailing CRLF separators in debugger-forward readback. Correcting that boundary exposed an empty-stderr assumption against 35 exact JDI diagnostics. The final V6 checker `d55770652a667e8e8a737ca594653e08d3d2ac076ea33d6fba66560738a06bfc` normalizes CRLF pairs and terminal LF only, rejects remaining bare CR and malformed/interior records, and admits only bounded exact command 199/1 diagnostic lines. [Android 14 ART](https://android.googlesource.com/platform/art/+/refs/tags/android-14.0.0_r1/adbconnection/adbconnection.cc) defines 199/1 as DDMS chunk notifications; installed-JDK dispatch handles [ordinary method events](https://docs.oracle.com/en/java/javase/21/docs/specs/jdwp/jdwp-protocol.html#JDWP_Event) through command set 64 (Event) and prints/ignores those separate notifications without throwing. Exact OEM behavior and the chunks' types/payloads remain unverified. Other stderr, malformed records, failed settlement and nonzero exit still reject.

Independent ultra review of final V6 manifest `2bcf95364ed390069ae887ac76afcb7854b4e9882fd98be52a25ecc9271eb1f3` passes the corrected checker over the immutable V5 execution and separately reviewed raw cleanup. Retained host parser/admission controls pass within their reviewed scope. Driver, observer/classes, APK, production owners and recovery/identity/protection/packet/cleanup guards were unchanged by this checker correction. The original rejection, raw 35-line stderr, intermediate rejection and all device receipts remain preserved. This is a host-only adjudication of actual V5 evidence; the Android run is not relabeled V6.

## Cleanup and retained failures

For the explicit run, genuine exact-owned Android VPN Settings Disconnect restores the original prepared owner. Native deletion removes only the two synthetic profiles before checked uninstall. All 12 captured preservation comparisons pass, errors are empty and the disposable package/data/paths are absent. Personal applications/configuration/settings/policy/task, original kernel state and normalized routing/network match baseline. Independent replay adds 21 checks over 41 joined raw commands and 123 pinned files, including observer settlement and exact debugger-forward removal. Complete raw outputs remain private; normalization excludes only documented naturally aging IPv6 lifetime/expiry tokens. Sampled preservation does not establish continuous device health, and inactive Android cached capability/history/allocation counters can differ.

The omitted run's actual `cleanup-20261013T084226310811Z.json` reports all 12 comparisons true, no errors and restoration true, including original settings/personal files/packages/policies/locales/prepared owner/network/size/density/foreground and absence of owned package/data/paths. Its separate independent raw cleanup replay passes 21 checks over 41 joined commands and 123 pinned files, including original kernel/routing preservation and exact owned observer/forward/resource removal. The two runs retain their own receipts and sampled-preservation limits.

The earlier observer-enumeration failure and 20-second setup timeout remain failed trials with unknown recovery outcomes after observer detach. Successful cleanup does not prove recovery. The successful V5 execution retains its original checker rejection; its driver, raw receipts and cleanup were not rewritten for the host-only correction.

## Port ownership and failure boundary

Warm-up and handshake initiation reuse the active protected WireGuard UDP socket for the relevant address family. They do not acquire a separate local port. If another socket owns the requested/current port during replacement `Bind.Open`, opening fails and recovery reports `TRANSPORT_FAILED` before initiation. The failed generation closes, the TUN/device remain retained and no automatic fallback port is chosen. A failed refresh may require manual reconnect. An expected warm-up I/O failure on an otherwise usable current socket is best-effort and can still be followed by initiation; closure, cancellation, obsolete generation and programming defects retain their failure behavior. The [recovery contract](../backend-contracts.md#expected-session-exhausted-cycle-recovery-primitive--73) owns these distinctions; no physical contention injection is claimed here.

## Remaining acceptance

The native primitive's scoped explicit/omitted Android core and sampled-cleanup gate is met. The fresh execution below also meets the scoped two-attempt controller budget gate with four live TUN identity samples. Literal-zero and broader physical/controller controls retain their separate scopes; full #73 acceptance remains open.

### Earlier budget trial — partial evidence

The independently reviewed budget fixture (`bf801c3c1070787cf0de53a51836c163dab84e6e6901820fb7f722152d8ba738`) and its 63 host controls were followed by unheld physical trial `1d9a0a84e9234621a398a988504a5860`. Three natural exhaustion episodes produced exactly two automatic backend/JNI entries, four true socket-protection returns and two socket replacements. Actual backend entries were 113303ms apart. The third episode settled as `DROPPED_ATTEMPT_LIMIT` without a third backend entry. This supplies scoped accounting/socket evidence, not complete budget acceptance for that run.

Budget TUN retention remains unverified for that earlier run. Its FD snapshots copied the admitted interface index rather than sampling the live index. An independent negative control changed every cached snapshot index consistently and still passed. A live read later found a different interface index with the same name and unchanged app process epoch. The retained commands bound the unexplained change to 08:54:52–09:05:02 UTC; they do not establish whether it occurred during recovery or after observer settlement. The maintainer reported no phone or other-chat input. Retained filtered app/system history supplies no recreation cause. No production defect or continuous retained-TUN result is inferred from this evidence gap.

The original budget cleanup refused the changed TUN and remains preserved as failed. A separately reviewed one-shot operator identified the current disposable package, UID, process epoch, profiles, exclusive VPN ranges and synthetic TUN before both exact native Disconnect taps; it did not rewrite historical ownership. Native Disconnect restored the original prepared owner with all TUNs absent. A fresh unchanged ordinary cleanup then passed all 12 preservation comparisons with no errors and removed the owned installation/resources. Independent raw replay passed 21 checks over 41 joined commands and 123 pinned files. This establishes sampled restoration, not the trial's missing TUN retention. At that checkpoint, a newly frozen live-index checker and controlled rerun were required before the scoped physical budget gate could pass; the fresh run below does not retroactively repair the earlier trial.

### Fresh budget execution — live TUN samples

Fresh explicit-port run `3cca8dc6cc4943a5813c4137d4020d0f` uses frozen live-index recipe `7e8aa1398a0d928559a03c20d38951b4d67a6a40560b758c346217a6f964fe4f` and the unchanged isolated production-equivalent APK. Install request `00076` timed out with an unknown remote outcome and was not retried. Exact package, UID and pulled-APK hash readback reconciled installation before fresh preparation within the admitted window.

Ordinary inner traffic against an intentionally silent synthetic endpoint produces three natural exhaustion episodes without a backend-entry hold or forced protocol timer. Exactly two automatic backend/JNI entries produce four true socket-protection returns and two socket replacements. Actual backend-body entries are 114410ms apart; the third episode settles as `DROPPED_ATTEMPT_LIMIT` without a third entry. Runtime ListenPort remains 35000. This establishes scoped natural exhaustion, automatic admission/accounting and third-attempt refusal; the silent-endpoint budget run supplies no authenticated-traffic result. Entry spacing exceeds the selected 30-second minimum but does not physically test exact early-trigger cooldown rejection or measure optimum defaults/recovery latency. Physical BUSY deferral, the four-entry ceiling and cancellation accounting remain separate controls.

Immutable activation admission and four separate live `ip -o link show type tun` reads agree on synthetic TUN index 105/name `tun0` at before, after attempt one, after attempt two and final FD boundaries. Each read is bracketed by matching process epochs and bound to its own joined raw command and owning FD snapshot. These are four sampled identity checks, not continuous TUN retention or a cause inference between samples.

Independent ultra receipt `4f12f8536b681c6fac3186d30770490e5da9c7a87200d220909eefcdbddf9633` passes 152 checks over 96 replayed raw commands and 381 pinned evidence files, confirming actual execution, observer/forward settlement and raw cleanup within that scope. Genuine owned Android Disconnect and ordinary cleanup `cleanup-20261013T105733946743Z.json` (`4915ffe91e446c5bbe8fb96a615a09aa39ef21882b1dfa2b86cead62b6c7693c`) restore the captured owner, remove the disposable installation/resources and report all 12 preservation comparisons true with no errors. Sampled preservation establishes no continuous personal-device health.

### Remaining controller and path controls

Physical cancellation/destruction during automatic work, actual OS protection denial, Doze/hardware suspend, underlying-network handover, IPv6/multiple-peer/hostname recovery, bootstrap policy and automation arbitration remain separate. No shipping-package, Internet-health, DPI, throughput or battery gain is established. [QA](../qa-test-plan.md#proposed-recovery-controls--73) retains broader gates; PR #164 owns final controller-head review/checks and delivery.
