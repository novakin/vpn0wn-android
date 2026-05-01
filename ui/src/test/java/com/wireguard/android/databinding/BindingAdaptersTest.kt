/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.databinding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BindingAdaptersTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun shortKeyReturnsDashForEmptyKey() {
        assertEquals("—", BindingAdapters.shortKey(null))
        assertEquals("—", BindingAdapters.shortKey(""))
    }

    @Test
    fun shortKeyLeavesShortKeyUnchanged() {
        assertEquals("abc123", BindingAdapters.shortKey("abc123"))
    }

    @Test
    fun shortKeyTruncatesLongKeyWithMiddleEllipsis() {
        assertEquals("abcdef…uvwxyz", BindingAdapters.shortKey("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun emptyHomeHeaderShowsInactiveSummary() {
        assertEquals("Vpn0wn · 0 CONFIGURED · INACTIVE", BindingAdapters.homeHeaderSummary(context, emptyList()))
    }

    @Test
    fun emptyHomeProtectionShowsUnprotected() {
        assertEquals("UNPROTECTED", BindingAdapters.homeProtectionLabel(context, emptyList()))
    }

    @Test
    fun emptyHomeExitIpShowsDash() {
        assertEquals("EXIT IP · —", BindingAdapters.homeExitIpLabel(emptyList()))
    }
}
