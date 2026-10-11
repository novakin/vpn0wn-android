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
    --endpoint) require_arg_value "$@"; endpoint=$2; shift 2 ;;
    --port) require_arg_value "$@"; port=$2; shift 2 ;;
    --cidr) require_arg_value "$@"; cidr=$2; shift 2 ;;
    --dns) require_arg_value "$@"; dns=$2; shift 2 ;;
    --force) force=true; shift ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_root
require_supported_os
require_endpoint "$endpoint"
require_port "$port"
port=$((10#$port))
require_ipv4_cidr_24 "$cidr"
require_dns "$dns"
require_owned_or_missing_config
require_owned_forwarding
[[ "$force" == true || ! -e "$WG_CONF" ]] || fail_json "config_exists" "$WG_CONF already exists. Pass --force to replace it." 73

export DEBIAN_FRONTEND=noninteractive
lock_host
lock_interface
require_owned_or_missing_config
require_owned_forwarding
[[ "$force" == true || ! -e "$WG_CONF" ]] || fail_json "config_exists" "Configuration already exists. Pass --force to replace it." 73
begin_mutation install
PACKAGE_EFFECTS=true
apt-get update >/dev/null 2>&1 || mutation_abort package_failed
apt-get install -y wireguard iptables qrencode >/dev/null 2>&1 || mutation_abort package_failed

server_private_key=$(wg genkey 2>/dev/null) || mutation_abort key_failed
server_public_key=$(wg_public_key_from_private "$server_private_key" 2>/dev/null) || mutation_abort key_failed
server_address=$(server_address_for_cidr "$cidr")

cat > "$CANDIDATE" <<EOF
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

prepare_candidate
if [[ "$WAS_RUNNING" == true ]]; then
  # wg-quick must tear down the old address/hooks using the old config.
  SYSTEM_CHANGED=true
  systemctl stop "wg-quick@$WG_IFACE" >/dev/null 2>&1 || mutation_abort stop_failed
  observe_runtime && [[ "$RUNTIME_RUNNING" == false ]] && observe_service || mutation_abort postcondition_failed
fi
replace_config
cat > "$RECOVERY_DIR/candidate.forward" <<EOF
# TUNNL_MANAGED 1
net.ipv4.ip_forward=1
EOF
replace_forwarding "$RECOVERY_DIR/candidate.forward" || mutation_abort write_failed
sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || mutation_abort forwarding_failed

SYSTEM_CHANGED=true
systemctl enable "wg-quick@$WG_IFACE" >/dev/null 2>&1 || mutation_abort enable_failed
systemctl restart "wg-quick@$WG_IFACE" >/dev/null 2>&1 || mutation_abort apply_failed
observe_runtime && [[ "$RUNTIME_RUNNING" == true ]] && observe_service && [[ "$SERVICE_ENABLED" == true ]] || mutation_abort postcondition_failed
verify_runtime_config "$WG_CONF" && verify_address "$WG_CONF" || mutation_abort postcondition_failed
finish_mutation

printf '{"ok":true,"interface":%s,"endpoint":%s,"port":%s,"cidr":%s,"serverPublicKey":%s,"cleanupPath":%s}\n' \
  "$(json_string "$WG_IFACE")" \
  "$(json_string "$endpoint")" \
  "$(json_string "$port")" \
  "$(json_string "$cidr")" \
  "$(json_string "$server_public_key")" \
  "$(json_string "$CLEANUP_PATH")"
