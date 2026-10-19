# Network automation native acceptance — #74

Last updated: 2026-10-19 (Europe/Paris).

## Scope and verdict

**Scoped PASS: one isolated API28 x86_64 native A7 cohort, including recorded cleanup and preservation.** The [independent runtime ultra review](https://github.com/novakin/vpn0wn-android/pull/298#pullrequestreview-5567387815) is satisfied within this scope, with no unresolved material findings. [PR #298](https://github.com/novakin/vpn0wn-android/pull/298) delivered the bounded source implementation as merge `1a084d016e440eb374ea2a6d9e1b7cc0a0022e42`. [#74](https://github.com/novakin/vpn0wn-android/issues/74) remains open for wider acceptance.

This record summarizes retained runtime evidence and its independent review. It performs no new runtime run and adds no product changes. Native A7 is distinct from the earlier JVM A7 checkpoint in the [QA guide](../qa-test-plan.md#planned-network-automation-controls--74). The [backend contract](../backend-contracts.md#planned-network-automation--74) and [operator guidance](../operations.md#planned-network-automation--74) retain ownership and support limits. Source review and CI keep their separate scopes in the PR acceptance record.

## Frozen source and artifact

| Binding | Accepted identity |
| --- | --- |
| Runtime source | `19667395800a7305a79e1a513d0596f3b3c858e3` |
| Source tree | `89afd679e608ea1431ed55e9bd22c53b81343a6c` |
| Input manifest SHA256 | `3a33c04633740d00559959a362fe881168402de19cfeb20e90b2c0ff77bb8620` |
| Produced and pulled installed APK SHA256 | `838aa465b09262800f901ef458ba6b3be068532c6d54a64c0f7d967e552543eb` |
| Isolated package / platform | `local.vpn0wn.automation74probe.debug`; Android API28, x86_64 |

The independent review verified all nine harness, thirty selected source and eight tool bindings, the clean source revision/tree and both initialized native gitlinks. The installed APK is 19,238,137 bytes and matches the audited producer. The separate artifact assessment retains its manifest/DEX/native-library scope; this runtime review does not replace it. The runtime reviewer did not independently rehash the system-image payload; the reviewed controller owns that admission check. The tested revision remains fixed after source merge and this documentation update.

The cohort uses the production Application, manager, monitor service, controller, GoBackend and real public Network callbacks. Actual Android VPN consent and granted `VpnService.prepare` consent precede the isolated synthetic tunnel. Its single `/32` route, synthetic endpoint and absence of configured ListenPort, DNS and keepalive bound the scenario; no external server or Internet route is accepted.

## Accepted native observations

| Control | Observed result and boundary |
| --- | --- |
| OWNED startup | Production monitor callbacks select a public Network with a live foreground monitor. Both native socket families complete the captured preparation, ready report and confirmation sequence. |
| Explicit Disable | Capture ownership changes to LEGACY through fresh protected sockets. Both socket inodes change; the sampled TUN, interface index, native session/handle and runtime port remain. The saved profile is unchanged. |
| Ordinary rebind | The accepted LEGACY capture publishes unready then ready state and replaces both sockets again, without a new Java activation or service. |
| Synthetic traffic | Exact authenticated HTTP 200/body checkpoints pass after OWNED startup, completed Disable and ordinary rebind. They establish reachability at those checkpoints, not uninterrupted transition traffic. |
| Obsolete request | Native admission returns OBSOLETE without a capture callback; the complete sampled native state remains unchanged. |
| Already-interrupted request | The worker never enters the observed native transport/capture methods and preserves the same state. Cancellation after native admission or during blocked I/O is not covered. |
| Ordinary DOWN and disabled Resume | DOWN removes running intent and the TUN. The real disabled service Resume action reaches FAILED, leaves the monitor absent and causes no native restart. |
| Enabled manual hold | A fresh Enable creates a new activation/service/capture on the selected Network. Manual DOWN retains the foreground monitor and reaches MANUAL_HOLD. Direct `controller.resume` preserves the episode, exact manual receipt/hold and DOWN state at return. Enabled service-intent acknowledgement and Settings UI are not exercised. |

For OWNED startup, Disable and rebind, each sampled socket's successful `prepare` precedes the matching successful ready `report` and `confirm`. In the frozen `GoBackend.SocketCapture.prepare` owner, OWNED success means `Network.bindSocket` returned and final captured-service `protect(socket)` returned true; LEGACY uses protection only. These API-return observations establish the production preparation/publication decisions. They do not observe the physical packet path.

Five native samples retain the same session, handle, TUN descriptor/name/index and runtime port. Socket descriptors are reused, while changed inodes demonstrate replacement on Disable and rebind. This is sampled identity/config preservation, not continuous observation of every TUN/route property. Expected retired-activation unready callbacks during DOWN cause no restart or replacement in the guarded DOWN/Resume phases. Foreground survival is sampled within the enabled episode; Doze, background restrictions, process recreation and boot remain outside it.

## Completion, preservation and cleanup

Independent parsing found exact semantic equality across native, private and instrumentation-bundle results, one successful instrumentation finish code `-1`, 61 relevant observer records, 24 complete entry/return pairs and thirteen network callbacks. Sequences are contiguous, no call remains pending, and both instrumentation and observer exits are zero. No controller failure receipt exists.

All fifteen cleanup/preservation fields are true, with an empty cleanup-error list: instrumentation and observer stopped; owned package, process, peer, peer port, emulator and launcher absent; forwards restored; shared owners preserved; source, inputs, boot, packages and captured settings unchanged. Native cleanup disables/drains automation, stops the monitor, brings the tunnel DOWN and deletes the fixture profile before publishing success. The host then verifies the live instrumented process, stops the observer, completes the process-bound detach acknowledgement and receives the matching successful instrumentation bundle. This accepts the reviewed completion order.

All eighteen before/after snapshot command outputs reconstruct equal stored snapshots: package/path inventory, boot identity, forwards, interface names and five captured settings. Neither interface-name snapshot contains a `tunN` name. Host-wide interface-index preservation is not claimed; the application separately samples its TUN index. Uninstall reports success, package/PID absence checks agree, and the owned debugger forward is removed. Peer/port, emulator/launcher absence and shared-owner preservation are supported by the reviewed controller's identity-checked receipts, not fresh observations made for this document.

All 300 recorded commands complete without timeout. The only nonzero exits are expected initial/final package-PID absence checks. Both runtime authority gates are closed. Cleanup acceptance admits no further device, producer or runtime action.

## Retained failed attempts

| Attempt | Original result retained |
| --- | --- |
| A2 | Failed before installation at wildcard host interface-index observation. `forwardRestored=false`; full restoration acceptance was not established. |
| A3 | Failed before installation at denied explicit `ip6_vti0/ifindex` observation. `forwardRestored=false`; full restoration acceptance was not established. |
| A4 | Failed before native exercise at nested consent-component expansion and malformed missing-readiness output. All fifteen cleanup fields are true. |
| A5 | Exact consent launch succeeded; readiness command quoting failed before exercise. All fifteen cleanup fields are true. |
| A6 | Instrumentation admission failed with an uninitialized manager before the observer gate; no native exercise stages or facts. All fifteen cleanup fields are true. |

These attempts and their original reviews remain failed. A7's corrected instrumentation and narrowed host interface-name inventory contract do not retrospectively accept them. A separate preliminary read-only audit selector included an unrelated receipt and stopped before output; the corrected selector passes without changing runtime evidence.

## Evidence and remaining acceptance

Private evidence remains under ignored `.artifacts/continuation-20261018/`, including the A7 producer/run and earlier failures. Raw host inventories, device/process identifiers, private result payloads and fixture configuration stay outside source distribution. The independently derived audit verifies the decisive result, observer, command and restoration evidence.

| Retained record | SHA256 |
| --- | --- |
| `network-automation-native-a7-runtime-ultra-review.md` | `3bb356be72d9a5f9ec75e597d3cab39ebb78569993f96ede652e01e31c8c860d` |
| `network-automation-native-a7-runtime-audit.json` | `aa6d0b488c3b7fc11f6e34821d743e2b2eb95c63a400f86288481363a110ba4f` |

Remaining #74 acceptance includes physical public-N selection/routing and hand-back, Wi-Fi loss with working mobile, packet/source-port/protocol behavior, outer IPv6 data traffic, failure and in-flight cancellation races, supported API/OEM/permission/policy/background/process/boot behavior, enabled service-Resume acknowledgement, and installed Settings/output/accessibility controls. Wider exact rules and supported configuration/backend boundaries remain governed by the owning contract.

A7 establishes no seamless handover, zero-leak guarantee, peer-health guarantee, reliable background survival, latency, speed or battery improvement. The passive observer suspends event threads, so its timing cannot support performance claims. Source merge and this isolated synthetic debug cohort establish no shipping-APK equivalence, release or physical deployment.

Documentation impact is limited to this historical acceptance record and current #74 status/evidence pointers. No new behavior, API/schema, architecture choice, operational policy, build setup or agent workflow follows; existing requirements and earlier source/test dates remain intact.
