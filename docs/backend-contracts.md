# Backend and provisioning contracts

Last updated: 2026-10-06 (Europe/Paris).

[Documentation index](../README.md#project-documentation) · Reviewed 2026-10-06

This guide distinguishes source-observed behavior from required repair boundaries. Update producer, consumer, tests and this guide together when a contract changes. [Architecture](architecture.md) owns module design; [the improvement plan](improvement-plan.md#codebase-actions) owns remaining repair status.

## WireGuard configuration and tunnel state

The existing [Config](../tunnel/src/main/java/com/wireguard/config/Config.java), Interface, Peer and endpoint/address types own parse/serialize behavior. Preserve repeated list fields, multi-peer profiles, IPv6 endpoint syntax and other supported WireGuard fields. Generated provisioning profiles must pass these same parsers/import paths.

| Section | Accepted attributes |
| --- | --- |
| Interface | `Address`, `DNS`, `ExcludedApplications`, `IncludedApplications`, `ListenPort`, `MTU`, `PrivateKey`. |
| Peer | `AllowedIPs`, `Endpoint`, `PersistentKeepalive`, `PresharedKey`, `PublicKey`. |

Private/public key requirements and value validation belong to those parsers. Peer Endpoint is optional; IPv6 endpoint literals use `[address]:port`. Unknown attributes, including wg-quick hooks and `SaveConfig`, are rejected. Domain-only DNS serialization and explicit negative network-prefix handling have known compatibility defects; they are not supported-behavior guarantees.

Backend state uses `DOWN`, `UP` and `TOGGLE`. `TunnelManager` uses TOGGLE as an in-progress observable transition; it is not proof of an active VPN. State-changing operations and waiting callers must settle after success, failure or cancellation. Delete/shutdown cannot leave a live backend orphaned from its model.

`GoBackend` manages Android VpnService and userspace/native activation; `WgQuickBackend` manages root/kernel commands. Preserve required permission/service setup and explicit ordering. A stale/destroyed service must not be reused; teardown cannot block the main thread waiting for long activation.

At committed baseline `11d4a9b1`, `GoBackend` preflight runs only with an explicit nonzero ListenPort. It sends a one-byte UDP packet from that port to configured resolved endpoints and retries socket/send failures. Missing endpoints skip preflight; native serialization preserves ListenPort. A failed `protect()`, send or interrupted retry is logged and activation continues; separate endpoint-resolution failure can still prevent startup. C5 owns teardown responsiveness and explicit preflight failure/cancellation policy. Mandatory preflight/random-port behavior described in the initial mixed-worktree audit is not present in this committed baseline.

## Provisioning command surface

The [client](../ui/src/main/java/com/wireguard/android/provisioning/TunnlProvisioningClient.kt) uploads [packaged scripts](../ui/src/main/java/com/wireguard/android/provisioning/ProvisioningScriptBundle.kt) to a temporary remote directory, constructs quoted commands, parses JSON and attempts cleanup. Commands requiring root run directly for root or through non-interactive `sudo -n`. Concrete SSH execution is not implemented yet.

Current server assumptions: Ubuntu/Debian, default `wg0`, IPv4 `/24` allocation, IPv4 default routes and iptables NAT. Script environment overrides do not establish a validated live platform/interface matrix. Plan/install/peer value options reject missing values and config-injection tokens. C2–C3 mutations enforce ownership, native locks, private snapshots and checked postconditions; this is offline source/test proof, not a real VPS run. The [script guide](../third_party/wireguard-install/tunnl/README.md#ownership-locking-and-recovery) owns validation, lock order, stopped-interface semantics and recovery limits. Read-only/pre-transaction shell failures are not uniformly JSON-guarded.

| Command | Current inputs | Result fields used by Android | Known gap |
| --- | --- | --- | --- |
| `detect.sh` | No app arguments. | OS/version/support, package/firewall, installed/config state, interface/endpoint/port/CIDR/forwarding. | Endpoint comes from config metadata, not public-IP discovery; detection is not connectivity proof. |
| `plan.sh` | `--endpoint`, `--port`, `--cidr`, `--dns`, optional `--force`. | Support Boolean, endpoint/port/CIDR/DNS, overwrite flag and steps. | Force is accepted for the shared request; planning stays advisory and read-only. Ownership/root/runtime feasibility is checked by install. |
| `install.sh` | Same fields plus optional `--force`. | Interface/endpoint/port/CIDR and server public key. | Ownership and effective runtime are checked; package/global forwarding effects are not transactional. Live proof remains separate. |
| `sync-status.sh` | No app arguments. | Interface/running/config metadata and peer name/key/AllowedIPs/handshake/RX/TX. | Native interface observation is true/false/null. Missing or invalid runtime counters are null; neither interface presence nor a timestamp proves connectivity. |
| `add-peer.sh` | App sends required `--name` and `--allowed-ips`, plus optional `--dns`. | Name/address/public key and full generated client config. | Serialized disk/apply/recovery checks exist; stopped interfaces receive disk-only changes. The profile contains a private key. |
| `remove-peer.sh` | `--name`. | Name, `removed:true`, `revoked:false`. | Effective running-peer removal and recovery are checked; stopped-interface changes remain disk-only. |
| `revoke-peer.sh` | `--name`. | Name, `removed:true`, `revoked:true` and archive path. | A unique secret-bearing archive follows checked removal. |
| `backup.sh` | No app arguments. | Interface, full server config and revoked count. | Count covers legacy `.conf` and unique `.conf.*` archives. Result is secret-bearing; never log it. |
| `uninstall.sh` | Required `--confirm`, optional `--purge-packages`. | Uninstalled/backup/packagesPurged. | Ownership, service/config absence and requested package absence checked. Partial package effects may prevent restoration. |

Do not use invented success fixtures to hide producer/consumer drift. Add fixtures from actual script responses and explicit assertions for intentional compatibility changes. Preserve unrelated server state and global forwarding; validate ownership and inputs before privileged writes.

Plan runs without root. `willOverwriteConfig` observes a visible regular file; restricted path access can hide an existing config. It is not overwrite approval or an exact install feasibility result. The force flag only changes install's replacement permission after ownership checks.

## Result, error and cancellation meaning

`ProvisioningCommandOutput` carries exit code/stdout/stderr. A zero exit passes `requireSuccess`; nonzero throws a category/exit-code exception while retaining the internal output for the responsible caller. Internal output may contain secrets and is not a diagnostic string.

`RootShell.run()` preserves returned stdout, marker framing and exit status without logging commands or stream payloads. Root startup diagnostics also omit raw UID/error output. Provisioning command failures log only their exit code/category. `ProvisioningJson` guards the full response, including nested arrays and peer rows; shape/parser failures carry no output or parser cause. Cleanup warnings omit exception payloads and preserve the completed result. [C1 evidence](development.md#c1-secret-safe-logging--2026-10-06) retains its historical scope; [C4 evidence](development.md#c4-provisioning-contracts-and-observed-health--2026-10-06) covers strict schemas and remote errors. Harness failures and future transport diagnostics still need handling at their owner.

JSON requires an actual `ok` Boolean. Consumed success strings, Booleans, arrays and nested objects are required and strictly typed, except optional `cleanupPath` and remove-only `archive` strings. Revoked success requires a nonblank archive string. Plan requires `supported:true`; peer mutation requires `removed:true`; uninstall requires `uninstalled:true`. Unknown extra fields are ignored. Numeric counters are decimal strings bounded by signed Long; `revokedCount` is bounded by signed Int. Running and peer handshake/RX/TX fields require Boolean/decimal-string or explicit null as appropriate. Null means unavailable observation, zero means an observed zero; missing fields and invalid/negative/overflow values are invalid responses.

Legacy string-only mutation results are intentionally rejected. The app uploads its packaged bundle per operation; pair the parser and scripts from the same reviewed revision.

Structured failures require string `error` and `message` fields, but remote text never becomes diagnostics. `ProvisioningFailureCategory` supplies fixed messages; the allowlist in `ProvisioningJson.remoteFailure` supplies a safe `remoteCode` only for supported codes. Unknown codes map to REMOTE_FAILURE without retaining the code. Missing/malformed fields map to INVALID_RESPONSE. Script execution consumes `ok:false` even on a nonzero exit, preserving the safe category/recovery; nonzero `ok:true` is still command failure. Generic command failures retain internal output under the contract above.

C2–C3 mutation failures additionally expose `recovery`, `recoveryPath` and `artifact`. Android keeps only the recovery enum: UNCHANGED, RESTORED, INCOMPLETE or UNKNOWN for an unrecognized/malformed present value; absent recovery stays null. INCOMPLETE/UNKNOWN adds a fixed inspection warning. Untrusted recovery paths/artifact text are not retained in exceptions. Successful install/add/remove/revoke/uninstall models expose optional `cleanupPath` (empty when absent or clean). Live UI presentation of recovery/cleanup remains C12; operator handling follows [operations](operations.md#provisioning-mutation-recovery).

The existing guarded shell boundary has a 30-second default execute/upload coroutine timeout, preserves cancellation and maps expected IO failures. Factory checks require a fingerprint when trust is disabled and at least one auth attempt. These are wrapper checks, not proof of host-key verification or real channel/session closure. C11 must implement and test those behaviors against the chosen SSH library.

Command success is limited to the requested operation's effective postconditions. Interrupted or timed-out remote operations have uncertain outcomes until reconciled. Revocation must remove live access; installation results must match runtime config; uninstall/purge results must reflect observed removal. Rollback/recovery failures must remain visible.

## Persistence and presentation

`FileConfigStore` preserves `.conf` names and wg-quick serialization. Its single app-owned instance serializes create/save/load/enumerate/rename/delete. Create/save write a unique private `.tmp` in the same directory, sync and close it, then atomically rename it to the target. Failed write/sync/rename reports IO failure and retains the prior profile; failed create publishes no profile. Rename rejects an existing destination without creating a marker. Temporary cleanup is attempted and failures log no profile payload. Process death can leave a private ignored `.tmp`; no automatic deletion of existing files or migration is performed. This does not establish directory durability under power loss or coordination with external writers/other store instances. See [architecture decision](architecture.md#local-profiles-commit-through-native-atomic-rename) and [focused evidence](development.md#atomic-local-profile-replacement--2026-10-06).

`VpsServerStore` keeps non-secret fleet metadata and nullable running observations through existing DataStore transactions. Legacy records without `running`, and malformed running types, remain usable with unknown health. Status updates persist true/false/null without retaining an earlier observation. Corrupt-store warnings exclude payloads/parser causes. Protected SSH credential persistence remains separate.

`VpsDeployMapper` maps incomplete endpoint/CIDR to PROVISIONING. Complete records need a positive, nonfuture sync timestamp at most 600 seconds old: running true maps ONLINE, false OFFLINE and null ATTENTION. Missing, future or older timestamps map ATTENTION regardless of old running evidence. Recent-sync and running health checks express separate facts; stale running health is unknown. ONLINE means recently observed interface presence, not a successful handshake, route, NAT, DNS or packet test. Peer ACTIVE requires running true and a nonfuture positive handshake at most 180 seconds old; unavailable counters render as an em dash.

UI presentation must use actual selected/view-owned data. Abbreviated keys remain display-only; copy/export/QR need the complete authorized payload. RX/TX rates depend on new snapshots and elapsed time; idle traffic cannot retain a prior nonzero rate indefinitely.

## Verification boundaries

Use existing [QA coverage](qa-test-plan.md) and [development commands](development.md#verification-commands-and-ownership). Validate actual responses, successful controls, failures, cancellation and state recovery. Syntax checks alone do not prove safe mutations; wrappers alone do not prove SSH behavior; JVM tests alone do not prove device/native traffic. Record those limits with the result.
