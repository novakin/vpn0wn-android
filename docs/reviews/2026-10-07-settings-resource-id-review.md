# Settings resource-ID investigation and source repair

Last updated: 2026-10-10 (Europe/Paris).

## Scope and observed symptom

Partial [#34 Settings](https://github.com/novakin/vpn0wn-android/issues/34) / [G4 #52](https://github.com/novakin/vpn0wn-android/issues/52) follow-up from clean master `4044ede4d586060c78213629b6fcb9a1ac0cd782`. The [candidate-device record](2026-10-07-g4-candidate-device-review.md) observed nine `Invalid resource ID 0x00000000.` messages after Settings opened on Pixel 10a, Android 16/API 36. Startup had none in its scoped capture. The phone was disconnected during this source investigation; no new APK, device or network operation occurred at that boundary. Subsequent candidate testing is recorded separately below.

## Source mechanism and decision

- Pinned [AndroidX Preference 1.2.1 source](https://dl.google.com/dl/android/maven2/androidx/preference/preference/1.2.1/preference-1.2.1-sources.jar): `Preference` defaults `mViewId` to `0`; `onBindViewHolder` assigns it to the row root. The public `setViewId` API owns that assignment. XML root IDs alone would be overwritten.
- [Android 16 View source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/core/java/android/view/View.java): `onProvideStructure` resolves resource names for IDs other than `View.NO_ID` or generated IDs. Assist, autofill and content capture share this path. Zero reaches `getResourceEntryName` and throws `Resources.NotFoundException`.
- [Android 16 resource source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/libs/androidfw/AssetManager2.cpp): `FindEntry` logs `Invalid resource ID` for invalid resource IDs before returning failure.

`SettingsFragment.onCreatePreferences` now initializes category and child row IDs to `View.NO_ID` through `Preference.setViewId` after inflation. These roots have no declared resource identity. Child resource IDs, preference keys, RecyclerView stable item IDs, stored values, filtering, layout, artwork and native actions remain intact. No custom adapter, dependency upgrade, generated identity, logging suppression or capture disabling is needed.

This is a reproduced source mechanism consistent with the phone symptom. At this source-only boundary, the earlier device capture had no caller stack, so the precise physical capture service and disappearance of all nine messages were not yet established.

## Regression and limits

`SettingsActivityTest.preferenceStructuresHaveNoInvalidResourceIdsAfterRecreation` runs API 36 with the existing synthetic kernel backend fixture. It binds and lays out every real `PreferenceGroupAdapter` row, captures the Android framework assist structure and checks roots/descendants for invalid ID zero before and after recreation. Each adapter must enable stable IDs; bound holders must have a non-`NO_ID` item identity matching that adapter. This does not assert ID equality across separate adapter instances. The initial regression fails against unchanged production with `Invalid resource ID in android.widget.LinearLayout. Actual: 0` and passes after the owner fix.

Focused Settings tests and Detekt cover the affected source; the source PR owns final-head results, independent ultra review and integration. Existing native raster checks retain supported layout configurations. JVM source/structure proof does not establish physical accessibility, content-capture service behavior, actual kernel support or the complete Settings/device matrix.

The source-only handoff required a verified in-place candidate install and scoped Settings/launch/navigation checks while preserving personal data. The subsequent section records that bounded warning confirmation. Keep #34/#52 open until their complete acceptance. No speed, battery or DPI benefit is claimed; benchmark recovery and repeated measurements remain separate.

## Candidate phone confirmation — 2026-10-07 (Europe/Paris)

After the source investigation above, the maintainer reconnected and unlocked Pixel 10a. PR #59 merged the ultra-accepted source head `79ee3880` at `5fa07cd0fe8e1ac5292dd4127ab51a76f4c8efdb`; the merged tree matches reviewed source. Final-head and master CI passed. Continuing the explicit G4 build/device authorization, [CI run 37612635025](https://github.com/novakin/vpn0wn-android/actions/runs/37612635025) passed 246 tests with zero failures/errors/skips and zero Detekt findings, then assembled the merged revision.

Downloaded debug artifact `11479710006` expires `2026-10-14T11:17:40Z`. Its revision file and SHA256 match the downloaded APK: `76d6a6926d18da17133764518fc58848e85eaeb1996088bb9b53f51bb1f3b9d4`. The untouched CI artifact remains local. A separate copy signed with the existing matching WSL debug key has SHA256 `04332f97f0013ea64e2ecca258f1f6d97c05689deeea61452a03361d825c4075`; signature verification and comparison of all 1,018 non-`META-INF` payload entries passed. `adb install -r` succeeded without uninstall/data clear, and installed-APK pullback matches this copy. This is a local debug candidate, not a release.

| Physical check | Observed result | Boundary |
| --- | --- | --- |
| Previous installed candidate `4e2f473d` | Pullback matches its earlier recorded hash. Two Settings openings each yielded nine ID-zero errors; the repeated opening has a fresh timestamp-scoped log. | The first retained log is unscoped; only the repeated opening supplies the controlled baseline. |
| New candidate Settings | Three confirmed openings yielded zero ID-zero errors. Their scoped logs also had no disk/socket StrictMode warnings; all nine visible native title strings matched the previous candidate. | One intervening navigation attempt captured Home and timed out on its animation; it is excluded from Settings acceptance. The first screenshot caught an activity transition, while its later hierarchy reached Settings. |
| Recreation/output | Landscape recreation yielded zero ID-zero errors and retained the labeled Back control. Settled landscape and restored portrait screenshots were inspected; supported native rows/header remained usable. | These are two current configurations, not a new complete theme/font/RTL/TalkBack matrix. Earlier captures retain their original builds. |
| Native navigation | Header Back returned to Home between openings; the log preference opened `LogViewerActivity`, and system Back returned to Settings. | No switch, tile-add/export callback, root/kernel action or log/config export was exercised. |
| Post-install launch | The scoped explicit MainActivity launch capture contained no ID-zero or disk/socket StrictMode warning. | This does not cover the complete package-replacement initialization or independently establish a fresh preflight path. |

The original installed APK and a validated private app-file archive were retained before replacement. Immediate and final comparisons preserve the file set and personal config/preferences; only Android's `profileInstalled` runtime marker changed. An initial strict comparison caught that marker change; it was classified, not restored. Nine checked device values match their initial state, including rotation, font scale, night mode, accessibility, locale, timeout and stay-awake policy. The phone reports AC charging despite its ADB cable; the temporary awake setting was corrected for that power type and restored. Owned remote screenshots/hierarchy files were removed. Private archives, APKs, raw logs and captures remain ignored local evidence.

The final inspected surface is the normal tunnel list with the original profile shown active. No manual tunnel toggle, profile/config/preference mutation, new synthetic profile, server operation or load test occurred. LTE and charging were observed; no speed/energy baseline or gain is claimed. Phone testing for this warning slice is complete.

The observed warning removal is now confirmed for these Settings opens and recreation. The precise physical capture service remains untraced without a caller stack. #34/#52 stay open for their remaining native/accessibility/provider/peer and controlled performance boundaries; this confirmation does not complete G4. Independent ultra review and final documentation publication belong in the follow-up PR acceptance record.
