# AmneziaWG native lifecycle and traffic acceptance — #76

Last updated: 2026-10-21 (Europe/Paris).

## Scope and status

**Scoped PASS: twelve native stages and all nine host cleanup/preservation checks in one isolated API24 x86_64 A4 cohort.** Independent ultra review, including a separate host-evidence review, is satisfied within this scope, with no actionable findings or material acceptance gaps. At this recorded A4 checkpoint, [PR #302](https://github.com/novakin/vpn0wn-android/pull/302) was draft and unmerged; its acceptance record owns subsequent source/documentation review, checks and delivery. [#76](https://github.com/novakin/vpn0wn-android/issues/76) and parent [#72](https://github.com/novakin/vpn0wn-android/issues/72) remain open.

This document records existing evidence; it adds no product changes or runtime execution. The [configuration/native ownership contract](../backend-contracts.md#optional-amneziawg-configuration-and-native-ownership--76) and [QA record](../qa-test-plan.md#optional-amneziawg-controls--76) retain the separate source, host handshake/traffic, artifact and broader Android acceptance boundaries.

The later [A2 retry1 traffic cohort](#api24-a2-retry1-traffic--2026-10-19) is separately accepted below. The A4 source, artifact, observations, failures and limits retain their original scope.

The separate [API36 A2 traffic cohort](#api36-a2-traffic--2026-10-20) extends the local platform matrix below; it does not widen either earlier cohort. The later [padding/header/rebind cohort](2026-10-21-amneziawg-padding-rebind-acceptance.md) adds a separate configured nonzero-field and rebind traffic result; the limits below retain their original cohort scope.

## Tested source and artifact

| Binding | Tested identity |
| --- | --- |
| Product source | `2300a1f458e212da81fcd3cc5f167566fb70b8a9` |
| Isolated APK size | 19,733,887 bytes |
| Produced, installed and cleanup-read APK SHA256 | `0ffba2a67eeccad86fabce9f5e59e0557e871074fbe000d8e1bf99c653b243f8` |
| Package / platform | `local.vpn0wn.awg76probe.debug`; Android API24, x86_64 |
| Runtime attempt | `run-20261019-070106-be5d4a` (A4) |
| Executed host runner SHA256 | `0b28fc3ca2c62c51687af75431b7ad984ed677484582cc4dbca3e2d9c01a3265` |

The probe runs the production Application, TunnelManager, GoBackend, VpnService and combined native engine under an ordinary app UID. Its overlay adds instrumentation/consent entry points, an isolated application ID and separate build outputs. Independent artifact review checked the manifest, DEX owners, four x86_64 libraries, both native engines and source-exact notices. Probe and shipping APK/native bytes differ; shipping-APK byte equivalence and release acceptance are not claimed. Source CI [run 39210600806](https://github.com/novakin/vpn0wn-android/actions/runs/39210600806) passed at the tested source revision; it does not establish this separate runtime cohort or later documentation-head acceptance.

Actual Android VPN consent preceded native exercise. Synthetic profiles use generated private keys, a loopback endpoint, a single `/32` route and an app inclusion restricted to the probe, without DNS or persistent keepalive. No listening peer or traffic producer was present. Raw private profiles, UAPI output and host inventories remain outside source distribution.

## Native observations

| Case | Passed stages |
| --- | --- |
| AmneziaWG, omitted ListenPort | UP, retained-session rebind, DOWN |
| AmneziaWG, explicit previously observed port | UP, retained-session rebind, DOWN |
| Ordinary WireGuard, omitted ListenPort | UP, retained-session rebind, DOWN |
| Ordinary WireGuard, explicit previously observed port | UP, retained-session rebind, DOWN |

The AWG cases set `Jc = 1` and require native `jc=1` readback; the WireGuard controls require that marker absent. This distinguishes the selected engine through actual native configuration, rather than its version string alone.

Every rebind preserves the native handle, exact service instance, capture session, TUN descriptors, effective port and saved configuration bytes. Transport generation increases and both observed IPv4/IPv6 socket inodes change. These are sampled identity and configuration assertions; they do not establish uninterrupted traffic or every intermediate routing property.

Each DOWN retires the captured native handle, releases the exact service and removes the owned TUN descriptors, UDP sockets and running marker. A new IPv4 bind proves the effective port immediately reusable without sending data. Saved configuration bytes remain unchanged. Synthetic profiles are removed before native completion. IPv6 was observed in this cohort; the immediate port-reuse control is IPv4 only.

## Host completion and preservation

The private native result reports `NATIVE_PASS_HOST_RESTORATION_REQUIRED` and `cleanupComplete=true`. The host separately accepts the instrumentation command/result and all nine postconditions: probe package absent, process absent, service absent, UID-owned UDP sockets absent, sampled baseline restored, owned guest stopped, console/buddy ports released, shared owners preserved and source/inputs unchanged. Final `acceptance.json` records `nativePassed=true`, `hostCommandPassed=true`, `passed=true` and no failure phase/class.

Before native admission and again before uninstall, `exec-out run-as` reads the installed APK as the probe UID into a binary host file. Both copies match the bound producer hash. Cleanup then confirms uninstall and compares the package/interface/boot/forward/reverse/always-on/lockdown baseline. Independent review verifies the native/private/terminal payload equality, successful instrumentation terminal record and the underlying host receipts. A separate later host readback at 07:06:29 UTC corroborates owned-process absence, released ports and preserved shared-owner identity; it is distinct from execution-time cleanup. This document performs no fresh device observation.

## Retained failed attempts

| Attempt | Original failure and cleanup boundary |
| --- | --- |
| A1 — `run-20261019-064124-a6c8bc` | Failed before installation when `ls -1 /sys/class/net` returned permission errors and no inventory. Guest shutdown, port release, shared-owner preservation and unchanged inputs passed; package/native acceptance was not reached. |
| A2 — `run-20261019-064741-254bc4` | Failed before installation when the listed guest's boot-complete shell exited 255 with empty output. The same four host preservation checks passed; native acceptance was not reached. |
| A3 — `run-20261019-065326-bd59cb` | Probe installation succeeded, but `adb pull` could not read the `pm path` APK. The same read failure blocked byte-verified uninstall. Process/service absence and the four host preservation checks passed, including owned guest shutdown. Package removal, UID-owned UDP absence and baseline restoration remain unaccepted. No consent or native stages ran. |

A4 reused the same compiled probe. Reviewed host corrections use shell-expanded interface names, retain the existing boot deadline while treating exit 255 as not ready, and stream installed APK bytes through the ordinary probe UID. Earlier attempts remain failed; A4 does not retroactively accept their cleanup. The earlier Windows-worktree build-guard failure is also retained separately from the successful producer.

## Evidence and remaining gates

Evidence stays under ignored `.artifacts/continuation-20261018/native-awg-lifecycle-prep-v1/runs/run-20261019-070106-be5d4a/`, with earlier attempts and their bound inputs preserved. The separately preserved independent report is `.artifacts/continuation-20261018/native-awg-lifecycle-runtime-ultra-review-a4.md`, SHA256 `f2292c0c06fd9f1cb7f143d3b6bfe753480dbed2edbb2071ae44092facd94120`.

| Retained A4 record | SHA256 |
| --- | --- |
| `acceptance.json` | `3b9674815656b2da464b0676e6d93eb60aa97ef71cebad45e98346b84816867b` |
| `native-private.json` | `c378b32e67a551134e91c881b16ebd062e2622da5098b17a22b96a9d2eb2dadd` |
| `cleanup.json` | `99e76f3118de7fad78f6e83d31c552ed1d5f78d8a2777978b89be5fa740010ce` |

`trafficTested`, `protectionObserved` and `dpiTested` are false. This cohort does not verify Android handshake/data traffic, observed socket-protection returns, selected-Network hostname/lockdown behavior, recovery triggers, cancellation during native calls, external-server interoperability, physical devices, other ABIs/platforms, installed IME/TalkBack, throughput, energy or DPI efficacy. Separate host synthetic traffic controls retain their own scope. At that checkpoint, broader distribution obligations and final PR review/checks remained required. The PR acceptance record owns subsequent source delivery; this runtime record alone establishes no merge, release or live-server deployment.

## API24 A2 retry1 traffic — 2026-10-19

**Scoped PASS: nine native stages, exact application traffic in both matching phases, wrong-H1 refusal and all eleven host cleanup/preservation checks.** Independent ultra runtime review, with a separate cleanup review, accepts this exact API24 x86_64 cohort with no actionable blocker. #76 and parent #72 remain open for their wider acceptance. This section records retained execution evidence; documenting it performs no new runtime test.

### Traffic source and artifact bindings

| Binding | Tested identity |
| --- | --- |
| Product source | `ad03dda6ad0023b151ec42f856efb755076b89bb` |
| Product tree | `a78485cab54b89cc69b412496215aed2f8a19d7e` |
| A2 isolated APK | 19,751,811 bytes; SHA256 `82b1f094d7da16d0c6e6306a77cdda76adbb2ea7397523c1d0248203b7fd91fa` |
| Package / platform | `local.vpn0wn.awg76trafficprobe.debug`; Android API24, x86_64 |
| B3 peer | Windows AMD64; SHA256 `f9b34ba8fd5ccbcafa2c4d3d1d4784e35cb1135c81aee45707d6d44473be07bd` |
| Peer engine | Unmodified official `github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828` |
| Runtime attempt | `run-20261019-164044-bcb1ad` (A2 retry1); actual exit 0, settled at `2026-10-19T16:44:27.1105904Z` |

The probe uses the production Application, TunnelManager, GoBackend, VpnService and reviewed combined native engine under a separate ordinary app UID. Actual system VPN consent preceded activation. Independent APK review checked manifest/DEX owners, the corrected bounded HTTP reader, signer, alignment, native provenance and source-exact notices. Both installed-copy readbacks equal the audited A2 APK. The independently audited B3 peer uses the official engine without the Android product overlay. Pinned source/byte checks do not establish checksum-database authentication during its dependency preparation. This isolated debug APK does not establish shipping-artifact equivalence or release acceptance.

The fixed synthetic matrix uses H1/H2/H3/H4 `101/102/103/104`, S1/S2/S3 `12`, S4 `0`, I1 `<b aabb>`, Jc `2`, Jmin/Jmax `8` and MTU `1280`. Only client H1 changes to `201` in the negative phase. Header protection is absent; cookies retain their enabled default. The probe-only route is `10.7.0.1/32`, client address `10.7.0.2/32` and numeric outer endpoint `10.0.2.2:51892`. No DNS, default route, persistent keepalive, explicit client ListenPort, recovery policy or automation is configured. Peer HTTP exists inside its virtual netstack; the admitted host UDP listener is wildcard. S4 `0` leaves nonzero transport-padding behavior outside this cohort.

### Traffic observations and cleanup

| Fresh activation | H1 | Handshake | Native RX / TX bytes | Application result |
| --- | --- | --- | --- | --- |
| Matching before | 101 | Fresh authenticated handshake | 556 / 626 | Exact framed challenge response; matching peer request |
| Wrong H1 | 201 | None; timestamp 0 | 0 / 356 | Bounded socket timeout; no peer HTTP request |
| Matching after | 101 | Fresh authenticated handshake | 556 / 626 | Exact framed challenge response; matching peer request |

Private, terminal-private and instrumentation results agree exactly. Each activation passes UP, traffic observation and DOWN, totaling nine ordered native stages. Native counters include protocol packets. The negative phase's TX increase from zero proves an actual send attempt while handshake and RX remain zero. The two exact HTTP responses and corresponding peer requests establish application traffic; the final matching phase checks that the path works again after changing H1 back. It uses a fresh activation and adds no recovery-trigger acceptance.

Every DOWN verifies exact owner/service/native-handle/TUN/socket retirement, unchanged saved bytes and app-side IPv4 port rebinding before the next activation. The peer records exactly two requests in phase order, matching the client's per-run challenge, then a normal owned stop with exit 0. Raw keys, challenge values, private configs, UAPI and host inventories remain outside source distribution.

All 110 command receipts are settled and output-drained. The traffic-evidence checker exits 0; native, host-command and aggregate acceptance are true. All eleven cleanup checks pass: peer stop and peer UDP release; package, process, service and UID-owned UDP absence; baseline restoration; owned guest stop and console/ADB port release; shared-owner preservation; unchanged source/inputs. Before/after baselines match. Separate later host absence readback corroborates execution-time cleanup. The exact executed runner, eleven consumed inputs, clean product source and initialized gitlinks remain bound to retained authority.

`cleanup-errors.json` is absent in this successful run. The reviewed receipt helper emits no file for an empty piped collection, while the aggregate success guard requires a zero cleanup-error count. Independent review reconciles that behavior with the actual acceptance and underlying cleanup receipts; no empty file was added to the original evidence.

### Retained traffic failures

These traffic labels are separate from the earlier A1–A4 lifecycle attempts above.

| Attempt | Original result and retained boundary |
| --- | --- |
| Traffic A1 — `run-20261019-161212-6804bd` | First matching HTTP response read timed out; wrong-H1 and final matching phases were not reached. A peer request establishes request arrival, but the exact client response remained unproved. Original guest-stop and port-release flags are false; later host absence is separate evidence. The attempt remains failed. |
| Traffic A2 — `run-20261019-163514-e304ff` | `getprop ro.kernel.qemu` exited 255 with empty output before install, consent, probe or peer admission. All 41 commands settled. Four applicable host preservation checks passed; the seven unentered app/peer/baseline flags remain false. The attempt remains failed. |

The new A2 APK contains the bounded Content-Length reader; its 25 host framing controls retain their offline scope. Retry1 reuses that audited APK and B3 peer with the separately admitted supported emulator port pair. Corrected framing and the supported pair were both present in the successful run. Neither earlier failure cause is proved retrospectively, including any relationship between exit 255 and the earlier port warning. Original artifacts, authority, results and reviews remain retained.

### Traffic evidence and remaining limits

Evidence stays under ignored `.artifacts/continuation-20261019-awg-next/`; the successful run is under `runs/run-20261019-164044-bcb1ad/`. No raw runtime report is added to source distribution.

| Retained traffic record, relative to that evidence root | SHA256 |
| --- | --- |
| `android-a2-retry1-runtime-ultra-review.md` | `4ae04a5c26c5f343593dd636d0ffd4af71024b75153f684421a3788b05fecfc6` |
| `android-a2-retry1-runtime-audit-ultra.json` | `0b234944a2bce67c85107ec8912a12605558fbadcedace204072e48adc1e0af7` |
| `runs/run-20261019-164044-bcb1ad/acceptance.json` | `d9dde8a0050bf95cbdf8fa52a2d6e6d5d8a99d72e150a71a0f9432df14577f3c` |
| `runs/run-20261019-164044-bcb1ad/cleanup.json` | `5be1691eda9c386ac659831f6795be815a7b61198b5addcade09c233a92c33ae` |

This proves only the named local Android/official-peer traffic and wrong-H1 matrix. Physical/external-server interoperability, network loss, rebind traffic, selected-Network hostname/lockdown behavior, observed protection returns/timing, recovery triggers, cancellation, nonzero S4/header protection, all field combinations, other ABIs/platforms, installed UI/accessibility, cross-app/release delivery, distribution obligations, DPI, performance and battery remain open. All execution gates are closed. The documentation PR owns later review/checks and publication; this cohort itself establishes no release or live-server deployment.

## API36 A2 traffic — 2026-10-20

The fresh API36 x86_64 A2 cohort passes the same bounded matching/wrong-H1/matching traffic matrix through production Application, TunnelManager, GoBackend and VpnService. Nine native stages and all eleven host cleanup/preservation checks pass. Independent ultra runtime review, including separate consent/command/cleanup review, passes without unresolved findings. No product behavior changed; #76 and parent #72 remain open.

### API36 source, artifact and observer

| Binding | Tested identity |
| --- | --- |
| Product source / tree | `ad03dda6ad0023b151ec42f856efb755076b89bb` / `a78485cab54b89cc69b412496215aed2f8a19d7e` |
| A2 isolated APK | 19,751,811 bytes; SHA256 `314e18ba9a1207b211673f92c9e72b69334474a42c2908ba06594f0c30c97928` |
| Package / platform | `local.vpn0wn.awg76trafficprobe.debug`; Android 16/API36, x86_64; minimum API24 retained |
| Peer | Same independently audited official v3.1.20260828 B3 Windows peer and binary recorded in the API24 traffic cohort |
| Runtime attempt | `run-20261020-210935-f5cd90`; controller exit 0 in 259,615 ms |

The frozen product source is unchanged from the accepted API24 traffic cohort. Against integration base `0de597c8`, its only non-Markdown differences are the unrelated retained-profile export MIME correction and regression test. This is isolated-probe compatibility evidence, not a test of the shipping APK or every change on current master. The peer, fixed field values, app-only route and numeric outer endpoint are unchanged; no DNS, default route, keepalive, explicit client ListenPort, automation or recovery policy was added.

Android 10+ [restricts app access to `/proc/net`](https://developer.android.com/about/versions/10/privacy/changes#proc-net-filesystem). The API36 probe therefore uses the public API34+ [`UiAutomation.executeShellCommandRwe`](https://developer.android.com/reference/android/app/UiAutomation#executeShellCommandRwe(java.lang.String)) instrumentation API for the fixed read-only commands `cat /proc/net/udp` and `cat /proc/net/udp6`. It requires empty stderr and recognized, valid tables, then matches the actual UAPI port against its own socket-descriptor inodes, ordinary app UID and unique family entries, including required IPv4. Every returned descriptor closes. This is a shell observer; product execution retains its ordinary UID, without root or adopted shell permission identity. The API supplies no child exit status, so none is claimed. Source ownership and public-SDK checks do not substitute for the actual runtime observations below.

The corrected build and static APK auditor both exit 0. Independent ultra artifact review verifies compiled observer/API guards, manifest owners, signer, alignment, source-exact notices and packaged native provenance. Compared with accepted API24 A2, only `classes11.dex` and `libwg-go.so` differ in the ZIP. Native executable sections match exactly; generated sources match in 242 of 244 files, with only build-output paths differing in `go.mod` and `overlay.json`. Remaining native differences resolve to build-path metadata, including module-info string length 1006 to 1008. No native behavior change is inferred from those byte differences.

### API36 observations and cleanup

Actual Android system consent identified the isolated package; the observed positive control was tapped without pregrant or injected results. Installed APK copies before execution and during cleanup match the audited APK. The production application runs under an ordinary app UID.

| Fresh activation | H1 | Handshake | Native RX / TX bytes | Application result |
| --- | --- | --- | --- | --- |
| Matching before | 101 | Fresh authenticated handshake | 556 / 722 | Exact framed response; matching peer request |
| Wrong H1 | 201 | None; timestamp 0 | 0 / 356 | Bounded socket timeout; no peer HTTP request |
| Matching after | 101 | Fresh authenticated handshake | 652 / 722 | Exact framed response; matching peer request |

Native counters include protocol packets and are not throughput measurements. Each activation passes UP, traffic observation and DOWN. Every DOWN confirms native handle retirement, exact service/TUN/socket release, unchanged saved bytes and immediate app-side IPv4 port reuse. Returning H1 to 101 starts a fresh activation; it does not exercise automatic recovery or rebind traffic.

The private and terminal results agree. All 103 controller command receipts are settled and output-drained; the traffic-evidence checker exits 0. All eleven cleanup facts pass: peer stop and UDP release; package/process/service/UID-owned UDP absence; baseline restoration; owned emulator stop and port release; shared-owner preservation; unchanged source/inputs. The separate observed consent action is retained with its XML and actual command result. Later host readback corroborates released target ports and unchanged shared ADB. Personal phones and live VPS systems were not used.

Baseline equality covers the seven recorded fields. Shared-process creation/path preservation is supported by the reviewed controller's inline checks and cleanup receipts; a separate full after-process inventory was not recorded. The 103 drained receipts cover controller commands, not the separately redirected emulator/peer streams or manual consent action. The peer's normal stop and exit 0 have separate evidence.

### API36 failures, evidence and remaining limits

The inherited app-side UDP-table reader was rejected before execution on the newer platform. The first replacement used `Os.getsockoptInt`, which is absent from the public Android SDK: API36 A1 compilation failed with exit 1 and emitted no accepted APK. A premature root exit-0 record was incorrect; the subsequently invoked auditor rejected the missing APK. The invalid record, actual exit-1 correction and failed audit remain retained. A2 replaces only the test observer and uses separate successful build/artifact/runtime evidence; it does not reclassify A1. The first A2 artifact-binding guard expected a generic status string and rejected the auditor's valid `STATIC_APK_CHECKS_PASS`; correcting the guard admitted the already audited bytes. No product fix resulted from these harness corrections.

Evidence remains under ignored `.artifacts/continuation-20261020-awg-api36/`, with this run under `runs/run-20261020-210935-f5cd90/`. Input, artifact and runtime reviews retain separate scopes. Raw profiles, keys, challenge values, UDP tables and host inventories remain outside source distribution. All execution gates are closed.

| Retained API36 record, relative to that evidence root | SHA256 |
| --- | --- |
| `independent-runtime-a2-ultra-review.md` | `d018f99268bfcc3c44bd298e9b97efb77ca854eb6127a3a6a58ae13abc339784` |
| `independent-runtime-a2-ultra-review.json` | `e366a5a6311c50b0037cec1f1354926e153746cc91893c8465f143ded1922c0c` |
| `runs/run-20261020-210935-f5cd90/acceptance.json` | `600993720bdb1a28ed9e044ea98e005778eb82bb0d6095b9c08b483bc9570185` |
| `runs/run-20261020-210935-f5cd90/cleanup.json` | `5be1691eda9c386ac659831f6795be815a7b61198b5addcade09c233a92c33ae` |

This cohort adds one local API36 x86_64 compatibility result. Physical/external-server interoperability, network loss/rebind, selected-Network hostname/lockdown behavior, observed protection returns/timing, recovery/cancellation, nonzero S4/header protection, wider fields/ABIs, installed UI/accessibility, distribution/release, DPI, performance and battery remain open. No production-readiness or complete #76 acceptance follows.
