# G4 unplugged energy pilots

Last updated: 2026-10-10 (Europe/Paris).

## Scope and authorization

Stopped method investigation for [G4/#52](https://github.com/novakin/vpn0wn-android/issues/52), separate from the [charging/cellular CPU record](2026-10-08-g4-ui-cpu-baselines.md). The maintainer authorized wireless-device setup and physically unplugged Pixel 10a for these pilots, then confirmed another chat was using ADB during energy testing. The extent and timing of that interference are unquantified. The maintainer subsequently confirmed the other chat stopped, requested restoration and supplied unlocked USB access. Final cleanup is verified below and the device session is released. The completed endpoints remain uncontrolled diagnostic observations; no #47 implementation, numeric target or candidate comparison is accepted.

The installed debug/userspace baseline remains source `f4a4b147568e6aaa3c82a4307aa6690977e966bd`, with verified APK/payload/install provenance in the [CPU record](2026-10-08-g4-ui-cpu-baselines.md#build-and-installation-provenance). One saved personal profile remains unchanged. No app code, protocol, ListenPort, endpoint, MTU, routes, DNS or keepalive is edited, and no benchmark server or transfer load is introduced. Natural background traffic is not isolated.

## Method and differing conditions

Pixel 10a, Android 16/API 36, physically unplugged and accessed through wireless ADB. Battery snapshots require AC/USB/wireless power OFF, reported discharging and no active battery simulation at both endpoints. This verifies the observed boundaries; it is not a calibrated power measurement or continuous charging-state monitor.

The path is Wi-Fi through the router VPN plus the phone VPN, with wireless debugging enabled. This differs from the earlier cellular/charging CPU windows and does not establish a clean direct Internet or carrier-DPI path. Foreground pilots use portrait/dark presentation, manual brightness value 8 and a temporary 600,000ms screen timeout. Settings are saved for restoration. The planned collection is three approximately 300-second windows each for visible Home and single-pane Detail covering Home, in varied order, and three approximately 600-second ordinary screen-off windows. No forced Doze, simulated unplugging or keepalive change is part of this method.

Display/focus/foreground/network/thermal snapshots and screenshots bracket foreground windows. The session's own measurement script performed no ADB sampling or generated traffic inside its timed hold; host progress output did not contact the phone. The later-confirmed external ADB activity means the overall windows cannot be called quiet or controlled. CPU deltas retain PID, process start, raw ticks and `CLK_TCK=100`. Battery elapsed time is measured between the midpoints of the before/after battery commands; the CPU interval uses its own counter-read timings. Do not equate those intervals or combine their readings with charging CPU medians.

Charge depletion is `before charge counter − after charge counter`, in µAh. The reported approximate whole-device mWh is that delta multiplied by the mean endpoint voltage in mV and divided by 1,000,000. Endpoint voltage is not a continuously sampled voltage integral. This uncalibrated estimate covers the entire phone, screen, radios, background apps and test instrumentation; it is not app-specific energy or evidence of #47's potential saving.

Interface snapshots bracket a wider interval than the battery/CPU samples and include wireless instrumentation near the boundaries. Interface counters are traffic context, not matched workload or app-specific traffic. In the first completed Detail pilot, `tun0` RX increased by approximately 36.8 MB between those snapshots; that uncontrolled traffic prevents interpreting its whole-device charge delta as pure presentation cost. Home 4's before/after interface snapshots show only 310 bytes of tunnel RX. A separate approximately 4.944 MB RX increase occurred between Home 4's after snapshot and Detail 3's before snapshot, in the setup gap; it is not evidence of a burst inside Home 4.

## Retained diagnostic endpoints and exclusions

Retain noisy observations and excluded raw records; do not discard a window merely because its charge result is inconvenient. Seven foreground endpoint pairs completed; six are retained as diagnostics after excluding Home 1. Independent ultra boundary review verified Home 2/3/4 and Detail 1/2's observed surfaces; it did not establish exclusive device use or controlled energy. Detail 3 changed from the upper hero viewport to Interface/Peers and cannot serve as a comparable fixed-view repeat. The available evidence does not establish who caused that viewport change, the notification-shade interruption or any particular traffic event.

| Pilot | CPU seconds | Charge depletion, mAh | Approximate whole-device mWh | Current boundary status |
| --- | ---: | ---: | ---: | --- |
| Home 1 | 62.46 | 17.5 | 73.9 | Excluded: notification shade covered the app at the after boundary; continuous Home residence unproven. |
| Home 2 | 66.06 | 12.5 | 52.7 | Boundary validated. |
| Detail 1 | 109.04 | 10.0 | 42.1 | Boundary validated; uncontrolled tunnel RX noted above. |
| Detail 2 | 104.34 | 30.0 | 126.2 | Boundary validated. |
| Home 3 | 65.53 | 22.5 | 94.5 | Boundary validated. |
| Home 4 | 64.20 | 7.5 | 31.5 | Boundary validated; valid noisy result retained. |
| Detail 3 | 98.87 | 32.5 | 136.2 | Viewport changed; excluded from fixed-view comparison, retained as a diagnostic. |
| Screen-off 1 | Unavailable | Unavailable | Unavailable | Interrupted/incomplete; only the before checkpoint is retained. |
| Screen-off 2–3 | Unavailable | Unavailable | Unavailable | Not started. |

The excluded Home 1 raw record is retained with its reason. Its 17.5mAh endpoint delta also contradicts the earlier inference that depletion necessarily follows a fixed 5mAh grid; it does not establish the counter's true resolution, update cadence or accuracy. Endpoint pairs alone cannot calibrate those properties.

Across the three recorded Home windows, depletion is 7.5–22.5mAh with median 12.5mAh; process CPU is 64.20–66.06 seconds with median 65.53 seconds. Across recorded Detail endpoints, including the changed third viewport, depletion is 10.0–32.5mAh with median 30.0mAh; CPU is 98.87–109.04 seconds with median 104.34 seconds. These summaries describe retained diagnostics; the latter combines differing viewports and is not a fixed-view comparison. Charge ranges overlap and vary much more than CPU within each scenario. Unquantified external ADB activity, uncontrolled traffic, whole-device activity, fuel-gauge behavior, test order and wireless instrumentation remain confounders. No calibrated noise bound, transferable baseline or numeric target is accepted. More endpoints do not resolve attribution by themselves.

The first planned 600-second screen-off command was deliberately stopped after the external-device-use disclosure, returning exit code 1. No after checkpoint or derived charge/CPU endpoint result exists. This is an interrupted measurement, not a product failure; the other two screen-off windows never started.

## Preservation and final restoration

The [CPU preservation record](2026-10-08-g4-ui-cpu-baselines.md#preservation-and-final-restoration) distinguishes the intermediate unchanged configuration/existing preference entries from temporary running intent and Android's runtime marker, then records the final archive comparison. The fresh energy-before archive matched the intermediate classification. Final saved configuration and DataStore bytes match the pre-install archive; all five preference entries match, the temporary running-intent entry is absent and only the Android runtime marker differs. No personal data was overwritten from an older archive. Raw archives, endpoints, identifiers, screenshots and system reports stay ignored and local.

After the maintainer confirmed the other chat stopped and explicitly requested restoration, the guarded helper restored only brightness mode and screen timeout over wireless ADB. Initial readback matched all twelve saved checked settings. At later final readback, eleven remained exact matches and only numeric brightness differed; the live value was preserved because automatic mode was restored and reported as applied. Timeout remained 30,000ms, and no other saved-setting mismatch remained. A complete final twelve-value byte match is therefore not claimed.

The phone was initially locked at that partial-cleanup boundary. The maintainer then supplied unlocked USB access. Verified final test-app VPN state is OFF with no GoBackend VPN service in the debug package, navigation returned to the launcher, Wi-Fi setting/runtime and wireless debugging are OFF, forced idle is false and no battery simulation remains. Installed APK SHA256 still matches the verified post-#48 baseline; the preceding APK remains retained for rollback. Persisted configuration comparison found no ListenPort/profile change despite an apparent cropped visual reading.

The Wi-Fi-disable USB child stalled and was deliberately stopped. That command's result is retained as a failed cleanup attempt; later independent USB readback verified Wi-Fi and wireless debugging OFF. A stalled command or lost wireless channel was not accepted as restoration proof. The two remaining owned capture files were removed and their absence checked; earlier trace cleanup has retained readback. All phone operations finished, no timed command remains active and the device session was released. The session did not disable access while the other chat owned it.

## Interpretation, remaining gates and documentation

These are unplugged uncontrolled diagnostics, not controlled energy-target proof. #47 still needs coordinated device ownership, a repeatable energy measure/workload, baseline variability, frozen numeric target/guardrails and subsequent paired candidate evidence. Preserve visible animation, accurate rates, connectivity, configuration, lifecycle and accessibility. #47 implementation and #52 closure remain unfulfilled. No speed, battery or DPI improvement, or verified no-change optimization verdict, follows from these observations.

This record links from [QA](../qa-test-plan.md#transfer-speed-and-battery-validation), the [active plan](../improvement-plan.md#active-enhancement-and-performance-goal) and the CPU report without changing runtime policy or expanding the goal. Historical measurements retain their own conditions and dates. Focused validation covers the four guides' local links/fragments, dates, fences and whitespace; the PR acceptance record owns final independent ultra review and publication/merge state. CPU/foreground screenshot review remains boundary evidence, not energy acceptance.
