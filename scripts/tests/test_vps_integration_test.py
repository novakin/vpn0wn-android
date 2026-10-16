import contextlib
import importlib.util
import io
import json
import os
import pathlib
import shlex
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "vps_integration_test.py"
SPEC = importlib.util.spec_from_file_location("vps_harness", SCRIPT)
HARNESS = importlib.util.module_from_spec(SPEC)
with patch.dict(os.environ, {
    "TUNNL_VPS_HOST": "203.0.113.10", "TUNNL_VPS_USER": "root", "TUNNL_VPS_DESTRUCTIVE": "1"
}, clear=True):
    SPEC.loader.exec_module(HARNESS)

PRIVATE_KEY = "synthetic-private-key-marker"
PSK = "synthetic-preshared-key-marker"
CONFIG = f"[Interface]\nPrivateKey = {PRIVATE_KEY}\n[Peer]\nPresharedKey = {PSK}\n"


class OfflineHost:
    def __init__(self, faults=(), overrides=None):
        self.faults = set(faults)
        self.overrides = overrides or {}
        self.commands = []
        self.calls = {}
        self.config = False
        self.running = False
        self.tools = False
        self.packages = "not-installed"
        self.peers = {}
        self.live_keys = set()
        self.upload = False
        self.artifacts = "preexisting-artifacts" in self.faults
        self.wireguard_directory = self.artifacts

    def run(self, command, timeout=300):
        self.commands.append(command)
        if command == "id -u | grep -qx 0":
            self.require("non-root" not in self.faults)
            return ""
        if command.startswith("test ! -e /etc/wireguard/tunnl"):
            self.require(not self.artifacts)
            return ""
        if command.startswith("mkdir -- "):
            self.require("mkdir-failure" not in self.faults and not self.upload)
            self.upload = True
            return ""
        if command.startswith("chmod 700 "):
            return ""
        if command == "wg show wg0 peers":
            return "\n".join(sorted(self.live_keys))
        if command.startswith("test -d /sys/class/net && test ! -e /etc/wireguard/wg0.conf"):
            self.require(not self.config and not self.running and "uninstall-runtime-unavailable" not in self.faults)
            return ""
        if command.startswith("for package in wireguard"):
            self.require(self.packages == "not-installed")
            return ""
        if command == "command -v wg >/dev/null":
            self.require(self.tools)
            return ""
        if command.startswith("rm -rf -- "):
            self.require("cleanup-failure" not in self.faults)
            self.upload = False
            if "/etc/wireguard/tunnl" in command:
                self.artifacts = False
                self.require(not self.config)
                self.wireguard_directory = False
            return ""
        if command.startswith(f"test ! -e {HARNESS.REMOTE_DIR}"):
            self.require(not self.upload)
            return ""
        if command == "test ! -e /etc/wireguard && test ! -L /etc/wireguard":
            self.require(not self.wireguard_directory)
            return ""
        args = shlex.split(command)
        script = pathlib.PurePosixPath(args[0]).name
        self.calls[script] = self.calls.get(script, 0) + 1
        if script == "detect.sh":
            payload = {"supported": True, "packageManager": "apt", "configExists": self.config,
                       "wireGuardInstalled": self.tools}
        elif script == "plan.sh":
            payload = {"supported": True, "willOverwriteConfig": self.config}
        elif script == "install.sh":
            self.config = self.artifacts = self.wireguard_directory = self.tools = True
            self.running = "install-stopped" not in self.faults
            self.packages = "installed"
            self.peers.clear()
            self.live_keys.clear()
            payload = {"interface": "wg0", "serverPublicKey": PRIVATE_KEY, "future": {"secret": PSK}}
        elif script == "sync-status.sh":
            payload = {"interface": "wg0", "running": self.running,
                       "peers": [{"name": name, "publicKey": key} for name, key in self.peers.items()]}
        elif script == "add-peer.sh":
            name = args[args.index("--name") + 1]
            key = f"synthetic-public-key-{name}"
            self.peers[name] = key
            self.live_keys.add(key)
            payload = {"name": name, "publicKey": key, "config": CONFIG, "nested": {"privateKey": PRIVATE_KEY}}
        elif script in ("revoke-peer.sh", "remove-peer.sh"):
            name = args[args.index("--name") + 1]
            self.require(args[args.index("--expected-public-key") + 1] == self.peers[name])
            key = self.peers.pop(name)
            if f"{script}-live-residue" not in self.faults:
                self.live_keys.remove(key)
            payload = {"name": name, "publicKey": key, "removed": True, "revoked": script == "revoke-peer.sh", "archive": CONFIG}
        elif script == "backup.sh":
            payload = {"interface": "wg0", "config": CONFIG, "revokedCount": "1"}
        elif script == "uninstall.sh":
            purge = "--purge-packages" in args
            self.config = "uninstall-config-residue" in self.faults
            self.running = "uninstall-live-residue" in self.faults
            self.tools = not purge
            if purge:
                self.packages = "config-files" if "uninstall-package-residue" in self.faults else "not-installed"
            payload = {"uninstalled": True, "packagesPurged": purge, "backup": CONFIG}
        else:
            raise AssertionError(f"unexpected offline command: {script}")
        payload["ok"] = True
        override = self.overrides.get((script, self.calls[script]))
        if isinstance(override, str):
            return override
        if override is not None:
            payload.update(override)
        return json.dumps(payload)

    @staticmethod
    def require(condition):
        if not condition:
            raise HARNESS.HarnessFailure("command failed (exit 1)")


