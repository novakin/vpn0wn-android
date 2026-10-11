# Official desugar source material preparation — #71

Last updated: 2026-10-11 (Europe/Paris).

This scoped host record supplies the repository-owned source material missing from the retained-only inventory for desugar source `73170c345e6a762fc6a1f0301bb15218850023ef`. It adds no source build, release transformation, APK method correspondence, recipient delivery or distribution acceptance. The [earlier native-source record](2026-10-11-native-source-preparation.md#other-source-inquiries-and-remaining-gates) owns the raw-entry/Maven linkage and separate native-tools reconstruction/relink proof. [#71](https://github.com/novakin/vpn0wn-android/issues/71) remains open under E6.

## Artifact and source identity

The inquiry remains associated with the audited unsigned release/googleplay artifact from source `15eb25239d876579ab60609efcc35413dde589f3`, APK SHA256 `fd8d37a2cb9d80cc197db9da3cbdc289ca1079b49b433f0b124c5d8ff3ac2ab6`. No new APK was assembled or installed. The host preparation used clean master `2cf620f32ae1d7dddb6ede651fd8e66dc8fe134e`; that current checkout does not change the audited artifact's revision.

The official [R8 dependency bundle](https://storage.googleapis.com/r8-deps/e4c206c7bdfe1415cd2716a1d59d699a80bc63dd) pins the selected library source. Fresh official commit/tree metadata agree with that retained pin. The complete recursive tree reports no truncation.

| Input | Verified value |
| --- | --- |
| Source commit | [`73170c345e6a762fc6a1f0301bb15218850023ef`](https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef) |
| Source tree | `a1405d773ff0bb771128842cc87b890f5da593a7` |
| Official archive | [Pinned tar.gz](https://codeload.github.com/google/desugar_jdk_libs/tar.gz/73170c345e6a762fc6a1f0301bb15218850023ef) |
| Archive SHA256 | `4cd2faa88ecb2450522fd4f0dcd11d6df878d7197b2276fb35532c50267f7a64` |
| Verified inventory | 9,124 regular files and 1,033 directories |

Every regular file's Git blob identity was independently recomputed against the official tree. File inventory and executable modes agree. Root BUILD, version/dependency metadata and the previously isolated Duration source also match the retained bytes. The archive was inspected without extracting or modifying it.

## Material supplied and preserved

The pristine archive contains library sources/notices, WORKSPACE, root/selected/addon/stub/config BUILD files, repository/setup rules, the Maven generator and all six Java selector sources. The selected java.base glob contains 3,047 Java files. Source declarations select `--config=d8_desugar` and merge filesystem/libcore addons; this is inspected build intent, not executed class selection.

The [historical R8 preparation candidate](https://r8.googlesource.com/r8/+/dfc1c9c76304c7c935a3bb9083863c772378112c/tools/desugar_jdk_libs_update.py) removes `DesugarDateTimeFormatterBuilder.java` before building. The original file remains in this pristine archive; the inspected deletion would leave 3,046 files in that glob. No deletion was performed, and the candidate's availability does not establish that it produced release 2.1.5.

Four external rule archives have version/revision and SHA256 declarations: bazel_skylib 1.4.2, rules_java `7cf3cefd…`, rules_cc `0d5f3f27…` and rules_jvm_external 4.5. Eight direct Maven build coordinates have version declarations in setup.bzl. Those declarations are inventoried; their external/transitive bytes, sources and notice closure are not supplied by the repository archive. It contains no Maven lock, `.bazelversion` or `.bazelrc`. Java 11 output requirements do not identify exact compiler/JDK/Bazel binaries; Python and POSIX packaging tools remain external.

## Verification and retained evidence

Bounded HTTPS retrieval, complete archive/tree verification and final preservation readback completed with exit 0. All 128 earlier inputs/receipts were rehashed unchanged. A separate read-only material review confirmed the repository-owned graph and external limits. No compiler, transformation, relink, source extraction, device/server operation, global configuration change or GitHub write occurred during preparation.

Ignored evidence is retained under `.artifacts/overnight-20261011/release-license-gap-audit/source-delivery/desugar-source-prep-73170c34-20261011/`: fetch metadata, official commit/tree responses, archive, full file/hash manifest, source inventory and final readback. The source inventory SHA256 is `6908abe2dca42f65eee5b559d76e3eca696aa4aac675d327ebbb5cbd54d913ce`. Command logs `20261011-144406-089-7d537be4e2ff43718750f3c339109022.log` and `20261011-144602-269-b1cbb8cae0eb46fb9d433a912fdfd243.log` retain fetch/verifier results. Do not rerun one-shot producers over these receipts. These private retained materials are not a published recipient source bundle.

Final independent ultra review and its findings/resolution belong in the owning PR acceptance record. This source/material preparation supplies no second-account approval or permission to distribute.

## Remaining gates

Complete prepared-source/build-toolchain closure remains open: external rules/Maven inputs, historical Android API32 input, R8 helper/build/test/Undesugarer material and exact producer invocation are unestablished. The freshly fetched library archive does not link the historical R8 candidate to the release transformation. Existing raw-entry/Maven comparisons retain their original scope; source reproduction and method-level correspondence to the actual APK remain unproved.

DataStore's [native license boundary](2026-10-11-datastore-license-boundary.md) keeps its exact publisher source/toolchain/native recipe unverified as engineering provenance; actual embedded contributor/license coverage remains the relevant distribution inquiry. Recipient usability/delivery, final signed/distributed-artifact acceptance, root-tools coexistence and complete cross-app migration remain open. [Attribution guidance](../license-attribution.md#source-reconstruction-and-relink-preparation) and the [improvement plan](../improvement-plan.md#authorized-open-issue-delivery--2026-10-09) retain those boundaries. No new missing notice, legal certification or release readiness is inferred from this inventory.
