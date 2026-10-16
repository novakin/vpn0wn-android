#!/usr/bin/env bash
set -euo pipefail

TEST_SCRIPT=$(realpath "${BASH_SOURCE[0]}")
REPO_DIR=$(cd "$(dirname "$TEST_SCRIPT")/../.." && pwd)
SCRIPTS="${TUNNL_TEST_SCRIPTS:-$REPO_DIR/third_party/wireguard-install/vpn0wn}"

# UID 0 is required by the real scripts, but tests must never gain host root.
[[ "$EUID" -eq 0 && $(awk '$1 == 0 {print $2}' /proc/self/uid_map) != 0 ]] || {
  printf 'Run with unshare --user --map-root-user bash %s\n' "$TEST_SCRIPT" >&2
  exit 77
}

setup() {
  TEST_ROOT=$(mktemp -d)
  trap '/bin/rm -rf -- "$TEST_ROOT"' EXIT
  export TEST_STATE="$TEST_ROOT/state"
  export WG_IFACE=wg0 WG_DIR="$TEST_ROOT/wireguard"
  export WG_CONF="$WG_DIR/wg0.conf" TUNNL_DIR="$WG_DIR/tunnl"
  export WG_LOCK_FILE="$TEST_ROOT/interface.lock"
  export WG_HOST_LOCK_FILE="$TEST_ROOT/host.lock"
  export FORWARD_CONF="$TEST_ROOT/forward.conf"
  mkdir -p "$TEST_STATE" "$WG_DIR" "$TEST_ROOT/bin"
  /bin/cp "$REPO_DIR/scripts/tests/fixtures/provisioning-command.sh" "$TEST_ROOT/bin/command"
  chmod 700 "$TEST_ROOT/bin/command"
  for tool in wg wg-quick systemctl apt-get dpkg-query sysctl ip cp mv rm; do
    ln -s command "$TEST_ROOT/bin/$tool"
  done
  export PATH="$TEST_ROOT/bin:$PATH"
  : > "$TEST_STATE/trace"
  printf '0' > "$TEST_STATE/forward-value"
}

owned_config() {
  local private public
  private=$(printf '%-32.32s' PRIVATE-old | base64 -w0)
  public=$(printf '%-32.32s' PUBLIC-existing | base64 -w0)
  cat > "$WG_CONF" <<EOF
# TUNNL_MANAGED 1
# ENDPOINT vpn.example
# TUNNL_CIDR 10.8.0.0/24
# TUNNL_DNS 1.1.1.1, 1.0.0.1
[Interface]
Address = 10.8.0.1/24
PrivateKey = $private
ListenPort = 51820
SaveConfig = false
PostUp = iptables -t nat -A POSTROUTING -s 10.8.0.0/24 ! -d 10.8.0.0/24 -j MASQUERADE
PostDown = iptables -t nat -D POSTROUTING -s 10.8.0.0/24 ! -d 10.8.0.0/24 -j MASQUERADE

# BEGIN_PEER existing
[Peer]
PublicKey = $public
AllowedIPs = 10.8.0.2/32
# END_PEER existing
EOF
  chmod 600 "$WG_CONF"
  /bin/cp "$WG_CONF" "$TEST_STATE/runtime.conf"
  /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
  printf '10.8.0.1/24\n' > "$TEST_STATE/address"
  printf '10.8.0.0/24' > "$TEST_STATE/nat"
  printf 'net.ipv4.ip_forward=1\n' > "$FORWARD_CONF"
  touch "$TEST_STATE/enabled"
  printf '%s\n' wireguard wireguard-tools qrencode iptables > "$TEST_STATE/packages"
}

run_script() {
  set +e
  bash "$SCRIPTS/$1.sh" "${@:2}" > "$TEST_ROOT/response" 2> "$TEST_ROOT/stderr"
  RESULT_CODE=$?
  set -e
  RESPONSE=$(cat "$TEST_ROOT/response")
}

assert_success() {
  [[ "$RESULT_CODE" -eq 0 && "$RESPONSE" == *'"ok":true'* ]] || {
    printf 'Expected successful command; got exit %s.\n' "$RESULT_CODE" >&2
    printf '%s' "$RESPONSE" | sed -n 's/.*"error":"\([a-z_]*\)".*/Failure category: \1/p' >&2
    return 1
  }
}

assert_failure() {
  [[ "$RESULT_CODE" -ne 0 && "$RESPONSE" == *'"ok":false'* && "$RESPONSE" == *"\"error\":\"$1\""* ]] || {
    printf 'Expected safe failure %s; got exit %s.\n' "$1" "$RESULT_CODE" >&2
    return 1
  }
  ! grep -q 'synthetic-secret-tool-diagnostic' "$TEST_ROOT/response" "$TEST_ROOT/stderr"
}

assert_no_mutations() {
  ! grep -Eq '^(apt-get|sysctl|cp|mv|rm) |^systemctl (enable|disable|stop|start|restart) |^wg (genkey|genpsk|syncconf|set) ' "$TEST_STATE/trace"
}

assert_restored() {
  cmp -s "$WG_CONF" "$TEST_ROOT/before.conf"
  awk '!/^#/ && !/^(Address|DNS|SaveConfig|PostUp|PostDown) = /' "$TEST_STATE/runtime.conf" > "$TEST_ROOT/actual.runtime"
  awk '!/^#/ && !/^(Address|DNS|SaveConfig|PostUp|PostDown) = /' "$TEST_ROOT/before.conf" > "$TEST_ROOT/expected.runtime"
  cmp -s "$TEST_ROOT/actual.runtime" "$TEST_ROOT/expected.runtime"
  [[ -f "$TEST_STATE/enabled" ]]
}

assert_private() {
  [[ $(stat -c %a "$1") == "$2" ]]
}

test_c2_unmanaged_uninstall() {
  printf '[Interface]\n# user managed\n' > "$WG_CONF"
  /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
  run_script uninstall --confirm
  assert_failure unmanaged_config
  cmp -s "$WG_CONF" "$TEST_ROOT/before.conf"
  assert_no_mutations
}

test_c2_unmanaged_force_install() {
  printf '[Interface]\n# user managed\n' > "$WG_CONF"
  /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
  run_script install --endpoint vpn.example --force
  assert_failure unmanaged_config
  cmp -s "$WG_CONF" "$TEST_ROOT/before.conf"
  assert_no_mutations
}

