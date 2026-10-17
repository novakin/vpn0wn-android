#!/usr/bin/env bash
set -euo pipefail

command_name=${0##*/}
action=${1:-none}
case "$command_name" in
  cp) action=copy ;;
  mv) action=rename ;;
  sync) action=flush ;;
  rm)
    if [[ "$action" == -*r* ]]; then action=cleanup; else action=remove; fi
    ;;
esac
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
if [[ "$command_name" == rm && "$action" == cleanup && -f "$TEST_STATE/fail-recovery-cleanup" && "${@: -1}" == */.tunnl-recovery.* ]]; then
  fail=true
fi
/usr/bin/flock -u 9
exec 9>&-
if [[ "$fail" == true ]]; then
  if [[ "$command_name" == rm && "$action" == cleanup && -f "$TEST_STATE/partial-cleanup" ]]; then
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

start_runtime() {
  [[ -f "$WG_CONF" ]] || return 1
  /bin/cp -- "$WG_CONF" "$runtime_file"
  awk '$1 == "Address" {print $3}' "$WG_CONF" > "$address_file"
  awk '/^# TUNNL_CIDR / {print $3}' "$WG_CONF" > "$nat_file"
  if [[ -f "$TEST_STATE/wrong-address-once" ]]; then
    printf '10.255.0.1/24' > "$address_file"
    /bin/rm "$TEST_STATE/wrong-address-once"
  fi
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
        if [[ "$count" -eq 1 && -f "$TEST_STATE/wrong-runtime" ]]; then
          case "$(cat "$TEST_STATE/wrong-runtime")" in
            key)
              wrong_key=$(printf '%-32.32s' PRIVATE-wrong | base64 -w0)
              sed -i "s|^PrivateKey = .*|PrivateKey = $wrong_key|" "$runtime_file"
              ;;
            port) sed -i 's/^ListenPort = .*/ListenPort = 51999/' "$runtime_file" ;;
            routes) sed -i 's|^AllowedIPs = .*|AllowedIPs = 10.255.255.99/32|' "$runtime_file" ;;
            *) exit 2 ;;
          esac
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
          peers)
            awk '$1 == "PublicKey" {print $3}' "$runtime_file"
            if [[ -f "$TEST_STATE/peers-failed-output" ]]; then
              printf 'synthetic-secret-tool-diagnostic\n' >&2
              exit 1
            fi
            ;;
          preshared-keys)
            awk '/^\[Peer\]/ {if(key) print key "\t" psk; key=""; psk="(none)"} $1 == "PublicKey" {key=$3} $1 == "PresharedKey" {psk=$3} END {if(key) print key "\t" psk}' "$runtime_file"
            ;;
          allowed-ips)
            awk '$1 == "PublicKey" {key=$3} $1 == "AllowedIPs" {print key "\t" substr($0, index($0, "=") + 2)}' "$runtime_file"
            ;;
          dump)
            if [[ -f "$TEST_STATE/dump" ]]; then
              cat "$TEST_STATE/dump"
              if [[ -f "$TEST_STATE/dump-failed-output" ]]; then
                printf 'synthetic-secret-tool-diagnostic\n' >&2
                exit 1
              fi
              exit 0
            fi
            printf 'synthetic-private\tsynthetic-public\t51820\toff\n'
            awk '$1 == "PublicKey" {key=$3} $1 == "AllowedIPs" {print key "\t(none)\t(none)\t" $3 "\t1710000000\t1024\t2048\t25"}' "$runtime_file"
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
        if [[ " $* " == *' --now '* && ! -f "$runtime_file" ]]; then
          start_runtime
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
        start_runtime
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
  sysctl)
    case "$*" in
      '-n net.ipv4.ip_forward') cat "$TEST_STATE/forward-value" ;;
      '-w net.ipv4.ip_forward='[01]) printf '%s' "${2##*=}" > "$TEST_STATE/forward-value" ;;
      *) exit 2 ;;
    esac
    ;;
  ss)
    [[ "$*" == '-H -uan sport = :'[0-9]* ]] || exit 2
    [[ ! -f "$TEST_STATE/udp-holders" ]] || cat "$TEST_STATE/udp-holders"
    ;;
  iptables) ;;
  ip)
    if [[ "$*" == '-4 -o address show' ]]; then
      [[ ! -f "$TEST_STATE/ipv4-addresses" ]] || cat "$TEST_STATE/ipv4-addresses"
    elif [[ "$*" == '-4 -o route show table all root '* || "$*" == '-4 -o route show table all match '* ]]; then
      route_file="$TEST_STATE/ipv4-routes-${7}"
      [[ ! -f "$route_file" ]] || cat "$route_file"
    elif [[ " $* " == *' link show '* ]]; then
      printf '1: lo: <LOOPBACK> mtu 65536\n'
      [[ ! -f "$TEST_STATE/runtime.conf" ]] || printf '2: wg0: <UP> mtu 1420\n'
    else
      printf '1: %s inet %s scope global\n' "$WG_IFACE" "$(cat "$address_file")"
    fi
    ;;
  sync) [[ $# -eq 2 && "$1" == -f && -e "$2" ]] ;;
  cp|mv|rm) exec "/bin/$command_name" "$@" ;;
  *) exit 2 ;;
esac
