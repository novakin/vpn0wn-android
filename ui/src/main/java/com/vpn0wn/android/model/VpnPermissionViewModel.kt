/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import androidx.lifecycle.ViewModel

internal class VpnPermissionViewModel : ViewModel() {
    var request: Request? = null

    data class Request(val tunnel: ObservableTunnel, val checked: Boolean)
}
