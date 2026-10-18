/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

data class VpsBackupComparison(
    val backupCapturedAtUtc: String,
    val observedAtUtc: String,
    val configTextMatches: Boolean,
    val onlyInBackup: List<String>,
    val onlyOnObservedServer: List<String>,
    val differentBodies: List<String>,
) {
    companion object {
        internal fun compare(backup: VpsBackupResult, observed: VpsBackupResult): VpsBackupComparison {
            val backupRecords = backup.revoked.associate { it.archiveName to it.config }
            val observedRecords = observed.revoked.associate { it.archiveName to it.config }
            return VpsBackupComparison(
                backupCapturedAtUtc = backup.capturedAtUtc,
                observedAtUtc = observed.capturedAtUtc,
                configTextMatches = backup.config == observed.config,
                onlyInBackup = (backupRecords.keys - observedRecords.keys).sorted(),
                onlyOnObservedServer = (observedRecords.keys - backupRecords.keys).sorted(),
                differentBodies = backupRecords.keys.intersect(observedRecords.keys)
                    .filter { backupRecords[it] != observedRecords[it] }.sorted(),
            )
        }
    }
}
