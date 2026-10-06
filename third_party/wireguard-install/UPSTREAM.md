# Nyr wireguard-install

Last updated: 2026-10-06 (Europe/Paris).

Source: https://github.com/Nyr/wireguard-install

Imported from commit: `3b85b2702ffcb2ce670c29bd90c6cae4a5bcc753`

License: MIT, see `LICENSE.txt`.

## Purpose

This snapshot is kept as the upstream reference for Tunnl's future self-hosted
WireGuard provisioning flow.

The upstream `original/wireguard-install.sh` script is interactive and designed
for terminal use. Tunnl must not execute it directly from Android. App-facing
provisioning should use small non-interactive scripts under `tunnl/` with stable
arguments and machine-readable output.

## Update Process

1. Review upstream changes and security implications.
2. Replace `original/wireguard-install.sh` from a pinned upstream commit.
3. Update this file's commit hash.
4. Port only intentional changes into `tunnl/`.
5. Keep the MIT license notice with distributed copies.
