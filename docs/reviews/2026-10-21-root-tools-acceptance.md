# Disposable rooted-device tools acceptance — #71 / PR #103

Last updated: 2026-10-21 (Europe/Paris).

## Scope and candidate

The maintainer selected Pixel 7 Pro as the disposable rooted target, including installation, interruption and reboot. The tested source is `1239644402df1812214b0137dc22097a0994ab19`; debug APK SHA256 is `4a6b51adf62f4f6bacdff2fa9af0a04af1eb6cadeb65fe482cdbf824a1132886`. This API34/SELinux Enforcing trial exercises the actual Settings preference and production installer. Later integration changes retain their separate review and CI scope; they do not change this installed-artifact identity.

The original app APK, candidate data, legacy profiles, module inventory and root-policy table were preserved before mutation. Two existing app installations remain separate. The legacy kernel tunnel was stopped for testing and restored afterward. No production server was modified.

## Observed controls

| Control | Result |
| --- | --- |
| Existing conflicts | Native Settings refuses the baseline containing WireGuard's module and two invalid module-root files, preserving all existing content. This does not isolate the module alone as the cause. |
| Other existing module-root entries | After parking the foreign module, two original empty regular files still cause refusal. Same-filesystem renames preserve their original identities and metadata. |
| Free-path install | With those conflicts temporarily parked, native installation stages only the owned vpn0wn module. Tool bytes match the candidate APK; root ownership, executable modes and SELinux labels pass. |
| Completed-stage reconciliation | Reopening Settings recognizes the completed stage without rewriting it. |
| Actual reboot activation and reuse | Reboot mounts the exact candidate tools globally; Settings reports compatible tools already installed. All 283 unrelated pre-reboot module inventory rows remain unchanged after reboot. |
| Incomplete owned-stage refusal | A separate empty partial-stage fixture is refused without mutation, then removed by checked empty-directory removal. |
| Interrupted production copy | An injected FIFO-open stall holds the second production `cp` after the first tool copies. Exact process argv, root UID, calling package and process identity are rechecked before terminating only that child. Native installation reports failure, and the actual EXIT cleanup removes incomplete staging. |
| Retry after interruption | Original private source binary is restored with its inode, bytes, owner, mode and label. Native retry succeeds with a complete verified stage; that unmounted test stage is then removed. |
| Restoration | Original APK bytes, two legacy profile hashes, zero candidate profiles, userspace selection, VPN secure settings and complete Magisk policy table are restored. Original foreign module and root entries retain identity/content/metadata; only the two parent directory timestamps changed. Test module/global paths are absent. The original kernel tunnel is UP after app/root-shell retirement. |

The interrupted control establishes child-copy termination under an injected I/O stall and the installer's EXIT cleanup. It does not establish installer-shell INT/TERM handling, app death, power loss or uninstrumented user cancellation. An interface being UP does not establish authenticated external traffic.

## Evidence and failed attempts

Private evidence is retained under ignored `.artifacts/continuation-20261021-root-tools-103/`: baseline/preservation archives, staged/reconciled/activated inventories, native XML/images, reboot receipts, interruption input/outcome/source-restoration records, module/APK restoration and `final-restoration.json`. These records contain device metadata and remain outside source distribution.

Retain the failed admission/comparison attempts separately: the first interruption admission assumed the wrong private-cache group/mode and stopped before mutation; corrected observed private metadata is distinct from the staged tools' required `0755`. Raw module comparisons failed on directory timestamps and enumeration order; identity/content/metadata reconciliation accounts for those differences without relabeling the raw comparisons as passes. Reinstalling the original test-only APK initially failed without `-t`; the explicit test-only reinstall succeeded with byte-exact readback. None establishes a product defect.

## Acceptance limits

These controls supply scoped physical reuse/refusal, staging/reconciliation, executable/label, interrupted-copy/retry, reboot activation and restoration evidence for PR #103. A foreign-module-only native refusal control was not performed; baseline refusal had multiple conflicts. Final-head integration review and CI remain owned by the PR acceptance record. Full #71 migration, release-signing/distribution, other OEM/Magisk combinations and the broader old/new-ID matrix remain open. This trial establishes neither VPN performance nor production readiness.
