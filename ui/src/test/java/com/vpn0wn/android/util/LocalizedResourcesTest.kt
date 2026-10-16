package com.vpn0wn.android.util

import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.BackendException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LocalizedResourcesTest {
    @Test
    @Config(qualifiers = "pt-rBR")
    fun brazilianTunFailureAcceptsTheBackendsEmptyArguments() {
        val failure = BackendException(BackendException.Reason.TUN_CREATION_ERROR)

        assertEquals("Não foi possível criar o dispositivo TUN", ErrorMessages[failure])
    }

    @Test
    @Config(qualifiers = "fr")
    fun frenchTransferUnitsRetainBinaryPrefixes() {
        assertEquals("1,00 Kio", QuantityFormatter.formatBytes(1024L))
        assertEquals("1,00 Mio", QuantityFormatter.formatBytes(1024L * 1024L))
        assertEquals("1,00 Gio", QuantityFormatter.formatBytes(1024L * 1024L * 1024L))
        assertEquals("1,00 Tio", QuantityFormatter.formatBytes(1024L * 1024L * 1024L * 1024L))
    }

    @Test
    @Config(qualifiers = "fr")
    fun frenchPrivateTransferCopyRetainsWarningsAndTypedValues() {
        val resources = Application.get().resources
        val confirmation = resources.getString(R.string.app_data_transfer_confirm_message, 1, 2)

        assertEquals("Exporter les données privées de l’application", resources.getString(R.string.app_data_transfer_auth_title))
        assertTrue(resources.getString(R.string.app_data_transfer_export_summary).contains("clés privées"))
        assertTrue(confirmation.startsWith("Profils : 1. Serveurs enregistrés : 2."))
        assertTrue(confirmation.contains("contrôle à distance et la restauration au démarrage"))
        assertTrue(confirmation.contains("Les nouveaux profils restent désactivés"))
        assertTrue(confirmation.contains("seront refusées"))
        assertTrue(confirmation.contains("autorisations Android restent inchangés"))
        assertTrue(resources.getString(R.string.app_data_transfer_import_summary).contains("sans remplacer"))
        assertTrue(resources.getString(R.string.app_data_transfer_recovery_error).contains("avant de réessayer"))
        assertEquals("ZIP de transfert privé enregistré : fixture.zip", resources.getString(R.string.app_data_transfer_export_success, "fixture.zip"))
    }

    @Test
    @Config(qualifiers = "fr")
    fun frenchInventoryNamesAndActionsDoNotFallBackToEnglish() {
        val resources = Application.get().resources

        assertEquals("CONFIGURÉS : 1 · ACTIFS : 0", resources.getQuantityString(R.plurals.n_tunnels, 1, 1, 0))
        assertEquals("CONFIGURÉS : 2 · ACTIFS : 1", resources.getQuantityString(R.plurals.n_tunnels, 2, 2, 1))
        assertEquals("1 CONFIGURÉ", resources.getQuantityString(R.plurals.n_peers, 1, 1))
        assertEquals("2 CONFIGURÉS", resources.getQuantityString(R.plurals.n_peers, 2, 2))
        assertEquals("pairs", resources.getString(R.string.peers))
        assertEquals("Actualiser", resources.getString(R.string.refresh))
        assertEquals("Rechercher", resources.getString(R.string.search))
    }

    @Test
    @Config(qualifiers = "fa-rIR")
    fun persianQuickTileFailureIncludesThePlatformResult() {
        val resources = Application.get().resources
        val code = 42
        val expectedCode = String.format(resources.configuration.locales[0], "%d", code)

        assertTrue(resources.getString(R.string.quick_settings_tile_add_failure, code).endsWith(": $expectedCode"))
    }

    @Test
    @Config(qualifiers = "ar-rSA")
    fun arabicDualPluralsMayEncodeTheCountWithoutAPlaceholder() {
        val resources = Application.get().resources
        val dualForms = listOf(
            R.plurals.import_total_success,
            R.plurals.set_excluded_applications,
            R.plurals.set_included_applications,
            R.plurals.exclude_n_applications,
        )

        for (resource in dualForms) {
            val unformatted = resources.getQuantityString(resource, 2)
            assertFalse(unformatted.contains('%'))
            assertEquals(unformatted, resources.getQuantityString(resource, 2, 2))
        }
    }
}
