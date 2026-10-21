# Native toolchain maintenance — #215

Last updated: 2026-10-17 (Europe/Paris).

## Scope and source

This record advances the [BD-G01 maintenance gap](2026-10-12-app-root-security-audit.md#bd-g01-native-maintenance-follow-up--215-2026-10-17) under [#215](https://github.com/novakin/vpn0wn-android/issues/215). Source commit `7b062be2ff7735c979cbfd93f9b4557329bfdee0` contains the build/generator/test and credits delta on base `d815ae44f5e5c69f5053bbe73b18e13025f38bbf`. Later documentation edits do not change the native source tested here. Final committed head, independent ultra verdict, complete repository gates, CI and delivery belong to the PR acceptance record.

The selected pins are Go 1.26.9, `x/crypto v0.58.0`, `x/net v0.61.0` and `x/sys v0.49.0`, with module directive `go 1.26.0`. WireGuard remains `v0.0.0-20250521234502-f333402bd9cb`. Official release metadata lists supported Go 1.27.2 and 1.26.9 at assessment time. Go 1.26 preserves macOS 12 support, while Go 1.27 requires macOS 13. No Darwin execution is established. Official archive hashes and checksum-database module sums were checked; original WireGuard sums remain unchanged.

Go 1.26 rejects module-cache overlays. The existing generator now validates the original exact WireGuard pin and rejects original replacements, copies its source into generated output, then writes private module files with a relative local replacement. Android Make and host checks pass that generated modfile alongside the existing exact-match overlays. Cached source and tracked module files remain unchanged. The [architecture decision](../architecture.md#stage-pinned-native-source-for-supported-go-overlays--215-2026-10-17) owns the alternatives and compatibility consequences; [development setup](../development.md#setup-and-source-boundaries) owns the current build recipe.

## Executed source and native proof

All controls below ran under WSL Linux with the configured host C/JDK toolchain. Android compilation uses actual CMake 3.22.1/Ninja/NDK 28.2.13676358, API24, Debug and the existing package/flexible-page arguments. CMake supplies its real CFLAGS/LDFLAGS to Make; this differs from the earlier direct-Make diagnostic's empty caller flags.

| Boundary | Observed result | Limit |
| --- | --- | --- |
| Compiler input/runtime patch | Official Linux archive SHA256 `42d158b4d8f7b61ac0a830567c940a86098fb7aac52e467a5ebec03ef5cc2f8d`; prepared Go executable SHA256 `e90c4e12df236ea0c2e7536fd68da8a47fc0b585c19588bf6c1a70cfc296a0f9`. All eight prepared runtime assembly files match official Go 1.26.9 bytes plus unchanged boottime hunks. | Source/patch integrity, not hardware suspend behavior. Darwin archive hashes checked; Darwin execution absent. |
| Module/stage integrity | `go mod tidy` and explicit `go mod verify` pass. Each ABI's 107 regular staged WireGuard source files and path set match the original cache. Original WireGuard pin/sums and tracked module files are retained. | Ordinary builds do not execute `go mod verify` every time. |
| Host ownership/lifecycle | Six executed Go race-package outputs pass through final `test-host.sh`; all seven `-Xcheck:jni` controls pass. | Host native/JNI execution, not Android protection or lifecycle acceptance. |
| Android native compilation | All twelve native outputs build across arm64-v8a, armeabi-v7a, x86_64 and x86. Each ELF machine matches its ABI. LOAD alignment is 16,384 bytes for 64-bit outputs and 4,096 for 32-bit outputs. | Native compilation, not APK assembly, loading or device support. |
| Incremental build | Actual CMake-generated Make arguments drive a private source fixture. Unchanged build matches baseline; native-source and overlay-helper markers each rebuild and appear; restored source yields exact baseline bytes. | Private fixture preserves product source; no performance claim. |
| Build metadata | All four Go libraries retain the exact required WireGuard version and exclude the checked absolute workstation prefix. | Local replacement omits WireGuard's original embedded dependency h1. |
| Offline attribution | Four exact selected Go/module LICENSE texts match retained complete BSD wording. Credits changes only four version headings; all terms/order remain. | Source-copy/wording comparison, not new packaged credits-screen or release acceptance. |

The local replacement's omitted embedded h1 is an explicit provenance tradeoff. Original `go.sum`, verified downloads, this run's explicit `go mod verify`, unchanged source/cache equality and retained input receipts anchor the selected source. Relative replacement metadata avoids an absolute workstation path. None of these proves final signed-artifact reproduction or recipient source delivery.

## CMake diagnostic advisory result

The actual arm64-v8a `libwg-go.so` has SHA256 `29b0b113d66b5a770064ba4bf93cdc9c103d0ec0e614d0fa37bea894e248d6ec`. Official `govulncheck v1.9.0` binary analysis and extraction both exit 0 and obtain **5,407 package/symbol pairs**. Exactly one finding appears: **GO-2026-5932**, a module-only OpenPGP deprecation advisory for `x/crypto v0.58.0`. No package or function frame appears. OpenPGP is absent from the actual 147-package Android arm64 import inventory and extracted symbols.

Fresh official OSV version queries find no matches for Go 1.26.9, x/net 0.61.0, x/sys 0.49.0 or the unchanged WireGuard pin. The x/crypto advisory remains. This establishes bounded artifact/source exclusion of OpenPGP, not a zero-advisory, complete call-reachability, all-ABI scan or whole-app security verdict. Scanner implementation/build identity and raw inventories remain in ignored evidence.

Source-mode `govulncheck` still exits 1: its loader misses overlay-added `Rebind`, `Recover` and `RecoveryUnavailable` declarations despite successful native compilation. No tool patch, fake product module, source-layout workaround or cache mutation conceals this failure. The original Go 1.24.3 diagnostic's empty-symbol fallback and 88 advisory IDs remain unchanged in [the historical security reassessment](2026-10-12-app-root-security-audit.md#dependency-and-native-diagnostic-results); those candidates and this new symbol-aware result have different artifacts/tool scopes.

## Scoped packaged Android compatibility

The authorized private companion imports the actual production `:tunnel` from clean source `7b062be2ff7735c979cbfd93f9b4557329bfdee0`, tree `8fc182b3ae24f248f02f7304e565e0b716136c55`. Its APK SHA256 is `a6f8cd35d1500ecfc84f80b1cc1674622a54b18a38c7f803c5a608cb2604ef9e`; Android v2 signing verifies with debug certificate SHA256 `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. App ID `local.vpn0wn.native215.debug` supplies its own package-specific UAPI path. The bundled Go libraries are arm64-v8a `e233c23b03daef7fed3237fc25c06f3ec19d22581cc996e2f071a8f45436d854` and x86_64 `8ea534cc196abda97f8343ae7b152a614c3ca7b2d260d6b4f8e834c913d41c25`. This is production tunnel-source integration in a separate package, not the shipping UI APK, its credits payload, release signing or APK equivalence.

A fresh owned API24 x86_64 guest executes four activations, sessions 1–4: requested ListenPort 35000, omitted ListenPort, parsed zero normalized by the existing parser, then explicit-port reactivation. The fixture uses only the companion app, no DNS/default route and a numeric loopback endpoint against a separately compiled unchanged pinned standard WireGuard/netstack peer. Android's real VPN consent dialog was accepted through its native OK button, without appops substitution. Seven HTTP200 requests have unique nonce queries, disabled caching, exact expected fixed response bytes, increasing RX/TX counters and nonzero handshake observations. Three rebinds return true, retain their activation sessions and permit subsequent authenticated traffic. Rebinds do not each establish a new handshake; observed timestamps may remain unchanged. All four explicit DOWN operations publish backend/callback DOWN and inactive native sessions.

The first traffic request was too early and returned the companion's `busy` result; the settled retry passed. A separate StopService request returned accepted but the VPN remained active after its five-second observation bound. Service inspection showed `startRequested=false` with Android's VPN system binding retained. This does not execute or prove onDestroy; the subsequent explicit DOWN succeeded. Neither observation is concealed as a passing lifecycle test or assigned a new product defect.

The owned peer was stopped after exact process-ownership validation; the companion was force-stopped/uninstalled, its exact temporary path removed and only its disposable emulator terminated. Package/path/peer/process/listener absence was read back; an earlier port5640 TIME_WAIT was not a listener. Cleanup and service-stop notes transcribe the completed tool outputs and distinguish the later host absence check. No personal-phone mutation occurred. Runtime JSON, artifact receipts, consent XML and cleanup notes remain private under ignored `.artifacts/native215-runtime/`.

## Packaged scan precision and matched counterpart

Direct extraction of the APK's stripped arm64 library yields no `pkgSymbols`. Its binary scan therefore emits 15 conservative GO-2026-5932 results: one module, seven package and seven function findings. Those fallback frames are not evidence of linked OpenPGP code. Do not apply the earlier CMake diagnostic's direct symbol precision to this packaged artifact.

The same companion build's unstripped arm64 counterpart has SHA256 `b43a5d1dad6c193141149844cb10eb982face9be7994b4f0027a0119715736bb`. Applying the actual NDK `llvm-strip --strip-unneeded` reproduces the packaged library byte for byte, SHA256 `e233c23b03daef7fed3237fc25c06f3ec19d22581cc996e2f071a8f45436d854`. Counterpart extraction obtains 5,407 package/symbol pairs and its only finding is module-level GO-2026-5932, with no package/function findings. This matched-counterpart evidence supports bounded OpenPGP exclusion for that packaged arm64 build; it is not a successful precise direct packaged scan, all-ABI call-reachability or a zero-advisory/security verdict. Source-mode loading remains unavailable as recorded above.

## Retained failures and review boundary

The initial Go 1.26 host run failed because overlays targeted `GOMODCACHE`; the generator-owned stage fixes that required compatibility issue. The initial APK-signature check used unavailable Build Tools 36.1.0; the corrected installed 36.0.0 verifier passes, with both command records retained. A private Unix Makefiles fixture inherited a FIFO jobserver unsupported by NDK make; switching the private harness to Ninja matched AGP. An initial incremental marker lacked an import in its private overlaid file; the corrected existing-type marker passes the full sequence. Original failed commands/logs remain retained. These fixture failures are not converted into product defects or silently discarded.

Independent ultra source/evidence review found no confirmed actionable source defect in the six native source/test files. It independently recomputed changed-file/artifact hashes, all four staged-source comparisons, runtime patch identity, compiler inputs and scanner arithmetic. The subsequent final-head review includes the four credits headings and completed documentation, as recorded by [PR #221](https://github.com/novakin/vpn0wn-android/pull/221). A same-account COMMENT neither supplies another GitHub account's approval nor replaces final PR acceptance.

Detailed commands, twelve-artifact hashes/ELF receipts, exact native-input/tool identities, source/cache comparisons, incremental states, scanner JSON and retained failures are local under ignored `.artifacts/215-assessment/`. Host/native logs remain under ignored `.artifacts/codex-command-logs` in the correction worktree. These receipts contain no production credentials and are not committed as raw host reports.

## Remaining acceptance

Delivery readback on 2026-10-17 (Europe/Paris): [PR #221](https://github.com/novakin/vpn0wn-android/pull/221) merged as `fc1435b06777a1b2d24077b68c27d2cab4d62742`, and #215 reads CLOSED. Its independently reviewed head `cc1344831840ce44b1278dd54a86caaf5ff6d023` passed [CI 38981313390](https://github.com/novakin/vpn0wn-android/actions/runs/38981313390): UI 972, tunnel 23 and release-startup one test, zero failures/errors/skips, zero Detekt findings, plus native race/JNI and offline/translation/kernel controls. The PR owns complete final-head acceptance; earlier source/build and Android checkpoints retain their original scope.

Scoped separate-package API24 x86_64 compatibility and post-rebind authenticated traffic pass as recorded above. No packaged/shipping APK equivalence, physical-phone run, signed release, Darwin execution, hardware suspend, external-network/server trial, DPI, speed or battery gain follows from this record. Existing #61/#73, app/root actor-pair controls and #71 release/distribution gates remain separate.

## Authoritative input references

- [Official Go releases and archive metadata](https://go.dev/dl/?mode=json).
- [Go 1.26 release/platform notes](https://go.dev/doc/go1.26) and [Go 1.27 release/platform notes](https://go.dev/doc/go1.27).
- [Go 1.26.9 runtime source](https://github.com/golang/go/tree/go1.26.9/src/runtime) and the [tracked boottime patch](../../tunnel/tools/libwg-go/goruntime-boottime-over-monotonic.diff).
- [x/crypto 0.58.0 checksum record](https://sum.golang.org/lookup/golang.org/x/crypto@v0.58.0), [x/net 0.61.0](https://sum.golang.org/lookup/golang.org/x/net@v0.61.0) and [x/sys 0.49.0](https://sum.golang.org/lookup/golang.org/x/sys@v0.49.0).
- [GO-2026-5932](https://pkg.go.dev/vuln/GO-2026-5932) and [official govulncheck documentation](https://pkg.go.dev/golang.org/x/vuln/cmd/govulncheck).
