/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.util.DownloadsFileSaver
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], shadows = [VpsCredentialFileTest.NativeFiles::class])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupExportTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var store: VpsServerStore
    private lateinit var model: VpsProvisioningViewModel
    private val owner = ViewModelStore()
    private val peer = VpsPlanningTestPeer()

    @Before fun setUp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        store = VpsServerStore(preferences, credentialFileForTest(File(folder.root, "credentials")))
        store.upsert(VpsProfileFixture.server)
        store.saveCredential(VpsProfileFixture.server, PIN, VpsCredential.Password("synthetic-ssh-password".toCharArray()))
        peer.statusOutput = VpsBackupExportFixture.status()
        peer.backupOutput = ProvisioningJson.encodeBackup(VpsBackupExportFixture.snapshot).toString(Charsets.UTF_8)
        model = VpsProvisioningViewModel(ApplicationProvider.getApplicationContext(), store, peer)
        owner.put("backup", model)
    }

    @After fun tearDown() = runTest {
        peer.blockStatus?.complete(Unit)
        peer.blockBackup?.complete(Unit)
        try {
            val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
            owner.clear()
            job?.cancelAndJoin()
        } finally {
            try { if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
        }
    }

    @Test fun exportedCiphertextRecoversIndependentlyAfterRemoteCredentialsAndClientsDrain() = runTest {
        val server = ready()
        val password = VpsBackupExportFixture.PASSWORD.toCharArray()
        var ciphertext: ByteArray? = null
        var recovered: VpsBackupResult? = null
        requireNotNull(model.exportBackup(server, password) { archive ->
            assertTrue(model.state.value.busy)
            assertEquals(peer.connections.size, peer.closes.get())
            assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
            ciphertext = archive
            recovered = VpsBackupArchive().decrypt(archive, password)
            assertFalse(archive.toString(Charsets.ISO_8859_1).contains(VpsProfileFixture.serverKey.privateKey.toBase64()))
        }).join()
        assertEquals(VpsBackupExportFixture.snapshot, recovered)
        assertTrue(requireNotNull(ciphertext).all { it == 0.toByte() })
        assertEquals(VpsBackupExportFixture.PASSWORD, password.concatToString())
        password.fill('\u0000')
        assertNull(model.state.value.problem)
        assertFalse(model.state.value.toString().contains(VpsProfileFixture.serverKey.privateKey.toBase64()))
        assertFalse(model.state.value.toString().contains(VpsBackupExportFixture.PASSWORD))
        assertFalse(model.state.value.busy)
        assertEquals(2, peer.commands.count { it.contains("./'sync-status.sh'") })
        assertEquals(1, peer.commands.count { it.contains("./'backup.sh'") })
    }

    @Test fun missingSelectedIdentityAndChangedRemoteKeyNeverReachBackupOutput() = runTest {
        requireNotNull(model.exportBackup(server(), password()) { fail("No identity was selected") }).join()
        assertEquals(VpsProvisioningViewModel.Problem.IDENTITY_UNAVAILABLE, model.state.value.problem)
        assertTrue(peer.connections.isEmpty())
        val selected = ready()
        peer.statusOutput = JSONObject(VpsBackupExportFixture.status()).put("serverPublicKey", VpsProfileFixture.client.publicKey.toBase64()).toString()
        requireNotNull(model.exportBackup(selected, password()) { fail("Server changed") }).join()
        assertEquals(VpsProvisioningViewModel.Problem.INVALID_RESPONSE, model.state.value.problem)
        assertFalse(peer.commands.any { it.contains("./'backup.sh'") })
    }

    @Test fun coherentExportPreservesPendingRemovalAndSavedAccess() = runTest {
        val selected = ready()
        val pending = PendingVpsRemove(selected.id, VpsEndpoint(selected.host, selected.sshPort, selected.sshUser), PIN,
            VpsProfileFixture.serverKey.publicKey.toBase64(), "phone", VpsProfileFixture.client.publicKey.toBase64())
        store.admitRemove(selected, pending)
        requireNotNull(model.syncStatus(selected)).join()
        val current = server()
        assertTrue(model.state.value.pendingRemove)
        var recovered: VpsBackupResult? = null
        requireNotNull(model.exportBackup(current, password()) { archive ->
            assertTrue(model.state.value.pendingRemove)
            assertEquals(VpsProvisioningViewModel.UserRemoval.UNCERTAIN, model.state.value.removal)
            assertEquals(current, model.state.value.removalOwner)
            assertEquals(pending, store.pendingRemove(current))
            recovered = VpsBackupArchive().decrypt(archive, password())
        }).join()
        assertEquals(VpsBackupExportFixture.snapshot, recovered)
        assertNull(model.state.value.problem)
        assertTrue(model.state.value.pendingRemove)
        assertEquals(pending, store.pendingRemove(current))
        val connection = requireNotNull(store.loadConnection(current))
        val credential = connection.credential as VpsCredential.Password
        try { assertEquals("synthetic-ssh-password", credential.value.concatToString()) } finally { credential.clear() }
        assertFalse(peer.commands.any { it.contains("./'remove-peer.sh'") })
    }

    @Test fun snapshotPrivateKeyMustMatchFreshAndSelectedPublicIdentity() = runTest {
        val selected = ready()
        val changed = VpsBackupExportFixture.snapshot.copy(config = VpsBackupExportFixture.snapshot.config.replace(
            VpsProfileFixture.serverKey.privateKey.toBase64(), VpsProfileFixture.client.privateKey.toBase64()))
        peer.backupOutput = ProvisioningJson.encodeBackup(changed).toString(Charsets.UTF_8)
        requireNotNull(model.exportBackup(selected, password()) { fail("Wrong server snapshot") }).join()
        assertEquals(VpsProvisioningViewModel.Problem.INVALID_RESPONSE, model.state.value.problem)
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test fun keyObservationUsesExistingAttributeGrammarWithoutChangingSnapshotText() = runTest {
        val selected = ready()
        val changed = VpsBackupExportFixture.snapshot.copy(config = VpsBackupExportFixture.snapshot.config.replace(
            "PrivateKey = ${VpsProfileFixture.serverKey.privateKey.toBase64()}", "  privatekey\t= ${VpsProfileFixture.serverKey.privateKey.toBase64()} # retained note\r"))
        peer.backupOutput = ProvisioningJson.encodeBackup(changed).toString(Charsets.UTF_8)
        var recovered: VpsBackupResult? = null
        requireNotNull(model.exportBackup(selected, password()) { recovered = VpsBackupArchive().decrypt(it, password()) }).join()
        assertEquals(changed, recovered)
        assertNull(model.state.value.problem)
    }

    @Test fun clearedAccessDuringSnapshotRefusesPublication() = runTest {
        val selected = ready()
        peer.blockBackup = CompletableDeferred()
        val operation = requireNotNull(model.exportBackup(selected, password()) { fail("Saved access was cleared") })
        peer.backupEntered.await()
        store.clearCredential(selected.id)
        assertEquals(PIN, store.trustedHostFingerprint(selected.host, selected.sshPort))
        peer.blockBackup!!.complete(Unit)
        operation.join()
        assertEquals(VpsProvisioningViewModel.Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test fun cancellationAndDuplicateAdmissionDrainWithoutOutput() = runTest {
        val selected = ready()
        peer.blockBackup = CompletableDeferred()
        val operation = requireNotNull(model.exportBackup(selected, password()) { fail("Cancelled export") })
        peer.backupEntered.await()
        assertNull(model.exportBackup(selected, password()) { fail("Duplicate") })
        assertNull(model.syncStatus(selected))
        operation.cancelAndJoin()
        assertEquals(VpsProvisioningViewModel.Problem.CANCELLED, model.state.value.problem)
        assertFalse(model.state.value.busy)
        assertEquals(peer.connections.size, peer.closes.get())
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
    }

    @Test fun actualDownloadsProviderReceivesOnlyRecoverableCiphertext() = runTest {
        val selected = ready()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val provider = OutputProvider(folder.newFile("backup"))
        provider.attachInfo(controller.get(), ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        try {
            val saver = DownloadsFileSaver(controller.get())
            requireNotNull(model.exportBackup(selected, password()) { archive ->
                assertEquals(provider.file.absolutePath, saver.save("backup.vpn0wnbackup", "application/octet-stream", false) { it.write(archive) })
            }).join()
            assertNull(model.state.value.problem)
            assertEquals(VpsBackupExportFixture.snapshot, VpsBackupArchive().decrypt(provider.file.readBytes(), password()))
            assertEquals(0, provider.deletes)
            assertFalse(provider.descriptor!!.fileDescriptor.valid())
        } finally { controller.destroy() }
    }

    @Test fun providerOpenFailureRemovesOwnedPartialDownloadAndSettlesFailure() = runTest {
        val selected = ready()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val provider = OutputProvider(folder.newFile("partial")).apply { failOpen = true }
        provider.attachInfo(controller.get(), ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        try {
            val saver = DownloadsFileSaver(controller.get())
            requireNotNull(model.exportBackup(selected, password()) { archive ->
                saver.save("backup.vpn0wnbackup", "application/octet-stream", false) { it.write(archive) }
            }).join()
            assertEquals(VpsProvisioningViewModel.Problem.STORAGE_FAILED, model.state.value.problem)
            assertFalse(model.state.value.busy)
            assertEquals(1, provider.deletes)
            assertFalse(provider.file.exists())
            assertEquals(peer.connections.size, peer.closes.get())
        } finally { controller.destroy() }
    }

    private suspend fun ready(): StoredVpsServer {
        requireNotNull(model.syncStatus(server())).join()
        return server()
    }
    private suspend fun server() = store.servers.first().single()
    private fun password() = VpsBackupExportFixture.PASSWORD.toCharArray()

    private class OutputProvider(val file: File) : ContentProvider() {
        var failOpen = false
        var deletes = 0
        var descriptor: ParcelFileDescriptor? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?) = Uri.parse("content://media/external/downloads/1")
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failOpen) throw IOException("Synthetic provider failure")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY).also { descriptor = it }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA)).apply { addRow(arrayOf(file.absolutePath)) }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletes++
            return if (file.delete()) 1 else 0
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    }
}

