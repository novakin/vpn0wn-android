# Operations and recovery

Last updated: 2026-10-06 (Europe/Paris).

[Documentation index](../README.md#project-documentation) · Reviewed 2026-10-06

This guide describes current Android handling and known limits. The [UI roadmap](ui-redesign-roadmap.md) lists visual gaps; the [improvement plan](improvement-plan.md) tracks reliability repairs. Do not treat a rendered screen or successful command as proof of a working VPN/deployment.

## Import, edit and connect

Use the add-tunnel flow to create a profile, import a file or scan a QR code. Existing import/config parsing owns profile validation and naming. Configs and QR payloads can contain private keys; handle them as credentials. Protect exported/downloaded copies and avoid sharing screenshots or logs containing keys.

Review interface addresses, DNS, peer endpoints and AllowedIPs before connecting. Supported WireGuard config behavior belongs to [backend contracts](backend-contracts.md); do not infer traffic protection from a label alone. The userspace backend needs Android VPN permission and can own one active tunnel. Kernel behavior requires root and available kernel support; multiple-tunnel behavior also depends on existing settings.

Current committed userspace preflight uses an explicit nonzero ListenPort and skips peers without resolved endpoints. It preserves the configured port and logs preflight failure before continuing activation; endpoint resolution can independently fail startup. See [the current activation contract](backend-contracts.md#wireguard-configuration-and-tunnel-state). C5 tracks teardown responsiveness and preflight failure/cancellation policy; repeated errors need diagnosis rather than blind toggles.

Known import/edit defects pending C8 include invalid-name dialog dismissal, QR image retry stream reuse and replacement-length handling. Abbreviated displayed keys also require a full-key copy fix. Until repaired, inspect an exported profile when a copied key is unusable; do not paste a displayed ellipsis as a WireGuard key.

## Permissions and backend changes

VPN permission, camera access and document/export handling depend on the selected action and Android version. Grant permissions for the requested workflow and verify the resulting action. Denial/cancellation is not success.

Switching the kernel/backend preference may shut down active tunnels before changing behavior. Failed shutdown currently has a recovery defect tracked by C6. If preference/UI state becomes stuck, retain sanitized evidence and report the failure; do not assume the new backend is active. Never delete a profile merely to diagnose an unresolved transition.

## Diagnostics and evidence

Use the in-app log viewer for relevant error categories and lifecycle events. A startup error, interface creation failure, service loss and lack of handshake have different causes. Record app revision, Android/backend, the triggering action and the smallest sanitized evidence.

Never attach private keys, PSKs, passwords or a full generated/backup config to an issue. C1 removes raw root-shell command/stdout/stderr logging, provisioning exit-failure payloads, malformed JSON payloads/parser causes and cleanup exception payloads. Internal command results still carry authorized output; do not stringify them into diagnostics. Historical log buffers and exported files may contain secret-bearing output. Review those logs before sharing; this fix does not sanitize previously written data or establish that every transport/server message is safe.

Traffic-rate and selected-view races are tracked by C6–C7. A displayed nonzero rate or a recent sync alone cannot prove connectivity. Device/packet behavior needs relevant real observations; unit tests cannot supply that evidence.

## VPS feature status

Fleet screens render persisted non-secret records. Concrete Android SSH transport, protected credential storage and live provisioning/user-action wiring are unfinished. The current VPS QR uses a real QR matrix but its payload is placeholder deployment data, not an importable generated WireGuard profile.

The app-facing server scripts exist under `third_party/wireguard-install/tunnl/`, with narrow Ubuntu/Debian, `wg0`, IPv4 `/24`, IPv4 default-route and iptables NAT assumptions. Use their [current contracts and gaps](backend-contracts.md#provisioning-command-surface), not the upstream interactive installer, as the integration reference.

The C2–C3 source repair rejects unmanaged state/config injection and adds serialized writes, checked runtime results and recovery. [Offline evidence](development.md#c2c3-offline-provisioning-safety--2026-10-06) does not establish real-server reliability. Android JSON/health contracts, the integration harness and real SSH/UI wiring remain unfinished. Installing or deleting server state is outside an ordinary source/docs task.

## Provisioning mutation recovery

Read [script ownership and recovery](../third_party/wireguard-install/tunnl/README.md#ownership-locking-and-recovery) before an authorized server mutation. `--force` permits replacement of owned config only. Installation stops an old running unit before replacing its config/hooks. Uninstall backs up owned config before stopping/deleting, preserves global forwarding and refuses package purge while another config/live interface exists.

On mutation failure, inspect `recovery`. `unchanged` means no config/runtime mutation required restoration; `restored` means observed disk/runtime/service recovery completed. Neither promises reversal of attempted package changes or global forwarding enable. `incomplete` means inspect the retained private `recoveryPath` and any `artifact` backup before retrying. Backup/runtime files and revoke archives contain private keys/PSKs: keep permissions private and never attach contents to issues.

Success may have nonempty `cleanupPath` when committed behavior succeeded but private snapshot cleanup did not. Inspect/remove that residue only after checking current state and its recovery value. Do not retry the completed operation solely because cleanup remained. Existing Android models do not present these fields yet; C4 owns consumer alignment.

Stopped peer mutations update saved config while leaving the interface absent; they do not prove live access removal or traffic health. SIGKILL, host loss, SSH timeout or a missing response leave uncertain outcomes until disk, service and actual peer state are reconciled. External tools can bypass Tunnl locks. No source/offline result authorizes production testing.

## Disposable validation and recovery

The [QA VPS use case](qa-test-plan.md#disposable-vps-integration-use-case) documents the opt-in destructive integration harness. It requires an explicitly authorized disposable target; it may install, remove peers, uninstall/purge and reinstall WireGuard. Do not run it on a production VPN host or one with user-managed WireGuard state. It is not part of default local tests/builds.

Before an authorized operational mutation, identify owned config/runtime state and relevant backup/recovery evidence. After failure or timeout, inspect actual state before retrying: a failed connection or missing response may leave a completed remote mutation. Do not claim rollback or revocation until its postconditions are observed. The harness also needs C9 postcondition assertions and payload-free failure diagnostics before it is suitable for operational evidence.

For a relevant change or incident, adapt the reviewed VPS change/incident structure: initial state and impact, intended scope, backup, procedure, validation, rollback/recovery result and sanitized evidence. Use this when an operational change needs it; no separate runbook framework is required for routine source edits.
