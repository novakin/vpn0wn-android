#!/usr/bin/env python3
"""Destructive Tunnl VPS provisioning integration test.

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

import json
import os
import pathlib
import shlex
import subprocess
import sys
import time
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT_DIR = ROOT / "third_party" / "wireguard-install" / "tunnl"


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
REMOTE_DIR = f"/root/tunnl-integration-{int(time.time())}"


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
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, timeout=timeout, check=False)
    if result.returncode != 0:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(shlex.quote(a) for a in args)}\n"
            f"stdout:\n{tail(result.stdout)}\n"
            f"stderr:\n{tail(result.stderr)}"
        )
    return result


def run_remote(command: str, timeout: int = 300) -> str:
    return run_local([*ssh_base(), target(), command], timeout=timeout).stdout


def run_json(label: str, command: str, timeout: int = 300) -> dict[str, Any]:
    output = run_remote(command, timeout=timeout).strip()
    try:
        payload = json.loads(output)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"{label} returned non-json output: {output[:500]}") from exc
    if payload.get("ok") is not True:
        raise RuntimeError(f"{label} returned ok=false: {redact_payload(payload)}")
    return payload


def tail(value: str, lines: int = 30) -> str:
    return "\n".join(value.splitlines()[-lines:])


def q(value: str) -> str:
    return shlex.quote(value)


def redact_payload(payload: dict[str, Any]) -> dict[str, Any]:
    redacted = dict(payload)
    for key in ("config", "privateKey", "presharedKey"):
        if key in redacted:
            redacted[key] = f"<redacted:{len(str(redacted[key]))}>"
    if "serverPublicKey" in redacted:
        redacted["serverPublicKeyLen"] = len(str(redacted.pop("serverPublicKey")))
    if "publicKey" in redacted:
        redacted["publicKeyLen"] = len(str(redacted.pop("publicKey")))
    return redacted


def peer_summary(payload: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        {
            "name": peer.get("name"),
            "allowedIps": peer.get("allowedIps"),
            "latestHandshake": peer.get("latestHandshake"),
        }
        for peer in payload.get("peers", [])
    ]


def log(label: str, payload: dict[str, Any]) -> None:
    print(f"{label}: {json.dumps(payload, sort_keys=True)}", flush=True)


def upload_scripts() -> None:
    run_remote(f"mkdir -p {q(REMOTE_DIR)}")
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
    log(f"{label}-install", redact_payload(install))

    sync = run_json(f"{label}-sync-after-install", f"{q(REMOTE_DIR + '/sync-status.sh')}")
    log(f"{label}-sync-after-install", {"running": sync.get("running"), "peers": peer_summary(sync)})

    if final:
        return

    peer1 = run_json(f"{label}-add-peer-1", f"{q(REMOTE_DIR + '/add-peer.sh')} --name android-test-1 --dns {q(DNS)}")
    log(f"{label}-add-peer-1", redact_payload(peer1))

    sync = run_json(f"{label}-sync-after-peer-1", f"{q(REMOTE_DIR + '/sync-status.sh')}")
    log(f"{label}-sync-after-peer-1", {"running": sync.get("running"), "peers": peer_summary(sync)})

    revoke = run_json(f"{label}-revoke-peer-1", f"{q(REMOTE_DIR + '/revoke-peer.sh')} --name android-test-1")
    log(f"{label}-revoke-peer-1", redact_payload(revoke))

    peer2 = run_json(f"{label}-add-peer-2", f"{q(REMOTE_DIR + '/add-peer.sh')} --name android-test-2 --dns {q(DNS)}")
    log(f"{label}-add-peer-2", redact_payload(peer2))

    remove = run_json(f"{label}-remove-peer-2", f"{q(REMOTE_DIR + '/remove-peer.sh')} --name android-test-2")
    log(f"{label}-remove-peer-2", redact_payload(remove))

    backup = run_json(f"{label}-backup", f"{q(REMOTE_DIR + '/backup.sh')}")
    log(f"{label}-backup", {"interface": backup.get("interface"), "configLen": len(str(backup.get("config", ""))), "revokedCount": backup.get("revokedCount")})


def uninstall(label: str) -> None:
    purge_arg = " --purge-packages" if PURGE else ""
    payload = run_json(f"{label}-uninstall", f"{q(REMOTE_DIR + '/uninstall.sh')} --confirm{purge_arg}", timeout=900)
    log(f"{label}-uninstall", {"uninstalled": payload.get("uninstalled"), "backup": bool(payload.get("backup")), "packagesPurged": payload.get("packagesPurged")})


def cleanup() -> None:
    run_remote(f"rm -rf {q(REMOTE_DIR)} /etc/wireguard/tunnl; rmdir /etc/wireguard 2>/dev/null || true", timeout=60)


def main() -> int:
    require_destructive_guard()
    print(f"target: {USER}@{HOST}:{SSH_PORT}", flush=True)
    run_remote("id -u | grep -qx 0")
    upload_scripts()
    print(f"upload: {REMOTE_DIR}", flush=True)

    detect = run_json("detect-before", f"{q(REMOTE_DIR + '/detect.sh')}")
    log(
        "detect-before",
        {
            "os": detect.get("os"),
            "version": detect.get("version"),
            "supported": detect.get("supported"),
            "packageManager": detect.get("packageManager"),
            "wireGuardInstalled": detect.get("wireGuardInstalled"),
            "configExists": detect.get("configExists"),
        },
    )

    plan = run_json("plan", f"{q(REMOTE_DIR + '/plan.sh')} --endpoint {q(HOST)} --port {q(WG_PORT)} --cidr {q(CIDR)} --dns {q(DNS)}")
    log("plan", {"willOverwriteConfig": plan.get("willOverwriteConfig"), "steps": len(plan.get("steps", []))})

    install_cycle("cycle-1")
    uninstall("cycle-1")

    detect = run_json("detect-after-uninstall", f"{q(REMOTE_DIR + '/detect.sh')}")
    log(
        "detect-after-uninstall",
        {
            "wireGuardInstalled": detect.get("wireGuardInstalled"),
            "configExists": detect.get("configExists"),
        },
    )

    install_cycle("cycle-2", final=True)
    uninstall("cycle-2")

    detect = run_json("detect-final", f"{q(REMOTE_DIR + '/detect.sh')}")
    log(
        "detect-final",
        {
            "wireGuardInstalled": detect.get("wireGuardInstalled"),
            "configExists": detect.get("configExists"),
        },
    )
    cleanup()
    cleanup_state = run_remote(
        "if command -v wg >/dev/null 2>&1; then echo wg-present; else echo wg-absent; fi; "
        "if [ -e /etc/wireguard ]; then find /etc/wireguard -maxdepth 2 -type f | sort; else echo etc-wireguard-absent; fi",
        timeout=60,
    )
    print(f"cleanup-state: {cleanup_state.strip()}", flush=True)
    print("cleanup: uploaded scripts and Tunnl test artifacts removed", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        try:
            cleanup()
        except Exception as cleanup_exc:
            print(f"cleanup-warning: {cleanup_exc}", file=sys.stderr)
        print(f"vps integration failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
