# Native retained-profile acceptance — #143

Last updated: 2026-10-20 (Europe/Paris).

The isolated API36 A8 cohort passes its native, synthetic-peer and cleanup checks. Independent ultra runtime review accepts this bounded evidence. [#143](https://github.com/novakin/vpn0wn-android/issues/143) remains open for the wider acceptance described below.

The separate [A9 cohort](#a9-exported-file-round-trip) also passes real DocumentsUI cancellation and reimport of the exported `.conf` through the production Home/Files path. Its source, artifact and runtime reviews pass independently at ultra. A8 and earlier attempts retain their original results.

## Scope and source

The A8 cohort exercises retained-profile disclosure through the production Application, default Fragment/ViewModel/client, AndroidKeyStore stores, native credential prompt, MediaStore Downloads and TunnelManager/FileConfigStore. It uses one owned API36 x86_64 emulator, an isolated test package, synthetic profiles and a loopback SSH fixture. The fixture validates requests and uploaded script bytes but executes no scripts.

The corrected product source is `3fe44604d89a243c3965a4a90740960460aa135c`, tree `e4038638e0398bc9b54c34fe4b4425ddfc09fa12`. A8 APK SHA256 is `997d4820208f5ae132ddde22faaa035b5c4300d79cedeea294d6cb3f6a6f7774`. Manifest SHA256 is `5d3eab5c560277ddf3c993da3d792b605d20eba8e4d2de48dcafdf792c7a4978`. Independent source and artifact reviews pass at ultra; both builds, APK audit and compiled-peer verifier exited 0.

## Failure and correction

Original A7 passed configured authentication, exact generated-QR checks and exported-byte/Config checks before its filename assertion failed. The failing assertion required a `.conf` suffix and valid bounded tunnel basename. A7 did not retain its actual display name, so its failure alone does not establish a particular suffix. Its aggregate remains failed; later root cleanup is separate evidence.

The existing source tests previously copied the requested Downloads display name. They now delegate filename resolution to Android `FileUtils.buildUniqueFile`. Both existing `.conf` controls fail with `text/plain` and pass when the export owner requests `application/octet-stream`; Detekt reports zero findings. The change preserves canonical UTF-8 contents, authentication, status validation, name limits, no-overwrite policy and cleanup.

A8's harness moves its existing non-secret export receipt before filename assertions, preserving the actual name if validation fails. All filename, payload, authentication and phase checks remain. Compiled artifact review confirms the single product MIME replacement, retained assertion ordering and unchanged authentication behavior.

Earlier attempts retain their original failures and cleanup records. The generated-bitmap QR assertion uses ZXing's `PURE_BARCODE` path after the ordinary detector failed on an unscaled generated bitmap; it still requires exact payload and canonical Config equality. This proves generated bitmap content, not camera or optical scan performance. Earlier authentication timeouts remain unexplained; later successful input does not establish their cause.

## Runtime results

The controller exited 0 after 361,091 ms. The offline aggregate verifier exited 0 after 397 ms. All 119 controller commands settled without timeout; the only nonzero child exit is the expected absent-process `pidof` check; all fifteen cleanup facts are true. The guest shut down normally, with shared ADB/source preserved and no separate cleanup reconciliation needed.

| Phase | Observed result |
|---|---|
| Seed/open | Real production owners, separate Keystore aliases/private ciphertext, same-process canonical reopen and fresh pinned status pass. |
| Authentication cancellation | Native credential cancellation produces no status read or sensitive output. |
| QR success | Configured PIN succeeds; a new pinned status read precedes exact generated-bitmap payload and canonical Config checks. |
| Export success | Fresh authentication/status produces a real Downloads row named `retain-mvgj322c.conf`; its 309 bytes exactly match the retained profile and parse canonically. The prior sentinel remains unchanged. |
| Local-import form cancellation | Native naming-form cancellation produces no new status read or tunnel. |
| Local import | Fresh authentication/status creates the exact canonical profile through the production manager and FileConfigStore; state remains DOWN. |
| In-flight cancellation | Cancelling the held SSH status closes its channel/session and prevents disclosure. |
| Stale-status refusal | A changed endpoint in the fresh status response prevents profile disclosure. |
| Restored positive control | Restored matching status permits authenticated exact QR output again. |

Seven successful configured-PIN authentications and seven real pinned JSch sessions are observed. Six status cycles complete; one is cancelled. All 27 peer commands and seven uploaded-asset cycles match the production protocol. The cancelled cycle's virtual directory is removed by the fixture after actual worker/session settlement; the other six are cleaned by the client. No script is executed.

Cleanup removes owned outputs/tunnel/package, restores PIN/lock/settings and reverse mappings, verifies absent VPN service/TUN and settled peer workers/listeners, then normally stops the owned emulator. All original receipts are retained.

An earlier root launch command failed its review-scope check before creating a guest. Root attributed the rejected admission to a PowerShell alias shadowing its hash helper; independent review confirmed the pre-guest rejection without replaying that diagnosis. Its exit-1 log remains preserved; correcting root admission did not change reviewed harness or product inputs.

## Independent review and retained evidence

Independent ultra source, compiled-artifact and runtime reviews pass with no unresolved findings. The runtime reviewer independently reran the aggregate checker: exit 0, 666 ms; 254 non-private top-level evidence files remained unchanged. The accepted artifact review confirms the one product MIME replacement and the moved test receipt with all assertions preserved. Author review is satisfied within the named functional cohort; no new visual acceptance is claimed.

Private artifacts remain under ignored `.artifacts/continuation-20261019-retained-profile-143/`: producer/audit inputs, original A1–A7 failures, A8 native/peer/command/cleanup receipts and independent review records. No profile payload, synthetic PIN or raw host report is committed. The A8 aggregate acceptance receipt SHA256 is `99c6b8ec1f51a8e27273c832c214c924c42abcae991eb06597bf6f43d1236382`. Final PR review and CI cover the eventual delivery head separately from the executed product revision.

## A9 exported-file round trip

On 2026-10-20 (Europe/Paris), a fresh owned API36 x86_64 emulator exercised the real exported-file path from merged source `9049beaea3a4f4f91b9965f94b175fbc0e714b74`, tree `91b86c8abc9d8822b464802d0bdeaf49de9de390`. This revision differs from A8's product source only in Markdown. No product code changed for A9. Its isolated APK SHA256 is `28c0f26b9e7ce31205c3c7fae78579a35bd0a1c764fa1e685aab17e4b6d07278`; input manifest SHA256 is `9167015783b88d2e2d7e96099288d331bfd321495649d2f8215fd03872742b4d`. Both producers, the APK audit and compiled-peer verifier exited 0 before runtime.

A9 repeats the nine original native phases in its own fresh cohort, then returns through native Back navigation to Home, opens the actual Add sheet and Files action, and observes Android's `com.google.android.documentsui` picker twice. The first visit is cancelled through Back: private files, vault, existing tunnel and Downloads rows remain unchanged. On retry, the picker selects the actual exported `retain-mvh42buv.conf`. The unchanged `GetContent` callback and `TunnelImporter` produce a second tunnel with the matching basename. Its complete canonical config matches the retained profile through both TunnelManager and FileConfigStore; it remains DOWN. The 309-byte export, sentinel and encrypted vault are preserved. No activity-result injection, fake provider or direct importer invocation substitutes for this path.

All eleven phases pass. The two picker visits add no SSH status cycle or authentication: totals remain seven successful configured-PIN authentications and seven pinned JSch sessions, with six completed status cycles, one cancelled cycle and 27 protocol commands. No tunnel is activated and no remote script runs. The selected filename, unchanged production callback path and persisted result are observed; the callback URI itself is not recorded.

The controller exited 0 after 369,157 ms; the Windows offline verifier exited 0 after 419 ms. All 113 controller commands settled without timeout; only the expected absent-process `pidof` check returned 1. All fifteen cleanup facts pass, including owned output/tunnel/package removal, PIN/lock/settings/reverse restoration, absent VPN service/TUN, settled peer workers/listeners, preserved shared ADB/source and normal owned-guest shutdown. No separate cleanup reconciliation was needed.

Independent ultra runtime review reran the offline verifier successfully in 1,020 ms, checked source/artifact/admission correspondence and preserved all 241 non-private A9 evidence files. A8 evidence and earlier failed records remain unchanged. The A9 aggregate acceptance SHA256 is `63bcfa41570e63586dff265b2b265478900eb268e5dfc081d8b4d425f562599b`; the independent runtime review receipt SHA256 is `cae42ba542a43952da2b9f75d13fba2e9b7b860a9c4f55dac2997cc017c15aae`. Original receipts and review reports remain under the same ignored artifact owner, in the separate A9 guest/run directories. Author review is satisfied with this bounded functional proof; no new visual acceptance is claimed.

## Limits

Synthetic seeding is not observed server-side user creation. Same-process store reopening is not process-death or power-loss durability. A8 parsed exported bytes and used a separate local-import action; it did not invoke TunnelImporter on that file. A9 supplies that real file-picker/import path only for its named API36 cohort. No tunnel is activated.

No live install/add/remove/revoke/rotate script, real client traffic, physical biometric/OEM/provider behavior, camera scanning, accessibility matrix, release or complete #143 acceptance follows. Original source/render/physical cohorts retain their named scopes.
