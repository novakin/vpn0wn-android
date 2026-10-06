/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

internal data class ParsedLogLine(val pid: Int, val tid: Int, val time: Date?, val level: String, val tag: String, var msg: String)

internal object LogLineParser {
    private const val TIME = 1
    private const val PID = 2
    private const val TID = 3
    private const val LEVEL = 4
    private const val TAG = 5
    private const val MESSAGE = 6
    private val threadtimeLine = Pattern.compile(
        "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})(?:\\s+[0-9A-Za-z_]+)?\\s+(\\d+)\\s+(\\d+)\\s+([A-Z])\\s+(.+?)\\s*: (.*)$"
    )

    fun parse(line: String, year: String): ParsedLogLine? {
        val match = threadtimeLine.matcher(line)
        if (!match.matches()) return null
        val pid = match.group(PID)!!.toIntOrNull()
        val tid = match.group(TID)!!.toIntOrNull()
        return if (pid != null && tid != null) {
            ParsedLogLine(
                pid = pid,
                tid = tid,
                time = parseTime(year, match.group(TIME)!!),
                level = match.group(LEVEL)!!,
                tag = match.group(TAG)!!,
                msg = match.group(MESSAGE)!!
            )
        } else null
    }

    fun levelsForFilter(filter: LogLevelFilter): Set<String>? = when (filter) {
        LogLevelFilter.ALL -> null
        LogLevelFilter.DEBUG -> setOf("V", "D")
        LogLevelFilter.INFO -> setOf("I")
        LogLevelFilter.WARN -> setOf("W")
        LogLevelFilter.ERROR -> setOf("E", "A", "F")
    }

    fun isError(level: String) = level in levelsForFilter(LogLevelFilter.ERROR).orEmpty()

    private fun parseTime(year: String, time: String): Date? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { isLenient = false }.parse("$year-$time")
    } catch (_: ParseException) {
        null
    }
}

internal enum class LogLevelFilter { ALL, DEBUG, INFO, WARN, ERROR }
