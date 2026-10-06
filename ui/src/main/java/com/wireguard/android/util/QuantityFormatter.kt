/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import com.wireguard.android.Application
import com.wireguard.android.R
import java.util.Locale
import java.util.concurrent.TimeUnit

object QuantityFormatter {
    fun formatBytes(bytes: Long): String {
        val context = Application.get().applicationContext
        val unit = when {
            bytes < KIB -> return context.getString(R.string.transfer_bytes, bytes)
            bytes < MIB -> ByteUnit(KIB, R.string.transfer_kibibytes)
            bytes < GIB -> ByteUnit(MIB, R.string.transfer_mibibytes)
            bytes < TIB -> ByteUnit(GIB, R.string.transfer_gibibytes)
            else -> ByteUnit(TIB, R.string.transfer_tibibytes)
        }
        return context.getString(unit.stringRes, scaledValue(bytes, unit.bytes))
    }

    fun formatEpochAgo(epochMillis: Long): String {
        var span = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - epochMillis)
        if (span <= 0L)
            return "0s"
        val days = TimeUnit.SECONDS.toDays(span)
        span -= TimeUnit.DAYS.toSeconds(days)
        val hours = TimeUnit.SECONDS.toHours(span)
        span -= TimeUnit.HOURS.toSeconds(hours)
        val minutes = TimeUnit.SECONDS.toMinutes(span)
        span -= TimeUnit.MINUTES.toSeconds(minutes)
        val seconds = span
        return when {
            days > 0L -> String.format(Locale.US, "%02dd %02dh", days, hours)
            hours > 0L -> String.format(Locale.US, "%02dh %02dm", hours, minutes)
            minutes > 0L -> String.format(Locale.US, "%02dm %02ds", minutes, seconds)
            else -> String.format(Locale.US, "%ds", seconds)
        }
    }

    private fun scaledValue(bytes: Long, unit: Long): Double {
        var whole = bytes / unit
        var cents = ((bytes % unit) * 100L + unit / 2L) / unit
        if (cents == 100L) {
            whole += 1L
            cents = 0L
        }
        return whole.toDouble() + cents.toDouble() / 100.0
    }

    private data class ByteUnit(val bytes: Long, val stringRes: Int)

    private const val KIB = 1024L
    private const val MIB = KIB * 1024L
    private const val GIB = MIB * 1024L
    private const val TIB = GIB * 1024L
}
