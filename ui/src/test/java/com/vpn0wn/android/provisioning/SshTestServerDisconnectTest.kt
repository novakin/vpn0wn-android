/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SshTestServerDisconnectTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun knownStaticFailuresAndNegotiationComponentsHaveFiniteCategories() {
        assertEquals(SshDisconnectCategory.HOST_SIGNATURE_VERIFY_FALSE,
            classify("com.jcraft.jsch.JSchException: verify: false"))
        val components = mapOf(
            "kex" to SshDisconnectCategory.NEGOTIATION_KEX,
            "server_host_key" to SshDisconnectCategory.NEGOTIATION_HOST_KEY,
            "cipher.c2s" to SshDisconnectCategory.NEGOTIATION_CIPHER,
            "cipher.s2c" to SshDisconnectCategory.NEGOTIATION_CIPHER,
            "mac.c2s" to SshDisconnectCategory.NEGOTIATION_MAC,
            "mac.s2c" to SshDisconnectCategory.NEGOTIATION_MAC,
            "compression.c2s" to SshDisconnectCategory.NEGOTIATION_COMPRESSION,
            "compression.s2c" to SshDisconnectCategory.NEGOTIATION_COMPRESSION,
            "lang.c2s" to SshDisconnectCategory.NEGOTIATION_LANGUAGE,
            "lang.s2c" to SshDisconnectCategory.NEGOTIATION_LANGUAGE,
        )
        components.forEach { (component, category) ->
            assertEquals(category, classify(negotiation(component)))
        }
    }

    @Test fun wrappedExceptionClassificationDropsAllDescriptionPayloads() {
        val cases = mapOf(
            "com.jcraft.jsch.JSchChangedHostKeyException" to SshDisconnectCategory.HOST_KEY_REFUSED,
            "com.jcraft.jsch.JSchUnknownHostKeyException" to SshDisconnectCategory.HOST_KEY_REFUSED,
            "java.security.spec.InvalidKeySpecException" to SshDisconnectCategory.INVALID_KEY_SPEC,
            "java.security.InvalidKeyException" to SshDisconnectCategory.INVALID_KEY,
            "java.security.InvalidAlgorithmParameterException" to SshDisconnectCategory.INVALID_ALGORITHM_PARAMETERS,
            "java.security.NoSuchAlgorithmException" to SshDisconnectCategory.NO_SUCH_ALGORITHM,
            "java.security.SignatureException" to SshDisconnectCategory.SIGNATURE_FAILURE,
            "java.security.ProviderException" to SshDisconnectCategory.PROVIDER_FAILURE,
            "java.lang.ClassNotFoundException" to SshDisconnectCategory.MISSING_CLASS,
            "java.lang.reflect.InvocationTargetException" to SshDisconnectCategory.REFLECTION_FAILURE,
            "java.lang.InstantiationException" to SshDisconnectCategory.REFLECTION_FAILURE,
            "java.lang.IllegalAccessException" to SshDisconnectCategory.REFLECTION_FAILURE,
            "java.lang.NoSuchMethodException" to SshDisconnectCategory.REFLECTION_FAILURE,
            "java.net.SocketTimeoutException" to SshDisconnectCategory.TRANSPORT_TIMEOUT,
            "java.io.EOFException" to SshDisconnectCategory.TRANSPORT_EOF,
            "java.net.SocketException" to SshDisconnectCategory.TRANSPORT_IO,
            "java.io.IOException" to SshDisconnectCategory.TRANSPORT_IO,
        )
        cases.forEach { (name, category) ->
            for (prefix in listOf("", "com.jcraft.jsch.JSchException: ", "com.jcraft.jsch.JSchException: Session.connect: ")) {
                val observed = SshClientDisconnect(true, KEY_EXCHANGE_FAILED, classify(prefix + name + ": " + SECRET))
                assertEquals(category, observed.category)
                assertFalse(observed.toString().contains(SECRET))
            }
        }
    }

    @Test fun unknownMalformedAndWrongReasonDescriptionsRemainRedacted() {
        for (description in listOf(null, SECRET, "java.security.InvalidKeyExceptionSuffix: $SECRET",
            "prefix java.security.InvalidKeyException: $SECRET", negotiation("unknown"), "verify: false",
            "com.jcraft.jsch.JSchAlgoNegoFailException: Algorithm negotiation fail: algorithmName=\"kex")) {
            assertEquals(SshDisconnectCategory.OTHER_REDACTED, classify(description))
        }
        assertEquals(SshDisconnectCategory.OTHER_REDACTED,
            SshTestServer.classifyClientDisconnect(11, "com.jcraft.jsch.JSchException: verify: false"))
    }

    @Test fun localDisconnectAndLaterCleanupCannotReplaceFirstRemoteObservation() {
        SshTestServer(temporaryFolder.newFolder().toPath()).use { peer ->
            assertEquals(SshClientDisconnect(), peer.clientDisconnect)
            peer.recordClientDisconnect(KEY_EXCHANGE_FAILED, SECRET, initiator = true)
            assertEquals(SshClientDisconnect(), peer.clientDisconnect)
            assertEquals(1L, peer.clientDisconnectReceived.count)
            peer.recordClientDisconnect(KEY_EXCHANGE_FAILED, "com.jcraft.jsch.JSchException: verify: false", initiator = false)
            val original = peer.clientDisconnect
            peer.recordClientDisconnect(11, SECRET, initiator = false)
            assertSame(original, peer.clientDisconnect)
            assertEquals(SshDisconnectCategory.HOST_SIGNATURE_VERIFY_FALSE, original.category)
            assertEquals(0L, peer.clientDisconnectReceived.count)
            assertFalse(original.toString().contains(SECRET))
        }
    }

    @Test fun actualPinRefusalReachesPublicServerDisconnectCallbackBeforeAuthentication() = runBlocking {
        SshTestServer(temporaryFolder.newFolder().toPath()).use { peer ->
            val client = JschRemoteShellClientFactory().create(peer.connection(pin = ZERO_PIN))
            try {
                val failure = runCatching { withTimeout(TEST_TIMEOUT_MILLIS) { client.execute("success") } }.exceptionOrNull()
                assertTrue(failure is ProvisioningException)
                assertEquals("SSH operation failed", failure?.message)
                assertTrue("Public SSHD disconnect callback missing", peer.clientDisconnectReceived.await(RECEIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertEquals(SshClientDisconnect(true, KEY_EXCHANGE_FAILED, SshDisconnectCategory.HOST_KEY_REFUSED), peer.clientDisconnect)
                assertEquals(0, peer.passwordAttempts.get())
                assertEquals(0, peer.keyAttempts.get())
                assertEquals(1L, peer.commandEntered.count)
            } finally {
                client.close()
            }
        }
    }

    private fun classify(description: String?) = SshTestServer.classifyClientDisconnect(KEY_EXCHANGE_FAILED, description)
    private fun negotiation(component: String) =
        "com.jcraft.jsch.JSchAlgoNegoFailException: Algorithm negotiation fail: algorithmName=\"$component\" jschProposal=\"$SECRET\" serverProposal=\"$SECRET\""

    companion object {
        private const val KEY_EXCHANGE_FAILED = 3
        private const val TEST_TIMEOUT_MILLIS = 10_000L
        private const val RECEIPT_TIMEOUT_SECONDS = 5L
        private const val SECRET = "synthetic-password-and-private-key-payload"
        private const val ZERO_PIN = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