test_c2_symlink_config() {
  owned_config
  /bin/mv "$WG_CONF" "$TEST_ROOT/other.conf"
  ln -s "$TEST_ROOT/other.conf" "$WG_CONF"
  run_script install --endpoint vpn.example --force
  assert_failure unmanaged_config
  [[ -L "$WG_CONF" ]]
  cmp -s "$TEST_ROOT/other.conf" "$TEST_ROOT/before.conf"
  assert_no_mutations
}

test_c2_unmanaged_peer_mutations() {
  printf '# BEGIN_PEER existing\n[Peer]\nPublicKey = synthetic\n# END_PEER existing\n' > "$WG_CONF"
  /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
  for operation in add-peer remove-peer revoke-peer; do
    run_script "$operation" --name existing
    assert_failure unmanaged_config
    cmp -s "$WG_CONF" "$TEST_ROOT/before.conf"
    assert_no_mutations
  done
}

test_c2_endpoint_injection() {
  for endpoint in $'vpn.example\nPostUp = injected' $'vpn.example\r' $'vpn.example\t'; do
    run_script install --endpoint "$endpoint"
    assert_failure invalid_endpoint
    [[ ! -e "$WG_CONF" && ! -e "$FORWARD_CONF" ]]
    assert_no_mutations
  done
}

test_c2_dns_injection() {
  for dns in $'1.1.1.1\n[Peer]' $'1.1.1.1\r' $'1.1.1.1\t' '1.1.1.1,,8.8.8.8'; do
    run_script plan --endpoint vpn.example --dns "$dns"
    assert_failure invalid_dns
    assert_no_mutations
  done
}

test_c2_peer_input_injection() {
  owned_config
  run_script add-peer --name laptop --dns $'1.1.1.1\nPostUp = injected'
  assert_failure invalid_dns
  run_script add-peer --name laptop --allowed-ips $'0.0.0.0/0\nPostUp = injected'
  assert_failure invalid_allowed_ips
  cmp -s "$WG_CONF" "$TEST_ROOT/before.conf"
  assert_no_mutations
}

test_c2_missing_values() {
  run_script install --endpoint
  assert_failure missing_arg
  run_script plan --endpoint --dns
  assert_failure missing_arg
  run_script add-peer --name
  assert_failure missing_arg
  assert_no_mutations
}

test_c2_foreign_forwarding_file() {
  printf 'net.ipv4.ip_forward=0\n# user settings\n' > "$FORWARD_CONF"
  /bin/cp "$FORWARD_CONF" "$TEST_ROOT/before.forward"
  run_script install --endpoint vpn.example
  assert_failure unmanaged_forwarding
  cmp -s "$FORWARD_CONF" "$TEST_ROOT/before.forward"
  assert_no_mutations
}

test_c2_valid_plan() {
  run_script plan --endpoint '[2001:db8::1]' --dns '1.1.1.1, 2001:4860:4860::8888, example.internal'
  assert_success
  assert_no_mutations
}

test_c2_valid_install() {
  run_script install --endpoint vpn.example --dns '1.1.1.1, 1.0.0.1'
  assert_success
  grep -qxF '# TUNNL_MANAGED 1' "$WG_CONF"
  [[ -f "$FORWARD_CONF" && -f "$TEST_STATE/runtime.conf" ]]
}

test_c2_valid_add() {
  owned_config
  run_script add-peer --name laptop --allowed-ips '0.0.0.0/0, ::/0'
  assert_success
  grep -qxF '# BEGIN_PEER laptop' "$WG_CONF"
  [[ "$RESPONSE" == *'0.0.0.0/0, ::/0'* ]]
}

test_c2_shared_package_config() {
  owned_config
  printf '# foreign config\n' > "$WG_DIR/other.conf"
  run_script uninstall --confirm --purge-packages
  assert_failure shared_wireguard_state
  assert_restored
  assert_no_mutations
}

test_c2_shared_package_runtime() {
  owned_config
  printf 'other0\n' > "$TEST_STATE/other-interfaces"
  run_script uninstall --confirm --purge-packages
  assert_failure shared_wireguard_state
  assert_restored
  assert_no_mutations
}

test_c3_concurrent_adds() {
  owned_config
  printf '0.05' > "$TEST_STATE/sync-delay"
  bash "$SCRIPTS/add-peer.sh" --name first > "$TEST_ROOT/first" 2> "$TEST_ROOT/first.err" &
  first_pid=$!
  bash "$SCRIPTS/add-peer.sh" --name second > "$TEST_ROOT/second" 2> "$TEST_ROOT/second.err" &
  second_pid=$!
  wait "$first_pid"
  wait "$second_pid"
  [[ $(grep -c '^# BEGIN_PEER ' "$WG_CONF") -eq 3 ]]
  [[ $(awk '$1 == "AllowedIPs" {print $3}' "$WG_CONF" | sort -u | wc -l) -eq 3 ]]
  [[ $(awk '$1 == "PublicKey" {print $3}' "$TEST_STATE/runtime.conf" | wc -l) -eq 3 ]]
  grep -q '"ok":true' "$TEST_ROOT/first"
  grep -q '"ok":true' "$TEST_ROOT/second"
}

test_c3_concurrent_add_remove() {
  owned_config
  printf '0.05' > "$TEST_STATE/sync-delay"
  bash "$SCRIPTS/add-peer.sh" --name fresh > "$TEST_ROOT/add" 2> "$TEST_ROOT/add.err" &
  add_pid=$!
  bash "$SCRIPTS/remove-peer.sh" --name existing > "$TEST_ROOT/remove" 2> "$TEST_ROOT/remove.err" &
  remove_pid=$!
  wait "$add_pid"
  wait "$remove_pid"
  grep -qxF '# BEGIN_PEER fresh' "$WG_CONF"
  ! grep -q '^# BEGIN_PEER existing$' "$WG_CONF"
  [[ $(awk '$1 == "PublicKey" {print $3}' "$TEST_STATE/runtime.conf" | wc -l) -eq 1 ]]
}

test_c3_strip_failure() {
  owned_config
  printf '*' > "$TEST_STATE/fail-wg-quick-strip"
  run_script add-peer --name laptop
  assert_failure invalid_config
  assert_restored
  ! grep -q '^wg syncconf ' "$TEST_STATE/trace"
}

test_c3_candidate_copy_failure() {
  owned_config
  printf '2' > "$TEST_STATE/fail-cp-copy"
  run_script add-peer --name laptop
  assert_failure write_failed
  assert_restored
}

