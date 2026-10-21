# Native Android wizard Detect acceptance

Last updated: 2026-10-18 (Europe/Paris).

## Scope and verdict

**VERIFIED, dynamic, within one isolated API28 positive password-Detect cohort.** A5 passes six native controls, instrumentation completion code `-1`, six complementary host facts, six cleanup facts and five shutdown facts. A different independent ultra reviewer verified the original evidence and both original pixels; no blocking finding remains in this scope. These are observers of one bounded run, not twelve independent transport scenarios. Related to [#143](https://github.com/novakin/vpn0wn-android/issues/143), which remains open.

This cohort exercises the production `Application`, `MainActivity`, default Fragment/ViewModel/client/Socket path and real Android Keystore through an isolated debug package against the existing synthetic SSH peer. The peer returns fixed Detect JSON and verifies uploaded assets and command order. Production server scripts were not executed on a Linux/VPS host. The [wizard contract](../backend-contracts.md#read-only-ssh-detect-and-plan--143), [transport record](2026-10-18-native-ssh-acceptance.md) and [storage record](2026-10-15-native-ssh-credential-storage-acceptance.md) retain their separate source, artifact and acceptance boundaries. No product source changed for this acceptance record.

## Source, artifact and executed provenance

Runtime source: clean `ef8ec1bba16e29f5a8631b77841d122455e29142`, tree `b31062a457845bfc35174e5caff84be27321e338`. All 661 pinned source files, both initialized native gitlinks, eight harness inputs and the exact two-file compiled-source inventory retain matching before/after receipts. This frozen runtime source is distinct from the later documentation integration base.

The accepted V7 x86_64 APK has SHA256 `96fb9224bd5e857acc6349712f812d735f9d392c6727e69bf83225c0f3b2dfe6`, 19,028,482 bytes, package `local.vpn0wn.wizarddetectprobe.debug`. The actual static audit verifies v2 signing, minSdk24/targetSdk36, production Application/MainActivity and the private permission-protected VPN service, INTERNET only, no shared UID, one self-target instrumentation, production/runner DEX bodies, isolated native identity and all twelve packaged script bytes. Its bounded outer-class DEX inspection does not claim a separate nested-coroutine body audit. The host-only SSH fixture is excluded from the APK. The pulled installed APK matches the accepted bytes.

V8/A5 reuses that V7 Build/audit and APK unchanged. Its controller repair replaces the denied netlink interface query with checked pre/post `/sys/class/net` inventories. No new APK was produced. The A5 Peer Gradle task compiles/runs the synthetic host peer; it is not an APK Build or a full product test suite.

The fresh Google APIs Android 9/API28 x86_64 guest uses the retained revision-11 image. Exactly one controller invocation and one Peer invocation completed; their direct/outer exits and Peer Quiet/inner exit are zero. The Peer run is `20261018-230939-0cfaad`. All 80 external-command receipts have completion timestamps and their expected exits. Expected absence exits retain their explicit meaning. Retained tool-terminal fields are authored observations corroborated by direct/native/host artifacts, not independently signed transcripts.

## Native and host observations

The original native stdout, result and accepted-result payloads agree on `passed:true`, exactly six true checks and instrumentation code `-1`. Native command exit zero alone was not acceptance.

| Native check | Observed boundary |
| --- | --- |
| `productionApplicationAndDefaultFragmentModel` | Instrumentation verifies the production Application and launches MainActivity; native Home Servers/Add actions reach the Fragment's default ViewModel and application-owned store. |
| `actualNativeFieldsActionBusyAndValidatedDetect` | Native SSH/password fields and Continue execute the actual client; busy is observed, then supported validated Detect settles without a mutation pending. |
| `realFleetPinAndKeystorePasswordEquality` | Real fleet persistence, saved SSH identity and authenticated host pin match; the production store decrypts the exact synthetic password through Android Keystore. |
| `privateCiphertextAndNoPlaintextInSettings` | The private no-backup credential file exists as ciphertext; neither it nor the settings file contains the synthetic password marker. |
| `nativeDetectOutputCaptured` | Native Detect and saved-access targets render; Generate plan is visible/enabled and two original screenshots are retained. No Plan click occurs. |
| `activityRetiredAndNoJschReaderThread` | The Activity is destroyed after completion and no sampled thread stack remains in JSch. |

The separate Peer result and host acceptance agree on `passed:true` and these six true facts:

| Host check | Observed boundary |
| --- | --- |
| `exactCommandOrderAndAllowlist` | Exact temporary-directory creation, private-directory setup, Detect command and owned cleanup order; other commands are rejected. |
| `allTwelveAssetBytesAnd0700` | All twelve uploaded production assets match their expected bytes and the owned directory has mode 0700. |
| `ownedRemoteDirectoryAbsent` | The synthetic peer's operation-owned remote directory is absent after cleanup. |
| `singlePinnedPasswordSession` | Exactly one password authentication, no client-key authentication and a drained authenticated session. Native pin equality supplies the complementary pin observation. |
| `noPrivilegedOrMutationCommand` | No privileged/install/user-management command appears in the admitted command sequence. |
| `allSessionsAndWorkersStopped` | The peer closes and its sessions/workers stop before the host receipt is published. |

These checks prove Android SSH/default-owner/credential integration against fixed synthetic responses. They do not prove that the uploaded Detect script successfully observes a real server.

## Output, preservation and cleanup

The pre-install and post-native `ls -1 /sys/class/net/` commands both complete successfully. Their identical nonempty inventories contain seven valid unique names, exact lowercase `lo` and no case-insensitive `tun` plus digits name. The post-native service query returns `(nothing)` with no matching probe VPN service. The successful PID-scoped diagnostic query contains no synthetic password marker. These are the retained observations for this guest, not universal Android interface or diagnostic guarantees.

Both unequal original PNGs are 1080×1920. A different independent ultra reviewer viewed both at their actual resolution and was **satisfied with the selected English portrait dark-theme targets**: readable Detect metadata/fingerprint, complete supported-server warning, complete encrypted-save feedback and full Generate plan action. The second capture intentionally scrolls to the saved-access target; the preceding partly clipped label is its scroll boundary. The reviewer applied Interface Design, Design System, Frontend Design and UI/UX Pro Max to the existing approved direction, font/token roles, hierarchy, state and legibility. No product correction was needed in these two originals.

| Private original | Bytes | SHA256 |
| --- | ---: | --- |
| `wizard-detect.png` | 127,553 | `12e7b77621a0f60adfc6349d3d3e802cbe84c711ea8bb1bcb2e98ce3642dc1fc` |
| `wizard-detect-saved.png` | 125,837 | `1472a097737ea15fba0256ee3a8e0abbb3911b12921e8a4f63f6581fff805c35` |

All six cleanup facts pass: owned package/process absent, unchanged boot/package/settings baselines and restored reverse mappings. All five shutdown facts pass: owned backend/launcher/listeners absent, shared owners preserved and owned userdata retained. The terminal owner readback records all ten owned process identities and the owned serial/listeners absent, while preserving the shared ADB identity. The 6 GiB userdata image remains retained; this review did not rehash that large image. Final `errors` is empty and `failureClass` is null. These are original terminal observations, not fresh runtime probes performed for this documentation change.

## Preserved failures and terminal admission

A4 remains **FAILED**: its whole controller stopped at `ip -o link show` with `Permission denied`, despite six positive native checks, six host facts and two captured PNGs. Those positives do not substitute for its missing controller postconditions. V8's controller-only sysfs repair is accepted through fresh A5 observations; it does not turn A4 into a pass. Earlier V5/A2 and V6/A3 failures retain their original scopes, with native frames unconfirmed. No later success identifies their unmeasured native cause.

Three offline evidence-formatting/readback failures remain retained: helper `H` resolved to `Get-History` with its original exit uncaptured; member-collection `Properties.Count` caused exit 1; a Peer handoff was initially misclassified as an external-command receipt, causing exit 1. Corrected helper/count/schema handling changed no runtime payload and repeated no controller, Peer or native operation.

The original 312-file success index retains its creation-time pending-review/consumed-execution status unchanged. The later independent report supplies final scoped acceptance. A separate terminal-closure record restores exact original false controller/producer admission bytes while preserving executed/consumed true copies. All fourteen restored input rows, unchanged controller/commands/wrapper and unchanged original success index are verified. Closure is additional evidence; it rewrites no run and admits no further operation.

Private retained evidence root: `.artifacts/continuation-20261018/`; actual guest/run evidence is under `native-wizard-detect-prep-v8/`. Raw inventories, credentials, screenshots and host reports stay outside source distribution.

| Retained record | SHA256 |
| --- | --- |
| `wizard-detect-v8-a5-success-runtime-evidence-index.json` — 312 original files / 80 command receipts | `6ac144c18519160def29573ab334caf300acde02cc2142fc362c542d2519b28f` |
| `wizard-detect-v8-a5-runtime-independent-ultra-review.md` — final evidence/pixel verdict | `1ea8c73431c9198743845349affba8e75dfb2ff48d11f937435118a4705d752e` |
| `wizard-detect-v8-a5-terminal-admission-closure.json` — false canonical gates / preserved consumed copies | `7348518d7af0f8a5eb25374d0ae0233d73df3541a7b41e105046fead4bec8d95` |
| `wizard-detect-v7-a4-failed-runtime-evidence-index.json` — unchanged failed A4 scope | `3ce2053e7f26170c49032a98541a288818903748f2e8596e1aa3b6fce5f300a5` |

## Remaining acceptance and delivery

This positive cohort adds no failure/cancellation/recreation or saved-access reuse/removal workflow acceptance, client-key authentication, wider algorithm/provider/API/OEM/physical or network coverage, actual server-script execution, Plan/install/create/profile/restore workflow, VPN traffic/routing/lockdown/protection, release or production acceptance. Still images establish neither busy animation nor touch/focus/IME/TalkBack behavior. The two originals do not cover French, RTL, enlarged text, short landscape, other sizes or a complete theme matrix. Marker absence in the enumerated files/PID diagnostics does not establish universal secret exclusion or complete memory erasure.

Overall C11/C12 and #143 remain **PARTIALLY_ASSESSED**. Earlier generic transport, storage and profile trials keep their own source/artifact scopes. No disposable VPS was selected and no live-server or release/deployment result follows. Final documentation-head independent review, CI, publication and merge remain the delivering PR's gates.

Documentation impact is limited to this historical acceptance record and current capability/verification pointers. No behavior, API/schema, ownership, operational policy, build setup, agent instruction or template changes require edits to architecture, development, AGENTS.md or contribution templates.
