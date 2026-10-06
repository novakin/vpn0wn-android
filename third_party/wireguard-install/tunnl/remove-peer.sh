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
apply_peer_config
finish_mutation

printf '{"ok":true,"removed":%s,"publicKey":%s,"cleanupPath":%s}\n' "$(json_string "$name")" "$(json_string "$REMOVED_PUBLIC_KEY")" "$(json_string "$CLEANUP_PATH")"
