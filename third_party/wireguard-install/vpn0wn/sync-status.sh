#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

require_root
require_owned_config
lock_interface
require_owned_config
validate_peer_markers || fail_json "invalid_config" "Peer metadata is ambiguous." 73

endpoint=$(conf_value ENDPOINT || true)
cidr=$(conf_value TUNNL_CIDR || true)
dns=$(conf_value TUNNL_DNS || true)
listen_port=$(grep -m1 -E '^ListenPort = ' "$WG_CONF" | awk '{print $3}' || true)
running=null
if observe_runtime; then
  running=$RUNTIME_RUNNING
fi

dump=""
if [[ "$running" == true ]]; then
  dump=$(wg show "$WG_IFACE" dump 2>/dev/null) || dump=""
fi

json_counter() {
  local value=$1
  if [[ "$value" =~ ^[0-9]+$ && ${#value} -le 19 ]] &&
      [[ ${#value} -lt 19 || "$value" < 9223372036854775808 ]]; then
    json_string "$value"
  else
    printf null
  fi
}

printf '{"ok":true,"interface":%s,"running":%s,"endpoint":%s,"listenPort":%s,"cidr":%s,"dns":%s,"peers":[' \
  "$(json_string "$WG_IFACE")" \
  "$running" \
  "$(json_string "$endpoint")" \
  "$(json_string "$listen_port")" \
  "$(json_string "$cidr")" \
  "$(json_string "$dns")"

first=true
awk '
  /^# BEGIN_PEER / { name=$3; public_key=""; allowed_ips=""; next }
  name && /^PublicKey = / { public_key=$3; next }
  name && /^AllowedIPs = / { allowed_ips=substr($0, index($0, "=")+2); next }
  /^# END_PEER / { print name "\t" public_key "\t" allowed_ips; name=""; next }
' "$WG_CONF" | while IFS=$'\t' read -r name public_key allowed_ips; do
  [[ "$first" == true ]] || printf ','
  first=false
  rx=""
  tx=""
  latest_handshake=""
  if [[ -n "$dump" && -n "$public_key" ]]; then
    row=$(printf '%s\n' "$dump" | awk -F '\t' -v key="$public_key" '$1 == key && NF == 8 { print; exit }')
    if [[ -n "$row" ]]; then
      latest_handshake=$(printf '%s' "$row" | awk -F '\t' '{print $5}')
      rx=$(printf '%s' "$row" | awk -F '\t' '{print $6}')
      tx=$(printf '%s' "$row" | awk -F '\t' '{print $7}')
    fi
  fi
  printf '{"name":%s,"publicKey":%s,"allowedIps":%s,"latestHandshake":%s,"rxBytes":%s,"txBytes":%s}' \
    "$(json_string "$name")" \
    "$(json_string "$public_key")" \
    "$(json_string "$allowed_ips")" \
    "$(json_counter "$latest_handshake")" \
    "$(json_counter "$rx")" \
    "$(json_counter "$tx")"
done

printf ']}\n'
