/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

data class VpsPeerRotationResult(
    val operationId: String,
    val name: String,
    val serverPublicKey: String,
    val oldPublicKey: String,
    val newPublicKey: String,
    val running: Boolean,
    val mode: Mode,
    val cleanupPath: String,
) {
    enum class Mode { ROTATE, VERIFY }

    internal fun matches(attempt: PendingVpsRotate): Boolean = operationId == attempt.operationId && name == attempt.name &&
        serverPublicKey == attempt.serverPublicKey && oldPublicKey == attempt.oldClientPublicKey && newPublicKey == attempt.newClientPublicKey
}
