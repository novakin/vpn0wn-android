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

public_key=$(awk -v name="$name" '
  $0 == "# BEGIN_PEER " name { inside=1 }
  inside && /^PublicKey = / { print $3; exit }
' "$WG_CONF")

tmp=$(mktemp)
awk -v name="$name" '
  $0 == "# BEGIN_PEER " name { skip=1; next }
  $0 == "# END_PEER " name { skip=0; next }
  !skip { print }
' "$WG_CONF" > "$tmp"
cat "$tmp" > "$WG_CONF"
rm -f "$tmp"

[[ -n "$public_key" ]] && wg set "$WG_IFACE" peer "$public_key" remove >&2 || true
wg syncconf "$WG_IFACE" <(wg-quick strip "$WG_IFACE") >&2 || true

printf '{"ok":true,"removed":%s,"publicKey":%s}\n' "$(json_string "$name")" "$(json_string "$public_key")"
