#!/usr/bin/env bash
set -euo pipefail

WG_IFACE="${WG_IFACE:-wg0}"
WG_DIR="${WG_DIR:-/etc/wireguard}"
WG_CONF="${WG_CONF:-$WG_DIR/$WG_IFACE.conf}"
TUNNL_DIR="${TUNNL_DIR:-$WG_DIR/tunnl}"
WG_LOCK_FILE="${WG_LOCK_FILE:-/run/lock/tunnl-wireguard-$WG_IFACE.lock}"
WG_HOST_LOCK_FILE="${WG_HOST_LOCK_FILE:-/run/lock/tunnl-wireguard-host.lock}"
FORWARD_CONF="${FORWARD_CONF:-/etc/sysctl.d/99-tunnl-wireguard-forward.conf}"

json_escape() {
  local s=${1-}
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\n'/\\n}
  s=${s//$'\r'/}
  printf '%s' "$s"
}

json_string() {
  printf '"%s"' "$(json_escape "${1-}")"
}

fail_json() {
  local code=${1:?}
  local message=${2:?}
  printf '{"ok":false,"error":%s,"message":%s}\n' "$(json_string "$code")" "$(json_string "$message")"
  exit "${3:-1}"
}

require_root() {
  [[ ${EUID:-$(id -u)} -eq 0 ]] || fail_json "not_root" "This command must run as root." 77
}

require_arg_value() {
  [[ $# -ge 2 && "$2" != --* ]] || fail_json "missing_arg" "An option value is missing." 64
}

require_public_key() {
  local LC_ALL=C
  # Match Android's key parser by rejecting nonzero base64 padding bits.
  [[ "$1" =~ ^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$ ]] || fail_json "invalid_public_key" "Expected public key must be a canonical WireGuard key." 64
}

require_owned_config() {
  [[ -e "$WG_CONF" || -L "$WG_CONF" ]] || fail_json "not_installed" "WireGuard configuration is absent." 72
  [[ -f "$WG_CONF" && ! -L "$WG_CONF" ]] && grep -qxF '# TUNNL_MANAGED 1' "$WG_CONF" || fail_json "unmanaged_config" "Refusing to modify unmanaged WireGuard configuration." 73
}

require_owned_or_missing_config() {
  [[ ! -e "$WG_CONF" && ! -L "$WG_CONF" ]] || require_owned_config
}

require_owned_forwarding() {
  [[ -e "$FORWARD_CONF" || -L "$FORWARD_CONF" ]] || return 0
  [[ -f "$FORWARD_CONF" && ! -L "$FORWARD_CONF" ]] || fail_json "unmanaged_forwarding" "Refusing to modify an unrecognized forwarding file." 73
  local content
  content=$(cat "$FORWARD_CONF")
  [[ "$content" == 'net.ipv4.ip_forward=1' || "$content" == $'# TUNNL_MANAGED 1\nnet.ipv4.ip_forward=1' ]] || fail_json "unmanaged_forwarding" "Refusing to modify an unrecognized forwarding file." 73
}

require_exclusive_package_use() {
  local other_config interfaces interface
  for other_config in "$WG_DIR"/*.conf; do
    [[ -e "$other_config" || -L "$other_config" ]] || continue
    [[ "$other_config" == "$WG_CONF" ]] || fail_json "shared_wireguard_state" "Package purge would affect another WireGuard configuration." 73
  done
  interfaces=$(wg show interfaces 2>/dev/null) || fail_json "runtime_unknown" "Unable to determine running WireGuard interfaces." 74
  for interface in $interfaces; do
    [[ "$interface" == "$WG_IFACE" ]] || fail_json "shared_wireguard_state" "Package purge would affect another WireGuard interface." 73
  done
}

require_endpoint() {
  local endpoint=${1-}
  [[ -n "$endpoint" ]] || fail_json "missing_endpoint" "--endpoint is required." 64
  [[ "$endpoint" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ || "$endpoint" =~ ^\[[0-9A-Fa-f:.]+\]$ ]] || fail_json "invalid_endpoint" "Endpoint must be a single hostname, IPv4 address or bracketed IPv6 literal." 64
}

require_port() {
  local port=${1-}
  [[ "$port" =~ ^[0-9]{1,5}$ ]] || fail_json "invalid_port" "Port must be between 1 and 65535." 64
  [[ $((10#$port)) -ge 1 && $((10#$port)) -le 65535 ]] || fail_json "invalid_port" "Port must be between 1 and 65535." 64
}

require_dns() {
  local dns=${1-} item
  local -a entries
  [[ -n "$dns" && "$dns" != *[[:cntrl:]]* && "$dns" != ,* && "$dns" != *, ]] || fail_json "invalid_dns" "DNS must contain single-line addresses or search domains." 64
  IFS=',' read -ra entries <<< "$dns"
  for item in "${entries[@]}"; do
    item=${item#"${item%%[! ]*}"}
    item=${item%"${item##*[! ]}"}
    [[ "$item" =~ ^[A-Za-z0-9_][A-Za-z0-9_.:-]*$ ]] || fail_json "invalid_dns" "DNS contains an invalid token." 64
  done
}

require_allowed_ips() {
  local routes=${1-} item address prefix
  local -a entries
  [[ -n "$routes" && "$routes" != *[[:cntrl:]]* && "$routes" != ,* && "$routes" != *, ]] || fail_json "invalid_allowed_ips" "AllowedIPs must contain single-line CIDRs." 64
  IFS=',' read -ra entries <<< "$routes"
  for item in "${entries[@]}"; do
    item=${item#"${item%%[! ]*}"}
    item=${item%"${item##*[! ]}"}
    [[ "$item" =~ ^([0-9A-Fa-f:.]+)/([0-9]{1,3})$ ]] || fail_json "invalid_allowed_ips" "AllowedIPs contains an invalid CIDR." 64
    address=${BASH_REMATCH[1]}
    prefix=${BASH_REMATCH[2]}
    if [[ "$address" == *:* ]]; then
      [[ $((10#$prefix)) -le 128 ]] || fail_json "invalid_allowed_ips" "IPv6 prefix is out of range." 64
    else
      require_ipv4_address "$address" invalid_allowed_ips
      [[ $((10#$prefix)) -le 32 ]] || fail_json "invalid_allowed_ips" "IPv4 prefix is out of range." 64
    fi
  done
}

require_ipv4_address() {
  local address=${1:?} code=${2:?} octet
  local -a octets
  [[ "$address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || fail_json "$code" "Invalid IPv4 address." 64
  IFS='.' read -ra octets <<< "$address"
  for octet in "${octets[@]}"; do
    [[ $((10#$octet)) -le 255 ]] || fail_json "$code" "Invalid IPv4 octet." 64
  done
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

detect_os() {
  OS_ID="unknown"
  OS_VERSION_ID=""
  if [[ -r /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    OS_ID=${ID:-unknown}
    OS_VERSION_ID=${VERSION_ID:-}
  elif [[ -r /etc/debian_version ]]; then
    OS_ID="debian"
    OS_VERSION_ID=$(cut -d. -f1 /etc/debian_version)
  fi
}

require_supported_os() {
  detect_os
  case "$OS_ID" in
    ubuntu|debian) ;;
    *) fail_json "unsupported_os" "Only Ubuntu and Debian are supported by the current Tunnl provisioning MVP." 65 ;;
  esac
}

sanitize_name() {
  local raw=${1:?}
  local clean
  clean=$(printf '%s' "$raw" | tr -c 'A-Za-z0-9_-' '_' | cut -c 1-32)
  [[ -n "$clean" ]] || fail_json "invalid_name" "Name contains no supported characters." 64
  printf '%s' "$clean"
}

require_ipv4_cidr_24() {
  local cidr=${1:?}
  [[ "$cidr" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.0/24$ ]] || fail_json "invalid_cidr" "Only IPv4 /24 CIDRs are supported by the current MVP." 64
  require_ipv4_address "${cidr%/*}" invalid_cidr
}

cidr_prefix() {
  local cidr=${1:?}
  printf '%s' "${cidr%.*}"
}

server_address_for_cidr() {
  local cidr=${1:?}
  printf '%s.1/24' "$(cidr_prefix "$cidr")"
}

next_peer_address() {
  local cidr=${1:?}
  local prefix
  prefix=$(cidr_prefix "$cidr")
  local octet
  for octet in $(seq 2 254); do
    if [[ ! -f "$WG_CONF" ]] || ! grep -Eq "AllowedIPs = ${prefix//./\\.}\.${octet}/32(,|$)" "$WG_CONF"; then
      printf '%s.%s' "$prefix" "$octet"
      return 0
    fi
  done
  fail_json "address_pool_full" "No free peer address remains in the /24 pool." 73
}

conf_value() {
  local key=${1:?}
  [[ -r "$WG_CONF" ]] || return 1
  grep -m1 -E "^# ${key} " "$WG_CONF" | sed "s/^# ${key} //"
}

wg_public_key_from_private() {
  local private_key=${1:?}
  printf '%s' "$private_key" | wg pubkey
}

lock_interface() {
  [[ "$WG_IFACE" =~ ^[A-Za-z0-9_.-]{1,15}$ ]] || fail_json "invalid_interface" "Invalid interface name." 64
  [[ ! -L "$WG_DIR" && ! -L "$TUNNL_DIR" && ! -L "$WG_LOCK_FILE" ]] || fail_json "unsafe_path" "Refusing a symlinked state or lock path." 73
  command_exists flock || fail_json "missing_tool" "flock is required for mutations." 69
  umask 077
  exec {INTERFACE_LOCK}>"$WG_LOCK_FILE" || fail_json "lock_failed" "Unable to open interface lock." 74
  flock "$INTERFACE_LOCK" || fail_json "lock_failed" "Unable to acquire interface lock." 74
}

lock_host() {
  [[ ! -L "$WG_HOST_LOCK_FILE" ]] || fail_json "unsafe_path" "Refusing a symlinked host lock." 73
  command_exists flock || fail_json "missing_tool" "flock is required for mutations." 69
  umask 077
  exec {HOST_LOCK}>"$WG_HOST_LOCK_FILE" || fail_json "lock_failed" "Unable to open host lock." 74
  flock "$HOST_LOCK" || fail_json "lock_failed" "Unable to acquire host lock." 74
}

observe_runtime() {
  local interfaces interface
  RUNTIME_RUNNING=false
  if ! command_exists wg; then
    [[ ! -f "$WG_CONF" ]] || return 1
    interfaces=$(ip -o link show 2>/dev/null) || return 1
    if printf '%s\n' "$interfaces" | awk -F': ' '{split($2, name, "@"); print name[1]}' | grep -qxF "$WG_IFACE"; then
      RUNTIME_RUNNING=true
    fi
    return 0
  fi
  interfaces=$(wg show interfaces 2>/dev/null) || return 1
  for interface in $interfaces; do
    [[ "$interface" != "$WG_IFACE" ]] || RUNTIME_RUNNING=true
  done
}

observe_service() {
  local enabled active status load_state
  SERVICE_ENABLED=false
  enabled=$(systemctl is-enabled "wg-quick@$WG_IFACE" 2>/dev/null) && status=0 || status=$?
  case "$enabled:$status" in
    enabled:0) SERVICE_ENABLED=true ;;
    disabled:1|not-found:4) ;;
    *)
      load_state=$(systemctl show --property=LoadState --value "wg-quick@$WG_IFACE" 2>/dev/null) || return 1
      [[ "$load_state" == not-found && "$RUNTIME_RUNNING" == false ]] || return 1
      return 0
      ;;
  esac
  active=$(systemctl is-active "wg-quick@$WG_IFACE" 2>/dev/null) && status=0 || status=$?
  case "$active:$status" in
    active:0) [[ "$RUNTIME_RUNNING" == true ]] ;;
    inactive:3|failed:3|unknown:4) [[ "$RUNTIME_RUNNING" == false ]] ;;
    *) return 1 ;;
  esac
}

begin_mutation() {
  MUTATION_MODE=$1
  MUTATION_CHANGED=false
  SYSTEM_CHANGED=false
  PACKAGE_EFFECTS=false
  HAD_CONFIG=false
  HAD_FORWARDING=false
  RETAIN_ARTIFACT=""
  REVOKE_ARCHIVE=""
  FORWARD_TEMP=""
  observe_runtime || fail_json "runtime_unknown" "Unable to determine interface state." 74
  WAS_RUNNING=$RUNTIME_RUNNING
  [[ "$MUTATION_MODE" != install || -f "$WG_CONF" || "$WAS_RUNNING" == false ]] || fail_json "unmanaged_runtime" "A live interface exists without owned config." 73
  WAS_ENABLED=false
  if [[ "$MUTATION_MODE" != peer ]]; then
    observe_service || fail_json "runtime_unknown" "Unable to determine service state." 74
    WAS_ENABLED=$SERVICE_ENABLED
  fi
  mkdir -p "$WG_DIR" 2>/dev/null || fail_json "snapshot_failed" "Unable to prepare private recovery files." 74
  RECOVERY_DIR=$(mktemp -d "$WG_DIR/.tunnl-recovery.XXXXXX") || fail_json "snapshot_failed" "Unable to prepare private recovery files." 74
  CANDIDATE="$RECOVERY_DIR/$WG_IFACE.conf"
  trap 'mutation_abort interrupted 130' INT TERM HUP
  trap 'mutation_abort command_failed 74' ERR
  trap 'mutation_abort interrupted 74' EXIT
  if [[ -f "$WG_CONF" ]]; then
    HAD_CONFIG=true
    cp -- "$WG_CONF" "$RECOVERY_DIR/previous.conf" 2>/dev/null || mutation_abort snapshot_failed
    chmod 600 "$RECOVERY_DIR/previous.conf" || mutation_abort snapshot_failed
  fi
  if [[ "$WAS_RUNNING" == true ]]; then
    wg showconf "$WG_IFACE" > "$RECOVERY_DIR/previous.runtime" 2>/dev/null || mutation_abort snapshot_failed
  fi
  if [[ "$MUTATION_MODE" != peer && -f "$FORWARD_CONF" ]]; then
    HAD_FORWARDING=true
    cp -- "$FORWARD_CONF" "$RECOVERY_DIR/previous.forward" 2>/dev/null || mutation_abort snapshot_failed
    chmod 600 "$RECOVERY_DIR/previous.forward" || mutation_abort snapshot_failed
  fi
}

peer_routes() {
  awk '
    $1 == "PublicKey" {key=$3}
    $1 == "AllowedIPs" {
      routes=substr($0, index($0, "=") + 1)
      gsub(/,/, " ", routes)
      count=split(routes, parts, /[[:space:]]+/)
      for (i=1; i<=count; i++) if (parts[i] != "") print key " " parts[i]
    }
  ' "$1" | LC_ALL=C sort
}

peer_psks() {
  awk '
    /^\[Peer\]/ {if (key != "") print key " " psk; key=""; psk="(none)"}
    $1 == "PublicKey" {key=$3}
    $1 == "PresharedKey" {psk=$3}
    END {if (key != "") print key " " psk}
  ' "$1" | LC_ALL=C sort
}

verify_runtime_config() {
  local config=$1 expected_key private_key actual_key expected_port actual_port peers routes psks
  private_key=$(awk '$1 == "PrivateKey" {print $3; exit}' "$config") || return 1
  [[ -n "$private_key" ]] || return 1
  expected_key=$(wg_public_key_from_private "$private_key" 2>/dev/null) || return 1
  actual_key=$(wg show "$WG_IFACE" public-key 2>/dev/null) || return 1
  [[ "$expected_key" == "$actual_key" ]] || return 1
  expected_port=$(awk '$1 == "ListenPort" {print $3; exit}' "$config") || return 1
  actual_port=$(wg show "$WG_IFACE" listen-port 2>/dev/null) || return 1
  [[ "$expected_port" == "$actual_port" ]] || return 1
  peers=$(wg show "$WG_IFACE" peers 2>/dev/null) || return 1
  [[ "$(printf '%s\n' "$peers" | sed '/^$/d' | LC_ALL=C sort)" == "$(awk '$1 == "PublicKey" {print $3}' "$config" | LC_ALL=C sort)" ]] || return 1
  routes=$(wg show "$WG_IFACE" allowed-ips 2>/dev/null) || return 1
  [[ "$(printf '%s\n' "$routes" | awk '{key=$1; gsub(/,/, " "); for(i=2;i<=NF;i++) print key " " $i}' | LC_ALL=C sort)" == "$(peer_routes "$config")" ]] || return 1
  psks=$(wg show "$WG_IFACE" preshared-keys 2>/dev/null) || return 1
  [[ "$(printf '%s\n' "$psks" | awk 'NF {print $1 " " $2}' | LC_ALL=C sort)" == "$(peer_psks "$config")" ]]
}

verify_address() {
  local config=$1 expected addresses
  expected=$(awk '$1 == "Address" {print $3; exit}' "$config") || return 1
  addresses=$(ip -4 -o address show dev "$WG_IFACE" 2>/dev/null) || return 1
  printf '%s\n' "$addresses" | awk '{print $4}' | grep -qxF "$expected"
}

prepare_candidate() {
  chmod 600 "$CANDIDATE" || mutation_abort write_failed
  wg-quick strip "$CANDIDATE" > "$RECOVERY_DIR/candidate.runtime" 2>/dev/null || mutation_abort invalid_config
}

replace_config() {
  MUTATION_CHANGED=true
  mv -fT -- "$CANDIDATE" "$WG_CONF" 2>/dev/null || mutation_abort write_failed
}

apply_peer_config() {
  replace_config
  if [[ "$WAS_RUNNING" == true ]]; then
    if ! wg syncconf "$WG_IFACE" "$RECOVERY_DIR/candidate.runtime" >/dev/null 2>&1; then
      systemctl restart "wg-quick@$WG_IFACE" >/dev/null 2>&1 || mutation_abort apply_failed
    fi
    verify_runtime_config "$WG_CONF" || mutation_abort postcondition_failed
  else
    observe_runtime && [[ "$RUNTIME_RUNNING" == false ]] || mutation_abort postcondition_failed
  fi
}

replace_forwarding() {
  local source=$1
  FORWARD_TEMP=$(mktemp "${FORWARD_CONF%/*}/.tunnl-forward.XXXXXX") || return 1
  cp -- "$source" "$FORWARD_TEMP" 2>/dev/null && chmod 600 "$FORWARD_TEMP" && mv -fT -- "$FORWARD_TEMP" "$FORWARD_CONF" 2>/dev/null || return 1
  FORWARD_TEMP=""
}

restore_mutation() {
  local restored=true
  if [[ "$SYSTEM_CHANGED" == true ]]; then
    observe_runtime || return 1
    if [[ "$RUNTIME_RUNNING" == true ]]; then
      # Teardown must still see the currently running config and its hooks.
      systemctl stop "wg-quick@$WG_IFACE" >/dev/null 2>&1 || return 1
      observe_runtime && [[ "$RUNTIME_RUNNING" == false ]] || return 1
    fi
  fi
  if [[ "$HAD_CONFIG" == true ]]; then
    [[ -f "$RECOVERY_DIR/previous.conf" ]] || return 1
    cp -- "$RECOVERY_DIR/previous.conf" "$RECOVERY_DIR/restore.conf" 2>/dev/null && mv -fT -- "$RECOVERY_DIR/restore.conf" "$WG_CONF" 2>/dev/null || restored=false
  else
    rm -f -- "$WG_CONF" 2>/dev/null || restored=false
  fi
  if [[ "$MUTATION_MODE" != peer ]]; then
    if [[ "$HAD_FORWARDING" == true ]]; then
      [[ -f "$RECOVERY_DIR/previous.forward" ]] || return 1
      replace_forwarding "$RECOVERY_DIR/previous.forward" || restored=false
    else
      rm -f -- "$FORWARD_CONF" 2>/dev/null || restored=false
    fi
  fi
  [[ "$restored" == true ]] || return 1
  if [[ "$SYSTEM_CHANGED" == true ]]; then
    if [[ "$WAS_ENABLED" == true ]]; then
      systemctl enable "wg-quick@$WG_IFACE" >/dev/null 2>&1 || return 1
    else
      systemctl disable "wg-quick@$WG_IFACE" >/dev/null 2>&1 || return 1
    fi
  fi
  if [[ "$WAS_RUNNING" == true ]]; then
    if [[ "$SYSTEM_CHANGED" == true ]]; then
      systemctl restart "wg-quick@$WG_IFACE" >/dev/null 2>&1 || return 1
    fi
    if ! wg syncconf "$WG_IFACE" "$RECOVERY_DIR/previous.runtime" >/dev/null 2>&1; then
      systemctl restart "wg-quick@$WG_IFACE" >/dev/null 2>&1 && wg syncconf "$WG_IFACE" "$RECOVERY_DIR/previous.runtime" >/dev/null 2>&1 || return 1
    fi
    verify_runtime_config "$RECOVERY_DIR/previous.runtime" || return 1
    [[ "$MUTATION_MODE" == peer ]] || verify_address "$RECOVERY_DIR/previous.conf" || return 1
  else
    [[ "$SYSTEM_CHANGED" != true ]] || systemctl stop "wg-quick@$WG_IFACE" >/dev/null 2>&1 || return 1
    observe_runtime && [[ "$RUNTIME_RUNNING" == false ]] || return 1
  fi
  if [[ "$SYSTEM_CHANGED" == true ]]; then
    observe_runtime && [[ "$RUNTIME_RUNNING" == "$WAS_RUNNING" ]] && observe_service && [[ "$SERVICE_ENABLED" == "$WAS_ENABLED" ]] || return 1
  fi
}

mutation_abort() {
  local reason=$1 exit_code=${2:-74} recovery=unchanged
  trap - ERR EXIT INT TERM HUP
  set +e
  if [[ "$MUTATION_CHANGED" == true || "$SYSTEM_CHANGED" == true ]]; then
    if restore_mutation; then recovery=restored; else recovery=incomplete; fi
  fi
  if [[ -n "$FORWARD_TEMP" ]]; then
    rm -f -- "$FORWARD_TEMP" 2>/dev/null || recovery=incomplete
  fi
  if [[ -n "$REVOKE_ARCHIVE" ]]; then
    rm -f -- "$REVOKE_ARCHIVE" 2>/dev/null || recovery=incomplete
  fi
  if [[ "$recovery" != incomplete ]]; then
    rm -rf -- "$RECOVERY_DIR" 2>/dev/null || recovery=incomplete
  fi
  local message="Mutation failed; prior configuration/runtime $recovery."
  [[ "$PACKAGE_EFFECTS" != true ]] || message+=" Package changes and global forwarding may remain; inspect before retrying."
  printf '{"ok":false,"error":%s,"message":%s,"recovery":%s,"recoveryPath":%s,"artifact":%s}\n' \
    "$(json_string "$reason")" "$(json_string "$message")" "$(json_string "$recovery")" \
    "$(json_string "$([[ "$recovery" != incomplete ]] || printf '%s' "$RECOVERY_DIR")")" "$(json_string "$RETAIN_ARTIFACT")"
  exit "$exit_code"
}

finish_mutation() {
  # Once committed, cleanup cannot roll back from partially deleted snapshots.
  trap - ERR EXIT INT TERM HUP
  CLEANUP_PATH=""
  rm -rf -- "$RECOVERY_DIR" 2>/dev/null || CLEANUP_PATH=$RECOVERY_DIR
}

private_artifact_directory() {
  local directory=$1
  [[ ! -L "$TUNNL_DIR" && ! -L "$directory" ]] || mutation_abort unsafe_path
  mkdir -p "$directory" 2>/dev/null && chmod 700 "$TUNNL_DIR" "$directory" || mutation_abort write_failed
}

peer_public_key() {
  awk -v name="$1" '
    $0 == "# BEGIN_PEER " name {inside=1; next}
    $0 == "# END_PEER " name {exit}
    inside && $1 == "PublicKey" && $2 == "=" {print $3; exit}
  ' "$WG_CONF"
}

require_expected_peer() {
  local name=$1 expected_public_key=$2
  validate_peer_markers || fail_json "invalid_config" "Peer markers are ambiguous." 74
  grep -qxF "# BEGIN_PEER $name" "$WG_CONF" && grep -qxF "# END_PEER $name" "$WG_CONF" || fail_json "peer_not_found" "Peer was not found." 73
  [[ $(peer_public_key "$name") == "$expected_public_key" ]] || fail_json "peer_identity_mismatch" "Peer identity changed; refresh before retrying." 73
}

prepare_peer_removal() {
  local name=$1
  validate_peer_markers || mutation_abort invalid_config
  grep -qxF "# BEGIN_PEER $name" "$WG_CONF" && grep -qxF "# END_PEER $name" "$WG_CONF" || mutation_abort peer_not_found
  REMOVED_PUBLIC_KEY=$(peer_public_key "$name")
  [[ -n "$REMOVED_PUBLIC_KEY" ]] || mutation_abort invalid_config
  [[ $(awk -v key="$REMOVED_PUBLIC_KEY" '$1 == "PublicKey" && $3 == key {count++} END {print count+0}' "$WG_CONF") -eq 1 ]] || mutation_abort invalid_config
  awk -v name="$name" '
    $0 == "# BEGIN_PEER " name {skip=1; next}
    $0 == "# END_PEER " name {skip=0; next}
    !skip {print}
  ' "$WG_CONF" > "$CANDIDATE" || mutation_abort write_failed
  prepare_candidate
}

validate_peer_markers() {
  awk '
    /^# BEGIN_PEER / {if (inside != "" || seen[$3]++) invalid=1; inside=$3; sections=0; keys=0; next}
    inside != "" && $1 == "[Peer]" {sections++}
    inside != "" && $1 == "PublicKey" {keys++}
    inside != "" && $1 == "[Interface]" {invalid=1}
    /^# END_PEER / {if (inside == "" || inside != $3 || sections != 1 || keys != 1) invalid=1; inside=""}
    END {exit (invalid || inside != "")}
  ' "$WG_CONF"
}
