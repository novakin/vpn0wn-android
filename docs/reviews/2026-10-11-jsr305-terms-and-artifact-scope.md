# JSR305 concurrent-annotation terms and artifact scope — #158

Last updated: 2026-10-12 (Europe/Paris).

This E6/#71 follow-up resolves the source-declaration interpretation and inspected-artifact disposition for four selected jsr305 3.0.2 annotations under [#158](https://github.com/novakin/vpn0wn-android/issues/158). It preserves the explicit CC BY 2.5 grant alongside the Apache 2.0 package POM. Actual APK, retained shrinker and exact class-structure evidence indicate no app-credit repair for these four definitions in the inspected artifacts. Applicable recipient source/build terms are documented below; broader contributor/source-correspondence and identity/distribution gates remain under [#71](https://github.com/novakin/vpn0wn-android/issues/71). Final-head review, checks and issue delivery state belong in the scoped PR.

## Scope and original material

Host inspection used clean master `d54cf2b171d882a98ccf9d099ba2b2d1823bfc0d` on 2026-10-11 (Europe/Paris), following the [selected Maven material audit](2026-10-11-desugar-selected-material.md). No dependency change, APK build, device/server operation, new app-credit asset or recipient source bundle was produced. Original archives, headers and earlier receipts remain unchanged.

| Exact jsr305 3.0.2 material | Bytes | SHA256 |
| --- | --- | --- |
| Published source JAR | 18,102 | `1c9e85e272d0708c6a591dc74828c71603053b48cc75ae83cce56912a2aa063b` |
| Selected binary JAR | 19,936 | `766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7` |
| Retained POM, identical to freshly retrieved official 3.0.2 POM | 4,286 | `19889dbdf1b254b2601a5ee645b8147a974644882297684c798afe5d63d78dfe` |

The complete source members and corresponding binary entries are `javax/annotation/concurrent/{GuardedBy,Immutable,NotThreadSafe,ThreadSafe}.{java,class}`. Each original 187-byte header is identical, SHA256 `126a1126efb3c2ec7abe38fbb520ca6459525ac353bf0b259d78d759cc75b58e`. Complete source, header and class copies retain their individual byte counts/hashes in the local collection receipt; no header was replaced with the POM declaration.

## Declaration disposition and supplied-copy terms

The four headers name copyright 2005 Brian Goetz and expressly refer to Creative Commons Attribution 2.5. The [official exact-version POM](https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom) declares Apache 2.0 but supplies no file-specific relicense explanation. Honor the explicit per-file grant for these covered copies; package metadata alone establishes neither an override nor exclusive or dual terms for them. This disposition does not classify every JSR305 file as CC BY 2.5.

