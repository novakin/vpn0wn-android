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
  for tool in wg wg-quick systemctl apt-get dpkg-query sysctl ip iptables ss cp mv rm; do
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

restore_source() {
  owned_config
  RESTORE_INPUT="$TEST_ROOT/input"
  mkdir -p "$RESTORE_INPUT/revoked"
  /bin/mv "$WG_CONF" "$RESTORE_INPUT/server.conf"
  /bin/rm -f "$TEST_STATE/runtime.conf" "$TEST_STATE/enabled" "$TEST_STATE/address" "$TEST_STATE/nat" "$FORWARD_CONF"
  RESTORE_PUBLIC_KEY=$(printf '%-32.32s' PUBLIC-old | base64 -w0)
}

restore_peer() {
  local name=$1 key=$2 address=$3 psk=${4-}
  printf '# BEGIN_PEER %s\n[Peer]\nPublicKey = %s\n' "$name" "$(printf '%-32.32s' "PUBLIC-$key" | base64 -w0)"
  [[ -z "$psk" ]] || printf 'PresharedKey = %s\n' "$(printf '%-32.32s' "PSK-$psk" | base64 -w0)"
  printf 'AllowedIPs = %s/32\n# END_PEER %s\n' "$address" "$name"
}

run_restore() {
  run_script restore --input "$RESTORE_INPUT" --endpoint restored.example --port 51900 \
    --expected-server-public-key "$RESTORE_PUBLIC_KEY" --confirm "$@"
}

