# Native fresh-restore orchestration acceptance

Last updated: 2026-10-21 (Europe/Paris).

Scope: B5 exercises the guarded Android fresh-restore workflow on one isolated API36 x86_64 emulator against a synthetic SSH peer. Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143) and [#196](https://github.com/novakin/vpn0wn-android/issues/196); both remain open. The controller, receipt verifier and independent ultra outcome review pass; no finding remains within this bounded cohort.

## Source and artifacts

Production source is `ad03dda6ad0023b151ec42f856efb755076b89bb`, tree `a78485cab54b89cc69b412496215aed2f8a19d7e`. The isolated `local.vpn0wn.backupcompareprobe.debug` APK retains the production Application, Fragment/ViewModel, client, transport, stores and Android Keystore. Instrumentation drives native actions and configured PIN authentication; it does not inject authentication outcomes, private restore state or a replacement transport factory.

| Binding | SHA256 |
| --- | --- |
| Reused B3 APK | `44b6f70db564207901f727a30596ae28084c709f3a062f3e3d5274898ed56a1e` |
| B5 input manifest | `0f0e1eb45df0137fb7a8285f44790055e2c1877123d147cc8f2ffec09b0c7201` |
| Separate B5 compiled-peer receipt | `7baa31f53c8735e77c45efadd037a73ee0716aa5a324a26cd105777eea10032f` |
| Independent outcome review | `4c6449c557c222ef796e16f22a42047113abb4de620534b28fa639d5b57c7363` |

The APK keeps its original producer evidence. B5's manifest identifies the later controller and peer preparation; it is not a new APK producer. Official emulator 37.2.12 runs alongside the installed SDK, using the retained API36 image and shared ADB server.

## Observed controls

All eleven native phases pass, with seventeen native checks:

| Boundary | Observation |
| --- | --- |
| Inspect and prepare | Open saved source, inspect the encrypted archive, Detect the fresh destination and perform CHECK through the production client. |
| Cancellation before admission | Cancel actual authentication, then cancel the confirmation dialog. Neither action dispatches a restore write. |
| Admitted restore | Persist the bound pending intent before receiving the single CONFIRM response. |
| Uncertain verification | Hold VERIFY, cancel through the native flow and retain the uncertain admission after command cancellation/drain. |
| Reopen and reconcile | Reopen the saved destination, reselect the original ciphertext and dispatch VERIFY only. Successful reconciliation clears the pending intent without repeating CONFIRM. |

The run records two DocumentsUI picker visits, four successful configured-PIN authentications, two application document reads and zero picker-provider reads. Five SSH sessions execute 23 commands: four completed operations and one cancelled VERIFY. All sessions close. Each cycle verifies the actual twelve packaged script assets; four restore-input cycles verify exact snapshot/revocation bytes and 0600 files in owned 0700 directories. The peer checks these bytes but never executes the scripts.

All fifteen cleanup predicates pass: native outputs/package/process/service removed, no test TUN, baseline and reverse mappings restored, temporary PIN removed, lock state restored, peer/listeners settled, guest stopped, shared owners and source preserved, and normal shutdown. Retained launcher and backend handles both report actual exit 0. Cleanup errors are empty. Physical phones and production servers were not operated on.

## Earlier failures and evidence

Earlier cohorts remain separate: the first lacked actual process-exit evidence; B2 failed picker selection; B3 passed its native phases but failed aggregate acceptance on abnormal emulator shutdown. B4 prepared a peer but did not run. B5 uses the separately reviewed newer emulator and preserves those histories.

Private evidence is retained under `.artifacts/continuation-20261021-fresh-restore-196/b5/` and `D:/Vpn0wn-Acceptance-20261021/android-guests/Vpn0wn_Backup196_API36_FreshRestore_B5_20261021`. The controller exits 0; `check-receipts.py` independently validates the exact phases, SSH cycles, artifact bindings and cleanup, then writes `acceptance.json`. Raw credentials, archives and host evidence remain outside source distribution.

## Limits

These are `ad03dda6` artifact results, not a run of integration base `ea90e1235760abd58ef0b6991fae40e7c4974b06`. The compared Android changes do not alter the direct restore path exercised here; later profile export, broadcast lifetime and TV consent changes are not covered. B5 transfers the older packaged scripts to a synthetic peer and provides no runtime acceptance for master's later allocator, absent-unit or route-parsing repairs.

The separate [disposable two-VM restore record](2026-10-21-disposable-server-restore-acceptance.md) covers privileged server operations and authenticated client traffic on its recorded scripts. These cohorts do not establish a combined Android/JSch/live-server run. Saved-store reopening occurs in the same process and is not process-death or power-loss recovery. The same-UID document provider and configured PIN do not establish cross-UID grants or biometric acceptance. Physical/OEM behavior, conditional providers, same-server Android rollback, public DNS cutover, full-profile interoperability and release acceptance remain open.
