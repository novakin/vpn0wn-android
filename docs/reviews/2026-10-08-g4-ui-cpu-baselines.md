# G4 presentation CPU baselines

Last updated: 2026-10-10 (Europe/Paris).

## Scope and authorization

Partial evidence for [G4/#52](https://github.com/novakin/vpn0wn-android/issues/52) and the baseline decision for [#47](https://github.com/novakin/vpn0wn-android/issues/47). The maintainer made Pixel 10a available and unlocked for the authorized device work. This session measures the unchanged baseline after the separate [#48 statistics repair](https://github.com/novakin/vpn0wn-android/pull/78); it implements no #47 optimization.

The installation below changes the test build, not product source or the saved profile. No profile, ListenPort, endpoint, MTU, routes, DNS or keepalive is edited. No transfer load, server operation or new protocol experiment is part of these presentation windows. Private archives, device identifiers, profiles, raw system reports, screenshots and traces remain in ignored local evidence.

## Build and installation provenance

Baseline source: `f4a4b147568e6aaa3c82a4307aa6690977e966bd`. Authorized [CI run 37745395747](https://github.com/novakin/vpn0wn-android/actions/runs/37745395747) passed 279 tests with zero failures/errors/skips, zero Detekt findings, native checks and debug APK assembly. This is a debug artifact, not a release.

| Artifact | SHA256 and verification |
| --- | --- |
| Original CI APK | `6c36b7a2f2a789705b3ebdc18f3453f519b78cddce4c01e62e46d002da21852f`; retained with source/run provenance. |
| Locally signed installed baseline | `5bcb432c58d88bf6a7f3d8ac9891566a047061182addd8524bec6ae9502a71bb`; signed with the existing matching debug signer. All 1,029 payload entries match the CI APK after excluding actual signing entries. Installation used `adb install -r`; installed-APK pullback matches. |
| Retained preceding APK | `b654c89f15d3e53b9b319ec52e7bbedb97f6714fc3ef5fe50c6b90eb30e3eaac`, source `68c54bae`; [earlier protocol/device evidence](2026-10-07-protocol-preflight-experiment.md#authorized-local-apk-and-pixel-checks--2026-10-07) keeps its original scope. |

The old APK and a fresh private app-data archive were retained before replacement. No uninstall or data clear occurred; the saved personal profile is unchanged at the installation boundary. Final preservation and device restoration are recorded below; the verified post-#48 test APK remains installed, with the preceding APK retained locally.

The earlier APK predates #48. These windows use the post-repair baseline to avoid attributing #48's changed statistics ownership to a later #47 candidate. A future paired candidate must retain the same unrelated source, profile, test conditions and workload; earlier-build observations are not its control.

## Method

Pixel 10a, Android 16/API 36, ARM64 userspace backend, one saved personal profile, cellular with Wi-Fi OFF, portrait, dark app palette and screen on. Android reports AC charging. These presentation windows used timed holds without the session's own in-hold sampling; they are not a synthetic traffic fixture or controlled idle-energy baseline. Existing background traffic is not isolated.

Three approximately 60-second windows per scenario completed in varied order: visible Home, single-pane Detail covering Home, Settings and app background. Accepted collection order was Home/Detail/Settings/background, then Settings/background, then background/Home/Detail/Home/Detail/Settings. Two intervening files were excluded after screenshot review: `detail-2` actually showed Home and `home-2` showed the launcher/background. The original files remain retained; the local window map records their exclusion rather than rewriting their labels or counting them as accepted repeats.

Screenshots and foreground/thermal snapshots bracket each window; this session's own script ran no screenshot, UI hierarchy dump or traffic probe inside the timed interval. Later windows also retain interface-counter snapshots for traffic context. An independent ultra review verified all twelve accepted before/after screenshot pairs. A boundary screenshot establishes the observed presentation; attachment or resumed state alone does not establish visibility. A foreground identity change, process restart or regressed counter rejects a window. The later disclosure of external ADB use concerned energy testing; concurrency during these earlier charging CPU windows is unknown. It neither establishes exclusive device use here nor retroactively proves that a specific CPU observation was affected.

CPU time is the process user/system tick delta divided by the observed `CLK_TCK=100`. Elapsed time uses the host monotonic stopwatch surrounding the counter reads. Mean single-core-equivalent utilization is `100 × CPU seconds / elapsed seconds`; it is not a percentage of the phone's total CPU capacity. PID and process-start counters identify a consistent process. The first Home and Detail windows retain computed CPU/elapsed results but lack raw tick endpoints and interface-counter snapshots; later windows retain those endpoints. Keep this evidence difference explicit.

Actual visible animation was confirmed at the phone's native 1080×2424 output. An initial comparison used an invalid resized coordinate and is excluded as animation proof. A temporary animator scale of 1.0 was removed to restore its original absent setting before the baseline. UIAutomator sometimes cannot obtain an idle animated hierarchy; a failed dump or stale XML supplies no acceptance evidence. Current screenshots own those boundary checks.

Whole-process CPU includes Android and native tunnel work. Scenario differences cannot isolate polling, a particular animator, crypto or another limiting owner. Interface counters include unrelated traffic. `gfxinfo` supplies cumulative or rolling frame context, not precise per-window callback counts or a hidden-view ownership measurement. The [source investigation](2026-10-07-performance-source-investigation.md) retains its separate synthetic lifecycle/animator evidence.

## Process CPU results

All twelve accepted windows lasted 60.18–60.35 seconds. Each row contains three repeats on the same build. Thermal-service status was 0 in the retained snapshots; this is not exhaustive hardware thermal or frequency evidence.

| Scenario | Baseline repeats | Median CPU seconds | Min–max CPU seconds | Median mean utilization |
| --- | ---: | ---: | ---: | ---: |
| Visible Home | 3 | 13.93 | 13.61–14.22 | 23.12% |
| Detail covering Home | 3 | 18.94 | 18.91–20.03 | 31.47% |
| Settings | 3 | 2.17 | 2.03–2.19 | 3.60% |
| App background | 3 | 0.11 | 0.10–0.14 | 0.18% |

Foreground presentation scenarios consumed more process CPU than app background in these windows. Detail exceeds Home here, but different visible surfaces and whole-process counters cannot attribute the difference to covered Home or identify how much CPU #47 could remove. These are within-build baseline observations, not a candidate comparison or energy result.

## Separate scheduling traces

One nominal 20-second Perfetto capture per scenario recorded `sched_switch`, `sched_waking`, process lifetime and graphics/view context. Trace bounds were 19.899 seconds for Home, 19.915 for Detail, 19.747 for Settings and 19.885 for background. These instrumented windows are separate from the process-counter repeats; do not pool their scheduled-thread totals with the process-counter medians.

| Scenario | Main scheduled CPU seconds | RenderThread scheduled CPU seconds | Other app/native scheduled CPU seconds |
| --- | ---: | ---: | ---: |
| Visible Home | 1.4755 | 3.0008 | 1.2254 |
| Detail covering Home | 1.9670 | 4.6155 | 1.1731 |
| Settings | 0.9224 | 0 recorded | 0.0201 |
| App background | 0 recorded | 0 recorded | 0.0680 |

Zero recorded means no positive scheduled slice in that queried group, not proof that a thread never existed. Main identifies the app's process-main thread; RenderThread identifies threads with that name, and the remaining group includes all other app-owned threads. The other-thread activity was largely graphics Binder/Mali completion context; the group is not an attribution to WireGuard. RenderThread CPU is not GPU busy time.

The runnable-state query counted states `R`/`R+` with a recorded waker: main transitions were 2,240/2,457/1,184 in Home/Detail/Settings, RenderThread transitions 1,783/1,232 in Home/Detail, and other-thread transitions 10,821/10,869/22/117 in Home/Detail/Settings/background. These are scheduler transitions, not hardware suspend wakeups, unique app requests or animator callback counts. The trace query returned no nonzero `error` or `data_loss` statistics; this scoped check does not establish every trace's completeness.

The captures support graphics/presentation work as a profiling direction. They do not isolate hidden callbacks or replace controlled energy, connectivity or accessibility proof.

## Preservation and final restoration

The immediate post-install archive contains the same three files as the fresh pre-install archive, all byte-for-byte identical. At the CPU checkpoint, the personal tunnel configuration remains byte-for-byte unchanged. The other two files changed: Android's runtime profile-install marker and the DataStore preferences file. No archived file was added or removed.

The CPU checkpoint's preference map preserves all five existing entries and identifies exactly one added entry, `enabled_configs`, owned by `UserKnobs.runningTunnels`. It records the active-tunnel running intent; it is not an edit to the saved configuration or an unrelated personal preference. That intermediate checkpoint does not establish whole-archive byte identity or completed running-state restoration. The changed Android runtime marker is classified separately and is not overwritten from the older archive.

After authorized final cleanup, the saved configuration and DataStore preferences file are byte-for-byte identical to their fresh pre-install archive entries. Both preference maps contain the same five entries with zero deltas; the temporary running-intent entry is gone. The three-file inventory is unchanged, with only Android's runtime profile-install marker differing. No older archive was restored over current data. The installed APK SHA256 still matches the verified baseline. An apparent cropped ListenPort reading was incorrect; persisted configuration and hash comparison found no port/profile change.

Measurements stopped following the energy-session disclosure of another ADB controller. The maintainer then confirmed the other chat stopped, requested restoration and supplied unlocked USB access. The test app's VPN is OFF with no GoBackend VPN service in the debug package, and the launcher is restored. USB readback verifies Wi-Fi and wireless debugging OFF, forced idle cleared and no battery simulation. Automatic brightness is restored and applied, and timeout is 30,000ms. Initial display restoration matched all twelve saved checked values; later final readback matched eleven, with only the dynamically adjusted numeric brightness value differing and deliberately preserved. No other saved setting mismatch remained.

A Wi-Fi-disable USB command stalled and was deliberately terminated; later independent USB readback verified the final Wi-Fi setting/runtime OFF and wireless debugging OFF. The failed command and successful recovery are recorded separately. The two remaining owned capture files were removed and their absence checked; preceding trace cleanup retains its readback proof. The [energy record](2026-10-08-g4-energy-pilots.md#preservation-and-final-restoration) owns the shared handoff details. All phone operations finished and the device session was released; retained private evidence and both APKs remain local.

## Interpretation and remaining gates

No energy baseline, numeric optimization target, #47 implementation, candidate comparison or battery/speed/DPI gain is delivered here. Charging can support CPU/scheduling diagnosis but cannot establish reduced battery drain. `dumpsys battery unplug` would only alter reported charging state; it does not remove physical power.

The later [physically unplugged energy pilots](2026-10-08-g4-energy-pilots.md) use wireless ADB and a Wi-Fi/router-VPN path. External ADB use was confirmed during that work; completed endpoints remain uncontrolled diagnostics, a changed viewport is not a fixed-view repeat, and the first screen-off window was interrupted. They retain their separate method, exclusions and handoff limits; do not pool them with these cellular/charging CPU windows or infer an optimization from either record.

#47 still requires controlled unplugged energy baselines, measured variability and frozen targets/guardrails before implementation, followed by the required paired candidate proof. Screen-off/hardware-suspend behavior, supported two-pane presentation, allocations, explicit statistics/animator counts, connectivity and accessibility guardrails are separate uncompleted boundaries. Ordinary Pixel rotation does not establish the `sw600dp` two-pane layout. #28/#34/#35/#36 and #52 retain their outstanding physical/provider/peer/accessibility acceptance.

## Documentation impact and review

This record and links from [QA](../qa-test-plan.md#transfer-speed-and-battery-validation) and the [active plan](../improvement-plan.md#active-enhancement-and-performance-goal) document partial device evidence. Runtime behavior, architecture and setup are unchanged; the focused measurement-ownership guidance lives in QA, with no agent/runtime guide change. Historical tests, grades and network measurements retain their original source/date/limits. Focused documentation validation covers the four changed guides' local links/fragments, dates, fences and whitespace. The PR acceptance record owns final independent ultra review and actual publication/merge state; the APK's CI result does not substitute for that review.
