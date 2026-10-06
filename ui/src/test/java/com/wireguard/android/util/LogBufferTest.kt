/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import org.junit.Assert.*
import org.junit.Test

class LogBufferTest {
    @Test
    fun continuationAfterPublishedBatchChangesVisibleRow() {
        val buffer = LogBuffer("2026")
        buffer.append(listOf(row("E", "first")))
        val changes = buffer.append(listOf("    synthetic continuation"))
        assertEquals(LogBuffer.Changes(0, 1, 0, 0), changes)
        assertEquals("first\n    synthetic continuation", buffer[0].msg)
        assertEquals(2, buffer.rawSnapshot().size)
    }

    @Test
    fun hiddenContinuationDoesNotNotifyVisibleRow() {
        val buffer = LogBuffer("2026")
        buffer.append(listOf(row("E", "error"), row("I", "info")))
        buffer.setFilter(LogLevelFilter.ERROR)
        assertNull(buffer.append(listOf("continuation")).changed)
        assertEquals(1, buffer.size)
        buffer.setFilter(LogLevelFilter.ALL)
        assertEquals("info\ncontinuation", buffer[1].msg)
    }

    @Test
    fun filteredEvictionShiftsRemainingRowsAndNotifiesMatchingInserts() {
        val buffer = LogBuffer("2026", maxLines = 3)
        buffer.append(listOf(row("E", "old"), row("I", "hidden"), row("F", "kept")))
        buffer.setFilter(LogLevelFilter.ERROR)
        val changes = buffer.append(listOf(row("W", "warning"), row("A", "new")))
        assertEquals(LogBuffer.Changes(1, 1, 1, null), changes)
        assertEquals(listOf("kept", "new"), List(buffer.size) { buffer[it].msg })
        buffer.setFilter(LogLevelFilter.ALL)
        assertEquals(listOf("kept", "warning", "new"), List(buffer.size) { buffer[it].msg })
    }

    @Test
    fun batchLargerThanCapacityKeepsNewestRowsAndCorrectInsertRange() {
        val buffer = LogBuffer("2026", maxLines = 2)
        buffer.append(listOf(row("I", "old")))
        buffer.setFilter(LogLevelFilter.ERROR)
        val changes = buffer.append(listOf(row("E", "evicted"), row("I", "kept"), row("F", "last")))
        assertEquals(LogBuffer.Changes(0, 0, 1, null), changes)
        assertEquals("last", buffer[0].msg)
        assertEquals(listOf(row("I", "kept"), row("F", "last")), buffer.rawSnapshot())
    }

    @Test
    fun continuationBudgetIsBoundedEvenWithoutNewHeaders() {
        val buffer = LogBuffer("2026", maxLines = 3, maxCharacters = 80)
        buffer.append(listOf(row("I", "initial")))
        repeat(100) { buffer.append(listOf("x".repeat(20))) }
        assertEquals(1, buffer.size)
        assertTrue(buffer[0].tag.length + buffer[0].msg.length <= 80)
        assertEquals("x".repeat(20), buffer[0].msg.takeLast(20))
        assertTrue(buffer.rawSnapshot().sumOf { it.length } <= 80)
        assertTrue(buffer.rawSnapshot().size <= 3)
    }

    @Test
    fun characterEvictionAndRefreshPreserveFilterAndSnapshots() {
        val buffer = LogBuffer("2026", maxCharacters = 80)
        val first = row("E", "x".repeat(40))
        buffer.append(listOf(first))
        val snapshot = buffer.rawSnapshot()
        val changes = buffer.append(listOf(row("I", "y".repeat(40))))
        assertEquals(1, changes.removed)
        assertEquals(1, buffer.size)
        assertEquals(listOf(first), snapshot)
        buffer.setFilter(LogLevelFilter.ERROR)
        buffer.clear()
        buffer.append(listOf(row("I", "hidden"), row("A", "visible")))
        assertEquals(1, buffer.size)
        assertEquals("visible", buffer[0].msg)
        assertEquals(2, buffer.rawSnapshot().size)
    }

    @Test
    fun initialContinuationIsRawOnlyAndUnfilteredEvictionHasValidRanges() {
        val buffer = LogBuffer("2026", maxLines = 2)
        assertEquals(LogBuffer.Changes(0, 0, 0, null), buffer.append(listOf("initial continuation")))
        buffer.append(listOf(row("I", "first"), row("W", "second")))
        assertEquals(LogBuffer.Changes(1, 1, 1, 0), buffer.append(listOf("continuation", row("E", "third"))))
        assertEquals(listOf("second\ncontinuation", "third"), List(buffer.size) { buffer[it].msg })
    }

    private fun row(level: String, message: String) = "10-06 12:34:56.789 1 2 $level Tag: $message"
}
