/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [VpsCredentialFileTest.NativeFiles::class])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsBackupImportTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var store: VpsServerStore
    private lateinit var model: VpsProvisioningViewModel
    private val owner = ViewModelStore()
    private val peer = VpsPlanningTestPeer()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        store = VpsServerStore(preferences, credentialFileForTest(File(folder.root, "credentials")))
        model = VpsProvisioningViewModel(ApplicationProvider.getApplicationContext(), store, peer)
        owner.put("backup", model)
    }

    @After fun tearDown() = runTest {
        try {
            val job = model.viewModelScope.coroutineContext[Job]
            owner.clear()
            job?.cancelAndJoin()
        } finally {
            try { scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
        }
    }

    @Test fun documentPreservesOpaqueBytesAndIgnoresProviderMetadata() = runTest {
        val bytes = ByteArray(517) { it.toByte() }
        val provider = input(bytes)
        val read = document().read()
        assertArrayEquals(bytes, read)
        read.fill(0)
        assertEquals(0, provider.queries)
        assertEquals(0, provider.mutations)
        assertTrue(provider.closed)
        assertFalse(provider.descriptor.fileDescriptor.valid())
    }

    @Test fun documentAcceptsMaximumAndStopsAtMaximumPlusOne() = runTest {
        input(ByteArray(VpsBackupArchive.MAX_ARCHIVE_BYTES) { 1 })
        val maximum = document().read()
        assertEquals(VpsBackupArchive.MAX_ARCHIVE_BYTES, maximum.size)
        maximum.fill(0)
        val oversized = input(ByteArray(VpsBackupArchive.MAX_ARCHIVE_BYTES + 5) { 2 })
        try { document().read(); fail("Oversized input returned") } catch (failure: VpsBackupArchiveException) {
            assertEquals(VpsBackupArchiveException.Category.FORMAT, failure.category)
        }
        assertEquals(VpsBackupArchive.MAX_ARCHIVE_BYTES.toLong() + 1, oversized.positionAtClose)
        assertTrue(oversized.closed)
        assertFalse(oversized.descriptor.fileDescriptor.valid())
    }

    @Test fun emptyFleetUnlockPublishesOnlyPublicSummaryWithoutStoreOrTransportMutation() = runTest {
        val provider = validInput()
        val before = provider.file.readBytes()
        val password = PASSWORD.toCharArray()
        requireNotNull(model.inspectBackup(document(), password)).join()
        assertEquals(PASSWORD, password.concatToString())
        password.fill('\u0000')
        val snapshot = VpsBackupExportFixture.snapshot
        assertEquals(VpsProvisioningViewModel.BackupSummary(snapshot.capturedAtUtc, snapshot.interfaceName,
            snapshot.snapshotVersion, snapshot.revokedCount, VpsProfileFixture.serverKey.publicKey.toBase64()), model.state.value.backupSummary)
        assertNull(model.state.value.problem)
        assertNull(model.state.value.backupFailure)
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.toString().contains(snapshot.config))
        assertFalse(model.state.value.toString().contains(VpsProfileFixture.serverKey.privateKey.toBase64()))
        assertFalse(model.state.value.toString().contains(PASSWORD))
        assertTrue(store.servers.first().isEmpty())
        assertTrue(peer.connections.isEmpty())
        assertTrue(peer.commands.isEmpty())
        assertEquals(0, provider.mutations)
        assertArrayEquals(before, provider.file.readBytes())
        model.clearBackupInspection()
        assertNull(model.state.value.backupSummary)
    }

    @Test fun readerPasswordPolicyAndSafeFailuresRemainDistinct() = runTest {
        validInput()
        for (password in listOf("x".toCharArray(), "wrong longer password".toCharArray())) {
            requireNotNull(model.inspectBackup(document(), password)).join()
            assertEquals(VpsProvisioningViewModel.BackupFailure.AUTHENTICATION, model.state.value.backupFailure)
            assertNull(model.state.value.backupSummary)
            password.fill('\u0000')
        }
        for (password in listOf(charArrayOf(), charArrayOf('\ud800'), CharArray(257) { 'a' })) {
            requireNotNull(model.inspectBackup(document(), password)).join()
            assertEquals(VpsProvisioningViewModel.BackupFailure.PASSWORD, model.state.value.backupFailure)
            assertNull(model.state.value.backupSummary)
            password.fill('\u0000')
        }
        input(ByteArray(57))
        requireNotNull(model.inspectBackup(document(), PASSWORD.toCharArray())).join()
        assertEquals(VpsProvisioningViewModel.BackupFailure.FORMAT, model.state.value.backupFailure)
    }

    @Test fun authenticatedSnapshotWithoutPublicKeyIsInspectableWithoutRestoreGrammarClaims() = runTest {
        validInput(VpsBackupExportFixture.snapshot.copy(config = "# TUNNL_MANAGED 1\nopaque snapshot text\n"))
        requireNotNull(model.inspectBackup(document(), PASSWORD.toCharArray())).join()
        assertNotNull(model.state.value.backupSummary)
        assertNull(model.state.value.backupSummary!!.serverPublicKey)
        assertNull(model.state.value.backupFailure)
        assertNull(model.state.value.problem)
        assertTrue(peer.commands.isEmpty())
    }

    @Test fun deniedProviderAndCloseFailureNeverPublishSummary() = runTest {
        val provider = validInput()
        model.begin()
        val existing = model.state.value
        provider.denied = true
        requireNotNull(model.inspectBackup(document(), PASSWORD.toCharArray())).join()
        assertEquals(VpsProvisioningViewModel.BackupFailure.INPUT, model.state.value.backupFailure)
        assertNull(model.state.value.problem)
        assertNull(model.state.value.backupSummary)
        assertEquals(existing.copy(backupFailure = VpsProvisioningViewModel.BackupFailure.INPUT), model.state.value)
        provider.denied = false
        provider.failClose = true
        requireNotNull(model.inspectBackup(document(), PASSWORD.toCharArray())).join()
        assertEquals(VpsProvisioningViewModel.BackupFailure.INPUT, model.state.value.backupFailure)
        assertNull(model.state.value.problem)
        assertNull(model.state.value.backupSummary)
        assertTrue(provider.closed)
        assertFalse(provider.descriptor.fileDescriptor.valid())
        assertEquals(existing.copy(backupFailure = VpsProvisioningViewModel.BackupFailure.INPUT), model.state.value)
        assertTrue(store.servers.first().isEmpty())
        assertTrue(peer.commands.isEmpty())
    }

    @Test fun providerHonoringCancellationCannotReplaceTheCancelledOperationWithRuntimeFailure() = runTest {
        val provider = validInput()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        provider.beforeOpen = { signal ->
            val nativeSignal = requireNotNull(signal)
            nativeSignal.setOnCancelListener { cancelled.countDown() }
            entered.countDown()
            check(cancelled.await(10, TimeUnit.SECONDS))
            nativeSignal.throwIfCanceled()
        }
        val operation = requireNotNull(model.inspectBackup(document(), PASSWORD.toCharArray()))
        try {
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            operation.cancel()
            operation.join()
            assertFalse(model.state.value.busy)
            assertEquals(VpsProvisioningViewModel.Problem.CANCELLED, model.state.value.problem)
            assertNull(model.state.value.backupSummary)
            assertTrue(peer.commands.isEmpty())
        } finally { operation.cancelAndJoin() }
    }

    @Test fun providerNativeCancellationWithoutCallerCancellationIsAnInputFailure() = runTest {
        validInput().beforeOpen = { throw OperationCanceledException("Synthetic provider cancellation") }
        requireNotNull(model.inspectBackup(document(), PASSWORD.toCharArray())).join()
        assertEquals(VpsProvisioningViewModel.BackupFailure.INPUT, model.state.value.backupFailure)
        assertNull(model.state.value.problem)
        assertNull(model.state.value.backupSummary)
        assertFalse(model.state.value.busy)
        assertTrue(peer.commands.isEmpty())
    }

    @Test fun cancellationDuringOwnedCloseCannotReplaceCancellationWithInputFailure() = runTest {
        val provider = validInput()
        lateinit var operation: Job
        provider.beforeClose = { operation.cancel() }
        provider.failClose = true
        operation = requireNotNull(model.inspectBackup(document(), PASSWORD.toCharArray()))
        operation.join()
        assertFalse(model.state.value.busy)
        assertEquals(VpsProvisioningViewModel.Problem.CANCELLED, model.state.value.problem)
        assertNull(model.state.value.backupFailure)
        assertNull(model.state.value.backupSummary)
        assertTrue(provider.closed)
        assertFalse(provider.descriptor.fileDescriptor.valid())
        assertTrue(peer.commands.isEmpty())
    }

    @Test fun cancellationSignalsBlockedProviderAndKeepsBusyUntilLateOpenDrains() = runTest {
        val provider = validInput()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        provider.beforeOpen = { signal ->
            signal!!.setOnCancelListener { cancelled.countDown() }
            entered.countDown()
            check(released.await(10, TimeUnit.SECONDS))
        }
        val operation = requireNotNull(model.inspectBackup(document(), PASSWORD.toCharArray()))
        try {
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            operation.cancel()
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { cancelled.await(10, TimeUnit.SECONDS) })
            assertTrue(model.state.value.busy)
            assertFalse(operation.isCompleted)
            assertNull(model.inspectBackup(document(), PASSWORD.toCharArray()))
            assertNull(model.state.value.backupSummary)
        } finally {
            released.countDown()
            withContext(NonCancellable) { operation.cancelAndJoin() }
        }
        assertTrue(provider.closed)
        assertFalse(provider.descriptor.fileDescriptor.valid())
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.backupSummary)
        assertEquals(VpsProvisioningViewModel.Problem.CANCELLED, model.state.value.problem)
        assertTrue(peer.commands.isEmpty())
    }

    private suspend fun validInput(snapshot: VpsBackupResult = VpsBackupExportFixture.snapshot): VpsBackupInputProvider {
        val password = PASSWORD.toCharArray()
        return try { input(VpsBackupArchive().encrypt(snapshot, password)) } finally { password.fill('\u0000') }
    }

    private fun input(bytes: ByteArray): VpsBackupInputProvider {
        val file = folder.newFile().apply { writeBytes(bytes) }
        return VpsBackupInputProvider(file).also { it.register(context) }
    }

    private fun document() = VpsBackupDocument(context.contentResolver, VpsBackupInputProvider.URI)

    private companion object { const val PASSWORD = "synthetic recovery password" }
}

