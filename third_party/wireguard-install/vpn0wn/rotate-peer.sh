#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

input=""
mode=rotate
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) require_arg_value "$@"; input=$2; shift 2 ;;
    --verify) mode=verify; shift ;;
    *) fail_json "invalid_arg" "Unknown rotation argument." 64 ;;
  esac
done

require_root
read_rotation_input "$input"
require_owned_config
lock_interface
require_owned_config
if [[ "$mode" == verify ]]; then
  require_rotation_state "${ROTATION_INPUT[newPublicKey]}" "${ROTATION_INPUT[oldPublicKey]}"
  CLEANUP_PATH=""
else
  require_rotation_state "${ROTATION_INPUT[oldPublicKey]}" "${ROTATION_INPUT[newPublicKey]}"
  begin_mutation peer
  inside=false
  while true; do
    line=""
    terminated=false
    if IFS= read -r line; then terminated=true; fi
    [[ "$terminated" == true || -n "$line" ]] || break
    if [[ "$line" == "# BEGIN_PEER ${ROTATION_INPUT[name]}" ]]; then inside=true; fi
    if [[ "$inside" == true && "$line" =~ ^[[:space:]]*PublicKey[[:space:]]*= ]]; then
      line=${line/"${ROTATION_INPUT[oldPublicKey]}"/"${ROTATION_INPUT[newPublicKey]}"}
    fi
    if [[ "$line" == "# END_PEER ${ROTATION_INPUT[name]}" ]]; then inside=false; fi
    printf '%s' "$line"
    [[ "$terminated" != true ]] || printf '\n'
    [[ "$terminated" == true ]] || break
  done < "$WG_CONF" > "$CANDIDATE"
  prepare_candidate
  apply_peer_config
  finish_mutation
fi

printf '{"ok":true,"mode":%s,"verified":true,"interface":"wg0","operationId":%s,"name":%s,"serverPublicKey":%s,"oldPublicKey":%s,"newPublicKey":%s,"running":%s,"cleanupPath":%s}\n' \
  "$(json_string "$mode")" "$(json_string "${ROTATION_INPUT[operationId]}")" "$(json_string "${ROTATION_INPUT[name]}")" \
  "$(json_string "${ROTATION_INPUT[serverPublicKey]}")" "$(json_string "${ROTATION_INPUT[oldPublicKey]}")" \
  "$(json_string "${ROTATION_INPUT[newPublicKey]}")" "$RUNTIME_RUNNING" "$(json_string "$CLEANUP_PATH")"