internal object VpsBackupExportFixture {
    const val PASSWORD = "synthetic backup password"
    val snapshot = VpsBackupResult("wg0", "# TUNNL_MANAGED 1\n[Interface]\nPrivateKey = ${VpsProfileFixture.serverKey.privateKey.toBase64()}\n", 1, 1,
        "2026-10-17T07:00:00Z", listOf(VpsRevokedBackup("revoked.conf", "# BEGIN_PEER revoked\n[Peer]\nPublicKey = ${VpsProfileFixture.client.publicKey.toBase64()}\n")))
    fun status(hasPeer: Boolean = false): String = JSONObject().put("ok", true).put("interface", "wg0").put("running", true)
        .put("endpoint", VpsProfileFixture.server.endpoint).put("listenPort", VpsProfileFixture.server.listenPort).put("cidr", VpsProfileFixture.server.cidr)
        .put("dns", VpsProfileFixture.server.dns).put("serverPublicKey", VpsProfileFixture.serverKey.publicKey.toBase64())
        .put("peers", JSONArray().apply {
            if (hasPeer) put(JSONObject().put("name", "phone").put("publicKey", VpsProfileFixture.client.publicKey.toBase64())
                .put("allowedIps", "10.8.0.2/32").put("latestHandshake", JSONObject.NULL).put("rxBytes", JSONObject.NULL).put("txBytes", JSONObject.NULL))
        }).toString()
}
