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
| `plan.sh` | `--endpoint`, `--port`, `--cidr`, `--dns`. | Endpoint/port/CIDR/DNS, overwrite flag and steps. | Client currently forwards install `--force`, which plan rejects; C4. |
| `install.sh` | Same fields plus optional `--force`. | Interface/endpoint/port/CIDR and server public key. | Ownership and effective runtime are checked; package/global forwarding effects are not transactional. Live proof remains separate. |
| `sync-status.sh` | No app arguments. | Interface/running/config metadata and peer name/key/AllowedIPs/handshake/RX/TX. | `running` is systemd activity; failed `wg show` falls back to zero counters. Store drops running health; mapper infers ONLINE from freshness; C4. |
| `add-peer.sh` | App sends required `--name` and `--allowed-ips`, plus optional `--dns`. | Name/address/public key and full generated client config. | Serialized disk/apply/recovery checks exist; stopped interfaces receive disk-only changes. The profile contains a private key. |
| `remove-peer.sh` | `--name`. | Android expects name and Boolean removed/revoked. | Script still emits the name as string `removed`; C4. Effective running-peer removal and recovery now checked. |
| `revoke-peer.sh` | `--name`. | Same mutation model plus archive path. | Script still emits string `revoked`; C4. A unique secret-bearing archive follows checked removal. |
| `backup.sh` | No app arguments. | Interface, full server config and revoked count. | Result is secret-bearing; never log it. |
| `uninstall.sh` | Required `--confirm`, optional `--purge-packages`. | Uninstalled/backup/packagesPurged. | Ownership, service/config absence and requested package absence checked. Partial package effects may prevent restoration. |

Do not use invented success fixtures to hide producer/consumer drift. Add fixtures from actual script responses and explicit assertions for intentional compatibility changes. Preserve unrelated server state and global forwarding; validate ownership and inputs before privileged writes.

## Result, error and cancellation meaning

`ProvisioningCommandOutput` carries exit code/stdout/stderr. A zero exit passes `requireSuccess`; nonzero throws a category/exit-code exception while retaining the internal output for the responsible caller. Internal output may contain secrets and is not a diagnostic string.

`RootShell.run()` preserves returned stdout, marker framing and exit status without logging commands or stream payloads. Root startup diagnostics also omit raw UID/error output. Provisioning command failures log only their exit code/category. Outer JSON construction in `requireOk` catches `JSONException` and throws a payload-free failure without retaining its parser cause. Nested response-shape parsing is not fully guarded: wrong-type peer rows can escape as raw JSON exceptions; C4 owns that repair. Cleanup warnings omit exception payloads and preserve the completed result. The repaired boundaries have [C1 regression evidence](development.md#c1-secret-safe-logging--2026-10-06); arbitrary structured server errors, harness failures and future transport diagnostics still need handling at their owner.

JSON uses an `ok` Boolean. Structured failures use error/message fields; malformed JSON becomes a provisioning failure. Consumers currently use optional getters for many fields; missing data must not be mistaken for confirmed runtime health or completed mutations. C4 owns producer/consumer contract proof.

C2–C3 mutation failures additionally expose `recovery`, `recoveryPath` and `artifact` without secret payloads. Successful mutations expose optional `cleanupPath` for retained private residue after commit. Existing Android models do not consume these recovery/cleanup fields; operator handling follows [operations](operations.md#provisioning-mutation-recovery). These additions do not repair the existing remove/revoke Boolean/name schema mismatch or freshness-derived health.

The existing guarded shell boundary has a 30-second default execute/upload coroutine timeout, preserves cancellation and maps expected IO failures. Factory checks require a fingerprint when trust is disabled and at least one auth attempt. These are wrapper checks, not proof of host-key verification or real channel/session closure. C11 must implement and test those behaviors against the chosen SSH library.

Command success is limited to the requested operation's effective postconditions. Interrupted or timed-out remote operations have uncertain outcomes until reconciled. Revocation must remove live access; installation results must match runtime config; uninstall/purge results must reflect observed removal. Rollback/recovery failures must remain visible.

## Persistence and presentation

`VpsServerStore` keeps non-secret fleet metadata through DataStore transactions. It does not implement protected SSH credential persistence. The current mapper's freshness-based ONLINE classification is a known health defect, not the intended success contract.

UI presentation must use actual selected/view-owned data. Abbreviated keys remain display-only; copy/export/QR need the complete authorized payload. RX/TX rates depend on new snapshots and elapsed time; idle traffic cannot retain a prior nonzero rate indefinitely.

## Verification boundaries

Use existing [QA coverage](qa-test-plan.md) and [development commands](development.md#verification-commands-and-ownership). Validate actual responses, successful controls, failures, cancellation and state recovery. Syntax checks alone do not prove safe mutations; wrappers alone do not prove SSH behavior; JVM tests alone do not prove device/native traffic. Record those limits with the result.