test_c3_atomic_rename_failure() {
  owned_config
  printf '1' > "$TEST_STATE/fail-mv-rename"
  run_script add-peer --name laptop
  assert_failure write_failed
  assert_restored
  assert_private "$WG_CONF" 600
}

test_c3_apply_failure_retry() {
  owned_config
  printf '1' > "$TEST_STATE/fail-wg-syncconf"
  printf '1' > "$TEST_STATE/fail-systemctl-restart"
  run_script add-peer --name laptop
  assert_failure apply_failed
  [[ "$RESPONSE" == *'"recovery":"restored"'* ]]
  assert_restored
  run_script add-peer --name laptop
  assert_success
  grep -qxF '# BEGIN_PEER laptop' "$WG_CONF"
}

test_c3_rollback_failure_retains_snapshot() {
  owned_config
  printf '*' > "$TEST_STATE/fail-wg-syncconf"
  printf '*' > "$TEST_STATE/fail-systemctl-restart"
  run_script add-peer --name laptop
  assert_failure apply_failed
  [[ "$RESPONSE" == *'"recovery":"incomplete"'* ]]
  recovery=$(find "$WG_DIR" -maxdepth 1 -type d -name '.tunnl-recovery.*')
  [[ -n "$recovery" && -f "$recovery/previous.conf" && -f "$recovery/previous.runtime" ]]
  assert_private "$recovery" 700
  assert_private "$recovery/previous.conf" 600
  assert_private "$recovery/previous.runtime" 600
}

test_c3_ignored_apply() {
  owned_config
  touch "$TEST_STATE/ignore-sync"
  run_script remove-peer --name existing
  assert_failure postcondition_failed
  assert_restored
}

test_c3_restart_fallback() {
  owned_config
  printf '1' > "$TEST_STATE/fail-wg-syncconf"
  run_script remove-peer --name existing
  assert_success
  ! grep -q '^# BEGIN_PEER existing$' "$WG_CONF"
  [[ -z $(awk '$1 == "PublicKey" {print $3}' "$TEST_STATE/runtime.conf") ]]
}

test_c3_stopped_peer_mutation() {
  owned_config
  /bin/rm "$TEST_STATE/runtime.conf" "$TEST_STATE/enabled"
  run_script add-peer --name laptop
  assert_success
  [[ ! -e "$TEST_STATE/runtime.conf" && ! -e "$TEST_STATE/enabled" ]]
  ! grep -Eq '^wg syncconf |^systemctl (start|restart) ' "$TEST_STATE/trace"
}

test_c3_runtime_unknown() {
  owned_config
  printf '*' > "$TEST_STATE/fail-wg-show"
  run_script remove-peer --name existing
  assert_failure runtime_unknown
  assert_restored
  assert_no_mutations
}

test_c3_revoke_effective_and_private() {
  owned_config
  run_script revoke-peer --name existing
  assert_success
  ! grep -q '^# BEGIN_PEER existing$' "$WG_CONF"
  [[ -z $(awk '$1 == "PublicKey" {print $3}' "$TEST_STATE/runtime.conf") ]]
  archive=$(find "$TUNNL_DIR/revoked" -type f -name 'existing.conf.*')
  [[ -n "$archive" ]]
  assert_private "$TUNNL_DIR/revoked" 700
  assert_private "$archive" 600
  [[ -f "$WG_LOCK_FILE" ]]
}

test_c3_revoke_apply_failure() {
  owned_config
  printf '1' > "$TEST_STATE/fail-wg-syncconf"
  printf '1' > "$TEST_STATE/fail-systemctl-restart"
  run_script revoke-peer --name existing
  assert_failure apply_failed
  assert_restored
  [[ -z $(find "$TUNNL_DIR/revoked" -type f) ]]
}

test_c3_revoke_archive_failure() {
  owned_config
  printf '2' > "$TEST_STATE/fail-mv-rename"
  run_script revoke-peer --name existing
  assert_failure archive_failed
  assert_restored
  [[ -z $(find "$TUNNL_DIR/revoked" -type f) ]]
}

test_c3_force_runtime_reconciled() {
  owned_config
  run_script install --endpoint fresh.example --port 51900 --cidr 10.9.0.0/24 --force
  assert_success
  grep -qxF 'ListenPort = 51900' "$TEST_STATE/runtime.conf"
  grep -qxF '10.9.0.1/24' "$TEST_STATE/address"
  grep -qxF '10.8.0.0/24' "$TEST_STATE/stopped-cidr"
  expected=$(awk '$1 == "PrivateKey" {print $3}' "$WG_CONF" | wg pubkey)
  actual=$(wg show wg0 public-key)
  [[ "$expected" == "$actual" && "$RESPONSE" == *"$actual"* ]]
  ! cmp -s "$WG_CONF" "$TEST_ROOT/before.conf"
}

test_c3_force_restart_failure() {
  owned_config
  printf '1' > "$TEST_STATE/fail-systemctl-restart"
  run_script install --endpoint fresh.example --force
  assert_failure apply_failed
  assert_restored
  [[ "$RESPONSE" == *'Package changes and global forwarding may remain'* ]]
}

test_c3_force_postcondition_rollback_hooks() {
  owned_config
  touch "$TEST_STATE/wrong-address-once"
  run_script install --endpoint fresh.example --cidr 10.9.0.0/24 --force
  assert_failure postcondition_failed
  [[ "$RESPONSE" == *'"recovery":"restored"'* ]]
  assert_restored
  grep -qxF '10.9.0.0/24' "$TEST_STATE/stopped-cidr"
  grep -qxF '10.8.0.0/24' "$TEST_STATE/nat"
}

test_c3_new_postcondition_rollback_hooks() {
  touch "$TEST_STATE/wrong-address-once"
  run_script install --endpoint vpn.example
  assert_failure postcondition_failed
  [[ "$RESPONSE" == *'"recovery":"restored"'* ]]
  [[ ! -e "$WG_CONF" && ! -e "$TEST_STATE/runtime.conf" && ! -e "$TEST_STATE/nat" ]]
}

hide_wg() {
  /bin/rm "$TEST_ROOT/bin/wg"
  for tool in bash dirname grep sed tr cut seq awk sort chmod mkdir mktemp cat base64 sleep find cmp stat wc flock touch; do
    ln -s "$(command -v "$tool")" "$TEST_ROOT/bin/$tool"
  done
  export PATH="$TEST_ROOT/bin"
}