assert_fresh_restore_rolled_back() {
  [[ ! -e "$WG_CONF" && ! -e "$FORWARD_CONF" && ! -e "$TEST_STATE/runtime.conf" && ! -e "$TEST_STATE/enabled" && ! -e "$TEST_STATE/nat" ]]
  [[ "$RESPONSE" == *'"recovery":"restored"'* ]]
  [[ -z $(find "$WG_DIR" -maxdepth 1 -type d -name '.tunnl-recovery.*') ]]
  [[ $(cat "$TEST_STATE/forward-value") == 0 ]]
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
  ! grep -Eq '^(apt-get|cp|mv|rm) |^sysctl -w |^systemctl (enable|disable|stop|start|restart) |^wg (genkey|genpsk|syncconf|set) ' "$TEST_STATE/trace"
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

test_c143_guarded_add_success_running_and_stopped() {
  local state key
  key=$(printf '%-32.32s' PUBLIC-old | base64 -w0)
  for state in running stopped; do
    owned_config
    if [[ "$state" == stopped ]]; then /bin/rm "$TEST_STATE/runtime.conf" "$TEST_STATE/enabled"; fi
    run_script add-peer --name laptop --expected-server-public-key "$key"
    assert_success
    grep -qxF '# BEGIN_PEER laptop' "$WG_CONF"
    [[ "$RESPONSE" == *"PublicKey = $key"* ]]
    if [[ "$state" == stopped ]]; then
      [[ ! -e "$TEST_STATE/runtime.conf" && ! -e "$TEST_STATE/enabled" ]]
    else
      grep -qxF 'AllowedIPs = 10.8.0.3/32' "$TEST_STATE/runtime.conf"
    fi
  done
}

test_c143_add_rejects_invalid_server_keys_before_mutation() {
  local key
  owned_config
  for key in '' ' ' 'not-a-key' "$(printf 'A%.0s' {1..42})B=" $'key\n[Peer]'; do
    run_script add-peer --name laptop --expected-server-public-key "$key"
    assert_failure invalid_public_key
    assert_restored
    assert_no_mutations
  done
  run_script add-peer --name laptop --expected-server-public-key
  assert_failure missing_arg
  assert_no_mutations
}

test_c143_add_rejects_configured_and_live_server_key_drift() {
  local key replacement_private replacement_public
  key=$(printf '%-32.32s' PUBLIC-old | base64 -w0)
  replacement_private=$(printf '%-32.32s' PRIVATE-replacement | base64 -w0)
  replacement_public=$(printf '%-32.32s' PUBLIC-replacement | base64 -w0)
  owned_config
  run_script add-peer --name laptop --expected-server-public-key "$replacement_public"
  assert_failure server_identity_mismatch
  assert_restored
  assert_no_mutations
  [[ -z $(find "$WG_DIR" -name '.tunnl-recovery.*') ]]
  sed -i "s|^PrivateKey = .*|PrivateKey = $replacement_private|" "$TEST_STATE/runtime.conf"
  /bin/cp "$TEST_STATE/runtime.conf" "$TEST_ROOT/before.runtime"
  run_script add-peer --name laptop --expected-server-public-key "$key"
  assert_failure server_identity_mismatch
  cmp -s "$WG_CONF" "$TEST_ROOT/before.conf"
  cmp -s "$TEST_STATE/runtime.conf" "$TEST_ROOT/before.runtime"
  assert_no_mutations
  [[ -z $(find "$WG_DIR" -name '.tunnl-recovery.*') ]]
}

test_c143_add_checks_server_identity_after_concurrent_owner() {
  local key replacement writer_lock mutation_pid ready
  owned_config
  key=$(printf '%-32.32s' PUBLIC-old | base64 -w0)
  replacement=$(printf '%-32.32s' PRIVATE-replacement | base64 -w0)
  export TEST_REAL_FLOCK TEST_LOCK_SIGNAL
  TEST_REAL_FLOCK=$(command -v flock)
  TEST_LOCK_SIGNAL="$TEST_ROOT/lock-entered"
  mkfifo "$TEST_LOCK_SIGNAL"
  exec {lock_signal}<>"$TEST_LOCK_SIGNAL"
  cat > "$TEST_ROOT/bin/flock" <<'EOF'
#!/usr/bin/env bash
printf 'ready\n' > "$TEST_LOCK_SIGNAL"
exec "$TEST_REAL_FLOCK" "$@"
EOF
  chmod 700 "$TEST_ROOT/bin/flock"
  exec {writer_lock}>"$WG_LOCK_FILE"
  "$TEST_REAL_FLOCK" "$writer_lock"
  bash "$SCRIPTS/add-peer.sh" --name laptop --expected-server-public-key "$key" > "$TEST_ROOT/response" 2> "$TEST_ROOT/stderr" &
  mutation_pid=$!
  if ! read -r -t 5 ready <&"$lock_signal"; then
    "$TEST_REAL_FLOCK" -u "$writer_lock"
    kill "$mutation_pid" 2>/dev/null || true
    wait "$mutation_pid" 2>/dev/null || true
    return 1
  fi
  [[ ! -s "$TEST_ROOT/response" ]]
  sed -i "s|^PrivateKey = .*|PrivateKey = $replacement|" "$WG_CONF"
  /bin/cp "$WG_CONF" "$TEST_STATE/runtime.conf"
  /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
  "$TEST_REAL_FLOCK" -u "$writer_lock"
  wait "$mutation_pid" && RESULT_CODE=0 || RESULT_CODE=$?
  RESPONSE=$(cat "$TEST_ROOT/response")
  assert_failure server_identity_mismatch
  assert_restored
  assert_no_mutations
  [[ -z $(find "$WG_DIR" -name '.tunnl-recovery.*') ]]
}

test_c143_add_and_status_suppress_failed_key_diagnostics() {
  local key
  owned_config
  key=$(printf '%-32.32s' PUBLIC-old | base64 -w0)
  printf '*' > "$TEST_STATE/fail-wg-pubkey"
  run_script add-peer --name laptop --expected-server-public-key "$key"
  assert_failure key_failed
  assert_restored
  assert_no_mutations
  [[ -z $(find "$WG_DIR" -name '.tunnl-recovery.*') ]]
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"serverPublicKey":null'* ]]
  ! grep -q 'synthetic-secret-tool-diagnostic' "$TEST_ROOT/response" "$TEST_ROOT/stderr"
  assert_no_mutations
}

test_c143_add_refuses_unobservable_live_identity_before_mutation() {
  local key
  owned_config
  key=$(printf '%-32.32s' PUBLIC-old | base64 -w0)
  printf '2' > "$TEST_STATE/fail-wg-show"
  run_script add-peer --name laptop --expected-server-public-key "$key"
  assert_failure runtime_unknown
  assert_restored
  assert_no_mutations
  [[ -z $(find "$WG_DIR" -name '.tunnl-recovery.*') ]]
}

test_c143_status_withholds_drifted_or_unobservable_live_identity() {
  local replacement
  owned_config
  replacement=$(printf '%-32.32s' PRIVATE-replacement | base64 -w0)
  sed -i "s|^PrivateKey = .*|PrivateKey = $replacement|" "$TEST_STATE/runtime.conf"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"running":true'* && "$RESPONSE" == *'"serverPublicKey":null'* ]]
  assert_no_mutations
  printf '0' > "$TEST_STATE/count-wg-show"
  printf '2' > "$TEST_STATE/fail-wg-show"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"running":true'* && "$RESPONSE" == *'"serverPublicKey":null'* ]]
  ! grep -q 'synthetic-secret-tool-diagnostic' "$TEST_ROOT/response" "$TEST_ROOT/stderr"
  assert_no_mutations
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

test_c12_guarded_mutation_success_running_and_stopped() {
  local operation state key
  for operation in remove-peer revoke-peer; do
    for state in running stopped; do
      owned_config
      key=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
      if [[ "$state" == stopped ]]; then /bin/rm "$TEST_STATE/runtime.conf" "$TEST_STATE/enabled"; fi
      run_script "$operation" --name existing --expected-public-key "$key"
      assert_success
      [[ "$RESPONSE" == *"\"publicKey\":\"$key\""* ]]
      ! grep -q '^# BEGIN_PEER existing$' "$WG_CONF"
      if [[ "$state" == stopped ]]; then
        [[ ! -e "$TEST_STATE/runtime.conf" && ! -e "$TEST_STATE/enabled" ]]
      else
        [[ -z $(awk '$1 == "PublicKey" {print $3}' "$TEST_STATE/runtime.conf") ]]
      fi
    done
  done
}

test_c12_guard_rejects_invalid_keys_before_mutation() {
  local operation key
  owned_config
  for operation in remove-peer revoke-peer; do
    for key in '' ' ' 'not-a-key' "$(printf 'A%.0s' {1..42})B=" "$(printf 'A%.0s' {1..44})=" $'key\n[Peer]'; do
      run_script "$operation" --name existing --expected-public-key "$key"
      assert_failure invalid_public_key
      assert_restored
      assert_no_mutations
    done
    run_script "$operation" --name existing --expected-public-key
    assert_failure missing_arg
    assert_no_mutations
  done
}

test_c12_guard_rejects_mismatch_and_reused_name() {
  local operation key replacement
  replacement=$(printf '%-32.32s' PUBLIC-replacement | base64 -w0)
  for operation in remove-peer revoke-peer; do
    owned_config
    key=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
    run_script "$operation" --name existing --expected-public-key "$replacement"
    assert_failure peer_identity_mismatch
    assert_restored
    assert_no_mutations
    [[ -z $(find "$WG_DIR" -name '.tunnl-recovery.*') ]]
    run_script remove-peer --name existing --expected-public-key "$key"
    assert_success
    run_script add-peer --name existing
    assert_success
    /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
    : > "$TEST_STATE/trace"
    run_script "$operation" --name existing --expected-public-key "$key"
    assert_failure peer_identity_mismatch
    assert_restored
    assert_no_mutations
  done
}

test_c12_guard_checks_identity_after_concurrent_owner() {
  local operation key replacement writer_lock mutation_pid ready
  replacement=$(printf '%-32.32s' PUBLIC-replacement | base64 -w0)
  export TEST_REAL_FLOCK TEST_LOCK_SIGNAL
  TEST_REAL_FLOCK=$(command -v flock)
  TEST_LOCK_SIGNAL="$TEST_ROOT/lock-entered"
  mkfifo "$TEST_LOCK_SIGNAL"
  exec {lock_signal}<>"$TEST_LOCK_SIGNAL"
  cat > "$TEST_ROOT/bin/flock" <<'EOF'
#!/usr/bin/env bash
printf 'ready\n' > "$TEST_LOCK_SIGNAL"
exec "$TEST_REAL_FLOCK" "$@"
EOF
  chmod 700 "$TEST_ROOT/bin/flock"
  for operation in remove-peer revoke-peer; do
    owned_config
    : > "$TEST_STATE/trace"
    key=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
    exec {writer_lock}>"$WG_LOCK_FILE"
    "$TEST_REAL_FLOCK" "$writer_lock"
    bash "$SCRIPTS/$operation.sh" --name existing --expected-public-key "$key" > "$TEST_ROOT/response" 2> "$TEST_ROOT/stderr" &
    mutation_pid=$!
    if ! read -r -t 5 ready <&"$lock_signal"; then
      "$TEST_REAL_FLOCK" -u "$writer_lock"
      kill "$mutation_pid" 2>/dev/null || true
      wait "$mutation_pid" 2>/dev/null || true
      return 1
    fi
    [[ ! -s "$TEST_ROOT/response" ]]
    sed -i "s|$key|$replacement|" "$WG_CONF"
    /bin/cp "$WG_CONF" "$TEST_STATE/runtime.conf"
    /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
    "$TEST_REAL_FLOCK" -u "$writer_lock"
    wait "$mutation_pid" && RESULT_CODE=0 || RESULT_CODE=$?
    RESPONSE=$(cat "$TEST_ROOT/response")
    assert_failure peer_identity_mismatch
    assert_restored
    assert_no_mutations
    [[ -z $(find "$WG_DIR" -name '.tunnl-recovery.*') ]]
    exec {writer_lock}>&-
  done
}

test_c12_guard_never_reads_identity_from_following_peer() {
  local operation key following_key
  following_key=$(printf '%-32.32s' PUBLIC-following | base64 -w0)
  for operation in remove-peer revoke-peer; do
    owned_config
    key=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
    sed -i 's/^PublicKey = /PublicKey  = /' "$WG_CONF"
    cat >> "$WG_CONF" <<EOF
# BEGIN_PEER following
[Peer]
PublicKey = $following_key
AllowedIPs = 10.8.0.3/32
# END_PEER following
EOF
    /bin/cp "$WG_CONF" "$TEST_STATE/runtime.conf"
    /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
    : > "$TEST_STATE/trace"
    run_script "$operation" --name existing --expected-public-key "$following_key"
    assert_failure peer_identity_mismatch
    assert_restored
    assert_no_mutations
    run_script "$operation" --name existing --expected-public-key "$key"
    assert_success
    [[ "$RESPONSE" == *"\"publicKey\":\"$key\""* ]]
    ! grep -q '^# BEGIN_PEER existing$' "$WG_CONF"
    grep -qxF '# BEGIN_PEER following' "$WG_CONF"
    [[ $(awk '$1 == "PublicKey" {print $3}' "$TEST_STATE/runtime.conf") == "$following_key" ]]
  done
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

test_c143_runtime_inventory_observes_live_only_peers() {
  local existing extra
  owned_config
  existing=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
  extra=$(printf '%-32.32s' PUBLIC-live-only | base64 -w0)
  printf '\n[Peer]\nPublicKey = %s\nAllowedIPs = 10.8.0.99/32\n' "$extra" >> "$TEST_STATE/runtime.conf"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *"\"runtimePeerPublicKeys\":[\"$existing\",\"$extra\"]"* ]]
  [[ "$RESPONSE" != *'"name":"live-only"'* ]]
  assert_no_mutations
}

test_c143_configured_inventory_covers_unmarked_running_and_stopped_peers() {
  local running key extra
  for running in true false; do
    owned_config
    key=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
    extra=$(printf '%-32.32s' PUBLIC-unmarked | base64 -w0)
    sed '/^# BEGIN_PEER /d; /^# END_PEER /d' "$WG_CONF" > "$TEST_ROOT/unmarked.conf"
    /bin/mv "$TEST_ROOT/unmarked.conf" "$WG_CONF"
    printf '\n[Peer]\nPublicKey=%s\nAllowedIPs = 10.8.0.99/32\n' "$extra" >> "$WG_CONF"
    [[ "$running" == true ]] || /bin/rm "$TEST_STATE/runtime.conf"
    run_script sync-status
    assert_success
    [[ "$RESPONSE" == *"\"configuredPeerPublicKeys\":[\"$key\",\"$extra\"]"* ]]
    [[ "$RESPONSE" == *'"peers":[]'* && "$RESPONSE" == *"\"running\":$running"* ]]
    assert_no_mutations
  done
}

test_c143_invalid_configured_inventory_stays_unknown() {
  local peer
  for peer in '[Peer]' $'[Peer]\nPublicKey = malformed-key' $'[Peer]\nPublicKey = \nAllowedIPs = 10.8.0.99/32'; do
    owned_config
    printf '\n%s\n' "$peer" >> "$WG_CONF"
    run_script sync-status
    assert_success
    [[ "$RESPONSE" == *'"configuredPeerPublicKeys":null'* ]]
    assert_no_mutations
  done
}

test_c143_duplicate_runtime_inventory_stays_unknown() {
  local key
  owned_config
  key=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
  printf '\n[Peer]\nPublicKey = %s\nAllowedIPs = 10.8.0.99/32\n' "$key" >> "$TEST_STATE/runtime.conf"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"runtimePeerPublicKeys":null'* ]]
  assert_no_mutations
}

test_c143_failed_runtime_inventory_discards_partial_stdout() {
  owned_config
  touch "$TEST_STATE/peers-failed-output"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"running":true'* && "$RESPONSE" == *'"runtimePeerPublicKeys":null'* ]]
  ! grep -q 'synthetic-secret-tool-diagnostic' "$TEST_ROOT/response" "$TEST_ROOT/stderr"
  assert_no_mutations
}

test_c143_malformed_runtime_inventory_stays_unknown() {
  owned_config
  printf '\n[Peer]\nPublicKey = synthetic-invalid-native-key\nAllowedIPs = 10.8.0.99/32\n' >> "$TEST_STATE/runtime.conf"
  run_script sync-status
  assert_success
  [[ "$RESPONSE" == *'"runtimePeerPublicKeys":null'* && "$RESPONSE" != *synthetic-invalid-native-key* ]]
  assert_no_mutations
}

test_c143_remove_checks_server_identity_after_concurrent_owner() {
  local key replacement writer_lock mutation_pid ready
  owned_config
  local peer_key
  peer_key=$(awk '$1 == "PublicKey" {print $3}' "$WG_CONF")
  key=$(printf '%-32.32s' PUBLIC-old | base64 -w0)
  replacement=$(printf '%-32.32s' PRIVATE-replacement | base64 -w0)
  export TEST_REAL_FLOCK TEST_LOCK_SIGNAL
  TEST_REAL_FLOCK=$(command -v flock)
  TEST_LOCK_SIGNAL="$TEST_ROOT/lock-entered"
  mkfifo "$TEST_LOCK_SIGNAL"
  exec {lock_signal}<>"$TEST_LOCK_SIGNAL"
  cat > "$TEST_ROOT/bin/flock" <<'EOF'
#!/usr/bin/env bash
printf 'ready\n' > "$TEST_LOCK_SIGNAL"
exec "$TEST_REAL_FLOCK" "$@"
EOF
  chmod 700 "$TEST_ROOT/bin/flock"
  exec {writer_lock}>"$WG_LOCK_FILE"
  "$TEST_REAL_FLOCK" "$writer_lock"
  bash "$SCRIPTS/remove-peer.sh" --name existing --expected-public-key "$peer_key" --expected-server-public-key "$key" > "$TEST_ROOT/response" 2> "$TEST_ROOT/stderr" &
  mutation_pid=$!
  if ! read -r -t 5 ready <&"$lock_signal"; then
    "$TEST_REAL_FLOCK" -u "$writer_lock"
    kill "$mutation_pid" 2>/dev/null || true
    wait "$mutation_pid" 2>/dev/null || true
    return 1
  fi
  [[ ! -s "$TEST_ROOT/response" ]]
  sed -i "s|^PrivateKey = .*|PrivateKey = $replacement|" "$WG_CONF"
  /bin/cp "$WG_CONF" "$TEST_STATE/runtime.conf"
  /bin/cp "$WG_CONF" "$TEST_ROOT/before.conf"
  "$TEST_REAL_FLOCK" -u "$writer_lock"
  wait "$mutation_pid" && RESULT_CODE=0 || RESULT_CODE=$?
  RESPONSE=$(cat "$TEST_ROOT/response")
  assert_failure server_identity_mismatch
  assert_restored
  assert_no_mutations
  [[ -z $(find "$WG_DIR" -name '.tunnl-recovery.*') ]]
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
  local replacement_private replacement_public
  owned_config
  replacement_private=$(printf '%-32.32s' PRIVATE-replacement | base64 -w0)
  replacement_public=$(printf '%-32.32s' PUBLIC-replacement | base64 -w0)
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
  sed -i "s|^PrivateKey = .*|PrivateKey = $replacement_private|" "$WG_CONF"
  /bin/cp "$WG_CONF" "$TEST_STATE/runtime.conf"
  "$TEST_REAL_FLOCK" -u "$writer_lock"
  wait "$status_pid"
  RESULT_CODE=0 RESPONSE=$(cat "$TEST_ROOT/status")
  assert_success
  [[ "$RESPONSE" == *'"endpoint":"moved.example"'* && "$RESPONSE" == *'"rxBytes":"1024"'* && "$RESPONSE" == *"\"serverPublicKey\":\"$replacement_public\""* ]]
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

test_c4_legacy_name_only_remove_schema() {
  owned_config
  run_script remove-peer --name existing
  assert_success
  [[ "$RESPONSE" == *'"name":"existing"'* && "$RESPONSE" == *'"removed":true'* && "$RESPONSE" == *'"revoked":false'* ]]
}

test_c4_legacy_name_only_revoke_schema() {
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

test_restore_fresh_identity_and_receipt() {
  restore_source
  run_restore
  assert_success
  python3 - "$TEST_ROOT/response" "$WG_CONF" "$RESTORE_INPUT/server.conf" "$RESTORE_PUBLIC_KEY" <<'PY'
import json
import pathlib
import sys
response, restored, source = map(pathlib.Path, sys.argv[1:4])
assert json.loads(response.read_text()) == {
    'ok': True, 'interface': 'wg0', 'endpoint': 'restored.example', 'port': '51900',
    'cidr': '10.8.0.0/24', 'serverPublicKey': sys.argv[4], 'peerCount': 1,
    'revokedCount': 0, 'cleanupPath': ''
}
before = source.read_text()
after = restored.read_text()
assert 'PrivateKey = ' + before.split('PrivateKey = ', 1)[1].splitlines()[0] in after
assert after.count('# BEGIN_PEER existing') == 1
assert after.split('# BEGIN_PEER existing', 1)[1] == before.split('# BEGIN_PEER existing', 1)[1]
assert '# ENDPOINT restored.example\n' in after
assert 'ListenPort = 51900\n' in after
assert 'PostUp = iptables -t nat -A POSTROUTING -s 10.8.0.0/24 ! -d 10.8.0.0/24 -j MASQUERADE\n' in after
assert 'PostDown = iptables -t nat -D POSTROUTING -s 10.8.0.0/24 ! -d 10.8.0.0/24 -j MASQUERADE\n' in after
PY
  [[ -f "$TEST_STATE/runtime.conf" && -f "$TEST_STATE/enabled" && $(cat "$TEST_STATE/forward-value") == 1 ]]
  [[ $(cat "$TEST_STATE/address") == 10.8.0.1/24 && $(cat "$TEST_STATE/nat") == 10.8.0.0/24 ]]
  assert_private "$WG_CONF" 600
  assert_private "$TUNNL_DIR/revoked" 700
  grep -qxF 'ss -H -uan sport = :51900' "$TEST_STATE/trace"
  ! grep -Eq '^apt-get |^wg (genkey|genpsk) ' "$TEST_STATE/trace"
}

test_restore_preserves_psk_and_historical_reuse() {
  restore_source
  printf '\n' >> "$RESTORE_INPUT/server.conf"
  restore_peer phone phone 10.8.0.3 current >> "$RESTORE_INPUT/server.conf"
  restore_peer existing first 10.8.0.2 historical > "$RESTORE_INPUT/revoked/existing.conf"
  restore_peer existing second 10.9.0.2 > "$RESTORE_INPUT/revoked/existing.conf.ABC123"
  run_restore
  assert_success
  [[ "$RESPONSE" == *'"peerCount":2'* && "$RESPONSE" == *'"revokedCount":2'* ]]
  cmp -s "$RESTORE_INPUT/revoked/existing.conf" "$TUNNL_DIR/revoked/existing.conf"
  cmp -s "$RESTORE_INPUT/revoked/existing.conf.ABC123" "$TUNNL_DIR/revoked/existing.conf.ABC123"
  assert_private "$TUNNL_DIR/revoked/existing.conf" 600
  assert_private "$TUNNL_DIR/revoked/existing.conf.ABC123" 600
  [[ $(awk '$1 == "PublicKey" {print $3}' "$WG_CONF" | wc -l) -eq 2 ]]
  [[ $(awk '$1 == "PresharedKey" {print $3}' "$WG_CONF") == "$(printf '%-32.32s' PSK-current | base64 -w0)" ]]
  [[ $(awk '$1 == "PresharedKey" {print $3}' "$TEST_STATE/runtime.conf") == "$(printf '%-32.32s' PSK-current | base64 -w0)" ]]
  ! grep -Eq '^apt-get |^wg (genkey|genpsk) ' "$TEST_STATE/trace"
}

test_restore_requires_explicit_options() {
  restore_source
  for missing in input endpoint port expected-server-public-key confirm; do
    args=()
    [[ "$missing" == input ]] || args+=(--input "$RESTORE_INPUT")
    [[ "$missing" == endpoint ]] || args+=(--endpoint restored.example)
    [[ "$missing" == port ]] || args+=(--port 51900)
    [[ "$missing" == expected-server-public-key ]] || args+=(--expected-server-public-key "$RESTORE_PUBLIC_KEY")
    [[ "$missing" == confirm ]] || args+=(--confirm)
    run_script restore "${args[@]}"
    assert_failure missing_arg
  done
  for option in input endpoint port expected-server-public-key; do
    run_script restore "--$option"
    assert_failure missing_arg
  done
  WG_IFACE=wg1 run_restore
  assert_failure invalid_interface
  assert_no_mutations
}

test_restore_rejects_unsafe_input_paths() {
  restore_source
  /bin/mv "$RESTORE_INPUT" "$TEST_ROOT/actual-input"
  ln -s "$TEST_ROOT/actual-input" "$RESTORE_INPUT"
  run_restore
  assert_failure unsafe_path
  /bin/rm "$RESTORE_INPUT"
  /bin/mv "$TEST_ROOT/actual-input" "$RESTORE_INPUT"
  /bin/mv "$RESTORE_INPUT/server.conf" "$TEST_ROOT/actual.conf"
  ln -s "$TEST_ROOT/actual.conf" "$RESTORE_INPUT/server.conf"
  run_restore
  assert_failure unsafe_path
  /bin/rm "$RESTORE_INPUT/server.conf"
  mkfifo "$RESTORE_INPUT/server.conf"
  run_restore
  assert_failure unsafe_path
  assert_no_mutations
}

test_restore_rejects_unsafe_revoked_entries() {
  restore_source
  for kind in absent symlink directory fifo hidden unsupported newline; do
    /bin/rm -rf -- "$RESTORE_INPUT/revoked"
    mkdir "$RESTORE_INPUT/revoked"
    case "$kind" in
      absent) /bin/rmdir "$RESTORE_INPUT/revoked" ;;
      symlink) ln -s "$RESTORE_INPUT/server.conf" "$RESTORE_INPUT/revoked/alice.conf" ;;
      directory) mkdir "$RESTORE_INPUT/revoked/alice.conf" ;;
      fifo) mkfifo "$RESTORE_INPUT/revoked/alice.conf" ;;
      hidden) printf x > "$RESTORE_INPUT/revoked/.hidden" ;;
      unsupported) printf x > "$RESTORE_INPUT/revoked/alice.conf.longer-suffix" ;;
      newline) printf x > "$RESTORE_INPUT/revoked/"$'alice.conf\nbob.conf' ;;
    esac
    run_restore
    if [[ "$kind" == hidden || "$kind" == unsupported || "$kind" == newline ]]; then
      assert_failure invalid_config
    else
      assert_failure unsafe_path
    fi
  done
  /bin/rm -rf -- "$RESTORE_INPUT/revoked"
  ln -s "$TEST_ROOT" "$RESTORE_INPUT/revoked"
  run_restore
  assert_failure unsafe_path
  assert_no_mutations
}

