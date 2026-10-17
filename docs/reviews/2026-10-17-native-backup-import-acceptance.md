# Native encrypted-backup reader and inspection acceptance

Last updated: 2026-10-17 (Europe/Paris).

## Verdict and boundary

Related to [#196](https://github.com/novakin/vpn0wn-android/issues/196), which remains open. The scoped Pixel 7 Pro/API34 reader and ViewModel trial passes: actual Android descriptor acquisition, bounded input, cancelled blocked-open drain, safe inspection metadata and local input-error handling. Exact installed-artifact readback, isolated-package removal and original foreground preservation also pass.

This is an isolated provider/model trial, not installed document-picker, authentication, password-dialog, WindowManager, IME, TalkBack, SSH or restoration acceptance. No server, VPN, personal app/configuration or device setting was changed. [The inspection contract](../backend-contracts.md#local-encrypted-backup-inspection--196) and [QA](../qa-test-plan.md#local-encrypted-backup-inspection-controls--196) retain the complete source and workflow boundaries.

## Tested source and artifact

The native producer uses signed source `534e30f03ea223feaf6ba024ada8ba59898404fd`. The production reader SHA256 is `50430a444f4be51f23ab92cafa0e71a2423c44f575bbecde10c69f6506d8701e`. Production/test source remained unchanged throughout the final build, audit and trial. A later mechanical unused-import deletion does not create another runtime run.

The exact isolated ARM64 debug APK has SHA256 `6f45759e9f9dbd090fdbd0cafc1c40090ce75db516c8c4711ba40b010c8ef4a3` and size 18,430,074 bytes. Its four frozen producer inputs have manifest SHA256 `7041a0f5a14110c9882d9c7135f7ab06aa9ba065a64d2292c8831b92660750c0`. The packaged 489-byte synthetic archive has SHA256 `d8d21a350bb00c070339f7164bd8e4b41bdcd13a9dd39c797e96b24559768446`; it is the independently recovered output from the [earlier export trial](2026-10-17-native-backup-export-acceptance.md), not personal server data.

The replacement manifest retains the actual Application, self-target instrumentation and one nonexported in-process provider. It has min24/target36, backup disabled, sole `arm64-v8a`, no permissions, activities, services or receivers. The artifact audit checks the actual merged manifest, APK signature, packaged fixture, frozen/live inputs, production/debug/untracked build inputs and exact clean native gitlinks. Actual DEX includes the corrected cancellation worker and both signal-aware provider overloads; class presence alone is insufficient evidence.

The real Application, preferences/fleet owners, document reader, ViewModel and codec execute. The injected remote-shell factory fails on any attempted transport creation. The provider serves only owned synthetic files; query and mutation entry points fail. The maintained 221-test/14-class source checkpoint remains separate from this native trial; neither overlapping scope is added to the other.

## Observed controls

| Control | Actual result |
|---|---|
| Regular input | Exact fixture bytes returned; captured descriptor closed |
| Existing maximum | Accepted at the codec's archive limit; returned buffer cleared and descriptor closed |
| Excess input | Rejected as FORMAT; captured descriptor closed |
| Provider ignoring cancellation | Native signal received; task remains pending until provider release, then late descriptor closes and cancelled task drains |
| Provider honoring cancellation | Actual `CancellationSignal.throwIfCanceled()` path drains as cancellation without returning a descriptor |
| Actual model inspection | Existing codec decrypts the fixture and publishes only expected public summary fields |
| Denied and unsolicited native cancellation | Actual model reports local INPUT failure, clears summary and busy state, and creates no server-associated problem |
| Side effects | Zero transport calls, provider queries or provider mutations; fleet and preferences remain empty |

The excess-input native control does not measure exact read offset; maintained JVM/source controls own maximum-plus-one acquisition proof. The controlled blocked providers are explicitly released: this does not establish a deadline for an arbitrary provider that never returns. No general latency, cold-KDF, complete heap-erasure or configured-authentication claim follows.

## Retained failed trial and correction

The first runtime trial passed regular/maximum/excess input, then failed the late-open cancellation-signal latch. The fixture overrode only `openFile`; Android's read path enters typed/asset overloads whose default implementations discard the signal before delegating. The [Android14 provider source](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/java/android/content/ContentProvider.java) and resolver path, plus the existing maintained provider fixture, established the failing layer. No product repair was indicated by this trial.

The corrected fixture forwards both cancellation-aware asset and typed-asset entry points to its existing three-argument `openFile`, and requires a nonnull signal for controlled blocked opens. It preserves the original latch, still-pending, drain and descriptor-closure assertions. New frozen inputs, a new APK audit and a fresh installation produced the passing trial above. The failed trial and its successful uninstall/absence/foreground cleanup remain retained separately. An earlier pre-install admission failure used the wrong foreground-report label; it changed no installed package.

## Cleanup and remaining acceptance

Fresh admission requires successful empty package inventory and the expected device/API. After installation, the pulled installed base APK matches the audited SHA256 exactly. Instrumentation clears its actual ViewModelStore, joins the captured model scope, closes its captured descriptor and deletes only its owned scratch files. The external runner has a 90-second deadline with bounded own-process shutdown; no timeout occurred in the accepted trial.

Unconditional cleanup uninstalled only the admitted isolated package. Successful package inventory and path readback establish absence; the original resumed-activity record is unchanged. Raw serial, UID, paths, foreground component and process receipts stay private outside source distribution.

Native picker and configured authentication, actual password-window/inset/IME/accessibility behavior, API24/other OEM/provider cases, process death/power loss, real SSH and guarded restore/client traffic remain open. The archive retains its existing experimental format and derivation-cost limits. Source integration, final-head CI, merge and release/deployment require their own acceptance; this record does not authorize a live VPS restore.
