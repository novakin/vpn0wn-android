# Backup and restore source security review

Last updated: 2026-10-18 (Europe/Paris).

## Verdict and scope

Independent ultra review found no actionable product defect in the inspected backup/restore paths at integrated master `16e002bd0a89f366b76ead5bcf59553f80fc561f`. This is a scoped source review, not a whole-app audit, execution approval or production-readiness rating. [#196](https://github.com/novakin/vpn0wn-android/issues/196) and [#143](https://github.com/novakin/vpn0wn-android/issues/143) remain open.

Source was read from clean `0a9a5ef19081c3a5eef9e51dde09143f55605655`; a Git comparison confirmed unchanged Android production/test and app-facing provisioning-script trees through the integrated master. Current owning contracts, operations and changelog were also read. The reviewer ran no tests, builds, device/emulator/server operations or GitHub writes. Parallel acceptance work has separate records; earlier checks retain their original revisions and environments.

## Reviewed boundaries

| Coverage | Status and evidence |
| --- | --- |
| Inspection of the source owners below | VERIFIED, static, at the revisions above. This describes completed inspection, not proof that every defect is absent. |
| Android, real SSH and destructive restore execution in this review | NOT_ASSESSED. Earlier runtime records retain their own scope; this review executed none. |
| Complete backup/restore security acceptance | PARTIALLY_ASSESSED. The remaining acceptance section identifies the outstanding runtime and policy boundaries. |

- `VpsBackupDocument` bounds input before KDF work, owns descriptor/cancellation handling, drains its worker and clears undelivered buffers. A provider that ignores cancellation can still delay drain.
- `VpsBackupArchive` validates the fixed envelope before KDF, authenticates before snapshot parsing and clears operation-owned password, key and plaintext arrays. Immutable strings and library copies remain documented erasure limits.
- `VpsProvisioningFragment` retains view/action-bound picker, authentication and secret owners. Export rechecks owner/admin policy before output; comparison exposes metadata without granting restore permission.
- `VpsProvisioningViewModel` separates read-only inspection from confirmed mutation, rechecks source/destination/SSH identity and durably records admission before dispatch. Failure, cancellation and cleanup warnings retain uncertainty instead of claiming success.
- `VpsServerStore` fences conflicting writers/imports and settles only the matching pending restore identity. `Vpn0wnProvisioningClient` delegates supported restore-envelope parsing to `ProvisioningJson`; `VpsBackupResult` and `VpsRevokedBackup` own redacted diagnostic strings. `VpsBackupComparison` compares exact configuration content while publishing safe equality/name metadata.
- `restore.sh` validates ordered input before privileged mutation, rejects unsupported/shared destination state, rechecks under locks, regenerates supported hooks rather than executing imported directives and verifies final disk/history/runtime/service/forwarding postconditions. Shared rollback code distinguishes admitted failure, incomplete recovery and committed cleanup.

Relevant test assertions and failure/cancellation controls, Downloads output ownership and selected SSH pin/channel/cancellation boundaries were inspected. No evidence-backed authentication bypass, secret disclosure, destructive overwrite or incorrect settlement was found in those paths. This finding does not establish exhaustive race/fault coverage or broaden the documented restore contract.

## Remaining acceptance

Android wizard/provider/authentication behavior, real SSH transport and restoration, traffic verification, broader credential/Keystore lifecycle, OEM/backup/power-loss behavior and uncoordinated external writers remain separate acceptance boundaries. Destructive restore requires an explicitly authorized disposable target; this review selected none. Same-host access-exclusion/overwrite and related unresolved policies remain issue-owned.

The [backend contracts](../backend-contracts.md) and [QA plan](../qa-test-plan.md) own implemented behavior and executed verification. The [native trial record](2026-10-18-native-consent-transfer-acceptance.md) separately records failed acceptance harness trials; their restoration checks do not certify live server safety.