test_restore_rejects_unsafe_destination_paths() {
  restore_source
  mkdir -p "$TUNNL_DIR"
  chmod 700 "$TUNNL_DIR"
  ln -s "$RESTORE_INPUT/revoked" "$TUNNL_DIR/revoked"
  run_restore
  assert_failure unsafe_path
  [[ -L "$TUNNL_DIR/revoked" ]]
  /bin/rm "$TUNNL_DIR/revoked"
  ln -s "$RESTORE_INPUT/server.conf" "$FORWARD_CONF"
  run_restore
  assert_failure unmanaged_forwarding
  [[ -L "$FORWARD_CONF" ]]
  assert_no_mutations
}

test_restore_rejects_existing_config_and_history() {
  restore_source
  for kind in managed unmanaged symlink directory; do
    case "$kind" in
      managed) /bin/cp "$RESTORE_INPUT/server.conf" "$WG_CONF" ;;
      unmanaged) printf '[Interface]\n' > "$WG_CONF" ;;
      symlink) ln -s "$RESTORE_INPUT/server.conf" "$WG_CONF" ;;
      directory) mkdir "$WG_CONF" ;;
    esac
    run_restore
    assert_failure config_exists
    [[ -e "$WG_CONF" || -L "$WG_CONF" ]]
    /bin/rm -rf -- "$WG_CONF"
  done
  mkdir -p "$TUNNL_DIR/revoked"
  chmod 700 "$TUNNL_DIR"
  printf 'retained old record\n' > "$TUNNL_DIR/revoked/old.conf"
  run_restore
  assert_failure revoked_history_exists
  [[ $(cat "$TUNNL_DIR/revoked/old.conf") == 'retained old record' ]]
  assert_no_mutations
}

