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
require_owned_config
require_owned_forwarding
[[ "$purge_packages" != true ]] || require_exclusive_package_use

[[ "$purge_packages" != true ]] || require_supported_os
lock_host
lock_interface
require_owned_config
require_owned_forwarding
[[ "$purge_packages" != true ]] || require_exclusive_package_use
begin_mutation uninstall

private_artifact_directory "$TUNNL_DIR/backups"
backup_path=$(mktemp "$TUNNL_DIR/backups/$WG_IFACE.conf.XXXXXX") || mutation_abort snapshot_failed
RETAIN_ARTIFACT=$backup_path
cp -- "$WG_CONF" "$backup_path" 2>/dev/null && chmod 600 "$backup_path" || mutation_abort snapshot_failed

SYSTEM_CHANGED=true
systemctl stop "wg-quick@$WG_IFACE" >/dev/null 2>&1 || mutation_abort stop_failed
systemctl disable "wg-quick@$WG_IFACE" >/dev/null 2>&1 || mutation_abort disable_failed
observe_runtime && [[ "$RUNTIME_RUNNING" == false ]] && observe_service && [[ "$SERVICE_ENABLED" == false ]] || mutation_abort postcondition_failed
MUTATION_CHANGED=true
rm -f -- "$WG_CONF" 2>/dev/null || mutation_abort write_failed
[[ ! -e "$WG_CONF" && ! -L "$WG_CONF" ]] || mutation_abort postcondition_failed
# Keep the forwarding file when other WireGuard state still uses it.
other_configs=false
for config in "$WG_DIR"/*.conf; do
  [[ ! -e "$config" && ! -L "$config" ]] || other_configs=true
done
interfaces=$(wg show interfaces 2>/dev/null) || mutation_abort runtime_unknown
if [[ "$other_configs" == false && -z "$interfaces" ]]; then
  rm -f -- "$FORWARD_CONF" 2>/dev/null || mutation_abort write_failed
fi

if [[ "$purge_packages" == true ]]; then
  export DEBIAN_FRONTEND=noninteractive
  PACKAGE_EFFECTS=true
  apt-get purge -y wireguard wireguard-tools qrencode >/dev/null 2>&1 || mutation_abort purge_failed
  for package in wireguard wireguard-tools qrencode; do
    status=$(dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null) && query_code=0 || query_code=$?
    [[ "$query_code" -eq 0 || "$query_code" -eq 1 ]] || mutation_abort purge_unknown
    [[ "$query_code" -eq 1 || "$status" == not-installed ]] || mutation_abort purge_failed
  done
fi
finish_mutation

printf '{"ok":true,"uninstalled":true,"backup":%s,"packagesPurged":%s,"cleanupPath":%s}\n' \
  "$(json_string "$backup_path")" \
  "$purge_packages" \
  "$(json_string "$CLEANUP_PATH")"
