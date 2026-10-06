# Tunnl WireGuard provisioning scripts

Last updated: 2026-10-06 (Europe/Paris).

These Tunnl-owned Bash commands adapt the [pinned upstream reference](../UPSTREAM.md) into a non-interactive server surface. Android uses these scripts rather than the upstream interactive installer. Concrete Android SSH transport and live UI actions remain unfinished.

This guide describes the C2–C3 source repair and its offline proof. Confirmation does not establish ownership; successful JSON does not establish traffic or handshake health. [Backend contracts](../../../docs/backend-contracts.md#provisioning-command-surface) owns Android arguments/results; [the plan](../../../docs/improvement-plan.md#codebase-actions) tracks delivery and remaining C4/C9 work.

## Scope and defaults

Install/plan support Ubuntu/Debian and apt. Default state is `wg0` under `/etc/wireguard`, with metadata under `/etc/wireguard/tunnl`. Shared `WG_IFACE`, `WG_DIR`, `WG_CONF` and `TUNNL_DIR` environment overrides exist. `FORWARD_CONF` defaults to `/etc/sysctl.d/99-tunnl-wireguard-forward.conf`; `WG_LOCK_FILE` and `WG_HOST_LOCK_FILE` select native lock paths. Overrides support private offline fixtures; alternate live interfaces/platforms have not been validated. Allocation supports IPv4 `.0/24` pools, IPv4 client default routes and iptables NAT.

Plan/install require a single hostname/IPv4 token or bracketed IPv6 endpoint and default to UDP `51820`, CIDR `10.8.0.0/24` and DNS `1.1.1.1, 1.0.0.1`. Ports are bounded decimal values. DNS accepts comma-separated address/search-domain tokens; client AllowedIPs accepts comma-separated IPv4/IPv6 CIDRs. Controls, newlines, empty tokens and config-section/hook injection are rejected. IPv6 checks are lexical, not a full address parser or resolver. Install uses configured apt repositories for `wireguard`, `iptables` and `qrencode`.

## Current command behavior

| Command | Source-observed result and limitation |
| --- | --- |
| `detect.sh` | Reports distro, tools/firewall hints, config/package presence and forwarding. Endpoint comes from config metadata, not external public-IP discovery. |
| `plan.sh` | Validates endpoint, port, CIDR, DNS and OS, then emits static install steps and an overwrite flag. It changes no state and is not an exact migration/update plan. It rejects Android's forwarded `--force`; C4. |
| `install.sh` | Requires root and ownership, stages config, enables/restarts the unit and checks service, server key, port, address and peer state. Forced reinstall stops the old unit with its old config/hooks before replacement. Existing config fails without `--force`; force never bypasses ownership. |
| `add-peer.sh` | Locks allocation/read/write/apply, rejects duplicate names or ambiguous markers, and returns a secret-bearing profile after disk/runtime postconditions. |
| `remove-peer.sh` | Removes one ordered, unambiguous block and checks disk/live peer removal. Absent names fail. String `removed` remains the name rather than Android's expected Boolean/name fields; C4. |
| `revoke-peer.sh` | Shares removal under the same lock, then publishes a unique private archive. Archive includes the PSK and server peer metadata, not the full client profile. String `revoked` remains an Android schema gap; C4. |
| `sync-status.sh` | Returns saved peer metadata and runtime handshake/transfer counters. `running` observes systemd activity. Failed `wg show` is suppressed and counters default to zero; neither proves VPN health. |
| `backup.sh` | Emits JSON containing full server config and revoked count. It does not write an archive or safe backup file; protect the secret-bearing result. |
| `uninstall.sh` | Requires `--confirm` and ownership, writes a unique private backup before stop/disable/delete, and verifies absence. Optional purge refuses other config/live interfaces and checks named package absence. No autoremove runs. Global forwarding stays unchanged; its managed file stays when another interface/config exists. |

## Ownership, locking and recovery

Mutations require a regular non-symlink config containing the exact existing `# TUNNL_MANAGED 1` marker. Missing config fails peer/uninstall commands. Forwarding replacement/removal accepts only the exact legacy `net.ipv4.ip_forward=1` file or its marked equivalent. Config/forwarding checks run before lock/filesystem/service/package effects and are repeated under lock. Fresh install also observes interfaces under lock before state writes/packages and rejects a collision without owned config, even when `wg` is absent. The marker is an ownership convention, not authentication against another root process.

All writers take a stable per-interface `flock` at `/run/lock/tunnl-wireguard-$WG_IFACE.lock`. Install/uninstall first take `/run/lock/tunnl-wireguard-host.lock` for shared packages/forwarding, then the interface lock. Locks are never deleted by uninstall. Readers such as sync/backup do not lock; readback can race a writer. External tools that bypass these locks remain outside serialization.

Private recovery directories live on the config filesystem with mode 0700; configs, runtime snapshots, backups and revoke archives use 0600. Candidates pass checked `wg-quick strip` before atomic rename. A running peer mutation uses `wg syncconf`, falls back to restart on command failure, then checks server key/port and exact peer keys/routes/PSKs. A stopped interface receives persistent config only and must remain absent; this is not live revocation proof. Strip extracts wg-quick fields; it is not a complete native validator for a stopped interface.

Write/apply/stop/disable/postcondition failures attempt to restore old disk/native runtime and, for install/uninstall, service state. Runtime snapshots preserve pre-existing live state independently of saved config. Peer recovery does not snapshot or verify prior systemd unit state; restart fallback can change unit activity for a manually started interface. Failure JSON reports `recovery` as `unchanged`, `restored` or `incomplete`; incomplete recovery retains private files at `recoveryPath`. An uninstall backup is retained and reported as `artifact` even on failure. Recovery never includes raw tool diagnostics or config payloads.

Package installation/purge and global forwarding enable are not an OS transaction. After attempted package changes, failure explicitly warns that those effects may remain. Partial purge can leave tools unavailable and recovery incomplete; retain the backup and inspect state before retrying. Uninstall success with purge requires named packages absent/not-installed, not merely residual `config-files` status. Existing unrelated packages are not autoremoved.

After operation postconditions and archive publication commit, cleanup does not roll back using partially deleted snapshots. Success includes `cleanupPath`: empty when cleanup completed, otherwise the private residue needing operator inspection/removal. Signals during mutation trigger recovery where Bash can handle them; SIGKILL, host loss and a lost remote response still require reconciliation.

## Output and safety boundaries

Normal responses and explicit failures use JSON on stdout. Mutating-command diagnostics omit raw package/tool output. Value-taking options in plan/install/peer commands reject missing values. Detect/sync/backup and pre-transaction shell failures are not a uniformly guarded error protocol; shared JSON escaping also does not cover every control character. Commands are non-interactive but not uniformly idempotent: duplicate add, absent remove and existing install can fail.

Never log or publish generated configs, backup payloads, private keys or PSKs. Shell-quoted arguments do not validate WireGuard config content. No script manages SSH configuration or intentionally edits SSH port rules; this is not a guarantee about all host firewall/package effects.

Remaining repairs include agreed Android JSON/health contracts (C4), harness postcondition/failure-redaction proof (C9), and real SSH/UI integration (C11–C12). No real-server validation is claimed by the C2–C3 offline suite.

## Verification

Run [the offline suite](../../../scripts/tests/provisioning_safety_test.sh) through an unprivileged Linux user namespace; see [development commands/evidence](../../../docs/development.md#c2c3-offline-provisioning-safety--2026-10-06). Fixtures use synthetic keys, private temporary files and fake privileged commands. Syntax alone does not establish mutation safety. The [disposable VPS harness](../../../docs/qa-test-plan.md#disposable-vps-integration-use-case) still needs C9 repairs and explicit authorization. No APK, SSH or live VPN proof was run for this change.
