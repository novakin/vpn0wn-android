# G4 import and device acceptance follow-up

Last updated: 2026-10-08 (Europe/Paris).

## Scope and provenance

Partial physical evidence for [#28 Downloads](https://github.com/novakin/wireguard-android/issues/28), [#34 Settings](https://github.com/novakin/wireguard-android/issues/34), [#35 Add/peer](https://github.com/novakin/wireguard-android/issues/35) and [#36 Home](https://github.com/novakin/wireguard-android/issues/36), under [G4/#52](https://github.com/novakin/wireguard-android/issues/52). All remain open; this record neither completes their acceptance nor demonstrates a performance gain.

Pixel 10a ran Android 16/API 36 with the existing userspace debug APK. Its SHA256 matched `5bcb432c58d88bf6a7f3d8ac9891566a047061182addd8524bec6ae9502a71bb`, source `f4a4b147568e6aaa3c82a4307aa6690977e966bd`; [the baseline record](2026-10-08-g4-ui-cpu-baselines.md#build-and-installation-provenance) owns CI, signing, payload and installation proof. The checkout was `dbd95b791d401cfe77e6df01b1c79d652d66a570`; it was not the installed source revision. No new APK, source change, root operation, server operation or traffic benchmark occurred.

The maintainer authorized checks on the saved working app. Earlier phone use interrupted export testing. A subsequent explicit untouched-phone handoff established the controlled interval from `2026-10-08T12:44:35Z` to restoration at `2026-10-08T13:07:54Z`, within the agreed 25 minutes. Settings-switch and tile/export observations preceded that interval and retain their weaker ownership evidence. The session's commands ended after restoration; the phone was released.

Controlled checks used native 1080×2424 portrait output, normal text scale and the app's dark palette. ADB screenshots were inspected; current hierarchy bounds guided taps when available. Animated Home sometimes prevented UIAutomator from reaching idle. Failed dumps and stale XML supplied no proof. Several selection/dismissal captures caught transitions: they establish observed header/action states, not settled checkbox fidelity or a new size/theme/RTL matrix. Earlier output matrices retain their [original revision and limits](2026-10-07-g4-candidate-device-review.md).

## Observations and acceptance limits

| Surface | Observed result | Boundary still open |
| --- | --- | --- |
| Mixed ZIP import, #35 | Four `.conf` entries contained valid alpha/bravo profiles, one invalid config and a duplicate alpha. Exactly two synthetic profiles imported, both DOWN. Native feedback read “Imported 2 of 4 tunnels”. Personal config hashes remained unchanged. | Other providers, inaccessible/unreadable streams and broader failure/recovery behavior. |
| QR image import, #35 | Offline ZXing decoding matched the synthetic config exactly. Native import of the valid PNG opened naming and saved one uniquely named DOWN profile. A blank PNG produced “Unable to import tunnel: QR code not found in image” and created no additional profile. | Camera decoding, permission denial/recovery and camera absence. Image decoding does not prove camera behavior. |
| Manual editor/peer, #35 | An unsaved blank draft accepted an added peer. With the numeric keyboard open, the final keepalive field and Add peer action scrolled above the IME; header Save remained reachable. The draft was cancelled without saving. | Other keyboard/output configurations, peer value copying, independent live RX/TX equality, missing-row clearing and TalkBack. |
| Home selection/deletion, #36 | Long press entered selection. Deselecting showed a zero-count header with no delete action; Select all showed four selected; Back restored the normal header. Only the three synthetic profiles were then selected. Confirmation names matched that exact set. Cancel was exercised before reopening and confirming deletion; all three test profiles were removed. | Settled selection visuals, identity preservation through rename/insertion/recreation, cancellation after deletion admission, partial failures and TalkBack. Cancellation before confirmation is not admitted-operation cancellation. |
| Settings switch, #34 | “Allow remote control apps” was toggled, persisted through landscape recreation and UI reopening, then restored to its original TRUE value. | This earlier run lacked the later exclusive handoff. Other switches, callbacks, TalkBack and conditional root/backend/admin paths remain unproved. A shell launch of the non-exported SettingsActivity was correctly rejected; navigation used Home. |
| Quick tile request, #34 | Request activation removed its Settings row; the tile was present afterward. | Initial tile state was not captured. Neither a newly added tile nor request cancellation is established; the tile was left intact. |
| Export, #28 | A native authentication prompt was observed. Export completed before Back during the interrupted run. The output had been absent beforehand; only the newly created app-owned output and its unique MediaStore row were removed after identity/hash checks. Both absences were verified. No archive contents were read or uploaded. | Export cancellation, authentication cause, archive-content correctness and production acquisition/write/cancellation cleanup remain unproved. Manual cleanup does not establish those application paths. |

Standard WireGuard configuration has no defined tunnel-name field. This app's text/QR import validates the config and opens `ConfigNamingDialogFragment`; `.conf` and ZIP imports derive names from their filenames. The synthetic QR therefore correctly requested a name. No new QR format or naming behavior was added.

## Preservation and restoration

The synthetic profiles contained no endpoint, ListenPort or default routes and stayed DOWN. The single original personal tunnel stayed ON; this session never toggled it or edited its config. After synthetic deletion, a fresh private archive contained exactly the original three app files, with identical hashes and no extra files. No stale archive was restored over live data. A settled final Home capture showed one configured active tunnel; the VPN service was still present. This proves preservation and observed running state, not a new independent connectivity test.

The three uploaded Downloads fixtures were removed only after their on-device hashes matched the retained generated files. PNG destinations had been checked absent before upload. ZIP destination absence was not recorded before upload, so preservation of a hypothetical pre-existing file at that same test path cannot be established. The session-owned remote screenshot/hierarchy files were also removed. Private configs, identifiers, app archives, screenshots and raw device reports remain in ignored `.artifacts/`.

Only screen-off timeout differed from the fresh controlled-session settings snapshot at cleanup; its original 30,000 ms value was restored. All recorded global/system/secure settings then matched that snapshot, including rotation, font scale, animations, existing accessibility services and the tile list. App locale also matched. TalkBack was installed but was not enabled or claimed as tested.

## Documentation and next boundary

Development evidence, QA, the active plan and UI roadmap link this scoped result while keeping unresolved physical gates visible. No backend changelog entry, architecture decision, README, operations, AGENTS.md or template change is needed: runtime behavior, ownership, user instructions and contribution policy are unchanged. No issue is closed from this partial record.

The next device session needs a coordinated physical camera target and actual TalkBack traversal/actions/announcements, then remaining peer, provider and Settings/export controls. Export uses all saved tunnels; another authentication/export test requires coordinated approval and must preserve personal output. Provider failure or admitted-deletion failure tests should use an isolated synthetic environment where needed rather than risking the working personal tunnel. Clean network, controlled energy and paired optimization proof remain separate G4 gates.
