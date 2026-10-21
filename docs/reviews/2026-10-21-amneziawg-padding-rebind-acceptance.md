# AmneziaWG padding, header protection and rebind acceptance

Last updated: 2026-10-21 (Europe/Paris).

Related to #76 and parent #72; both remain open. This record extends the [earlier native lifecycle and traffic evidence](2026-10-19-amneziawg-native-lifecycle-acceptance.md). It changes no product behavior or shipping APK.

## Tested scope

The isolated API36 x86_64 probe exercises production Application, TunnelManager, GoBackend and VpnService with the official `github.com/amnezia-vpn/amneziawg-go/v3` peer at `v3.1.20260828`. The peer source is unmodified. The app retains its existing production overlay, including the S4 refresh after a blocked TUN read.

| Binding | Tested identity |
| --- | --- |
| Product revision / tree | `ad03dda6ad0023b151ec42f856efb755076b89bb` / `a78485cab54b89cc69b412496215aed2f8a19d7e` |
| Probe package | `local.vpn0wn.awg76trafficprobe.debug` |
| Probe APK SHA256 | `32cd8ae55d49b1297b2b2e8af9ff3e58c246b21a6a7d2d9cb4073a700c5a15e1` |
| Peer executable SHA256 | `4166d02ce78dde212983030063b73b312367ffdd93822265942ca0dfb91c6b90` |
| Runtime | `run-20261021-003558-6f7442`; controller exit 0 in 248,806 ms |
| Integration comparison | `1bce9270`; non-Markdown differences from the tested source are the unrelated retained-profile export MIME correction and regression test |

The synthetic matrix configures S1–S4 `12`, H1–H4 `101/102/103/104`, nonzero shared HeaderProtectionKey and ContentPaddingAddition `16`. Client I1 is `<b aabb>`; Jc is `2`, Jmin/Jmax are `8`, MTU is `1280`, and cookies remain enabled. The probe-only route is `10.7.0.1/32`, client address `10.7.0.2/32` and numeric outer endpoint `10.0.2.2:51892`. ListenPort and keepalive are omitted. No endpoint DNS, default route, recovery policy or automation is configured.

Builds and static APK audit exit 0. Independent input and artifact reviews accept this exact bounded runtime. They verify the pinned official peer sources/dependencies, app manifest and executable ownership, signing/alignment and compiled probe. Relative to the prior accepted APK, production executable owners remain unchanged; the native executable section is unchanged and native byte differences resolve to build metadata. These checks do not establish runtime success by themselves.

## Observations

The real Android VPN consent dialog identified the isolated package. The observed positive button was tapped; the retained consent receipt records result `-1` and `prepared=true`. The dialog XML was inspected during execution but was not retained after guest removal. Product execution used an ordinary app UID; the existing public instrumentation shell observer read UDP tables without changing product permissions.

Thirteen native stages and four matching peer HTTP receipts passed:

| Activation | Header key | Before rebind | After rebind |
| --- | --- | --- | --- |
| `match-before` | Matching | Fresh handshake, exact authenticated response and RX/TX growth | Exact authenticated response and further RX/TX growth |
| `wrong-key` | One bit differs; other settings preserved | Connection timeout, TX growth, zero handshake/RX and no peer HTTP request | Not attempted |
| `match-after` | Matching again | Fresh handshake, exact authenticated response and RX/TX growth | Exact authenticated response and further RX/TX growth |

Both rebinds retain activation/session, native handle, service, TUN and actual local port while replacing socket inodes and advancing socket generation. Post-rebind traffic can use the retained cryptographic session; this is not a claim that rebind forces a fresh handshake. Each DOWN retires the exact native/service owner, preserves stored config bytes and permits IPv4 port reuse.

All eleven host cleanup/preservation checks pass: peer termination and port release; probe package/process/service/socket absence; baseline restoration; guest termination and port release; shared-owner preservation; unchanged source inputs. The controller's success path also requires zero accumulated cleanup errors. An empty cleanup-error list produces no separate file in this runner; no explicit empty-file receipt is claimed. All runtime gates are closed after completion.

## Evidence and review

Private evidence remains under ignored `.artifacts/continuation-20261021-awg-padding/`, including the executed authority, source/input bindings, build receipts, independent artifact reviews and `runs/run-20261021-003558-6f7442/` native, peer, command, acceptance and cleanup records. Raw fixture material and device reports are excluded from source distribution.

Independent ultra runtime review passes with no findings (`runtime-ultra.json`, SHA256 `d4d63b013a49eb26a44aaf1dd300f1d5f25ed73d7774b8b74f4b3afc20f36fac`). Its independent checker exits 0; all 123 recorded commands are contiguous, settled and drained, including 48 expected exit-1 absence/consent polls. Native private, instrumentation and terminal reports agree, and installed/cleanup APK hashes match the admitted artifact. The reviewer is satisfied within the stated scope. The later PR acceptance record owns the final documentation head, independent review publication, CI and merge state.

## Limits

This establishes authenticated operation under the configured nonzero padding/header-protection matrix and traffic after two explicit socket rebinds. UAPI settings and application traffic do not measure every emitted packet byte or prove every field combination. The official peer samples S4 before its first blocked TUN read; unlike the app overlay it does not refresh that value. Eventual HTTP success therefore does not establish first-packet delivery or rule out retransmission. No peer workaround was introduced.

Physical/external-server interoperability, carrier/OEM behavior, other ABIs, network handover, hostname/lockdown, recovery, cancellation, hardware sleep, installed UI/accessibility, release/distribution obligations, DPI resistance, speed and battery remain outside this cohort. The earlier records retain their original scopes. Omitted ListenPort does not exercise enabled WireGuard preflight ordering.
