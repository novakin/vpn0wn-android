# DataStore native license and provenance boundary — #71

Last updated: 2026-10-11 (Europe/Paris).

## Scope and evidence

This read-only assessment clarifies the remaining DataStore gate in [attribution guidance](../license-attribution.md). The [composed release audit](2026-10-11-release-artifact-acceptance.md) remains pinned to fork source `15eb25239d876579ab60609efcc35413dde589f3` and unsigned release/googleplay APK SHA256 `fd8d37a2cb9d80cc197db9da3cbdc289ca1079b49b433f0b124c5d8ff3ac2ab6`. Its four packaged `libdatastore_shared_counter.so` payloads match the allocated ELF sections in `androidx.datastore:datastore-core-android:1.2.1`; the exact AAR SHA256 is `435edad7bcb1fbb1a2a46de7be4d6daf299479b3328ebf757ebdfe02810cbdd8`. No new APK, native build, installation or distribution ran for this assessment.

The [versioned Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core-android/1.2.1/datastore-core-android-1.2.1.pom) declares Apache 2.0. The published source JAR carries Apache source declarations but omits native C++ sources. The separately retained release-associated candidate `03aea68c431abd3fa436e9f8fa9b9cda12f18334` declares Apache 2.0 in its [counter header](https://android.googlesource.com/platform/frameworks/support/+/03aea68c431abd3fa436e9f8fa9b9cda12f18334/datastore/datastore-core/src/androidMain/cpp/shared_counter.h), [counter implementation](https://android.googlesource.com/platform/frameworks/support/+/03aea68c431abd3fa436e9f8fa9b9cda12f18334/datastore/datastore-core/src/androidMain/cpp/shared/shared_counter.cc) and [JNI implementation](https://android.googlesource.com/platform/frameworks/support/+/03aea68c431abd3fa436e9f8fa9b9cda12f18334/datastore/datastore-core/src/androidMain/cpp/jni/androidx_datastore_core_SharedCounter.cc). This candidate is distinct from verified publisher checkout/toolchain metadata for build `14975342`; its headers do not establish exact native-source correspondence.

## Distribution conditions and engineering provenance

[Apache 2.0 section 4](https://www.apache.org/licenses/LICENSE-2.0#redistribution), also retained in [COPYING](../../COPYING), permits redistribution “in Source or Object form” subject to its applicable license-copy, modification-notice, source-attribution and NOTICE conditions. Apache 2.0 alone imposes no corresponding-source delivery, exact compiler reproduction or byte-identical reconstruction requirement for the identified DataStore contribution. Keep its full Apache notice and apply relevant conditions to the actual distribution.

Actual embedded header/runtime contributions retain their own terms. Compiler strings and the candidate source declarations do not establish the complete contributor/license set for the packaged native payloads. That targeted attribution gate remains open; this assessment adds no notice, applies no toolchain exception and certifies no complete distribution compliance.

The next distribution check is to identify actual embedded native header/runtime contributions and retain their applicable notice text or verify the applicable license exception. Exact publisher checkout, Konan/sysroot/compiler identity and native reproduction can strengthen engineering provenance when needed. Track that proof separately from the identified DataStore license's distribution conditions; an unresolved build recipe alone does not create a new Apache source-delivery obligation.

## Remaining gates

The [native source/relink record](2026-10-11-native-source-preparation.md) and [desugar source preparation](2026-10-11-desugar-source-preparation.md) retain their original scopes. Applicable corresponding-source/relinking obligations for other packaged components, recipient usability/delivery and final signed/distributed-artifact acceptance remain open. No DataStore result changes those component-specific boundaries or completes [#71](https://github.com/novakin/vpn0wn-android/issues/71).

This dated assessment, attribution guidance and the related desugar record carry the clarification. Historical source/material proof, event dates and revisions are preserved. Product behavior, dependency pins, license payloads, setup, UI and operational policy remain unchanged; backend changelog, UI roadmap, architecture and agent instructions need no behavior entry. The independent final-head review and repository checks belong to the delivering PR.
