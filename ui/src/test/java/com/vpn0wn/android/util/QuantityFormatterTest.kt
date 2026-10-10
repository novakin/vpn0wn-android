/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import com.vpn0wn.android.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuantityFormatterTest {
    @Test
    fun formatBytesUsesExpectedUnits() {
        assertEquals("512 B", QuantityFormatter.formatBytes(512))
        assertEquals("1.00 KiB", QuantityFormatter.formatBytes(1024))
        assertEquals("1.00 MiB", QuantityFormatter.formatBytes(1024L * 1024L))
        assertEquals("1.00 GiB", QuantityFormatter.formatBytes(1024L * 1024L * 1024L))
        assertEquals("1.00 TiB", QuantityFormatter.formatBytes(1024L * 1024L * 1024L * 1024L))
        assertEquals("5120.00 TiB", QuantityFormatter.formatBytes(5L * 1024L * 1024L * 1024L * 1024L * 1024L))
    }

    @Test
    fun formatEpochAgoUsesCompactNeutralText() {
        val now = System.currentTimeMillis()

        assertEquals("0s", QuantityFormatter.formatEpochAgo(now + 1000L))
        assertEquals("7s", QuantityFormatter.formatEpochAgo(now - 7_000L))
        assertEquals("01m 05s", QuantityFormatter.formatEpochAgo(now - 65_000L))
        assertEquals("02h 03m", QuantityFormatter.formatEpochAgo(now - ((2L * 60L + 3L) * 60L * 1000L)))
        assertEquals("01d 02h", QuantityFormatter.formatEpochAgo(now - ((26L * 60L * 60L) * 1000L)))
    }
}
