package com.vpn0wn.android.util

import android.content.res.Configuration
import com.vpn0wn.android.Application
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.BackendException
import com.vpn0wn.config.BadConfigException
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyFormatException
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LocalizedResourcesTest {
    @Test
    @Config(qualifiers = "fr")
    fun frenchConfigFailureIdentifiesTheAttributeWithinItsSection() {
        val failure = BadConfigException(
            BadConfigException.Section.PEER,
            BadConfigException.Location.PUBLIC_KEY,
            BadConfigException.Reason.MISSING_ATTRIBUTE,
            null,
        )

        assertTrue(ErrorMessages[failure].contains("PublicKey de Peer"))
    }

    @Test
    @Config(qualifiers = "in")
    fun indonesianKeyFailureExplainsTheByteLength() {
        val cause = assertThrows(KeyFormatException::class.java) { Key.fromBase64("short") }
        val failure = BadConfigException(BadConfigException.Section.PEER, BadConfigException.Location.PUBLIC_KEY, cause)

        assertTrue(ErrorMessages[failure].contains("44 karakter (32 byte)"))
        assertFalse(ErrorMessages[failure].contains("32 bit"))
    }

    @Test
    @Config(qualifiers = "in")
    fun indonesianKeepaliveUsesTheConfiguredInterval() {
        val keepaliveSeconds = 25
        val message = Application.get().resources.getQuantityString(
            R.plurals.persistent_keepalive_seconds_unit, keepaliveSeconds, keepaliveSeconds,
        )

        assertEquals("setiap 25 detik", message)
    }

    @Test
    @Config(qualifiers = "ca-rES")
    fun catalanSingularDeletionRetainsItsDiagnosticArgument() {
        val message = Application.get().resources.getQuantityString(R.plurals.delete_error, 1, 1, "fixture failure")

        assertTrue(message.contains("1 túnel"))
        assertTrue(message.endsWith(": fixture failure"))
    }

    @Test
    @Config(qualifiers = "ar-rSA")
    fun arabicMtuFailureSeparatesItsExplanationFromTheContext() {
        val failure = BadConfigException(
            BadConfigException.Section.INTERFACE,
            BadConfigException.Location.MTU,
            BadConfigException.Reason.INVALID_VALUE,
            "invalid",
        )

        val explanation = Application.get().resources.getString(R.string.bad_config_explanation_positive_number)
        assertTrue(explanation.startsWith(": "))
        assertTrue(ErrorMessages[failure].endsWith(explanation))
    }

    @Test
    @Config(qualifiers = "en")
    fun negativeParsedMtuUsesMtuContextAndGuidance() {
        val failure = assertThrows(BadConfigException::class.java) {
            com.vpn0wn.config.Interface.Builder().parseMtu("-1")
        }
        val resources = Application.get().resources
        val message = ErrorMessages[failure]

        assertTrue(message.contains("MTU"))
        assertFalse(message.contains("ListenPort"))
        assertTrue(message.endsWith(resources.getString(R.string.bad_config_explanation_positive_number)))
        assertFalse(message.endsWith(resources.getString(R.string.bad_config_explanation_udp_port)))
    }

    @Test
    fun everyLocaleExplainsTheSupportedKeepaliveBounds() {
        val application = Application.get()
        val locales = listOf(
            "ar-SA", "ca-ES", "cs-CZ", "da-DK", "de", "el-GR", "es-ES", "et-EE", "fa-IR", "fi-FI", "fr",
            "hi", "hi-IN", "hu-HU", "id", "it", "ja", "ko-KR", "nl-NL", "no-NO", "pa-IN", "pl-PL",
            "pt-BR", "pt-PT", "ro-RO", "ru", "si-LK", "sk-SK", "sl", "sv-SE", "tr-TR", "uk-UA", "vi-VN", "zh-CN", "zh-TW",
        )

        for (tag in locales) {
            val configuration = Configuration(application.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val context = application.createConfigurationContext(configuration)
            val explanation = context.getString(R.string.bad_config_explanation_pka)
            assertTrue("$tag retains the supported zero value", explanation.contains('0'))
            assertTrue("$tag retains the supported maximum", explanation.contains("65535"))
            if (tag == "ar-SA" || tag == "fa-IR") {
                assertTrue("$tag isolates the increasing range", explanation.contains("\u20660–65535\u2069"))
            }
        }
    }

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

        assertTrue(resources.getString(R.string.quick_settings_tile_add_failure, code).endsWith(expectedCode))
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

    @Test
    fun homeSelectionAndDeleteCountsUseTheLocalesIntegerCategories() {
        val cases = listOf(
            Triple("sk-SK", 1, "%d VYBRATÝ" to "odstrániť %d tunel?"),
            Triple("sk-SK", 2, "%d VYBRATÉ" to "odstrániť %d tunely?"),
            Triple("sk-SK", 5, "%d VYBRATÝCH" to "odstrániť %d tunelov?"),
            Triple("ro-RO", 1, "%d SELECTAT" to "șterge %d tunel?"),
            Triple("ro-RO", 2, "%d SELECTATE" to "șterge %d tuneluri?"),
            Triple("ro-RO", 20, "%d SELECTATE" to "șterge %d de tuneluri?"),
            Triple("cs-CZ", 1, "%d VYBRÁNO" to "smazat %d tunel?"),
            Triple("cs-CZ", 2, "%d VYBRÁNO" to "smazat %d tunely?"),
            Triple("cs-CZ", 5, "%d VYBRÁNO" to "smazat %d tunelů?"),
            Triple("pl-PL", 1, "%d WYBRANY" to "usunąć %d tunel?"),
            Triple("pl-PL", 2, "%d WYBRANE" to "usunąć %d tunele?"),
            Triple("pl-PL", 5, "%d WYBRANYCH" to "usunąć %d tuneli?"),
            Triple("pl-PL", 12, "%d WYBRANYCH" to "usunąć %d tuneli?"),
            Triple("pl-PL", 22, "%d WYBRANE" to "usunąć %d tunele?"),
            Triple("ru", 1, "%d ВЫБРАНО" to "удалить %d туннель?"),
            Triple("ru", 2, "%d ВЫБРАНО" to "удалить %d туннеля?"),
            Triple("ru", 5, "%d ВЫБРАНО" to "удалить %d туннелей?"),
            Triple("ru", 21, "%d ВЫБРАНО" to "удалить %d туннель?"),
            Triple("sl", 1, "%d IZBRAN" to "izbrisati %d tunel?"),
            Triple("sl", 2, "%d IZBRANA" to "izbrisati %d tunela?"),
            Triple("sl", 3, "%d IZBRANI" to "izbrisati %d tunele?"),
            Triple("sl", 5, "%d IZBRANIH" to "izbrisati %d tunelov?"),
            Triple("sl", 101, "%d IZBRAN" to "izbrisati %d tunel?"),
            Triple("sl", 102, "%d IZBRANA" to "izbrisati %d tunela?"),
            Triple("uk-UA", 1, "%d ВИБРАНО" to "видалити %d тунель?"),
            Triple("uk-UA", 2, "%d ВИБРАНО" to "видалити %d тунелі?"),
            Triple("uk-UA", 5, "%d ВИБРАНО" to "видалити %d тунелів?"),
            Triple("uk-UA", 21, "%d ВИБРАНО" to "видалити %d тунель?"),
            Triple("ar-SA", 0, "تم تحديد %d" to "حذف %d من الأنفاق؟"),
            Triple("ar-SA", 1, "تم تحديد %d" to "حذف نفق واحد؟"),
            Triple("ar-SA", 2, "تم تحديد %d" to "حذف نفقين؟"),
            Triple("ar-SA", 3, "تم تحديد %d" to "حذف %d أنفاق؟"),
            Triple("ar-SA", 11, "تم تحديد %d" to "حذف %d نفقًا؟"),
            Triple("ar-SA", 100, "تم تحديد %d" to "حذف %d نفق؟"),
        )
        val application = Application.get()

        for ((tag, count, expected) in cases) {
            val locale = Locale.forLanguageTag(tag)
            val configuration = Configuration(application.resources.configuration).apply { setLocale(locale) }
            val resources = application.createConfigurationContext(configuration).resources
            assertEquals("$tag selection $count", String.format(locale, expected.first, count),
                resources.getQuantityString(R.plurals.vpn0wn_selection_title, count, count))
            assertEquals("$tag deletion $count", String.format(locale, expected.second, count),
                resources.getQuantityString(R.plurals.vpn0wn_delete_sheet_title, count, count))
        }
    }

    @Test
    @Config(qualifiers = "uk-rUA")
    fun ukrainianKeepaliveRetainsTheCountWhenTheOneCategoryRepeats() {
        val resources = Application.get().resources

        assertEquals("кожну 1 секунду", resources.getQuantityString(R.plurals.persistent_keepalive_seconds_unit, 1, 1))
        assertEquals("кожну 21 секунду", resources.getQuantityString(R.plurals.persistent_keepalive_seconds_unit, 21, 21))
        assertEquals("кожну 101 секунду", resources.getQuantityString(R.plurals.persistent_keepalive_seconds_unit, 101, 101))
    }
}
