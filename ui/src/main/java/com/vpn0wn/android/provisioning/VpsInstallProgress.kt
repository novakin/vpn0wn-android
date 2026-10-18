/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

enum class VpsInstallProgress(internal val marker: String) {
    PREPARING("vpn0wn-progress:install:prepare"),
    PACKAGES("vpn0wn-progress:install:packages"),
    CONFIGURING("vpn0wn-progress:install:configure"),
    STARTING("vpn0wn-progress:install:start"),
    VERIFYING("vpn0wn-progress:install:verify"),
}

internal class VpsInstallProgressReader {
    private val line = StringBuilder()
    private val pending = mutableListOf<VpsInstallProgress>()
    private var discarded = false
    private var lastOrdinal = -1

    @Synchronized
    fun write(value: Int) {
        val byte = value and BYTE_MASK
        if (byte == '\n'.code) {
            if (!discarded) {
                val phase = VpsInstallProgress.entries.firstOrNull { it.marker == line.toString() }
                if (phase != null && phase.ordinal > lastOrdinal) {
                    pending.add(phase)
                    lastOrdinal = phase.ordinal
                }
            }
            line.setLength(0)
            discarded = false
        } else if (!discarded) {
            if (line.length == MAX_LINE_BYTES) {
                line.setLength(0)
                discarded = true
            } else {
                line.append(byte.toChar())
            }
        }
    }

    @Synchronized
    fun drain(): List<VpsInstallProgress> = pending.toList().also { pending.clear() }

    private companion object {
        const val MAX_LINE_BYTES = 64
        const val BYTE_MASK = 0xff
    }
}