test_c3_missing_wg_existing_interface() {
  touch "$TEST_STATE/runtime.conf"
  hide_wg
  run_script install --endpoint vpn.example
  assert_failure unmanaged_runtime
  assert_no_mutations
  [[ ! -e "$WG_CONF" ]]
}

test_c3_missing_wg_clean_install() {
  touch "$TEST_STATE/restore-wg-on-install"
  hide_wg
  run_script install --endpoint vpn.example
  assert_success
  [[ -f "$WG_CONF" && -f "$TEST_STATE/runtime.conf" ]]
}

test_c3_force_ignored_restart() {
  owned_config
  touch "$TEST_STATE/ignore-restart"
  run_script install --endpoint fresh.example --force
  assert_failure postcondition_failed
  cmp -s "$WG_CONF" "$TEST_ROOT/before.conf"
  [[ "$RESPONSE" == *'"recovery":"incomplete"'* ]]
}

test_c3_new_install_failure() {
  printf '1' > "$TEST_STATE/fail-systemctl-restart"
  run_script install --endpoint vpn.example
  assert_failure apply_failed
  [[ ! -e "$WG_CONF" && ! -e "$FORWARD_CONF" && ! -e "$TEST_STATE/runtime.conf" && ! -e "$TEST_STATE/enabled" ]]
}

test_c3_uninstall_stop_failure() {
  owned_config
  printf '1' > "$TEST_STATE/fail-systemctl-stop"
  run_script uninstall --confirm
  assert_failure stop_failed
  assert_restored
  [[ -f "$FORWARD_CONF" ]]
}

test_c3_uninstall_disable_failure() {
  owned_config
  printf '1' > "$TEST_STATE/fail-systemctl-disable"
  run_script uninstall --confirm
  assert_failure disable_failed
  assert_restored
}

test_c3_uninstall_ignored_stop() {
  owned_config
  touch "$TEST_STATE/ignore-stop"
  run_script uninstall --confirm
  assert_failure postcondition_failed
  cmp -s "$WG_CONF" "$TEST_ROOT/before.conf"
  [[ -f "$TEST_STATE/runtime.conf" && "$RESPONSE" == *'"recovery":"incomplete"'* ]]
}

test_c3_uninstall_delete_failure() {
  owned_config
  printf '1' > "$TEST_STATE/fail-rm-remove"
  run_script uninstall --confirm
  assert_failure write_failed
  assert_restored
}

test_c3_uninstall_purge_failure() {
  owned_config
  printf '1' > "$TEST_STATE/fail-apt-get-purge"
  run_script uninstall --confirm --purge-packages
  assert_failure purge_failed
  assert_restored
  [[ "$RESPONSE" == *'Package changes and global forwarding may remain'* ]]
}

test_c3_uninstall_partial_purge() {
  owned_config
  touch "$TEST_STATE/partial-purge"
  run_script uninstall --confirm --purge-packages
  assert_failure purge_failed
  [[ "$RESPONSE" == *'"recovery":"incomplete"'* ]]
  backup=$(find "$TUNNL_DIR/backups" -type f -name 'wg0.conf.*')
  [[ -n "$backup" && "$RESPONSE" == *"$backup"* ]]
  assert_private "$backup" 600
  [[ "$RESPONSE" == *'Package changes and global forwarding may remain'* ]]
  ! grep -q '^apt-get autoremove ' "$TEST_STATE/trace"
}

test_c3_revoke_cleanup_failure() {
  owned_config
  printf '1' > "$TEST_STATE/fail-rm-cleanup"
  touch "$TEST_STATE/partial-cleanup"
  run_script revoke-peer --name existing
  assert_success
  ! grep -q '^# BEGIN_PEER existing$' "$WG_CONF"
  [[ -z $(awk '$1 == "PublicKey" {print $3}' "$TEST_STATE/runtime.conf") ]]
  [[ -n $(find "$TUNNL_DIR/revoked" -type f) ]]
  [[ "$RESPONSE" != *'"cleanupPath":""'* ]]
}

test_c3_install_absent_template() {
  touch "$TEST_STATE/absent-template"
  run_script install --endpoint vpn.example
  assert_success
  [[ -f "$WG_CONF" && -f "$TEST_STATE/runtime.conf" ]]
}

test_c3_install_unknown_service_bus() {
  touch "$TEST_STATE/absent-template"
  printf '*' > "$TEST_STATE/fail-systemctl-show"
  run_script install --endpoint vpn.example
  assert_failure runtime_unknown
  assert_no_mutations
}

test_c3_wrong_psk() {
  owned_config
  touch "$TEST_STATE/ignore-psk"
  run_script add-peer --name laptop
  assert_failure postcondition_failed
  assert_restored
}

test_c3_wrong_server_key() {
  owned_config
  printf 'key' > "$TEST_STATE/wrong-runtime"
  run_script add-peer --name laptop
  assert_failure postcondition_failed
  assert_restored
}

test_c3_wrong_listen_port() {
  owned_config
  printf 'port' > "$TEST_STATE/wrong-runtime"
  run_script add-peer --name laptop
  assert_failure postcondition_failed
  assert_restored
}

test_c3_wrong_runtime_routes() {
  owned_config
  printf 'routes' > "$TEST_STATE/wrong-runtime"
  run_script add-peer --name laptop
  assert_failure postcondition_failed
  assert_restored
}

test_c3_residual_package_config() {
  owned_config
  touch "$TEST_STATE/residual-package"
  run_script uninstall --confirm --purge-packages
  assert_failure purge_failed
  [[ -n $(find "$TUNNL_DIR/backups" -type f) ]]
}

test_c3_shared_host_lock() {
  owned_config
  printf '0.3' > "$TEST_STATE/install-delay"
  env WG_IFACE=wg1 WG_CONF="$WG_DIR/wg1.conf" WG_LOCK_FILE="$TEST_ROOT/wg1.lock" bash "$SCRIPTS/install.sh" --endpoint second.example --cidr 10.9.0.0/24 > "$TEST_ROOT/install-other" 2> "$TEST_ROOT/install-other.err" &
  install_pid=$!
  for attempt in $(seq 1 100); do
    [[ ! -f "$TEST_STATE/count-apt-get-install" ]] || break
    sleep 0.01
  done
  [[ -f "$TEST_STATE/count-apt-get-install" ]]
  run_script uninstall --confirm --purge-packages
  assert_failure shared_wireguard_state
  wait "$install_pid"
  [[ -f "$WG_DIR/wg1.conf" && -f "$FORWARD_CONF" && -f "$TEST_STATE/runtime-wg1.conf" ]]
  assert_restored
  ! grep -q '^apt-get purge ' "$TEST_STATE/trace"
}

