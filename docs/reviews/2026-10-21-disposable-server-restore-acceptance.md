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

## R5 retired-user, new-client and hostname follow-up

On 2026-10-21 (Europe/Paris), a separate R5 run used clean source `6da78bdb309f59377fa9aabc294da7652e92121c`; the production scripts are identical to R4's tested revision. Before reuse, the passed R4 receipt and inactive guest resources were checked, and each prior managed-history directory was retained through a same-filesystem rename with its inode preserved. No production server was accessed.

R5 passed thirteen phases and both owned cleanup checks. It repeats the eight R4 phases and adds:

- The peer already revoked in the snapshot remains unable to authenticate after both source and destination restoration: fresh client interfaces show zero handshake and HTTP times out.
- A newly added destination peer has a public key distinct from the retained and retired identities. Server inventory contains exactly the retained and new active peers; the exported profile has the restored server identity and selected endpoint. The new client's actual derived identity, fresh handshake, positive counters and exact destination HTTP response pass.
- The retained client's identity and configured hostname/port work from separate source and destination guest-local namespaces. Native resolution of `restore196.test` returns controlled addresses `127.0.0.2` and `127.0.0.3` respectively; both clients establish fresh handshakes and exact authenticated HTTP responses. The client is recreated between guests. This establishes guest-local NSS resolution, not public DNS propagation or migration of one continuously running client.

R5 runner SHA256 is `88460a96533147033c53e6db38218c366947529f0aa0162b8c360a962258899c`. The command exited zero in 55,725 ms. Both original hosts-file contents were restored exactly. Client/server interfaces, namespace, HTTP listeners and operation directories were removed; packages and product uninstall backups remain on the disposable disks. R4's blank-line normalization and byte-exact revoked-record results were reproduced; configuration byte equality is still not claimed. R4 and earlier results retain their original scope.

## Earlier failures and correction

R1 stopped during same-server CHECK with exit 73. Its error reporting omitted the public error code. R2 added bounded diagnostics and confirmed `postcondition_failed`: selected-interface iproute2 output omits the already-selected `dev wg0` field, and an otherwise IPv4-only interface has an automatic IPv6 multicast route. The original offline fixture masked both native forms. The correction accepts those exact forms while keeping whole-table device ownership, duplicate and custom-route refusal strict.

The unchanged source fails the updated native-route regression with exit 73. The corrected source passes all 216 mapped-root safety cases. Independent final source review resolved one README wording finding before admitting the runtime rerun.

R3 passed restored-client traffic but failed a compound byte-equality assertion. That result did not identify which comparison failed. Source review established that the writer intentionally normalizes empty lines. The independently reviewed R4 comparator keeps every nonempty line exact and ordered, and keeps revoked records byte-exact. R4 itself established the one-empty-segment difference; that observation is not retroactively assigned to R3. All failed results and each guest's earlier history were retained separately before reruns.

## Evidence and limits

R4 runner SHA256: `571d995c1994bf0a6e68c7d2f7191a0f06c938f5c866db0f1443f6b0a3b73498`. Its dated private result and independent input/outcome reviews belong to this cohort. [PR #324](https://github.com/novakin/vpn0wn-android/pull/324) owns current final-head review, CI, publication and merge acceptance; these facts are not implied by this runtime record.

These trials establish their named local CLI/kernel-client scenarios. They do not establish Android picker/Keystore/JSch orchestration, encrypted-container interoperability, public DNS cutover, unchanged full exported-profile behavior or full-tunnel Internet traffic, external IPv4/IPv6 paths, other distributions, failure/cancellation under real SSH loss, or production readiness. R4 did not test snapshot-retired-peer traffic denial or a new client after restoration; the separate R5 controls above add those results. Existing source and separate Android evidence retain their original scope. #143/#196 stay open until their remaining acceptance is met.
