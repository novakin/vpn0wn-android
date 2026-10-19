# AmneziaWG native lifecycle and traffic acceptance — #76

Last updated: 2026-10-19 (Europe/Paris).

## Scope and status

**Scoped PASS: twelve native stages and all nine host cleanup/preservation checks in one isolated API24 x86_64 A4 cohort.** Independent ultra review, including a separate host-evidence review, is satisfied within this scope, with no actionable findings or material acceptance gaps. At this recorded A4 checkpoint, [PR #302](https://github.com/novakin/vpn0wn-android/pull/302) was draft and unmerged; its acceptance record owns subsequent source/documentation review, checks and delivery. [#76](https://github.com/novakin/vpn0wn-android/issues/76) and parent [#72](https://github.com/novakin/vpn0wn-android/issues/72) remain open.

This document records existing evidence; it adds no product changes or runtime execution. The [configuration/native ownership contract](../backend-contracts.md#optional-amneziawg-configuration-and-native-ownership--76) and [QA record](../qa-test-plan.md#optional-amneziawg-controls--76) retain the separate source, host handshake/traffic, artifact and broader Android acceptance boundaries.

The later [A2 retry1 traffic cohort](#api24-a2-retry1-traffic--2026-10-19) is separately accepted below. The A4 source, artifact, observations, failures and limits retain their original scope.

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
