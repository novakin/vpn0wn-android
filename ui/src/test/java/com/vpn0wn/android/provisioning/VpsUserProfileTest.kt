/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import com.vpn0wn.config.Config
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyPair
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = Application::class, sdk = [28])
class VpsUserProfileTest {
    @Test
    fun generatedProfileRetainsOneCompleteCanonicalPayload() {
        val profile = VpsProfileFixture.profile()
        assertEquals(VpsProfileFixture.client.publicKey.toBase64(), profile.identity.clientPublicKey)
        assertEquals(VpsProfileFixture.serverKey.publicKey.toBase64(), profile.identity.serverPublicKey)
        assertEquals("phone", profile.name)
        assertEquals(profile.payload(), Config.parse(profile.payload().byteInputStream()).toWgQuickString())
        assertTrue(profile.payload().contains(VpsProfileFixture.client.privateKey.toBase64()))
        assertTrue(profile.payload().contains(VpsProfileFixture.psk.toBase64()))
        assertEquals("VpsUserProfile(redacted)", profile.toString())
        assertEquals("VpsAddPeerResult(redacted)", VpsProfileFixture.result().toString())
    }

    @Test
    fun explicitDnsAndRoutesMustMatchReturnedProfile() {
        val request = VpsProfileFixture.request.copy(dns = "9.9.9.9", allowedIps = "10.20.0.0/16, ::/0")
        val result = VpsProfileFixture.result().copy(config = VpsProfileFixture.text()
            .replace("1.1.1.1", "9.9.9.9").replace("0.0.0.0/0", "10.20.0.0/16, ::/0"))
        val profile = VpsProfileFixture.profile(request = request, result = result)
        assertEquals(profile.payload(), Config.parse(profile.payload().byteInputStream()).toWgQuickString())
        invalid { VpsProfileFixture.profile(request = request) }
    }

    @Test
    fun independentlyParsedProfilesCompareCompleteCanonicalContent() {
        val profile = VpsProfileFixture.profile()
        val reparsed = VpsUserProfile.decode(profile.identity, profile.name,
            "# equivalent input\n" + profile.payload().replace(" = ", "  =  "))
        assertNotSame(profile.config, reparsed.config)
        assertNotSame(profile.config.`interface`.keyPair, reparsed.config.`interface`.keyPair)
        assertTrue(profile.sameContent(reparsed))
    }

    @Test
    fun matchingIdentityAndNameCannotHideChangedPrivatePayload() {
        val profile = VpsProfileFixture.profile()
        val changed = listOf(
            profile.payload().replace(VpsProfileFixture.psk.toBase64(), Key.fromBytes(ByteArray(32) { 8 }).toBase64()),
            profile.payload().replace("203.0.113.10:51820", "203.0.113.11:51820"),
            profile.payload().replace("0.0.0.0/0", "10.8.0.0/24"),
            profile.payload().replace("DNS = 1.1.1.1", "DNS = 9.9.9.9"),
        )
        changed.forEach { text ->
            val different = VpsUserProfile.decode(profile.identity, profile.name, text)
            assertEquals(profile.identity, different.identity)
            assertEquals(profile.name, different.name)
            assertFalse(profile.sameContent(different))
        }
    }

    @Test
    fun emptyDnsUsesServerDefaultLikeAddPeerScript() {
        assertTrue(VpsProfileFixture.profile(request = VpsProfileFixture.request.copy(dns = ""))
            .sameContent(VpsProfileFixture.profile()))
    }

    @Test
    fun requestAndReceiptNameAndAddressMustAgree() {
        for (name in listOf("", "wrong", "invalid name", "x".repeat(33))) {
            invalid { VpsProfileFixture.profile(result = VpsProfileFixture.result().copy(name = name)) }
        }
        for (address in listOf("10.8.0.0", "10.8.0.1", "10.8.0.255", "10.9.0.2", "::2", "bad")) {
            invalid { VpsProfileFixture.profile(result = VpsProfileFixture.result().copy(address = address)) }
        }
        invalid { VpsProfileFixture.profile(result = VpsProfileFixture.result().copy(address = "10.8.0.3")) }
        invalid { VpsProfileFixture.profile(server = VpsProfileFixture.server.copy(cidr = "10.8.0.1/24")) }
        invalid { VpsProfileFixture.profile(server = VpsProfileFixture.server.copy(cidr = "10.8.0.0/16")) }
    }

