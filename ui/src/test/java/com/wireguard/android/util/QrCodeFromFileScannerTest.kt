package com.wireguard.android.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.Reader
import com.google.zxing.Result
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], shadows = [QrCodeFromFileScannerTest.BitmapDecoder::class])
class QrCodeFromFileScannerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var provider: ImageProvider
    private val uri = Uri.parse("content://fixture/image")

    @Before
    fun setUp() {
        BitmapDecoder.bitmaps.clear()
        BitmapDecoder.samples.clear()
        BitmapDecoder.memoryFailure = false
        BitmapDecoder.alwaysMemoryFailure = false
        provider = ImageProvider(temporary.newFile("image.png").apply { writeBytes(byteArrayOf(1)) })
        provider.attachInfo(context, ProviderInfo().apply { authority = "fixture" })
        ShadowContentResolver.registerProviderInternal("fixture", provider)
    }

    @Test
    fun scaledRetryReopensInputAndRecyclesBothBitmaps() = runBlocking {
        val reader = ControlledReader { attempt ->
            if (attempt == 1) throw NotFoundException.getNotFoundInstance()
            fixtureResult()
        }
        assertEquals("fixture", QrCodeFromFileScanner(context.contentResolver, reader).scan(uri).text)
        assertEquals(2, provider.opens)
        assertEquals(listOf(1, 2), BitmapDecoder.samples)
        assertTrue(BitmapDecoder.bitmaps.all { it.isRecycled })
    }

    @Test
    fun allocationPressureCanRetryAtSmallerScale() = runBlocking {
        BitmapDecoder.memoryFailure = true
        val reader = ControlledReader { fixtureResult() }
        assertEquals("fixture", QrCodeFromFileScanner(context.contentResolver, reader).scan(uri).text)
        assertEquals(2, provider.opens)
        assertEquals(1, reader.calls)
        assertTrue(BitmapDecoder.bitmaps.all { it.isRecycled })
    }

    @Test
    fun cancellationAndProgrammingErrorsEscapeWithoutRetry() {
        val cancellation = CancellationException("fixture cancelled")
        val cancelledReader = ControlledReader { throw cancellation }
        assertEquals(cancellation.message, assertThrows(CancellationException::class.java) {
            runBlocking { QrCodeFromFileScanner(context.contentResolver, cancelledReader).scan(uri) }
        }.message)
        assertEquals(1, cancelledReader.calls)
        val defect = AssertionError("fixture defect")
        val defectiveReader = ControlledReader { throw defect }
        assertEquals(defect.message, assertThrows(AssertionError::class.java) {
            runBlocking { QrCodeFromFileScanner(context.contentResolver, defectiveReader).scan(uri) }
        }.message)
        assertEquals(1, defectiveReader.calls)
        assertTrue(BitmapDecoder.bitmaps.all { it.isRecycled })
    }

    @Test
    fun exhaustedAllocationPressureReportsRecoverableInputFailure() {
        BitmapDecoder.alwaysMemoryFailure = true
        val reader = ControlledReader { fixtureResult() }
        val failure = assertThrows(IOException::class.java) {
            runBlocking { QrCodeFromFileScanner(context.contentResolver, reader).scan(uri) }
        }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is OutOfMemoryError })
        assertEquals(8, provider.opens)
        assertEquals(0, reader.calls)
    }

    @Test
    fun exhaustedScanPreservesOriginalReaderFailure() {
        val failure = NotFoundException.getNotFoundInstance()
        val reader = ControlledReader { throw failure }
        assertSame(failure, assertThrows(NotFoundException::class.java) {
            runBlocking { QrCodeFromFileScanner(context.contentResolver, reader).scan(uri) }
        })
        assertEquals(BitmapDecoder.samples.size, provider.opens)
        assertTrue(BitmapDecoder.bitmaps.all { it.isRecycled })
    }

    private fun fixtureResult() = Result("fixture", null, emptyArray(), BarcodeFormat.QR_CODE)

    private class ControlledReader(private val decode: (Int) -> Result) : Reader {
        var calls = 0
        override fun decode(image: BinaryBitmap): Result = decode(++calls)
        override fun decode(image: BinaryBitmap, hints: Map<DecodeHintType, *>?): Result = decode(image)
        override fun reset() = Unit
    }

    private class ImageProvider(private val image: java.io.File) : ContentProvider() {
        var opens = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Implements(BitmapFactory::class)
    // Robolectric instantiates this static API shadow through a public constructor.
    @Suppress("UtilityClassWithPublicConstructor")
    class BitmapDecoder {
        companion object {
            val bitmaps = mutableListOf<Bitmap>()
            val samples = mutableListOf<Int>()
            var memoryFailure = false
            var alwaysMemoryFailure = false

            @JvmStatic
            @Implementation
            fun decodeStream(input: InputStream, @Suppress("UNUSED_PARAMETER") padding: Rect?, options: BitmapFactory.Options): Bitmap? {
                samples.add(options.inSampleSize)
                if (input.read() == -1) return null
                if (memoryFailure || alwaysMemoryFailure) {
                    memoryFailure = false
                    throw OutOfMemoryError("fixture allocation pressure")
                }
                return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmaps.add(it) }
            }
        }
    }
}