test_c3_malformed_peer_markers() {
  owned_config
  cat >> "$WG_CONF" <<EOF

# BEGIN_PEER bob
[Peer]
PublicKey = $(printf '%-32.32s' PUBLIC-bob | base64 -w0)
AllowedIPs = 10.8.0.3/32
# END_PEER bob
EOF
  /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
  for malformed in reversed duplicate nested multiple-native-peers; do
    /bin/cp "$TEST_ROOT/before.conf" "$WG_CONF"
    case "$malformed" in
      reversed) sed -i '/# BEGIN_PEER existing/i # END_PEER existing' "$WG_CONF" ;;
      duplicate) sed -i '/# BEGIN_PEER existing/a # BEGIN_PEER existing' "$WG_CONF" ;;
      nested) sed -i '/# BEGIN_PEER existing/a # BEGIN_PEER other' "$WG_CONF" ;;
      multiple-native-peers) sed -i '/# END_PEER existing/i [Peer]\nPublicKey = second-synthetic-key\nAllowedIPs = 10.8.0.99/32' "$WG_CONF" ;;
    esac
    /bin/cp "$WG_CONF" "$TEST_ROOT/malformed.conf"
    run_script remove-peer --name existing
    assert_failure invalid_config
    cmp -s "$WG_CONF" "$TEST_ROOT/malformed.conf"
    run_script add-peer --name fresh
    assert_failure invalid_config
    cmp -s "$WG_CONF" "$TEST_ROOT/malformed.conf"
  done
}

test_c3_restore_disk_failure() {
  owned_config
  printf '1' > "$TEST_STATE/fail-wg-syncconf"
  printf '1' > "$TEST_STATE/fail-systemctl-restart"
  printf '2' > "$TEST_STATE/fail-mv-rename"
  run_script add-peer --name laptop
  assert_failure apply_failed
  [[ "$RESPONSE" == *'"recovery":"incomplete"'* ]]
  recovery=$(find "$WG_DIR" -maxdepth 1 -type d -name '.tunnl-recovery.*')
  cmp -s "$recovery/previous.conf" "$TEST_ROOT/before.conf"
}

test_c3_unique_backup_and_keep_packages() {
  owned_config
  run_script uninstall --confirm
  assert_success
  /bin/cp "$TEST_ROOT/before.conf" "$WG_CONF"
  /bin/cp "$TEST_ROOT/before.conf" "$TEST_STATE/runtime.conf"
  touch "$TEST_STATE/enabled"
  run_script uninstall --confirm
  assert_success
  [[ $(find "$TUNNL_DIR/backups" -type f | wc -l) -eq 2 ]]
  grep -qxF wireguard "$TEST_STATE/packages"
  ! grep -q '^apt-get purge ' "$TEST_STATE/trace"
}

test_c3_uninstall_ignored_purge() {
  owned_config
  touch "$TEST_STATE/ignore-purge"
  run_script uninstall --confirm --purge-packages
  assert_failure purge_failed
  assert_restored
}

test_c3_uninstall_success_keeps_global_forwarding() {
  owned_config
  printf '1' > "$TEST_STATE/forward-value"
  run_script uninstall --confirm --purge-packages
  assert_success
  [[ ! -e "$WG_CONF" && ! -e "$FORWARD_CONF" && ! -e "$TEST_STATE/runtime.conf" && ! -e "$TEST_STATE/enabled" ]]
  [[ $(cat "$TEST_STATE/forward-value") == 1 && -f "$WG_LOCK_FILE" ]]
  [[ $(cat "$TEST_STATE/packages") == iptables && ! -e "$TEST_STATE/autoremove" ]]
  backup=$(find "$TUNNL_DIR/backups" -type f -name 'wg0.conf.*')
  assert_private "$TUNNL_DIR/backups" 700
  assert_private "$backup" 600
  cmp -s "$backup" "$TEST_ROOT/before.conf"
}

test_c3_uninstall_preserves_other_config() {
  owned_config
  printf '# foreign config\n' > "$WG_DIR/other.conf"
  run_script uninstall --confirm
  assert_success
  [[ -f "$WG_DIR/other.conf" && -f "$FORWARD_CONF" && -f "$TEST_STATE/packages" ]]
}

test_c3_signal_recovery() {
  owned_config
  printf '0.3' > "$TEST_STATE/sync-delay"
  bash "$SCRIPTS/add-peer.sh" --name laptop > "$TEST_ROOT/response" 2> "$TEST_ROOT/stderr" &
  mutation_pid=$!
  for attempt in $(seq 1 100); do
    [[ ! -f "$TEST_STATE/count-wg-syncconf" ]] || break
    sleep 0.01
  done
  [[ -f "$TEST_STATE/count-wg-syncconf" ]]
  kill -TERM "$mutation_pid"
  wait "$mutation_pid" && RESULT_CODE=0 || RESULT_CODE=$?
  RESPONSE=$(cat "$TEST_ROOT/response")
  assert_failure interrupted
  assert_restored
}

test_fixture_legacy_file_commands() {
  printf 'synthetic fixture content\n' > "$TEST_ROOT/source"
  cp "$TEST_ROOT/source" "$TEST_ROOT/copy"
  cmp -s "$TEST_ROOT/source" "$TEST_ROOT/copy"
  mv "$TEST_ROOT/copy" "$TEST_ROOT/moved"
  [[ ! -e "$TEST_ROOT/copy" && -f "$TEST_ROOT/moved" ]]
  rm "$TEST_ROOT/moved"
  [[ ! -e "$TEST_ROOT/moved" ]]
}

check_contract_fixture() {
  local name=$1 expected="$REPO_DIR/ui/src/test/resources/provisioning/$1.json"
  if [[ "$name" == backup ]]; then
    sed -E 's/\.conf\.[A-Za-z0-9]{6}/.conf.ABC123/g; s/"capturedAtUtc":"[^"]+"/"capturedAtUtc":"2026-10-16T00:00:00Z"/' "$TEST_ROOT/response" > "$TEST_ROOT/normalized"
  else
    sed -E "s|$TEST_ROOT|/fixture|g; s/\\.conf\\.[A-Za-z0-9]{6}/.conf.synthetic/g" "$TEST_ROOT/response" > "$TEST_ROOT/normalized"
  fi
  if [[ ${TUNNL_UPDATE_CONTRACT_FIXTURES:-0} == 1 ]]; then
    mkdir -p "$(dirname "$expected")"
    /bin/cp "$TEST_ROOT/normalized" "$expected"
  else
    cmp -s "$TEST_ROOT/normalized" "$expected" || {
      printf 'Script response disagrees with synthetic fixture: %s\n' "$name" >&2
      return 1
    }
  fi
}

