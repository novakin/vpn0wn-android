/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.activity

import java.util.concurrent.TimeUnit
import android.content.ClipDescription.compareMimeTypes
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.collection.CircularArray
import androidx.core.app.ShareCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.databinding.LogViewerActivityBinding
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

class LogViewerActivity : AppCompatActivity() {
    private lateinit var binding: LogViewerActivityBinding
    private lateinit var logAdapter: LogEntryAdapter
    private var logLines = CircularArray<LogLine>()
    private var rawLogLines = CircularArray<String>()
    private var recyclerView: RecyclerView? = null
    private var autoScroll = true
    private var lastUri: Uri? = null
    private var logJob: Job? = null
    @Volatile private var logProcess: Process? = null
    @Volatile private var logGeneration = 0

    private val year by lazy {
        val yearFormatter: DateFormat = SimpleDateFormat("yyyy", Locale.US)
        yearFormatter.format(Date())
    }

    private val colorInk2 by lazy { ResourcesCompat.getColor(resources, R.color.tunnl_ink_2, theme) }
    private val colorInk3 by lazy { ResourcesCompat.getColor(resources, R.color.tunnl_ink_3, theme) }
    private val colorWarn by lazy { ResourcesCompat.getColor(resources, R.color.tunnl_warn, theme) }
    private val colorError by lazy { ResourcesCompat.getColor(resources, R.color.tunnl_error, theme) }
    private val colorSignalDeep by lazy { ResourcesCompat.getColor(resources, R.color.tunnl_signal_deep, theme) }

