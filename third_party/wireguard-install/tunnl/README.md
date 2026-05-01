# Tunnl WireGuard Provisioning Scripts

This directory contains Tunnl-owned, non-interactive provisioning scripts derived
from the upstream `wireguard-install.sh` logic.

The Android app should call these scripts over SSH, not the upstream interactive
installer. Every command should be idempotent, accept explicit arguments, and
return parseable output.

## Command Contract

Implemented MVP commands:

- `detect.sh` — detect distro, package manager, firewall stack, WireGuard state,
  public endpoint, and support level.
- `plan.sh` — print the exact changes required for install/update without
  changing the server.
- `install.sh` — install and configure `wg0` from explicit inputs.
- `add-peer.sh` — create one peer and print the generated client config.
- `remove-peer.sh` — remove one peer from `wg0.conf`.
- `revoke-peer.sh` — disable one peer without deleting local metadata.
- `sync-status.sh` — return server health, peer list, handshakes, and transfer
  counters.
- `backup.sh` — print an archive or text bundle of server-side WireGuard config.
- `uninstall.sh` — remove Tunnl-managed WireGuard configuration after explicit
  confirmation from the app UI.

Current support is intentionally narrow: Ubuntu/Debian, `wg0`, IPv4 `/24`
address pools, IPv4 default routes, and iptables NAT. Android runtime
integration is still separate from these server-side scripts.

## Output Rules

- Prefer JSON for app parsing.
- Print logs to stderr and machine-readable data to stdout.
- Never prompt. Missing inputs must exit with a non-zero status and an error
  code.
- Never modify SSH/firewall rules that protect port 22.
- Do not download third-party binaries from services not controlled by the user.
- Do not disable global kernel/network settings during uninstall; the VPS may
  host other routing workloads.
