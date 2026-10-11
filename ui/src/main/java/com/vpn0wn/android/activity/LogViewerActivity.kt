/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.vpn0wn.android.activity

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
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import com.vpn0wn.android.BuildConfig
import com.vpn0wn.android.R
import com.vpn0wn.android.databinding.LogViewerActivityBinding
import com.vpn0wn.crypto.KeyPair
import com.vpn0wn.android.util.LogBuffer
import com.vpn0wn.android.util.LogLevelFilter
import com.vpn0wn.android.util.LogLineParser
import com.vpn0wn.android.util.LogcatStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LogViewerActivity : AppCompatActivity() {
    private lateinit var binding: LogViewerActivityBinding
    private lateinit var logAdapter: LogEntryAdapter
    private val logBuffer by lazy { LogBuffer(year) }
    private var recyclerView: RecyclerView? = null
    private var autoScroll = true
    private var lastUri: Uri? = null
    private var logJob: Job? = null
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
                R.id.filter_debug   -> LogLevelFilter.DEBUG
                R.id.filter_info    -> LogLevelFilter.INFO
                R.id.filter_warn    -> LogLevelFilter.WARN
                R.id.filter_error   -> LogLevelFilter.ERROR
                else -> LogLevelFilter.ALL
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
    }

    private fun restartLogStream() {
        stopLogStream()
        logBuffer.clear()
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
        val snapshot = withContext(Dispatchers.Main.immediate) { logBuffer.rawSnapshot() }
        return withContext(Dispatchers.Default) {
            snapshot.joinToString(separator = "\n", postfix = if (snapshot.isEmpty()) "" else "\n").toByteArray(Charsets.UTF_8)
        }
    }

    private suspend fun shareLog() {
        val log = rawLogBytes()
        val uri = publishLog(log)
        val shareIntent = ShareCompat.IntentBuilder(this)
            .setType("text/plain")
            .setSubject(getString(R.string.log_export_subject))
            .setStream(uri)
            .setChooserTitle(R.string.log_export_title)
            .createChooserIntent()
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        grantUriPermission("android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        revokeLastActivityResultLauncher.launch(shareIntent)
    }

    @MainThread
    private fun publishLog(log: ByteArray): Uri {
        revokeLastUri()
        val key = KeyPair().privateKey.toHex()
        LOGS[key] = log
        val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.exported-log/$key")
        lastUri = uri
        return uri
    }

    private suspend fun streamingLog(generation: Int) {
        var tailLines = LOG_INITIAL_TAIL_LINES
        while (generation == logGeneration) {
            streamLogcatProcess(generation, tailLines)
            if (generation != logGeneration) break
            tailLines = LOG_RESTART_TAIL_LINES
            Log.w(TAG, "Log stream ended; restarting")
            delay(LOG_RESTART_DELAY_MS)
        }
    }

    private suspend fun streamLogcatProcess(generation: Int, tailLines: Int) {
        val builder = ProcessBuilder("logcat", "-b", "all", "-v", "threadtime", "-T", tailLines.toString(), "*:V")
            .redirectErrorStream(true)
        builder.environment()["LC_ALL"] = "C"
        try {
            val process = builder.start()
            LogcatStream.read(process) { batch ->
                withContext(Dispatchers.Main.immediate) { applyLogBatch(generation, batch) }
            }
        } catch (e: IOException) {
            if (generation == logGeneration) Log.e(TAG, "Log stream failed", e)
        }
    }

    @MainThread
    private fun applyLogBatch(generation: Int, batch: List<String>) {
        if (generation != logGeneration) return
        val changes = logBuffer.append(batch)
        logAdapter.applyChanges(changes)
        if (autoScroll) scrollLogToBottom()
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
        else     -> if (LogLineParser.isError(level)) colorError else Color.TRANSPARENT
    }

    private fun scrollLogToBottom() {
        if (logAdapter.itemCount > 0)
            recyclerView?.scrollToPosition(logAdapter.itemCount - 1)
    }

    companion object {
        private val LOGS: MutableMap<String, ByteArray> = ConcurrentHashMap()
        private const val LOG_INITIAL_TAIL_LINES = 500
        private const val LOG_RESTART_DELAY_MS = 1000L
        private const val LOG_RESTART_TAIL_LINES = 50
        private const val TAG = "vpn0wn/LogViewerActivity"
    }

    private inner class LogEntryAdapter : RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {
        private val timeFormatter by lazy { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

        private inner class ViewHolder(
            layout: View,
            val levelBorder: View,
            val logTime: MaterialTextView,
            val logLevel: MaterialTextView,
            val logTag: MaterialTextView,
            val logMsg: MaterialTextView,
            var isSingleLine: Boolean = true
        ) : RecyclerView.ViewHolder(layout)

        override fun getItemCount() = logBuffer.size

        fun setLevelFilter(filter: LogLevelFilter) {
            logBuffer.setFilter(filter)
            notifyDataSetChanged()
        }

        fun reset() = notifyDataSetChanged()

        fun applyChanges(changes: LogBuffer.Changes) {
            if (changes.removed > 0) notifyItemRangeRemoved(0, changes.removed)
            if (changes.inserted > 0) notifyItemRangeInserted(changes.insertedStart, changes.inserted)
            changes.changed?.let { notifyItemChanged(it) }
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
            val line = logBuffer[position]

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
                m.addRow(arrayOf<Any>("vpn0wn-log.txt", it.size.toLong()))
                m
            }

        override fun onCreate(): Boolean = true

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun getType(uri: Uri): String? = logForUri(uri)?.let { "text/plain" }

        override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? =
            getType(uri)?.let { if (compareMimeTypes(it, mimeTypeFilter)) arrayOf(it) else null }

        internal fun writeLog(output: OutputStream, log: ByteArray?) {
            try {
                output.use { it.write(requireNotNull(log)) }
            } catch (_: IOException) {
                Log.d(TAG, "Log export pipe write failed; receiver may have closed it")
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (mode != "r") return null
            val log = logForUri(uri) ?: return null
            return openPipeHelper(uri, "text/plain", null, log) { output, _, _, _, l ->
                writeLog(ParcelFileDescriptor.AutoCloseOutputStream(output), l)
            }
        }
    }
}
