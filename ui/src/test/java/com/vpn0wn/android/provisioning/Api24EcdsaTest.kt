/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import com.jcraft.jsch.Buffer
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.SignatureECDSA
import com.jcraft.jsch.jce.SignatureECDSA256
import com.jcraft.jsch.jce.SignatureECDSA384
import com.jcraft.jsch.jce.SignatureECDSA521
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.SignatureException
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 25])
class Api24EcdsaTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun compatibilityMappingsStaySessionLocal() {
        val original = EcdsaCurve.entries.associateWith { JSch.getConfig(it.algorithm) }
        val session = JSch().getSession("synthetic", "127.0.0.1", 22)
        Api24Ecdsa.configure(session)
        for (curve in EcdsaCurve.entries) {
            assertTrue(session.getConfig(curve.algorithm).startsWith(Api24Ecdsa::class.java.packageName))
            assertEquals(original[curve], JSch.getConfig(curve.algorithm))
        }
    }

    @Test @Config(sdk = [26, 28]) fun newerPlatformsKeepLibraryVerifiersAndPrivateIdentities() {
        val jsch = JSch()
        val session = jsch.getSession("synthetic", "127.0.0.1", 22)
        val original = EcdsaCurve.entries.associateWith { session.getConfig(it.algorithm) }
        Api24Ecdsa.configure(session)
        for (curve in EcdsaCurve.entries) assertEquals(original[curve], session.getConfig(curve.algorithm))
        val key = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
        try {
            assertFalse(Api24Ecdsa.addIdentity(jsch, key))
            assertTrue(jsch.identityRepository.identities.isEmpty())
        } finally { key.dispose() }
    }

    @Test fun allCurvesInteroperateWithTheLibraryInBothSignatureDirections() {
        for (curve in EcdsaCurve.entries) {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(curve.bits) }.generateKeyPair()
            val public = keys.public as ECPublicKey
            val scalar = (keys.private as ECPrivateKey).s.toByteArray()
            try {
                val compatible = Api24EcdsaSignature(curve)
                val original = librarySignature(curve)
                compatible.init()
                compatible.setPrvKey(scalar)
                compatible.update(MESSAGE)
                original.init()
                original.setPubKey(coordinate(public.w.affineX, curve), coordinate(public.w.affineY, curve))
                original.update(MESSAGE)
                assertTrue(original.verify(wrap(curve, compatible.sign())))

                original.init()
                original.setPrvKey(scalar)
                original.update(MESSAGE)
                val signature = wrap(curve, original.sign())
                compatible.init()
                compatible.setPubKey(coordinate(public.w.affineX, curve), coordinate(public.w.affineY, curve))
                compatible.update(MESSAGE)
                assertTrue(compatible.verify(signature))
                compatible.init()
                compatible.setPubKey(coordinate(public.w.affineX, curve), coordinate(public.w.affineY, curve))
                compatible.update("different synthetic message".toByteArray())
                assertFalse(compatible.verify(signature))
            } finally { scalar.fill(0) }
        }
    }

    @Test fun sshIntegersPreserveHighBitsLeadingZerosAndP521LongDerLengths() {
        val curve = EcdsaCurve.NISTP256
        val der = byteArrayOf(0x30, 0x07, 0x02, 0x02, 0x00, 0x80.toByte(), 0x02, 0x01, 0x01)
        val canonical = strings(byteArrayOf(0, 0x80.toByte()), byteArrayOf(1))
        assertArrayEquals(der, sshEcdsaSignatureToDer(curve.algorithm, wrap(curve, canonical)))
        assertArrayEquals(canonical, derEcdsaSignatureToSsh(der))
        val padded = strings(byteArrayOf(0, 0, 0x80.toByte()), byteArrayOf(0, 1))
        assertArrayEquals(der, sshEcdsaSignatureToDer(curve.algorithm, wrap(curve, padded)))

        val longCurve = EcdsaCurve.NISTP521
        val r = ByteArray(66).apply { this[0] = 1; this[lastIndex] = 0x80.toByte() }
        val s = ByteArray(66).apply { this[0] = 1; this[lastIndex] = 1 }
        val longDer = byteArrayOf(0x30, 0x81.toByte(), 0x88.toByte(), 0x02, 0x42) + r + byteArrayOf(0x02, 0x42) + s
        val longPayload = strings(r, s)
        assertArrayEquals(longDer, sshEcdsaSignatureToDer(longCurve.algorithm, wrap(longCurve, longPayload)))
        assertArrayEquals(longPayload, derEcdsaSignatureToSsh(longDer))
    }

    @Test fun malformedOrMismatchedSshSignaturesCannotReachVerification() {
        val curve = EcdsaCurve.NISTP256
        val valid = wrap(curve, strings(byteArrayOf(1), byteArrayOf(1)))
        val invalid = listOf(
            byteArrayOf(), valid.copyOf(valid.size - 1), valid + 0,
            wrap(EcdsaCurve.NISTP384, strings(byteArrayOf(1), byteArrayOf(1))),
            wrap(curve, strings(byteArrayOf(0x80.toByte()), byteArrayOf(1))),
            wrap(curve, strings(byteArrayOf(), byteArrayOf(1))),
            wrap(curve, strings(byteArrayOf(1), byteArrayOf(1)) + 0),
            byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f),
        )
        for (encoded in invalid) assertThrows(SignatureException::class.java) { sshEcdsaSignatureToDer(curve.algorithm, encoded) }
    }

    @Test fun wrongCurvePointCannotBeImported() {
        val signature = Api24EcdsaSignature(EcdsaCurve.NISTP256)
        signature.init()
        assertThrows(SignatureException::class.java) { signature.setPubKey(ByteArray(48), ByteArray(48)) }
    }

    @Test fun removingIdentityClearsParsedScalarAndStopsFurtherSigning() {
        val jsch = JSch()
        val key = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
        try {
            assertTrue(Api24Ecdsa.addIdentity(jsch, key))
            val identity = jsch.identityRepository.identities.single()
            assertNotNull(identity.getSignature(MESSAGE, "ecdsa-sha2-nistp256"))
            assertNull(identity.getSignature(MESSAGE, "rsa-sha2-256"))
            jsch.removeAllIdentity()
            identity.clear()
            assertNull(identity.publicKeyBlob)
            assertNull(identity.getSignature(MESSAGE))
            assertFalse(identity.setPassphrase(null))
            val exported = key.forSSHAgent()
            try {
                val buffer = Buffer(exported)
                repeat(3) { buffer.string }
                val scalar = buffer.string
                try { assertTrue(scalar.isNotEmpty() && scalar.all { it == 0.toByte() }) }
                finally { scalar.fill(0) }
            } finally { exported.fill(0) }
        } finally {
            jsch.removeAllIdentity()
            key.dispose()
        }
    }

    @Test fun allEcdsaHostCurvesAuthenticatePasswordAndRunPinnedExec() = runBlocking {
        for (curve in EcdsaCurve.entries) {
            SshTestServer(temporaryFolder.newFolder().toPath(), ecdsaHostKey = true, ecdsaHostKeyBits = curve.bits).use { peer ->
                val socket = Socket()
                val client = JschRemoteShellClientFactory { socket }.create(peer.connection())
                try {
                    val output = withTimeout(TEST_TIMEOUT) { client.execute("success") }
                    assertEquals(0, output.exitCode)
                    assertEquals("synthetic stdout\n", output.stdout)
                    assertEquals(1, peer.passwordAttempts.get())
                    assertEquals(0, peer.keyAttempts.get())
                } finally { client.close() }
                assertTrue(socket.isClosed)
                awaitNoSessions(peer)
            }
        }
    }

    @Test fun allEcdsaPrivateCurvesAuthenticatePlainAndEncryptedPem() = runBlocking {
        for (curve in EcdsaCurve.entries) {
            for (passphrase in listOf<String?>(null, PASSPHRASE)) {
                assertPrivateAuthentication(KeyPair.ECDSA, curve.bits, passphrase)
            }
        }
    }

    @Test fun rsaSha2PrivateAuthenticationKeepsTheLibraryPath() = runBlocking {
        for (passphrase in listOf<String?>(null, PASSPHRASE)) assertPrivateAuthentication(KeyPair.RSA, 2048, passphrase)
    }

    @Test fun wrongEcPassphraseFailsBeforeSocketCreationAndWrongPinBeforeAuthentication() = runBlocking {
        val identity = identity(KeyPair.ECDSA, 256, PASSPHRASE)
        SshTestServer(temporaryFolder.newFolder().toPath(), ecdsaHostKey = true).use { peer ->
            peer.allowedPublicKey = identity.publicKey
            var created = 0
            val badPassphrase = JschRemoteShellClientFactory { created++; Socket() }
                .create(peer.connection(identity.credential.copy(passphrase = "synthetic wrong passphrase")))
            try {
                assertSafeFailure(runCatching { badPassphrase.execute("success") }.exceptionOrNull())
            } finally { badPassphrase.close() }
            assertEquals(0, created)
            val wrongPin = JschRemoteShellClientFactory().create(peer.connection(identity.credential, pin = ZERO_PIN))
            try {
                assertSafeFailure(runCatching { withTimeout(TEST_TIMEOUT) { wrongPin.execute("success") } }.exceptionOrNull())
            } finally { wrongPin.close() }
            assertEquals(0, peer.passwordAttempts.get())
            assertEquals(0, peer.keyAttempts.get())
            assertEquals(1L, peer.commandEntered.count)
            awaitNoSessions(peer)
        }
    }

    @Test fun cancellingEcAuthenticatedCommandClosesSocketAndDrainsTheSession() = runBlocking {
        val identity = identity(KeyPair.ECDSA, 256, null)
        SshTestServer(temporaryFolder.newFolder().toPath(), ecdsaHostKey = true).use { peer ->
            peer.allowedPublicKey = identity.publicKey
            val socket = Socket()
            val client = JschRemoteShellClientFactory { socket }.create(peer.connection(identity.credential))
            try {
                val operation = async(Dispatchers.IO) { client.execute("hang") }
                withContext(Dispatchers.IO) { assertTrue(peer.commandEntered.await(5, TimeUnit.SECONDS)) }
                val threads = Thread.getAllStackTraces().keys.filter { it.name == "Connect thread 127.0.0.1 session" && it.isAlive }
                assertTrue(threads.isNotEmpty())
                withTimeout(TEST_TIMEOUT) { operation.cancelAndJoin() }
                client.close()
                assertTrue(socket.isClosed)
                assertTrue(threads.none { it.isAlive })
                awaitNoSessions(peer)
                assertSafeFailure(runCatching { client.execute("success") }.exceptionOrNull())
            } finally { client.close() }
        }
    }

    private suspend fun assertPrivateAuthentication(type: Int, bits: Int, passphrase: String?) {
        val identity = identity(type, bits, passphrase)
        SshTestServer(temporaryFolder.newFolder().toPath()).use { peer ->
            peer.allowedPublicKey = identity.publicKey
            val client = JschRemoteShellClientFactory().create(peer.connection(identity.credential))
            try {
                assertEquals(0, withTimeout(TEST_TIMEOUT) { client.execute("success") }.exitCode)
                assertEquals(0, peer.passwordAttempts.get())
                assertTrue(peer.keyAttempts.get() > 0)
            } finally { client.close() }
            awaitNoSessions(peer)
        }
    }

    private fun identity(type: Int, bits: Int, passphrase: String?): SyntheticIdentity {
        val key = KeyPair.genKeyPair(JSch(), type, bits)
        val password = passphrase?.toByteArray()
        try {
            val privateKey = ByteArrayOutputStream()
            key.writePrivateKey(privateKey, password)
            val algorithm = Buffer(key.publicKeyBlob).string.toString(Charsets.US_ASCII)
            return SyntheticIdentity(
                VpsCredential.PrivateKey(privateKey.toString(Charsets.UTF_8), passphrase),
                "$algorithm ${Base64.getEncoder().encodeToString(key.publicKeyBlob)}",
            )
        } finally {
            password?.fill(0)
            key.dispose()
        }
    }

    private fun assertSafeFailure(failure: Throwable?) {
        assertTrue(failure is ProvisioningException)
        assertNull(failure?.cause)
        assertNull((failure as ProvisioningException).output)
        val text = failure.stackTraceToString()
        for (secret in listOf(PASSPHRASE, "synthetic wrong passphrase", "PRIVATE KEY")) assertFalse(text.contains(secret))
    }

    private suspend fun awaitNoSessions(peer: SshTestServer) {
        withTimeout(TEST_TIMEOUT) { while (peer.activeSessions != 0) delay(10) }
    }

    private fun librarySignature(curve: EcdsaCurve): SignatureECDSA = when (curve) {
        EcdsaCurve.NISTP256 -> SignatureECDSA256()
        EcdsaCurve.NISTP384 -> SignatureECDSA384()
        EcdsaCurve.NISTP521 -> SignatureECDSA521()
    }

    private fun coordinate(value: BigInteger, curve: EcdsaCurve): ByteArray {
        val encoded = value.toByteArray().takeLast(curve.coordinateBytes).toByteArray()
        return ByteArray(curve.coordinateBytes - encoded.size) + encoded
    }

    private fun wrap(curve: EcdsaCurve, payload: ByteArray): ByteArray = strings(curve.algorithm.toByteArray(Charsets.US_ASCII), payload)
    private fun strings(vararg values: ByteArray): ByteArray {
        val buffer = Buffer()
        values.forEach(buffer::putString)
        return ByteArray(buffer.length).also(buffer::getByte)
    }

    private data class SyntheticIdentity(val credential: VpsCredential.PrivateKey, val publicKey: String)

    private companion object {
        const val TEST_TIMEOUT = 10_000L
        const val PASSPHRASE = "synthetic EC passphrase"
        const val ZERO_PIN = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val MESSAGE = "synthetic SSH signed message".toByteArray()
    }
}
