package com.wireguard.android.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.model.TunnelTestEnvironment
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class TunnelImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://import-fixture/profile")
    private lateinit var application: Application
    private lateinit var provider: InputProvider

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = Application()
        Application::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(application, context)
        val file = temporary.newFile("invalid.conf").apply { writeText("[Interface]\nPrivateKey = invalid\n") }
        provider = InputProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = "import-fixture" })
        ShadowContentResolver.registerProviderInternal("import-fixture", provider)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        application.onTerminate()
        Dispatchers.resetMain()
    }

    @Test
    fun cancellationAndProgrammingErrorsDoNotProduceImportMessages() {
        val failures = listOf(CancellationException("cancelled"), AssertionError("defect"))
        for (failure in failures) {
            provider.queryFailure = failure
            var messages = 0
            assertEquals(failure.message, assertThrows(failure.javaClass) {
                runBlocking { TunnelImporter.importTunnel(context.contentResolver, uri) { messages++ } }
            }.message)
            assertEquals(0, messages)
        }
    }

    @Test
    fun expectedInputFailureReportsOnceWithoutLoggingItsPayload() = runBlocking {
        val payload = "fixture-secret-payload"
        provider.queryFailure = IOException(payload)
        val messages = mutableListOf<CharSequence>()
        TunnelImporter.importTunnel(context.contentResolver, uri) { messages.add(it) }
        assertEquals(1, messages.size)
        val logs = ShadowLog.getLogsForTag("WireGuard/TunnelImporter")
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { it.msg.contains(payload) || it.throwable?.message?.contains(payload) == true })
    }

    @Test
    fun callbackDefectEscapesWithoutASecondDelivery() {
        val failure = AssertionError("callback defect")
        var messages = 0
        assertEquals(failure.message, assertThrows(AssertionError::class.java) {
            runBlocking {
                TunnelImporter.importTunnel(context.contentResolver, uri) {
                    messages++
                    throw failure
                }
            }
        }.message)
        assertEquals(1, messages)
    }

    @Test
    fun mixedZipImportsAllValidEntriesBeforeOneFinalCallback() = runBlocking {
        val env = TunnelTestEnvironment(temporary.root, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        application = env.application
        val existing = env.manager.create("duplicate", TunnelTestEnvironment.profile())
        val first = TunnelTestEnvironment.profile(51821)
        val second = TunnelTestEnvironment.profile(51822)
        val entries = linkedMapOf(
            "notes.txt" to "ignored",
            "folder/alpha.conf" to first.toWgQuickString(),
            "invalid.conf" to "[Interface]\nPrivateKey = invalid\n",
            "duplicate.conf" to first.toWgQuickString(),
            "beta.conf" to second.toWgQuickString(),
        )
        provider.input = temporary.newFile("profiles.zip").apply {
            ZipOutputStream(outputStream()).use { zip ->
                for ((name, text) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }
        provider.displayName = "profiles.zip"
        val messages = mutableListOf<CharSequence>()
        TunnelImporter.importTunnel(context.contentResolver, uri) {
            assertEquals(setOf("alpha", "beta", "duplicate"), env.store.configs.keys)
            messages.add(it)
        }
        assertEquals(listOf(context.resources.getQuantityString(R.plurals.import_partial_success, 4, 2, 4)), messages)
        val tunnels = env.manager.getTunnels()
        assertEquals(setOf("alpha", "beta", "duplicate"), tunnels.map { it.name }.toSet())
        assertEquals(first.toWgQuickString(), tunnels["alpha"]!!.getConfigAsync().toWgQuickString())
        assertEquals(second.toWgQuickString(), tunnels["beta"]!!.getConfigAsync().toWgQuickString())
        assertEquals(existing, tunnels["duplicate"])
        assertEquals(TunnelTestEnvironment.profile().toWgQuickString(), env.store.configs.getValue("duplicate").toWgQuickString())
    }

    private class InputProvider(var input: File) : ContentProvider() {
        var queryFailure: Throwable? = null
        var displayName = "fixture.conf"
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queryFailure?.let { throw it }
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(displayName)) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
