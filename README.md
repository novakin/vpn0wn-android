# Tunnl — WireGuard Android

Last updated: 2026-10-09 (Europe/Paris).

Tunnl is [novakin's WireGuard Android fork](https://github.com/novakin/wireguard-android), adding an engineering-console redesign and a partial VPS fleet/provisioning flow to the existing Android DataBinding/Fragment architecture.

The app uses [WireGuard](https://www.wireguard.com/), with the [kernel implementation](https://git.zx2c4.com/android_kernel_wireguard/about/) when available and the non-root [userspace implementation](https://git.zx2c4.com/wireguard-go/about/). The [official WireGuard app on the Play Store](https://play.google.com/store/apps/details?id=com.wireguard.android) distributes upstream WireGuard, not this fork.

## Current scope

The redesign builds on existing tunnel import/edit/connect behavior. Native VPS fleet screens render persisted non-secret records. Concrete SSH transport, protected SSH credential persistence and live provisioning/user actions remain unfinished; current VPS QR data is a placeholder rather than an importable generated profile.

Read [operations and recovery](docs/operations.md) and [backend contracts](docs/backend-contracts.md) before relying on experimental provisioning scripts or runtime labels. The [improvement plan](docs/improvement-plan.md) tracks known safety, compatibility and lifecycle repairs; a UI label or successful command is not proof of VPN health. The source declares minimum Android API 24; executed device coverage is separate in the [QA plan](docs/qa-test-plan.md).

Settings also provides a [full app-data transfer ZIP](docs/operations.md#app-data-transfer): tunnel profiles, selected settings and non-secret saved VPS records. The archive contains private tunnel keys. [Scoped synthetic AVD acceptance](docs/reviews/2026-10-09-app-data-transfer-acceptance.md) covers the legacy-ID preparation; renamed release and complete cross-app/device acceptance remain open.

The userspace source uses native UDP warm-up on the same protected socket as each outgoing handshake initiation. An explicit nonzero ListenPort enables one best-effort one-byte zero datagram attempt before each initiation; absent/zero ListenPort disables it. Native protection covers socket creation/rebind, and the WireGuard protocol remains unchanged. On 2026-10-08 (Europe/Paris), the maintainer authorized adoption through [PR #63](https://github.com/novakin/wireguard-android/pull/63). PR #63 records exact-head review/checks and actual delivery state. The maintainer reported sleep/wake recovery without manual action. [The experiment record](docs/reviews/2026-10-07-protocol-preflight-experiment.md#authorized-local-apk-and-pixel-checks--2026-10-07) retains scoped Pixel 10a checks and the measured [195-second forced deep Doze recovery](docs/reviews/2026-10-07-protocol-preflight-experiment.md#forced-deep-doze-recovery--2026-10-07). The new report is not a new measured trial. [#61](https://github.com/novakin/wireguard-android/issues/61) remains open for physical lifecycle, unchanged-server interoperability and DPI acceptance; hardware deep suspend, network rebind, warm-up packet capture and broader interoperability remain unverified. No DPI, speed or battery gain is established.

## Get started

Clone this fork with its native submodules:

```bash
git clone --recurse-submodules https://github.com/novakin/wireguard-android.git
cd wireguard-android
```

Prepare the compatible JDK, Android SDK/NDK and CMake toolchain described in [development setup](docs/development.md#setup-and-source-boundaries). Native command examples use Linux/macOS or a configured WSL Linux environment; use that environment's SDK paths. Existing checkouts can initialize submodules with `git submodule update --init --recursive`. macOS native builds need [flock](https://github.com/discoteq/flock).

For a local debug APK after setup:

```bash
./gradlew :ui:assembleDebug --console=plain
```

The debug application ID has the `.debug` suffix. Build commands document operator actions; agent APK assembly still requires an explicit request under [agent instructions](AGENTS.md). Local assembly does not publish a release or deploy to a server.

## Contributing

Start with [AGENTS.md](AGENTS.md), the sole project agent-instruction file, and the [development workflow](docs/development.md). Choose the owning guide and relevant tests, refresh the intended integration base, preserve unrelated work, and use a scoped branch/PR. Apply actual GitHub labels/assignee and fill the [PR template](.github/PULL_REQUEST_TEMPLATE.md) with the final change, documentation impact, executed checks and limits.

Use the [bug report](.github/ISSUE_TEMPLATE/bug_report.md) or [improvement](.github/ISSUE_TEMPLATE/improvement.md) template for concrete work. Keep keys, passwords and raw secret-bearing logs out of issues/PRs. Focused tests, UI/device proof and destructive disposable-VPS validation have separate boundaries in [development](docs/development.md#verification-commands-and-ownership); merge and deployment require their own authorization.

Repository-native unit/static [Android CI checks](docs/development.md#android-ci-and-debug-apk-artifacts) run on PR heads and `master` pushes. Debug APK artifacts require explicit manual dispatch with `build_apk=true`; signing/retention and installation limits are described in that guide. A passing check does not establish device/VPN behavior or branch protection.

`.design/` is an ignored, read-only local prototype and can be absent from a clean clone. UI fidelity work needs that reference or another user-approved visual source; ordinary runtime/documentation work does not depend on it.

## Project documentation

Tunnl redesign and functional work are tracked in:

- [Architecture](docs/architecture.md): ownership, runtime flows and consequential decisions.
- [Development](docs/development.md): setup, verification, issues, PR descriptions and merge workflow.
- [Operations](docs/operations.md): current workflows, diagnostics and recovery limits.
- [Backend contracts](docs/backend-contracts.md): configuration/transitions and provisioning arguments/results.
- [Improvement plan](docs/improvement-plan.md): agent instructions, documentation/process, code repairs, reuse decisions and acceptance criteria.
- [UI redesign roadmap](docs/ui-redesign-roadmap.md): visual specification, progress and fidelity gaps.
- [Backend changelog](docs/backend-changelog.md): functional history and current integration gaps.
- [QA test plan](docs/qa-test-plan.md): coverage requirements, verification layers and opt-in VPS validation.
- [Agent/README review](docs/reviews/2026-10-06-agent-readme-review.md): bounded quality criteria, fixes, reused process and review evidence.
- [Full documentation review](docs/reviews/2026-10-06-full-documentation-review.md): committed-source reconciliation, coverage limits and confirmed runtime handoff.
- [WG Tunnel comparison](docs/reviews/2026-10-08-wgtunnel-comparison.md): versioned feature/ownership assessment, current native/statistics repair status and bounded reuse/refactoring advice.
- [Font attribution](docs/font-attribution.md) and [provisioning upstream reference](third_party/wireguard-install/UPSTREAM.md).

## Embedding

The published upstream tunnel library is [on Maven Central](https://search.maven.org/artifact/com.wireguard.android/tunnel), with [library documentation](https://javadoc.io/doc/com.wireguard.android/tunnel). That artifact provides the upstream tunnel API; it does not distribute this fork's Tunnl UI or provisioning flow. For this checkout's library source, see the [tunnel module](tunnel/build.gradle.kts).

Replace `<published-version>` below with a published upstream version. The checkout's `wireguardVersionName` does not prove that a matching fork artifact was published. This Kotlin DSL example uses the checkout's Java 17 and [desugaring dependency](gradle/libs.versions.toml) settings; match the requirements of the chosen artifact and consuming Android toolchain.

```kotlin
val wireguardTunnelVersion = "<published-version>"

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}
dependencies {
    implementation("com.wireguard.android:tunnel:$wireguardTunnelVersion")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

See Android's [desugaring guidance](https://developer.android.com/studio/write/java8-support#library-desugaring) for consumer configuration.

## Translating

Upstream WireGuard uses its [Crowdin translation project](https://crowdin.com/project/WireGuard). That service and this repository's upstream sync script are not evidence that Tunnl-specific strings are synchronized there. Contribute fork-specific resource changes through a scoped PR, updating [default strings](ui/src/main/res/values/strings.xml), appropriate locales and affected [QA guidance](docs/qa-test-plan.md).

## Licensing and attribution

Keep the source [COPYING](COPYING), [font attribution](docs/font-attribution.md) and [vendored provisioning reference/license](third_party/wireguard-install/UPSTREAM.md) with their components. Preserve upstream attribution when building or distributing a fork.
