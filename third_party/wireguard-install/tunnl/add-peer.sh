#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

name=""
dns=""
allowed_ips="0.0.0.0/0"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) name=${2-}; shift 2 ;;
    --dns) dns=${2-}; shift 2 ;;
    --allowed-ips) allowed_ips=${2-}; shift 2 ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_root
[[ -r "$WG_CONF" ]] || fail_json "not_installed" "$WG_CONF does not exist." 72
[[ -n "$name" ]] || fail_json "missing_name" "--name is required." 64
name=$(sanitize_name "$name")
grep -q "^# BEGIN_PEER $name$" "$WG_CONF" && fail_json "peer_exists" "Peer already exists: $name" 73

cidr=$(conf_value TUNNL_CIDR || true)
endpoint=$(conf_value ENDPOINT || true)
dns=${dns:-$(conf_value TUNNL_DNS || true)}
[[ -n "$cidr" && -n "$endpoint" && -n "$dns" ]] || fail_json "missing_server_metadata" "Tunnl metadata is missing from $WG_CONF." 72

peer_ip=$(next_peer_address "$cidr")
listen_port=$(grep -m1 -E '^ListenPort = ' "$WG_CONF" | awk '{print $3}')
server_private_key=$(grep -m1 -E '^PrivateKey = ' "$WG_CONF" | awk '{print $3}')
server_public_key=$(wg_public_key_from_private "$server_private_key")
private_key=$(wg genkey)
public_key=$(wg_public_key_from_private "$private_key")
psk=$(wg genpsk)

cat >> "$WG_CONF" <<EOF

# BEGIN_PEER $name
[Peer]
PublicKey = $public_key
PresharedKey = $psk
AllowedIPs = $peer_ip/32
# END_PEER $name
EOF

wg syncconf "$WG_IFACE" <(wg-quick strip "$WG_IFACE") >&2 || systemctl restart "wg-quick@$WG_IFACE" >&2

client_config=$(cat <<EOF
[Interface]
Address = $peer_ip/32
DNS = $dns
PrivateKey = $private_key

[Peer]
PublicKey = $server_public_key
PresharedKey = $psk
AllowedIPs = $allowed_ips
Endpoint = $endpoint:$listen_port
PersistentKeepalive = 25
EOF
)

printf '{"ok":true,"name":%s,"address":%s,"publicKey":%s,"config":%s}\n' \
  "$(json_string "$name")" \
  "$(json_string "$peer_ip")" \
  "$(json_string "$public_key")" \
  "$(json_string "$client_config")"
