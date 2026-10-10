/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class LogLineParserTest {
    @Test
    fun threadtimeWithoutUidKeepsFieldsAndTimestamp() {
        val row = LogLineParser.parse("10-06 12:34:56.789  1234  5678 I WireGuard/Test: synthetic message", "2026")!!
        assertEquals(1234, row.pid)
        assertEquals(5678, row.tid)
        assertEquals("I", row.level)
        assertEquals("WireGuard/Test", row.tag)
        assertEquals("synthetic message", row.msg)
        assertEquals("2026-10-06 12:34:56.789", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(row.time!!))
    }

    @Test
    fun numericAndNamedUidDoNotShiftPidOrTid() {
        for (uid in listOf("10042", "u0_a42", "root")) {
            val row = LogLineParser.parse("10-06 12:34:56.789 $uid 1234 5678 W Native tag: synthetic", "2026")!!
            assertEquals(1234, row.pid)
            assertEquals(5678, row.tid)
            assertEquals("Native tag", row.tag)
        }
    }

    @Test
    fun continuationAndMalformedHeadersAreNotParsedRows() {
        for (line in listOf("    at synthetic.frame(Test.kt:1)", "--------- beginning of main", "10-06 12:34:56x789 1 2 I Tag: bad separator", "10-06 12:34:56.789 9999999999999 2 I Tag: bad pid")) {
            assertNull(LogLineParser.parse(line, "2026"))
        }
    }

    @Test
    fun invalidDateKeepsMessageWithoutInventingTimestamp() {
        val row = LogLineParser.parse("13-40 12:34:56.789 1 2 E Tag: synthetic", "2026")!!
        assertNull(row.time)
        assertEquals("synthetic", row.msg)
    }

    @Test
    fun filtersAndErrorRailIncludeNativeAssertAndFatalLevels() {
        assertNull(LogLineParser.levelsForFilter(LogLevelFilter.ALL))
        assertEquals(setOf("V", "D"), LogLineParser.levelsForFilter(LogLevelFilter.DEBUG))
        assertEquals(setOf("I"), LogLineParser.levelsForFilter(LogLevelFilter.INFO))
        assertEquals(setOf("W"), LogLineParser.levelsForFilter(LogLevelFilter.WARN))
        assertEquals(setOf("E", "A", "F"), LogLineParser.levelsForFilter(LogLevelFilter.ERROR))
        for (level in listOf("E", "A", "F")) assertTrue(LogLineParser.isError(level))
        for (level in listOf("V", "D", "I", "W")) assertFalse(LogLineParser.isError(level))
    }
}
