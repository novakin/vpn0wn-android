# UDP warm-up compatibility review

Last updated: 2026-10-07 (Europe/Paris).

## Scope and evidence

[#37](https://github.com/novakin/wireguard-android/issues/37), G1 of the [active goal](../improvement-plan.md#active-enhancement-and-performance-goal). Compare merged master `3dc57410a457979bea2e16fc8c008abbcdfb1371` with the preserved original checkout at `65feb91d` plus its dirty GoBackend/BackendException/error-message changes. The [preservation review](2026-10-06-local-work-recovery.md) owns that snapshot and both APKs. This is a source compatibility decision, not a new native packet, performance or phone run.

Reviewed owners: [GoBackend](../../tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java), [BackendException](../../tunnel/src/main/java/com/wireguard/android/backend/BackendException.java), [Config serialization](../../tunnel/src/main/java/com/wireguard/config/Config.java), [Interface serialization](../../tunnel/src/main/java/com/wireguard/config/Interface.java) and [GoBackendLifecycleTest](../../ui/src/test/java/com/wireguard/android/backend/GoBackendLifecycleTest.kt). The active contract is [WireGuard configuration and tunnel state](../backend-contracts.md#wireguard-configuration-and-tunnel-state).

## Findings and decision

| Boundary | Merged behavior | Preserved variant | Decision |
| --- | --- | --- | --- |
| Endpoint-free profile | Skip absent endpoints; continue to native config/activation. | Reject when no resolved target exists. | Keep merged eligibility; endpoint-free peers are supported. |
| ListenPort | Skip warm-up when absent/zero; serialize an explicit configured port unchanged. | Choose a fresh 49152–65535 runtime port and replace native serialization. | Keep config ownership and explicit-port compatibility. |
| Warm-up traffic | One-byte packet per resolved endpoint, with five best-effort I/O attempts and 200ms retry delay. | Three random 32-byte packets per target, packet/settle sleeps and ten attempts; warm-up is mandatory. | Do not port unproven extra work or mandatory activation rejection. |
| Protection/bind/send | Protect before bind/send; I/O exhaustion logs and native activation continues. | Failed warm-up becomes a new activation error. | Keep the existing expected-I/O policy. No policy change is approved by recovery. |
| Interruption/DNS | UDP interruption aborts with the thread flag preserved; DNS startup has its separate failure/interruption path. | Reclassify warm-up interruption as a new backend failure. | Keep the merged exception/liveness boundaries. |
| Service identity/lifecycle | Check liveness around activation, retries and native commit; teardown is ordered and checks exact service ownership. | Remove merged destroyed-service checks and currentService identity handling; restore synchronous cleanup. | Keep merged lifecycle repairs; never copy the old complete source file. |

Decision/status, 2026-10-07 (Europe/Paris): retain the merged implementation; recover no part of the experimental mandatory/random-port variant. Source inspection establishes concrete compatibility regressions and no demonstrated benefit requiring a change. A future measured proposal may revisit a narrowly defined warm-up requirement, but it needs a concrete policy decision before changing endpoint or ListenPort compatibility. #43 investigates performance separately; it does not approve this variant.

The candidate closes its warm-up socket before native acquisition. That alone does not establish reliable port handoff, preserved NAT mapping, packet delivery or improved reconnect behavior. Extra random packets and sleeps are source-observed work, not measured speed or battery costs. Do not promote the retained APK into approval or claim a performance gain from this decision.

The candidate-only UDP_PREFLIGHT_FAILURE reason and its missing 35-locale messages are not ported because their behavior is not accepted. Copying unused translations or duplicating config serialization would add unsupported surface. Original source/APKs remain preserved; no cleanup, history rewrite or migration is part of this decision.

## Verification and limits

Existing backend fixtures exercise endpoint-free explicit-port and absent-port skips, preserved explicit native serialization, preflight interruption, rejected socket protection, prompt destruction while the transition lock is held, dead-service reownership/acquisition rejection, old-service cleanup identity and ordered DOWN callbacks. These are synthetic service/native-call controls; skips and protection failure do not prove successful native activation or sent traffic.

Bind/send retry exhaustion, DNS resolution failure and DNS-sleep interruption are traced in the existing owner rather than injected by these fixtures. Physical socket protection, native port handoff, packet/NAT behavior, reconnect, process/service restart and successful native activation remain unproven. No APK assembly/install, real VPN traffic, root, server operation or personal config replacement was run. See [scoped verification](../development.md#udp-warm-up-compatibility-decision--2026-10-07).

The source-only review fulfils #37's recovery decision boundary by rejecting the incompatible candidate and disclosing native limits. It does not certify the retained warm-up policy or fulfil G4's physical/performance acceptance.