    @Test
    fun keysDnsRoutesEndpointAndKeepaliveCannotChangeUnobserved() {
        val text = VpsProfileFixture.text()
        val changed = listOf(
            text.replace(VpsProfileFixture.client.privateKey.toBase64(), KeyPair().privateKey.toBase64()),
            text.replace(VpsProfileFixture.serverKey.publicKey.toBase64(), KeyPair().publicKey.toBase64()),
            text.replace("DNS = 1.1.1.1", "DNS = 9.9.9.9"),
            text.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 10.8.0.0/24"),
            text.replace("203.0.113.10:51820", "203.0.113.11:51820"),
            text.replace("203.0.113.10:51820", "203.0.113.10:51821"),
            text.replace("PersistentKeepalive = 25", "PersistentKeepalive = 30"),
            text.replace("PresharedKey = " + VpsProfileFixture.psk.toBase64(), ""),
            text.replace("Address = 10.8.0.2/32", "Address = 10.8.0.2/32\nMTU = 1280"),
            text.replace("Address = 10.8.0.2/32", "Address = 10.8.0.2/24"),
        )
        changed.forEach { config ->
            invalid { VpsProfileFixture.profile(result = VpsProfileFixture.result().copy(config = config)) }
        }
        for (port in listOf("0", "65536", "bad")) {
            invalid { VpsProfileFixture.profile(server = VpsProfileFixture.server.copy(listenPort = port)) }
        }
    }

    @Test
    fun repeatedSectionsExtraPeersAndOversizedOrMalformedPayloadsFailSafely() {
        val text = VpsProfileFixture.text()
        val changed = listOf(
            text + "\n[Interface]\n",
            text + "\n[Peer]\nPublicKey = " + KeyPair().publicKey.toBase64(),
            text.replace("[Interface]", "[Peer]"),
            text.replace("[Peer]", "[Unknown]"),
            text.replace("PrivateKey = " + VpsProfileFixture.client.privateKey.toBase64(), "PrivateKey = secret-invalid"),
            "x".repeat(VpsUserProfile.MAX_PROFILE_BYTES + 1),
            "#é".repeat(VpsUserProfile.MAX_PROFILE_BYTES / 2) + text,
        )
        changed.forEach { config ->
            invalid { VpsProfileFixture.profile(result = VpsProfileFixture.result().copy(config = config)) }
        }
    }

    @Test
    fun storageIdentityRequiresExactNonemptyNumericSshTupleAndCanonicalKeys() {
        for (server in listOf(
            VpsProfileFixture.server.copy(id = ""),
            VpsProfileFixture.server.copy(host = "vpn.example"),
            VpsProfileFixture.server.copy(sshPort = 0),
            VpsProfileFixture.server.copy(sshPort = 65536),
            VpsProfileFixture.server.copy(sshUser = ""),
        )) invalid { VpsProfileFixture.profile(server = server) }
        invalid { VpsProfileFixture.profile(serverPublicKey = "invalid secret") }
        invalid { VpsProfileFixture.profile(result = VpsProfileFixture.result().copy(publicKey = "invalid secret")) }
    }

    private fun invalid(operation: () -> Unit) {
        val failure = assertThrows(VpsUserProfileException::class.java, operation)
        assertEquals(VpsUserProfileException.Category.INVALID_PROFILE, failure.category)
        assertEquals(failure.category.message, failure.message)
        assertNull(failure.cause)
    }
}

internal object VpsProfileFixture {
    val client = KeyPair()
    val serverKey = KeyPair()
    val psk = Key.fromBytes(ByteArray(32) { 9 })
    val server = StoredVpsServer("server-1", "Server", "203.0.113.10", 22, "root",
        "203.0.113.10", "51820", "10.8.0.0/24", "1.1.1.1")
    val request = VpsAddPeerRequest("phone", "0.0.0.0/0")

    fun text(): String = listOf(
        "[Interface]",
        "Address = 10.8.0.2/32",
        "DNS = 1.1.1.1",
        "PrivateKey = " + client.privateKey.toBase64(),
        "",
        "[Peer]",
        "PublicKey = " + serverKey.publicKey.toBase64(),
        "PresharedKey = " + psk.toBase64(),
        "AllowedIPs = 0.0.0.0/0",
        "Endpoint = 203.0.113.10:51820",
        "PersistentKeepalive = 25",
        "",
    ).joinToString("\n")

    fun result() = VpsAddPeerResult("phone", "10.8.0.2", client.publicKey.toBase64(), text())

    fun profile(
        server: StoredVpsServer = this.server,
        serverPublicKey: String = serverKey.publicKey.toBase64(),
        request: VpsAddPeerRequest = this.request,
        result: VpsAddPeerResult = VpsProfileFixture.result(),
    ) = VpsUserProfile.generated(server, serverPublicKey, request, result)
}
