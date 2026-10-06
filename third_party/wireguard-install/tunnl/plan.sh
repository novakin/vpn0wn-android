#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

endpoint=""
port="51820"
cidr="10.8.0.0/24"
dns="1.1.1.1, 1.0.0.1"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --endpoint) require_arg_value "$@"; endpoint=$2; shift 2 ;;
    --port) require_arg_value "$@"; port=$2; shift 2 ;;
    --cidr) require_arg_value "$@"; cidr=$2; shift 2 ;;
    --dns) require_arg_value "$@"; dns=$2; shift 2 ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_endpoint "$endpoint"
require_port "$port"
port=$((10#$port))
require_ipv4_cidr_24 "$cidr"
require_dns "$dns"
require_supported_os

steps=(
  "install wireguard iptables qrencode with apt"
  "create $WG_CONF with Tunnl management markers"
  "enable IPv4 forwarding in $FORWARD_CONF"
  "add NAT PostUp/PostDown rules for $cidr on $WG_IFACE"
  "enable and start wg-quick@$WG_IFACE"
)

printf '{"ok":true,"supported":true,"endpoint":%s,"port":%s,"cidr":%s,"dns":%s,"willOverwriteConfig":%s,"steps":[' \
  "$(json_string "$endpoint")" \
  "$(json_string "$port")" \
  "$(json_string "$cidr")" \
  "$(json_string "$dns")" \
  "$([[ -f "$WG_CONF" ]] && printf true || printf false)"

for i in "${!steps[@]}"; do
  [[ "$i" -gt 0 ]] && printf ','
  json_string "${steps[$i]}"
done

printf ']}\n'
