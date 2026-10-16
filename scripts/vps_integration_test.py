#!/usr/bin/env python3
"""Destructive vpn0wn VPS provisioning integration test.

This script intentionally uses key-only OpenSSH via subprocess. It never reads or
prints a password. Required environment variables:

  TUNNL_VPS_HOST
  TUNNL_VPS_USER
  TUNNL_VPS_DESTRUCTIVE=1

Optional:

  TUNNL_VPS_SSH_KEY      Path to a private key. If omitted, ssh-agent/default keys are used.
  TUNNL_VPS_PORT         SSH port, defaults to 22.
  TUNNL_VPS_WG_PORT      WireGuard listen port, defaults to 51820.
  TUNNL_VPS_CIDR         WireGuard CIDR, defaults to 10.88.0.0/24.
  TUNNL_VPS_DNS          DNS string, defaults to "1.1.1.1, 1.0.0.1".
  TUNNL_VPS_PURGE        "0" to keep packages installed after uninstall. Defaults to "1".
"""

from __future__ import annotations

import datetime
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import time
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT_DIR = ROOT / "third_party" / "wireguard-install" / "vpn0wn"


class HarnessFailure(RuntimeError):
    pass


def env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if value is None or value == "":
        raise SystemExit(f"missing required environment variable: {name}")
    return value


HOST = env("TUNNL_VPS_HOST")
USER = env("TUNNL_VPS_USER")
SSH_PORT = env("TUNNL_VPS_PORT", "22")
SSH_KEY = os.environ.get("TUNNL_VPS_SSH_KEY")
DESTRUCTIVE = env("TUNNL_VPS_DESTRUCTIVE")
WG_PORT = env("TUNNL_VPS_WG_PORT", "51820")
CIDR = env("TUNNL_VPS_CIDR", "10.88.0.0/24")
DNS = env("TUNNL_VPS_DNS", "1.1.1.1, 1.0.0.1")
PURGE = env("TUNNL_VPS_PURGE", "1") != "0"
REMOTE_DIR = f"/root/vpn0wn-integration-{int(time.time())}"


def require_destructive_guard() -> None:
    if DESTRUCTIVE != "1":
        raise SystemExit("refusing to run: set TUNNL_VPS_DESTRUCTIVE=1 for this destructive VPS test")


def ssh_base() -> list[str]:
    args = [
        "ssh",
        "-p",
        SSH_PORT,
        "-o",
        "BatchMode=yes",
        "-o",
        "PasswordAuthentication=no",
        "-o",
        "PreferredAuthentications=publickey",
        "-o",
        "StrictHostKeyChecking=accept-new",
        "-o",
        "ConnectTimeout=20",
    ]
    if SSH_KEY:
        args.extend(["-i", SSH_KEY])
    return args


def scp_base() -> list[str]:
    args = [
        "scp",
        "-P",
        SSH_PORT,
        "-o",
        "BatchMode=yes",
        "-o",
        "PasswordAuthentication=no",
        "-o",
        "PreferredAuthentications=publickey",
        "-o",
        "StrictHostKeyChecking=accept-new",
    ]
    if SSH_KEY:
        args.extend(["-i", SSH_KEY])
    return args


def target() -> str:
    return f"{USER}@{HOST}"


def run_local(args: list[str], timeout: int = 300) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        raise HarnessFailure("command timed out") from None
    except OSError:
        raise HarnessFailure("command could not start") from None
    if result.returncode != 0:
        raise HarnessFailure(f"command failed (exit {result.returncode})")
    return result


def run_remote(command: str, timeout: int = 300) -> str:
    return run_local([*ssh_base(), target(), command], timeout=timeout).stdout


def run_json(label: str, command: str, timeout: int = 300) -> dict[str, Any]:
    output = run_remote(command, timeout=timeout).strip()
    try:
        payload = json.loads(output)
    except json.JSONDecodeError:
        raise HarnessFailure(f"{label}: invalid JSON") from None
    if not isinstance(payload, dict):
        raise HarnessFailure(f"{label}: expected JSON object")
    if payload.get("ok") is not True:
        raise HarnessFailure(f"{label}: response did not report success")
    return payload


def q(value: str) -> str:
    return shlex.quote(value)


def require_postcondition(label: str, condition: bool) -> None:
    if not condition:
        raise HarnessFailure(f"{label}: postcondition failed")


def log(label: str) -> None:
    print(f"{label}: verified", flush=True)


def failure_message(error: Exception) -> str:
    if isinstance(error, HarnessFailure):
        return str(error)
    return f"unexpected {type(error).__name__}"


