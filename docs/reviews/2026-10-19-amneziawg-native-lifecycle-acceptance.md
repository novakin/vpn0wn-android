# AmneziaWG native lifecycle acceptance — #76

Last updated: 2026-10-19 (Europe/Paris).

## Scope and status

**Scoped PASS: twelve native stages and all nine host cleanup/preservation checks in one isolated API24 x86_64 A4 cohort.** Independent ultra review, including a separate host-evidence review, is satisfied within this scope, with no actionable findings or material acceptance gaps. At this recorded A4 checkpoint, [PR #302](https://github.com/novakin/vpn0wn-android/pull/302) was draft and unmerged; its acceptance record owns subsequent source/documentation review, checks and delivery. [#76](https://github.com/novakin/vpn0wn-android/issues/76) and parent [#72](https://github.com/novakin/vpn0wn-android/issues/72) remain open.

This document records existing evidence; it adds no product changes or runtime execution. The [configuration/native ownership contract](../backend-contracts.md#optional-amneziawg-configuration-and-native-ownership--76) and [QA record](../qa-test-plan.md#optional-amneziawg-controls--76) retain the separate source, host handshake/traffic, artifact and broader Android acceptance boundaries.

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
