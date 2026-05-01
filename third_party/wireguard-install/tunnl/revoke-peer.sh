#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

name=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) name=${2-}; shift 2 ;;
    *) fail_json "invalid_arg" "Unknown argument: $1" 64 ;;
  esac
done

require_root
[[ -r "$WG_CONF" ]] || fail_json "not_installed" "$WG_CONF does not exist." 72
[[ -n "$name" ]] || fail_json "missing_name" "--name is required." 64
name=$(sanitize_name "$name")
grep -q "^# BEGIN_PEER $name$" "$WG_CONF" || fail_json "peer_not_found" "Peer not found: $name" 66

mkdir -p "$TUNNL_DIR/revoked"
chmod 700 "$TUNNL_DIR" "$TUNNL_DIR/revoked"

public_key=$(awk -v name="$name" '
  $0 == "# BEGIN_PEER " name { inside=1 }
  inside && /^PublicKey = / { print $3; exit }
' "$WG_CONF")

awk -v name="$name" '
  $0 == "# BEGIN_PEER " name { inside=1 }
  inside { print }
  $0 == "# END_PEER " name { exit }
' "$WG_CONF" > "$TUNNL_DIR/revoked/$name.conf"

"$SCRIPT_DIR/remove-peer.sh" --name "$name" >/dev/null

printf '{"ok":true,"revoked":%s,"publicKey":%s,"archive":%s}\n' \
  "$(json_string "$name")" \
  "$(json_string "$public_key")" \
  "$(json_string "$TUNNL_DIR/revoked/$name.conf")"
