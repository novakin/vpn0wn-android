/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.provisioning

import org.junit.Assert.assertEquals
import org.junit.Test

class VpsProvisioningRequestTest {
    @Test
    fun installArgsIncludeRequiredFieldsInCommandContractOrder() {
        val request = VpsInstallRequest(
            endpoint = "203.0.113.10",
            cidr = "10.8.0.0/24",
            dns = "1.1.1.1",
            port = 51821,
        )

        assertEquals(
            listOf(
                "--endpoint", "203.0.113.10",
                "--port", "51821",
                "--cidr", "10.8.0.0/24",
                "--dns", "1.1.1.1",
            ),
            request.installArgs(),
        )
    }

    @Test
    fun installArgsAppendForceOnlyWhenRequested() {
        val request = VpsInstallRequest(
            endpoint = "203.0.113.10",
            cidr = "10.8.0.0/24",
            dns = "1.1.1.1",
            force = true,
        )

        assertEquals(
            listOf(
                "--endpoint", "203.0.113.10",
                "--port", "51820",
                "--cidr", "10.8.0.0/24",
                "--dns", "1.1.1.1",
                "--force",
            ),
            request.installArgs(),
        )
    }

    @Test
    fun addPeerArgsOmitDnsWhenUnset() {
        val request = VpsAddPeerRequest(
            name = "phone",
            allowedIps = "0.0.0.0/0,::/0",
        )

        assertEquals(
            listOf("--name", "phone", "--allowed-ips", "0.0.0.0/0,::/0"),
            request.args(),
        )
    }

    @Test
    fun addPeerArgsIncludeDnsWhenSet() {
        val request = VpsAddPeerRequest(
            name = "phone",
            allowedIps = "0.0.0.0/0,::/0",
            dns = "9.9.9.9",
        )

        assertEquals(
            listOf("--name", "phone", "--dns", "9.9.9.9", "--allowed-ips", "0.0.0.0/0,::/0"),
            request.args(),
        )
    }
}
