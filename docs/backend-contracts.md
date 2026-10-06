# Backend and provisioning contracts

[Documentation index](../README.md#project-documentation) · Reviewed 2026-10-06

This guide distinguishes source-observed behavior from required repair boundaries. Update producer, consumer, tests and this guide together when a contract changes. [Architecture](architecture.md) owns module design; [the improvement plan](improvement-plan.md#codebase-actions) owns remaining repair status.

## WireGuard configuration and tunnel state

The existing [Config](../tunnel/src/main/java/com/wireguard/config/Config.java), Interface, Peer and endpoint/address types own parse/serialize behavior. Preserve repeated list fields, multi-peer profiles, IPv6 endpoint syntax and other supported WireGuard fields. Generated provisioning profiles must pass these same parsers/import paths.

Backend state uses `DOWN`, `UP` and `TOGGLE`. `TunnelManager` uses TOGGLE as an in-progress observable transition; it is not proof of an active VPN. State-changing operations and waiting callers must settle after success, failure or cancellation. Delete/shutdown cannot leave a live backend orphaned from its model.

`GoBackend` manages Android VpnService and userspace/native activation; `WgQuickBackend` manages root/kernel commands. Preserve required permission/service setup and explicit ordering. A stale/destroyed service must not be reused; teardown cannot block the main thread waiting for long activation.

Current `GoBackend` preflight resolves endpoints, chooses a fresh high UDP port, sends protected warm-up packets and passes that port to native activation. Missing endpoints or preflight failure prevent startup. This currently rejects otherwise valid endpoint-free profiles and overrides configured ListenPort. C5 owns the compatibility correction while preserving intentional applicable-profile preflight behavior. Do not silently reinterpret this source-observed divergence as general WireGuard semantics.

## Provisioning command surface

The [client](../ui/src/main/java/com/wireguard/android/provisioning/TunnlProvisioningClient.kt) uploads [packaged scripts](../ui/src/main/java/com/wireguard/android/provisioning/ProvisioningScriptBundle.kt) to a temporary remote directory, constructs quoted commands, parses JSON and attempts cleanup. Commands requiring root run directly for root or through non-interactive `sudo -n`. Concrete SSH execution is not implemented yet.

Current server assumptions: Ubuntu/Debian, `wg0`, IPv4 `/24` allocation, IPv4 default routes and iptables NAT. Scripts print machine-readable JSON to stdout and diagnostic/package output separately. Shell quoting and remote privilege selection do not validate text inserted into WireGuard config.

| Command | Current inputs | Result fields used by Android | Known gap |
| --- | --- | --- | --- |
| `detect.sh` | No app arguments. | OS/version/support, package/firewall, installed/config state, interface/endpoint/port/CIDR/forwarding. | Detection is not proof of working connectivity. |
| `plan.sh` | `--endpoint`, `--port`, `--cidr`, `--dns`. | Endpoint/port/CIDR/DNS, overwrite flag and steps. | Client currently forwards install `--force`, which plan rejects; C4. |
| `install.sh` | Same fields plus optional `--force`. | Interface/endpoint/port/CIDR and server public key. | Config injection and forced reinstall/runtime mismatch; C2–C3. |
| `sync-status.sh` | No app arguments. | Interface/running/config metadata and peer name/key/AllowedIPs/handshake/RX/TX. | Store drops running health; mapper infers ONLINE from freshness; C4. |
| `add-peer.sh` | App sends required `--name` and `--allowed-ips`, plus optional `--dns`. | Name/address/public key and full generated client config. | Concurrency and failed-apply recovery; C3. The config contains a private key. |
| `remove-peer.sh` | `--name`. | Android expects name and Boolean removed/revoked. | Script emits the name as string `removed` rather than expected fields; live removal failures are hidden; C3–C4. |
| `revoke-peer.sh` | `--name`. | Same mutation model plus archive path. | Script emits string `revoked`; inherited live removal failures; C3–C4. |
| `backup.sh` | No app arguments. | Interface, full server config and revoked count. | Result is secret-bearing; never log it. |
| `uninstall.sh` | Required `--confirm`, optional `--purge-packages`. | Uninstalled/backup/packagesPurged. | Missing ownership gate and hidden service/package failures; C2–C3. |

Do not use invented success fixtures to hide producer/consumer drift. Add fixtures from actual script responses and explicit assertions for intentional compatibility changes. Preserve unrelated server state and global forwarding; validate ownership and inputs before privileged writes.

## Result, error and cancellation meaning

`ProvisioningCommandOutput` carries exit code/stdout/stderr. A zero exit passes `requireSuccess`; nonzero throws a category/exit-code exception while retaining the internal output for the responsible caller. Internal output may contain secrets and is not a diagnostic string.

`RootShell.run()` preserves returned stdout, marker framing and exit status without logging commands or stream payloads. Root startup diagnostics also omit raw UID/error output. Provisioning command failures log only their exit code/category. Malformed JSON handling catches `JSONException` and throws a payload-free failure without retaining the parser cause, which may contain input. Cleanup warnings omit exception payloads and preserve the completed result. These boundaries are covered by the [C1 regression evidence](development.md#c1-secret-safe-logging--2026-10-06); arbitrary structured server error text and future transport diagnostics still need appropriate handling at their owner.

JSON uses an `ok` Boolean. Structured failures use error/message fields; malformed JSON becomes a provisioning failure. Consumers currently use optional getters for many fields; missing data must not be mistaken for confirmed runtime health or completed mutations. C4 owns producer/consumer contract proof.

The existing guarded shell boundary has a 30-second default execute/upload coroutine timeout, preserves cancellation and maps expected IO failures. Factory checks require a fingerprint when trust is disabled and at least one auth attempt. These are wrapper checks, not proof of host-key verification or real channel/session closure. C11 must implement and test those behaviors against the chosen SSH library.

Command success is limited to the requested operation's effective postconditions. Interrupted or timed-out remote operations have uncertain outcomes until reconciled. Revocation must remove live access; installation results must match runtime config; uninstall/purge results must reflect observed removal. Rollback/recovery failures must remain visible.

## Persistence and presentation

`VpsServerStore` keeps non-secret fleet metadata through DataStore transactions. It does not implement protected SSH credential persistence. The current mapper's freshness-based ONLINE classification is a known health defect, not the intended success contract.

UI presentation must use actual selected/view-owned data. Abbreviated keys remain display-only; copy/export/QR need the complete authorized payload. RX/TX rates depend on new snapshots and elapsed time; idle traffic cannot retain a prior nonzero rate indefinitely.

## Verification boundaries

Use existing [QA coverage](qa-test-plan.md) and [development commands](development.md#verification-commands-and-ownership). Validate actual responses, successful controls, failures, cancellation and state recovery. Syntax checks alone do not prove safe mutations; wrappers alone do not prove SSH behavior; JVM tests alone do not prove device/native traffic. Record those limits with the result.
