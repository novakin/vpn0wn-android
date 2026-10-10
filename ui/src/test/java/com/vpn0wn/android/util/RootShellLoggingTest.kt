/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RootShellLoggingTest {
    @Before
    fun clearLogs() = ShadowLog.clear()

    @Test
    fun preservesKernelDumpWithoutLoggingCommandOrKeys() {
        val privateKey = "synthetic-private-key"
        val presharedKey = "synthetic-preshared-key"
        val output = listOf("$privateKey\tpublic-key\t51820\toff", "peer-key\t$presharedKey\tendpoint")
        val shell = ScriptedRootShell(ApplicationProvider.getApplicationContext(), output, emptyList(), 0)
        val received = mutableListOf<String>()

        assertEquals(0, shell.run(received, "wg show wg0 dump; echo synthetic-command-secret"))
        assertEquals(output, received)
        assertFalse(logs().contains(privateKey))
        assertFalse(logs().contains(presharedKey))
        assertFalse(logs().contains("synthetic-command-secret"))
    }

    @Test
    fun preservesExitStatusWithoutLoggingFailedCommandOutput() {
        val secret = "synthetic-stderr-secret"
        val shell = ScriptedRootShell(ApplicationProvider.getApplicationContext(), listOf(secret), listOf(secret), 7)

        assertEquals(7, shell.run(null, "failed-command"))
        assertFalse(logs().contains(secret))
    }

    private fun logs(): String = ShadowLog.getLogs().joinToString("\n") {
        it.msg + it.throwable?.stackTraceToString().orEmpty()
    }

    private class ScriptedRootShell(
        context: Context,
        private val output: List<String>,
        private val errors: List<String>,
        private val exitCode: Int,
    ) : RootShell(context) {
        override fun start() {
            val script = ByteArrayOutputStream()
            setField("stdin", object : OutputStreamWriter(script, StandardCharsets.UTF_8) {
                override fun flush() {
                    super.flush()
                    val marker = script.toString(StandardCharsets.UTF_8.name()).substringAfter("echo ").substringBefore(';')
                    setField("stdout", stream(marker, output))
                    setField("stderr", stream(marker, errors))
                }
            })
        }

        private fun stream(marker: String, lines: List<String>): BufferedReader =
            BufferedReader(StringReader((listOf(marker) + lines + "$marker $exitCode").joinToString("\n", postfix = "\n")))

        private fun setField(name: String, value: Any) {
            RootShell::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
    }
}
