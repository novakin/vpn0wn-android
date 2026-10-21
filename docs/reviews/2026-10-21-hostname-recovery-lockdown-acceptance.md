# Hostname recovery and cancellation under lockdown — #73/#75

Last updated: 2026-10-21 (Europe/Paris).

Scope: natural selected-Network hostname/numeric recovery and manual cancellation during delayed Android DNS on one owned API36 x86_64 emulator. Related to [#73](https://github.com/novakin/vpn0wn-android/issues/73) and [#75](https://github.com/novakin/vpn0wn-android/issues/75); both remain open. The fresh R6 command and automated evidence checks pass. Independent ultra review accepts the bounded native, packet, cancellation and cleanup evidence; no actionable defect remains within this scope.

## Source, artifacts and setup

Frozen production source: `ad03dda6ad0023b151ec42f856efb755076b89bb`, tree `a78485cab54b89cc69b412496215aed2f8a19d7e`. Relevant production owners match integration base `e82e852d1d8f1eae8490cbd42c24bc2e98c691bd`; remaining source differences are unrelated VPS export MIME code/tests. The provider retains production Application, manager, automation, backend and native transport owners. Its instrumentation overlay and separate test package do not establish shipping-APK equivalence.

| Artifact | SHA256 |
| --- | --- |
| Provider r2 `local.vpn0wn.hostname73probe.debug` | `2d317c5c0ae0c9a912736977ad7a56876a048b801f2dec4eb1c0ebedc2424eda` |
| Ordinary WireGuard peer | `06a09dbcecfa2a240da8a5c7204510e5ef3dadce83311eb530d1025ca8d553ff` |
| Separate ordinary-UID canary | `cf5a9fd3467137f4d9dcfdc65c38341fcccc061b4aa690c2f796b265ed99d077` |
| Sealed Radiotap capture | `c3610e999dd1b6ca8b26d652839e34c8977651dc0200a0fc739af104be11068f` |

The provider build exits 0 in 214,883 ms; static APK audit exits 0 in 9,973 ms. API24 minimum/API36 target, x86_64 packaging, signature, alignment, compiled owner and native provenance checks pass. Independent input review passes 46 evidence tests and 34 static/live-file checks; exact-artifact ultra review admits only a fresh bounded run.

One fresh emulated Wi-Fi Network carries ordinary WireGuard to the emulator host alias `10.0.2.2:51973`, with inner IPv4 `10.7.0.2/32` to `10.7.0.1/32`. ListenPort and keepalive are omitted. The selected port is allocated at activation and retained during that activation's recovery. Controlled DNS names and credentials are synthetic; no personal phone, external peer or live VPS participates. Private DNS is inactive. Actual Android consent and native Always-on/Block connections controls are operated and visually inspected, then corroborated by system policy and live provider flags. Provider UID 10216 and ordinary canary UID 10217 are distinct. The socket observer owns UiAutomation; no concurrent host hierarchy dump runs.

## Observed runtime

Thirty native stages complete without probe failure. The peer is absent during ordinary handshake retries; no exhaustion injection or shortened production timer is used.

| Control | Observation |
| --- | --- |
| Before/during/after lockdown | Canary default DNS, selected-Network DNS and literal-IP HTTP succeed before and after; all fail under lockdown. Healthy fixture controls surround the negative phase. Native switches, system owner/lockdown/allowlist and live flags are checked. |
| Hostname recovery | Natural exhaustion occurs after about 103 seconds. Android's recovery DNS answer is held, the same-key peer starts, then DNS is released. One backend entry/admission/charged attempt completes with `INITIATION_STARTED`; session, selected Network, service, saved config, native handle, TUN/interface and local port remain. Generation advances 1 to 2 and socket inode sets are disjoint. Exact 58-byte HTTP response follows authentication. |
| Numeric control | Natural exhaustion occurs after about 103 seconds. One admission/charged attempt replaces sockets while retaining the same owners and port. Exact 57-byte authenticated response follows. No endpoint-name DNS occurs in the numeric observation window. Recovery may already be COMPLETED when sampled; the observed natural episode and timestamps remain retained. |
| Cancellation during held DNS | Manual DOWN cancels the recovery job. The host releases DNS before awaiting DOWN/worker settlement. The old session/transport retires, confirmed admissions remain zero, the saved profile survives and no resurrection is observed. This tests safe settlement, not prompt cancellation of Android's blocking lookup. |
| Fresh lifecycle | A new numeric activation under lockdown exchanges the exact 58-byte restored challenge. Policy is then restored and ordinary canary positive controls pass again. |

The sealed underlay capture and peer/DNS receipts are checked against phase windows, endpoint-only queries, ordinary-canary negatives/positives, and two-way WireGuard UDP on port 51973. These controls establish the local authenticated path, not Internet health or universal containment.

All 18 cleanup/preservation fields pass: native policy, packages, processes, services and baseline restore; DNS/peer/guest/netsim stop; their owned ports release; commands drain; capture seals; shared ADB, Windows DNS owners and frozen source/inputs remain unchanged. Cleanup errors are empty. The native instrumentation exits 0, and the full host command exits 0 in 877,268 ms.

## Earlier failures and corrections

R1–R5 remain failed/incomplete. Windows sharing errors affected live peer-output/DNS-event reads; shared-file reads and focused open-writer/partial-record controls repair the responsible host readers. A concurrent hierarchy dump conflicted with UiAutomation ownership; later policy proof uses inspected screenshots and system/live flags. One run missed its native-action deadline during handoff.

R5 reached natural hostname exhaustion but failed at the probe's hostname barrier. Source review found an obsolete-generation assertion after DNS release could already replace sockets; the original failure lacked a precise source line. The corrected barrier allows that expected transition while the dedicated recovery assertions retain identity, new-generation, disjoint-socket and authenticated-traffic requirements. Cancellation keeps its old-owner barrier while DNS is held. The checker accepts valid zero native handles/TUN descriptors, rejects negative/non-integer identities, and the probe records only a sanitized own-code failure location.

The abandoned DNS-reader trial retains its nonzero fixture exit/cleanup error even though resource absence checks later passed. No earlier failure is overwritten or reclassified by R6. These repairs change only ignored validation inputs; production source remains unchanged.

## Evidence and limits

Ignored evidence owner: `.artifacts/continuation-20261020-hostname-recovery`; fresh run `runs/run-20261020-233053-437a0c` (2026-10-21 Europe/Paris). Executed input manifest SHA256: `177d74dc82804bef2a9998b11152bca8ac5362b2d4ab6ed729defebabf41fabb`. Actual host command log: `20261021-013049-099-e5f41e9e4e8a4610af6cdc2edc7c5214.log`. Executed authority, manifest, runner, operator screenshots/actions and actual tool settlement are retained. Independent runtime review receipt SHA256: `8afbdf6387f220a3d68ae53f20e882d2e2cd4c32f814070b4f26d9c9dc8a4787`. Review verifies all 450 recorded commands settled with drained output, including nine expected exit-1 probes and normal exit 0 for all four peer instances.

This proves one controlled ordinary-WireGuard IPv4 path. Physical/OEM behavior, external paths, handover, changed endpoint addresses, alternate families, Private DNS/DNS64, full/split/LAN/excluded-app matrices, process death/reboot/revocation, and bounded resolver cancellation latency remain open. It supplies no AWG, DPI, speed, battery, universal leak-free or release claim. The [selected-network contract](../backend-contracts.md#selected-network-hostname-recovery--7374) and [system policy matrix](../qa-test-plan.md#android-system-vpn-policy-acceptance) own remaining acceptance.
