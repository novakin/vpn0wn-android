# Offline license screen: repaired native acceptance

Last updated: 2026-10-10 (Europe/Paris).

## Scope and provenance

Scoped private-AVD proof for [PR #100](https://github.com/novakin/wireguard-android/pull/100), a partial [#71](https://github.com/novakin/wireguard-android/issues/71) attribution slice. This record covers the original accessibility-node truncation, the repaired native notice rendering and the executed navigation/selection matrix. It does not close #71 or establish release licensing compliance.

The root operator used the fresh headless `Tunnl_Private_API36_20261010` instance, serial `emulator-5584`, Android 16/API 36 Google APIs x86_64 image revision 7, Pixel 8 profile and density 420. The visible AVD, physical Pixel and shared ADB server were outside this session's operation scope. Raw captures, hierarchy XML, private audit reports and build evidence remain ignored under `.artifacts/runtime-acceptance-20261010/`; they are not source-distribution artifacts. Root owns final private-instance restoration and shutdown; this scoped record does not assert completed cleanup of the broader ongoing runtime session.

| Artifact | Source boundary and identity |
| --- | --- |
| Original debug APK | Published source `3a9db82dc38b0d1a84ab7627e784eddbb654457b`, tree `4149d73d76a8e4ee011699ad9c52e5dd902bdba5`; SHA256 `167434d87307336227a3bc798192def6b4be285ab4df7cdb09c7a96e86bdf6f1`, 25,973,146 bytes. |
| Repaired debug APK | Same base with the uncommitted `LicensesActivity`/layout/test repair and owning documentation; SHA256 `2212d8ebcb236c2856e015833ac1c1e4c9c5f8d8b899441c84771f9cc1386c61`, 27,989,440 bytes. Source hashes, tracked patch and new notice XML are archived in `apks/100-repaired/`. The operator's APK hash readbacks match this identity. |

The independent original artifact audit compared complete notices against eighteen original source texts and thirty-nine cached JAR/AAR notice entries. Credits, both OFL assets and all three actual font binaries matched the reviewed source. The manifest declares a non-exported license activity; DEX contains it and excludes the removed donation preference. All sixteen native payloads were inventoried: `libwg-go.so`, `libwg.so`, `libwg-quick.so` and DataStore's `libdatastore_shared_counter.so` in each of four configured ABIs. Actual Go metadata matches documented versions. DataStore's allocated ELF sections match its exact source AAR despite stripping. The repaired APK retains every license asset and all sixteen native binaries byte-for-byte. [Software attribution](../license-attribution.md) owns provenance details and remaining distribution gates.

## Original defect and repair

At the original head, one TextView held all 165,908 UTF-16 characters. Native hierarchy captures exposed only the first 100,000 characters even after scrolling; later font and GPL notices were absent from that node's text although the full asset still drew. Independent review traced this to Android 16 [TextView's accessibility text](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/widget/TextView.java#15129) and [TextUtils.trimToParcelableSize](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/text/TextUtils.java#2299). Original failed-head evidence is retained. The defect concerns standard node text; it does not claim every possible TalkBack reading mode was experimentally unavailable.

`LicensesActivity.onCreate` now renders the existing 27 top-level notices as separate selectable LTR TextViews inside the existing ScrollView. Their exact text, whitespace and order reconstruct the unchanged asset. The largest notice is 35,350 characters, below the framework cap. Header tokens, safe-area handling, native Back, lifecycle-bound off-Main reading and localized I/O failure handling stay with their existing owners. Select all/copy now cover the current notice.

`LicensesActivityTest.everyFullNoticeReachesAccessibilityWithoutParcelTruncation` first reproduces the 100,000-character cap on an unsplit TextView. It then compares every actual framework accessibility node against its full rendered notice and their concatenation against the original asset, including complete font and final GPL notices. All three license tests and twenty-six Settings tests passed without failures/errors/skips on the repaired source; native Detekt passed with zero findings. Authorized repaired assembly plus Detekt passed through `Invoke-CodexQuiet`; its retained worktree log is `20261010-111552-390-5e9ad5d500554290806f85a843fe5e0f.log`. These pre-integration results retain their own source scope; final integrated-head checks and independent ultra acceptance belong to the PR.

## Repaired native evidence

Keyboard traversal reached all 27 notice nodes. `100-repaired-native-traversal.json`, `100-repaired-notice-01.xml` through `-27.xml` and `100-repaired-native-text-check.json` record total text length 165,908 and maximum node length 35,350. Twenty-six complete sections match the asset exactly. UIAutomator replaces nine form-feed characters in the GPL-2.0 section with periods because those characters cannot be serialized into its XML; this is a capture limitation, not a remaining 100,000-character truncation. The framework-node JVM comparison preserves those original characters. Complete Inter Tight and JetBrains Mono OFL text and the final GPL-3.0 notice are available in native nodes.

The repaired six-case matrix records effective configuration, Back labels/bounds, scroll bounds and a focused complete final notice. Each case has native first/last PNG and XML captures, retained at original output sizes.

| Effective configuration | Native capture boundary |
| --- | --- |
| 320×640dp English portrait, system light, 1× text | First and final notices. |
| 320×640dp English portrait, system dark, 1× text | First and final notices. |
| 640×320dp English landscape, system dark, 1× text | First and final notices. |
| 640×320dp English landscape, system dark, 2× text | First and final notices; 410px/156dp scroll viewport remains. |
| 320×640dp Arabic RTL portrait, system light, 2× text | Actual `ldrtl`; first and final notices, legal text remains LTR. |
| 320×640dp Arabic RTL portrait, system dark, 2× text | Actual `ldrtl`; first and final notices, legal text remains LTR. |

Back is 126×126px at density 420, equivalent to 48×48dp, with an English or Arabic accessibility label. Arabic places it on the right; the scrollable legal payload stays LTR. The selected locked dark palette remains consistent under both system modes. These are scoped native captures, not a complete device/theme support matrix.

The native selection toolbar exposes Select all and Copy. Ctrl+A and Back while selection was active did not exit the activity; selection dismissal remained separate from navigation. Clicking `licenses_back` returned to Settings, and the nonpersistent preference reopened the license screen. Correct native Settings preference navigation was used in all six configurations. An attempted direct `run-as am` launch was rejected with `SecurityException` for the private activity; its failed helper evidence is preserved and is not a product defect. Selection-toolbar visibility does not establish clipboard contents, and keyboard traversal is not TalkBack speech/traversal.

## Acceptance and remaining limits

The repaired APK satisfies this scoped native node-text, notice reachability, selection-control and navigation boundary. Final merged-head source review, integrated repository checks, actual CI and a fresh final-artifact route smoke remain separate PR gates; this record does not approve or report a merge, push, release or deployment.

Actual TalkBack speech/reading/actions, clipboard readback, physical/OEM/minimum-API coverage and injected asset-I/O/cancellation failures were not executed. Applicable G4 requirements remain under the [QA plan](../qa-test-plan.md#remaining-g4-device-acceptance). Release/googleplay/shrunk content, file-specific and toolchain-runtime notices including DataStore's native contribution, ambiguous source declarations and applicable corresponding-source/relinking obligations remain distribution gates. Original font binary download/build provenance is not established. Fork NOTICE/modification audit, product/package identity, updater, tools coexistence and complete old/new-app transfer acceptance remain #71 work.
