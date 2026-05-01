#!/usr/bin/env bash
set -euo pipefail

WG_IFACE="${WG_IFACE:-wg0}"
WG_DIR="${WG_DIR:-/etc/wireguard}"
WG_CONF="${WG_CONF:-$WG_DIR/$WG_IFACE.conf}"
TUNNL_DIR="${TUNNL_DIR:-$WG_DIR/tunnl}"

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
  local a b c
  IFS='.' read -r a b c _ <<< "${cidr%/*}"
  for octet in "$a" "$b" "$c"; do
    [[ "$octet" -ge 0 && "$octet" -le 255 ]] || fail_json "invalid_cidr" "CIDR contains an invalid IPv4 octet." 64
  done
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
