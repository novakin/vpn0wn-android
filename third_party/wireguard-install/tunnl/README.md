# Tunnl WireGuard provisioning scripts

Last updated: 2026-10-06 (Europe/Paris).

These Tunnl-owned Bash commands adapt the [pinned upstream reference](../UPSTREAM.md) into a non-interactive server surface. Android uses these scripts rather than the upstream interactive installer. Concrete Android SSH transport and live UI actions remain unfinished.

This guide describes committed source at `11d4a9b1`, including its limits. Confirmation does not establish ownership; successful JSON does not establish VPN health. [Backend contracts](../../../docs/backend-contracts.md#provisioning-command-surface) owns Android arguments/results; [the plan](../../../docs/improvement-plan.md#codebase-actions) owns repairs C2–C4 and C9.

## Scope and defaults

Install/plan support Ubuntu/Debian and apt. Default state is `wg0` under `/etc/wireguard`, with metadata under `/etc/wireguard/tunnl`. Shared `WG_IFACE`, `WG_DIR`, `WG_CONF` and `TUNNL_DIR` environment overrides exist; alternate interfaces/platforms have not been validated here. Allocation supports IPv4 `.0/24` pools, IPv4 client default routes and iptables NAT.

Plan/install require a nonempty endpoint and default to UDP `51820`, CIDR `10.8.0.0/24` and DNS `1.1.1.1, 1.0.0.1`. Nonempty endpoint validation is not config-injection protection. Install uses configured apt repositories for `wireguard`, `iptables` and `qrencode`.

## Current command behavior

| Command | Source-observed result and limitation |
| --- | --- |
| `detect.sh` | Reports distro, tools/firewall hints, config/package presence and forwarding. Endpoint comes from config metadata, not external public-IP discovery. |
| `plan.sh` | Validates endpoint presence, port, CIDR and OS, then emits static install steps and an existing-config overwrite flag. It changes no state and is not an exact migration/update plan. It rejects `--force`, although Android forwards that install option. |
| `install.sh` | Requires root, installs packages, generates fresh keys, writes config/forwarding setup and enables/starts the unit. Existing config fails without `--force`; force can overwrite unmanaged state and does not reconcile already-running config. |
| `add-peer.sh` | Requires a name, generates keys/PSK, allocates an address, appends a peer and applies through sync/restart. Emits secret-bearing client config. Duplicate names fail; no mutation lock or apply rollback exists. |
| `remove-peer.sh` | Removes the saved peer block and attempts live removal/sync. Absent names fail. Live failures are suppressed; string `removed` contains the name rather than Android's expected Boolean/name fields. |
| `revoke-peer.sh` | Archives the peer block, then invokes removal. Archive includes the PSK and server peer metadata, not the full client credentials/profile. Failure can leave an archive without effective revocation. String `revoked` also differs from Android's model. |
| `sync-status.sh` | Returns saved peer metadata and runtime handshake/transfer counters. `running` observes systemd activity. Failed `wg show` is suppressed and counters default to zero; neither proves VPN health. |
| `backup.sh` | Emits JSON containing full server config and revoked count. It does not write an archive or safe backup file; protect the secret-bearing result. |
| `uninstall.sh` | Requires `--confirm`, optionally accepts `--purge-packages`, and stops the unit before checking/copying/removing config. No ownership gate exists; unmanaged config can be removed. Stop/purge/autoremove failures are suppressed. It removes the forwarding setup file but leaves the global forwarding value unchanged and retains a config backup under the Tunnl directory. |

## Output and safety boundaries

Normal responses and explicit `fail` paths use JSON on stdout; package diagnostics use stderr. Missing option values can fail through `shift 2`; unhandled strict-shell/tool failures can produce non-JSON errors. Shared JSON escaping does not cover every control character. Commands are non-interactive but not uniformly idempotent: duplicate add, absent remove and existing install can fail.

Never log or publish generated configs, backup payloads, private keys or PSKs. Shell-quoted arguments do not validate WireGuard config content. No script manages SSH configuration or intentionally edits SSH port rules; this is not a guarantee about all host firewall/package effects.

Required repairs remain separate from current behavior: ownership/input validation before privileged effects (C2), serialized mutations with honest apply/removal/recovery results (C3), agreed JSON/health contracts (C4), and harness postcondition/failure-redaction proof (C9). See the plan for acceptance and issues.

## Verification

Use offline syntax, temporary configs and injected commands before operational testing. Syntax alone does not establish mutation safety. The [disposable VPS harness](../../../docs/qa-test-plan.md#disposable-vps-integration-use-case) needs explicit authorization and is never a default check. Do not use these experimental scripts on production or user-managed VPN hosts while the safety gaps remain.