test_restore_rejects_writable_state_parents_without_chmod() {
  restore_source
  for mode in 777 775 757; do
    chmod "$mode" "$WG_DIR"
    run_restore
    assert_failure unsafe_path
    [[ $(stat -c %a "$WG_DIR") == "$mode" ]]
  done
  chmod 755 "$WG_DIR"
  mkdir "$TUNNL_DIR"
  for mode in 777 755 750; do
    chmod "$mode" "$TUNNL_DIR"
    run_restore
    assert_failure unsafe_path
    [[ $(stat -c %a "$TUNNL_DIR") == "$mode" ]]
  done
  [[ ! -e "$WG_CONF" && ! -e "$TUNNL_DIR/revoked" ]]
  assert_no_mutations
}

test_restore_rejects_nonroot_state_owners() {
  restore_source
  mkdir "$TUNNL_DIR"
  chmod 700 "$TUNNL_DIR"
  cat > "$TEST_ROOT/bin/stat" <<'SH'
#!/usr/bin/env bash
if [[ "$*" == '-c %u %a -- '* && "${*: -1}" == "$TEST_NONROOT_DIRECTORY" ]]; then
  printf '1234 %s\n' "$(/usr/bin/stat -c %a -- "$TEST_NONROOT_DIRECTORY")"
else
  exec /usr/bin/stat "$@"
fi
SH
  chmod 700 "$TEST_ROOT/bin/stat"
  export TEST_NONROOT_DIRECTORY
  for TEST_NONROOT_DIRECTORY in "$WG_DIR" "$TUNNL_DIR"; do
    before=$(/usr/bin/stat -c '%u %a' -- "$TEST_NONROOT_DIRECTORY")
    run_restore
    assert_failure unsafe_path
    [[ $(/usr/bin/stat -c '%u %a' -- "$TEST_NONROOT_DIRECTORY") == "$before" ]]
  done
  assert_no_mutations
}

