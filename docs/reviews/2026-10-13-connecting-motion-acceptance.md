# Connecting motion — scoped Android acceptance

Last updated: 2026-10-13 (Europe/Paris).

## Scope and artifact

[#174](https://github.com/novakin/vpn0wn-android/issues/174) refines only the warning-colored CONNECTING motion in the existing `TunnelSignalView` and `TunnelThroughputView`. The 1.8-second halfway sweep fades during the first and last fifth of each loop; disabled animation retains a visible stationary warning dot at one quarter of the full rail. Geometry, font roles, state mapping and connected blue motion are unchanged. The read-only prototype's original 900ms motion remains historical reference, not the current cadence.

The isolated debug artifact `local.vpn0wn.recoveryprobe.debug` was built from `e5d53cbb22982a3d13b2551e1be6b9dafe7afd7d`, source tree `84cc308888d6b6f2f7aea9c1c4e496d17ea62acd`. Artifact receipt `9bb5496512f8f13939b2d9400abaff299a72e839c2359d8dd005267a6c0bded0` records installed APK SHA256 `b3208e4666ec01eb77a7e4308a31754517e0f2606c246b780a7ee9c333929c6f` and signing certificate `23d36814e694f8c06b9df2149f7a211d7c0ef0c10c7ac0154610a16cc763c580`. Production `ui/src/main` tree `b339595a64f281e2a44bdd27e0297e9c3d1c8ace` matches source checkpoint `930a1e6304dac99175ddb5ccc336f32c7940c0c0`; this is UI equivalence, not a new APK execution or whole-source equivalence claim.

Exactly one install request was issued. A strict install-output check failed; exact installed APK/UID readback reconciled that request without reinstalling. The disposable app UID and its SDK sandbox were admitted before synthetic activation. No personal profile supplied the trial state.

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

## Remaining acceptance and documentation impact

Light theme, landscape, large text, RTL, ERROR and authenticated blue UP output, complete transitions/recreation, accessibility and other devices remain separate controls. No shipping/release-package, cross-device, backend, authenticated-traffic or complete #174 acceptance is claimed. [QA](../qa-test-plan.md#connecting-motion-controls--174) owns the remaining matrix; [the UI roadmap](../ui-redesign-roadmap.md#done) records the source refinement and scoped native evidence. Wider Home acceptance remains #80; covered-view scheduling and battery policy remain #47.

Only the UI roadmap, QA guidance and this acceptance record need updates. AGENTS.md, architecture, backend guides, setup and workflow documentation remain accurate: this trial changes no policy, owner, protocol, persisted contract or operator procedure.
