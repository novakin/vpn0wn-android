/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal class SavedVpsCredential(
    val endpoint: VpsEndpoint,
    val fingerprint: String,
    val credential: VpsCredential,
) {
    fun matches(server: StoredVpsServer): Boolean =
        endpoint.host == server.host && endpoint.port == server.sshPort && endpoint.user == server.sshUser

    fun connection(): VpsConnection = VpsConnection(
        endpoint,
        when (val value = credential) {
            is VpsCredential.Password -> VpsCredential.Password(value.value.copyOf())
            is VpsCredential.PrivateKey -> value.copy()
        },
        trustHostKey = false,
        hostKeyFingerprint = fingerprint,
    )

    override fun toString(): String = "SavedVpsCredential(redacted)"
}

internal object VpsCredentialRecords {
    private const val MAX_SSH_PORT = 65_535
    private val identityFields = setOf("id", "host", "port", "user", "fingerprint", "auth")

    fun decode(bytes: ByteArray): MutableMap<String, SavedVpsCredential> {
        val records = linkedMapOf<String, SavedVpsCredential>()
        try {
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            val tokener = JSONTokener(text)
            val array = tokener.nextValue() as? JSONArray ?: corrupt()
            if (tokener.nextClean() != '\u0000') corrupt()
            for (index in 0 until array.length()) {
                val json = array.get(index) as? JSONObject ?: corrupt()
                val id = json.string("id")
                val host = json.string("host")
                val user = json.string("user")
                val port = json.get("port") as? Int ?: corrupt()
                if (id.isBlank() || host.isBlank() || user.isBlank() || port !in 1..MAX_SSH_PORT || records.containsKey(id)) corrupt()
                val fingerprint = json.string("fingerprint")
                JschRemoteShellClientFactory.decodeHostFingerprint(fingerprint)
                val credential = when (json.string("auth")) {
                    "password" -> {
                        if (json.keys().asSequence().toSet() != identityFields + "password") corrupt()
                        VpsCredential.Password(json.string("password").toCharArray())
                    }
                    "private_key" -> {
                        if (json.keys().asSequence().toSet() != identityFields + setOf("pem", "passphrase")) corrupt()
                        val passphrase = when (val value = json.get("passphrase")) {
                            JSONObject.NULL -> null
                            is String -> value
                            else -> corrupt()
                        }
                        VpsCredential.PrivateKey(json.string("pem"), passphrase)
                    }
                    else -> corrupt()
                }
                records[id] = SavedVpsCredential(VpsEndpoint(host, port, user), fingerprint, credential)
            }
            return records
        } catch (_: JSONException) {
            clear(records)
            corrupt()
        } catch (_: CharacterCodingException) {
            clear(records)
            corrupt()
        } catch (_: ProvisioningException) {
            clear(records)
            corrupt()
        } catch (failure: VpsCredentialStorageException) {
            clear(records)
            throw failure
        }
    }

    fun encode(records: Map<String, SavedVpsCredential>): ByteArray {
        val array = JSONArray()
        for ((id, saved) in records) {
            val json = JSONObject()
                .put("id", id)
                .put("host", saved.endpoint.host)
                .put("port", saved.endpoint.port)
                .put("user", saved.endpoint.user)
                .put("fingerprint", saved.fingerprint)
            when (val credential = saved.credential) {
                is VpsCredential.Password -> json.put("auth", "password").put("password", String(credential.value))
                is VpsCredential.PrivateKey -> json.put("auth", "private_key").put("pem", credential.pem)
                    .put("passphrase", credential.passphrase ?: JSONObject.NULL)
            }
            array.put(json)
        }
        return array.toString().toByteArray(Charsets.UTF_8)
    }

    fun clear(records: Map<String, SavedVpsCredential>) {
        records.values.forEach { (it.credential as? VpsCredential.Password)?.clear() }
    }

    private fun JSONObject.string(name: String): String = get(name) as? String ?: corrupt()

    private fun corrupt(): Nothing = throw VpsCredentialStorageException(VpsCredentialStorageException.Category.CORRUPT)
}
