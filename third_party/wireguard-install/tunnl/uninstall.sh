#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

confirm=false
purge_packages=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --confirm) confirm=true; shift ;;
    --purge-packages) purge_packages=true; shift ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_root
[[ "$confirm" == true ]] || fail_json "confirmation_required" "Pass --confirm after explicit UI confirmation." 64

systemctl disable --now "wg-quick@$WG_IFACE" >&2 || true

backup_path=""
if [[ -f "$WG_CONF" ]]; then
  mkdir -p "$TUNNL_DIR/backups"
  backup_path="$TUNNL_DIR/backups/$WG_IFACE.conf.$(date +%Y%m%d%H%M%S)"
  cp "$WG_CONF" "$backup_path"
  rm -f "$WG_CONF"
fi

rm -f /etc/sysctl.d/99-tunnl-wireguard-forward.conf

if [[ "$purge_packages" == true ]]; then
  require_supported_os
  export DEBIAN_FRONTEND=noninteractive
  apt-get purge -y wireguard wireguard-tools qrencode >&2 || true
  apt-get autoremove -y >&2 || true
fi

printf '{"ok":true,"uninstalled":true,"backup":%s,"packagesPurged":%s}\n' \
  "$(json_string "$backup_path")" \
  "$purge_packages"
