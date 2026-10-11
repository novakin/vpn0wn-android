#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

detect_os

package_manager="unknown"
command_exists apt-get && package_manager="apt"
command_exists dnf && package_manager="dnf"

firewall="none"
systemctl is-active --quiet firewalld.service 2>/dev/null && firewall="firewalld"
command_exists nft && firewall="nftables"
command_exists iptables && firewall="iptables"

wg_installed=false
command_exists wg && wg_installed=true

wg_config_exists=false
[[ -f "$WG_CONF" ]] && wg_config_exists=true

endpoint=""
listen_port=""
cidr=""
if [[ -r "$WG_CONF" ]]; then
  endpoint=$(conf_value ENDPOINT || true)
  listen_port=$(grep -m1 -E '^ListenPort = ' "$WG_CONF" | awk '{print $3}' || true)
  cidr=$(conf_value TUNNL_CIDR || true)
fi

ip_forward="unknown"
[[ -r /proc/sys/net/ipv4/ip_forward ]] && ip_forward=$(cat /proc/sys/net/ipv4/ip_forward)

supported=false
[[ "$OS_ID" == "ubuntu" || "$OS_ID" == "debian" ]] && supported=true

printf '{"ok":true,"os":%s,"version":%s,"supported":%s,"packageManager":%s,"firewall":%s,"wireGuardInstalled":%s,"configExists":%s,"interface":%s,"endpoint":%s,"listenPort":%s,"cidr":%s,"ipv4Forwarding":%s}\n' \
  "$(json_string "$OS_ID")" \
  "$(json_string "$OS_VERSION_ID")" \
  "$supported" \
  "$(json_string "$package_manager")" \
  "$(json_string "$firewall")" \
  "$wg_installed" \
  "$wg_config_exists" \
  "$(json_string "$WG_IFACE")" \
  "$(json_string "$endpoint")" \
  "$(json_string "$listen_port")" \
  "$(json_string "$cidr")" \
  "$(json_string "$ip_forward")"
