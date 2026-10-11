/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.android.util

import androidx.annotation.MainThread
import androidx.collection.CircularArray

@MainThread
internal class LogBuffer(private val year: String, private val maxLines: Int = MAX_RETAINED_LINES, private val maxCharacters: Int = MAX_RETAINED_CHARACTERS) {
    private val lines = CircularArray<ParsedLogLine>()
    private val rawLines = CircularArray<String>()
    private val visiblePositions = ArrayList<Int>()
    private var levelFilter: Set<String>? = null
    private var rawCharacters = 0
    private var parsedCharacters = 0

    init {
        require(maxLines > 0 && maxCharacters > 0)
    }

    val size: Int get() = if (levelFilter == null) lines.size() else visiblePositions.size

    operator fun get(position: Int): ParsedLogLine = lines[if (levelFilter == null) position else visiblePositions[position]]

    fun setFilter(filter: LogLevelFilter) {
        levelFilter = LogLineParser.levelsForFilter(filter)
        visiblePositions.clear()
        val levels = levelFilter ?: return
        for (position in 0 until lines.size()) {
            if (lines[position].level in levels) visiblePositions.add(position)
        }
    }

    fun clear() {
        lines.clear()
        rawLines.clear()
        visiblePositions.clear()
        rawCharacters = 0
        parsedCharacters = 0
    }

    fun rawSnapshot(): List<String> = List(rawLines.size()) { rawLines[it] }

    fun append(batch: List<String>): Changes {
        val oldSize = lines.size()
        var removed = 0
        var priorModified = false
        for (raw in batch) {
            val line = raw.take(maxCharacters)
            rawLines.addLast(line)
            rawCharacters += line.length
            while (rawLines.size() > maxLines || rawCharacters > maxCharacters) {
                rawCharacters -= rawLines.popFirst().length
            }
            val parsed = LogLineParser.parse(line, year)
            if (parsed != null) {
                lines.addLast(parsed)
                parsedCharacters += parsed.tag.length + parsed.msg.length
            } else if (!lines.isEmpty()) {
                val last = lines[lines.size() - 1]
                val previousLength = last.msg.length
                last.msg = (last.msg + "\n" + line).takeLast((maxCharacters - last.tag.length).coerceAtLeast(0))
                parsedCharacters += last.msg.length - previousLength
                if (lines.size() + removed == oldSize) priorModified = true
            }
            while (lines.size() > maxLines || parsedCharacters > maxCharacters) {
                val evicted = lines.popFirst()
                parsedCharacters -= evicted.tag.length + evicted.msg.length
                removed++
            }
        }
        val removedOld = removed.coerceAtMost(oldSize)
        val insertedStart = oldSize - removedOld
        val levels = levelFilter
        val removedVisible: Int
        val visibleInsertStart: Int
        val insertedVisible: Int
        if (levels == null) {
            removedVisible = removedOld
            visibleInsertStart = insertedStart
            insertedVisible = lines.size() - insertedStart
        } else {
            removedVisible = visiblePositions.count { it < removed }
            visiblePositions.removeAll { it < removed }
            for (index in visiblePositions.indices) visiblePositions[index] -= removed
            visibleInsertStart = visiblePositions.size
            for (position in insertedStart until lines.size()) {
                if (lines[position].level in levels) visiblePositions.add(position)
            }
            insertedVisible = visiblePositions.size - visibleInsertStart
        }
        val changedSource = oldSize - removed - 1
        val changedVisible = if (priorModified && changedSource >= 0) {
            if (levels == null) changedSource else visiblePositions.indexOf(changedSource).takeIf { it >= 0 }
        } else null
        return Changes(removedVisible, visibleInsertStart, insertedVisible, changedVisible)
    }

    data class Changes(val removed: Int, val insertedStart: Int, val inserted: Int, val changed: Int?)

    companion object {
        private const val MAX_RETAINED_LINES = 20_000
        private const val MAX_RETAINED_CHARACTERS = 4 * 1024 * 1024
    }
}
