#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

name=""
expected_public_key=""
expected_server_public_key=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) require_arg_value "$@"; name=$2; shift 2 ;;
    --expected-public-key) require_arg_value "$@"; require_public_key "$2"; expected_public_key=$2; shift 2 ;;
    --expected-server-public-key) require_arg_value "$@"; require_public_key "$2"; expected_server_public_key=$2; shift 2 ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_root
require_owned_config
[[ -n "$name" ]] || fail_json "missing_name" "--name is required." 64
name=$(sanitize_name "$name")
lock_interface
require_owned_config
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
begin_mutation peer
prepare_peer_removal "$name"
apply_peer_config
finish_mutation

printf '{"ok":true,"name":%s,"removed":true,"revoked":false,"publicKey":%s,"cleanupPath":%s}\n' "$(json_string "$name")" "$(json_string "$REMOVED_PUBLIC_KEY")" "$(json_string "$CLEANUP_PATH")"
