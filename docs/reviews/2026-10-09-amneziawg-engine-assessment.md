# Optional AmneziaWG engine assessment

Last updated: 2026-10-10 (Europe/Paris).

## Result and scope

Recommend the official Go engine's version tag **`v3.1.20260828` (`b5928efb6ca19f0153958460c3d141f04abc5c2e`)** as the candidate for maintainer selection under [#76](https://github.com/novakin/vpn0wn-android/issues/76). Integrate only the userspace engine through the existing config, backend and native lifecycle owners. Retain the existing ordinary WireGuard engine and kernel path initially. The source defaults resemble ordinary WireGuard frames, but that does not establish interchangeable engines or justify replacing the verified lifecycle boundary.

For AWG, recommend the engine's explicitly configured I/J packets as its sole pre-initiation packet behavior. Keep cookies enabled by default. Do not stack the current WireGuard one-byte warm-up onto AWG or send preflight packets from Java. Maintainer selection of the engine, admitted field contract and packet interaction remains open; this report approves none of them and adds no implementation.

This is a source assessment of Tunnl `7a5fc6594cfcf585d31a056ed869d504ce10e7fe`, the official revisions below and the live #76/#72 discussion read on 2026-10-09 (Europe/Paris). It extends the [WG Tunnel comparison](2026-10-08-wgtunnel-comparison.md), whose original revisions and historical findings retain their scope. No engine build, Gradle/APK, device, server, packet, interoperability, speed, energy or DPI trial ran. Downloaded source evidence stays in ignored local artifacts; it is not redistributed with this report.

## Version and integration matrix

| Candidate/source | Exact inspected pin | Consequence |
| --- | --- | --- |
| Existing ordinary WireGuard | `golang.zx2c4.com/wireguard` `v0.0.0-20250521234502-f333402bd9cb`; prepared Go 1.24.3 | Preserve its configuration, kernel compatibility, native protection, warm-up and ownership tests. |
| Official AWG Go candidate | `github.com/amnezia-vpn/amneziawg-go/v3` `v3.1.20260828`, `b5928efb6ca19f0153958460c3d141f04abc5c2e` | Module requires Go 1.25.0. Source supports the complete field matrix below; no Android ABI acceptance is established. |
| Official Android source | `ff15093bf3856fea0947923474870b86efb682f0` | Its [Go module][android-module] pins **`v3.1.20260814`**, resolving to `1b86b2ae0e493e7ea93f8c1a0f0cb6735b1551f1`, rather than the newer candidate. |
| Published Android release's source tag | `v3.1.20260814`, `5c16489e2cd9ed3a0a7a27c7445bba5238132f86` | The [release](https://github.com/amnezia-vpn/amneziawg-android/releases/tag/v3.1.20260814) exposes an APK; its source Go pin matches inspected Android HEAD. HEAD is three commits ahead and changes workflow/app metadata/artwork, not the engine pin or config/native files. The APK itself was not inspected. |
| WG Tunnel whole core | Previously inspected core `e823073151a535187fb276913a4feaed368b75af`, declared 1.9.1 | Minimum API 26 conflicts with Tunnl's API 24 boundary. Its orchestration, forked engine and other features are unnecessary for this bounded integration. Do not adopt it wholesale. |

The two official Go tags differ only in `device/send.go` and `device/receive.go` in the [pinned comparison][engine-diff]. `v3.1.20260828` changes two behaviors:

- `DisableCookies=true` now bypasses the entire under-load MAC2/challenge/source-rate-limiter block; MAC1 still applies. The Android-pinned older tag suppresses sending cookie replies while still demanding MAC2/rate limiting under load, which can drop an unverified initiation without issuing its challenge. Keeping the option false retains cookie protection. An explicit opt-out must describe the selected engine's actual behavior.
- `ContentPaddingAddition` now uses the peer UDP window and full packet size, including the S4 prefix and minimum message overhead, instead of the older TUN-MTU/plaintext ceiling. It takes precedence over the RandomTrailers transport-padding branch.