    private val revokeLastActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revokeLastUri()
    }

    private fun revokeLastUri() {
        val uri = lastUri ?: return
        val key = uri.pathSegments.lastOrNull()
        if (key != null)
            LOGS.remove(key)
        revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lastUri = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LogViewerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        logAdapter = LogEntryAdapter()
        binding.recyclerView.apply {
            recyclerView = this
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
        }

        binding.filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val levelFilter = when (checkedIds.firstOrNull()) {
                R.id.filter_debug   -> setOf("V", "D")
                R.id.filter_info    -> setOf("I")
                R.id.filter_warn    -> setOf("W")
                R.id.filter_error   -> setOf("E", "A", "F")
                else -> null
            }
            logAdapter.setLevelFilter(levelFilter)
            scrollLogToBottom()
        }

        binding.backButton.setOnClickListener { finish() }
        binding.refreshButton.setOnClickListener { restartLogStream() }
        binding.shareButton.setOnClickListener {
            lifecycleScope.launch { shareLog() }
        }
        binding.liveToggle.setOnClickListener {
            autoScroll = !autoScroll
            updateLiveToggle()
            if (autoScroll)
                scrollLogToBottom()
        }

        updateLiveToggle()
        startLogStream()
    }

    override fun onDestroy() {
        stopLogStream()
        revokeLastUri()
        super.onDestroy()
    }

    private fun startLogStream() {
        stopLogStream()
        val generation = logGeneration
        logJob = lifecycleScope.launch(Dispatchers.IO) { streamingLog(generation) }
    }

    private fun stopLogStream() {
        ++logGeneration
        logJob?.cancel()
        logJob = null
        logProcess?.destroy()
    }

    private fun restartLogStream() {
        stopLogStream()
        logLines = CircularArray()
        rawLogLines = CircularArray()
        logAdapter.reset()
        val generation = logGeneration
        logJob = lifecycleScope.launch(Dispatchers.IO) { streamingLog(generation) }
    }

    private fun updateLiveToggle() {
        if (autoScroll) {
            binding.liveToggle.setText(R.string.tunnl_log_live)
            binding.liveToggle.setTextColor(colorSignalDeep)
        } else {
            binding.liveToggle.setText(R.string.tunnl_log_paused)
            binding.liveToggle.setTextColor(colorInk3)
        }
    }

    private suspend fun rawLogBytes(): ByteArray {
        val builder = StringBuilder()
        withContext(Dispatchers.IO) {
            for (i in 0 until rawLogLines.size()) {
                builder.append(rawLogLines[i])
                builder.append('\n')
            }
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    private suspend fun shareLog() {
        revokeLastUri()
        val key = KeyPair().privateKey.toHex()
        LOGS[key] = rawLogBytes()
        lastUri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.exported-log/$key")
        val shareIntent = ShareCompat.IntentBuilder(this)
            .setType("text/plain")
            .setSubject(getString(R.string.log_export_subject))
            .setStream(lastUri)
            .setChooserTitle(R.string.log_export_title)
            .createChooserIntent()
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        grantUriPermission("android", lastUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        revokeLastActivityResultLauncher.launch(shareIntent)
    }

    private suspend fun streamingLog(generation: Int) = withContext(Dispatchers.IO) {
        var useRestartTail = false
        while (generation == logGeneration) {
            streamLogcatProcess(generation, useRestartTail)
            if (generation != logGeneration)
                break
            useRestartTail = true
            Log.w(TAG, "Log stream ended; restarting")
            delay(LOG_RESTART_DELAY_MS)
        }
    }

    // EOF flush, generation invalidation and batch deferral have distinct exit/continue requirements.
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun streamLogcatProcess(generation: Int, useRestartTail: Boolean) {
        val command = mutableListOf("logcat", "-b", "all", "-v", "threadtime")
        if (useRestartTail) {
            command.add("-T")
            command.add(LOG_RESTART_TAIL_LINES.toString())
        }
        command.add("*:V")

        val builder = ProcessBuilder().command(command)
        builder.environment()["LC_ALL"] = "C"
        var process: Process? = null
        try {
            process = try {
                builder.start()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start log process", e)
                return
            }
            logProcess = process
            val stdout = BufferedReader(InputStreamReader(process!!.inputStream, StandardCharsets.UTF_8))

            var posStart = withContext(Dispatchers.Main.immediate) { logLines.size() }
            var timeLastNotify = System.nanoTime()
            var priorModified = false
            val bufferedLogLines = arrayListOf<LogLine>()
            var timeout = INITIAL_LOG_FLUSH_INTERVAL_NS

            suspend fun flushBufferedLogLines() {
                withContext(Dispatchers.Main.immediate) {
                    if (generation != logGeneration)
                        return@withContext
                    val hasInsertedLines = bufferedLogLines.isNotEmpty()
                    val hasChanges = priorModified || hasInsertedLines
                    if (priorModified) {
                        logAdapter.onLineChanged(posStart - 1)
                        priorModified = false
                    }
                    val fullLen = logLines.size() + bufferedLogLines.size
                    if (fullLen >= MAX_LOG_LINES) {
                        val numToRemove = fullLen - MAX_LOG_LINES + 1
                        logLines.removeFromStart(numToRemove)
                        logAdapter.onLinesRemovedFromStart(numToRemove)
                        posStart -= numToRemove
                    }
                    for (bufferedLine in bufferedLogLines) {
                        logLines.addLast(bufferedLine)
                    }
                    bufferedLogLines.clear()
                    val insertedCount = logLines.size() - posStart
                    if (insertedCount > 0)
                        logAdapter.onLinesInserted(posStart, insertedCount)
                    posStart = logLines.size()

                    if (hasChanges && autoScroll)
                        scrollLogToBottom()
                }
            }

            while (generation == logGeneration) {
                val line = stdout.readLine()
                if (line == null) {
                    flushBufferedLogLines()
                    break
                }
                if (generation != logGeneration)
                    break
                if (rawLogLines.size() >= MAX_LOG_LINES)
                    rawLogLines.popFirst()
                rawLogLines.addLast(line)
                val logLine = parseLine(line)
                if (logLine != null) {
                    bufferedLogLines.add(logLine)
                } else {
                    if (bufferedLogLines.isNotEmpty()) {
                        bufferedLogLines.last().msg += "\n$line"
                    } else if (!logLines.isEmpty()) {
                        logLines[logLines.size() - 1].msg += "\n$line"
                        priorModified = true
                    }
                }
                val timeNow = System.nanoTime()
                if (bufferedLogLines.size < MAX_BUFFERED_LINES && (timeNow - timeLastNotify) < timeout && stdout.ready())
                    continue
                timeout = LOG_FLUSH_INTERVAL_NS
                timeLastNotify = timeNow

                flushBufferedLogLines()
            }
            flushBufferedLogLines()
        } catch (e: IOException) {
            if (generation == logGeneration)
                Log.e(TAG, "Log stream failed", e)
        } finally {
            process?.destroy()
            if (logProcess === process)
                logProcess = null
        }
    }

    private fun parseTime(timeStr: String): Date? {
        val formatter: DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return try {
            formatter.parse("$year-$timeStr")
        } catch (e: ParseException) {
            null
        }
    }

    private fun parseLine(line: String): LogLine? {
        val m: Matcher = THREADTIME_LINE.matcher(line)
        return if (m.matches()) {
            LogLine(m.group(THREADTIME_PID)!!.toInt(), m.group(THREADTIME_TID)!!.toInt(), parseTime(m.group(THREADTIME_TIME)!!), m.group(THREADTIME_LEVEL)!!, m.group(THREADTIME_TAG)!!, m.group(THREADTIME_MESSAGE)!!)
        } else {
            null
        }
    }

    private fun levelToColor(level: String) = when (level) {
        "V", "D" -> colorInk3
        "I"      -> colorInk2
        "W"      -> colorWarn
        "E", "A", "F" -> colorError
        else     -> colorInk3
    }

    private fun borderColor(level: String) = when (level) {
        "W"      -> colorWarn
        "E", "A", "F" -> colorError
        else     -> Color.TRANSPARENT
    }

    private fun scrollLogToBottom() {
        if (logAdapter.itemCount > 0)
            recyclerView?.scrollToPosition(logAdapter.itemCount - 1)
    }

    private data class LogLine(val pid: Int, val tid: Int, val time: Date?, val level: String, val tag: String, var msg: String)

    companion object {
        private const val MAX_LOG_LINES = (1 shl 16) - 1
        private const val MAX_BUFFERED_LINES = (1 shl 14) - 1
        private val INITIAL_LOG_FLUSH_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(500)
        private val LOG_FLUSH_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(2500)
        private const val THREADTIME_TIME = 1
        private const val THREADTIME_PID = 2
        private const val THREADTIME_TID = 3
        private const val THREADTIME_LEVEL = 4
        private const val THREADTIME_TAG = 5
        private const val THREADTIME_MESSAGE = 6
        private val THREADTIME_LINE: Pattern =
            Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.\\d{3})(?:\\s+[0-9A-Za-z]+)?\\s+(\\d+)\\s+(\\d+)\\s+([A-Z])\\s+(.+?)\\s*: (.*)$")
        private val LOGS: MutableMap<String, ByteArray> = ConcurrentHashMap()
        private const val LOG_RESTART_DELAY_MS = 1000L
        private const val LOG_RESTART_TAIL_LINES = 50
        private const val TAG = "WireGuard/LogViewerActivity"
    }

    private inner class LogEntryAdapter : RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {
        private val timeFormatter by lazy { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
        private var levelFilter: Set<String>? = null
        private val visiblePositions = ArrayList<Int>()

        private inner class ViewHolder(
            layout: View,
            val levelBorder: View,
            val logTime: MaterialTextView,
            val logLevel: MaterialTextView,
            val logTag: MaterialTextView,
            val logMsg: MaterialTextView,
            var isSingleLine: Boolean = true
        ) : RecyclerView.ViewHolder(layout)

        override fun getItemCount() = if (levelFilter == null) logLines.size() else visiblePositions.size

        fun setLevelFilter(levelFilter: Set<String>?) {
            this.levelFilter = levelFilter
            rebuildVisiblePositions()
            notifyDataSetChanged()
        }

        fun reset() {
            visiblePositions.clear()
            notifyDataSetChanged()
        }

        fun onLinesInserted(positionStart: Int, itemCount: Int) {
            if (levelFilter == null) {
                notifyItemRangeInserted(positionStart, itemCount)
            } else {
                val filter = levelFilter ?: return
                var visibleInsertStart = -1
                var visibleInsertCount = 0
                for (sourcePosition in positionStart until positionStart + itemCount) {
                    if (logLines[sourcePosition].level !in filter) continue
                    if (visibleInsertStart == -1)
                        visibleInsertStart = visiblePositions.size
                    visiblePositions.add(sourcePosition)
                    visibleInsertCount++
                }
                if (visibleInsertCount > 0)
                    notifyItemRangeInserted(visibleInsertStart, visibleInsertCount)
            }
        }

        fun onLinesRemovedFromStart(itemCount: Int) {
            if (levelFilter == null) {
                notifyItemRangeRemoved(0, itemCount)
                return
            }
            val removedVisibleCount = visiblePositions.count { it < itemCount }
            visiblePositions.removeAll { it < itemCount }
            for (i in visiblePositions.indices)
                visiblePositions[i] = visiblePositions[i] - itemCount
            if (removedVisibleCount > 0)
                notifyItemRangeRemoved(0, removedVisibleCount)
        }

        fun onLineChanged(sourcePosition: Int) {
            if (sourcePosition < 0) return
            if (levelFilter == null) {
                notifyItemChanged(sourcePosition)
            } else {
                val visiblePosition = visiblePositions.indexOf(sourcePosition)
                if (visiblePosition >= 0)
                    notifyItemChanged(visiblePosition)
            }
        }

        private fun rebuildVisiblePositions() {
            visiblePositions.clear()
            val filter = levelFilter ?: return
            for (i in 0 until logLines.size()) {
                if (logLines[i].level in filter)
                    visiblePositions.add(i)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.log_viewer_entry, parent, false)
            return ViewHolder(
                layout = view,
                levelBorder = view.findViewById(R.id.level_border),
                logTime = view.findViewById(R.id.log_time),
                logLevel = view.findViewById(R.id.log_level),
                logTag = view.findViewById(R.id.log_tag),
                logMsg = view.findViewById(R.id.log_msg),
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val sourcePosition = if (levelFilter == null) position else visiblePositions[position]
            val line = logLines[sourcePosition]

            holder.levelBorder.setBackgroundColor(borderColor(line.level))
            holder.logTime.text = line.time?.let { timeFormatter.format(it) } ?: "--:--:--.---"
            holder.logLevel.apply {
                text = line.level
                setTextColor(levelToColor(line.level))
            }
            holder.logTag.text = line.tag
            holder.logMsg.apply {
                if (holder.isSingleLine) setSingleLine() else isSingleLine = false
                text = line.msg
                setOnClickListener {
                    holder.isSingleLine = !holder.isSingleLine
                    isSingleLine = holder.isSingleLine
                }
            }
        }
    }

    class ExportedLogContentProvider : ContentProvider() {
        private fun logForUri(uri: Uri): ByteArray? = LOGS[uri.pathSegments.lastOrNull()]

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            logForUri(uri)?.let {
                val m = MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), 1)
                m.addRow(arrayOf<Any>("wireguard-log.txt", it.size.toLong()))
                m
            }

        override fun onCreate(): Boolean = true

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun getType(uri: Uri): String? = logForUri(uri)?.let { "text/plain" }

        override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? =
            getType(uri)?.let { if (compareMimeTypes(it, mimeTypeFilter)) arrayOf(it) else null }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (mode != "r") return null
            val log = logForUri(uri) ?: return null
            return openPipeHelper(uri, "text/plain", null, log) { output, _, _, _, l ->
                try {
                    FileOutputStream(output.fileDescriptor).write(l!!)
                } catch (_: IOException) {
                }
            }
        }
    }
}
