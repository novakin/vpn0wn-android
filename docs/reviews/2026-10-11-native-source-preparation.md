# Native source reconstruction and relink preparation — #71

Last updated: 2026-10-11 (Europe/Paris).

## Scope and result

**PASS for local reconstruction and modification/relink/restoration of eight native tools.** This record continues the [composed unsigned artifact audit](2026-10-11-release-artifact-acceptance.md) and [attribution guidance](../license-attribution.md). Source and artifact remain pinned to `15eb25239d876579ab60609efcc35413dde589f3`, not the later documentation head. The identical unsigned release/googleplay APK has SHA256 `fd8d37a2cb9d80cc197db9da3cbdc289ca1079b49b433f0b124c5d8ff3ac2ab6`.

Only `libwg.so` and `libwg-quick.so` across `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` were rebuilt. These are executables packaged with `.so` filenames; this does not reproduce `libwg-go.so`, DataStore, the whole APK or its signing. No rebuilt Android target was executed, installed or distributed. Host build tools necessarily ran. [#71](https://github.com/novakin/vpn0wn-android/issues/71) remains open.

## Exact source inputs

The retained fork Git archive supplies the pinned app-facing [CMake](https://github.com/novakin/vpn0wn-android/blob/15eb25239d876579ab60609efcc35413dde589f3/tunnel/tools/CMakeLists.txt), compatibility source/headers and build inputs. Separate archives supply [wireguard-tools `e2ecaaa739144997ccff89d6ad6ec81698ea6ced`](https://git.zx2c4.com/wireguard-tools/tree/?id=e2ecaaa739144997ccff89d6ad6ec81698ea6ced) and host [elf-cleaner `7efc05090675ec6161b7def862728086a26c3b1f`](https://github.com/termux/termux-elf-cleaner/tree/7efc05090675ec6161b7def862728086a26c3b1f); Git archive does not inline submodules. Extract them at `tunnel/tools/wireguard-tools` and `tunnel/tools/elf-cleaner` beneath the fork source. An exact supplement preserves tools `src/fuzz/.gitignore`, omitted by its archive export rules; it is source-tree metadata rather than a compiler input.

Independent review checked all three archive hashes/embedded Git identities and the 653 archived regular source files plus that supplement. The reconstructed tree's 655-entry manifest also includes its symlink. The tools retain GPL source/license material and the minimized LGPL-2.1-or-later `netlink.h` included through `ipc.c`/`ipc-linux.h`; the helper is statically compiled into `wg`. This preparation adds no dynamic-library refactor or object-only delivery policy.

## Executed native recipe

The Linux/WSL build used NDK `28.2.13676358`, CMake `3.22.1` with its bundled Ninja, Android API 24, `RelWithDebInfo`, flexible page sizes ON and package `com.vpn0wn.android`. Generated compiler records report Clang `19.0.1`. Fourteen compile commands per ABI and the link flags/libraries match the retained historical build records after source-path relocation. These are recorded toolchain identities/recipes, not a hash-attested complete toolchain installation or a cross-host guarantee.

The following recipe expresses the executed existing CMake configuration. Set `native_source` to the verified reconstructed tree and `native_sdk` to the installed Linux SDK; neither path supplies missing source archives. Use a fresh build/output root, run noisy commands through the [development command-log workflow](../development.md#verification-commands-and-ownership), and retain real failure exits.

```bash
native_source=/path/to/verified/reconstructed-source
native_sdk=/path/to/android-sdk
native_stage=$(mktemp -d /tmp/vpn0wn-native.XXXXXX)
native_ndk="$native_sdk/ndk/28.2.13676358"
native_cmake="$native_sdk/cmake/3.22.1/bin/cmake"
native_ninja="$native_sdk/cmake/3.22.1/bin/ninja"
for native_abi in arm64-v8a armeabi-v7a x86 x86_64; do
  "$native_cmake" -S "$native_source/tunnel/tools" -B "$native_stage/build/$native_abi" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$native_ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$native_ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_NDK="$native_ndk" -DANDROID_ABI="$native_abi" -DANDROID_PLATFORM=android-24 \
    -DANDROID_PACKAGE_NAME=com.vpn0wn.android -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$native_stage/lib/$native_abi" || exit
  "$native_cmake" --build "$native_stage/build/$native_abi" \
    --target libwg.so libwg-quick.so --parallel 2 || exit
done
```

CMake builds and runs the existing host elf-cleaner dependency. Only the two explicit targets were selected; no Go target, Gradle/APK assembly or dependency-resolution build was added. Copies of each output were stripped with the pinned NDK's `llvm-strip --strip-unneeded`; original unstripped files were preserved. This locally executed strip recipe reproduces packaged bytes, but does not independently establish the historical stripping invocation.

## Actual artifact correspondence

All eight stripped outputs equal the actual pinned APK ZIP entries byte-for-byte. Independent checks also recomputed unstripped/stripped hashes and parsed ELF payloads. The inherited allocated-section comparator checks file-backed section names/payloads, excluding `SHT_NOBITS`, section addresses/flags and program headers. Its result means allocated-payload equivalence; complete stripped-byte equality supplies the stronger whole-file comparison for these eight payloads.

| ABI | Payload | Packaged/rebuilt SHA256 |
| --- | --- | --- |
| arm64-v8a | `libwg.so` | `6e735cce1878215599401d096f9bee6c9ffc72c82f86fcc0b9c785686608ff66` |
| arm64-v8a | `libwg-quick.so` | `ed664324d25b8efeac1b15d860ddb2502bc7607ff20e1bf0c1369d4ba188a86a` |
| armeabi-v7a | `libwg.so` | `85716d5fbf90804d2c7e5e64d1c01fb0861362d02513de1a22f61ee314cd7023` |
| armeabi-v7a | `libwg-quick.so` | `b1c61b84bd126b2b49ee635b48f3fe5d299d61edfa833274c13c3194f50f5702` |
| x86 | `libwg.so` | `3841a4c03228c8f46879ff619992b9fb975d5e6a417a80c623aec009ad720ef4` |
| x86 | `libwg-quick.so` | `a0e127969e04cbf2edc7215e00023724f56288b6d08653ebc00d797fc5adfa13` |
| x86_64 | `libwg.so` | `7292029eba050487dc3f7c0a4aa00bd6dbca27ba76eb01055a3586c5c21043eb` |
| x86_64 | `libwg-quick.so` | `e3d92290ff488875084c800347f7e190d824a1dafcbffaa0d0a357aa12f4021e` |

## Modification, preservation and restoration

A separate fresh tree first established an unmodified baseline in its own build path. The only source change then added a disposable `fprintf` marker to `netlink.h:mnl_socket_open`; it was a relink control, not shipping logging or a performance change. In all four ABIs, rebuilding changes the IPC object and its allocated payload, changes executable `.text`, and retains the marker in stripped `wg`. Unrelated `wg-quick` remains identical in complete unstripped and stripped bytes.

Restoring exact original helper bytes and rebuilding in the same path restores all eight tools and all four IPC objects to their complete baseline bytes. Independent review checked all 60 original/modified/restored snapshots, the restored source manifest and preservation of 1,503 baseline/failed-tree file/symlink entries plus protected files/archive hashes. No original source archive, audited artifact, failed attempt or baseline evidence was overwritten.

The initial relink attempt remains failed: it compared full unstripped `wg-quick` bytes built at different paths, allowing DWARF path differences to invalidate that control. The successful fresh attempt uses same-path untouched/restored comparisons. It does not retroactively validate the failed attempt. Disassembly and Android execution were not performed; the scoped code/object/marker checks establish local relinking without runtime claims.

## Evidence and independent review

Retained ignored receipts include `inventory.json`, `native-linkage.json`, `baseline-proof.json`, `relink-payload-proof.json`, stage manifests/output hashes and terminal readbacks. The fresh proof SHA256 is `bca40d6f61ab9a850770df4965913e2f5d7cd2da08a4d2309cc3ac1bbdcf740a`; its retained producer script SHA256 is `8d688dd131f89ca489130278b03a19899f1d93760d68450b2556fec21bf2078b`. Private archives and host reports are not committed as source-distribution material.

The first archive-only build command completed with exit 0. The fresh relink build's original wrapper-session handle was lost, so its explicit wrapper exit readback remains unavailable. Its completed log reports all controls passing; separate process monitoring and terminal verification completed with exit 0, checked all 60 snapshot hashes and original helper bytes, and confirmed owned commands stopped. Saved binary/source checks provide the evidence; an inferred wrapper exit does not.

Independent ultra review recomputed archive, actual APK-byte, ELF, relink/restoration and preservation checks, and returned PASS for this exact bounded proof with no blocking findings. Documentation-head review/publication belongs in the owning PR acceptance record; this evidence review supplies no GitHub second-account approval or permission for distribution.

## Other source inquiries and remaining gates

Official historical [R8 dependency-bundle](https://storage.googleapis.com/r8-deps/e4c206c7bdfe1415cd2716a1d59d699a80bc63dd) metadata pins desugar source `73170c345e6a762fc6a1f0301bb15218850023ef`; all 1,462 raw entry payloads match the official [2.1.5 raw release](https://storage.googleapis.com/r8-releases/raw/desugar_jdk_libs/2.1.5/desugar_jdk_libs.jar). Container bytes differ. The official transformed Maven JAR matches the app's resolved dependency. Historical [preparation](https://r8.googlesource.com/r8/+/dfc1c9c76304c7c935a3bb9083863c772378112c/tools/desugar_jdk_libs_update.py) removes `DesugarDateTimeFormatterBuilder.java` before building. Exact release invocation, transformation revision/toolchain, complete prepared-source closure and method-level APK correspondence remain unproved; notice-copy source `092407c5` retains its separate role.

DataStore 1.2.1's [release-associated candidate `03aea68c431abd3fa436e9f8fa9b9cda12f18334`](https://android.googlesource.com/platform/frameworks/support/+/03aea68c431abd3fa436e9f8fa9b9cda12f18334/datastore/datastore-core/) supplies native source/header bytes matching the inspected Apache owners. Build `14975342` still lacks a verified exact source/toolchain/build manifest; existing APK-to-AAR allocated-payload equality does not establish native-source reproduction. No definite new missing notice or compiler-string-derived obligation is asserted.

Before distribution, provide and verify a usable recipient source/relink delivery route and its complete required material, then repeat applicable checks against the final signed/distributed artifact. This local proof does not establish recipient delivery, cross-host usability, complete license fulfilment, signing/release readiness, DataStore/desugar closure, root-tools coexistence or cross-app migration. Those remain separate #71 gates; [the improvement plan](../improvement-plan.md#authorized-open-issue-delivery--2026-10-09) retains their scope.