test_c4_response_fixtures() {
  owned_config
  run_script plan --endpoint vpn.example --force
  assert_success
  check_contract_fixture plan
  run_script install --endpoint vpn.example --force
  assert_success
  check_contract_fixture install
  run_script add-peer --name phone --allowed-ips '0.0.0.0/0'
  assert_success
  check_contract_fixture add-peer
  run_script sync-status
  assert_success
  check_contract_fixture status-running
  run_script remove-peer --name phone
  assert_success
  check_contract_fixture remove-peer
  run_script remove-peer --name phone
  assert_failure peer_not_found
  check_contract_fixture error
  run_script add-peer --name tablet --allowed-ips '0.0.0.0/0'
  assert_success
  run_script revoke-peer --name tablet
  assert_success
  check_contract_fixture revoke-peer
  run_script backup
  assert_success
  check_contract_fixture backup
  run_script uninstall --confirm
  assert_success
  check_contract_fixture uninstall
}

test_c12_status_serializes_with_existing_interface_lock() {
  owned_config
  export TEST_REAL_FLOCK
  TEST_REAL_FLOCK=$(command -v flock)
  exec {writer_lock}>"$WG_LOCK_FILE"
  "$TEST_REAL_FLOCK" "$writer_lock"
  mkfifo "$TEST_ROOT/lock-entered"
  exec {lock_signal}<>"$TEST_ROOT/lock-entered"
  export TEST_LOCK_SIGNAL="$TEST_ROOT/lock-entered"
  cat > "$TEST_ROOT/bin/flock" <<'EOF'
#!/usr/bin/env bash
printf 'ready\n' > "$TEST_LOCK_SIGNAL"
exec "$TEST_REAL_FLOCK" "$@"
EOF
  chmod 700 "$TEST_ROOT/bin/flock"
  bash "$SCRIPTS/sync-status.sh" > "$TEST_ROOT/status" 2> "$TEST_ROOT/status.err" &
  status_pid=$!
  if ! read -r -t 5 ready <&"$lock_signal"; then
    kill "$status_pid" 2>/dev/null || true
    wait "$status_pid" 2>/dev/null || true
    return 1
  fi
  [[ ! -s "$TEST_ROOT/status" ]]
  sed -i 's/# ENDPOINT vpn.example/# ENDPOINT moved.example/' "$WG_CONF"
  /bin/cp "$WG_CONF" "$TEST_STATE/runtime.conf"
  "$TEST_REAL_FLOCK" -u "$writer_lock"
  wait "$status_pid"
  RESULT_CODE=0 RESPONSE=$(cat "$TEST_ROOT/status")
  assert_success
  [[ "$RESPONSE" == *'"endpoint":"moved.example"'* && "$RESPONSE" == *'"rxBytes":"1024"'* ]]
  assert_no_mutations
}

test_c12_status_refuses_unowned_or_ambiguous_config() {
  owned_config
  sed -i '/# TUNNL_MANAGED 1/d' "$WG_CONF"
  run_script sync-status
  assert_failure unmanaged_config
  assert_no_mutations
  owned_config
  printf '# BEGIN_PEER broken\n' >> "$WG_CONF"
  run_script sync-status
  assert_failure invalid_config
  assert_no_mutations
}

test_c4_native_observation() {
  owned_config
  printf '*' > "$TEST_STATE/fail-systemctl-is-active"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"running":true'* && "$RESPONSE" == *'"rxBytes":"1024"'* ]]
  assert_no_mutations
}

test_c4_missing_counters() {
  owned_config
  printf 'synthetic-header\n' > "$TEST_STATE/dump"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"running":true'* && "$RESPONSE" == *'"rxBytes":null'* ]]
  assert_no_mutations
}

test_c4_invalid_counters() {
  owned_config
  public=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
  printf '%s\t(none)\t(none)\t10.8.0.2/32\tsecret-counter\t9223372036854775808\t-1\t25\n' "$public" > "$TEST_STATE/dump"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"latestHandshake":null,"rxBytes":null,"txBytes":null'* ]]
  [[ "$RESPONSE" != *secret-counter* ]]
  assert_no_mutations
}

test_c4_failed_dump_discards_stdout() {
  owned_config
  public=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
  printf '%s\t(none)\t(none)\t10.8.0.2/32\t1710000000\t1024\t2048\t25\n' "$public" > "$TEST_STATE/dump"
  touch "$TEST_STATE/dump-failed-output"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"running":true'* && "$RESPONSE" == *'"latestHandshake":null,"rxBytes":null,"txBytes":null'* ]]
  ! grep -q 'synthetic-secret-tool-diagnostic' "$TEST_ROOT/response" "$TEST_ROOT/stderr"
  assert_no_mutations
  check_contract_fixture status-running-unknown
}

test_c4_force_plan() {
  owned_config
  run_script plan --endpoint vpn.example --force
  assert_success
  [[ "$RESPONSE" == *'"willOverwriteConfig":true'* ]]
  assert_no_mutations
}

test_c4_remove_schema() {
  owned_config
  run_script remove-peer --name existing
  assert_success
  [[ "$RESPONSE" == *'"name":"existing"'* && "$RESPONSE" == *'"removed":true'* && "$RESPONSE" == *'"revoked":false'* ]]
}

test_c4_revoke_schema() {
  owned_config
  run_script revoke-peer --name existing
  assert_success
  [[ "$RESPONSE" == *'"name":"existing"'* && "$RESPONSE" == *'"removed":true'* && "$RESPONSE" == *'"revoked":true'* ]]
}

test_c4_unknown_observation() {
  owned_config
  printf '*' > "$TEST_STATE/fail-wg-show"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"running":null'* && "$RESPONSE" == *'"rxBytes":null'* ]]
  check_contract_fixture status-unknown
  assert_no_mutations
}

test_c4_stopped_observation() {
  owned_config
  /bin/rm "$TEST_STATE/runtime.conf"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"running":false'* && "$RESPONSE" == *'"latestHandshake":null'* ]]
  check_contract_fixture status-stopped
  assert_no_mutations
}

