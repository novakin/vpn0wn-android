#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

export LC_ALL=C
MAX_CONFIG_BYTES=262144
MAX_REVOKED_BYTES=16384
MAX_REVOKED_FILES=1024
MAX_INPUT_BYTES=786432

invalid_restore_config() {
  fail_json "invalid_config" "Snapshot contains unsupported managed server state." 73
}

read_restore_file() {
  local file=$1 maximum=$2 size content
  [[ -f "$file" && ! -L "$file" ]] || fail_json "unsafe_path" "Restore input must contain regular files." 73
  size=$(stat -c %s -- "$file" 2>/dev/null) || fail_json "invalid_config" "Unable to read restore input." 74
  [[ "$size" -gt 0 && "$size" -le "$maximum" ]] || invalid_restore_config
  content=$(head -c "$((maximum + 1))" -- "$file" 2>/dev/null && printf '.') 2>/dev/null || invalid_restore_config
  RESTORE_CONTENT=${content%?}
  [[ ${#RESTORE_CONTENT} -eq "$size" ]] || invalid_restore_config
  printf '%s' "$RESTORE_CONTENT" | iconv -f UTF-8 -t UTF-8 >/dev/null 2>&1 || invalid_restore_config
  [[ "${RESTORE_CONTENT//$'\n'/}" != *[[:cntrl:]]* ]] || invalid_restore_config
  input_bytes=$((input_bytes + size))
  [[ "$input_bytes" -le "$MAX_INPUT_BYTES" ]] || invalid_restore_config
  RESTORE_LINES=()
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] || RESTORE_LINES+=("$line")
  done <<< "$RESTORE_CONTENT"
}

valid_restore_key() {
  [[ "$1" =~ ^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$ ]]
}

parse_restore_peer() {
  local start=$1 historical=${2:-false} line octet address
  local -a address_octets
  line=${RESTORE_LINES[start]-}
  [[ "$line" =~ ^#\ BEGIN_PEER\ ([A-Za-z0-9_-]{1,32})$ ]] || invalid_restore_config
  PEER_NAME=${BASH_REMATCH[1]}
  [[ ${RESTORE_LINES[start+1]-} == '[Peer]' && ${RESTORE_LINES[start+2]-} == 'PublicKey = '* ]] || invalid_restore_config
  PEER_KEY=${RESTORE_LINES[start+2]#'PublicKey = '}
  valid_restore_key "$PEER_KEY" || invalid_restore_config
  PEER_PSK=""
  PEER_END=$((start + 3))
  if [[ ${RESTORE_LINES[PEER_END]-} == 'PresharedKey = '* ]]; then
    PEER_PSK=${RESTORE_LINES[PEER_END]#'PresharedKey = '}
    valid_restore_key "$PEER_PSK" || invalid_restore_config
    PEER_END=$((PEER_END + 1))
  fi
  [[ ${RESTORE_LINES[PEER_END]-} == 'AllowedIPs = '* ]] || invalid_restore_config
  PEER_ROUTE=${RESTORE_LINES[PEER_END]#'AllowedIPs = '}
  [[ "$PEER_ROUTE" == */32 ]] || invalid_restore_config
  address=${PEER_ROUTE%/32}
  (require_ipv4_address "$address" invalid_config) >/dev/null 2>&1 || invalid_restore_config
  [[ "$historical" == true || "${address%.*}" == "$subnet_prefix" ]] || invalid_restore_config
  IFS='.' read -r -a address_octets <<< "$address"
  for octet in "${address_octets[@]}"; do
    [[ "$octet" == "$((10#$octet))" ]] || invalid_restore_config
  done
  octet=${address##*.}
  [[ $((10#$octet)) -ge 2 && $((10#$octet)) -le 254 ]] || invalid_restore_config
  [[ ${RESTORE_LINES[PEER_END+1]-} == "# END_PEER $PEER_NAME" ]] || invalid_restore_config
  PEER_END=$((PEER_END + 2))
}

read_restore_config() {
  local index octet
  local -a subnet_octets
  read_restore_file "$1" "$MAX_CONFIG_BYTES"
  RESTORE_CONFIG=$RESTORE_CONTENT
  [[ ${#RESTORE_LINES[@]} -ge 11 && ${RESTORE_LINES[0]} == '# TUNNL_MANAGED 1' &&
     ${RESTORE_LINES[1]} == '# ENDPOINT '* && ${RESTORE_LINES[2]} == '# TUNNL_CIDR '* &&
     ${RESTORE_LINES[3]} == '# TUNNL_DNS '* && ${RESTORE_LINES[4]} == '[Interface]' &&
     ${RESTORE_LINES[6]} == 'PrivateKey = '* && ${RESTORE_LINES[7]} == 'ListenPort = '* ]] || invalid_restore_config
  source_endpoint=${RESTORE_LINES[1]#'# ENDPOINT '}
  cidr=${RESTORE_LINES[2]#'# TUNNL_CIDR '}
  dns=${RESTORE_LINES[3]#'# TUNNL_DNS '}
  server_private_key=${RESTORE_LINES[6]#'PrivateKey = '}
  source_port=${RESTORE_LINES[7]#'ListenPort = '}
  if ! (require_endpoint "$source_endpoint"; require_ipv4_cidr_24 "$cidr"; require_dns "$dns"; require_port "$source_port") >/dev/null 2>&1; then
    invalid_restore_config
  fi
  IFS='.' read -r -a subnet_octets <<< "${cidr%/*}"
  for octet in "${subnet_octets[@]}"; do
    [[ "$octet" == "$((10#$octet))" ]] || invalid_restore_config
  done
  valid_restore_key "$server_private_key" || invalid_restore_config
  subnet_prefix=$(cidr_prefix "$cidr")
  server_address=$(server_address_for_cidr "$cidr")
  [[ ${RESTORE_LINES[5]} == "Address = $server_address" && ${RESTORE_LINES[8]} == 'SaveConfig = false' &&
     ${RESTORE_LINES[9]} == "PostUp = iptables -t nat -A POSTROUTING -s $cidr ! -d $cidr -j MASQUERADE" &&
     ${RESTORE_LINES[10]} == "PostDown = iptables -t nat -D POSTROUTING -s $cidr ! -d $cidr -j MASQUERADE" ]] || invalid_restore_config
  active_names=()
  active_keys=()
  active_routes=()
  active_bodies=()
  active_config=""
  peer_count=0
  index=11
  while [[ "$index" -lt ${#RESTORE_LINES[@]} ]]; do
    parse_restore_peer "$index"
    [[ -z ${active_names[$PEER_NAME]+x} && -z ${active_keys[$PEER_KEY]+x} && -z ${active_routes[$PEER_ROUTE]+x} ]] || invalid_restore_config
    active_names[$PEER_NAME]=1
    active_keys[$PEER_KEY]=$PEER_NAME
    active_routes[$PEER_ROUTE]=1
    active_bodies[$PEER_KEY]="$PEER_NAME $PEER_ROUTE $PEER_PSK"
    active_config+=$'\n'"# BEGIN_PEER $PEER_NAME"$'\n[Peer]\n'"PublicKey = $PEER_KEY"$'\n'
    [[ -z "$PEER_PSK" ]] || active_config+="PresharedKey = $PEER_PSK"$'\n'
    active_config+="AllowedIPs = $PEER_ROUTE"$'\n'"# END_PEER $PEER_NAME"$'\n'
    peer_count=$((peer_count + 1))
    index=$PEER_END
  done
}

read_restore_history() {
  local file archive_name archived_peer_name
  revoked_names=()
  revoked_bodies=()
  [[ -e "$1" || -L "$1" ]] || return 0
  [[ -d "$1" && ! -L "$1" ]] || fail_json unsafe_path "Restore history requires a regular directory." 73
  revoked_files=()
  if ! find "$1" -mindepth 1 -maxdepth 1 -print0 2>/dev/null | sort -z | mapfile -d '' -t revoked_files; then
    invalid_restore_config
  fi
  [[ ${#revoked_files[@]} -le "$MAX_REVOKED_FILES" ]] || invalid_restore_config
  for file in "${revoked_files[@]}"; do
    archive_name=${file##*/}
    [[ "$archive_name" =~ $REVOKED_ARCHIVE_NAME_PATTERN ]] || invalid_restore_config
    archived_peer_name=${BASH_REMATCH[1]}
    input_bytes=$((input_bytes + ${#archive_name}))
    read_restore_file "$file" "$MAX_REVOKED_BYTES"
    parse_restore_peer 0 true
    [[ "$PEER_NAME" == "$archived_peer_name" && "$PEER_END" -eq ${#RESTORE_LINES[@]} && -z ${active_keys[$PEER_KEY]+x} ]] || invalid_restore_config
    revoked_names+=("$archive_name")
    revoked_bodies+=("$RESTORE_CONTENT")
  done
}

restore_resource_failure() {
  if [[ ${replacement_admitted:-false} == true ]]; then
    mutation_abort "$1" "${3:-74}"
  fi
  fail_json "$@"
}

observed_ipv4_range() {
  local prefix=$1 address length value mask octet
  local -a octets
  [[ "$prefix" =~ ^([^/]+)/([0-9]{1,2})$ ]] || restore_resource_failure "runtime_unknown" "Unable to determine IPv4 subnet use." 74
  address=${BASH_REMATCH[1]}
  length=${BASH_REMATCH[2]}
  (require_ipv4_address "$address" invalid_config) >/dev/null 2>&1 || restore_resource_failure "runtime_unknown" "Unable to determine IPv4 subnet use." 74
  [[ $((10#$length)) -le 32 ]] || restore_resource_failure "runtime_unknown" "Unable to determine IPv4 subnet use." 74
  IFS='.' read -r -a octets <<< "$address"
  value=0
  for octet in "${octets[@]}"; do
    value=$(((value << 8) | 10#$octet))
  done
  mask=$(((0xffffffff << (32 - 10#$length)) & 0xffffffff))
  IPV4_RANGE_FIRST=$((value & mask))
  IPV4_RANGE_LAST=$((IPV4_RANGE_FIRST | (0xffffffff ^ mask)))
}

classify_owned_restore_route() {
  local owned_cidr=$2 owned_address=${3%/*} device_clause=" dev $WG_IFACE"
  # ip suppresses the device field only when the query already selected that interface.
  if [[ ${4:-false} == true ]]; then
    device_clause=
  fi
  local -a fields
  read -r -a fields <<< "$1"
  # Only these native address routes return when the managed interface is recreated.
  case "${fields[*]}" in
    "$owned_cidr$device_clause proto kernel scope link src $owned_address"|\
      "$owned_cidr$device_clause table main proto kernel scope link src $owned_address")
      RESTORE_ROUTE_KIND=connected ;;
    "local $owned_address$device_clause table local proto kernel scope host src $owned_address")
      RESTORE_ROUTE_KIND=local ;;
    "broadcast ${owned_cidr%/*}$device_clause table local proto kernel scope link src $owned_address")
      RESTORE_ROUTE_KIND=broadcast_first ;;
    "broadcast ${owned_cidr%.0/*}.255$device_clause table local proto kernel scope link src $owned_address")
      RESTORE_ROUTE_KIND=broadcast_last ;;
    *) return 1 ;;
  esac
}

require_unused_subnet() {
  local target_cidr=${1:-$cidr} owned_cidr=${2-} owned_address=${3-}
  local target_first target_last addresses routes line selector prefix RESTORE_ROUTE_KIND
  local -a fields
  local -A owned_routes=()
  observed_ipv4_range "$target_cidr"
  target_first=$IPV4_RANGE_FIRST
  target_last=$IPV4_RANGE_LAST
  addresses=$(ip -4 -o address show 2>/dev/null) || restore_resource_failure "runtime_unknown" "Unable to determine IPv4 subnet use." 74
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    read -r -a fields <<< "$line"
    [[ ${#fields[@]} -ge 4 && ${fields[0]} =~ ^[0-9]+:$ && ${fields[2]} == inet ]] || restore_resource_failure "runtime_unknown" "Unable to determine IPv4 subnet use." 74
    if [[ -n "$owned_cidr" && ${fields[1]%:} == "$WG_IFACE" && ${fields[3]} == "$owned_address" ]]; then
      continue
    fi
    observed_ipv4_range "${fields[3]}"
    [[ "$IPV4_RANGE_FIRST" -gt "$target_last" || "$IPV4_RANGE_LAST" -lt "$target_first" ]] || restore_resource_failure "subnet_in_use" "Restore IPv4 subnet is already in use." 73
  done <<< "$addresses"
  for selector in root match; do
    owned_routes=()
    routes=$(ip -4 -o route show table all "$selector" "$target_cidr" 2>/dev/null) || restore_resource_failure "runtime_unknown" "Unable to determine IPv4 subnet use." 74
    while IFS= read -r line; do
      [[ -n "$line" ]] || continue
      read -r -a fields <<< "$line"
      [[ ${#fields[@]} -ge 2 ]] || restore_resource_failure "runtime_unknown" "Unable to determine IPv4 subnet use." 74
      prefix=${fields[0]}
      case "$prefix" in
        default|0.0.0.0/0) continue ;;
        unicast|local|broadcast|multicast|anycast|blackhole|unreachable|prohibit|throw) prefix=${fields[1]} ;;
      esac
      [[ "$prefix" != default ]] || prefix=0.0.0.0/0
      [[ "$prefix" == */* ]] || prefix+=/32
      if [[ -n "$owned_cidr" ]] && classify_owned_restore_route "$line" "$owned_cidr" "$owned_address"; then
        [[ -z ${owned_routes[$RESTORE_ROUTE_KIND]+x} ]] || restore_resource_failure "subnet_in_use" "Restore found ambiguous interface routes." 73
        owned_routes[$RESTORE_ROUTE_KIND]=1
        continue
      fi
      observed_ipv4_range "$prefix"
      [[ "$IPV4_RANGE_FIRST" -gt "$target_last" || "$IPV4_RANGE_LAST" -lt "$target_first" ]] || restore_resource_failure "subnet_in_use" "Restore IPv4 subnet is already in use." 73
    done <<< "$routes"
  done
}

require_restore_port() {
  local owned_port=${1-} sockets
  sockets=$(ss -H -uan "sport = :$port" 2>/dev/null) || restore_resource_failure "runtime_unknown" "Unable to determine UDP port availability." 74
  [[ "$port" == "$owned_port" || -z "${sockets//[[:space:]]/}" ]] || restore_resource_failure "port_in_use" "Restore UDP port is already in use." 73
}

require_restore_state_paths() {
  [[ ! -L "$WG_DIR" && ! -L "$TUNNL_DIR" && ! -L "$TUNNL_DIR/revoked" ]] || fail_json "unsafe_path" "Restore refuses symlinked state paths." 73
  [[ ! -e "$WG_DIR" || -d "$WG_DIR" ]] && [[ ! -e "$TUNNL_DIR" || -d "$TUNNL_DIR" ]] || fail_json "unsafe_path" "Restore requires regular state directories." 73
  local owner mode directory_attributes
  if [[ -d "$WG_DIR" ]]; then
    directory_attributes=$(stat -c '%u %a' -- "$WG_DIR" 2>/dev/null) || fail_json "unsafe_path" "Unable to inspect the WireGuard directory." 73
    read -r owner mode <<< "$directory_attributes"
    [[ "$owner" == 0 && "$mode" =~ ^[0-7]{3,4}$ && $((8#$mode & 0022)) -eq 0 ]] || fail_json "unsafe_path" "Restore requires a protected root-owned WireGuard directory." 73
  fi
  if [[ -d "$TUNNL_DIR" ]]; then
    [[ $(stat -c '%u %a' -- "$TUNNL_DIR" 2>/dev/null) == '0 700' ]] || fail_json "unsafe_path" "Restore requires a private root-owned state directory." 73
  fi
}

require_fresh_destination() {
  [[ ! -e "$WG_CONF" && ! -L "$WG_CONF" ]] || fail_json "config_exists" "Restore requires an absent WireGuard configuration." 73
  require_restore_state_paths
  if [[ -e "$TUNNL_DIR/revoked" ]]; then
    [[ -d "$TUNNL_DIR/revoked" ]] || fail_json "unsafe_path" "Restore history must be a regular directory." 73
    local -a entries=()
    if ! find "$TUNNL_DIR/revoked" -mindepth 1 -maxdepth 1 -print0 2>/dev/null | mapfile -d '' -t entries; then
      fail_json "runtime_unknown" "Unable to inspect destination history." 74
    fi
    [[ ${#entries[@]} -eq 0 ]] || fail_json "revoked_history_exists" "Restore requires empty destination history." 73
  fi
  require_owned_forwarding
  require_absent_restore_interface
  require_restore_port
  require_unused_subnet
}

require_absent_restore_interface() {
  local links
  observe_runtime || restore_resource_failure "runtime_unknown" "Unable to determine interface state." 74
  [[ "$RUNTIME_RUNNING" == false ]] || restore_resource_failure "unmanaged_runtime" "Restore requires an absent interface." 73
  links=$(ip -o link show 2>/dev/null) || restore_resource_failure "runtime_unknown" "Unable to determine interface state." 74
  if printf '%s\n' "$links" | awk -F': ' '{split($2, name, "@"); print name[1]}' | grep -qxF "$WG_IFACE"; then
    restore_resource_failure "unmanaged_runtime" "Restore requires an absent interface." 73
  fi
}

require_supported_running_network() {
  local addresses routes line RESTORE_ROUTE_KIND count=0 connected=false multicast=false
  local -a fields
  local -A owned_routes=()
  addresses=$(ip -o address show dev "$WG_IFACE" 2>/dev/null) || fail_json "runtime_unknown" "Unable to inspect current interface addresses." 74
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    read -r -a fields <<< "$line"
    [[ ${#fields[@]} -ge 4 && ${fields[1]} == "$WG_IFACE" && ${fields[2]} == inet && ${fields[3]} == "$server_address" ]] ||
      fail_json "postcondition_failed" "Current interface contains unsupported addresses." 73
    count=$((count + 1))
  done <<< "$addresses"
  [[ "$count" -eq 1 ]] || fail_json "postcondition_failed" "Current interface addresses do not match configuration." 73
  routes=$(ip -4 -o route show table all dev "$WG_IFACE" 2>/dev/null) || fail_json "runtime_unknown" "Unable to inspect current interface routes." 74
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    classify_owned_restore_route "$line" "$cidr" "$server_address" true && [[ -z ${owned_routes[$RESTORE_ROUTE_KIND]+x} ]] ||
      fail_json "postcondition_failed" "Current interface contains unsupported routes." 73
    owned_routes[$RESTORE_ROUTE_KIND]=1
    [[ "$RESTORE_ROUTE_KIND" != connected ]] || connected=true
  done <<< "$routes"
  [[ "$connected" == true ]] || fail_json "postcondition_failed" "Current interface subnet route is absent." 73
  routes=$(ip -6 -o route show table all dev "$WG_IFACE" 2>/dev/null) || fail_json "runtime_unknown" "Unable to inspect current interface routes." 74
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    read -r -a fields <<< "$line"
    [[ "$multicast" == false && "${fields[*]}" == 'multicast ff00::/8 table local proto kernel metric 256 pref medium' ]] ||
      fail_json "postcondition_failed" "Current interface contains unsupported routes." 73
    multicast=true
  done <<< "$routes"
}

inspect_replacement_destination() {
  require_restore_state_paths
  require_owned_config
  require_owned_forwarding
  local snapshot_config=$RESTORE_CONFIG snapshot_cidr=$cidr key name change users="" discarded="" separator="" index
  local -a snapshot_revoked_names=("${revoked_names[@]}")
  local -A snapshot_keys=() snapshot_bodies=() snapshot_revoked=()
  for key in "${!active_keys[@]}"; do
    snapshot_keys[$key]=${active_keys[$key]}
    snapshot_bodies[$key]=${active_bodies[$key]}
  done
  for ((index=0; index<${#revoked_names[@]}; index++)); do
    snapshot_revoked[${revoked_names[index]}]=${revoked_bodies[index]}
  done
  local input_bytes=0 source_endpoint cidr dns server_private_key source_port subnet_prefix server_address active_config peer_count
  local RESTORE_CONTENT RESTORE_CONFIG PEER_NAME PEER_KEY PEER_PSK PEER_ROUTE PEER_END
  local -a RESTORE_LINES=() revoked_names=() revoked_bodies=() revoked_files=() sorted_keys=()
  local -A active_names=() active_keys=() active_routes=() active_bodies=()
  read_restore_config "$WG_CONF"
  read_restore_history "$TUNNL_DIR/revoked"
  key=$(wg_public_key_from_private "$server_private_key" 2>/dev/null) || fail_json "key_failed" "Unable to determine current server identity." 74
  [[ "$key" == "$expected_server_public_key" ]] || fail_json "server_identity_mismatch" "Current server identity does not match the backup." 73
  observe_runtime || fail_json "runtime_unknown" "Unable to determine current interface state." 74
  [[ "$RUNTIME_RUNNING" == true ]] || fail_json "unmanaged_runtime" "Replacement requires a running managed interface." 73
  observe_service && verify_runtime_config "$WG_CONF" && verify_address "$WG_CONF" || fail_json "postcondition_failed" "Current managed runtime does not match its configuration." 74
  require_supported_running_network
  local forwarding forwarding_file="" history_present=false forwarding_present=false
  forwarding=$(sysctl -n net.ipv4.ip_forward 2>/dev/null) || fail_json "runtime_unknown" "Unable to determine forwarding state." 74
  [[ "$forwarding" == 0 || "$forwarding" == 1 ]] || fail_json "runtime_unknown" "Unable to determine forwarding state." 74
  if [[ -f "$FORWARD_CONF" ]]; then
    forwarding_present=true
    forwarding_file=$(cat "$FORWARD_CONF" 2>/dev/null && printf '.') || fail_json "runtime_unknown" "Unable to inspect forwarding configuration." 74
  fi
  [[ ! -d "$TUNNL_DIR/revoked" ]] || history_present=true
  require_restore_port "$source_port"
  require_unused_subnet "$snapshot_cidr" "$cidr" "$server_address"
  REPLACEMENT_DIGEST=$({
    printf '%s\0' vpn0wn-same-server-review-v1 "$endpoint" "$port" "$snapshot_config"
    for name in "${snapshot_revoked_names[@]}"; do
      printf '%s\0%s\0' "$name" "${snapshot_revoked[$name]}"
    done
    printf '\0%s\0' "$RESTORE_CONFIG" "$history_present"
    for ((index=0; index<${#revoked_names[@]}; index++)); do
      printf '%s\0%s\0' "${revoked_names[index]}" "${revoked_bodies[index]}"
    done
    printf '\0%s\0' "$SERVICE_ENABLED" "$forwarding" "$forwarding_present" "$forwarding_file"
  } | sha256sum 2>/dev/null) || fail_json "runtime_unknown" "Unable to compare replacement state." 74
  REPLACEMENT_DIGEST=${REPLACEMENT_DIGEST%% *}
  [[ "$REPLACEMENT_DIGEST" =~ ^[0-9a-f]{64}$ ]] || fail_json "runtime_unknown" "Unable to compare replacement state." 74
  printf '%s\n' "${!snapshot_keys[@]}" "${!active_keys[@]}" | sed '/^$/d' | LC_ALL=C sort -u | mapfile -t sorted_keys ||
    fail_json "runtime_unknown" "Unable to compare replacement users." 74
  for key in "${sorted_keys[@]}"; do
    change=""
    if [[ -z ${snapshot_keys[$key]+x} ]]; then
      name=${active_keys[$key]}
      change=removed
    elif [[ -z ${active_keys[$key]+x} ]]; then
      name=${snapshot_keys[$key]}
      change=restored
    elif [[ ${snapshot_bodies[$key]} != "${active_bodies[$key]}" ]]; then
      name=${snapshot_keys[$key]}
      change=changed
    fi
    [[ -n "$change" ]] || continue
    users+="$separator{\"name\":$(json_string "$name"),\"publicKey\":$(json_string "$key"),\"change\":$(json_string "$change")}"
    separator=,
  done
  separator=""
  for ((index=0; index<${#revoked_names[@]}; index++)); do
    name=${revoked_names[index]}
    change=""
    if [[ -z ${snapshot_revoked[$name]+x} ]]; then
      change=removed
    elif [[ ${snapshot_revoked[$name]} != "${revoked_bodies[index]}" ]]; then
      change=replaced
    fi
    [[ -n "$change" ]] || continue
    discarded+="$separator{\"archiveName\":$(json_string "$name"),\"change\":$(json_string "$change")}"
    separator=,
  done
  REPLACEMENT_REVIEW=$(printf '{"reviewSha256":%s,"endpoint":%s,"port":%s,"cidr":%s,"dns":%s,"peerCount":%s,"revokedCount":%s,"running":true,"serviceEnabled":%s,"users":[%s],"discardedRevoked":[%s]}' \
    "$(json_string "$REPLACEMENT_DIGEST")" "$(json_string "$source_endpoint")" "$(json_string "$source_port")" "$(json_string "$cidr")" \
    "$(json_string "$dns")" "$peer_count" "${#revoked_names[@]}" "$SERVICE_ENABLED" "$users" "$discarded")
}

write_restored_config() {
  cat <<EOF || return 1
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
  printf '%s' "$active_config"
}

restore_file_matches() {
  local file=$1 expected=$2 size content
  [[ -f "$file" && ! -L "$file" ]] || return 1
  size=$(stat -c %s -- "$file" 2>/dev/null) || return 1
  [[ "$size" -eq ${#expected} ]] || return 1
  content=$(head -c "$((size + 1))" -- "$file" 2>/dev/null && printf '.') 2>/dev/null || return 1
  [[ "$content" == "$expected." ]]
}

verify_restored_history() {
  local index
  local -a entries=()
  [[ -d "$TUNNL_DIR/revoked" && ! -L "$TUNNL_DIR/revoked" ]] || return 1
  if ! find "$TUNNL_DIR/revoked" -mindepth 1 -maxdepth 1 -print0 2>/dev/null | mapfile -d '' -t entries; then
    return 1
  fi
  [[ ${#entries[@]} -eq ${#revoked_names[@]} ]] || return 1
  for ((index=0; index<${#revoked_names[@]}; index++)); do
    restore_file_matches "$TUNNL_DIR/revoked/${revoked_names[index]}" "${revoked_bodies[index]}" || return 1
  done
}

verify_recovered_history() {
  local previous=$1 file content
  local -a previous_entries=() current_entries=()
  find "$previous" -mindepth 1 -maxdepth 1 -print0 2>/dev/null | mapfile -d '' -t previous_entries || return 1
  find "$TUNNL_DIR/revoked" -mindepth 1 -maxdepth 1 -print0 2>/dev/null | mapfile -d '' -t current_entries || return 1
  [[ ${#previous_entries[@]} -eq ${#current_entries[@]} ]] || return 1
  for file in "${previous_entries[@]}"; do
    [[ -f "$file" && ! -L "$file" ]] || return 1
    content=$(cat -- "$file" 2>/dev/null && printf '.') || return 1
    restore_file_matches "$TUNNL_DIR/revoked/${file##*/}" "${content%?}" || return 1
  done
}

verify_restored_state() {
  local expected_config
  expected_config=$(write_restored_config && printf '.') || return 1
  restore_file_matches "$WG_CONF" "${expected_config%?}" && verify_restored_history || return 1
  restore_file_matches "$FORWARD_CONF" $'# TUNNL_MANAGED 1\nnet.ipv4.ip_forward=1\n' || return 1
  [[ $(sysctl -n net.ipv4.ip_forward 2>/dev/null) == 1 ]] || return 1
  observe_runtime && [[ "$RUNTIME_RUNNING" == true ]] && observe_service && [[ "$SERVICE_ENABLED" == true ]] || return 1
  verify_runtime_config "$WG_CONF" && verify_address "$WG_CONF"
}

input=""
endpoint=""
port=""
expected_server_public_key=""
confirmed=false
check_only=false
verify_only=false
replace_running=false
expected_current_state=""
replacement_admitted=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) require_arg_value "$@"; input=$2; shift 2 ;;
    --endpoint) require_arg_value "$@"; endpoint=$2; shift 2 ;;
    --port) require_arg_value "$@"; port=$2; shift 2 ;;
    --expected-server-public-key) require_arg_value "$@"; expected_server_public_key=$2; shift 2 ;;
    --confirm) confirmed=true; shift ;;
    --check) check_only=true; shift ;;
    --verify) verify_only=true; shift ;;
    --replace-running) replace_running=true; shift ;;
    --expected-current-state) require_arg_value "$@"; expected_current_state=$2; shift 2 ;;
    *) fail_json "invalid_arg" "Restore received an unsupported argument." 64 ;;
  esac
done

require_root
mode_count=0
for mode in "$confirmed" "$check_only" "$verify_only"; do
  [[ "$mode" != true ]] || mode_count=$((mode_count + 1))
done
[[ "$mode_count" -le 1 ]] || fail_json "invalid_arg" "Choose exactly one restore mode." 64
[[ -n "$input" && -n "$endpoint" && -n "$port" && -n "$expected_server_public_key" && "$mode_count" -eq 1 ]] || fail_json "missing_arg" "Restore requires input, destination, expected identity and one explicit mode." 64
if [[ "$replace_running" == true ]]; then
  [[ "$verify_only" == false && ( "$confirmed" == true || -z "$expected_current_state" ) ]] || fail_json "invalid_arg" "Replacement review is available only for check or confirm." 64
  [[ "$confirmed" != true || "$expected_current_state" =~ ^[0-9a-f]{64}$ ]] || fail_json "missing_arg" "Replacement requires the checked current-state digest." 64
  command_exists sha256sum || fail_json "missing_tool" "Replacement requires the installed state-comparison tool." 69
else
  [[ -z "$expected_current_state" ]] || fail_json "invalid_arg" "A current-state digest requires explicit replacement." 64
fi
[[ "$WG_IFACE" == wg0 ]] || fail_json "invalid_interface" "Restore currently supports wg0 only." 64
require_endpoint "$endpoint"
require_port "$port"
port=$((10#$port))
require_public_key "$expected_server_public_key"
require_supported_os
for tool in wg wg-quick ip iptables systemctl ss sysctl iconv; do
  command_exists "$tool" || fail_json "missing_tool" "Restore requires installed WireGuard and network tools." 69
done
[[ -d "$input" && ! -L "$input" && -d "$input/revoked" && ! -L "$input/revoked" ]] || fail_json "unsafe_path" "Restore input requires regular directories." 73
set +m
shopt -s lastpipe
input_entries=()
if ! find "$input" -mindepth 1 -maxdepth 1 -print0 2>/dev/null | mapfile -d '' -t input_entries; then
  invalid_restore_config
fi
[[ ${#input_entries[@]} -eq 2 ]] || invalid_restore_config
for file in "${input_entries[@]}"; do
  [[ "${file##*/}" == server.conf || "${file##*/}" == revoked ]] || invalid_restore_config
done

input_bytes=0
declare -A active_names=() active_keys=() active_routes=() active_bodies=()
read_restore_config "$input/server.conf"
read_restore_history "$input/revoked"
server_public_key=$(wg_public_key_from_private "$server_private_key" 2>/dev/null) || fail_json "key_failed" "Unable to determine restored server identity." 74
valid_restore_key "$server_public_key" || fail_json "key_failed" "Unable to determine restored server identity." 74
[[ "$server_public_key" == "$expected_server_public_key" ]] || fail_json "server_identity_mismatch" "Snapshot server identity does not match confirmation." 73
if [[ "$verify_only" == true ]]; then
  require_restore_state_paths
  require_owned_config
  require_owned_forwarding
  lock_host
  lock_interface
  require_restore_state_paths
  require_owned_config
  require_owned_forwarding
  verify_restored_state || fail_json "postcondition_failed" "Restored server state could not be verified." 74
  printf '{"ok":true,"verifyOnly":true,"verified":true,"interface":%s,"endpoint":%s,"port":%s,"cidr":%s,"dns":%s,"serverPublicKey":%s,"peerCount":%s,"revokedCount":%s,"running":true,"serviceEnabled":true}\n' \
    "$(json_string "$WG_IFACE")" "$(json_string "$endpoint")" "$(json_string "$port")" "$(json_string "$cidr")" \
    "$(json_string "$dns")" "$(json_string "$server_public_key")" "$peer_count" "${#revoked_names[@]}"
  exit 0
fi
if [[ "$replace_running" == true ]]; then
  inspect_replacement_destination
else
  require_fresh_destination
fi
lock_host
lock_interface
if [[ "$replace_running" == true ]]; then
  inspect_replacement_destination
  [[ "$confirmed" != true || "$REPLACEMENT_DIGEST" == "$expected_current_state" ]] || fail_json "restore_target_changed" "Replacement state changed; check and review it again." 73
else
  require_fresh_destination
fi
if [[ "$check_only" == true ]]; then
  replacement_json=""
  [[ "$replace_running" != true ]] || replacement_json=",\"replacement\":$REPLACEMENT_REVIEW"
  printf '{"ok":true,"checkOnly":true,"plan":{"interface":%s,"endpoint":%s,"port":%s,"cidr":%s,"dns":%s,"serverPublicKey":%s,"peerCount":%s,"revokedCount":%s%s}}\n' \
    "$(json_string "$WG_IFACE")" "$(json_string "$endpoint")" "$(json_string "$port")" "$(json_string "$cidr")" \
    "$(json_string "$dns")" "$(json_string "$server_public_key")" "$peer_count" "${#revoked_names[@]}" "$replacement_json"
  exit 0
fi
begin_mutation restore

write_restored_config > "$CANDIDATE" || mutation_abort write_failed
prepare_candidate
mkdir "$RECOVERY_DIR/candidate.revoked" || mutation_abort write_failed
for ((index=0; index<${#revoked_names[@]}; index++)); do
  printf '%s' "${revoked_bodies[index]}" > "$RECOVERY_DIR/candidate.revoked/${revoked_names[index]}" || mutation_abort write_failed
  chmod 600 "$RECOVERY_DIR/candidate.revoked/${revoked_names[index]}" || mutation_abort write_failed
done
if [[ "$replace_running" == true ]]; then
  replacement_admitted=true
  SYSTEM_CHANGED=true
  systemctl stop "wg-quick@$WG_IFACE" >/dev/null 2>&1 || mutation_abort apply_failed
  require_absent_restore_interface
  require_restore_port
  require_unused_subnet
fi
REVOKED_CHANGED=true
MUTATION_CHANGED=true
mkdir -p "$TUNNL_DIR" || mutation_abort write_failed
[[ "$HAD_REVOKED" != true ]] || mv -T -- "$TUNNL_DIR/revoked" "$RECOVERY_DIR/replaced.revoked" 2>/dev/null || mutation_abort write_failed
mv -T -- "$RECOVERY_DIR/candidate.revoked" "$TUNNL_DIR/revoked" 2>/dev/null || mutation_abort write_failed
replace_config
printf '# TUNNL_MANAGED 1\nnet.ipv4.ip_forward=1\n' > "$RECOVERY_DIR/candidate.forward" || mutation_abort write_failed
replace_forwarding "$RECOVERY_DIR/candidate.forward" || mutation_abort write_failed
RESTORE_FORWARDING_CHANGED=true
sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || mutation_abort forwarding_failed
[[ $(sysctl -n net.ipv4.ip_forward 2>/dev/null) == 1 ]] || mutation_abort postcondition_failed
SYSTEM_CHANGED=true
systemctl enable "wg-quick@$WG_IFACE" >/dev/null 2>&1 || mutation_abort enable_failed
systemctl restart "wg-quick@$WG_IFACE" >/dev/null 2>&1 || mutation_abort apply_failed
verify_restored_state || mutation_abort postcondition_failed
finish_mutation
printf '{"ok":true,"interface":%s,"endpoint":%s,"port":%s,"cidr":%s,"serverPublicKey":%s,"peerCount":%s,"revokedCount":%s,"cleanupPath":%s}\n' \
  "$(json_string "$WG_IFACE")" "$(json_string "$endpoint")" "$(json_string "$port")" "$(json_string "$cidr")" \
  "$(json_string "$server_public_key")" "$peer_count" "${#revoked_names[@]}" "$(json_string "$CLEANUP_PATH")"
