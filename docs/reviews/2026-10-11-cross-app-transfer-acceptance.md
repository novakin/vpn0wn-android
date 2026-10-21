# Synthetic cross-app transfer acceptance — pinned debug pair

Last updated: 2026-10-11 (Europe/Paris).

## Result and scope

An actual native export from the legacy debug app imported successfully into the separately installed vpn0wn debug app on an isolated API 36 AVD. Both apps were recreated and their persisted data read back. All nine native phase wrappers completed with exit 0; all 22 individual transfer-checker controls and the aggregate passed. Owned transfer outputs were removed, installations/userdata retained, and the owned emulator stopped with all five shutdown checks true.

This is bounded E6 evidence for [#71](https://github.com/novakin/vpn0wn-android/issues/71), following the maintainer's [settings/saved-fleet selection](https://github.com/novakin/vpn0wn-android/issues/71#issuecomment-6086430555). It extends the [legacy-ID preparation record](2026-10-09-app-data-transfer-acceptance.md) with a real two-app transfer. It changes no app behavior, archive schema or migration policy. Current documentation-head review, CI and integration state belong in the delivering PR acceptance record; the runtime result applies only to the pinned artifacts below.

## Environment and artifact boundary

The run used Android 16/API 36 Google APIs x86_64 revision 7, user 0, `emulator-5598`, 1080×2400 at density 420. Both target packages were initially absent. Installation created independent Android sandboxes and UIDs; it did not update an existing old installation. No new APK was built and no physical-phone operation formed part of this run.

| Role | Source revision | Application ID / UID | Installed APK SHA256 |
| --- | --- | --- | --- |
| Legacy exporter | `e4efaea0d424e279d04ceee5dee3e8e396115d33` | `com.wireguard.android.debug` / `10216` | `8c3c8f8f9daefe8121842e11175b5dba5bdbe9574ee3fddfbe1162c43d0064c2` |
| New-ID importer | `20ed2f66f7ffa89fcf5071d27c51728d227d8a22` | `com.vpn0wn.android.debug` / `10217` | `e7ffab8f3c63483faf5daee49392a75d2c1896cda392f803352019ee2f425ca4` |

On-device SHA256 readbacks of the installed APKs matched the pinned hashes. Both retained artifacts have the verified debug certificate SHA256 `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. The shared signer does not establish an upgrade from another installed signer or release identity.

The synthetic payload contained two peerless, endpoint-free profiles, four portable Boolean settings plus the last-used selection, and one saved non-secret VPS record. Both profiles stayed DOWN; recreation readback found no package VPN service. This run exercised data transfer without establishing VPN traffic, Android VPN permission, tile or boot setup, SSH connectivity or server health.

## Native transfer and persistence

| Completed phase | Observed result |
| --- | --- |
| `install` | Exact retained artifacts installed into the initially absent packages; installed identity/hash and distinct UIDs recorded. |
| `seed-import` | Legacy Settings imported the pinned synthetic seed through Android's real document picker and one confirmation. |
| `source-capture` | Source canonical profile bytes and settings/fleet matched the seed before native export. |
| `export-submit` | Legacy Settings submitted Export app data once. |
| `export-collect` | Exactly one new native export was collected; its MediaStore owner was `com.wireguard.android.debug`. |
| `destination-baseline` | New-ID app had empty profile/fleet state; destination preferences were captured before import. |
| `destination-import` | New-ID Settings imported that actual native export through Android's real picker and one confirmation. |
| `recreate-readback` | Both apps stopped/reopened; DOWN state, profile/settings/fleet persistence and the 22 checker controls passed. |
| `cleanup` | Only recorded seed/export outputs and the empty operation-owned upload folder were removed; installations/userdata remained. |

The collected native ZIP was 919 bytes, SHA256 `341268c70a4195cdb38f28331ee22ca84946815d7884b148fc2a61b7c54f1cd6`. Its manifest and canonical profiles matched the seed, but the destination received the actual legacy-app output rather than a generated replacement archive.

Source profile and preference bytes remained unchanged across export and both-app recreation. Destination profile bytes, all five portable settings and the saved fleet record matched the native archive. The checker found no imported-profile restore intent. Destination nonportable preference maps were empty before and after: their equality passes, but populated nonportable-setting preservation is not established. The fresh destination also provides no unrelated-record, conflict or active-tunnel preservation proof.

## Cleanup and retained evidence

Cleanup checked absence of the operation-owned outputs and retention of both installations and their userdata. Graceful owned stop then verified `ownedProcessesAbsent`, `ownedPortsAbsent`, `sharedOwnersPreserved`, `userdataRetained` and `configUnchanged`, all true. The original launch session `41186` was joined with exit 0. This leaves synthetic app data available in the retained AVD; it does not restore an existing personal-data baseline.

Private evidence remains ignored under `.artifacts/overnight-20261011/transfer-ready-native-20261011`, its `transfer-ready-avd-20261011` sibling, `transfer-ready-binding-20261011` and `transfer-ready-prep-20261011`. The preparation's `HANDOFF.md`, `NATIVE.md` and `FINAL-READY-ULTRA-REVIEW.md` retain their earlier offline/unbound scope; the separate binding and native receipts record subsequent execution. APKs, private archives, fixture keys, raw captures and command streams are not source evidence.

Retained launch/stop logs under `.artifacts/codex-command-logs` are `20261011-153451-215-488589be28c144fb8134f2ee4ed1412d.log` and `20261011-155802-334-00864a19a37f4ecda2fd656a36f86d7f.log`. Native phase intents, command results, `recreate-readback-done.json`, `cleanup-done.json` and `stop-result.json` preserve the decisive run/readback evidence. Earlier failed `emulator-5594`/`5596` attempts and their evidence were preserved; their failures do not become successful phases of this run.

## Remaining acceptance and documentation impact

[#71](https://github.com/novakin/vpn0wn-android/issues/71) stays open. The pinned pair establishes neither current-master runtime nor an existing-old-app exporter upgrade, physical or release migration, configured biometric hardware, provider-failure cleanup, populated nonportable/unrelated destination preservation, VPN/permission/tile/boot setup, rollback/process-death recovery, root-tools/kernel ownership or distribution/corresponding-source acceptance. Keep the old installation, data and signer-compatible recovery artifact until those applicable migration gates are verified. No DPI, speed or battery result follows.

[Operations](../operations.md#app-data-transfer) owns private-file handling and update order; [QA](../qa-test-plan.md#full-app-data-transfer-acceptance) owns the remaining observable checks; the [transfer contract](../backend-contracts.md#full-app-data-transfer) and [namespace contract](../backend-contracts.md#application-and-source-identity--71) remain unchanged. README, operations and QA now point to this scoped record. The improvement plan and historical architecture/namespace records still correctly leave complete migration open. Backend/UI ledgers, architecture, contracts, AGENTS.md and templates need no edit because this adds acceptance evidence without changing behavior, ownership, setup, protocol or process policy.
