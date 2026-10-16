#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

export LC_ALL=C
MAX_CONFIG_BYTES=262144
MAX_REVOKED_BYTES=16384
MAX_REVOKED_FILES=1024
MAX_SNAPSHOT_BYTES=786432

read_snapshot_file() {
  local file=$1 maximum=$2 size content
  [[ -f "$file" && ! -L "$file" ]] || fail_json "unsafe_path" "Backup requires regular state files." 73
  size=$(stat -c %s -- "$file" 2>/dev/null) || fail_json "backup_failed" "Unable to read backup state." 74
  [[ "$size" -gt 0 && "$size" -le "$maximum" ]] || fail_json "backup_limit" "Backup state exceeds supported limits." 73
  iconv -f UTF-8 -t UTF-8 -- "$file" >/dev/null 2>&1 || fail_json "invalid_config" "Backup state is not valid UTF-8 text." 73
  content=$(cat -- "$file" 2>/dev/null && printf '.') 2>/dev/null || fail_json "backup_failed" "Unable to read backup state." 74
  SNAPSHOT_CONTENT=${content%?}
  [[ ${#SNAPSHOT_CONTENT} -eq "$size" ]] || fail_json "invalid_config" "Backup state contains unsupported binary content." 73
}

require_root
[[ $# -eq 0 ]] || fail_json "invalid_arg" "Backup accepts no arguments." 64
require_owned_config
command_exists iconv || fail_json "missing_tool" "iconv is required for backups." 69
lock_interface
require_owned_config

read_snapshot_file "$WG_CONF" "$MAX_CONFIG_BYTES"
config_json=$(json_string "$SNAPSHOT_CONTENT")
revoked_json=""
revoked_directory="$TUNNL_DIR/revoked"
revoked_files=()
if [[ -e "$revoked_directory" || -L "$revoked_directory" ]]; then
  [[ -d "$revoked_directory" && ! -L "$revoked_directory" ]] || fail_json "unsafe_path" "Revoked backup state must be a regular directory." 73
  # Keep mapfile in this shell while pipefail preserves enumeration failures.
  set +m
  shopt -s lastpipe
  if ! find "$revoked_directory" -mindepth 1 -maxdepth 1 -print0 2>/dev/null | mapfile -d '' -t revoked_files; then
    fail_json "backup_failed" "Unable to enumerate revoked backup state." 74
  fi
fi
[[ ${#revoked_files[@]} -le "$MAX_REVOKED_FILES" ]] || fail_json "backup_limit" "Backup state exceeds supported limits." 73
for file in "${revoked_files[@]}"; do
  archive_name=${file##*/}
  [[ "$archive_name" =~ ^[A-Za-z0-9_-]{1,32}\.conf(\.[A-Za-z0-9]{6})?$ ]] || fail_json "invalid_config" "Revoked backup names are unsupported." 73
  read_snapshot_file "$file" "$MAX_REVOKED_BYTES"
  [[ -z "$revoked_json" ]] || revoked_json+=,
  revoked_json+="{\"archiveName\":$(json_string "$archive_name"),\"config\":$(json_string "$SNAPSHOT_CONTENT")}"
  [[ $((${#config_json} + ${#revoked_json})) -le "$MAX_SNAPSHOT_BYTES" ]] || fail_json "backup_limit" "Backup state exceeds supported limits." 73
done
captured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ') || fail_json "backup_failed" "Unable to date backup state." 74
snapshot=$(printf '{"ok":true,"interface":%s,"config":%s,"revokedCount":%s,"snapshotVersion":1,"capturedAtUtc":%s,"revoked":[%s]}' \
  "$(json_string "$WG_IFACE")" "$config_json" "$(json_string "${#revoked_files[@]}")" "$(json_string "$captured_at")" "$revoked_json")
[[ $((${#snapshot} + 1)) -le "$MAX_SNAPSHOT_BYTES" ]] || fail_json "backup_limit" "Backup state exceeds supported limits." 73

printf '%s\n' "$snapshot"
