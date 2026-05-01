#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

endpoint=""
port="51820"
cidr="10.8.0.0/24"
dns="1.1.1.1, 1.0.0.1"
force=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --endpoint) endpoint=${2-}; shift 2 ;;
    --port) port=${2-}; shift 2 ;;
    --cidr) cidr=${2-}; shift 2 ;;
    --dns) dns=${2-}; shift 2 ;;
    --force) force=true; shift ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_root
require_supported_os
[[ -n "$endpoint" ]] || fail_json "missing_endpoint" "--endpoint is required." 64
[[ "$port" =~ ^[0-9]+$ && "$port" -ge 1 && "$port" -le 65535 ]] || fail_json "invalid_port" "Port must be between 1 and 65535." 64
require_ipv4_cidr_24 "$cidr"
[[ "$force" == true || ! -e "$WG_CONF" ]] || fail_json "config_exists" "$WG_CONF already exists. Pass --force to replace it." 73

export DEBIAN_FRONTEND=noninteractive
apt-get update >&2
apt-get install -y wireguard iptables qrencode >&2

mkdir -p "$WG_DIR" "$TUNNL_DIR"
chmod 700 "$WG_DIR" "$TUNNL_DIR"

server_private_key=$(wg genkey)
server_public_key=$(wg_public_key_from_private "$server_private_key")
server_address=$(server_address_for_cidr "$cidr")

umask 077
cat > "$WG_CONF" <<EOF
# TUNNL_MANAGED 1
# ENDPOINT $endpoint
# TUNNL_CIDR $cidr
# TUNNL_DNS $dns
[Interface]
Address = $server_address
PrivateKey = $server_private_key
ListenPort = $port
SaveConfig = false
PostUp = iptables -t nat -A POSTROUTING -s $cidr ! -d $cidr -j MASQUERADE
PostDown = iptables -t nat -D POSTROUTING -s $cidr ! -d $cidr -j MASQUERADE
EOF

cat > /etc/sysctl.d/99-tunnl-wireguard-forward.conf <<EOF
net.ipv4.ip_forward=1
EOF
sysctl -w net.ipv4.ip_forward=1 >&2

systemctl enable --now "wg-quick@$WG_IFACE" >&2

printf '{"ok":true,"interface":%s,"endpoint":%s,"port":%s,"cidr":%s,"serverPublicKey":%s}\n' \
  "$(json_string "$WG_IFACE")" \
  "$(json_string "$endpoint")" \
  "$(json_string "$port")" \
  "$(json_string "$cidr")" \
  "$(json_string "$server_public_key")"
