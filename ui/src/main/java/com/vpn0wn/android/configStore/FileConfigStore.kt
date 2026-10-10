/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.configStore

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.vpn0wn.android.R
import com.vpn0wn.config.BadConfigException
import com.vpn0wn.config.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Configuration store that uses a `wg-quick`-style file for each configured tunnel.
 */
class FileConfigStore(private val context: Context) : ConfigStore {
    @Throws(IOException::class)
    @Synchronized
    override fun create(name: String, config: Config): Config {
        Log.d(TAG, "Creating configuration for tunnel $name")
        val file = fileFor(name)
        if (file.exists())
            throw IOException(context.getString(R.string.config_file_exists_error, file.name))
        write(file, config)
        return config
    }

    @Throws(IOException::class)
    @Synchronized
    override fun delete(name: String) {
        Log.d(TAG, "Deleting configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.delete())
            throw IOException(context.getString(R.string.config_delete_error, file.name))
    }

    @Synchronized
    override fun enumerate(): Set<String> {
        return context.fileList()
            .filter { it.endsWith(".conf") }
            .map { it.substring(0, it.length - ".conf".length) }
            .toSet()
    }

    private fun fileFor(name: String): File {
        return File(context.filesDir, "$name.conf")
    }

    @Throws(BadConfigException::class, IOException::class)
    @Synchronized
    override fun load(name: String): Config {
        FileInputStream(fileFor(name)).use { stream -> return Config.parse(stream) }
    }

    @Throws(IOException::class)
    @Synchronized
    override fun rename(name: String, replacement: String) {
        Log.d(TAG, "Renaming configuration for tunnel $name to $replacement")
        val file = fileFor(name)
        val replacementFile = fileFor(replacement)
        if (replacementFile.exists()) throw IOException(context.getString(R.string.config_exists_error, replacement))
        try {
            Os.rename(file.path, replacementFile.path)
        } catch (e: ErrnoException) {
            throw IOException(context.getString(R.string.config_rename_error, file.name), e)
        }
    }

    @Throws(IOException::class)
    @Synchronized
    override fun save(name: String, config: Config): Config {
        Log.d(TAG, "Saving configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.isFile)
            throw FileNotFoundException(context.getString(R.string.config_not_found_error, file.name))
        write(file, config)
        return config
    }

    private fun write(file: File, config: Config) {
        val temporary = File.createTempFile("wireguard-", ".tmp", context.filesDir)
        try {
            context.openFileOutput(temporary.name, Context.MODE_PRIVATE).use { stream ->
                stream.write(config.toWgQuickString().toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Os.rename(temporary.path, file.path)
            } catch (e: ErrnoException) {
                throw IOException(context.getString(R.string.config_rename_error, file.name), e)
            }
        } finally {
            if (temporary.exists() && !temporary.delete())
                Log.w(TAG, "Couldn't delete temporary configuration file")
        }
    }

    companion object {
        private const val TAG = "WireGuard/FileConfigStore"
    }
}
