# Native password-backup codec controls

Last updated: 2026-10-16 (Europe/Paris).

## Verdict and scope

Related to [#196](https://github.com/novakin/vpn0wn-android/issues/196), which remains open. Named archive interoperability, maximum-payload, constrained-heap, cancellation-feedback and cleanup controls pass on API24/API36 emulators. **Overall cold-performance acceptance is not established: the initial API36 cohort fails the proposed 2,000 ms p95 target.** A later settled diagnostic and a separate instrumented fresh-boot phase diagnostic have passing scopes. The codec remains unwired and experimental; this record neither freezes a writer format nor accepts integrated backup/restore or release.

The [archive contract](../backend-contracts.md#experimental-password-archive-codec--196), [QA controls](../qa-test-plan.md#experimental-password-archive-controls--196) and [cryptography decision](../architecture.md#experimental-portable-backup-cryptography--196-2026-10-16) retain their owners and remaining gates. This is a sanitized assessment of synthetic controls; raw host/process receipts and failure logs remain outside source distribution.

## Original producer and evidence boundary

The original isolated debug APK was built from clean producer `fa17782e3831fa14c44a74a60b8f81703c604339`. Its SHA-256 is `fb8894490c06bfaf51d540a6c92b4b21f0def6a144fdb4cdbe1a0ddbd95c52e3`. The acceptance variant uses plain `android.app.Application`, self-target instrumentation, package `local.vpn0wn.backuparchiveprobe.debug`, min24/target36, debug/test-only, backup disabled and no production components or permissions. It executes the production codec/parser rather than adding production test hooks.

Offline APK inspection verified signature, actual runner/codec/BC DEX definitions, resolved `bcprov-jdk15to18:1.86` and JSch 2.28.8 graph, byte-identical independent fixture, complete standalone BC MIT notice and its complete credits section. These are artifact packaging facts, not release, license-compliance or security certification. No advisory recheck or native SSH regression was performed by these probes.

For that original artifact, root executed on fresh owned Google APIs x86_64 API24 revision 27 and API36 revision 7 guests. Readback reconciled 60 accepted cold process identities and 66 successful native result/raw-output pairs: exact SDK/package/mode, successful completion and exit, instrumentation code -1, complete expected check sets and Boolean true values. Fresh admission and active PID plus process-start observations bind each accepted cold sample; no process identity was inferred after completion.

The original JVM/Detekt result retains its source `87a56274` plus two parser annotations association in QA. Neither that run nor this producer APK is a test of integration `56a1ba95758336b45b62392311856cbe76be8458`. A separate Git-object comparison found nine identical blobs between producer and integration: codec/test, independent fixture, version catalog, UI Gradle declaration, BC notice/credits, JSch factory and its regression test. Other Gradle declarations/properties have no delta. The shared-model delta only adds accepted blank/duplicate status-peer guards; snapshot models, `parseBackup` and archive validation remain unchanged. This supports bounded source-equivalence attribution, while final integrated-head checks/review and any new artifact retain their own scope.

## Observations

| Control | API24 | API36 |
| --- | --- | --- |
| Initial fresh-process cold, twenty samples | p95 **194.0142 ms**, max 215.9149 ms: scoped PASS | p95 **3044.284840 ms**, max 4830.485583 ms: **FAIL** |
| Separate settled-runtime cold diagnostic, twenty | Not performed | p95 **446.031918 ms**, max 477.322801 ms: settled-scope PASS |
| Warm KDF, twenty in full mode | p95 **176.1057 ms** | p95 **223.639242 ms** |
| Maximum-payload encrypt/decrypt | **327.4766 / 335.2937 ms** | **325.195676 / 261.891816 ms** |
| Initial recorded Java headroom | **67,787,032 bytes**, 64.6467514 MiB | **67,929,456 bytes**, 64.7825775 MiB |
| Sampled Java/native peak bytes | **591,653,408 / 6,821,168** | **170,211,984 / 6,120,944** |
| Main callback / cancellation settlement | **13.628 / 153.0753 ms** | **2.106557 / 383.297188 ms** |

Cold p95 uses nearest-rank sorted sample 19 of 20. Each sample measures first BC generator initialization/allocation/derivation in a distinct fresh application process, at unchanged Argon2id v1.3 19,456 KiB/two passes/one lane. Password/parameter preparation is outside that interval. One API24 host-sampling race remains a failed attempt despite native completion; its separately recorded replacement supplies the twentieth accepted identity. No failed sample was converted to success.

The initial API36 sequence has four samples over 2,000 ms and a pronounced early/late latency change under elevated host load. Boot/load influence is plausible, not established. The sole settled diagnostic was predeclared after full/heap/cancel work, with a new twenty-process cohort and recorded host observations. It retains every observation without pooling, pruning or repeated retries. Prior workload conditions its scope; its PASS does not replace the initial FAIL or establish overall cold/API36 performance acceptance. No KDF costs or algorithms changed.

Both full modes decrypt the independent archive and encrypt all eight audited password vectors against independently derived keys, reporting AndroidOpenSSL AES-GCM and packaged-notice checks. Each completes an exact 786,504-byte maximum archive round trip; each heap mode completes three. Actual initial headroom is within the original **65 MiB / 68,157,440-byte tolerance**, not an exact 64 MiB ceiling or a minimum-device policy. Nonempty numeric ART before/after observations include GC counts 275 to 423 on API24 and 1 to 14 on API36. Five-ms sampling can miss transient peaks and does not measure maximum RSS. Whole maximum-payload timings have no separate two-second gate.

Cancellation observes the BC worker before and after the request, Main feedback while the job is unfinished, settlement without a returned archive and preserved caller characters. The non-cooperative KDF settles before cleanup; these controls do not prove instruction-level interruption, production-screen responsiveness or absence of ANR. Immutable strings, library/provider copies and VM termination retain memory-erasure limits.

Binary-safe extraction followed each passing full mode. Normal, non-optimized Windows Python 3.12.10 with `cryptography` 46.0.7 independently derived Argon2id, authenticated/decrypted AES-GCM with the full header as AAD and compared the exact semantic snapshot; both verifier commands exited zero. The independent fixture was originally generated with `cryptography` 50.0.2. Matching synthetic ciphertext across APIs follows the harness's deterministic fixture salt/nonce; it is not a production randomness claim.

## Separate fresh-boot phase diagnostic — 2026-10-16

The predeclared `api36-fresh-boot-phase-01` diagnostic used clean source `fa778011d7d834b70353280c59155382e536d98f`, tree `172001faf2ff40bc7f83562d39d3f36b131652bb`, and a new isolated APK with SHA-256 `b67cccce038b59b7c18480fa589f1e92d7f0b8462cd79ce80f3f263b2ccf30ce`. Twenty complete result/raw-output pairs bind twenty distinct fresh application processes to one fresh owned API36 boot. Each process invokes cold mode once; no KDF, warmup or full/heap/cancel mode preceded the cohort on that boot. This is separate from the original artifact and its cohorts; their counts are not combined.

At unchanged Argon2id v1.3 19,456 KiB/two passes/one lane, nearest-rank p95, sorted sample 19 of 20, is **1660.850712 ms**, below **2000 ms** for this diagnostic. Maximum **6004.295886 ms** is the first sample and the sole observation over target; it remains included. Added construction/init/derive wall and thread-CPU timings put that sample's derive phase at **6003.954538 ms wall / 2726.919177 ms thread CPU**. Extra clocks and metadata introduce observer overhead and can condition execution. Wall/CPU differences establish no scheduler, host, boot, JIT, class-loading or GC cause; metadata windows and heap snapshots are not phase attribution or peak/RSS measurements.

Independent ultra review passed this cohort's admission, reconciliation and predeclared arithmetic, checking all twenty raw/results, process identities, phase/metadata fields and retained helper receipts. It independently checked exact APK ZIP/DEX contents against source, including the new diagnostic fields and unchanged costs/vector/provider checks, fixture and complete notices. This verdict accepts that evidence scope only. The historical initial **3044.284840 ms FAIL** and conditioned settled **446.031918 ms** remain distinct; neither this passing diagnostic nor its six-second first sample is omitted or used to erase the initial failure.

The pre-build WSL Git/worktree-path failure and initial test-only installation failure remain retained. The task-local Git bridge corrected build admission; fresh same-boot package absence preceded corrected `-t` installation and sample 1. After sampling, isolated uninstall and package-list/path absence passed; owned launcher/child exit, guest disconnect and unused owned ports were verified. Source remained clean at the pinned revision and the exact APK hash was unchanged. No physical/OEM, production UI/ANR, overall cold-performance, writer-format freeze or #196 delivery acceptance follows.

## Cleanup, failures and remaining gates

Both isolated packages were uninstalled after fresh same-boot admission; package path/list absence was verified before owned shutdown. Receipts confirm guest disconnection, recorded launcher/child exit, no listening fixed ports and preserved disks; root joined launch commands. Earlier harness admission/bootstrap repairs, the API24 sampling race, API36's initial offline admission observation and its failed cold cohort remain retained. These owned-guest checks do not establish preservation of uninspected personal-device state.

The source foundation can remain a partial #196 slice, with the failed target and experimental status explicit. Oldest physical/OEM cost, production startup/UI/ANR, native JSch with BC, configured authentication/admin restrictions, file/provider ownership/cleanup, password UX, fresh-install user recovery, safe privileged restore/rollback, client traffic and release remain unaccepted. A later writer-format decision must address the unresolved performance boundary rather than silently reducing costs or treating either later diagnostic as replacement evidence. Final PR checks/review and publication state remain separate from this historical runtime record.