class HarnessTest(unittest.TestCase):
    def execute(self, host, purge=True):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(HARNESS, "run_remote", side_effect=host.run), \
                patch.object(HARNESS, "run_local"), patch.object(HARNESS, "PURGE", purge), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = HARNESS.cli()
        output = stdout.getvalue() + stderr.getvalue()
        self.assertNotIn(PRIVATE_KEY, output)
        self.assertNotIn(PSK, output)
        self.assertNotIn("[Interface]", output)
        return code, output

    def test_full_purge_and_keep_packages_controls(self):
        for purge in (True, False):
            with self.subTest(purge=purge):
                host = OfflineHost()
                code, output = self.execute(host, purge)
                self.assertEqual(0, code, output)
                self.assertFalse(host.config or host.running or host.artifacts or host.upload)
                self.assertEqual(not purge, host.tools)
                self.assertEqual(6, host.calls["sync-status.sh"])
                self.assertEqual(2, host.calls["uninstall.sh"])
                self.assertIn("cleanup: uploaded scripts and vpn0wn test artifacts removed", output)

    def test_no_cleanup_before_root_ownership_or_upload_directory_creation(self):
        for fault in ("non-root", "preexisting-artifacts", "mkdir-failure"):
            with self.subTest(fault=fault):
                host = OfflineHost((fault,))
                code, _ = self.execute(host)
                self.assertEqual(1, code)
                self.assertFalse(any(command.startswith("rm ") for command in host.commands))
                self.assertNotIn("install.sh", host.calls)

    def test_destructive_guard_makes_no_remote_call(self):
        with patch.object(HARNESS, "DESTRUCTIVE", "0"), patch.object(HARNESS, "run_remote") as remote:
            with self.assertRaises(SystemExit):
                HARNESS.main()
            remote.assert_not_called()

    def test_partial_upload_only_removes_owned_upload_directory(self):
        host = OfflineHost()
        with patch.object(HARNESS, "run_remote", side_effect=host.run), \
                patch.object(HARNESS, "run_local", side_effect=HARNESS.HarnessFailure("command failed (exit 7)")), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(HARNESS.HarnessFailure, "exit 7"):
                HARNESS.main()
        self.assertFalse(host.upload)
        self.assertEqual([f"rm -rf -- {HARNESS.REMOTE_DIR}"], [c for c in host.commands if c.startswith("rm ")])

    def test_running_and_exact_saved_or_live_peer_failures_retain_server_artifacts(self):
        for fault in ("install-stopped", "revoke-peer.sh-live-residue", "remove-peer.sh-live-residue"):
            with self.subTest(fault=fault):
                host = OfflineHost((fault,))
                code, output = self.execute(host)
                self.assertEqual(1, code)
                self.assertIn("postcondition failed", output)
                self.assertTrue(host.artifacts)
                self.assertFalse(host.upload)
                self.assertFalse(any("/etc/wireguard/tunnl" in c for c in host.commands if c.startswith("rm ")))

    def test_false_uninstall_config_interface_or_package_postconditions_fail(self):
        cases = [
            (("uninstall-config-residue",), {}),
            (("uninstall-config-residue",), {("detect.sh", 2): {"configExists": False}}),
            (("uninstall-live-residue",), {}),
            (("uninstall-runtime-unavailable",), {}),
            (("uninstall-package-residue",), {}),
            ((), {("uninstall.sh", 1): {"uninstalled": False}}),
            ((), {("uninstall.sh", 1): {"packagesPurged": False}}),
            ((), {("detect.sh", 2): {"wireGuardInstalled": True}}),
            ((), {("detect.sh", 3): {"configExists": True}}),
        ]
        for faults, overrides in cases:
            with self.subTest(faults=faults, overrides=overrides):
                host = OfflineHost(faults, overrides)
                code, _ = self.execute(host)
                self.assertEqual(1, code)
                self.assertTrue(host.artifacts)

    def test_keep_packages_rejects_reported_purge_or_missing_tools(self):
        for overrides in ({("uninstall.sh", 1): {"packagesPurged": True}},
                          {("detect.sh", 2): {"wireGuardInstalled": False}}):
            with self.subTest(overrides=overrides):
                code, _ = self.execute(OfflineHost(overrides=overrides), purge=False)
                self.assertEqual(1, code)

    def test_typed_mutation_and_backup_false_postconditions_fail(self):
        cases = [
            ("install.sh", {"interface": "wrong"}),
            ("add-peer.sh", {"name": "wrong"}), ("add-peer.sh", {"publicKey": None}),
            ("add-peer.sh", {"publicKey": ""}),
            ("revoke-peer.sh", {"name": "wrong"}), ("revoke-peer.sh", {"removed": False}),
            ("revoke-peer.sh", {"revoked": 1}), ("remove-peer.sh", {"removed": "true"}),
            ("remove-peer.sh", {"revoked": True}), ("remove-peer.sh", {"name": "wrong"}),
            ("revoke-peer.sh", {"publicKey": "stale-key"}), ("remove-peer.sh", {"publicKey": None}),
            ("backup.sh", {"interface": "wrong"}), ("backup.sh", {"config": None}),
            ("backup.sh", {"config": ""}), ("backup.sh", {"revokedCount": "0"}),
            ("backup.sh", {"revokedCount": 1}), ("uninstall.sh", {"packagesPurged": 1}),
            ("uninstall.sh", {"uninstalled": 1}),
        ]
        for script, override in cases:
            with self.subTest(script=script, override=override):
                code, _ = self.execute(OfflineHost(overrides={(script, 1): override}))
                self.assertEqual(1, code)

    def test_emitted_package_probe_checks_each_status_and_query_exit_offline(self):
        shell = shutil.which("sh")
        if shell is None:
            self.skipTest("POSIX shell unavailable")
        host = OfflineHost()
        self.assertEqual(0, self.execute(host)[0])
        command = next(c for c in host.commands if c.startswith("for package in wireguard"))
        with tempfile.TemporaryDirectory() as directory:
            query = pathlib.Path(directory) / "dpkg-query"
            query.write_text("#!/bin/sh\n"
                             "if [ \"$3\" = \"$FAIL_PACKAGE\" ]; then\n"
                             "  printf '%s' \"$PACKAGE_STATUS\"\n  exit \"$QUERY_EXIT\"\nfi\n"
                             "printf not-installed\n", encoding="utf-8")
            query.chmod(0o700)
            for package in ("wireguard", "wireguard-tools", "qrencode"):
                for status, exit_code, expected in (("not-installed", 0, 0), ("", 1, 0),
                                                     ("installed", 0, 1), ("config-files", 0, 1), ("", 2, 1)):
                    with self.subTest(package=package, status=status, exit_code=exit_code):
                        result = subprocess.run([shell, "-c", command], env={
                            "PATH": directory, "FAIL_PACKAGE": package, "PACKAGE_STATUS": status,
                            "QUERY_EXIT": str(exit_code)
                        }, capture_output=True, text=True, timeout=10, check=False)
                        self.assertEqual(expected, result.returncode, result.stderr)
            query.unlink()
            result = subprocess.run([shell, "-c", command], env={"PATH": directory},
                                    capture_output=True, text=True, timeout=10, check=False)
            self.assertNotEqual(0, result.returncode)

    def test_unknown_or_malformed_sync_state_is_not_empty_success(self):
        invalid = [{"running": value} for value in (False, None, "true", 1)] + [
            {"peers": None}, {"peers": {}}, {"peers": [{"name": "extra", "publicKey": PRIVATE_KEY}]},
            {"interface": "unexpected-interface"},
        ]
        for override in invalid:
            with self.subTest(override=override):
                code, _ = self.execute(OfflineHost(overrides={("sync-status.sh", 1): override}))
                self.assertEqual(1, code)

    def test_missing_wrong_or_duplicate_added_peer_fails(self):
        invalid = [[], [{"name": "wrong", "publicKey": "synthetic-public-key-android-test-1"}],
                   [{"name": "android-test-1", "publicKey": PRIVATE_KEY}],
                   [{"name": "android-test-1", "publicKey": "synthetic-public-key-android-test-1"}] * 2,
                   [None], [{"name": [], "publicKey": PRIVATE_KEY}]]
        for peers in invalid:
            with self.subTest(peers=peers):
                code, _ = self.execute(OfflineHost(overrides={("sync-status.sh", 2): {"peers": peers}}))
                self.assertEqual(1, code)

    def test_remote_failure_and_malformed_json_never_print_payloads(self):
        responses = [CONFIG, json.dumps([CONFIG]), json.dumps({"ok": False, "message": CONFIG, "nested": {"key": PSK}}),
                     json.dumps({"ok": 1, "config": CONFIG})]
        for response in responses:
            with self.subTest(response=response):
                code, _ = self.execute(OfflineHost(overrides={("install.sh", 1): response}))
                self.assertEqual(1, code)

    def test_command_failure_timeout_and_start_failure_omit_args_streams_and_causes(self):
        args = ["ssh", PRIVATE_KEY, CONFIG]
        responses = [subprocess.CompletedProcess(args, 23, CONFIG, PSK),
                     subprocess.TimeoutExpired(args, 300, output=CONFIG, stderr=PSK), OSError(CONFIG)]
        for response in responses:
            with self.subTest(response=type(response).__name__):
                kwargs = {"side_effect": response} if isinstance(response, Exception) else {"return_value": response}
                with patch.object(HARNESS.subprocess, "run", **kwargs):
                    with self.assertRaises(HARNESS.HarnessFailure) as caught:
                        HARNESS.run_local(args)
                text = str(caught.exception)
                self.assertNotIn(PRIVATE_KEY, text)
                self.assertNotIn(PSK, text)
                self.assertNotIn("[Interface]", text)
                self.assertIsNone(caught.exception.__cause__)
                if isinstance(response, subprocess.CompletedProcess):
                    self.assertIn("exit 23", text)

    def test_success_command_preserves_internal_output_without_logging(self):
        result = subprocess.CompletedProcess(["ssh"], 0, CONFIG, PSK)
        output = io.StringIO()
        with patch.object(HARNESS.subprocess, "run", return_value=result), contextlib.redirect_stdout(output):
            self.assertIs(result, HARNESS.run_local(["ssh"]))
        self.assertEqual("", output.getvalue())

    def test_unexpected_primary_and_cleanup_payloads_are_not_printed(self):
        output = io.StringIO()
        host = OfflineHost()
        with patch.object(HARNESS, "run_remote", side_effect=host.run), patch.object(HARNESS, "run_local"), \
                patch.object(HARNESS, "install_cycle", side_effect=ValueError(CONFIG)), \
                patch.object(HARNESS, "cleanup", side_effect=RuntimeError(PSK)), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(output):
            self.assertEqual(1, HARNESS.cli())
        self.assertIn("cleanup-warning: unexpected RuntimeError", output.getvalue())
        self.assertIn("vps integration failed: unexpected ValueError", output.getvalue())
        self.assertNotIn(PRIVATE_KEY, output.getvalue())
        self.assertNotIn(PSK, output.getvalue())

    def test_cleanup_failure_preserves_primary_failure_and_reports_safe_category(self):
        host = OfflineHost(("cleanup-failure",))
        stderr = io.StringIO()
        with patch.object(HARNESS, "run_remote", side_effect=host.run), patch.object(HARNESS, "run_local"), \
                patch.object(HARNESS, "install_cycle", side_effect=ValueError(CONFIG)), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
            with self.assertRaises(ValueError) as caught:
                HARNESS.main()
        self.assertEqual(CONFIG, str(caught.exception))
        self.assertIn("cleanup-warning: command failed (exit 1)", stderr.getvalue())
        self.assertNotIn(PRIVATE_KEY, stderr.getvalue())
        code, output = self.execute(OfflineHost(("install-stopped", "cleanup-failure")))
        self.assertEqual(1, code)
        self.assertIn("postcondition failed", output)
        self.assertIn("cleanup-warning", output)

    def test_final_cleanup_failure_is_not_success(self):
        code, output = self.execute(OfflineHost(("cleanup-failure",)))
        self.assertEqual(1, code)
        self.assertNotIn("cleanup: uploaded scripts and vpn0wn test artifacts removed", output)

    def test_key_only_ssh_and_scp_options_remain(self):
        for args in (HARNESS.ssh_base(), HARNESS.scp_base()):
            self.assertIn("BatchMode=yes", args)
            self.assertIn("PasswordAuthentication=no", args)
            self.assertIn("PreferredAuthentications=publickey", args)
            self.assertIn("StrictHostKeyChecking=accept-new", args)


if __name__ == "__main__":
    unittest.main()
