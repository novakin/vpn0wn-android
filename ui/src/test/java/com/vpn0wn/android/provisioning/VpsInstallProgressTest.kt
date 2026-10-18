/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpsInstallProgressTest {
    @Test fun singleByteWritesFollowOutputStreamLowByteSemantics() {
        val reader = VpsInstallProgressReader()
        write(reader, VpsInstallProgress.PREPARING.marker)
        reader.write(0x10a)
        assertEquals(listOf(VpsInstallProgress.PREPARING), reader.drain())
    }

    @Test fun onlyCompleteExactAllowlistedLinesBecomeProgress() {
        val reader = VpsInstallProgressReader()
        write(reader, "PrivateKey=synthetic-secret\nvpn0wn-progress:install:prepare")
        assertTrue(reader.drain().isEmpty())
        write(reader, "\n${VpsInstallProgress.PACKAGES.marker}\n")
        assertEquals(listOf(VpsInstallProgress.PREPARING, VpsInstallProgress.PACKAGES), reader.drain())
        assertTrue(reader.drain().isEmpty())
    }

    @Test fun prefixedSuffixedCrAndOversizedLinesCannotLeakOrForgePhases() {
        val reader = VpsInstallProgressReader()
        val marker = VpsInstallProgress.PREPARING.marker
        write(reader, "secret $marker\n${marker} secret\n$marker\r\n${"x".repeat(1000)}$marker\n")
        assertTrue(reader.drain().isEmpty())
        write(reader, "$marker\n")
        assertEquals(listOf(VpsInstallProgress.PREPARING), reader.drain())
    }

    @Test fun repeatsAndOutOfOrderPhasesRemainBoundedAndCannotRewind() {
        val reader = VpsInstallProgressReader()
        repeat(1000) {
            write(reader, "${VpsInstallProgress.PACKAGES.marker}\n${VpsInstallProgress.PREPARING.marker}\n")
        }
        write(reader, "${VpsInstallProgress.VERIFYING.marker}\n${VpsInstallProgress.STARTING.marker}\n")
        assertEquals(listOf(VpsInstallProgress.PACKAGES, VpsInstallProgress.VERIFYING), reader.drain())
    }

    private fun write(reader: VpsInstallProgressReader, text: String) = text.toByteArray().forEach { reader.write(it.toInt()) }
}
