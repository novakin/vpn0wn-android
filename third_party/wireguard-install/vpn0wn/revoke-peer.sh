#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

name=""
expected_public_key=""
expected_server_public_key=""
operation_id=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) require_arg_value "$@"; name=$2; shift 2 ;;
    --expected-public-key) require_arg_value "$@"; require_public_key "$2"; expected_public_key=$2; shift 2 ;;
    --expected-server-public-key) require_arg_value "$@"; require_public_key "$2"; expected_server_public_key=$2; shift 2 ;;
    --operation-id) require_arg_value "$@"; [[ "$2" =~ ^$REVOCATION_OPERATION_ID_PATTERN$ ]] || fail_json "invalid_operation_id" "Operation identity is invalid." 64; operation_id=$2; shift 2 ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_root
require_owned_config
[[ -n "$name" ]] || fail_json "missing_name" "--name is required." 64
name=$(sanitize_name "$name")
lock_interface
require_owned_config
if [[ -n "$operation_id" ]]; then
  read_revoked_archive_inventory || fail_json "archive_inventory_unknown" "Revocation history cannot be verified." 74
  [[ ${#REVOKED_ARCHIVE_NAMES[@]} -lt "$MAX_REVOKED_ARCHIVES" ]] || fail_json "archive_limit" "Revocation history exceeds supported limits." 73
  for previous_id in "${REVOKED_OPERATION_IDS[@]}"; do
    [[ "$previous_id" != "$operation_id" ]] || fail_json "operation_id_conflict" "Operation identity already exists." 73
  done
  [[ ! -e "$TUNNL_DIR/revoked/$name.conf.$operation_id" && ! -L "$TUNNL_DIR/revoked/$name.conf.$operation_id" ]] ||
    fail_json "operation_id_conflict" "Operation identity already exists." 73
fi
[[ -z "$expected_public_key" ]] || require_expected_peer "$name" "$expected_public_key"
if [[ -n "$expected_server_public_key" ]]; then
  server_private_key=$(awk '$1 == "PrivateKey" {print $3; exit}' "$WG_CONF")
  server_public_key=$(wg_public_key_from_private "$server_private_key" 2>/dev/null) || fail_json "key_failed" "Unable to determine server identity." 74
  [[ "$server_public_key" == "$expected_server_public_key" ]] || fail_json "server_identity_mismatch" "Server identity changed." 73
  observe_runtime || fail_json "runtime_unknown" "Unable to determine interface state." 74
  if [[ "$RUNTIME_RUNNING" == true ]]; then
    live_public_key=$(wg show "$WG_IFACE" public-key 2>/dev/null) || fail_json "runtime_unknown" "Unable to determine live server identity." 74
    [[ "$live_public_key" == "$expected_server_public_key" ]] || fail_json "server_identity_mismatch" "Live server identity changed." 73
  fi
fi
command_exists sync || fail_json "missing_tool" "sync is required for revocation archives." 69
begin_mutation peer
prepare_peer_removal "$name"

awk -v name="$name" '
  $0 == "# BEGIN_PEER " name { inside=1 }
  inside { print }
  $0 == "# END_PEER " name { exit }
' "$WG_CONF" > "$RECOVERY_DIR/revoked.conf"

chmod 600 "$RECOVERY_DIR/revoked.conf" || mutation_abort archive_failed
private_artifact_directory "$TUNNL_DIR/revoked"
if [[ -n "$operation_id" ]]; then
  archive="$TUNNL_DIR/revoked/$name.conf.$operation_id"
  ln -T -- "$RECOVERY_DIR/revoked.conf" "$archive" 2>/dev/null || mutation_abort archive_failed
  REVOKE_ARCHIVE=$archive
else
  archive=$(mktemp "$TUNNL_DIR/revoked/$name.conf.XXXXXX") || mutation_abort archive_failed
  REVOKE_ARCHIVE=$archive
  mv -fT -- "$RECOVERY_DIR/revoked.conf" "$archive" 2>/dev/null || mutation_abort archive_failed
fi
read_revoked_archive_inventory || mutation_abort archive_failed
archive_verified=false
for ((index=0; index<${#REVOKED_ARCHIVE_NAMES[@]}; index++)); do
  if [[ "${REVOKED_ARCHIVE_NAMES[index]}" == "${archive##*/}" && "${REVOKED_PEER_NAMES[index]}" == "$name" &&
        "${REVOKED_PEER_KEYS[index]}" == "$REMOVED_PUBLIC_KEY" && "${REVOKED_OPERATION_IDS[index]}" == "$operation_id" ]]; then
    archive_verified=true
  fi
done
[[ "$archive_verified" == true ]] || mutation_abort archive_failed
# Preserve the original private peer block before an irreversible interruption of apply.
sync -f "$archive" 2>/dev/null || mutation_abort archive_failed
apply_peer_config
finish_mutation

printf '{"ok":true,"name":%s,"removed":true,"revoked":true,"publicKey":%s,"archive":%s,"archiveName":%s,"operationId":%s,"cleanupPath":%s}\n' \
  "$(json_string "$name")" \
  "$(json_string "$REMOVED_PUBLIC_KEY")" \
  "$(json_string "$archive")" \
  "$(json_string "${archive##*/}")" \
  "$([[ -n "$operation_id" ]] && json_string "$operation_id" || printf null)" \
  "$(json_string "$CLEANUP_PATH")"
