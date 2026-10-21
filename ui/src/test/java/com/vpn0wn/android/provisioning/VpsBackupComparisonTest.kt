/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import org.junit.Assert.*
import org.junit.Test

class VpsBackupComparisonTest {
    @Test fun captureDatesAndDirectoryOrderDoNotChangeExactBodyComparison() {
        val backup = snapshot(listOf(
            VpsRevokedBackup("phone.conf", "old private body\n"),
            VpsRevokedBackup("phone.conf.ABC123", "new private body\n"),
        ))
        val observed = backup.copy(capturedAtUtc = "2026-10-18T12:00:00Z", revoked = backup.revoked.reversed())
        val comparison = VpsBackupComparison.compare(backup, observed)
        assertTrue(comparison.configTextMatches)
        assertTrue(comparison.onlyInBackup.isEmpty())
        assertTrue(comparison.onlyOnObservedServer.isEmpty())
        assertTrue(comparison.differentBodies.isEmpty())
        assertEquals(backup.capturedAtUtc, comparison.backupCapturedAtUtc)
        assertEquals(observed.capturedAtUtc, comparison.observedAtUtc)
        assertFalse(comparison.toString().contains("private"))
    }

    @Test fun fullBasenamesAndEveryBodyCharacterRemainSignificant() {
        val backup = snapshot(listOf(
            VpsRevokedBackup("phone.conf", "same body\n"),
            VpsRevokedBackup("phone.conf.ABC123", "same body\n"),
            VpsRevokedBackup("phone.conf.00000000-0000-0000-0000-000000000001", "private body\r\n"),
            VpsRevokedBackup("tablet.conf", "private body\t"),
        ))
        val observed = snapshot(listOf(
            VpsRevokedBackup("phone.conf", "same body\n"),
            VpsRevokedBackup("phone.conf.DEF456", "same body\n"),
            VpsRevokedBackup("phone.conf.00000000-0000-0000-0000-000000000001", "private body\n"),
            VpsRevokedBackup("tablet.conf", "private body "),
        )).copy(config = backup.config + "\n")
        val comparison = VpsBackupComparison.compare(backup, observed)
        assertFalse(comparison.configTextMatches)
        assertEquals(listOf("phone.conf.ABC123"), comparison.onlyInBackup)
        assertEquals(listOf("phone.conf.DEF456"), comparison.onlyOnObservedServer)
        assertEquals(listOf("phone.conf.00000000-0000-0000-0000-000000000001", "tablet.conf"), comparison.differentBodies)
        assertFalse(comparison.toString().contains("private body"))
    }

    @Test fun emptyHistoryStaysEmptyAndCommentsAreComparedAsText() {
        val backup = snapshot(emptyList())
        for (config in listOf(backup.config + "# note\n", backup.config.replace("\n", "\r\n"), backup.config.trimEnd())) {
            val comparison = VpsBackupComparison.compare(backup, backup.copy(config = config))
            assertFalse(comparison.configTextMatches)
            assertTrue(comparison.onlyInBackup.isEmpty())
            assertTrue(comparison.onlyOnObservedServer.isEmpty())
            assertTrue(comparison.differentBodies.isEmpty())
        }
    }

    private fun snapshot(records: List<VpsRevokedBackup>) = VpsBackupResult(
        "wg0", "# TUNNL_MANAGED 1\nprivate server body\n", records.size, 1, "2026-10-17T07:00:00Z", records,
    )
}