test_restore_accepts_protected_existing_state_parents() {
  restore_source
  chmod 755 "$WG_DIR"
  mkdir -p "$TUNNL_DIR/revoked"
  chmod 700 "$TUNNL_DIR"
  run_restore
  assert_success
  assert_private "$WG_DIR" 755
  assert_private "$TUNNL_DIR" 700
  assert_private "$TUNNL_DIR/revoked" 700
}

test_restore_rejects_address_prefix_overlaps() {
  restore_source
  for prefix in 10.8.0.20/24 10.8.0.129/25 10.8.1.1/16 10.8.0.2/32 10.8.0.4/0; do
    printf '2: eth0 inet %s scope global noprefixroute eth0\n' "$prefix" > "$TEST_STATE/ipv4-addresses"
    run_restore
    assert_failure subnet_in_use
  done
  assert_no_mutations
}

test_restore_rejects_route_prefix_overlaps() {
  restore_source
  for selector in root match; do
    for prefix in 10.8.0.0/24 10.8.0.128/25 10.8.0.0/16 10.8.0.2; do
      printf '%s dev eth0 table 123 proto static\n' "$prefix" > "$TEST_STATE/ipv4-routes-$selector"
      run_restore
      assert_failure subnet_in_use
    done
    /bin/rm "$TEST_STATE/ipv4-routes-$selector"
  done
  assert_no_mutations
}

test_restore_allows_ordinary_defaults_and_unrelated_subnets() {
  restore_source
  printf '1: lo inet 127.0.0.1/8 scope host lo\n2: eth0 inet 10.9.0.1/24 scope global eth0\n' > "$TEST_STATE/ipv4-addresses"
  printf '10.9.0.0/24 dev eth0 proto kernel\n' > "$TEST_STATE/ipv4-routes-root"
  printf 'default via 192.0.2.1 dev eth0\n0.0.0.0/0 via 192.0.2.2 dev eth1 table 123\n' > "$TEST_STATE/ipv4-routes-match"
  run_restore
  assert_success
  grep -qxF 'ip -4 -o address show' "$TEST_STATE/trace"
  grep -qxF 'ip -4 -o route show table all root 10.8.0.0/24' "$TEST_STATE/trace"
  grep -qxF 'ip -4 -o route show table all match 10.8.0.0/24' "$TEST_STATE/trace"
}

test_restore_rejects_default_deny_routes() {
  restore_source
  for selector in root match; do
    for kind in blackhole unreachable prohibit; do
      for prefix in default 0.0.0.0/0; do
        printf '%s %s table 123 proto static\n' "$kind" "$prefix" > "$TEST_STATE/ipv4-routes-$selector"
        run_restore
        assert_failure subnet_in_use
      done
    done
    /bin/rm "$TEST_STATE/ipv4-routes-$selector"
  done
  assert_no_mutations
}

test_restore_rejects_unknown_subnet_observations() {
  restore_source
  for call in 1 2 3; do
    /bin/rm -f "$TEST_STATE/count-ip--4"
    printf '%s' "$call" > "$TEST_STATE/fail-ip--4"
    run_restore
    assert_failure runtime_unknown
  done
  /bin/rm "$TEST_STATE/fail-ip--4"
  for observation in 'malformed' '2: eth0 inet 10.8.0.1/33' '2: eth0 inet 999.1.2.3/24' '2: eth0 inet 10.9.0.1'; do
    printf '%s\n' "$observation" > "$TEST_STATE/ipv4-addresses"
    run_restore
    assert_failure runtime_unknown
  done
  /bin/rm "$TEST_STATE/ipv4-addresses"
  for selector in root match; do
    for observation in 'malformed' 'invalid dev eth0' '10.8.0.0/33 dev eth0' 'blackhole invalid'; do
      printf '%s\n' "$observation" > "$TEST_STATE/ipv4-routes-$selector"
      run_restore
      assert_failure runtime_unknown
    done
    /bin/rm "$TEST_STATE/ipv4-routes-$selector"
  done
  assert_no_mutations
}

