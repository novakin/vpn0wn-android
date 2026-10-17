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

server_public_key=""
server_private_key=$(awk '$1 == "PrivateKey" {print $3; exit}' "$WG_CONF")
if [[ -n "$server_private_key" ]] && configured_public_key=$(wg_public_key_from_private "$server_private_key" 2>/dev/null) &&
    [[ "$configured_public_key" =~ ^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$ ]]; then
  if [[ "$running" == false ]]; then
    server_public_key=$configured_public_key
  elif [[ "$running" == true ]]; then
    live_public_key=$(wg show "$WG_IFACE" public-key 2>/dev/null) || live_public_key=""
    if [[ "$live_public_key" == "$configured_public_key" ]]; then
      server_public_key=$configured_public_key
    fi
  fi
fi
server_public_key_json=null
if [[ -n "$server_public_key" ]]; then
  server_public_key_json=$(json_string "$server_public_key")
fi

peer_keys_json() {
  local inventory=$1 key result='[' separator=''
  local -A seen=()
  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    (require_public_key "$key") >/dev/null 2>&1 || return 1
    [[ -z "${seen[$key]+set}" ]] || return 1
    seen[$key]=1
    result+="$separator$(json_string "$key")"
    separator=','
  done <<< "$inventory"
  printf '%s]' "$result"
}

configured_peer_keys_json=null
if configured_peer_keys=$(awk '
  function finish_peer() {if (section == "peer" && keys != 1) invalid=1}
  {
    line=$0; sub(/#.*/, "", line); sub(/^[[:space:]]+/, "", line); sub(/[[:space:]]+$/, "", line)
    if (line == "") next
    if (line ~ /^\[/) {
      finish_peer()
      heading=tolower(line)
      if (heading == "[peer]") {section="peer"; keys=0}
      else if (heading == "[interface]") section="interface"
      else {section=""; invalid=1}
      next
    }
    separator=index(line, "=")
    if (separator == 0 || section == "") {invalid=1; next}
    field=substr(line, 1, separator-1); sub(/[[:space:]]+$/, "", field)
    if (tolower(field) == "publickey") {
      if (section != "peer") {invalid=1; next}
      keys++
      key=substr(line, separator+1); sub(/^[[:space:]]+/, "", key); sub(/[[:space:]]+$/, "", key)
      if (key == "") invalid=1
      print key
    }
  }
  END {finish_peer(); exit invalid}
' "$WG_CONF"); then
  configured_peer_keys_json=$(peer_keys_json "$configured_peer_keys") || configured_peer_keys_json=null
fi

runtime_peer_keys_json=null
if [[ "$running" == false ]]; then
  runtime_peer_keys_json='[]'
elif [[ "$running" == true ]] && runtime_peer_keys=$(wg show "$WG_IFACE" peers 2>/dev/null); then
  runtime_peer_keys_json=$(peer_keys_json "$runtime_peer_keys") || runtime_peer_keys_json=null
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

printf '{"ok":true,"interface":%s,"running":%s,"endpoint":%s,"listenPort":%s,"cidr":%s,"dns":%s,"serverPublicKey":%s,"runtimePeerPublicKeys":%s,"configuredPeerPublicKeys":%s,"peers":[' \
  "$(json_string "$WG_IFACE")" \
  "$running" \
  "$(json_string "$endpoint")" \
  "$(json_string "$listen_port")" \
  "$(json_string "$cidr")" \
  "$(json_string "$dns")" \
  "$server_public_key_json" \
  "$runtime_peer_keys_json" \
  "$configured_peer_keys_json"

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
