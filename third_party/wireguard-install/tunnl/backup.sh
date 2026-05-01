#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

require_root
[[ -r "$WG_CONF" ]] || fail_json "not_installed" "$WG_CONF does not exist." 72

conf=$(cat "$WG_CONF")
revoked_count=0
[[ -d "$TUNNL_DIR/revoked" ]] && revoked_count=$(find "$TUNNL_DIR/revoked" -type f -name '*.conf' | wc -l)

printf '{"ok":true,"interface":%s,"config":%s,"revokedCount":%s}\n' \
  "$(json_string "$WG_IFACE")" \
  "$(json_string "$conf")" \
  "$(json_string "$revoked_count")"
