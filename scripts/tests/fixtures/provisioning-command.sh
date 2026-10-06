#!/usr/bin/env bash
set -euo pipefail

command_name=${0##*/}
action=${1:-none}
runtime_file="$TEST_STATE/runtime.conf"
enabled_file="$TEST_STATE/enabled"
address_file="$TEST_STATE/address"
nat_file="$TEST_STATE/nat"
if [[ "$WG_IFACE" != wg0 ]]; then
  runtime_file="$TEST_STATE/runtime-$WG_IFACE.conf"
  enabled_file="$TEST_STATE/enabled-$WG_IFACE"
  address_file="$TEST_STATE/address-$WG_IFACE"
  nat_file="$TEST_STATE/nat-$WG_IFACE"
fi
exec 9>"$TEST_STATE/mock.lock"
/usr/bin/flock 9
printf '%s %s\n' "$command_name" "$*" >> "$TEST_STATE/trace"
counter_file="$TEST_STATE/count-$command_name-$action"
count=0
[[ ! -f "$counter_file" ]] || count=$(cat "$counter_file")
count=$((count + 1))
printf '%s' "$count" > "$counter_file"
failure_file="$TEST_STATE/fail-$command_name-$action"
fail=false
if [[ -f "$failure_file" ]]; then
  failures=$(cat "$failure_file")
  [[ "$failures" != '*' && ",$failures," != *",$count,"* ]] || fail=true
fi
/usr/bin/flock -u 9
exec 9>&-
if [[ "$fail" == true ]]; then
  if [[ "$command_name" == rm && "$action" == -rf && -f "$TEST_STATE/partial-cleanup" ]]; then
    /bin/rm -f -- "${@: -1}/previous.conf"
  fi
  printf 'synthetic-secret-tool-diagnostic\n' >&2
  exit 1
fi

public_key() {
  local private decoded
  private=$(cat)
  decoded=$(printf '%s' "$private" | base64 -d)
  printf '%-32.32s' "PUBLIC-${decoded#PRIVATE-}" | base64 -w0
}

case "$command_name" in
  wg)
    [[ ! -f "$TEST_STATE/wg-unavailable" ]] || exit 127
    case "$action" in
      genkey) printf '%-32.32s' "PRIVATE-$count" | base64 -w0; printf '\n' ;;
      genpsk) printf '%-32.32s' "PSK-$count" | base64 -w0; printf '\n' ;;
      pubkey) public_key; printf '\n' ;;
      syncconf)
        [[ -f "$runtime_file" ]] || exit 1
        [[ ! -f "$TEST_STATE/sync-delay" ]] || sleep "$(cat "$TEST_STATE/sync-delay")"
        [[ ! -f "$TEST_STATE/ignore-sync" ]] || exit 0
        if [[ -f "$TEST_STATE/ignore-psk" ]]; then
          awk '!/^PresharedKey = /' "$3" > "$runtime_file"
        else
          /bin/cp -- "$3" "$runtime_file"
        fi
        ;;
      showconf)
        [[ -f "$runtime_file" ]] || exit 1
        awk '!/^#/ && !/^(Address|DNS|SaveConfig|PostUp|PostDown) = /' "$runtime_file"
        ;;
      show)
        if [[ "${2-}" == interfaces ]]; then
          [[ ! -f "$TEST_STATE/runtime.conf" ]] || printf 'wg0 '
          for runtime in "$TEST_STATE"/runtime-*.conf; do
            [[ -f "$runtime" ]] || continue
            interface=${runtime##*/runtime-}
            printf '%s ' "${interface%.conf}"
          done
          [[ ! -f "$TEST_STATE/other-interfaces" ]] || cat "$TEST_STATE/other-interfaces"
          printf '\n'
          exit 0
        fi
        [[ -f "$runtime_file" ]] || exit 1
        case "${3-}" in
          public-key)
            awk '$1 == "PrivateKey" {print $3; exit}' "$runtime_file" | public_key
            printf '\n'
            ;;
          listen-port) awk '$1 == "ListenPort" {print $3; exit}' "$runtime_file" ;;
          peers) awk '$1 == "PublicKey" {print $3}' "$runtime_file" ;;
          preshared-keys)
            awk '/^\[Peer\]/ {if(key) print key "\t" psk; key=""; psk="(none)"} $1 == "PublicKey" {key=$3} $1 == "PresharedKey" {psk=$3} END {if(key) print key "\t" psk}' "$runtime_file"
            ;;
          allowed-ips)
            awk '$1 == "PublicKey" {key=$3} $1 == "AllowedIPs" {print key "\t" substr($0, index($0, "=") + 2)}' "$runtime_file"
            ;;
          *) exit 2 ;;
        esac
        ;;
      *) exit 2 ;;
    esac
    ;;
  wg-quick)
    [[ "$action" == strip ]] || exit 2
    config=${2:?}
    [[ "$config" == */* ]] || config=$WG_CONF
    awk '!/^#/ && !/^(Address|DNS|SaveConfig|PostUp|PostDown) = /' "$config"
    ;;
  systemctl)
    case "$action" in
      is-active)
        if [[ ! -f "$runtime_file" ]]; then printf 'inactive\n'; exit 3; fi
        printf 'active\n'
        ;;
      is-enabled)
        [[ ! -f "$TEST_STATE/absent-template" ]] || exit 1
        if [[ -f "$enabled_file" ]]; then printf 'enabled\n'; else printf 'disabled\n'; exit 1; fi
        ;;
      show) [[ -f "$TEST_STATE/absent-template" ]] && printf 'not-found\n' || printf 'loaded\n' ;;
      enable)
        touch "$enabled_file"
        if [[ " $* " == *' --now '* ]]; then
          /bin/cp -- "$WG_CONF" "$runtime_file"
          awk '$1 == "Address" {print $3}' "$WG_CONF" > "$address_file"
        fi
        ;;
      disable)
        [[ ! -f "$TEST_STATE/ignore-disable" ]] || exit 0
        /bin/rm -f "$enabled_file"
        [[ " $* " != *' --now '* ]] || /bin/rm -f "$runtime_file"
        ;;
      stop)
        [[ ! -f "$TEST_STATE/ignore-stop" ]] || exit 0
        [[ ! -f "$WG_CONF" ]] || awk '/^# TUNNL_CIDR / {print $3}' "$WG_CONF" > "$TEST_STATE/stopped-cidr"
        if [[ -f "$runtime_file" && -f "$nat_file" ]]; then
          [[ -f "$WG_CONF" && $(awk '/^# TUNNL_CIDR / {print $3}' "$WG_CONF") == "$(cat "$nat_file")" ]] || exit 1
        fi
        /bin/rm -f "$runtime_file"
        /bin/rm -f "$nat_file"
        ;;
      start|restart)
        [[ ! -f "$TEST_STATE/ignore-restart" ]] || exit 0
        [[ -f "$WG_CONF" ]] || exit 1
        /bin/cp -- "$WG_CONF" "$runtime_file"
        awk '$1 == "Address" {print $3}' "$WG_CONF" > "$address_file"
        awk '/^# TUNNL_CIDR / {print $3}' "$WG_CONF" > "$nat_file"
        if [[ -f "$TEST_STATE/wrong-address-once" ]]; then
          printf '10.255.0.1/24' > "$address_file"
          /bin/rm "$TEST_STATE/wrong-address-once"
        fi
        ;;
      *) exit 2 ;;
    esac
    ;;
  apt-get)
    case "$action" in
      update) ;;
      install)
        [[ ! -f "$TEST_STATE/install-delay" ]] || sleep "$(cat "$TEST_STATE/install-delay")"
        printf '%s\n' wireguard wireguard-tools qrencode iptables > "$TEST_STATE/packages"
        /bin/rm -f "$TEST_STATE/absent-template"
        if [[ -f "$TEST_STATE/restore-wg-on-install" ]]; then
          /bin/ln -s command "${0%/*}/wg"
        fi
        ;;
      purge)
        [[ ! -f "$TEST_STATE/ignore-purge" ]] || exit 0
        printf 'iptables\n' > "$TEST_STATE/packages"
        if [[ -f "$TEST_STATE/partial-purge" ]]; then
          touch "$TEST_STATE/wg-unavailable"
          printf 'synthetic-secret-tool-diagnostic\n' >&2
          exit 1
        fi
        ;;
      autoremove) touch "$TEST_STATE/autoremove" ;;
      *) exit 2 ;;
    esac
    ;;
  dpkg-query)
    package=${@: -1}
    [[ ! -f "$TEST_STATE/residual-package" ]] || { printf 'config-files'; exit 0; }
    [[ -f "$TEST_STATE/packages" ]] && grep -qxF "$package" "$TEST_STATE/packages" || exit 1
    printf 'installed'
    ;;
  sysctl) printf '1' > "$TEST_STATE/forward-value" ;;
  ip)
    if [[ " $* " == *' link show '* ]]; then
      printf '1: lo: <LOOPBACK> mtu 65536\n'
      [[ ! -f "$TEST_STATE/runtime.conf" ]] || printf '2: wg0: <UP> mtu 1420\n'
    else
      printf '1: %s inet %s scope global\n' "$WG_IFACE" "$(cat "$address_file")"
    fi
    ;;
  cp|mv|rm) exec "/bin/$command_name" "$@" ;;
  *) exit 2 ;;
esac
