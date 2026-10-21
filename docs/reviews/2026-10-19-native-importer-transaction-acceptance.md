# Current-importer transaction acceptance — #71

Last updated: 2026-10-20 (Europe/Paris).

Scope: the current production importer on one fresh, owned API36 x86_64 emulator. Related to [#71](https://github.com/novakin/vpn0wn-android/issues/71); the issue remains open. Independent ultra review accepts the three exercise cases, same-boot cold-process readback and eleven cleanup checks. Runtime and offline checks occurred on 2026-10-19; review prose was finalized on 2026-10-20 without another runtime trial.

## Revision and environment

Frozen source is `ad03dda6ad0023b151ec42f856efb755076b89bb`, tree `a78485cab54b89cc69b412496215aed2f8a19d7e`. UI, tunnel and maintained third-party source match integration base `1418197613264d120b6a432d159bdf2bb1371c1f`. The isolated debug overlay adds instrumentation and x86_64 selection; production Application, manifest, TunnelManager, PreferencesDataStore factory and native package identity remain intact. This is a test APK, not a release artifact.

APK SHA256: `fade5ab95ac2038727f26189ab1d7a7abf1796fe357a36f619a46f7819df3860`. Package `com.vpn0wn.android.debug` was initially absent from owned guest `Vpn0wn_Import71_API36_A3`. Instrumentation runs under that app's ordinary UID; host shell inventory supplies separate sampled interface/cleanup evidence. No personal installation, phone or live server is used.

Independent input/artifact review checked seven harness and 23 production-owner bindings, installed production owners and native libraries. Compared with the diagnostic APK, the corrected instrumentation removes only unsupported app-UID `/sys/class/net` enumeration. Native/backend emptiness and model DOWN assertions remain. That null enumeration identifies the harness failure; it does not establish its platform policy cause.

## Executed controls

The production archive writer/reader generates a synthetic archive. `TunnelManager.importAppData` seeds a populated destination, including an existing tunnel, unrelated fleet record, last-used selection and typed preferences. A temporary ConfigStore decorator and the manager's existing operation lock control these cases; the real preferences store and successful file writes remain production-owned.

| Case | Observed result |
| --- | --- |
| Second ConfigStore create fails | Injected IOException precedes the second file creation. The first new file rolls back; prior destination, preferences and unrelated records remain. |
| Caller cancels before admission | Cancellation while awaiting the operation lock performs zero creates. |
| Caller cancels after admission | After the first real file creation, admitted import settles its commit and DOWN model state before the cancelled caller settles. Retry is idempotent. |
| Cold-process readback | Force-stop and a new process retain the same boot/UID and the recorded canonical state, file metadata, full typed preference map and fleet/selection state. |

The three exercise cases and separate cold-readback phase pass. The controller exits 0 after 211,000 ms. All eleven cleanup checks pass: private sandbox removal; package, process and service absence; sampled no-TUN; baseline and reverse restoration; normal owned-guest shutdown; shared-owner and source preservation. No cleanup error remains. Shared ADB is preserved.

The corrected build and artifact audit exit 0. Existing independent controller evidence retains its 24 native-result and nine shutdown controls. Runtime acceptance is bound to the corrected APK and separate runtime manifest; earlier builds do not establish this run's outcome.

## Retained evidence and limits

Ignored evidence owner: `.artifacts/continuation-20261019-import71-next`. Build/audit output is `runs/build-current-a4`; actual runtime is `guests/Vpn0wn_Import71_API36_A3`. Compile manifest SHA256 is `a7b400e0a34a7dfa4e2fd92994bd1b22b24b32a96f3faa273945443cc00269ce`; runtime manifest SHA256 is `23681f32fcd03b88f74f80c77b4fb7fb3d6e5a879613a2c7e8dae2f1666ff40e`. Independent actual-review receipt SHA256: `c80c36b0ade116d0a24956eee6f4589d16451ab722477af135ae1fd15a4246b4`. All 254 original run files remain byte-identical; all 104 recorded commands settled without timeout. Settled command log: `20261019-213645-969-52fe509c01224450a7d68f5b6f9841e0.log`.

A1 failed before import cases. A2 added bounded diagnostic frames and identified the unsupported enumeration; both failed runs retain their original results and eleven passing cleanup checks. A3 does not replace or relabel them.

This is not a final FileStorage commit-failure test, native document-picker test, historical export compatibility run or cross-app/release migration. It establishes neither active-tunnel preservation nor VPN traffic, OS tile/permission setup, kill-during-commit or abrupt power-loss safety, physical/OEM coverage or full #71 acceptance. Stored tile preferences are ordinary data here; no native tile is installed. Product source and operational behavior are unchanged.