The [official protocol guide](https://docs.amnezia.org/documentation/amnezia-wg/), read on 2026-10-09, still describes cookie suppression without the other cookie mechanisms changing and content padding limited to the internal MTU. Those descriptions do not fully match the newer pinned code. Use the selected source contract and tested matrix; do not advertise generic “AWG 3” compatibility from documentation or a version label alone.

## Build, API 24 and native ownership

Tunnl's [UI](../../ui/build.gradle.kts) and [tunnel](../../tunnel/build.gradle.kts) modules explicitly retain minimum API 24. The candidate's Go 1.25 requirement is a separate build-tool change, not authority to raise that minimum. Verify API 24 plus `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64` native loading/activation before accepting a bundled engine. No inspected APK/AAR manifest or ELF establishes that compatibility.

Official Android is not a drop-in recipe. Its [Makefile][android-make] prepares Go **1.24.2** while both the wrapper module and engine require **1.25.0**. A toolchain auto-download could use a different runtime from the one receiving the retained CLOCK_BOOTTIME patch. Choose and verify a pinned Go 1.25 toolchain, checksum and compatible runtime patch explicitly. Preserve the existing ordinary-WireGuard recipe until its own compatibility changes are reviewed. The recipe's IPC linker path also omits `/v3`; the actual engine [socket-directory variable][ipc-unix] belongs to the `/v3/ipc` package. Do not assume that flag places a socket in the app's private directory.

Official [CMake][android-cmake] and its SONAME reuse **`libwg-go.so`, `libwg.so` and `libwg-quick.so`**, colliding with this app's outputs. The Go exports use `awg*`, and [JNI][android-jni] targets `org.amnezia.awg.GoBackend`, but distinct JNI names do not resolve duplicate APK paths/SONAMEs. A separate engine needs distinct native output, CMake target, SONAME, loader name and app-owned JNI entry points. Inspect generated ELF exports and prove both Go c-shared runtimes can initialize/query/stop in one process; source names alone do not establish coexistence. A single-engine replacement remains an alternative requiring ordinary-WireGuard compatibility and lifecycle proof, rather than the recommended initial slice.

Preserve the existing owners rather than copying the official Android backend or WG Tunnel core:

| Owner/boundary | Existing contract to retain | Official source gap to resolve for AWG |
| --- | --- | --- |
| `GoBackend` and JNI context | Exact VpnService/activation-thread identity, failure propagation, startup cancellation and context lifetime | Official [backend][android-backend] calls `protect()` after `awgTurnOn` and ignores its Boolean result. This cannot replace protection before native startup/rebind sends. |
| Android native bind | Protect every available socket before publication; terminal shutdown; generation/service checks around sends | Official wrapper creates `NewStdNetBind()` directly. Port the responsible bind behavior to AWG's `conn` types; ordinary WG's package types are not an interchangeable API. |
| Descriptor access | Missing address family yields `-1/EINVAL`; every real socket must be protected | Official [descriptor helpers][awg-descriptors] dereference the family's connection directly. Retain the existing unavailable-family guard. |
| Handle and query ownership | Monotonic non-reused IDs, synchronized admission/removal, admitted-query drain, stale-publication rejection | Official [API wrapper][android-api] uses an unsynchronized handle map and reusable IDs. Reuse Tunnl's owned admission/teardown design, including native callback failure handling. |
| UAPI and device teardown | Close accepted clients to release blocked IPC, cancel bind I/O, drain queries, then close device and free JNI context | Official [listener Close][ipc-linux] closes the listener/inotify owner without tracking accepted clients. Keep the app-owned UAPI server. |
| Bind setup/recovery | Preserve requested explicit port on failed Open/route setup; close bind/route ownership on mark failure | Official [BindUpdate][awg-device] clears `netc.port` on setup failure and returns from mark failure without equivalent cleanup. Adapt the narrowly pinned ownership repairs; do not reuse the existing WG overlay against a different module/version. |

The [native contract](../backend-contracts.md#wireguard-configuration-and-tunnel-state) remains authoritative. Its version-checked overlay, `androidbind`, `tunnelhandles`, `uapiserver`, JNI context and Java session/query guards are preservation requirements, not proof that changing import paths makes AWG safe. An implementation needs corresponding host/race/JNI and ABI controls.

## Complete candidate field matrix

The following are source capabilities, not attributes accepted by Tunnl today. Existing ordinary-WireGuard fields and their round trips remain unchanged. AWG admission must preserve supplied values and explicit presence through import/edit/save/export/recreation; engine `IpcGet` is not a lossless configuration store because it omits several zero values. Protocol intent must survive even an AWG profile whose extensions are empty/default. Unsupported fields/backends must fail before activation without stripping or downgrade.

| Config field → UAPI | Candidate engine validation/default | Compatibility and required admission work |
| --- | --- | --- |
| `Jc/Jmin/Jmax` → `jc/jmin/jmax` | Decimal uint32; default zero; getter omits zero. No `Jmin ≤ Jmax`, count, allocation or MTU guard. Ordered unequal bounds select `[Jmin,Jmax)`; equal bounds give the fixed minimum. | Outbound extra packets need no equal server values. Official docs describe uint16 while Android accepts nonnegative signed Java integers and drops zero. Define a bounded admitted count/packet-size contract and reject inverted sizes before native work; do not blindly adopt either parser. |
| `I1…I5` → `i1…i5` | Optional CPS chain; default nil. Getter preserves nonnil chain's original specification. | Outbound signatures need no equal server fields. Validate grammar and bounded allocation; preserve the supplied specification instead of accepting text the engine silently ignores. |
| `S1…S4` → `s1…s4` | Decimal uint16 `0…65535`; default zero; getter omits zero. A nonzero header-protection key requires every S value at least 12. | Prefixes alter initiation, response, cookie and transport framing. Receiving peers need compatible values. Android accepts values above the engine's uint16 range and does not check the key/prefix relationship. |
| `H1…H4` → `h1…h4` | uint32 scalar or inclusive ordered range; four ranges must not overlap. Defaults **1/2/3/4**. | Device-wide message-type values/ranges need compatible peers. Android stores arbitrary trimmed strings; validate range syntax, order and overlap before activation. Preserve zero-valued ranges explicitly even though the getter omits them. |
| `HeaderProtectionKey` → `header_protection_key` | 32-byte key; zero disables it. Android config is Base64, UAPI is hex. | Nonzero key needs a compatible peer key and all S values ≥12. Treat it as secret-bearing key material. Header protection changes actual protocol headers; it is not client-only junk. |
| `ContentPaddingAddition` → `content_padding_addition` | uint32 scalar/range; zero disables custom addition, leaving ordinary padding. | No equal peer range is required, but the newer UDP-window/packet-size behavior needs MTU/transfer proof. Android does not validate its retained range text. |
| `RekeyAfterTime` → `rekey_after_time` | uint32 scalar/range in seconds; zero falls back to 120s. | Local timer selection; matching peer values are not required. Validate before activation and preserve ranges. |
| `RekeyTimeout` → `rekey_timeout` | Same type/unit; zero falls back to 5s. | Local retry timer; this assessment adds no Java scheduler or alternate retry flow. |
| `RejectAfterTime` → `reject_after_time` | Same type/unit; zero falls back to 180s. | Local key lifetime; interoperability/recovery still needs proof. |
| `KeepaliveTimeout` → `keepalive_timeout` | Same type/unit; zero falls back to 10s. | Local timer selection; preserve supplied range intent. |
| `MaxHandshakeAttempts` → `max_handshake_attempts` | uint32 scalar/range; zero falls back to 18 attempts. | Local retry budget; it does not establish reachability or authorize unlimited recovery. |
| `RandomTrailers` → `random_trailers` | Strict Go Boolean parsing; default false; getter emits `0/1`. | Handshake/cookie receivers must permit oversized frames. An enabled receiver still accepts exact-length frames. Transport adds encrypted zero-filled content. Official guide recommends equal S values to reduce ambiguous detection. |
| `DisableCookies` → `disable_cookies` | Strict Go Boolean parsing; default false; getter emits `0/1`. | Keep false by default. The candidate's explicit true value bypasses under-load MAC2/challenge/source limiting, unlike Android's older engine pin; disclose and test that chosen behavior. |
| Peer `PersistentKeepalive` → `persistent_keepalive_interval` | AWG supports uint32 scalar/range; zero disables it. | Preserve ordinary-WG parser semantics in WG mode. Android retains AWG trimmed string/range and omits blank/`off`; validate admitted AWG values explicitly. |

[Engine UAPI parsing/serialization][awg-uapi], [range parsing][awg-ranges], [merge constraints][awg-merge], [timer defaults/fallback][awg-timers] and [Android config output/parsers][android-interface] support this matrix. Android's Boolean serializer accepts `on/1/true/t/yes` case-insensitively and silently turns every other retained string into false. Malformed Boolean input must fail clearly in this app. Android's J/S zero-to-absence behavior and permissive H/range/CPS setters are not lossless-validation templates.

CPS parsing in [the engine][awg-obf] supports `b`, `t`, `r`, `rc`, `rd`, `d`, `ds` and `dz`; its README documents only the first five. Hex `b` needs nonempty even-length valid hex; `t` emits a four-byte timestamp. Length tags use signed `Atoi` without nonnegative or output-size guards. Data tags use supplied data, which is nil for these I packets. Unknown/empty tags or missing `>` fail, but text outside tags and extra arguments are ignored; empty/untagged input clears the chain. Admission must reject malformed or unsafe input instead of relying on a native allocation failure or silent clearing. Concrete packet/count resource ceilings remain a selection gate; this assessment does not invent an accepted budget.

UAPI mutation is not fully atomic: some J/I/Boolean values change immediately, and merge stores headers before the final key/S relationship check. Validate a complete admitted config before activation and retain existing failure/recovery ownership; a native `IpcSet` error cannot be assumed to restore the previous runtime automatically.

## Handshake preflight, sockets and ports

In [SendHandshakeInitiation][awg-send], after the native retry interval allows an initiation, the engine constructs configured nonnil I1…I5 packets in order, appends the configured J junk train, then appends the real initiation. It passes the batch once to [Peer.SendBuffers][awg-peer], which uses that peer's captured endpoint and device bind. Initial and retry initiations follow this path. This is an optional configured chain, not an unconditional fixed preflight packet, DNS probe, acknowledgment or pacing delay. Native UDP batching/offload may affect how a capture represents individual sends; packet order/emission remains unverified here.

The native bind sends those packets through the same selected address-family socket generation to the same remote peer endpoint as the initiation. [Open][awg-bind] uses an OS-selected source port when requested port is zero, and attempts the supplied nonzero local port otherwise, with IPv4/IPv6 sharing the bound port. An automatic port may be retained on a reopen; zero intent does not mean “rotate on every initiation.” The configured remote Endpoint port is the destination and is not replaced by ListenPort. No matching server junk/count/signature settings are needed for the extra I/J packets alone, although their effect on a particular server/path is untested. S/H/header-key and permissive trailer framing alter real messages and need compatible receiving peers.

Tunnl's [current detector](../../tunnel/tools/libwg-go/androidbind/bind.go) recognizes exactly an ordinary 148-byte initiation with little-endian header type 1. AWG prefixes, custom headers, header protection and trailers invalidate that assumption; other configured packets could resemble its pattern. Therefore do not classify AWG traffic with this detector or add a blind one-byte send for every batch. Preserve its existing explicit-port behavior for ordinary WG only. Recommended AWG behavior is engine-owned I/J configuration, with all socket protection, liveness, generation, cancellation and teardown checks retained independently of packet decoration.

Local source-port rotation remains [#77](https://github.com/novakin/vpn0wn-android/issues/77), and AWG server/provisioning changes remain outside this assessment. No random port, Java sender, extra scheduler, duplicate one-byte packet or automatic cookie disabling is selected here.

## Components and licenses

| Component considered | Observed license/role | Initial integration implication |
| --- | --- | --- |
| Official AWG Go source | [MIT][awg-license], including retained WireGuard copyrights | Preserve notices for copied/bundled source and expose actual component credits. Do not label the entire integration Apache-only. |
| Official AWG Android wrapper/config/backend/CMake | [Apache-2.0][android-license] and per-file SPDX headers | Preserve its license/copyright notices for reused portions; reuse only the necessary contracts/source, not its app/backend wholesale. |
| `amneziawg-tools` submodule | Pin `ee0f0a9aa34ff0a0da4b3433b9512781cfe02843`; [GPL-2.0 tools and Android wg-quick source][tools-license] | Official CMake bundles these as `libwg.so`/`libwg-quick.so`. They are unnecessary for the proposed AWG userspace-only slice; standard WG kernel/provisioning stays unchanged. If bundled later, retain their distinct obligations. |
| `termux-elf-cleaner` submodule | Pin `7efc05090675ec6161b7def862728086a26c3b1f`; [GPL-3.0 COPYING][elf-license] | Host build helper, rather than the Go VPN engine. Retain attribution/obligations for any redistributed source/tool; do not attribute it as MIT or assume it is an APK engine component. |
| Go runtime and dependency closure | Candidate [go.mod][awg-module] includes crypto/net/sys, Outline SDK modules, atomic, YAML and other dependencies | Module declarations are not an APK contents inventory. Determine the actual linked/imported closure and its notices at the selected build; modules used only by other packages/tests must not be reported as proven bundled components. |

Coordinate the real offline Licenses & credits surface with [#71](https://github.com/novakin/vpn0wn-android/issues/71). Engine integration requires accurate credits even if the wider identity migration remains pending. This report adds no dependency, binary or copied upstream source.

## Selection and remaining acceptance

The concrete recommendation is official `/v3` `v3.1.20260828`, AWG userspace only, the complete field families above with explicit validated protocol intent, cookies enabled by default, and engine-configured I/J pre-initiation packets without the WG one-byte wrapper. Kernel AWG activation must fail honestly; ordinary WG userspace/kernel and standard provisioning must remain unchanged. Agree bounded count/CPS allocation admission and native library ownership before implementation; prove runtime coexistence before accepting the resulting build.

Maintainer selection is still required. Then #76 must prove lossless admitted config round trips; malformed/inconsistent/unsupported-field rejection; wrong-backend rejection; protected sockets and explicit/automatic port behavior; failure/cancellation/startup/rebind/query/destruction; all supported ABI/API 24 loading; and separately authorized matching-server/ordinary-WG interoperability and packet behavior. Wrong settings, network loss, MTU/padding and cookie-mode differences need observable controls. No DPI, speed, battery or leak result follows from source adoption or a successful build. This is partial assessment delivery and must not close #76 or parent #72.

## Source references

[awg-module]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/go.mod
[android-module]: https://github.com/amnezia-vpn/amneziawg-android/blob/ff15093bf3856fea0947923474870b86efb682f0/tunnel/tools/libwg-go/go.mod
[engine-diff]: https://github.com/amnezia-vpn/amneziawg-go/compare/1b86b2ae0e493e7ea93f8c1a0f0cb6735b1551f1...b5928efb6ca19f0153958460c3d141f04abc5c2e
[android-make]: https://github.com/amnezia-vpn/amneziawg-android/blob/ff15093bf3856fea0947923474870b86efb682f0/tunnel/tools/libwg-go/Makefile#L23
[android-cmake]: https://github.com/amnezia-vpn/amneziawg-android/blob/ff15093bf3856fea0947923474870b86efb682f0/tunnel/tools/CMakeLists.txt#L13
[android-jni]: https://github.com/amnezia-vpn/amneziawg-android/blob/ff15093bf3856fea0947923474870b86efb682f0/tunnel/tools/libwg-go/jni.c#L18
[android-backend]: https://github.com/amnezia-vpn/amneziawg-android/blob/ff15093bf3856fea0947923474870b86efb682f0/tunnel/src/main/java/org/amnezia/awg/backend/GoBackend.java#L421
[android-api]: https://github.com/amnezia-vpn/amneziawg-android/blob/ff15093bf3856fea0947923474870b86efb682f0/tunnel/tools/libwg-go/api-android.go#L75
[awg-descriptors]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/conn/boundif.go#L8
[ipc-unix]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/ipc/uapi_unix.go#L27
[ipc-linux]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/ipc/uapi_linux.go#L36
[awg-device]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/device/device.go#L525
[awg-uapi]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/device/uapi.go#L355
[awg-ranges]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/device/noise-types.go#L106
[awg-merge]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/device/uapi.go#L824
[awg-timers]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/device/timers.go#L229
[android-interface]: https://github.com/amnezia-vpn/amneziawg-android/blob/ff15093bf3856fea0947923474870b86efb682f0/tunnel/src/main/java/org/amnezia/awg/config/Interface.java#L647
[awg-obf]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/device/obf.go#L11
[awg-send]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/device/send.go#L106
[awg-peer]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/device/peer.go#L120
[awg-bind]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/conn/bind_std.go#L140
[awg-license]: https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/LICENSE
[android-license]: https://github.com/amnezia-vpn/amneziawg-android/blob/ff15093bf3856fea0947923474870b86efb682f0/COPYING
[tools-license]: https://github.com/amnezia-vpn/amneziawg-tools/blob/ee0f0a9aa34ff0a0da4b3433b9512781cfe02843/src/wg-quick/android.c#L1
[elf-license]: https://github.com/termux/termux-elf-cleaner/blob/7efc05090675ec6161b7def862728086a26c3b1f/COPYING