test_restore_rejects_live_interface() {
  restore_source
  /bin/cp "$RESTORE_INPUT/server.conf" "$TEST_STATE/runtime.conf"
  run_restore
  assert_failure unmanaged_runtime
  [[ ! -e "$WG_CONF" ]]
  cmp -s "$TEST_STATE/runtime.conf" "$RESTORE_INPUT/server.conf"
  assert_no_mutations
}

test_restore_rejects_udp_holders() {
  restore_source
  for holder in \
    'UNCONN 0 0 0.0.0.0:51900 0.0.0.0:*' \
    'ESTAB 0 0 192.0.2.1:51900 192.0.2.2:53000' \
    'UNCONN 0 0 [::]:51900 [::]:*'; do
    printf '%s\n' "$holder" > "$TEST_STATE/udp-holders"
    run_restore
    assert_failure port_in_use
  done
  assert_no_mutations
}

test_restore_rejects_unknown_udp_observation() {
  restore_source
  printf '*' > "$TEST_STATE/fail-ss--H"
  run_restore
  assert_failure runtime_unknown
  assert_no_mutations
}

test_restore_requires_installed_tools() {
  restore_source
  for tool in wg wg-quick ip iptables systemctl ss sysctl; do
    cat > "$TEST_ROOT/bin/command-check" <<SH
command() {
  if [[ \$1 == -v && \$2 == '$tool' ]]; then return 1; fi
  builtin command "\$@"
}
SH
    BASH_ENV="$TEST_ROOT/bin/command-check" run_restore
    assert_failure missing_tool
  done
  assert_no_mutations
}

test_restore_rejects_identity_mismatch() {
  restore_source
  RESTORE_PUBLIC_KEY=$(printf '%-32.32s' PUBLIC-wrong | base64 -w0)
  run_restore
  assert_failure server_identity_mismatch
  RESTORE_PUBLIC_KEY=invalid-synthetic-secret
  run_restore
  assert_failure invalid_public_key
  [[ "$RESPONSE" != *invalid-synthetic-secret* ]]
  assert_no_mutations
}

test_restore_rejects_unrecognized_grammar() {
  restore_source
  /bin/cp "$RESTORE_INPUT/server.conf" "$TEST_ROOT/valid-source.conf"
  for invalid in unknown duplicate hook saveconfig marker peer-marker extra-section nul utf8; do
    /bin/cp "$TEST_ROOT/valid-source.conf" "$RESTORE_INPUT/server.conf"
    case "$invalid" in
      unknown) sed -i '/^Address = /a DNS = 1.1.1.1' "$RESTORE_INPUT/server.conf" ;;
      duplicate) sed -i '/^ListenPort = /a ListenPort = 51820' "$RESTORE_INPUT/server.conf" ;;
      hook) sed -i "s|^PostUp = .*|PostUp = touch $TEST_ROOT/hook-ran|" "$RESTORE_INPUT/server.conf" ;;
      saveconfig) sed -i 's/^SaveConfig = false/SaveConfig = true/' "$RESTORE_INPUT/server.conf" ;;
      marker) sed -i '/^# TUNNL_MANAGED 1$/d' "$RESTORE_INPUT/server.conf" ;;
      peer-marker) sed -i 's/^# END_PEER existing$/# END_PEER other/' "$RESTORE_INPUT/server.conf" ;;
      extra-section) printf '\n[Interface]\n' >> "$RESTORE_INPUT/server.conf" ;;
      nul) printf '\000' >> "$RESTORE_INPUT/server.conf" ;;
      utf8) printf '\377' >> "$RESTORE_INPUT/server.conf" ;;
    esac
    run_restore
    assert_failure invalid_config
    [[ ! -e "$TEST_ROOT/hook-ran" && ! -e "$WG_CONF" ]]
  done
  assert_no_mutations
}

test_restore_rejects_noncanonical_keys() {
  restore_source
  /bin/cp "$RESTORE_INPUT/server.conf" "$TEST_ROOT/valid-source.conf"
  for directive in PrivateKey PublicKey PresharedKey; do
    for key in short 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB='; do
      /bin/cp "$TEST_ROOT/valid-source.conf" "$RESTORE_INPUT/server.conf"
      if [[ "$directive" == PresharedKey ]]; then
        sed -i "/^PublicKey = /a PresharedKey = $key" "$RESTORE_INPUT/server.conf"
      else
        sed -i "s|^$directive = .*|$directive = $key|" "$RESTORE_INPUT/server.conf"
      fi
      run_restore
      assert_failure invalid_config
    done
  done
  assert_no_mutations
}

test_restore_rejects_invalid_pool_and_duplicate_peers() {
  restore_source
  /bin/cp "$RESTORE_INPUT/server.conf" "$TEST_ROOT/valid-source.conf"
  for invalid in subnet leading-zero-subnet server peer-mask peer-server peer-broadcast peer-foreign leading-zero-peer duplicate-name duplicate-key duplicate-address; do
    /bin/cp "$TEST_ROOT/valid-source.conf" "$RESTORE_INPUT/server.conf"
    case "$invalid" in
      subnet) sed -i 's|10.8.0.0/24|10.8.0.0/16|g' "$RESTORE_INPUT/server.conf" ;;
      leading-zero-subnet) sed -i 's|10.8.0.|10.08.0.|g' "$RESTORE_INPUT/server.conf" ;;
      server) sed -i 's|^Address = .*|Address = 10.8.0.2/24|' "$RESTORE_INPUT/server.conf" ;;
      peer-mask) sed -i 's|^AllowedIPs = .*|AllowedIPs = 10.8.0.2/24|' "$RESTORE_INPUT/server.conf" ;;
      peer-server) sed -i 's|^AllowedIPs = .*|AllowedIPs = 10.8.0.1/32|' "$RESTORE_INPUT/server.conf" ;;
      peer-broadcast) sed -i 's|^AllowedIPs = .*|AllowedIPs = 10.8.0.255/32|' "$RESTORE_INPUT/server.conf" ;;
      peer-foreign) sed -i 's|^AllowedIPs = .*|AllowedIPs = 10.9.0.2/32|' "$RESTORE_INPUT/server.conf" ;;
      leading-zero-peer) restore_peer another another 10.8.0.02 >> "$RESTORE_INPUT/server.conf" ;;
      duplicate-name) restore_peer existing another 10.8.0.3 >> "$RESTORE_INPUT/server.conf" ;;
      duplicate-key) restore_peer another existing 10.8.0.3 >> "$RESTORE_INPUT/server.conf" ;;
      duplicate-address) restore_peer another another 10.8.0.2 >> "$RESTORE_INPUT/server.conf" ;;
    esac
    run_restore
    assert_failure invalid_config
  done
  assert_no_mutations
}

test_restore_rejects_revoked_identity_overlap() {
  restore_source
  restore_peer existing existing 10.8.0.2 > "$RESTORE_INPUT/revoked/existing.conf"
  run_restore
  assert_failure invalid_config
  assert_no_mutations
}

