# Downloads output ownership acceptance

Last updated: 2026-10-18 (Europe/Paris).

## Scope and verdict

Combined evidence meets [#28](https://github.com/novakin/vpn0wn-android/issues/28)'s behavior and agreed G4 physical Downloads/provider boundary. Source repair was delivered in [PR #46](https://github.com/novakin/vpn0wn-android/pull/46) at `0f363c8f`; this record reconciles its accepted source controls, historical native ZIP evidence, physical API34 MediaStore proof and new API28 legacy proof. No production source changes. Broader [#52](https://github.com/novakin/vpn0wn-android/issues/52) and [#196](https://github.com/novakin/vpn0wn-android/issues/196) acceptance stays open.

**D5 native controls and relevant cleanup are independently verified. Its original automated overall result remains FAIL.** The failed host query and unmeasured former APK-path erasure below do not leave a #28 behavior criterion unmet. This record supports scoped issue closure; it does not claim complete trial, current whole-app or release acceptance.

## Evidence and acceptance

The [source checkpoint](../development.md#downloads-output-ownership--2026-10-07) retains its original 22 combined saver/ZIP tests and zero static findings. Later source runs retain their recorded scopes; source inspection is not a new test run. The [physical picker/Downloads record](2026-10-18-native-backup-picker-acceptance.md) supplies actual API34 MediaStore success, injected write failure, post-acquisition cancellation, descriptor closure, row removal and preserved controls before harness cleanup. Its same-UID picker/no-configured-authentication limits remain specific to that recorded scope.

The legacy producer is clean source `0deb39edd0f529ff14f72e2c475dde7c9fe96289`, tree `24e3b5c834f258c782a62c00d42e5ebe9a8c1656`; APK SHA256 `77d246f6a478798e3e118db96351943be2b32d11ba7596072e0346ff302406f5`. The audited isolated x86_64 package declares minimum/target API24/36 and only legacy storage permission through API28. It runs on an owned API28 Google APIs guest. `DownloadsFileSaver.kt` bytes match the physical producer `0bc2dfef` and documentation base `77ef167d`, SHA256 `7d079dcbcf4c5e209e5d0143fcdde4e36f374c9a551fcc172d20d202fbbdac57`. This establishes owner parity, not whole-APK equivalence.

| #28 criterion | Accepted proof |
| --- | --- |
| Existing saver/output owner; no export service or dependency | Unchanged production owner retains acquisition, metadata, synchronous callback, close, cleanup and completed-path return. |
| Acquisition/metadata failure and acquired cancellation; closure/cleanup or visible cleanup failure preserving primary error | Source controls cover open/query/null responses, metadata cancellation, closed streams/cursors and primary Error/exception/cancellation with suppressed cleanup errors. Physical API34 and D5 prove actual acquired-write failure/cancellation closure and output absence before harness cleanup; a held synchronous callback drains. |
| MediaStore and supported pre29 ownership; ordinary success and permission denial | Physical API34 covers MediaStore. D5 proves real Deny with null/no writer/no output, retry Allow, exact filesystem success and preserved non-overwrite collision. Independent backing/process-mounted observations corroborate bytes and absence. |
| Explicit zero/false deletion and failure semantics | Source controls cover provider delete-zero, thrown cleanup errors and filesystem delete-false while retaining the primary failure. Native proof covers successful real removal. No unconditional cleanup or blocking-write interruption claim. |
| Existing overwrite and ZIP serialization | Source success/overwrite/reservation and canonical two-profile ZIP controls remain accepted. [Historical API28 ZIP/open-failure retry](2026-10-09-g4-private-native-acceptance.md#api-28-export-open-failure-follow-up) retains its original source/namespace. D5 finite-byte files are not new ZIP integration proof. |
| Contracts, operations and QA | Existing ownership, failure, overwrite and cancellation contracts remain; this delivery updates stale acceptance summaries and links the combined proof. |

D5 has six ordered native checkpoints, one native result, ADB exit 0 and instrumentation success code `-1`. All eight native facts pass; counts include cleanup, not independent scenario counts. Nine native FD observations show four held-writer samples at 1 and five settled samples at 0. Four independent host settled observations find zero exact owned descriptor aliases; no host mid-writer sample is claimed. Failure/cancelled files are already absent while success/control bytes still match, before harness cleanup.

## Retained failed attempts and cleanup reconciliation

All five attempts use the same audited APK. No later success rewrites an earlier outcome.

| Attempt | Original outcome |
| --- | --- |
| D1 | Empty successful root-command output failed the strict acknowledgement guard before install/native execution; cleanup passed. |
| D2 | Installed probe, then permission parser rejected an empty runtime section before native execution. Controller cleanup passed; a wrapper process-exit identity race required separate read-only preservation reconciliation. |
| D3 | Native admission and permission dialog reached; the selector rejected the actual ellipsized label before any tap. Controller cleanup and wrapper shutdown passed. |
| D4 | Native admission gate failed during direct acknowledgement publication. Final host bytes were exact; the native read bytes were unobserved, so partial/empty-read cause remains inference. Cleanup/shutdown passed. |
| D5 | Six native stages passed after narrow independently reviewed checker/publication fixes. Overall wrapper/controller exits remain 1 because the post-uninstall package query lost its host ADB connection. |

D5's original `acceptance.json` remains FAIL with primary failure null, one `cleanup-package: exit 1; expected 0` cleanup error and unset `ownedPackageAndPublicOutputsRemoved`. Before uninstall, recorded PID/own-UID process, public-path and owned-FD absence checks pass. Verified owned uninstall then exits 0 with `Success`. The next package query exits 1 with host ADB daemon/connection failure and no package result; it does not prove package presence or absence. The dedicated later APK-path query is skipped.

A subsequent successful complete package/UID inventory excludes the probe and equals admission. Later Downloads, mappings, launcher and sampled preservation match; owned guest shutdown passes all six postconditions with original shared owners preserved and userdata retained. These later observations reconcile package deregistration and relevant cleanup without a retry or result rewrite. Former APK filesystem-path erasure is **not directly measured**. The host transport cause remains unresolved; no intentional ADB-server lifecycle operation occurred.

Independent review verifies all 784 frozen evidence files and 35 checks. Private evidence remains under ignored `.artifacts/continuation-20261018/`; raw host/device metadata is not committed. Review SHA256: `53800e693e03df2857e956302615676471e49f4f138fbd9013934c3fa8556d13`; criterion matrix: `39899f9ac90c64b93c9fa01f1d7be1a09d77cda97a7d9fc714d7ecc4496d664c`; frozen vector: `379b6c44e49ea0fb7252571fd9d21ba67a042f794401b5cc34755dd7ddf1fc30`.

## Limits and documentation impact

Injected failure is not organic disk/provider refusal. Blocking writes remain non-interruptible; final dispatcher-return cancellation may leave a completed file without success feedback. Arbitrary providers/OEMs, configured authentication, cross-UID routing, spoken accessibility, wider lifecycle, current whole-app/release and live-server acceptance remain with their existing owners. No further runtime, rebuild or device action forms part of this documentation delivery.

Contracts, operations, QA, plan and changelog link this scoped acceptance. README, AGENTS.md, architecture, templates, UI roadmap and historical records need no edit: product behavior, setup, ownership, instructions and visuals are unchanged. PR-owned final-head review/CI/publication records remain separate.
