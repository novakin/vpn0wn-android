/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import com.vpn0wn.android.util.TunnelImportInput
import com.vpn0wn.config.BadConfigException
import com.vpn0wn.config.Config
import com.vpn0wn.config.InetAddresses
import com.vpn0wn.config.InetNetwork
import com.vpn0wn.config.Interface
import com.vpn0wn.config.ParseException
import com.vpn0wn.config.Peer
import com.vpn0wn.crypto.Key
import com.vpn0wn.crypto.KeyFormatException
import java.io.IOException

class VpsUserProfileException(val category: Category) : IOException(category.message) {
    enum class Category(val message: String) {
        INVALID_PROFILE("Generated user profile is invalid"),
        CONFLICT("Saved user profile identity or content changed"),
        CORRUPT("Saved user profiles are corrupt"),
        KEY_UNAVAILABLE("Saved user-profile key is unavailable"),
        IO("Saved user-profile storage is unavailable"),
        LIMIT("Saved user profiles exceed supported limits"),
    }
}

data class VpsUserProfileIdentity(
    val serverId: String,
    val ssh: VpsEndpoint,
    val serverPublicKey: String,
    val clientPublicKey: String,
) {
    internal fun validate() {
        if (serverId.isBlank() || ssh.host.isBlank() || ssh.user.isBlank() || ssh.port !in 1..MAX_PORT) invalidProfile()
        try {
            InetAddresses.parse(ssh.host)
            if (Key.fromBase64(serverPublicKey).toBase64() != serverPublicKey ||
                Key.fromBase64(clientPublicKey).toBase64() != clientPublicKey) invalidProfile()
        } catch (_: KeyFormatException) {
            invalidProfile()
        } catch (_: ParseException) {
            invalidProfile()
        }
    }

    private companion object {
        const val MAX_PORT = 65_535
    }
}

class VpsUserProfile private constructor(val identity: VpsUserProfileIdentity, val name: String, val config: Config) {
    fun payload(): String = config.toWgQuickString()

    internal fun sameContent(other: VpsUserProfile): Boolean =
        identity == other.identity && name == other.name && payload() == other.payload()

    internal fun matchesObserved(status: VpsServerStatus, peer: VpsPeerStatus): Boolean {
        if (status.serverPublicKey != identity.serverPublicKey || peer.publicKey != identity.clientPublicKey || peer.name != name)
            return false
        return try {
            val expected = Interface.Builder().setKeyPair(config.`interface`.keyPair)
                .parseAddresses(peer.allowedIps).parseDnsServers(status.dns).build()
            config.`interface` == expected && config.peers.single().endpoint.get().toString() == "${status.endpoint}:${status.listenPort}"
        } catch (_: BadConfigException) {
            false
        }
    }

    override fun toString(): String = "VpsUserProfile(redacted)"

    companion object {
        internal const val MAX_PROFILE_BYTES = TunnelImportInput.MAX_PROFILE_BYTES
        private const val MAX_PORT = 65_535
        private const val KEEPALIVE_SECONDS = 25
        private const val PREFIX_BYTES = 3
        private const val IPV4_BYTES = 4
        private const val PREFIX_BITS = 24
        private const val FIRST_CLIENT = 2
        private const val LAST_CLIENT = 254
        private val NAME = Regex("[A-Za-z0-9_-]{1,32}")

        fun generated(server: StoredVpsServer, serverPublicKey: String, request: VpsAddPeerRequest, result: VpsAddPeerResult): VpsUserProfile {
            val identity = VpsUserProfileIdentity(server.id, VpsEndpoint(server.host, server.sshPort, server.sshUser), serverPublicKey, result.publicKey)
            val profile = decode(identity, result.name, result.config)
            val listenPort = server.listenPort.toIntOrNull() ?: invalidProfile()
            if (request.name != result.name || listenPort !in 1..MAX_PORT) invalidProfile()
            try {
                val network = InetNetwork.parse(server.cidr)
                val address = InetAddresses.parse(result.address)
                val networkBytes = network.address.address
                val addressBytes = address.address
                if (network.mask != PREFIX_BITS || networkBytes.size != IPV4_BYTES || addressBytes.size != IPV4_BYTES ||
                    networkBytes.last().toInt() != 0 || !networkBytes.copyOf(PREFIX_BYTES).contentEquals(addressBytes.copyOf(PREFIX_BYTES)) ||
                    addressBytes.last().toUByte().toInt() !in FIRST_CLIENT..LAST_CLIENT) invalidProfile()
                val actual = profile.config
                val expectedInterface = Interface.Builder().setKeyPair(actual.`interface`.keyPair)
                    .parseAddresses("${result.address}/32").parseDnsServers(request.dns?.takeUnless { it.isEmpty() } ?: server.dns).build()
                val expectedPeer = Peer.Builder().parsePublicKey(serverPublicKey)
                    .setPreSharedKey(actual.peers.single().preSharedKey.get())
                    .parseAllowedIPs(request.allowedIps).parseEndpoint("${server.endpoint}:${server.listenPort}")
                    .setPersistentKeepalive(KEEPALIVE_SECONDS).build()
                if (actual != Config.Builder().setInterface(expectedInterface).addPeer(expectedPeer).build()) invalidProfile()
            } catch (_: BadConfigException) {
                invalidProfile()
            } catch (_: ParseException) {
                invalidProfile()
            }
            return profile
        }

        internal fun decode(identity: VpsUserProfileIdentity, name: String, text: String): VpsUserProfile {
            identity.validate()
            if (!NAME.matches(name) || text.length > MAX_PROFILE_BYTES) invalidProfile()
            val sections = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.startsWith('[') }.toList()
            if (sections.size != 2 || !sections[0].equals("[Interface]", true) || !sections[1].equals("[Peer]", true)) invalidProfile()
            val config = try {
                TunnelImportInput.parseText(text)
            } catch (_: BadConfigException) {
                invalidProfile()
            } catch (_: IOException) {
                invalidProfile()
            }
            val peer = config.peers.singleOrNull() ?: invalidProfile()
            if (config.`interface`.keyPair.publicKey.toBase64() != identity.clientPublicKey ||
                peer.publicKey.toBase64() != identity.serverPublicKey || !peer.preSharedKey.isPresent ||
                !peer.endpoint.isPresent || !peer.persistentKeepalive.isPresent || peer.persistentKeepalive.get() != KEEPALIVE_SECONDS)
                invalidProfile()
            val canonical = config.toWgQuickString().toByteArray(Charsets.UTF_8)
            try {
                if (canonical.size > MAX_PROFILE_BYTES) invalidProfile()
            } finally {
                canonical.fill(0)
            }
            return VpsUserProfile(identity, name, config)
        }
    }
}

private fun invalidProfile(): Nothing = throw VpsUserProfileException(VpsUserProfileException.Category.INVALID_PROFILE)