test_restore_rechecks_destination_under_locks() {
  restore_source
  export TEST_HELD_LOCK TEST_LOCK_SIGNAL
  TEST_LOCK_SIGNAL="$TEST_ROOT/restore-lock-entered"
  mkfifo "$TEST_LOCK_SIGNAL"
  exec {lock_signal}<>"$TEST_LOCK_SIGNAL"
  cat > "$TEST_ROOT/bin/flock" <<'SH'
#!/usr/bin/env bash
if [[ $(readlink "/proc/$$/fd/$1") == "$TEST_HELD_LOCK" ]]; then
  printf 'ready\n' > "$TEST_LOCK_SIGNAL"
fi
exec /usr/bin/flock "$@"
SH
  chmod 700 "$TEST_ROOT/bin/flock"
  for change in config subnet parent; do
    for TEST_HELD_LOCK in "$WG_HOST_LOCK_FILE" "$WG_LOCK_FILE"; do
      exec {held_lock}>"$TEST_HELD_LOCK"
      /usr/bin/flock "$held_lock"
      bash "$SCRIPTS/restore.sh" --input "$RESTORE_INPUT" --endpoint restored.example --port 51900 \
        --expected-server-public-key "$RESTORE_PUBLIC_KEY" --confirm > "$TEST_ROOT/response" 2> "$TEST_ROOT/stderr" &
      restore_pid=$!
      if ! read -r -t 5 ready <&"$lock_signal"; then
        kill "$restore_pid" 2>/dev/null || true
        wait "$restore_pid" 2>/dev/null || true
        return 1
      fi
      [[ ! -s "$TEST_ROOT/response" ]]
      case "$change" in
        config) printf '# concurrent managed owner\n' > "$WG_CONF" ;;
        subnet) printf '2: eth0 inet 10.8.1.1/16 scope global noprefixroute eth0\n' > "$TEST_STATE/ipv4-addresses" ;;
        parent) chmod 777 "$WG_DIR" ;;
      esac
      /usr/bin/flock -u "$held_lock"
      exec {held_lock}>&-
      wait "$restore_pid" && RESULT_CODE=0 || RESULT_CODE=$?
      RESPONSE=$(cat "$TEST_ROOT/response")
      case "$change" in
        config)
          assert_failure config_exists
          [[ $(cat "$WG_CONF") == '# concurrent managed owner' ]]
          /bin/rm "$WG_CONF"
          ;;
        subnet)
          assert_failure subnet_in_use
          /bin/rm "$TEST_STATE/ipv4-addresses"
          ;;
        parent)
          assert_failure unsafe_path
          assert_private "$WG_DIR" 777
          chmod 755 "$WG_DIR"
          ;;
      esac
    done
  done
  assert_no_mutations
}

test_restore_apply_failure_restores_absent_history() {
  restore_source
  restore_peer old archived 10.8.0.2 > "$RESTORE_INPUT/revoked/old.conf"
  printf '1' > "$TEST_STATE/fail-systemctl-restart"
  run_restore
  assert_failure apply_failed
  assert_fresh_restore_rolled_back
  [[ ! -e "$TUNNL_DIR/revoked" ]]
  cmp -s "$RESTORE_INPUT/server.conf" "$TEST_ROOT/before.conf"
  [[ -f "$RESTORE_INPUT/revoked/old.conf" ]]
}

test_restore_apply_failure_restores_empty_history() {
  restore_source
  restore_peer old archived 10.8.0.2 > "$RESTORE_INPUT/revoked/old.conf"
  mkdir -p "$TUNNL_DIR/revoked"
  chmod 700 "$TUNNL_DIR"
  chmod 750 "$TUNNL_DIR/revoked"
  printf '1' > "$TEST_STATE/fail-systemctl-restart"
  run_restore
  assert_failure apply_failed
  assert_fresh_restore_rolled_back
  [[ -d "$TUNNL_DIR/revoked" && -z $(find "$TUNNL_DIR/revoked" -mindepth 1 -print -quit) ]]
  assert_private "$TUNNL_DIR/revoked" 750
}

test_restore_write_failure_rolls_back() {
  restore_source
  printf '1' > "$TEST_STATE/fail-mv-rename"
  run_restore
  assert_failure write_failed
  [[ ! -e "$WG_CONF" && ! -e "$FORWARD_CONF" && ! -e "$TEST_STATE/runtime.conf" ]]
  [[ $(cat "$TEST_STATE/forward-value") == 0 ]]
  cmp -s "$RESTORE_INPUT/server.conf" "$TEST_ROOT/before.conf"
}

test_restore_postcondition_failure_rolls_back() {
  restore_source
  touch "$TEST_STATE/wrong-address-once"
  run_restore
  assert_failure postcondition_failed
  assert_fresh_restore_rolled_back
  [[ $(cat "$TEST_STATE/stopped-cidr") == 10.8.0.0/24 ]]
}

test_restore_incomplete_recovery_retains_state() {
  restore_source
  restore_peer old archived 10.8.0.2 > "$RESTORE_INPUT/revoked/old.conf"
  touch "$TEST_STATE/wrong-address-once"
  printf '*' > "$TEST_STATE/fail-systemctl-stop"
  run_restore
  assert_failure postcondition_failed
  [[ "$RESPONSE" == *'"recovery":"incomplete"'* ]]
  recovery=$(find "$WG_DIR" -maxdepth 1 -type d -name '.tunnl-recovery.*')
  [[ -n "$recovery" && "$RESPONSE" == *"\"recoveryPath\":\"$recovery\""* ]]
  [[ -f "$WG_CONF" && -f "$TEST_STATE/runtime.conf" && -f "$TEST_STATE/enabled" ]]
  cmp -s "$RESTORE_INPUT/revoked/old.conf" "$TUNNL_DIR/revoked/old.conf"
  assert_private "$recovery" 700
  assert_private "$WG_CONF" 600
}

test_restore_cleanup_failure_reports_committed_path() {
  restore_source
  touch "$TEST_STATE/fail-recovery-cleanup"
  run_restore
  assert_success
  recovery=$(find "$WG_DIR" -maxdepth 1 -type d -name '.tunnl-recovery.*')
  [[ -n "$recovery" && "$RESPONSE" == *"\"cleanupPath\":\"$recovery\""* ]]
  [[ -f "$WG_CONF" && -f "$TEST_STATE/runtime.conf" && -f "$TEST_STATE/enabled" && $(cat "$TEST_STATE/forward-value") == 1 ]]
  ! grep -q '^systemctl stop ' "$TEST_STATE/trace"
  assert_private "$recovery" 700
}

test_restore_rejects_bounded_input_excess() {
  restore_source
  /bin/cp "$RESTORE_INPUT/server.conf" "$TEST_ROOT/valid-source.conf"
  head -c 262145 /dev/zero | tr '\000' x > "$RESTORE_INPUT/server.conf"
  run_restore
  assert_failure invalid_config
  /bin/cp "$TEST_ROOT/valid-source.conf" "$RESTORE_INPUT/server.conf"
  head -c 16385 /dev/zero | tr '\000' x > "$RESTORE_INPUT/revoked/alice.conf"
  run_restore
  assert_failure invalid_config
  /bin/rm "$RESTORE_INPUT/revoked/alice.conf"
  for ((number=0; number<1025; number++)); do printf x > "$RESTORE_INPUT/revoked/peer$number.conf"; done
  run_restore
  assert_failure invalid_config
  /bin/rm -rf -- "$RESTORE_INPUT/revoked"
  mkdir "$RESTORE_INPUT/revoked"
  for ((number=0; number<50; number++)); do
    restore_peer "peer$number" "archived$number" 10.8.0.2 > "$RESTORE_INPUT/revoked/peer$number.conf"
    head -c 16000 /dev/zero | tr '\000' '\n' >> "$RESTORE_INPUT/revoked/peer$number.conf"
  done
  run_restore
  assert_failure invalid_config
  assert_no_mutations
}