test_backup_complete_snapshot() {
  owned_config
  mkdir -p "$TUNNL_DIR/revoked"
  printf '# quoted "comment" \\ path\t\r\n# UTF-8: café\n\n' >> "$WG_CONF"
  for name in alice.conf alice.conf.AAAAAA alice.conf.BBBBBB; do
    printf '# BEGIN_PEER alice\n[Peer]\nPresharedKey = synthetic-private-psk\n# END_PEER alice\n\n' > "$TUNNL_DIR/revoked/$name"
  done
  run_script backup
  assert_success
  python3 - "$WG_CONF" "$TUNNL_DIR/revoked" "$TEST_ROOT/response" <<'PY'
import datetime
import json
import pathlib
import sys
config, revoked, response = map(pathlib.Path, sys.argv[1:])
snapshot = json.loads(response.read_text())
assert snapshot['snapshotVersion'] == 1
datetime.datetime.strptime(snapshot['capturedAtUtc'], '%Y-%m-%dT%H:%M:%SZ')
assert snapshot['interface'] == 'wg0'
assert snapshot['config'].encode() == config.read_bytes()
assert snapshot['revokedCount'] == '3'
assert {entry['archiveName']: entry['config'].encode() for entry in snapshot['revoked']} == {
    file.name: file.read_bytes() for file in revoked.iterdir()
}
PY
  assert_no_mutations
}

test_backup_json_controls() {
  owned_config
  for ((code=1; code<32; code++)); do
    printf '%b' "\\$(printf '%03o' "$code")" >> "$WG_CONF"
  done
  run_script backup
  assert_success
  python3 - "$WG_CONF" "$TEST_ROOT/response" <<'PY'
import json
import pathlib
import sys
assert json.loads(pathlib.Path(sys.argv[2]).read_text())['config'].encode() == pathlib.Path(sys.argv[1]).read_bytes()
PY
  assert_no_mutations
}

test_backup_empty_revoked() {
  owned_config
  for state in absent directory; do
    [[ "$state" != directory ]] || mkdir -p "$TUNNL_DIR/revoked"
    run_script backup
    assert_success
    [[ "$RESPONSE" == *'"revokedCount":"0"'* && "$RESPONSE" == *'"revoked":[]'* ]]
  done
  assert_no_mutations
}

test_backup_ownership() {
  printf '[Interface]\nPrivateKey = synthetic-secret\n' > "$WG_CONF"
  run_script backup
  assert_failure unmanaged_config
  /bin/mv "$WG_CONF" "$TEST_ROOT/external.conf"
  ln -s "$TEST_ROOT/external.conf" "$WG_CONF"
  run_script backup
  assert_failure unmanaged_config
  assert_no_mutations
}

test_backup_rejects_unsafe_revoked_entries() {
  owned_config
  mkdir -p "$TUNNL_DIR/revoked"
  for kind in symlink directory fifo hidden unsupported newline; do
    case "$kind" in
      symlink) ln -s "$WG_CONF" "$TUNNL_DIR/revoked/alice.conf" ;;
      directory) mkdir "$TUNNL_DIR/revoked/alice.conf" ;;
      fifo) mkfifo "$TUNNL_DIR/revoked/alice.conf" ;;
      hidden) printf x > "$TUNNL_DIR/revoked/.hidden" ;;
      unsupported) printf x > "$TUNNL_DIR/revoked/alice.conf.longer-suffix" ;;
      newline) printf x > "$TUNNL_DIR/revoked/"$'alice.conf\nbob.conf' ;;
    esac
    run_script backup
    if [[ "$kind" == hidden || "$kind" == unsupported || "$kind" == newline ]]; then assert_failure invalid_config; else assert_failure unsafe_path; fi
    /bin/rm -rf -- "$TUNNL_DIR/revoked"
    mkdir "$TUNNL_DIR/revoked"
  done
  /bin/rm -rf -- "$TUNNL_DIR/revoked"
  ln -s "$TEST_ROOT" "$TUNNL_DIR/revoked"
  run_script backup
  assert_failure unsafe_path
  assert_no_mutations
}

test_backup_rejects_binary_and_invalid_utf8() {
  owned_config
  printf '\000synthetic-secret' >> "$WG_CONF"
  run_script backup
  assert_failure invalid_config
  owned_config
  printf '\377' >> "$WG_CONF"
  run_script backup
  assert_failure invalid_config
  assert_no_mutations
}

test_backup_limits() {
  owned_config
  head -c 262145 /dev/zero | tr '\000' x >> "$WG_CONF"
  run_script backup
  assert_failure backup_limit
  owned_config
  mkdir -p "$TUNNL_DIR/revoked"
  head -c 16385 /dev/zero | tr '\000' x > "$TUNNL_DIR/revoked/alice.conf"
  run_script backup
  assert_failure backup_limit
  for ((number=0; number<1025; number++)); do printf x > "$TUNNL_DIR/revoked/peer$number.conf"; done
  run_script backup
  assert_failure backup_limit
  assert_no_mutations
}

