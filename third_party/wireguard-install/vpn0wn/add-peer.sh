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
    --name) require_arg_value "$@"; name=$2; shift 2 ;;
    --dns) require_arg_value "$@"; dns=$2; shift 2 ;;
    --allowed-ips) require_arg_value "$@"; allowed_ips=$2; shift 2 ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_root
require_owned_config
[[ -n "$name" ]] || fail_json "missing_name" "--name is required." 64
name=$(sanitize_name "$name")
require_allowed_ips "$allowed_ips"
[[ -z "$dns" ]] || require_dns "$dns"
lock_interface
require_owned_config
validate_peer_markers || fail_json "invalid_config" "Peer metadata is ambiguous." 73
grep -q "^# BEGIN_PEER $name$" "$WG_CONF" && fail_json "peer_exists" "Peer already exists: $name" 73

cidr=$(conf_value TUNNL_CIDR || true)
endpoint=$(conf_value ENDPOINT || true)
dns=${dns:-$(conf_value TUNNL_DNS || true)}
[[ -n "$cidr" && -n "$endpoint" && -n "$dns" ]] || fail_json "missing_server_metadata" "Tunnl metadata is missing from $WG_CONF." 72
require_ipv4_cidr_24 "$cidr"
require_endpoint "$endpoint"
require_dns "$dns"
require_allowed_ips "$allowed_ips"

listen_port=$(grep -m1 -E '^ListenPort = ' "$WG_CONF" | awk '{print $3}')
server_private_key=$(grep -m1 -E '^PrivateKey = ' "$WG_CONF" | awk '{print $3}')
require_port "$listen_port"
begin_mutation peer
peer_ip=$(next_peer_address "$cidr") || mutation_abort address_pool_full
server_public_key=$(wg_public_key_from_private "$server_private_key" 2>/dev/null) || mutation_abort key_failed
private_key=$(wg genkey 2>/dev/null) || mutation_abort key_failed
public_key=$(wg_public_key_from_private "$private_key" 2>/dev/null) || mutation_abort key_failed
psk=$(wg genpsk 2>/dev/null) || mutation_abort key_failed

cp -- "$WG_CONF" "$CANDIDATE" 2>/dev/null || mutation_abort write_failed
cat >> "$CANDIDATE" <<EOF

# BEGIN_PEER $name
[Peer]
PublicKey = $public_key
PresharedKey = $psk
AllowedIPs = $peer_ip/32
# END_PEER $name
EOF

prepare_candidate
apply_peer_config

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

finish_mutation
printf '{"ok":true,"name":%s,"address":%s,"publicKey":%s,"config":%s,"cleanupPath":%s}\n' \
  "$(json_string "$name")" \
  "$(json_string "$peer_ip")" \
  "$(json_string "$public_key")" \
  "$(json_string "$client_config")" \
  "$(json_string "$CLEANUP_PATH")"
