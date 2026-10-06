#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

name=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) require_arg_value "$@"; name=$2; shift 2 ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_root
require_owned_config
[[ -n "$name" ]] || fail_json "missing_name" "--name is required." 64
name=$(sanitize_name "$name")
lock_interface
require_owned_config
begin_mutation peer
prepare_peer_removal "$name"

awk -v name="$name" '
  $0 == "# BEGIN_PEER " name { inside=1 }
  inside { print }
  $0 == "# END_PEER " name { exit }
' "$WG_CONF" > "$RECOVERY_DIR/revoked.conf"

private_artifact_directory "$TUNNL_DIR/revoked"
apply_peer_config
archive=$(mktemp "$TUNNL_DIR/revoked/$name.conf.XXXXXX") || mutation_abort archive_failed
REVOKE_ARCHIVE=$archive
mv -fT -- "$RECOVERY_DIR/revoked.conf" "$archive" 2>/dev/null || mutation_abort archive_failed
finish_mutation

printf '{"ok":true,"revoked":%s,"publicKey":%s,"archive":%s,"cleanupPath":%s}\n' \
  "$(json_string "$name")" \
  "$(json_string "$REMOVED_PUBLIC_KEY")" \
  "$(json_string "$archive")" \
  "$(json_string "$CLEANUP_PATH")"