test_backup_encoded_response_limit() {
  owned_config
  mkdir -p "$TUNNL_DIR/revoked"
  for ((number=0; number<8; number++)); do
    head -c 16384 /dev/zero | tr '\000' '\001' > "$TUNNL_DIR/revoked/peer$number.conf"
  done
  run_script backup
  assert_failure backup_limit
  [[ "$RESPONSE" != *'"ok":true'* && ${#RESPONSE} -lt 1024 ]]
  assert_no_mutations
}

test_backup_late_read_failure_emits_no_partial_snapshot() {
  owned_config
  mkdir -p "$TUNNL_DIR/revoked"
  printf synthetic-private-psk > "$TUNNL_DIR/revoked/alice.conf"
  cat > "$TEST_ROOT/bin/cat" <<'SH'
#!/usr/bin/env bash
if [[ "${*: -1}" == */revoked/* ]]; then printf synthetic-secret-read-error >&2; exit 1; fi
exec /bin/cat "$@"
SH
  chmod 700 "$TEST_ROOT/bin/cat"
  run_script backup
  assert_failure backup_failed
  [[ "$RESPONSE" != *'"ok":true'* && "$RESPONSE" != *synthetic-private-psk* ]]
  ! grep -q synthetic-secret-read-error "$TEST_ROOT/stderr"
  assert_no_mutations
}

test_backup_failed_revoked_enumeration() {
  owned_config
  mkdir -p "$TUNNL_DIR/revoked"
  printf synthetic-private-psk > "$TUNNL_DIR/revoked/alice.conf"
  cat > "$TEST_ROOT/bin/find" <<'SH'
#!/usr/bin/env bash
[[ ! -f "$TEST_STATE/partial-enumeration" ]] || printf '%s\0' "$TUNNL_DIR/revoked/alice.conf"
printf synthetic-secret-enumeration-error >&2
exit 1
SH
  chmod 700 "$TEST_ROOT/bin/find"
  for state in empty partial; do
    [[ "$state" != partial ]] || touch "$TEST_STATE/partial-enumeration"
    run_script backup
    assert_failure backup_failed
    [[ "$RESPONSE" != *'"ok":true'* && "$RESPONSE" != *synthetic-private-psk* ]]
    ! grep -q synthetic-secret-enumeration-error "$TEST_ROOT/stderr"
  done
  assert_no_mutations
}

test_backup_waits_and_holds_interface_lock() {
  owned_config
  mkfifo "$TEST_ROOT/backup-lock-entered"
  cat > "$TEST_ROOT/bin/flock" <<'SH'
#!/usr/bin/env bash
printf entered > "$TEST_ROOT/backup-lock-entered"
exec /usr/bin/flock "$@"
SH
  cat > "$TEST_ROOT/bin/cat" <<'SH'
#!/usr/bin/env bash
if [[ "${*: -1}" == "$WG_CONF" || "${*: -1}" == "$TUNNL_DIR"/revoked/* ]]; then
  if /usr/bin/flock -n "$WG_LOCK_FILE" true; then exit 1; fi
fi
exec /bin/cat "$@"
SH
  chmod 700 "$TEST_ROOT/bin/flock" "$TEST_ROOT/bin/cat"
  export TEST_ROOT
  exec {held_lock}>"$WG_LOCK_FILE"
  /usr/bin/flock "$held_lock"
  bash "$SCRIPTS/backup.sh" > "$TEST_ROOT/response" 2> "$TEST_ROOT/stderr" &
  backup_pid=$!
  timeout 5 /bin/cat "$TEST_ROOT/backup-lock-entered" >/dev/null
  [[ ! -s "$TEST_ROOT/response" ]]
  mkdir -p "$TUNNL_DIR/revoked"
  printf '# archived old peer\n' > "$TUNNL_DIR/revoked/existing.conf"
  sed -i '/# BEGIN_PEER existing/,/# END_PEER existing/d' "$WG_CONF"
  /usr/bin/flock -u "$held_lock"
  exec {held_lock}>&-
  wait "$backup_pid"
  python3 - "$WG_CONF" "$TEST_ROOT/response" <<'PY'
import json
import pathlib
import sys
snapshot = json.loads(pathlib.Path(sys.argv[2]).read_text())
assert snapshot['config'].encode() == pathlib.Path(sys.argv[1]).read_bytes()
assert '# BEGIN_PEER existing' not in snapshot['config']
assert snapshot['revoked'] == [{'archiveName': 'existing.conf', 'config': '# archived old peer\n'}]
PY
  assert_no_mutations
}

if [[ ${1-} == --case ]]; then
  setup
  "$2"
  exit
fi

tests=(
  test_backup_complete_snapshot test_backup_json_controls test_backup_empty_revoked
  test_backup_ownership test_backup_rejects_unsafe_revoked_entries test_backup_rejects_binary_and_invalid_utf8
  test_backup_limits test_backup_encoded_response_limit test_backup_late_read_failure_emits_no_partial_snapshot
  test_backup_failed_revoked_enumeration test_backup_waits_and_holds_interface_lock
  test_c12_status_serializes_with_existing_interface_lock test_c12_status_refuses_unowned_or_ambiguous_config
  test_c4_force_plan test_c4_remove_schema test_c4_revoke_schema
  test_c4_unknown_observation test_c4_stopped_observation
  test_c4_response_fixtures test_c4_native_observation test_c4_missing_counters test_c4_invalid_counters
  test_c4_failed_dump_discards_stdout
  test_c2_unmanaged_uninstall test_c2_unmanaged_force_install test_c2_symlink_config
  test_c2_unmanaged_peer_mutations test_c2_endpoint_injection test_c2_dns_injection
  test_c2_peer_input_injection test_c2_missing_values test_c2_foreign_forwarding_file
  test_c2_valid_plan test_c2_valid_install test_c2_valid_add
  test_c2_shared_package_config test_c2_shared_package_runtime
  test_c3_concurrent_adds test_c3_concurrent_add_remove test_c3_strip_failure
  test_c3_candidate_copy_failure test_c3_atomic_rename_failure test_c3_apply_failure_retry
  test_c3_rollback_failure_retains_snapshot test_c3_ignored_apply test_c3_restart_fallback
  test_c3_stopped_peer_mutation test_c3_runtime_unknown test_c3_revoke_effective_and_private
  test_c3_revoke_apply_failure test_c3_revoke_archive_failure test_c3_force_runtime_reconciled
  test_c3_force_restart_failure test_c3_force_ignored_restart test_c3_new_install_failure
  test_c3_uninstall_stop_failure test_c3_uninstall_disable_failure test_c3_uninstall_ignored_stop
  test_c3_uninstall_delete_failure test_c3_uninstall_purge_failure test_c3_uninstall_partial_purge
  test_c3_uninstall_ignored_purge test_c3_uninstall_success_keeps_global_forwarding
  test_c3_uninstall_preserves_other_config test_c3_signal_recovery
  test_c3_revoke_cleanup_failure test_c3_restore_disk_failure test_c3_unique_backup_and_keep_packages
  test_c3_install_absent_template test_c3_install_unknown_service_bus
  test_c3_wrong_psk test_c3_residual_package_config test_c3_shared_host_lock test_c3_malformed_peer_markers
  test_c3_force_postcondition_rollback_hooks test_c3_new_postcondition_rollback_hooks
  test_c3_missing_wg_existing_interface test_c3_missing_wg_clean_install
  test_fixture_legacy_file_commands
  test_c3_wrong_server_key test_c3_wrong_listen_port test_c3_wrong_runtime_routes
)
passed=0
failed=0
for test in "${tests[@]}"; do
  if bash "$TEST_SCRIPT" --case "$test"; then
    printf 'PASS %s\n' "$test"
    passed=$((passed + 1))
  else
    printf 'FAIL %s\n' "$test"
    failed=$((failed + 1))
  fi
done
printf 'RESULT %s passed; %s failed; no host root/network commands.\n' "$passed" "$failed"
[[ "$failed" -eq 0 ]]
