/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.activity

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.recyclerview.widget.RecyclerView
import com.vpn0wn.android.util.LogBuffer
import com.vpn0wn.android.util.LogLevelFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LogViewerPublicationTest {
    @Test
    fun overlappingSharePreparationLeavesOnlyLatestPublicationOwned() = runTest {
        val activity = Robolectric.buildActivity(LogViewerActivity::class.java).get()
        val provider = LogViewerActivity.ExportedLogContentProvider()
        val firstBytes = CompletableDeferred<ByteArray>()
        val secondBytes = CompletableDeferred<ByteArray>()
        val latestBytes = "synthetic first é".toByteArray(Charsets.UTF_8)
        val first = async { publishShare(activity, firstBytes.await()) }
        val second = async { publishShare(activity, secondBytes.await()) }
        try {
            secondBytes.complete("synthetic second".toByteArray())
            val earlierUri = second.await()
            assertEquals("text/plain", provider.getType(earlierUri))
            firstBytes.complete(latestBytes)
            val latestUri = first.await()
            assertNull(provider.getType(earlierUri))
            assertNull(provider.query(earlierUri, null, null, null, null))
            assertEquals("text/plain", provider.getType(latestUri))
            requireNotNull(provider.query(latestUri, null, null, null, null)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("vpn0wn-log.txt", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                assertEquals(latestBytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
            }
            assertEquals(latestUri, ReflectionHelpers.getField<Uri>(activity, "lastUri"))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "revokeLastUri")
            assertNull(provider.getType(latestUri))
            assertNull(provider.query(latestUri, null, null, null, null))
        } finally {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "revokeLastUri")
        }
    }

    @Test
    fun invalidatedReaderCannotPublishIntoRefreshedBuffer() {
        val activity = Robolectric.buildActivity(LogViewerActivity::class.java).get()
        val adapterClass = LogViewerActivity::class.java.declaredClasses.single { it.simpleName == "LogEntryAdapter" }
        val adapter = adapterClass.getDeclaredConstructor(LogViewerActivity::class.java).apply { isAccessible = true }
            .newInstance(activity) as RecyclerView.Adapter<*>
        ReflectionHelpers.setField(activity, "logAdapter", adapter)
        ReflectionHelpers.setField(activity, "autoScroll", false)
        val buffer = ReflectionHelpers.callInstanceMethod<LogBuffer>(activity, "getLogBuffer")
        val changes = ArrayList<String>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { changes.add("insert $positionStart $itemCount") }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { changes.add("change $positionStart $itemCount") }
        })
        publish(activity, 0, listOf(row("E", "before")))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "stopLogStream")
        buffer.clear()
        buffer.setFilter(LogLevelFilter.ERROR)
        changes.clear()
        publish(activity, 0, listOf(row("E", "stale")))
        assertEquals(0, adapter.itemCount)
        assertTrue(buffer.rawSnapshot().isEmpty())
        assertTrue(changes.isEmpty())
        publish(activity, 1, listOf(row("I", "hidden"), row("F", "fresh")))
        publish(activity, 1, listOf("synthetic continuation"))
        assertEquals(1, adapter.itemCount)
        assertEquals("fresh\nsynthetic continuation", buffer[0].msg)
        assertEquals(listOf("insert 0 1", "change 0 1"), changes)
    }

    private fun publish(activity: LogViewerActivity, generation: Int, rows: List<String>) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "applyLogBatch", ClassParameter.from(Int::class.javaPrimitiveType, generation), ClassParameter.from(List::class.java, rows))
    }

    private fun row(level: String, message: String) = "10-06 12:34:56.789 1 2 $level Tag: $message"

    private fun publishShare(activity: LogViewerActivity, bytes: ByteArray): Uri =
        ReflectionHelpers.callInstanceMethod(activity, "publishLog", ClassParameter.from(ByteArray::class.java, bytes))
}
