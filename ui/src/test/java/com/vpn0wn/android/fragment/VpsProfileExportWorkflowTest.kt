/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.AppDataTransferSettingsTest
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.android.util.BiometricAuthenticator
import com.vpn0wn.android.util.TunnelImporter
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29],
    shadows = [VpsCredentialFileTest.NativeFiles::class, AppDataTransferSettingsTest.Authentication::class])
@OptIn(ExperimentalCoroutinesApi::class)
internal class VpsProfileExportWorkflowTest : VpsProvisioningWorkflowFixture() {
    @Test
    fun exportRestrictionAtOutputDeletesOwnedFileBeforeAnyProfileBytesAreWritten() = runTest {
        VpsUserProfileFileTest.resetNativeFiles()
        val profiles = profileStore {}
        val fixture = fixture(profiles = profiles)
        try {
            openProfileUser(fixture, profiles)
            val output = ProfileOutputProvider(folder.newFile()).apply {
                onOpened = { setProfileExportRestriction(fixture, true) }
                attachInfo(fixture.activity, ProviderInfo().apply { authority = "media" })
            }
            ShadowContentResolver.registerProviderInternal("media", output)
            click(fixture, R.string.vpn0wn_action_export)
            AppDataTransferSettingsTest.Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
            finishProfileAction(fixture)
            assertEquals(1, output.inserts)
            assertEquals(1, output.opens)
            assertEquals(1, output.deletes)
            assertEquals(0L, output.bytesAtDeletion)
            assertFalse(output.file.exists())
            assertTrue(output.name!!.endsWith(".conf"))
            assertEquals(2, profileStatusCommands(fixture))
        } finally { fixture.close() }
    }

    @Test
    fun exportedProfilesWithShortOrThirtyTwoCharacterNamesRoundTripThroughFileImporter() = runTest {
        for (peerName in listOf("phone", "p".repeat(32))) {
            VpsUserProfileFileTest.resetNativeFiles()
            val profiles = profileStore {}
            val fixture = fixture(profiles = profiles)
            try {
                openProfileUser(fixture, profiles, result = VpsProfileFixture.result().copy(name = peerName))
                val output = ProfileOutputProvider(folder.newFile()).apply {
                    attachInfo(fixture.activity, ProviderInfo().apply { authority = "media" })
                }
                ShadowContentResolver.registerProviderInternal("media", output)
                click(fixture, R.string.vpn0wn_action_export)
                AppDataTransferSettingsTest.Authentication.callbacks.last()(BiometricAuthenticator.Result.Success(null))
                finishProfileAction(fixture)
                val exportedName = requireNotNull(output.name)
                val tunnelName = exportedName.removeSuffix(".conf")
                assertTrue(exportedName.endsWith(".conf"))
                assertFalse(Tunnel.isNameInvalid(tunnelName))
                assertTrue(tunnelName.length <= Tunnel.NAME_MAX_LENGTH)
                assertEquals(VpsProfileFixture.profile().payload(), output.file.readText())
                val messages = mutableListOf<CharSequence>()
                TunnelImporter.importTunnel(fixture.activity.contentResolver, Uri.parse("content://media/external/downloads/1")) { messages.add(it) }
                assertEquals(listOf(fixture.activity.getString(R.string.import_success, tunnelName)), messages.map { it.toString() })
                val imported = fixture.environment.manager.getTunnels().single()
                assertEquals(tunnelName, imported.name)
                assertEquals(Tunnel.State.DOWN, imported.state)
                assertEquals(VpsProfileFixture.profile().payload(), fixture.environment.store.configs.getValue(tunnelName).toWgQuickString())
                assertTrue(fixture.environment.backend.appliedConfigs.isEmpty())
            } finally { fixture.close() }
        }
    }

    private class ProfileOutputProvider(val file: File) : ContentProvider() {
        var inserts = 0
        var opens = 0
        var deletes = 0
        var name: String? = null
        var bytesAtDeletion: Long? = null
        var onOpened: (() -> Unit)? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/x-wireguard-profile"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts++
            name = values!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            return Uri.parse("content://media/external/downloads/1")
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            val descriptor = ParcelFileDescriptor.open(file, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_WRITE_ONLY)
            if (mode != "r") onOpened?.invoke()
            return descriptor
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(requireNotNull(projection)).apply {
                addRow(projection.map { if (it == MediaStore.MediaColumns.DISPLAY_NAME) name else file.absolutePath })
            }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(Uri.parse("content://media/external/downloads/1"), uri)
            deletes++
            bytesAtDeletion = file.length()
            return if (file.delete()) 1 else 0
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