test_restore_rejects_late_invalid_revoked_body() {
  restore_source
  restore_peer alpha first 10.8.0.2 > "$RESTORE_INPUT/revoked/alpha.conf"
  cat > "$TEST_ROOT/bin/find" <<'SH'
#!/usr/bin/env bash
set -o pipefail
/usr/bin/find "$@" | sort -z
SH
  chmod 700 "$TEST_ROOT/bin/find"
  for invalid in name hook key extra-block; do
    restore_peer zeta second 10.8.0.2 > "$RESTORE_INPUT/revoked/zeta.conf"
    case "$invalid" in
      name) sed -i 's/PEER zeta/PEER other/g' "$RESTORE_INPUT/revoked/zeta.conf" ;;
      hook) printf 'PostUp = synthetic-secret-hook\n' >> "$RESTORE_INPUT/revoked/zeta.conf" ;;
      key) sed -i 's|^PublicKey = .*|PublicKey = invalid-synthetic-secret|' "$RESTORE_INPUT/revoked/zeta.conf" ;;
      extra-block) restore_peer zeta third 10.8.0.3 >> "$RESTORE_INPUT/revoked/zeta.conf" ;;
    esac
    run_restore
    assert_failure invalid_config
    [[ "$RESPONSE" != *synthetic-secret* ]]
  done
  assert_no_mutations
}

test_restore_rejects_failed_revoked_enumeration() {
  restore_source
  restore_peer alpha first 10.8.0.2 > "$RESTORE_INPUT/revoked/alpha.conf"
  export RESTORE_INPUT
  cat > "$TEST_ROOT/bin/find" <<'SH'
#!/usr/bin/env bash
if [[ "$1" != "$RESTORE_INPUT/revoked" ]]; then exec /usr/bin/find "$@"; fi
[[ ! -f "$TEST_STATE/partial-enumeration" ]] || printf '%s\0' "$RESTORE_INPUT/revoked/alpha.conf"
printf synthetic-secret-enumeration-error >&2
exit 1
SH
  chmod 700 "$TEST_ROOT/bin/find"
  for state in empty partial; do
    [[ "$state" != partial ]] || touch "$TEST_STATE/partial-enumeration"
    run_restore
    assert_failure invalid_config
    ! grep -q synthetic-secret-enumeration-error "$TEST_ROOT/response" "$TEST_ROOT/stderr"
  done
  assert_no_mutations
}

test_restore_rejects_unknown_forwarding_observation() {
  restore_source
  printf '*' > "$TEST_STATE/fail-sysctl--n"
  run_restore
  assert_failure runtime_unknown
  /bin/rm "$TEST_STATE/fail-sysctl--n"
  printf synthetic-secret-invalid-state > "$TEST_STATE/forward-value"
  run_restore
  assert_failure runtime_unknown
  [[ "$RESPONSE" != *synthetic-secret-invalid-state* ]]
  assert_no_mutations
}

test_restore_failed_forwarding_recovery_retains_snapshot() {
  restore_source
  touch "$TEST_STATE/wrong-address-once"
  printf '2' > "$TEST_STATE/fail-sysctl--w"
  run_restore
  assert_failure postcondition_failed
  [[ "$RESPONSE" == *'"recovery":"incomplete"'* && $(cat "$TEST_STATE/forward-value") == 1 ]]
  recovery=$(find "$WG_DIR" -maxdepth 1 -type d -name '.tunnl-recovery.*')
  [[ -n "$recovery" && "$RESPONSE" == *"\"recoveryPath\":\"$recovery\""* ]]
  cmp -s "$RESTORE_INPUT/server.conf" "$TEST_ROOT/before.conf"
  assert_private "$recovery" 700
}

if [[ ${1-} == --case ]]; then
  setup
  "$2"
  exit
fi

tests=(
  test_c143_configured_inventory_covers_unmarked_running_and_stopped_peers test_c143_invalid_configured_inventory_stays_unknown
  test_c143_duplicate_runtime_inventory_stays_unknown
  test_c143_runtime_inventory_observes_live_only_peers test_c143_failed_runtime_inventory_discards_partial_stdout
  test_c143_malformed_runtime_inventory_stays_unknown test_c143_remove_checks_server_identity_after_concurrent_owner
  test_restore_fresh_identity_and_receipt test_restore_preserves_psk_and_historical_reuse
  test_restore_requires_explicit_options test_restore_rejects_unsafe_input_paths
  test_restore_rejects_unsafe_revoked_entries test_restore_rejects_unsafe_destination_paths
  test_restore_rejects_existing_config_and_history test_restore_rejects_live_interface
  test_restore_rejects_writable_state_parents_without_chmod test_restore_rejects_nonroot_state_owners
  test_restore_accepts_protected_existing_state_parents test_restore_rejects_address_prefix_overlaps
  test_restore_rejects_route_prefix_overlaps test_restore_allows_ordinary_defaults_and_unrelated_subnets
  test_restore_rejects_default_deny_routes test_restore_rejects_unknown_subnet_observations
  test_restore_rejects_udp_holders test_restore_rejects_unknown_udp_observation test_restore_requires_installed_tools
  test_restore_rejects_identity_mismatch test_restore_rejects_unrecognized_grammar test_restore_rejects_noncanonical_keys
  test_restore_rejects_invalid_pool_and_duplicate_peers test_restore_rejects_revoked_identity_overlap
  test_restore_rechecks_destination_under_locks test_restore_apply_failure_restores_absent_history
  test_restore_apply_failure_restores_empty_history test_restore_write_failure_rolls_back
  test_restore_postcondition_failure_rolls_back test_restore_incomplete_recovery_retains_state
  test_restore_cleanup_failure_reports_committed_path
  test_restore_rejects_bounded_input_excess test_restore_rejects_late_invalid_revoked_body
  test_restore_rejects_failed_revoked_enumeration test_restore_rejects_unknown_forwarding_observation
  test_restore_failed_forwarding_recovery_retains_snapshot
  test_backup_complete_snapshot test_backup_json_controls test_backup_empty_revoked
  test_backup_ownership test_backup_rejects_unsafe_revoked_entries test_backup_rejects_binary_and_invalid_utf8
  test_backup_limits test_backup_encoded_response_limit test_backup_late_read_failure_emits_no_partial_snapshot
  test_backup_failed_revoked_enumeration test_backup_waits_and_holds_interface_lock
  test_c143_guarded_add_success_running_and_stopped test_c143_add_rejects_invalid_server_keys_before_mutation
  test_c143_add_rejects_configured_and_live_server_key_drift test_c143_add_checks_server_identity_after_concurrent_owner
  test_c143_add_and_status_suppress_failed_key_diagnostics test_c143_add_refuses_unobservable_live_identity_before_mutation
  test_c143_status_withholds_drifted_or_unobservable_live_identity
  test_c12_guarded_mutation_success_running_and_stopped test_c12_guard_rejects_invalid_keys_before_mutation
  test_c12_guard_rejects_mismatch_and_reused_name test_c12_guard_checks_identity_after_concurrent_owner
  test_c12_guard_never_reads_identity_from_following_peer
  test_c12_status_serializes_with_existing_interface_lock test_c12_status_refuses_unowned_or_ambiguous_config
  test_c4_force_plan test_c4_legacy_name_only_remove_schema test_c4_legacy_name_only_revoke_schema
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
