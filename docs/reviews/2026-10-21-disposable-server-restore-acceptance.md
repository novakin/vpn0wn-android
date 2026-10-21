# Disposable managed-server restore acceptance

Last updated: 2026-10-21 (Europe/Paris).

## Scope and source

The maintainer authorized local disposable Linux VMs for #143/#196 acceptance. Two isolated Ubuntu 24.04.5 guests exercised the production provisioning scripts at `89ea4ca82fe2e8af036b13e3c08d8cfaba041eea`. Host access used loopback-only SSH/UDP forwarding, guest-specific verified SSH host keys and synthetic WireGuard identities. Production infrastructure was excluded. [#323](https://github.com/novakin/vpn0wn-android/issues/323) owns the native-route correction found by these trials; [#196](https://github.com/novakin/vpn0wn-android/issues/196) retains broader backup/restore acceptance.

The R4 controller used the existing install, add, revoke, backup, restore and status commands. A client interface created in the source guest's underlay namespace moved into an isolated network namespace, with only the synthetic server address routed through it. Each successful traffic control required the exact guest-specific HTTP response, a nonzero handshake on a freshly created client interface and positive transfer counters. Private keys, snapshots and guest reports remain outside tracked documentation.

## Results

R4 passed eight phases and both owned cleanup checks:

- Both guests admitted clean operation paths before uploading the reviewed scripts.
- The original client authenticated to the source. Revocation then produced a completed HTTP timeout and zero handshake on a fresh client interface.
- Same-server CHECK, confirmed literal rollback and VERIFY succeeded. The retained client authenticated again without replacing its private identity.
- Original and restored configuration contained exactly the same ordered nonempty LF-separated lines, including all key/PSK and peer fields. Revoked archive names and contents were byte-exact. Canonical restoration removed one empty LF segment; original configuration byte equality was deliberately not claimed.
- Fresh-destination CHECK, CONFIRM and VERIFY succeeded with the original server and active-peer identities and exact revoked history. The retained client authenticated to the destination through its changed endpoint, with a distinct destination response.
- A final source backup matched its post-rollback configuration and history exactly, proving that destination restoration did not modify the source.

Both cleanup checks passed: test client namespace/interface, managed server interface/configuration, HTTP listeners and uploaded operation directories were removed. Product uninstall recovery backups and installed WireGuard packages remain on the disposable guest disks. This is owned cleanup, not complete guest-baseline restoration or secret erasure.

## Earlier failures and correction

R1 stopped during same-server CHECK with exit 73. Its error reporting omitted the public error code. R2 added bounded diagnostics and confirmed `postcondition_failed`: selected-interface iproute2 output omits the already-selected `dev wg0` field, and an otherwise IPv4-only interface has an automatic IPv6 multicast route. The original offline fixture masked both native forms. The correction accepts those exact forms while keeping whole-table device ownership, duplicate and custom-route refusal strict.

The unchanged source fails the updated native-route regression with exit 73. The corrected source passes all 216 mapped-root safety cases. Independent final source review resolved one README wording finding before admitting the runtime rerun.

R3 passed restored-client traffic but failed a compound byte-equality assertion. That result did not identify which comparison failed. Source review established that the writer intentionally normalizes empty lines. The independently reviewed R4 comparator keeps every nonempty line exact and ordered, and keeps revoked records byte-exact. R4 itself established the one-empty-segment difference; that observation is not retroactively assigned to R3. All failed results and each guest's earlier history were retained separately before reruns.

## Evidence and limits

R4 runner SHA256: `571d995c1994bf0a6e68c7d2f7191a0f06c938f5c866db0f1443f6b0a3b73498`. Its dated private result and independent input/outcome reviews belong to this cohort. [PR #324](https://github.com/novakin/vpn0wn-android/pull/324) owns current final-head review, CI, publication and merge acceptance; these facts are not implied by this runtime record.

This trial establishes the named local CLI/kernel-client scenarios. It does not establish Android picker/Keystore/JSch orchestration, encrypted-container interoperability, newly created clients after restore, stable-hostname DNS cutover, unchanged full exported-profile behavior or full-tunnel Internet traffic, external IPv4/IPv6 paths, other distributions, failure/cancellation under real SSH loss, or production readiness. The snapshot-retired peer's traffic denial was not tested after either restore; the observed denial control revoked the retained active peer after backup and before literal rollback. Existing source and separate Android evidence retain their original scope. #143/#196 stay open until their remaining acceptance is met.