def check_sync(label: str, expected_peers: dict[str, str]) -> None:
    sync = run_json(label, f"{q(REMOTE_DIR + '/sync-status.sh')}")
    require_postcondition(f"{label}-running", sync.get("running") is True)
    require_postcondition(f"{label}-interface", sync.get("interface") == "wg0")
    peers = sync.get("peers")
    require_postcondition(f"{label}-peers", isinstance(peers, list) and len(peers) == len(expected_peers))
    observed = {}
    for peer in peers:
        require_postcondition(f"{label}-peer-row", isinstance(peer, dict))
        name, public_key = peer.get("name"), peer.get("publicKey")
        require_postcondition(f"{label}-peer-row", isinstance(name, str) and isinstance(public_key, str))
        require_postcondition(f"{label}-peer-identity", name not in observed and expected_peers.get(name) == public_key)
        observed[name] = public_key
    require_postcondition(f"{label}-peers", observed == expected_peers)
    live_keys = run_remote("wg show wg0 peers").strip().splitlines()
    require_postcondition(f"{label}-live-peers", sorted(live_keys) == sorted(expected_peers.values()))
    log(label)


def upload_scripts() -> None:
    for path in sorted(SCRIPT_DIR.iterdir()):
        if path.is_file():
            run_local([*scp_base(), str(path), f"{target()}:{REMOTE_DIR}/{path.name}"], timeout=60)
    run_remote(f"chmod 700 {q(REMOTE_DIR)}/*.sh")


def install_cycle(label: str, final: bool = False) -> None:
    install = run_json(
        f"{label}-install",
        f"{q(REMOTE_DIR + '/install.sh')} --endpoint {q(HOST)} --port {q(WG_PORT)} --cidr {q(CIDR)} --dns {q(DNS)} --force",
        timeout=900,
    )
    require_postcondition(f"{label}-install-interface", install.get("interface") == "wg0")
    check_sync(f"{label}-sync-after-install", {})

    if final:
        return

    peer1 = run_json(f"{label}-add-peer-1", f"{q(REMOTE_DIR + '/add-peer.sh')} --name android-test-1 --dns {q(DNS)}")
    require_postcondition(f"{label}-add-peer-1-name", peer1.get("name") == "android-test-1")
    require_postcondition(f"{label}-add-peer-1-key", isinstance(peer1.get("publicKey"), str) and bool(peer1["publicKey"]))
    check_sync(f"{label}-sync-after-peer-1", {"android-test-1": peer1["publicKey"]})

    revoke = run_json(f"{label}-revoke-peer-1", f"{q(REMOTE_DIR + '/revoke-peer.sh')} --name android-test-1 --expected-public-key {q(peer1['publicKey'])}")
    require_postcondition(f"{label}-revoke-peer-1", revoke.get("removed") is True and revoke.get("revoked") is True)
    require_postcondition(f"{label}-revoke-peer-1-name", revoke.get("name") == "android-test-1")
    require_postcondition(f"{label}-revoke-peer-1-key", revoke.get("publicKey") == peer1["publicKey"])
    check_sync(f"{label}-sync-after-revoke-1", {})

    peer2 = run_json(f"{label}-add-peer-2", f"{q(REMOTE_DIR + '/add-peer.sh')} --name android-test-2 --dns {q(DNS)}")
    require_postcondition(f"{label}-add-peer-2-name", peer2.get("name") == "android-test-2")
    require_postcondition(f"{label}-add-peer-2-key", isinstance(peer2.get("publicKey"), str) and bool(peer2["publicKey"]))
    check_sync(f"{label}-sync-after-peer-2", {"android-test-2": peer2["publicKey"]})

    remove = run_json(f"{label}-remove-peer-2", f"{q(REMOTE_DIR + '/remove-peer.sh')} --name android-test-2 --expected-public-key {q(peer2['publicKey'])}")
    require_postcondition(f"{label}-remove-peer-2", remove.get("removed") is True and remove.get("revoked") is False)
    require_postcondition(f"{label}-remove-peer-2-name", remove.get("name") == "android-test-2")
    require_postcondition(f"{label}-remove-peer-2-key", remove.get("publicKey") == peer2["publicKey"])
    check_sync(f"{label}-sync-after-remove-2", {})

    backup = run_json(f"{label}-backup", f"{q(REMOTE_DIR + '/backup.sh')}")
    require_postcondition(f"{label}-backup-interface", backup.get("interface") == "wg0")
    require_postcondition(f"{label}-backup-config", isinstance(backup.get("config"), str) and bool(backup["config"]))
    require_postcondition(f"{label}-backup-revoked-count", backup.get("revokedCount") == "1")
    require_postcondition(f"{label}-backup-version", type(backup.get("snapshotVersion")) is int and backup["snapshotVersion"] == 1)
    captured_at = backup.get("capturedAtUtc")
    try:
        valid_date = isinstance(captured_at, str) and datetime.datetime.strptime(captured_at, "%Y-%m-%dT%H:%M:%SZ").strftime("%Y-%m-%dT%H:%M:%SZ") == captured_at
    except ValueError:
        valid_date = False
    require_postcondition(f"{label}-backup-date", valid_date)
    revoked = backup.get("revoked")
    require_postcondition(f"{label}-backup-revoked", isinstance(revoked, list) and len(revoked) == 1 and isinstance(revoked[0], dict))
    archive_name = revoked[0].get("archiveName")
    require_postcondition(f"{label}-backup-revoked-name", isinstance(archive_name, str) and re.fullmatch(r"android-test-1\.conf\.[A-Za-z0-9]{6}", archive_name) is not None)
    archived_config = revoked[0].get("config")
    require_postcondition(f"{label}-backup-revoked-config", isinstance(archived_config, str) and f"PublicKey = {peer1['publicKey']}" in archived_config.splitlines())
    log(f"{label}-backup")


