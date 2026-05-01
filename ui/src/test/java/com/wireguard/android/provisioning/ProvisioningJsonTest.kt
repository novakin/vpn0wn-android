/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningJsonTest {
    @Test
    fun parseDetectReadsServerCapabilities() {
        val result = ProvisioningJson.parseDetect(
            """
            {
              "ok": true,
              "os": "debian",
              "version": "12",
              "supported": true,
              "packageManager": "apt",
              "firewall": "nftables",
              "wireGuardInstalled": true,
              "configExists": false,
              "interface": "wg0",
              "endpoint": "203.0.113.10",
              "listenPort": "51820",
              "cidr": "10.8.0.0/24",
              "ipv4Forwarding": "enabled"
            }
            """.trimIndent(),
        )

        assertEquals("debian", result.os)
        assertEquals("12", result.version)
        assertTrue(result.supported)
        assertEquals("apt", result.packageManager)
        assertEquals("nftables", result.firewall)
        assertTrue(result.wireGuardInstalled)
        assertFalse(result.configExists)
        assertEquals("wg0", result.interfaceName)
        assertEquals("203.0.113.10", result.endpoint)
        assertEquals("51820", result.listenPort)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("enabled", result.ipv4Forwarding)
    }

    @Test
    fun parsePlanReadsStepsAndOverwriteFlag() {
        val result = ProvisioningJson.parsePlan(
            """
            {
              "ok": true,
              "endpoint": "203.0.113.10",
              "port": "51820",
              "cidr": "10.8.0.0/24",
              "dns": "1.1.1.1",
              "willOverwriteConfig": true,
              "steps": ["install wireguard-tools", "write wg0.conf"]
            }
            """.trimIndent(),
        )

        assertEquals("203.0.113.10", result.endpoint)
        assertEquals("51820", result.port)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("1.1.1.1", result.dns)
        assertTrue(result.willOverwriteConfig)
        assertEquals(listOf("install wireguard-tools", "write wg0.conf"), result.steps)
    }

    @Test
    fun parseInstallReadsCreatedServerConfig() {
        val result = ProvisioningJson.parseInstall(
            """
            {
              "ok": true,
              "interface": "wg0",
              "endpoint": "203.0.113.10",
              "port": "51820",
              "cidr": "10.8.0.0/24",
              "serverPublicKey": "server-key"
            }
            """.trimIndent(),
        )

        assertEquals("wg0", result.interfaceName)
        assertEquals("203.0.113.10", result.endpoint)
        assertEquals("51820", result.port)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("server-key", result.serverPublicKey)
    }

    @Test
    fun parseStatusReadsPeersAndCounters() {
        val result = ProvisioningJson.parseStatus(
            """
            {
              "ok": true,
              "interface": "wg0",
              "running": true,
              "endpoint": "203.0.113.10",
              "listenPort": "51820",
              "cidr": "10.8.0.0/24",
              "dns": "1.1.1.1",
              "peers": [
                {
                  "name": "phone",
                  "publicKey": "phone-key",
                  "allowedIps": "10.8.0.2/32",
                  "latestHandshake": "1710000000",
                  "rxBytes": "1024",
                  "txBytes": "2048"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("wg0", result.interfaceName)
        assertTrue(result.running)
        assertEquals("203.0.113.10", result.endpoint)
        assertEquals("51820", result.listenPort)
        assertEquals("10.8.0.0/24", result.cidr)
        assertEquals("1.1.1.1", result.dns)
        assertEquals(1, result.peers.size)
        assertEquals("phone", result.peers.single().name)
        assertEquals("phone-key", result.peers.single().publicKey)
        assertEquals("10.8.0.2/32", result.peers.single().allowedIps)
        assertEquals(1710000000L, result.peers.single().latestHandshake)
        assertEquals(1024L, result.peers.single().rxBytes)
        assertEquals(2048L, result.peers.single().txBytes)
    }

    @Test
    fun parseAddPeerReadsConfigPayload() {
        val result = ProvisioningJson.parseAddPeer(
            """
            {
              "ok": true,
              "name": "phone",
              "address": "10.8.0.2/32",
              "publicKey": "phone-key",
              "config": "[Interface]\nAddress = 10.8.0.2/32"
            }
            """.trimIndent(),
        )

        assertEquals("phone", result.name)
        assertEquals("10.8.0.2/32", result.address)
        assertEquals("phone-key", result.publicKey)
        assertTrue(result.config.contains("Address = 10.8.0.2/32"))
    }

    @Test
    fun parsePeerMutationReadsRemoveAndRevokeFlags() {
        val result = ProvisioningJson.parsePeerMutation(
            """
            {
              "ok": true,
              "name": "phone",
              "removed": true,
              "revoked": false,
              "archive": "/etc/wireguard/revoked/phone.conf"
            }
            """.trimIndent(),
        )

        assertEquals("phone", result.name)
        assertTrue(result.removed)
        assertFalse(result.revoked)
        assertEquals("/etc/wireguard/revoked/phone.conf", result.archive)
    }

    @Test
    fun parseBackupReadsBackupMetadata() {
        val result = ProvisioningJson.parseBackup(
            """
            {
              "ok": true,
              "interface": "wg0",
              "config": "[Interface]",
              "revokedCount": "3"
            }
            """.trimIndent(),
        )

        assertEquals("wg0", result.interfaceName)
        assertEquals("[Interface]", result.config)
        assertEquals(3, result.revokedCount)
    }

    @Test
    fun parseUninstallReadsResult() {
        val result = ProvisioningJson.parseUninstall(
            """
            {
              "ok": true,
              "uninstalled": true,
              "backup": "/tmp/wg0.conf",
              "packagesPurged": false
            }
            """.trimIndent(),
        )

        assertTrue(result.uninstalled)
        assertEquals("/tmp/wg0.conf", result.backup)
        assertFalse(result.packagesPurged)
    }

    @Test
    fun parseInvalidJsonThrowsProvisioningException() {
        val exception = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.parseDetect("[Interface]\nPrivateKey = secret")
        }

        assertEquals("Remote command returned invalid JSON", exception.message)
        assertFalse(exception.message.orEmpty().contains("secret"))
    }

    @Test
    fun parseErrorPayloadThrowsProvisioningException() {
        val exception = assertThrows(ProvisioningException::class.java) {
            ProvisioningJson.parseDetect(
                """{"ok":false,"error":"unsupported_os","message":"Debian 10"}""",
            )
        }

        assertEquals("unsupported_os: Debian 10", exception.message)
    }
}
