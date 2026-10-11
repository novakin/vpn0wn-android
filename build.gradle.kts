// Modified for vpn0wn.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.legacy.kapt) apply false
    alias(libs.plugins.detekt) apply false
}

val vpsIntegrationPython = providers.environmentVariable("TUNNL_PYTHON").orElse("python3")

tasks.register<Exec>("vpsIntegrationTest") {
    group = "verification"
    description = "Runs the destructive opt-in VPS provisioning lifecycle test against TUNNL_VPS_HOST."

    commandLine(vpsIntegrationPython.get(), "$rootDir/scripts/vps_integration_test.py")

    doFirst {
        val required = listOf("TUNNL_VPS_HOST", "TUNNL_VPS_USER", "TUNNL_VPS_DESTRUCTIVE")
        val missing = required.filter { providers.environmentVariable(it).orNull.isNullOrBlank() }
        require(missing.isEmpty()) {
            "Missing required environment variables for vpsIntegrationTest: ${missing.joinToString()}. " +
                "This destructive test is opt-in only."
        }
        require(providers.environmentVariable("TUNNL_VPS_DESTRUCTIVE").orNull == "1") {
            "Set TUNNL_VPS_DESTRUCTIVE=1 to acknowledge this test may install, purge, and remove WireGuard on the target VPS."
        }
    }
}
