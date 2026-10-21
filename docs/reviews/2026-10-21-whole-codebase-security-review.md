# Whole-codebase source and security review — #208

Last updated: 2026-10-21 (Europe/Paris).

## Scope and method

This record consolidates the completed 2026-10-21 maintained-source review and its repairs. Original candidate: `1239644402df1812214b0137dc22097a0994ab19`, base `36eb2837a232bacf91bfe749ead9518248f38aee`, clean `fix/tools-coexistence`. It included then-unmerged [PR #103](https://github.com/novakin/vpn0wn-android/pull/103) source. Candidate inspection is separate from delivery and runtime acceptance. The [earlier delivered-code review](2026-10-17-delivered-code-review.md) at `56509a3a` and [app/root audit](2026-10-12-app-root-security-audit.md) retain their original dates, findings and scope.

The preparation inventory has 701 tracked superproject entries and 170 expanded vendored paths. Its aggregate rows remain inventory-only, not a completed assessment ledger. They do not establish that 701 files passed. The five retained lane reports and their supporting records own substantive coverage; overlapping lane counts must not be summed as unique files.

| Lane | Completed inspection | Material limits |
| --- | --- | --- |
| Android production | 73 production Kotlin implementations | Some reads omit imports/package/blank boilerplate; provisioning owners are separate. |
| Android support | 74 test/fixture files and 95 nonlocalized text resources; 174 complete paths including five boundary/config paths | 35 translations received structural/format checks and selected French review; 24 assets/notices remain inventory-only. No screenshot review in this lane. |
| Provisioning | 26 provisioning owners, VPS Fragment and three widgets: 30 production files | 64 test/fixture paths inventoried; only one full and four targeted test-body reads, with the remainder limited to the recorded inventory. |
| Server scripts | Twelve maintained scripts, their README, Python integration harness/tests and fake-command fixture | The large shell suite received targeted ranges plus test-name/registration inspection. |
| Tunnel/native/root composition | Maintained production config/crypto, C/JNI, Go, generators, patches, recipes/pins and composed Android owners; five tunnel tests and ten config fixtures | Larger native test suites received mixed targeted/inventory inspection. External engine/module trees, dependency advisories and cryptographic assurance were not audited wholesale. |

Provisioning's broad documentation context reads were partly truncated; exhaustive guide review is not claimed. Original review lanes executed no tests, builds or device/server operations. Supporting XML/format analysis is static evidence. Later regression and runtime results retain their own revision, environment and actor. Private reports and inventories remain in ignored `.artifacts/continuation-20261021-full-acceptance/`; they are not source-distribution artifacts.

## Confirmed findings and corrections

| Finding | Corrected behavior | Delivery and proof |
| --- | --- | --- |
| `ANDROID-LIFE-01`, P2 | Remote broadcasts retain completion ownership until admitted SET/REFRESH work settles. | [#317](https://github.com/novakin/vpn0wn-android/issues/317), [PR #320](https://github.com/novakin/vpn0wn-android/pull/320), merged `031f952c`. Final head `6e51441e`; 33 distinct JVM/Robolectric controls across recorded runs, zero Detekt findings and final-head CI. This is not one fresh combined 33-control execution. |
| `ANDROID-LIFE-02`, P2 | TV consent retains the original tunnel and explicit direction through recreation, consumes approval once and remains fail-closed after process death. | Same #317/PR #320. No native TV or permission-bypass result follows from source/JVM proof. |
| `S1`, P2 | Peer allocation accounts for compact/tabbed assignments and every comma-separated route, preserving existing profiles and refusing unsafe allocation. | [#318](https://github.com/novakin/vpn0wn-android/issues/318), [PR #319](https://github.com/novakin/vpn0wn-android/pull/319), merged `0b78a8d2`. Reviewed head `5f469118`; nine exact-head changed controls and final CI. Retained full 207-test execution lacks a contemporaneous source manifest. The provisioning/server reports describe the same defect, counted once. |

The native and Android-support lanes added no confirmed defect or High/Critical vulnerability. That is a finding count within the inspected scope, not proof that the app has no security defects. The three retained source findings have mapped repairs.

Later disposable-server work independently found two further defects:

- [#321](https://github.com/novakin/vpn0wn-android/issues/321), [PR #322](https://github.com/novakin/vpn0wn-android/pull/322): admit verified fresh-system missing-unit responses. Reviewed `8067d946`, merged `5d3ad512`; 214 mapped-root controls and a separate eleven-checkpoint disposable lifecycle pass. That cohort did not establish restore/client traffic.
- [#323](https://github.com/novakin/vpn0wn-android/issues/323), [PR #324](https://github.com/novakin/vpn0wn-android/pull/324): parse native interface-filtered routes that omit `dev`, and the exact optional IPv6 kernel multicast route, while retaining strict custom-route refusal. Final reviewed `6da78bdb`, passing [CI 39557148909](https://github.com/novakin/vpn0wn-android/actions/runs/39557148909), merged `738a33d7` with identical reviewed/merged trees; #323 closed.

The [disposable restore record](2026-10-21-disposable-server-restore-acceptance.md) owns the 216-control offline gate and R4 two-VM result: retained-client authenticated traffic, pre-rollback denial, literal same-server rollback, exact ordered nonempty configuration fields/history, fresh-destination changed-endpoint traffic, source preservation and both owned cleanups. Blank-line normalization is measured; failed R3 remains failed. The snapshot-retired peer was not traffic-tested after restoration. This is not Android encrypted-container/JSch, new post-restore-client, hostname-cutover or external-server acceptance.

## Verdict and remaining limits

Confirmed findings in the retained source review are corrected and delivered. Later runtime defects also have reviewed, merged repairs. No additional unverified hypothesis is promoted to a required code change. Remote-control revocation after dispatch/admission remains a policy/runtime question in the Android report, not a demonstrated authorization defect.

Remaining scope includes release signing/distribution and full ABI provenance; dependency/advisory/crypto assurance; ordinary-UID/platform and user/profile boundaries; physical authentication/provider/Keystore durability; root-manager/shared-kernel and wider Magisk behavior; real network-loss/leakage/blocked-I/O races; crash/power-loss/signal cases; supported distro/SSH failures; rendered accessibility and public/external interoperability. Existing scoped acceptance narrows only its named cohorts, including [hostname recovery/lockdown](2026-10-21-hostname-recovery-lockdown-acceptance.md) and [AWG padding/rebind](2026-10-21-amneziawg-padding-rebind-acceptance.md).

[#71](https://github.com/novakin/vpn0wn-android/issues/71), [#73](https://github.com/novakin/vpn0wn-android/issues/73), [#75](https://github.com/novakin/vpn0wn-android/issues/75), [#76](https://github.com/novakin/vpn0wn-android/issues/76), [#143](https://github.com/novakin/vpn0wn-android/issues/143) and [#196](https://github.com/novakin/vpn0wn-android/issues/196) retain their owning acceptance boundaries. No production-readiness score, clean dependency-scan claim or release/security sign-off follows. The [plan](../improvement-plan.md), [contracts](../backend-contracts.md), [QA](../qa-test-plan.md) and [operations](../operations.md) remain authoritative. This consolidation changes documentation only and does not authorize runtime operations.
