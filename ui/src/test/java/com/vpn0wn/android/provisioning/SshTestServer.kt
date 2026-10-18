/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.channel.ChannelSessionFactory
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Assert.assertTrue

internal enum class SshDisconnectCategory {
    NOT_OBSERVED, OTHER_REDACTED, HOST_SIGNATURE_VERIFY_FALSE, HOST_KEY_REFUSED,
    NEGOTIATION_KEX, NEGOTIATION_HOST_KEY, NEGOTIATION_CIPHER, NEGOTIATION_MAC,
    NEGOTIATION_COMPRESSION, NEGOTIATION_LANGUAGE, INVALID_KEY_SPEC, INVALID_KEY,
    INVALID_ALGORITHM_PARAMETERS, NO_SUCH_ALGORITHM, SIGNATURE_FAILURE, PROVIDER_FAILURE,
    MISSING_CLASS, REFLECTION_FAILURE, TRANSPORT_TIMEOUT, TRANSPORT_EOF, TRANSPORT_IO,
}

internal data class SshClientDisconnect(
    val seen: Boolean = false,
    val reason: Int? = null,
    val category: SshDisconnectCategory = SshDisconnectCategory.NOT_OBSERVED,
)

internal class SshTestServer(
    private val directory: Path,
    ecdsaHostKey: Boolean = false,
    legacyRsaOnly: Boolean = false,
    private val host: String = "127.0.0.1",
    sha1MacOnly: Boolean = false,
) : AutoCloseable {
    val passwordAttempts = AtomicInteger()
    val keyAttempts = AtomicInteger()
    val clientDisconnectReceived = CountDownLatch(1)
    private val observedClientDisconnect = AtomicReference(SshClientDisconnect())
    val clientDisconnect: SshClientDisconnect get() = observedClientDisconnect.get()
    val commandEntered = CountDownLatch(1)
    val releaseHungOutput = CountDownLatch(1)
    val channelOpenEntered = CountDownLatch(1)
    val releaseChannelOpen = CountDownLatch(1)
    val uploadEntered = CountDownLatch(1)
    val releaseUpload = CountDownLatch(1)
    @Volatile var hangUpload = false
    @Volatile var hangChannelOpen = false
    @Volatile var failChmod = false
    @Volatile var allowedPublicKey: String? = null
    private val commands = Executors.newCachedThreadPool()
    private val hostKey = KeyPairGenerator.getInstance(if (ecdsaHostKey) "EC" else "RSA")
        .apply { initialize(if (ecdsaHostKey) 256 else 2048) }.generateKeyPair()
    private val server = SshServer.setUpDefaultServer()
    val fingerprint: String
        get() {
            val encoded = PublicKeyEntry.toString(hostKey.public).split(' ')[1]
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(encoded))
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    val port: Int get() = server.port
    val activeSessions: Int get() = server.activeSessions.size

    init {
        Files.createDirectories(directory)
        server.host = host
        server.port = 0
        server.keyPairProvider = KeyPairProvider.wrap(hostKey)
        server.addSessionListener(object : SessionListener {
            override fun sessionDisconnect(session: Session, reason: Int, msg: String?, language: String?, initiator: Boolean) {
                recordClientDisconnect(reason, msg, initiator)
            }
        })
        if (legacyRsaOnly) server.signatureFactories = listOf(BuiltinSignatures.rsa)
        if (sha1MacOnly) {
            server.cipherFactories = listOf(BuiltinCiphers.aes128ctr)
            server.macFactories = listOf(BuiltinMacs.hmacsha1)
        }
        server.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ ->
            passwordAttempts.incrementAndGet()
            user == USER && password == PASSWORD
        }
        server.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ ->
            keyAttempts.incrementAndGet()
            user == USER && PublicKeyEntry.toString(key) == allowedPublicKey
        }
        server.commandFactory = CommandFactory { channel, command -> FixtureCommand(channel, command) }
        server.channelFactories = listOf(object : ChannelSessionFactory() {
            override fun createChannel(session: Session): Channel {
                if (hangChannelOpen) {
                    channelOpenEntered.countDown()
                    check(releaseChannelOpen.await(10, TimeUnit.SECONDS)) { "Synthetic channel-open gate was not released" }
                }
                return super.createChannel(session)
            }
        })
        val sftp = SftpSubsystemFactory.Builder().build()
        sftp.addSftpEventListener(object : SftpEventListener {
            override fun writing(
                session: ServerSession,
                remoteHandle: String,
                localHandle: FileHandle,
                offset: Long,
                data: ByteArray,
                dataOffset: Int,
                dataLen: Int,
            ) {
                if (hangUpload) {
                    uploadEntered.countDown()
                    check(releaseUpload.await(10, TimeUnit.SECONDS)) { "Synthetic upload gate was not released" }
                }
            }

            override fun modifyingAttributes(session: ServerSession, path: Path, attrs: MutableMap<String, *>?) {
                if (failChmod) throw java.io.IOException(SECRET_MARKER)
            }
        })
        server.subsystemFactories = listOf(sftp)
        server.fileSystemFactory = VirtualFileSystemFactory(directory)
        server.start()
    }

    fun connection(
        credential: VpsCredential = VpsCredential.Password(PASSWORD.toCharArray()),
        pin: String? = fingerprint,
        trust: Boolean = false,
    ) = VpsConnection(VpsEndpoint(host, port, USER), credential, trust, pin, maxAuthAttempts = 1)

    fun sendIdleMessage() {
        server.activeSessions.single().sendIgnoreMessage(*"synthetic idle pulse".toByteArray())
    }

    fun recordClientDisconnect(reason: Int, description: String?, initiator: Boolean) {
        if (initiator) return
        val previous = observedClientDisconnect.get()
        if (previous.seen) return
        val observed = SshClientDisconnect(true, reason, classifyClientDisconnect(reason, description))
        if (observedClientDisconnect.compareAndSet(previous, observed)) clientDisconnectReceived.countDown()
    }

    override fun close() {
        releaseHungOutput.countDown()
        releaseChannelOpen.countDown()
        releaseUpload.countDown()
        server.stop(true)
        commands.shutdownNow()
        assertTrue("Fixture command workers did not stop", commands.awaitTermination(5, TimeUnit.SECONDS))
    }

    private inner class FixtureCommand(private val channel: ChannelSession, private val command: String) : Command {
        private lateinit var stdout: OutputStream
        private lateinit var stderr: OutputStream
        private lateinit var exit: ExitCallback
        private var worker: Future<*>? = null

        override fun setInputStream(input: InputStream) = Unit
        override fun setOutputStream(output: OutputStream) { stdout = output }
        override fun setErrorStream(error: OutputStream) { stderr = error }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            worker = commands.submit {
                commandEntered.countDown()
                when {
                    command.startsWith("progress") -> {
                        stdout.write("$SECRET_MARKER\n${VpsInstallProgress.VERIFYING.marker}\n".toByteArray())
                        stdout.flush()
                        stderr.write("$SECRET_MARKER\nvpn0wn-progress:install:".toByteArray())
                        stderr.flush()
                        stderr.write("prepare\n".toByteArray())
                        stderr.flush()
                        releaseHungOutput.await()
                        stderr.write("${VpsInstallProgress.PACKAGES.marker}\n${VpsInstallProgress.PREPARING.marker}\n${VpsInstallProgress.VERIFYING.marker}\n".toByteArray())
                        stderr.flush()
                        if (command == "progress-hang") CountDownLatch(1).await()
                        else exit.onExit(if (command == "progress-nonzero") 17 else 0)
                    }
                    command == "hang" -> {
                        releaseHungOutput.await()
                        stdout.write("synthetic pulse\n".toByteArray())
                        stdout.flush()
                        CountDownLatch(1).await()
                    }
                    command == "missing-status" -> this.channel.close(false)
                    command.startsWith("stdout:") -> emit(stdout, command.substringAfter(':').toInt())
                    command.startsWith("stderr:") -> emit(stderr, command.substringAfter(':').toInt())
                    else -> {
                        stdout.write("synthetic stdout\n".toByteArray())
                        stderr.write("synthetic stderr\n".toByteArray())
                        stdout.flush()
                        stderr.flush()
                        exit.onExit(if (command == "nonzero") 17 else 0)
                    }
                }
            }
        }

        private fun emit(output: OutputStream, byteCount: Int) {
            var remaining = byteCount
            val chunk = ByteArray(8192) { 'x'.code.toByte() }
            while (remaining > 0) {
                val size = minOf(chunk.size, remaining)
                output.write(chunk, 0, size)
                remaining -= size
            }
            output.flush()
            exit.onExit(0)
        }

        override fun destroy(channel: ChannelSession) { worker?.cancel(true) }
    }

    companion object {
        const val USER = "synthetic-user"
        const val PASSWORD = "synthetic-password"
        const val SECRET_MARKER = "synthetic-secret-payload"
        private const val KEY_EXCHANGE_FAILED = 3
        private const val JSCH_EXCEPTION_PREFIX = "com.jcraft.jsch.JSchException: "
        private const val NEGOTIATION_PREFIX =
            "com.jcraft.jsch.JSchAlgoNegoFailException: Algorithm negotiation fail: algorithmName=\""
        private val negotiationCategories = mapOf(
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
        private val exceptionCategories = mapOf(
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

        // JSch attempts to send its original connect exception in reason 3 before the driver sanitizes it.
        fun classifyClientDisconnect(reason: Int, description: String?): SshDisconnectCategory {
            if (reason != KEY_EXCHANGE_FAILED || description == null) return SshDisconnectCategory.OTHER_REDACTED
            if (description == JSCH_EXCEPTION_PREFIX + "verify: false") return SshDisconnectCategory.HOST_SIGNATURE_VERIFY_FALSE
            val failure = description.removePrefix(JSCH_EXCEPTION_PREFIX).removePrefix("Session.connect: ")
            return if (failure.startsWith(NEGOTIATION_PREFIX)) {
                val end = failure.indexOf('"', NEGOTIATION_PREFIX.length)
                val component = if (end < 0) null else failure.substring(NEGOTIATION_PREFIX.length, end)
                negotiationCategories[component] ?: SshDisconnectCategory.OTHER_REDACTED
            } else {
                exceptionCategories.entries.firstOrNull { (name, _) -> failure == name || failure.startsWith("$name:") }
                    ?.value ?: SshDisconnectCategory.OTHER_REDACTED
            }
        }
    }
}