internal class VpsBackupInputProvider(val file: File) : ContentProvider() {
    var queries = 0
    var mutations = 0
    var denied = false
    var failClose = false
    var opens = 0
    var positionAtClose = 0L
    var closed = false
    lateinit var descriptor: ParcelFileDescriptor
    var beforeOpen: ((CancellationSignal?) -> Unit)? = null
    var beforeClose: (() -> Unit)? = null

    fun register(context: Context) {
        attachInfo(context, ProviderInfo().apply { authority = URI.authority })
        ShadowContentResolver.registerProviderInternal(requireNotNull(URI.authority), this)
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/plain"
    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor =
        AssetFileDescriptor(openFile(uri, mode, signal), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor =
        AssetFileDescriptor(openFile(uri, "r", signal), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openFile(uri, mode, null)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        assertEquals("r", mode)
        opens++
        if (denied) throw SecurityException("Synthetic provider denial")
        beforeOpen?.invoke(signal)
        val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val source = ReflectionHelpers.getField<RandomAccessFile>(Shadow.extract<Any>(opened), "file")
        return object : ParcelFileDescriptor(opened) {
            override fun close() {
                // A new FileInputStream here mutates JDK descriptor parents during Robolectric's reentrant close.
                if (!closed) {
                    closed = true
                    positionAtClose = if (source.fd.valid()) source.filePointer else 0
                    beforeClose?.invoke()
                }
                super.close()
                if (failClose) throw IOException("Synthetic provider close failure")
            }
        }.also { descriptor = it }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queries++
        return MatrixCursor(arrayOf("_size")).apply { addRow(arrayOf(Long.MAX_VALUE)) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { mutations++; return null }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; return 0 }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; return 0 }

    companion object { val URI: Uri = Uri.parse("content://backup-reader-fixture/archive") }
}
