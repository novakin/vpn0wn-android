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
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.session.Session
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

internal class SshTestServer(
    private val directory: Path,
    ecdsaHostKey: Boolean = false,
    legacyRsaOnly: Boolean = false,
    private val host: String = "127.0.0.1",
    sha1MacOnly: Boolean = false,
) : AutoCloseable {
    val passwordAttempts = AtomicInteger()
    val keyAttempts = AtomicInteger()
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
    }
}
