#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

[[ -r "$WG_CONF" ]] || fail_json "not_installed" "$WG_CONF does not exist." 72

endpoint=$(conf_value ENDPOINT || true)
cidr=$(conf_value TUNNL_CIDR || true)
dns=$(conf_value TUNNL_DNS || true)
listen_port=$(grep -m1 -E '^ListenPort = ' "$WG_CONF" | awk '{print $3}' || true)
running=false
systemctl is-active --quiet "wg-quick@$WG_IFACE" 2>/dev/null && running=true

dump=""
if command_exists wg; then
  dump=$(wg show "$WG_IFACE" dump 2>/dev/null || true)
fi

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
  name && /^AllowedIPs = / { allowed_ips=$3; next }
  /^# END_PEER / { print name "\t" public_key "\t" allowed_ips; name=""; next }
' "$WG_CONF" | while IFS=$'\t' read -r name public_key allowed_ips; do
  [[ "$first" == true ]] || printf ','
  first=false
  rx="0"
  tx="0"
  latest_handshake="0"
  if [[ -n "$dump" && -n "$public_key" ]]; then
    row=$(printf '%s\n' "$dump" | awk -v key="$public_key" '$1 == key { print; exit }')
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
    "$(json_string "$latest_handshake")" \
    "$(json_string "$rx")" \
    "$(json_string "$tx")"
done

printf ']}\n'
