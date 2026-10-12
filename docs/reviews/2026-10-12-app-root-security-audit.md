# Full app/root security audit — aggregate assessment

Last updated: 2026-10-12 (Europe/Paris).

Delivered source: clean `master`, `5b1ddffbdc5692496b0dc2c96fed4925c9499f1a`. Three confirmed bounded defects: two Medium, one Low. No High/Critical defect or ordinary-app root escalation is established. Static assessment and scoped host/Android work are complete: 8/8 recorded control assertions and 12/12 recorded restoration criteria pass. The assertions include identity, same-URI consistency and internal navigation, not eight attack attempts. Broader runtime and release assurance remain partial. Audit completion is not security sign-off or release readiness.

This consolidates the root/native, IPC/data and build/dependency assessments, their retained receipts and the earlier #73 coverage/environment addendum. Application source and runtime policy are unchanged. Repairs follow their owning issues and the existing contribution workflow; this report supplies no new authorization for application fixes, production operations, workstation elevation or runtime changes.

## Tracking

| Finding | Severity | Owning issue |
| --- | --- | --- |
| ROOT-CLEANUP-REGEX | Medium | [#165: literal privileged cleanup target](https://github.com/novakin/vpn0wn-android/issues/165) |
| IPC-DATA-01 | Medium | [#166: execution-time administrator restrictions](https://github.com/novakin/vpn0wn-android/issues/166) |
| IPC-DATA-02 | Low | [#167: finite ordinary-import work](https://github.com/novakin/vpn0wn-android/issues/167) |

The documentation PR references these issues without closing them: recording a defect does not repair it. Existing [#71](https://github.com/novakin/vpn0wn-android/issues/71) owns root/user/coexistence policy questions; recovery and leak acceptance remain under [#73](https://github.com/novakin/vpn0wn-android/issues/73), [#75](https://github.com/novakin/vpn0wn-android/issues/75) and [#77](https://github.com/novakin/vpn0wn-android/issues/77).

## Environment and evidence identity

The recorded workstation session has unrestricted filesystem access, enabled networking and approval policy `never`; prompt scope provides no technical isolation. Windows has no administrator token; WSL runs as UID 1000. Credentials/connectors and unrelated files are accessible capabilities, but their contents were not enumerated or read. Access is not authorization. Workstation root was neither requested nor used.

Available toolchain: OpenJDK 21.0.10, selected WSL Android SDK platform 36/build-tools 36.0.0, NDK 28.2.13676358 and SDK-local CMake 3.22.1; cached Gradle wrapper 9.8.1. AGP 9.1.0 and Detekt 1.23.8 are declared. Standalone Go/CMake/Ninja/Gradle were absent from default WSL PATH; SDK-local/pinned build tools are distinct. This inventory and successful scoped commands do not certify every tool or establish credential isolation.

The named authorized phone is the rooted Pixel 7 Pro, API 34/user 0, with exact device identity retained privately in the environment receipt. No emulator was authorized for this aggregate. Fresh admission found an existing legacy VPN active; it was left untouched. The coordinator completed the permitted component/URI controls and released the device at 16:15 Europe/Paris. No tunnel activation, remote-control permission pair or port-release trial ran. A prior readiness statement or root grant did not authorize changing that VPN or altering another device/profile.

Current-head artifact provenance, independently hash-read from the retained files:

| Artifact / command | Actual result | Boundary |
| --- | --- | --- |
| Offline `:ui:dependencies --configuration releaseRuntimeClasspath` | Exit 0, 2 seconds; 108 selected external module identities; no failed/unparsed nodes | Host configuration resolution only. Includes BOM/multiplatform metadata; not APK content. |
| OSV selected release-runtime batch | 108 successful module/version responses, zero matching IDs | Version query coverage only; native/repackaged/build/desugaring and other variant scopes separate. |
| `:ui:assembleRelease` at audited head | Successful, 3m19s; unsigned release SHA256 `81d197166f8b565f5e0d4d3f0a154ceaca15f36858d3c93cd3dce5637539fca1` | Assembly is proved; signed release, device execution and complete packaged-security inspection are not. |
| Current-head debug assembly | Successful, 22 seconds; APK SHA256 `72f16854543dbef6c1266b175e54fdc50e201370b446285098fcc5455a817092` | Retained identity identifies `com.vpn0wn.android.debug`, source `5b1ddffb`, signer SHA256 `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. This exact artifact was temporarily installed/tested in the existing debug sandbox, then the original candidate APK was restored. |
| Root cleanup POSIX matcher probe | Coordinator-reported eight positive/negative host controls passed under WSL UID 1000; wrong-target matches reproduced | Real host regex behavior, with no Android kernel/netfilter/root side effect. Android impact untested. |

Native pins: wireguard-tools `e2ecaaa739144997ccff89d6ad6ec81698ea6ced`; elf-cleaner `7efc05090675ec6161b7def862728086a26c3b1f`; Go compiler 1.24.3 and WireGuard module `v0.0.0-20250521234502-f333402bd9cb`. The module's `go 1.23.1` directive is not the embedded compiler/runtime version. The tracked Gradle wrapper JAR matches its official 9.8.1 checksum.

## Scoped Android controls and restoration

The independently ultra-reviewed probe APK `0d9ad565c35a6f1fd9b60d07508b8486a6e15753b4b7807a8b746fbb74ae5a28` ran as ordinary app UID 10703; target UID was 10692. Its fresh certificate differs from the target's; no shared UID, target instrumentation, root API, Internet/storage permission or direct privileged command is present. Privileged setup/observation does not turn its normal app API requests into root requests. The independent PASS concerns bounded probe safety/artifact consistency, not vpn0wn security sign-off.

All 8/8 recorded control assertions pass: distinct ordinary actor identity; denied VPN bind; external private Settings launch rejected with SecurityException; actual target log URI rejected without a grant; the same URI read through the actual chooser-granted recipient flow; new access rejected with SecurityException after recipient return; exact same URI across these cases; internal Settings navigation succeeds. These are assertions over scoped requests and observations, not eight ordinary-app attack attempts. The successful log read retained only a 4096-byte prefix count/hash, not log content or proof of the complete stream. New-open denial does not recall already-open descriptors or copied bytes. VPN binding has no equivalent authorized activation/bind positive control, so only the unauthorized negative behavior is verified.

All twelve recorded restoration criteria pass: both application UIDs and exact APK hashes; both private-store filename-to-hash maps; raw secure/system/global settings; exact Magisk policy rows; probe removal; original legacy foreground. Private-store enumeration order differs, but the filename-to-hash maps are identical; no data change follows from ordering. The original `4c3c9142` debug APK is restored exactly, SHA256 `393354313a3419f749fc3a50bcb1207d15e3a2c18e230b146387d4acf956ef3d`. Settings triggered a root prompt that timed out and created this target UID's denial row (`policy=1`); the target originally had no root grant/row, and only that newly owned row was removed to restore the original policy rows. This proves neither a root grant nor execution of the privileged preamble SQL.

The legacy list/active VPN remained the foreground baseline and no audit tunnel was activated. Restoration is scoped: APK install timestamps, caches and AppOps history were not byte-restored or claimed unchanged. No DPC/admin restriction timing, secondary user/profile, kernel collision, privileged revocation/upgrade, release runtime, continuous reachability or packet-leak trial occurred. Evidence: `device-controls-receipt.json`, `probe-independent-ultra-review.md` and its receipt; raw device reports remain private and are not copied here.

## Confirmed defects

### ROOT-CLEANUP-REGEX — Medium: privileged cleanup loses literal target identity

`Tunnel.java:20` admits `.` and `+` in valid interface names. Kernel DOWN invokes the compiled pinned helper (`WgQuickBackend.java:188`; `tunnel/tools/CMakeLists.txt:12`). Its `del_if` interpolates the name into POSIX extended regexes without escaping (`tunnel/tools/wireguard-tools/src/wg-quick/android.c:646`), then deletes matching firewall rules and selects a network to destroy (`android.c:654`, `:667`). The route match also accepts a longer table-name prefix.

Static trace and the real host matcher demonstrate that `a.b` can match `aXb`, `a+b` can match `aaab` while missing its own literal name, and `wg0` can match `wg01`. The condition requires authorized root/kernel use plus an imported/chosen valid name and existing colliding rules/network. It can affect unrelated privileged resources or leave owned cleanup incomplete. No ordinary-app permission bypass, actual Android deletion, packet leak or root escalation was reproduced. Confidence is high for incorrect matching; Android impact remains conditional.

Later authorized repair should preserve all valid names and match literal, exact owned firewall/network targets. Acceptance needs wrong-target negative controls and equivalent correct cleanup, followed by separately authorized Android ownership/collision checks. No fix is made here.

### IPC-DATA-01 — Medium: administrator policy not revalidated at export/reveal execution

`ui/src/main/java/com/vpn0wn/android/preference/ZipExporterPreference.kt:84` checks the live administrator restriction before authentication; its callback at `:88` starts asynchronous profile export without another policy check before secret output (`:46`). `TunnelEditorFragment.kt:346` similarly checks before authentication, then reveals in the callback at `:350`; `haveShownKeys` also persists across later policy/selection changes. An administrator revoking export/key access while the operation is pending cannot prevent the already-admitted sensitive action. This is a confirmed static policy defect, with no Android reproduction or external-app authentication bypass claim.

An allowed operator/native authentication result or documented no-lock path remains necessary. Existing completed exports/already-visible secrets cannot be recalled and are not new violations. The fitting owner pattern already exists in `AppDataExporterPreference.kt:118`, `:120`, `:149`: current request/view guards and live policy immediately before sensitive output. Verify restriction revocation produces no new output/reveal and unchanged policy still succeeds. Real restriction timing requires an authorized disposable DPC-managed target; the personal phone must not be enrolled or have ownership altered for this audit.

### IPC-DATA-01 source repair follow-up — #166, 2026-10-12 (Europe/Paris)

The maintainer subsequently requested repair of #165/#166/#167. This follow-up covers #166 source changes after the pinned audit; the original source references, FAILED coverage entry and unperformed Android controls above/below retain their historical scope. It supplies no new device or DPC authorization.

`ZipExporterPreference` now binds its authentication/loading to the current Settings view, consumes terminal callbacks once and checks live policy before loading, acquisition and secret writes. View destruction cancels loading; detachment retires authentication and restores the control for a replacement view. `TunnelEditorFragment` checks current policy and field/configuration/selection/view identity before reveal, masks secret fields and resets authorization on selection or loaded-config/view replacement, and discards delayed configuration loads. Allowed configured-authentication and no-lock paths remain unchanged; a failed biometric sample can still succeed through the same prompt.

Two owner regressions reproduced export/reveal after authentication-time revocation against unchanged `f6a617149b11b4a19de6c9b399b7ff47ee141b36` (2/2 expected assertion failures). The subsequent owner suite uses mocked restriction/authentication results, synthetic profile/store/provider responses and Robolectric API 28/29. Final-head independent review, check results and integration state belong to the repair PR acceptance record. These source controls cannot recall completed exports or already-observed/copied secrets, interrupt an active synchronous write, or establish real DPC timing. Disposable managed-user/profile denied/allowed controls remain required for Android acceptance; the personal phone must not be enrolled or have ownership changed.

The initial patched full UI run executed 505 tests: 497 passed and eight ZIP owner controls failed with `ClassCastException`. Robolectric retained `AppDataTransferSettingsTest.Authentication` on the shared Kotlin `BiometricAuthenticator` singleton, while those controls requested a distinct ZIP shadow type. The nine ZIP policy controls had passed in the preceding focused run; all eleven final editor controls passed in this full run. This is a test-fixture failure, not an observed production regression. The fixtures now reuse one authentication shadow type and reset its exact expected title/result state per test. The failed run remains retained; the repair PR owns verification after that fixture-only correction.

### IPC-DATA-02 — Low: ordinary import lacks finite resource bounds

`TunnelImporter.kt:40`, `:64`, `:109` accumulate unbounded profile entries and launch work per retained profile; `tunnel/src/main/java/com/vpn0wn/config/Config.java:73` reads unbounded lines. User-selected file/provider content can exhaust memory/CPU or block indefinitely. Runtime denial-of-service magnitude has not been measured.

Reachability requires the native picker or TV local file selection. Explicit external `ACTION_VIEW` launch does not dispatch its URI to the importer at this revision (`MainActivity.kt:70`; `BaseActivity.kt:47`; `TunnelListFragment.kt:111`). No silent external import, arbitrary write, shell hook or activation is established. Later bounded-input repair should preserve legitimate multi-profile/QR import, partial results and cancellation; verify rejected oversized inputs alongside valid controls.

## Coverage ledger

Statuses describe precise criteria, separately from severity: **VERIFIED** has sufficient stated evidence; **FAILED** establishes the criterion is unmet; **PARTIALLY_ASSESSED** has material remaining gaps; **NOT_ASSESSED** has no relevant executed review/test; **NOT_APPLICABLE** identifies an absent criterion with a reason. Static VERIFIED never implies Android runtime proof.

| Criterion | Status | Evidence mode / exact scope and remaining boundary |
| --- | --- | --- |
| Delivered source, native pins and available toolchain identified | VERIFIED | Static source/Git receipts and read-only environment inventory at `5b1ddffb`; capabilities and authorization distinguished. |
| Scoped device admission and recorded restoration | VERIFIED | Dynamic API34/user0/ordinary actor identities and 12/12 named restoration criteria above. Exact store maps/settings/policy/APK/UID/foreground restored; install timestamps/caches/AppOps history excluded. |
| Complete device-state preservation and broader operation admission | PARTIALLY_ASSESSED | Legacy VPN left active/untouched; no activation authorized for the scoped trial. Excluded restoration surfaces and other targets/users not assessed. |
| Declared component/action/permission ownership | VERIFIED | Static main manifests and app entrypoint traces. Framework receiver, tile, VPN and URI authorization precede asynchronous app work. |
| Incoming custom root/app Binder authorization | NOT_APPLICABLE | No app-owned incoming custom Binder/AIDL endpoint exists; native DNS Binder code is an outgoing proxy. Framework/provider boundaries remain applicable below. |
| Scoped log URI no-grant / chooser-grant / post-return enforcement | VERIFIED | Dynamic exact-main debug/API34/user0, ordinary UID10703 versus target10692/different signer; same actual URI denied, chooser-granted 4096-byte prefix read, then denied on new access after recipient return. Open descriptors/full-content secrecy excluded. |
| Private Settings rejection and internal authorized navigation | VERIFIED | Dynamic exact-main debug/API34/user0: ordinary external actor gets SecurityException; in-app Settings navigation succeeds. Broader activity/profile enforcement excluded. |
| Unauthorized VPN bind | VERIFIED | Dynamic ordinary-actor negative control on exact-main debug/API34/user0. No authorized bind/activation success or side-effect/port-release trial. |
| CONTROL_TUNNELS denied/granted and VPN activation pairs | NOT_ASSESSED | No target control permission granted; remote opt-in remained off. Existing active personal VPN was not displaced. Root/shell/mocks do not substitute. |
| Remote-control queue admission and revocation semantics | PARTIALLY_ASSESSED | Static opt-in checked before inventory/mutex wait (`TunnelManager.kt:481`); no sender capability retained or late permission/setting recheck. Product policy for admitted requests and actual permission revocation remain open. |
| Internal mutable tunnel/config target preservation | PARTIALLY_ASSESSED | Static manager/backend serialization, managed-object identity and immutable snapshots. Same-UID/root external changes and independent actors untested. |
| Literal kernel cleanup target preserved | FAILED | ROOT-CLEANUP-REGEX, static plus unprivileged host matcher. Actual Android kernel/netd side effects not tested. |
| Administrator policy applied at sensitive execution | FAILED | IPC-DATA-01, static shared debug/release source. Native auth/DPC timing controls pending. |
| Ordinary import finite resource bounds | FAILED | IPC-DATA-02, static shared source. Android/provider exhaustion and cancellation not measured. |
| Configured-auth fail-closed/no-lock source branches | VERIFIED | Static helper branches; no actual biometric/cancellation/revocation or release-runtime claim. |
| Profile path/name validation and bounded full-transfer schema | VERIFIED | Static parser/store/manager flow: no ZIP path extraction, safe names, collision rejection, typed/limited transfer and new profiles DOWN. Crash/power-loss recovery remains limited. |
| Private persistence, intentional secret export and general log secrecy | PARTIALLY_ASSESSED | Static MODE_PRIVATE/atomic replacement, explicit unencrypted export, log snapshots; scoped URI enforcement verified separately. DAC/SELinux, in-progress output visibility, full-stream/exception redaction and other recipients/versions remain gaps. |
| Clipboard/screenshot/IME/accessibility confidentiality | PARTIALLY_ASSESSED | Static reveal FLAG_SECURE/user-copy flow only; version/device behavior and sensitive clipboard expiry untested. |
| Kernel shared-resource ownership and Android user/profile scope | PARTIALLY_ASSESSED | Name-only global interface observation; helper UID range `0–99999`/packages.list and owner-user native paths traced (`android.c:803`; `libwg-go/Makefile:57`). Device-wide policy, old/new-app collisions, secondary/work users untested. |
| Persistent root helper disconnect/revoke/cancel/upgrade | PARTIALLY_ASSESSED | Static long-lived `su`, shared command/stop lock, existing-file/cache reuse. Root-manager revocation, blocked I/O, upgrades/stale executables and helper version mismatch not tested. |
| Renamed app blocked from legacy shared installer | VERIFIED | Static admission guard precedes mutations (`ToolsInstaller.java:137`); no root installation attempted. Alternate trusted library hosts and old installed tools excluded. |
| Root-manager notification/logging policy | PARTIALLY_ASSESSED | Static own-UID SQL in `RootShell.java:50` disables those fields before `id -u`; no grant/deny escalation. Reachability from startup/Settings and absent restoration traced; expected policy/#71 and fresh execution/readback unresolved. |
| JNI/native handle ownership and UAPI permission source | VERIFIED | Static exact service/activation identity, nonreused handles, remove-before-drain, client/device/context teardown and new-directory/socket modes. Android DAC/SELinux, user path, races and actual OS denial unproved. |
| Native cryptographic randomness and protocol primitives | VERIFIED | Static `Key.java:186` SecureRandom/clamping; pinned CLI OS randomness and Go crypto/rand/standard WireGuard primitives. No cryptanalysis/timing/device entropy certification. |
| Exact-head selected release-runtime inventory and public query execution | VERIFIED | Host offline configuration, 108 identities; 108 OSV responses/zero matches. Receipt retains each selected version; final APK/repackaged/native/build/desugaring content separate. |
| Complete advisory/linked native reachability closure | PARTIALLY_ASSESSED | Go 1.24.3/x-module matches remain; bounded Android path exclusions established, no complete call-aware/binary scan. No-match Maven results do not establish clean app. |
| Exact-head release/debug assembly identity | VERIFIED | Successful retained build logs and hash reads above. Release unsigned; debug certificate/identity known. This criterion is artifact production only. |
| Signed release, final manifest/native hardening and upgrade continuity | PARTIALLY_ASSESSED | Current unsigned release exists; source shrinking/variant identity and historical target36 manifests known. Current signed-release certificate, complete merged-component/binary enforcement and runtime absent. |
| Concrete SSH transport/host-key/credential lifecycle | NOT_ASSESSED | No production SSH implementation wired; factory guards and non-secret persisted field lists do not prove negotiation, cancellation or actual host-key enforcement. |
| Root/kernel traffic health, leakage, SELinux and real protection denial | NOT_ASSESSED | No current audit packet/attacker/OS-denial controls. Build success, recent sync, mocks or loopback history cannot verify these criteria. |
| CI source least-privilege configuration | VERIFIED | Static full action commit pins, read-only contents, no persisted checkout credentials, explicit manual debug APK opt-in. Actual remote secrets, protection and current run not inferred. |

## Material gaps, policy questions and hardening

Security sign-off should remain blocked on ROOT-CLEANUP-REGEX and IPC-DATA-01 plus missing remote-control/VPN authorized pairs, unresolved root resource/user ownership, and current release/native enforcement. IPC-DATA-02 needs a bounded-input resolution or explicit risk acceptance; no privilege escalation claim follows from it. Scoped Android controls and recorded restoration are complete; broader requested runtime security assurance remains partial for the explicitly unperformed criteria.

Go 1.24.3 and `x/crypto 0.38.0`/`x/net 0.40.0`/`x/sys 0.33.0` have advisory matches. Reviewed Android WireGuard paths use crypto primitives and IPv4/IPv6 socket batching, without SSH/OpenPGP/Windows/HTML/HTTP2 advisory flows. Java resolves endpoint hostnames; native endpoint parsing is numeric `netip.ParseAddrPort`, and local bind resolution is numeric. No attacker-reachable CNAME/SVCB advisory path was established. Supported-toolchain and final linked-symbol/repackaged-content assessment remain necessary; version alone does not prove exploitability.

Long-lived root-shell authorization, admitted-operation cancellation, framework opt-in/permission revocation and shared kernel/device-wide ownership need explicit product policy and paired tests. No requirement for a fresh prompt on every root operation is invented. Root-manager logging/notification changes are an existing policy question rather than a newly classified vulnerability. `.artifacts/` exclusion, `allowBackup=false` and app-private storage do not prove all OEM transfer/user-profile or rooted-device confidentiality.

Optional hardening/standards gaps are distinct: Gradle dependency locking/verification metadata, clipboard sensitivity/expiry and obfuscation policy. Their absence is not a confirmed exploit. The current release DSL omits explicit `targetSdk`, but inspected existing/historical manifests target36; no obsolete-target vulnerability is asserted. Encryption of private keys against an already-rooted device was not a documented product requirement. Concrete SSH transport must be assessed when implemented.

## Carried-forward #73 subset and handoff

Recovery candidate `4c3c9142f70e823f7c006ca13eba5725c3dec276` and native primitive base `a99aa38910112833e2f0d74262c576c1721a66b9` are separate unmerged scopes. Their 32 debug JVM/Robolectric recovery controls and source review do not verify delivered master or real Android authorization. Preserve earlier repaired P1 engineering/harness labels with their original scope; they are not new High/Critical security findings.

V2 Android orchestration failed before silent-endpoint trigger, exhaustion or automatic recovery; timeout outcome was unknown and observer exited 1. This is a failed harness procedure, not an established app recovery defect. Manual post-OFF reconciliation passed 10/10 comparisons; original personal-state restoration passed 12/13, with a legacy preference difference whose changed keys/semantic equivalence remain unknown. V3's 73 host controls, 13 dependency pins and independent offline PASS prove frozen orchestration only; no V3 device trial or timeout fix is demonstrated. Do not convert this subset's verdict into full-app approval.

Detailed local evidence is retained under ignored `.artifacts/security-audit-20261012/`: root, IPC/data and dependency reports/receipts; release-runtime inventory and advisory responses; scoped device-control/restoration receipt; probe and aggregate ultra reviews. The aggregate receipt records input hashes, build logs and artifact identities. Historical subset reports remain under `.artifacts/overnight-20261012/recovery-pilot-harness-ultra-20261012/`. Initial assessments and revised snapshots remain preserved. Raw host/device reports, private-store evidence and credentials are excluded from source distribution.

The bounded assessment is complete; broader runtime security assurance remains partial and security sign-off is withheld. The documentation PR owns final publication review and routine CI acceptance. Future repairs and runtime validation retain their separate issue acceptance and authorization boundaries.