[Original JSR305 import material](https://chromium.googlesource.com/external/jsr-305/+/151fd3976210c6b79117e30b02aee86a20ff5c7f/) and [JCIP's annotation source](https://jcip.net/jcip-annotations-src.jar) provide provenance. Current JCIP headers add Tim Peierls and republication wording, differing from the selected 3.0.2 bytes. They cannot replace the exact selected headers or establish that the current source JAR reproduces the selected binary.

The [official CC BY 2.5 legal code](https://creativecommons.org/licenses/by/2.5/legalcode), especially section 4, requires a license copy or URI, intact applicable notices and reasonable supplied-author/title/URI attribution when covered material is distributed or publicly performed. Adaptations also need the applicable derivative credit. Preserve the exact original notices and supply the applicable attribution/license material at the existing delivery owner if a recipient source/build bundle contains these files, classes or adaptations. CC BY 2.5 does not by itself require the whole app to use that license or impose a corresponding-source delivery requirement. Local retained research copies do not prove any recipient delivery.

## Actual APK definition and reference checks

The target descriptors were `Ljavax/annotation/concurrent/GuardedBy;`, `Immutable`, `NotThreadSafe` and `ThreadSafe` under the same package. Direct scans validated each DEX header, file size, checksum/signature and class-definition/type-ID tables. Android SDK `apkanalyzer dex packages --defined-only` independently checked complete definition counts and each target's definition absence; `MainActivity` was a positive definition control. Definition and reference evidence are separate.

| Retained artifact | Recorded source | DEX files / definitions | Target original definitions / type IDs |
| --- | --- | --- | --- |
| Unsigned release | `15eb25239d876579ab60609efcc35413dde589f3` | 2 / 2,150 | 0 / 0 for each target |
| Unsigned googleplay | Same source and bytes as release | 2 / 2,150 | 0 / 0 for each target |
| Disposable installed-base debug | `66390e5ae3190b4741871d70eaaadacedec50943` | 17 / 10,845 | 0 / 0 for each target |

Both unsigned artifacts have SHA256 `fd8d37a2cb9d80cc197db9da3cbdc289ca1079b49b433f0b124c5d8ff3ac2ab6`; the debug APK has SHA256 `86f8231b31b9eb9dc234fcb3507e6b9f3887b85c33253fdd59a79399e25f0a31`. Six actual SDK CLI calls exited 0. Twelve protected inputs were unchanged before/after inspection.

Compile-only membership and original-name scans alone would leave applicability uncertain. The bounded follow-up below checks the retained production configuration and exact class structure. The later debug result still has a different source revision and does not prove historical release equality. No current-master, final signed/distributed, release-reproduction or universal material-absence claim follows.

### Retained shrinker and annotation-class follow-up — 2026-10-12

The exact historical release/googleplay `mapping.txt`, `configuration.txt` and `usage.txt` match the original `15eb2523` artifact-audit hashes and are byte-identical across the two variants. Configuration line 4 is `-dontobfuscate`; the mapping declares R8 9.1.31 and minimum API 24. No target occurs in the retained mapping/configuration/usage. The source at both inspected revisions selects jsr305 3.0.2 as `compileOnly(libs.jsr305)`. These files are not a complete source-to-producer input manifest, but the actual no-obfuscation policy supplies no basis for a renamed-definition explanation of the APK absence.

Installed JDK `javap` inspection of the exact selected binary identifies all four classes as public abstract annotation interfaces, with zero fields and no `Code` attributes. `GuardedBy` has only abstract `String value()`; the other three have no methods. Thus these definitions contain no concrete implementation body to inline. The complete 8,584-byte output has SHA256 `bb547db359b7079f3fc04c8bf2402a2a73af49663c02339e63f57a3487125aaa`; the actual command exited 0.

Combined with absence of every target definition/type reference in the inspected APKs, this supports leaving app-credit bytes unchanged for these four definitions. It does not classify other JSR305 files, establish general relocated/adapted-material absence, reproduce the APK or discharge recipient source/build-copy obligations. Reassess when relevant dependencies, packaging or supplied material change.

## Retained evidence and remaining gates

The local terms collection is `.artifacts/overnight-20261011/jsr305-terms-20261011/`: collection receipt SHA256 `446f0a6336dd0c2911418ca1042a68ff3edaf589fa1c7e984b4d99dee737b462`, original-import receipt `692ad00b28da6d2df615a231139b0268621cf62c45bd4689ec5d48c50850d0b8`, scoped disposition `ba3890a0af0c161658d0d185378a7087ed245148206abb147039b8cf18d2d461`. The APK inspection receipt is `.artifacts/overnight-20261011/jsr305-app-inclusion-20261011/receipt.json`, SHA256 `86b16c0ddb03c74a640f138adb25533bb903ad012a3c4f1d697419f4a2dc30eb`. Raw collections and private host paths remain local rather than entering source distribution.

Independent terms-material verification recomputed exact copies, original-import associations and authoritative response bodies, preserving five original inputs and forty-six earlier evidence files. Its receipt SHA256 is `edb2f9d9b273842ced179604d2ef5cd98612d07bbb2e5df03746c60b976f92e6`; report SHA256 is `ca0ad74239fb8528a36eef57e80da36058cc9493485a1f7850a5926240b41399`. The fresh supplemental `jsr305-app-inclusion-20261011/R8-SCOPE-READBACK.json` has SHA256 `ecc5807125e20bbfa4347a8e2f89bf1546d59e1c9a690e9699541e886af467c4`; its verification command exited 0. Earlier receipts and their original scope remain unchanged.

The #158 disposition is bounded to these four exact declarations, inspected artifacts and conditional recipient-copy handling. Broader source correspondence, other contributor coverage and final source/artifact recipient delivery remain #71 gates. Final-head independent ultra review, documentation checks and actual publication state belong in the scoped PR acceptance record; this record does not grant merge or release permission.
