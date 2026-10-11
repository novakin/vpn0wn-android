# Selected desugar Maven source and notice material — #71

Last updated: 2026-10-11 (Europe/Paris).

This E6 record adds selected build-dependency archive material to the [official desugar source preparation](2026-10-11-desugar-source-preparation.md). It retains thirteen exact-coordinate source JARs and inventories fourteen selected binary JARs. The source collector and author verifier both exited 1: the exact selected `listenablefuture` source classifier and checksum returned HTTP 404. Material coverage remains incomplete; [#71](https://github.com/novakin/vpn0wn-android/issues/71) stays open under the [authorized improvement plan](../improvement-plan.md#authorized-open-issue-delivery--2026-10-09).

## Scope and identity

The host collection used clean master/origin `b75846c0d54c450e9f0cef81dc98575dfa216903` on 2026-10-11 (Europe/Paris). The associated desugar library source remains `73170c345e6a762fc6a1f0301bb15218850023ef`. The earlier unsigned release/googleplay audit remains at `15eb25239d876579ab60609efcc35413dde589f3`; no new APK or release was produced. This preparation does not change either historical revision's scope.

The retained Coursier graph selects fourteen JARs. `dep-tree.json` SHA256 is `400fe4d69e5bb23b40af752c506369b84fd9a49dc2db751c044fe6cac4daad8a`; `runtime-result.json` is `a93627b74bea0caae5eca6e944fed1019154d83d672909a89d7b749ab6e35320`. All 225 frozen cache files, including 31 POMs, remain unchanged. Selected Guava is **31.0.1-jre**, not the recipe's historical/direct 23.0. Guava 23.0 bytes and superseded descriptors remain separate preserved evidence; no resolver rerun or version substitution occurred.

| Selected coordinate | Exact source material |
| --- | --- |
| `com.google.auto:auto-common:1.2.1` | Original retained source reused. |
| `com.google.auto.value:auto-value:1.8.2` | Original retained source reused. |
| `com.google.auto.value:auto-value-annotations:1.9` | Original retained source reused. |
| `com.google.code.findbugs:jsr305:3.0.2` | Original retained source reused. |
| `com.google.guava:guava:31.0.1-jre` | Exact published source retrieved. |
| `com.google.errorprone:error_prone_annotations:2.7.1` | Exact published source retrieved. |
| `com.google.guava:failureaccess:1.0.1` | Exact published source retrieved. |
| `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` | Source and `.sha1` unavailable: original attempts 7 and 8 both returned HTTP 404. |
| `com.google.j2objc:j2objc-annotations:1.3` | Exact published source retrieved. |
| `org.checkerframework:checker-qual:3.12.0` | Exact published source retrieved. |
| `org.ow2.asm:asm:9.0` | Original retained source reused. |
| `org.ow2.asm:asm-commons:9.0` | Original retained source reused. |
| `org.ow2.asm:asm-tree:9.0` | Original retained source reused. |
| `org.ow2.asm:asm-analysis:9.0` | Exact published source retrieved. |

## Actual source and binary material

The collector ran once against fourteen fixed anonymous HTTPS URLs: seven selected `-sources.jar` paths at `https://repo1.maven.org/maven2/` and their `.sha1` sidecars. Twelve requests succeeded. Seven source JARs were reused without downloading them; six were retrieved. Every available source matches its published SHA1, retained SHA256, selected coordinate and checked binary link. These are exact receipt/byte associations, not source-to-binary reproduction. There was no redirect, fallback repository, retry or alternate version; both missing-classifier attempts remain in their original receipts.

Recomputed source inventories contain **1,307 regular members, 1,266 Java members and zero duplicate names** across the thirteen available archives. The complete named notice candidate is `META-INF/LICENSE.txt` in checker-qual 3.12.0: 1,126 bytes, SHA256 `e90eaa08dea089df10f2bf7f9dba63a5f6237c01562c4741e2e86773e78582d5`. Its copyright, MIT permission, condition and disclaimer are retained byte-for-byte under controlled `notices/10-sources-01.txt`.

The separate selected-binary inventory covers all fourteen JARs, **3,963 regular members and zero duplicate names**. Auto-value 1.8.2 and checker-qual 3.12.0 each contain `META-INF/LICENSE.txt` with those same complete Checker Framework MIT bytes; their controlled copies are `notices/02-binary-01.txt` and `notices/10-binary-01.txt`. Auto-value also has 1,110 `autovalue/shaded/` class entries. Its retained `auto-value-parent:1.8.2` POM declares `guava.version` as 30.1.1-jre, distinct from externally selected Guava 31.0.1-jre. The external graph and thirteen source archives therefore do not establish the shaded content's exact source/version or full notice closure. Absence of a named notice in another archive does not establish absence of obligations.

Targeted source inspection also finds an unresolved declaration boundary in jsr305 3.0.2. `javax/annotation/concurrent/{GuardedBy,Immutable,NotThreadSafe,ThreadSafe}.java` each retains copyright 2005 Brian Goetz and a Creative Commons Attribution 2.5 header reference; its published POM declares Apache 2.0. The selected binary has all four corresponding class paths and no named notice candidate. Full terms, declaration reconciliation and their application remain open. This build-dependency finding does not establish that these classes ship in the APK or justify silently applying the POM's blanket declaration.

The selected listenablefuture JAR is 2,199 bytes, SHA256 `b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99`. Its only regular members are `META-INF/MANIFEST.MF`, `META-INF/maven/com.google.guava/listenablefuture/pom.xml` and `META-INF/maven/com.google.guava/listenablefuture/pom.properties`; it contains no class file. This inspects the actual metadata-only payload rather than inferring emptiness from its version name. It does not turn the source-publication 404 into success or establish zero licensing obligations.

## Verification and retained evidence

Before collection, independent ultra review rejected four helper defects: followed redirects, optional inventory proof, unbound material provenance and incomplete preservation coverage. The original preparation/scripts remain under `rejected-preparation-v1/`. The corrected exact helpers passed a separate preexecution ultra review, including fifteen redirect-refusal and twelve verifier-guard controls. That verdict covers execution readiness, not the later material result.

Collector SHA256 is `56facff064200453c5b2f2954089126d154a463cefec0ade6edcc9f6f799768a`; verifier SHA256 is `d335147d92f3cda7724fad5f50b566e3b7c5d62180667254d517df227d5c610f`. The author verifier records **zero consistency findings, `materialComplete: false`**. Missing source/checksum coverage alone keeps its result incomplete. All **565** protected source-task inputs match their same-path before/after/current hashes; the binary inventory independently preserves all **530** of its frozen inputs. These scoped preservation sets and the 225-file cache retain prior receipts, reviews, failed evidence and historical inputs.

Evidence remains ignored under `.artifacts/overnight-20261011/desugar-selected-source-20261011/` and `.artifacts/overnight-20261011/desugar-selected-binary-notices-20261011/`. The source receipt binds original attempts, source/binary hashes and full member/notice inventories; the binary inventory binds every archive member and copied notice. Paths below are relative to those respective directories.

| Retained record | SHA256 |
| --- | --- |
| Source `receipt.json` | `a24f3b738ac961ff2bde51a9571119721f6d5f4956f747af2dfcac126d2f4e05` |
| Source `verification.json` | `2776cbf0ea97a0fac9c97b297b180939980a257c96bf93b7e39b5b20b6b1f1af` |
| Source `preserved-inputs.before.json` and `.after.json` | `7f853829b1b14a148497430343035c65aea8a68cea976e0ab3ba5e30e2fb164a` |
| Source `HELPER-ULTRA-REVIEW-v2.md` | `b839d4be302b45a0272adf132e101be797f6e428024e83ffa8e8a76208d2face` |
| Binary `binary-inventory.json` | `5dfe8e516517522bcd3bc5583028a8aeb3736483a5427f22f6006d263b21bc8d` |
| Binary `protected-before.json` and `protected-after.json` | `f93d99988ea05dcb99313c65b258aa5795c354260c6abd40605219a3e2115557` |
| Actual-material `INDEPENDENT-MATERIAL-REVIEW.md` | `861393d59ee68ca0e700cdc878930d45ae704ac774b52107db7df5f0cc6f9153` |
| Actual-material `INDEPENDENT-MATERIAL-REVIEW-RECEIPT.json` | `c0c7c46344e60168382333cd03472b95dc16075773876ad328897b6b39113a3a` |

Actual command logs remain under `.artifacts/codex-command-logs/`: collector `20261011-202308-314-ebf62d1c522a428198b3ec406eb8c34e.log` and verifier `20261011-202349-019-730ebc44000f4039af8fe01fb28a7339.log` both exited 1; binary inventory `20261011-202219-831-db586d9ae8c043b2b7a89d3cf08818d2.log` exited 0. Do not rerun these one-shot producers over their preserved receipts.

Fresh independent ultra review recomputed every source/binary member, exact notice copy, original request association and both preservation sets without importing or rerunning the author helpers. It passed integrity, preservation and honest bounded partial material; fourteen-classifier, complete header/shaded/POM, license/distribution and #71 acceptance remain incomplete. Its report and receipt are retained under `.artifacts/overnight-20261011/desugar-selected-material-runtime-ultra-20261011/`. The concrete next gates include the JSR305 per-file declarations, auto-value's relocated component/version coverage, metadata-only ListenableFuture disposition and remaining complete source-header terms. [#158](https://github.com/novakin/vpn0wn-android/issues/158) tracks the newly observed JSR305 declaration/applicability finding after independent ultra review of its issue scope; it does not presume those build-dependency classes ship in an APK. Final-head documentation review/checks and publication acceptance belong in the owning PR.

## Remaining gates and documentation impact

This record supplies archive-byte/member integrity and complete named notice copies only. Full per-header/shaded-component/effective-POM closure, authoritative material for missing source, source correspondence, external Bazel rules, historical JDK/Bazel/API32/R8 producer/transformation, APK method inclusion, recipient usable corresponding-source/relink delivery and final signed/distributed-artifact acceptance remain open. Build-graph membership neither proves APK inclusion nor imposes a blanket distribution obligation on every build dependency. These locally retained notices are build-dependency material, not new shipped credits or a recipient source bundle.

The [attribution guide](../license-attribution.md#source-reconstruction-and-relink-preparation) owns those component-specific boundaries. Earlier source-preparation, notice-copy and 29-notice artifact/runtime records retain their original tested scope. No downloaded content was executed or built; archive inspection copied only complete named notice candidates into controlled filenames. No APK, device, server or distributed delivery operation occurred in this material slice.

Only this record and its owning attribution guide need updates. Product behavior, build configuration, architecture ownership, operational policy, QA procedure, entry points and agent/contribution/template contracts are unchanged; backend changelog, architecture, QA, README, AGENTS and process/templates therefore need no edit. Documentation checks do not establish runtime or distribution acceptance, and this partial E6 evidence does not close #71.
