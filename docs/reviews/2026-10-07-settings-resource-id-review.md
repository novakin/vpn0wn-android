# Settings resource-ID investigation and source repair

Last updated: 2026-10-07 (Europe/Paris).

## Scope and observed symptom

Partial [#34 Settings](https://github.com/novakin/wireguard-android/issues/34) / [G4 #52](https://github.com/novakin/wireguard-android/issues/52) follow-up from clean master `4044ede4d586060c78213629b6fcb9a1ac0cd782`. The [candidate-device record](2026-10-07-g4-candidate-device-review.md) observed nine `Invalid resource ID 0x00000000.` messages after Settings opened on Pixel 10a, Android 16/API 36. Startup had none in its scoped capture. The phone is disconnected for this source investigation; no new APK, device or network operation is claimed.

## Source mechanism and decision

- Pinned [AndroidX Preference 1.2.1 source](https://dl.google.com/dl/android/maven2/androidx/preference/preference/1.2.1/preference-1.2.1-sources.jar): `Preference` defaults `mViewId` to `0`; `onBindViewHolder` assigns it to the row root. The public `setViewId` API owns that assignment. XML root IDs alone would be overwritten.
- [Android 16 View source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/core/java/android/view/View.java): `onProvideStructure` resolves resource names for IDs other than `View.NO_ID` or generated IDs. Assist, autofill and content capture share this path. Zero reaches `getResourceEntryName` and throws `Resources.NotFoundException`.
- [Android 16 resource source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/libs/androidfw/AssetManager2.cpp): `FindEntry` logs `Invalid resource ID` for invalid resource IDs before returning failure.

`SettingsFragment.onCreatePreferences` now initializes category and child row IDs to `View.NO_ID` through `Preference.setViewId` after inflation. These roots have no declared resource identity. Child resource IDs, preference keys, RecyclerView stable item IDs, stored values, filtering, layout, artwork and native actions remain intact. No custom adapter, dependency upgrade, generated identity, logging suppression or capture disabling is needed.

This is a reproduced source mechanism consistent with the phone symptom. The earlier device capture has no caller stack, so the precise physical capture service and disappearance of all nine messages are not yet established.

## Regression and limits

`SettingsActivityTest.preferenceStructuresHaveNoInvalidResourceIdsAfterRecreation` runs API 36 with the existing synthetic kernel backend fixture. It binds every real `PreferenceGroupAdapter` row, captures the Android framework assist structure, checks roots/descendants for invalid ID zero and retains stable adapter identity across recreation. The test fails against unchanged production with `Invalid resource ID in android.widget.LinearLayout. Actual: 0` and passes after the owner fix.

Focused Settings tests and Detekt cover the affected source; the current PR owns final-head results, independent ultra review and integration. Existing native raster checks retain supported layout configurations. JVM source/structure proof does not establish physical accessibility, content-capture service behavior, actual kernel support or the complete Settings/device matrix.

Reconnect the authorized phone, install a verified candidate in place while preserving personal data, then repeat scoped Settings/startup captures and native navigation/accessibility checks. Keep #34/#52 open until their complete acceptance. No speed, battery or DPI benefit is claimed; benchmark recovery and repeated measurements remain separate.
