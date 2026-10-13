# Connecting motion — scoped Android acceptance

Last updated: 2026-10-13 (Europe/Paris).

## Scope and artifact

[#174](https://github.com/novakin/vpn0wn-android/issues/174) refines only the warning-colored CONNECTING motion in the existing `TunnelSignalView` and `TunnelThroughputView`. The 1.8-second halfway sweep fades during the first and last fifth of each loop; disabled animation retains a visible stationary warning dot at one quarter of the full rail. Geometry, font roles, state mapping and connected blue motion are unchanged. The read-only prototype's original 900ms motion remains historical reference, not the current cadence.

The isolated debug artifact `local.vpn0wn.recoveryprobe.debug` was built from `e5d53cbb22982a3d13b2551e1be6b9dafe7afd7d`, source tree `84cc308888d6b6f2f7aea9c1c4e496d17ea62acd`. Artifact receipt `9bb5496512f8f13939b2d9400abaff299a72e839c2359d8dd005267a6c0bded0` records installed APK SHA256 `b3208e4666ec01eb77a7e4308a31754517e0f2606c246b780a7ee9c333929c6f` and signing certificate `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. Production `ui/src/main` tree `b339595a64f281e2a44bdd27e0297e9c3d1c8ace` matches source checkpoint `930a1e6304dac99175ddb5ccc336f32c7940c0c0`; this is UI equivalence, not a new APK execution or whole-source equivalence claim.

The original four-clip trial issued exactly one install request. A strict install-output check failed; exact installed APK/UID readback reconciled that request without reinstalling. The disposable app UID and its SDK sandbox were admitted before synthetic activation. No personal profile supplied the trial state.

At this 2026-10-13 checkpoint, [PR #175](https://github.com/novakin/vpn0wn-android/pull/175) is draft and #174 remains open. The PR owns source review/CI, final-head acceptance and delivery; this record adds only the physical scope below. Independent ultra adjudication passes the four scoped clips, native capture/action bindings and sampled cleanup.

## Recorded native output

Four bounded MP4 clips, each longer than six seconds, cover Home and Detail at effective animator scales 1 and 0. Actual output is dark portrait, 1080×2340, normal text, French LTR. Before/after XML for each recording gives eight captured hierarchies, all showing CONNECTING. The recordings establish that presentation state over their captured boundaries; they establish no authentication or reachability.

| Animator scale | Home and Detail observation |
| --- | --- |
| 1 | At least three complete approximately 1.8-second loops show the warning dot's halfway sweep and smooth fade around the reset, without a visible midpoint-to-start flash. |
| 0 | The warning dot remains visible, opaque and stationary at one quarter of the full rail. |

Visual inspection covers these four recordings only. Screen changes between Home and Detail do not establish the full UP/ERROR/DOWN transition matrix. No performance, battery or backend result follows from the smoother presentation.

Independent runtime receipt `bcfd7bb9602b7d25614e3d83927bc31f640f71c3689c027beac3a0ac57d70af6` passes 23 checks over 212 joined commands, including 14 native taps, 38 raw XML/PNG bindings and 694 pinned evidence files. It verifies source/artifact identity, motion and fades at scale 1, the stationary warning indication at scale 0 and independently recomputed cleanup within the stated scope.

## Restoration and retained failures

Exact-label Android VPN Settings Disconnect restores the captured original prepared legacy VPN owner. The remaining profile was verified synthetic, then the disposable package was uninstalled. Native UI profile deletion was not exercised.

Cleanup reports all nine compared fields true: captured settings, prepared VPN owner package/type, absence of TUNs, top-level `.conf` hashes in captured personal installations, kernel link/rule inventories, root policies, original foreground activity/task, owned-package absence and owned-path absence. These checks use the actual same-session baseline and final readbacks. They establish sampled restoration of those surfaces; inactive cached VPN owner/capability metadata is not byte-identical, and the full personal store, all routes and continuous device health are not audited. The phone was restored and released after cleanup.

Retained host-runner execution outputs record failures in standard-library name shadowing, CRLF handling, scratch-directory write permission, strict install-output validation and console Unicode output. Those transcribed outputs remain separate from pinned raw Android evidence; no complete raw host-stream audit is claimed. The reconciled single install and successful native evidence do not erase those failures. Recordings, XML, APKs and private execution evidence remain ignored under the trial artifacts; no secret-bearing profiles or host reports enter this record.

## Failed follow-up — 2026-10-13

A separate fresh session installed the same exact APK once and imported its owned synthetic profile. Its requested dark-landscape, 2× Arabic RTL, animator-scale-1 cell failed before any native `screenrecord` command. Zero MP4 files and zero new native motion acceptance resulted; the original four clips and their installation/cleanup receipts retain their own scope.

The preserved `dark-landscape-large-rtl-s1-start-private.xml` shows owned Home with two overlapping `tunnel_switch` nodes at identical bounds. The original resolver rejected both with `Unique owned switch unavailable`; the control was duplicated in the hierarchy, not absent. The two row subtrees match after excluding positional indices. The visible `tunnel_signal` height was only 8px, so accepting the target alone would not establish complete motion framing. Duplicate origin remains unproved; a source-consistent RecyclerView overlap is not a measured cause or a confirmed duplicate-data defect.

The user reported no phone interaction. A sampled app process remained alive, and the nonempty raw crash buffer contained no identified owned fatal block. These samples do not explain the duplicate hierarchy or later foreground/VPN changes. Before cleanup, readback already showed the original prepared legacy owner, inactive VPN type `-1` and no TUN. The exact Disconnect guard separately remained FAILED because its required dialog was absent; no successful Disconnect tap or callback is established, and restoration is not attributed to that guard.

Independent receipt `4cf3b89eacab721918c2e78ad7350972b9d53da035814adedfe7af2ed138aa76` passes precise failure adjudication and sampled restoration: 19 checks over 180 joined command clients, six native taps, 17 raw hierarchy bindings and 607 pinned evidence files. All 11 sampled cleanup comparisons pass, including original night mode, the isolated app locale and package/path absence. The prior preservation limits remain: prepared-owner package/type and captured settings/configuration surfaces do not establish byte-identical cached VPN metadata, a complete store/route audit or continuous device health. The phone was restored and released; no further ADB work is scheduled by this record.

The later host-only repair resolves duplicate targets only when all attributes except positional index match and checks owned foreground plus the full native diagram height. A required drag uses the current Home app bar or native Detail scroll owner. Frame extraction and Back selection reuse the same strict resolver. Fourteen injected tests pass, and independent ultra host preflight passes in receipt `d0bbda4fe32e2e6e61f4228ec4fdb0ec7e24262ade1424b676e1401ff01ee3b5`. It changes no app code and supplies no new native motion or framing acceptance. A future trial still needs bounded capture preparation and actual native framing of the complete diagram. Original failed captures and commands remain FAILED and preserved.

## Remaining acceptance and documentation impact

Light theme, landscape, large text, RTL, ERROR and authenticated blue UP output, complete transitions/recreation, accessibility and other devices remain separate controls. No shipping/release-package, cross-device, backend, authenticated-traffic or complete #174 acceptance is claimed. [QA](../qa-test-plan.md#connecting-motion-controls--174) owns the remaining matrix; [the UI roadmap](../ui-redesign-roadmap.md#done) records the source refinement and scoped native evidence. Wider Home acceptance remains #80; covered-view scheduling and battery policy remain #47.

Only the UI roadmap, QA guidance and this acceptance record need updates. AGENTS.md, architecture, backend guides, setup and workflow documentation remain accurate: these trials and the host-only capture repair change no app policy, source geometry, app owner, protocol, backend behavior or persisted contract. Capture-specific guidance remains in QA and this record.
