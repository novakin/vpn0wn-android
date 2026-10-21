# Native configured-authentication backup comparison

Last updated: 2026-10-20 (Europe/Paris).

## Scope and result

The isolated API36 A2 run passes all nine native phases, its synthetic SSH peer, the controller and the offline aggregate verifier. It covers configured device-PIN authentication, actual DocumentsUI selection, recovery-password handling and read-only Compare under [#196](https://github.com/novakin/vpn0wn-android/issues/196). The issue remains open. Independent runtime review is recorded with the final PR acceptance; this record does not establish restoration, live-server behavior or release readiness.

The probe keeps the production Application, MainActivity, default Fragment/ViewModel, encrypted credential store, archive codec, document reader and JSch comparison owners. It seeds one synthetic saved server and encrypted archive through those owners. It substitutes no authentication callback, ActivityResult, model state or transport factory. The local SSH fixture validates the twelve uploaded script assets and the admitted backup-only command order; scripts never execute.

The synthetic read-only DocumentsProvider shares the isolated consumer's UID and requires `MANAGE_DOCUMENTS`. Actual system-picker routing therefore proves this same-UID provider path, not cross-UID URI grants or arbitrary document providers. Neither a personal phone nor a live VPS participates.

## Source and artifact

- Frozen product revision: `ad03dda6ad0023b151ec42f856efb755076b89bb`, tree `a78485cab54b89cc69b412496215aed2f8a19d7e`.
- Documentation integration base: `7d221a28ee16a3b4390e7a0ce3b6588b4d8cb29a`. Its 26 pinned non-Markdown owners match the tested source; this is not a new run of the integration revision.
- API36 x86_64 owned emulator; isolated package `local.vpn0wn.backupcompareprobe.debug`.
- APK SHA256: `57a15dbda51bad26bdec0b3ca395d038398321b87688859aa0ebf197cfe08899`.
- Harness input manifest SHA256: `29d0f681592da199254d5f07f41f3d98d15d84918e7ff44c0fd9519b518a9d87`.

Fresh APK and peer builds, APK audit and peer resolution verification all exit zero. Independent source/artifact review checks the emitted readiness/diagnostic changes, unchanged production/provider classes, all 205 peer classpath entries and 2,915 members. Build-directory tokens explain the bounded native-library byte difference from A1. This preparation review is separate from runtime acceptance.

## Executed controls

| Phase | Observed result |
| --- | --- |
| Seed/open | Production owners, configured secure keyguard and encrypted credential reopen; no SSH cycle. |
| Picker cancellation | Actual DocumentsUI Back retires selection without authentication, archive read or SSH. |
| Authentication cancellation | Actual system credential cancellation produces no recovery-password form, archive read or SSH. |
| Password dismissal | A new PIN prompt completes; the secure masked recovery-password form cancels without reading the archive. |
| Wrong password | A new PIN prompt completes; one provider read rejects the wrong recovery password without summary or SSH. |
| Summary Close | Authenticated archive summary closes; invoking its retained stale positive callback starts no SSH. |
| Differing Compare | One pinned backup read publishes the expected text-equality and archive-name differences without private bodies or restore actions. |
| In-flight Cancel | Close retires the action; the retained callback cannot restart it, and the held SSH channel/session/worker drains. |
| Equal Compare | A new authenticated comparison succeeds after cancellation, with equal config text and empty difference groups. |

Totals: eight picker visits, six successful configured authentications, five app-provider reads and zero picker fixture reads. The peer observes three authenticated SSH sessions, two completed backup responses, one cancelled response and eleven commands. Completed sessions remove their own temporary uploads. The cancelled socket cannot issue its cleanup command; the fixture removes only that cycle's scratch after channel/session/worker drain.

The run preserves private store/config/fixture hashes, the saved server record and empty tunnel/output sets. It performs no restore, status polling, tunnel creation or VPN activation.

## Settlement and preserved failures

The controller exits zero after 393,523 ms. The offline `check-receipts.py` verifier exits zero after 439 ms and checks exact phase/count receipts, command settlement and all fifteen cleanup facts. Owned outputs, package, process, service, reverse mappings and synthetic PIN are removed; the original lock state and sampled baseline are restored. The peer closes, the guest stops normally, shared owners remain and source is preserved.

A1 remains failed. Seed/open, picker cancellation and authentication cancellation passed before its first successful-authentication attempt timed out. It accepted no successful authentication, archive read or comparison. Its original failed aggregate and separately recorded normal cleanup remain unchanged.

A2 adds named diagnostic steps and waits for the app's focused window and resumed Activity/Fragment before opening the picker. PIN injection, callbacks, acceptance and product source remain unchanged. A2 success does not establish the cause of A1's timeout or prove a production authentication repair. Earlier shared-harness/classpath failures retain their original evidence and are not relabelled as successful runs.

## Evidence and limits

Private evidence remains under ignored `.artifacts/continuation-20261019-backup-compare-196/`: the A2 build/peer/audit receipts, independent reviews, actual tool exits and `guests/Vpn0wn_Backup196_API36_A2/` raw/canonical native, peer, command, cleanup and aggregate receipts. Do not publish synthetic credentials, archive payloads or raw host/device output.

Physical/OEM authentication, biometrics, cross-UID grants, arbitrary providers, provider refusal/timeout, complete export/archive shipping behavior, privileged server execution, actual restore/rollback, retained-client traffic and endpoint cutover remain separate. No new visual/theme/font-scale/RTL/accessibility verdict, network privacy guarantee, performance gain or APK release follows.

The [comparison contract](../backend-contracts.md#read-only-encrypted-backup-comparison--196), [QA controls](../qa-test-plan.md#read-only-encrypted-backup-comparison-controls--196) and [operator guide](../operations.md#compare-an-encrypted-backup-with-a-saved-server) retain their respective behavior, repeatable checks and operational limits.