def uninstall(label: str) -> None:
    purge_arg = " --purge-packages" if PURGE else ""
    payload = run_json(f"{label}-uninstall", f"{q(REMOTE_DIR + '/uninstall.sh')} --confirm{purge_arg}", timeout=900)
    require_postcondition(f"{label}-uninstall", payload.get("uninstalled") is True)
    require_postcondition(f"{label}-package-policy", payload.get("packagesPurged") is PURGE)
    detect = run_json(f"{label}-detect-after-uninstall", f"{q(REMOTE_DIR + '/detect.sh')}")
    require_postcondition(f"{label}-config-removed", detect.get("configExists") is False)
    require_postcondition(f"{label}-tools", detect.get("wireGuardInstalled") is (not PURGE))
    run_remote("test -d /sys/class/net && test ! -e /etc/wireguard/wg0.conf && test ! -L /etc/wireguard/wg0.conf && test ! -e /sys/class/net/wg0")
    if PURGE:
        run_remote(
            "for package in wireguard wireguard-tools qrencode; do "
            "status=$(dpkg-query -W -f='${db:Status-Status}' \"$package\" 2>/dev/null); code=$?; "
            "[ \"$code\" -eq 1 ] || { [ \"$code\" -eq 0 ] && [ \"$status\" = not-installed ]; } || exit 1; done"
        )
    else:
        run_remote("command -v wg >/dev/null")
    log(f"{label}-uninstall")


def cleanup(remove_managed_artifacts: bool = False) -> None:
    command = f"rm -rf -- {q(REMOTE_DIR)}"
    if remove_managed_artifacts:
        command += " /etc/wireguard/tunnl && if [ -d /etc/wireguard ]; then rmdir -- /etc/wireguard; fi"
    run_remote(command, timeout=60)
    run_remote(f"test ! -e {q(REMOTE_DIR)} && test ! -L {q(REMOTE_DIR)}", timeout=60)
    if remove_managed_artifacts:
        run_remote("test ! -e /etc/wireguard && test ! -L /etc/wireguard", timeout=60)


def main() -> int:
    require_destructive_guard()
    print(f"target: {USER}@{HOST}:{SSH_PORT}", flush=True)
    run_remote("id -u | grep -qx 0")
    run_remote("test ! -e /etc/wireguard/tunnl && test ! -L /etc/wireguard/tunnl")
    run_remote(f"mkdir -- {q(REMOTE_DIR)}")
    try:
        upload_scripts()
        print("upload: completed", flush=True)
        detect = run_json("detect-before", f"{q(REMOTE_DIR + '/detect.sh')}")
        require_postcondition("detect-before-supported", detect.get("supported") is True and detect.get("packageManager") == "apt")
        require_postcondition("detect-before-clean-config", detect.get("configExists") is False)
        log("detect-before")
        plan = run_json("plan", f"{q(REMOTE_DIR + '/plan.sh')} --endpoint {q(HOST)} --port {q(WG_PORT)} --cidr {q(CIDR)} --dns {q(DNS)}")
        require_postcondition("plan-supported", plan.get("supported") is True)
        require_postcondition("plan-clean-config", plan.get("willOverwriteConfig") is False)
        log("plan")
        install_cycle("cycle-1")
        uninstall("cycle-1")
        install_cycle("cycle-2", final=True)
        uninstall("cycle-2")
        cleanup(remove_managed_artifacts=True)
    except Exception:
        try:
            cleanup()
        except Exception as cleanup_error:
            print(f"cleanup-warning: {failure_message(cleanup_error)}", file=sys.stderr)
        raise
    print("cleanup: uploaded scripts and vpn0wn test artifacts removed", flush=True)
    return 0


def cli() -> int:
    try:
        return main()
    except Exception as exc:
        print(f"vps integration failed: {failure_message(exc)}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(cli())
