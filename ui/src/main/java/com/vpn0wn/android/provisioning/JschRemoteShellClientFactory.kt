/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import com.vpn0wn.config.InetAddresses
import com.vpn0wn.config.ParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class JschRemoteShellClientFactory internal constructor(private val socketCreator: () -> Socket) : RemoteShellClientFactory {
    constructor() : this(::Socket)

    override fun create(connection: VpsConnection): RemoteShellClient {
        val address = address(connection.endpoint.host, connection.endpoint.port)
        if (connection.endpoint.user.isBlank()) throw invalidInput("SSH endpoint is invalid")
        if (connection.maxAuthAttempts < 1) throw invalidInput("SSH auth attempt limit is invalid")
        val fingerprint = connection.hostKeyFingerprint ?: throw invalidInput("SSH host fingerprint is required")
        val pin = decodeHostFingerprint(fingerprint)
        return GuardedRemoteShellClient(JschRemoteShellClient(connection.endpoint, address, socketCreator, connection, pin))
    }

    // Teardown must retain original cancellation or defects when socket/session closure also fails.
    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
    override suspend fun discoverHostKey(host: String, port: Int): String {
        val address = address(host, port)
        val client = JschRemoteShellClient(VpsEndpoint(host, port, ""), address, socketCreator)
        var failure: Throwable? = null
        return try {
            withTimeoutOrNull(GuardedRemoteShellClient.DEFAULT_TIMEOUT_MILLIS) { client.discoverHostKey() }
                ?: throw ProvisioningException("SSH host-key discovery timed out")
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            try {
                client.close()
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
    }

    private fun address(host: String, port: Int): InetAddress {
        val address = try {
            InetAddresses.parse(host)
        } catch (_: ParseException) {
            throw invalidInput("SSH requires a numeric IPv4 or IPv6 address")
        }
        if (port !in MIN_PORT..MAX_PORT)
            throw invalidInput("SSH endpoint is invalid")
        return address
    }

    companion object {
        const val MAX_STDOUT_BYTES = 1_048_576
        const val MAX_STDERR_BYTES = 65_536
        private const val SHA256_BYTES = 32
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65_535
        private val FINGERPRINT = Regex("^SHA256:[A-Za-z0-9+/]{43}$")

        internal fun decodeHostFingerprint(fingerprint: String): ByteArray {
            if (!FINGERPRINT.matches(fingerprint)) throw invalidInput("SSH host fingerprint is invalid")
            val pin = Base64.decode(fingerprint.removePrefix("SHA256:"), Base64.NO_WRAP)
            val canonical = Base64.encodeToString(pin, Base64.NO_WRAP or Base64.NO_PADDING)
            if (pin.size != SHA256_BYTES || fingerprint != "SHA256:$canonical")
                throw invalidInput("SSH host fingerprint is invalid")
            return pin
        }

        private fun invalidInput(message: String) = ProvisioningException(message, category = ProvisioningFailureCategory.INVALID_INPUT)
    }
}

private class JschRemoteShellClient(
    private val endpoint: VpsEndpoint,
    private val address: InetAddress,
    private val socketCreator: () -> Socket,
    private val connection: VpsConnection? = null,
    private val pin: ByteArray? = null,
) : RemoteShellClient {
    private val operationMutex = Mutex()
    private val closed = AtomicBoolean()
    private val socket = AtomicReference<Socket?>()
    private val threadFailure = AtomicReference<Throwable?>()
    private val cleanupFailure = AtomicReference<Throwable?>()
    private val operationThread = AtomicReference<Thread?>()
    private val threads = mutableListOf<Thread>()
    private val jsch = JSch()
    private var session: Session? = null

    suspend fun discoverHostKey(): String = operate {
        val host = address.hostAddress ?: throw ProvisioningException("SSH address is unavailable")
        val repository = DiscoveredHostKey(host, endpoint.port)
        val created = createSession(repository)
        try {
            created.connect(GuardedRemoteShellClient.DEFAULT_TIMEOUT_MILLIS.toInt())
            throw ProvisioningException("SSH discovery did not stop before authentication")
        } catch (failure: JSchUnknownHostKeyException) {
            repository.fingerprint ?: throw failure
        }
    }

    override suspend fun execute(command: String): ProvisioningCommandOutput = execute(command) {}

    override suspend fun execute(command: String, onProgress: (VpsInstallProgress) -> Unit): ProvisioningCommandOutput = operate {
        val connected = connectedSession()
        val channel = connected.openChannel("exec") as ChannelExec
        val stdout = LimitedOutput(JschRemoteShellClientFactory.MAX_STDOUT_BYTES)
        val progress = VpsInstallProgressReader()
        val stderr = LimitedOutput(JschRemoteShellClientFactory.MAX_STDERR_BYTES, progress)
        try {
            channel.setInputStream(null)
            channel.setOutputStream(stdout)
            channel.setErrStream(stderr)
            channel.setCommand(command)
            channel.connect(GuardedRemoteShellClient.DEFAULT_TIMEOUT_MILLIS.toInt())
            while (!channel.isClosed) {
                checkOutput(stdout, stderr)
                progress.drain().forEach(onProgress)
                checkOpen()
                if (!connected.isConnected) throw ProvisioningException("SSH connection closed")
                Thread.sleep(CHANNEL_POLL_MILLIS)
            }
            checkOutput(stdout, stderr)
            progress.drain().forEach(onProgress)
            threadFailure.get()?.let { throw it }
            if (channel.exitStatus < 0) throw ProvisioningException("SSH command returned no exit status")
            ProvisioningCommandOutput(channel.exitStatus, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()))
        } finally {
            channel.disconnect()
        }
    }

    override suspend fun upload(path: String, content: String, mode: String): ProvisioningCommandOutput {
        if (!MODE.matches(mode) || !path.startsWith('/') || path.contains('\u0000'))
            throw ProvisioningException("SSH upload input is invalid", category = ProvisioningFailureCategory.INVALID_INPUT)
        return operate {
            val channel = connectedSession().openChannel("sftp") as ChannelSftp
            try {
                channel.connect(GuardedRemoteShellClient.DEFAULT_TIMEOUT_MILLIS.toInt())
                ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)).use { channel.put(it, path) }
                channel.chmod(mode.toInt(OCTAL_RADIX), path)
                ProvisioningCommandOutput(0, "", "")
            } finally {
                channel.disconnect()
            }
        }
    }

    // Report recorded reader defects after draining; distinct cleanup failures cannot replace them.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun close() {
        abortSocket()
        withContext(NonCancellable + Dispatchers.IO) {
            operationMutex.withLock {
                var cleanup: Throwable? = null
                try { disconnectAndJoin() } catch (problem: Throwable) {
                    cleanup = problem
                }
                threadFailure.get()?.let { failure ->
                    if (cleanup != null && cleanup !== failure) failure.addSuppressed(cleanup)
                    throw failure
                }
                cleanup?.let { throw it }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    // Cleanup reports failure only without an original cancellation or defect to preserve.
    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
    private suspend fun <T> operate(block: () -> T): T = operationMutex.withLock {
        checkOpen()
        supervisorScope {
            // Coroutine stack recovery can replace wrappers; cleanup belongs to the original failure.
            val originalFailure = AtomicReference<Throwable?>()
            val worker = async(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                runInterruptible {
                    operationThread.set(Thread.currentThread())
                    try {
                        try {
                            block()
                        } catch (_: JSchException) {
                            threadFailure.get()?.let { throw it }
                            throw ProvisioningException("SSH operation failed")
                        } catch (_: SftpException) {
                            threadFailure.get()?.let { throw it }
                            throw ProvisioningException("SSH upload failed")
                        } catch (_: IOException) {
                            threadFailure.get()?.let { throw it }
                            throw ProvisioningException("SSH transport failed")
                        } catch (problem: InterruptedException) {
                            threadFailure.get()?.let { throw it }
                            throw problem
                        }
                    } catch (problem: Throwable) {
                        originalFailure.compareAndSet(null, problem)
                        throw problem
                    } finally {
                        operationThread.set(null)
                    }
                }
            }
            var failure: Throwable? = null
            try {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cause ->
                        if (cause != null) originalFailure.set(cause)
                        abortSocket()
                        worker.cancel()
                    }
                    worker.invokeOnCompletion { cause ->
                        if (cause == null) continuation.resume(worker.getCompleted())
                        else {
                            originalFailure.compareAndSet(null, cause)
                            continuation.resumeWithException(cause)
                        }
                    }
                    worker.start()
                }
            } catch (problem: Throwable) {
                failure = originalFailure.get() ?: problem
                abortSocket()
                throw problem
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    worker.cancelAndJoin()
                    if (closed.get()) {
                        try {
                            disconnectAndJoin()
                        } catch (cleanup: Throwable) {
                            if (failure == null) throw cleanup
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                    }
                }
            }
        }
    }

    private fun connectedSession(): Session {
        checkOpen()
        session?.let { if (it.isConnected) return it else throw ProvisioningException("SSH connection closed") }
        val host = address.hostAddress ?: throw ProvisioningException("SSH address is unavailable")
        val authentication = requireNotNull(connection)
        val created = createSession(PinnedHostKey(host, endpoint.port, requireNotNull(pin)))
        created.setConfig("MaxAuthTries", authentication.maxAuthAttempts.toString())
        configureCredential(created, authentication.credential)
        created.connect(GuardedRemoteShellClient.DEFAULT_TIMEOUT_MILLIS.toInt())
        return created
    }

    private fun createSession(hostKeys: HostKeyRepository): Session {
        checkOpen()
        val host = address.hostAddress ?: throw ProvisioningException("SSH address is unavailable")
        val created = jsch.getSession(endpoint.user, host, endpoint.port)
        session = created
        created.setConfig("StrictHostKeyChecking", "yes")
        created.setConfig("server_host_key", HOST_KEYS)
        // Optional BC classes must not widen the transport's signature probes.
        created.setConfig("CheckSignatures", HOST_KEYS)
        Api24Ecdsa.configure(created)
        created.setConfig("kex", KEY_EXCHANGES)
        created.setConfig("cipher.c2s", CIPHERS)
        created.setConfig("cipher.s2c", CIPHERS)
        created.setConfig("mac.c2s", MACS)
        created.setConfig("mac.s2c", MACS)
        created.setHostKeyRepository(hostKeys)
        created.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val owned = socketCreator()
                socket.set(owned)
                if (closed.get()) {
                    owned.close()
                    throw IOException("SSH client is closed")
                }
                owned.connect(InetSocketAddress(address, endpoint.port), GuardedRemoteShellClient.DEFAULT_TIMEOUT_MILLIS.toInt())
                return owned
            }
            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })
        // JSch interrupts its reader during disconnect but does not join it.
        created.setThreadFactory { runnable ->
            Thread(runnable).also { thread ->
                thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                    threadFailure.compareAndSet(null, safeFailure(failure))
                    abortSocket()
                }
                synchronized(threads) { threads.add(thread) }
            }
        }
        return created
    }

    private fun configureCredential(created: Session, credential: VpsCredential) {
        when (credential) {
            is VpsCredential.Password -> {
                created.setConfig("PreferredAuthentications", "password")
                val bytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(credential.value))
                val password = ByteArray(bytes.remaining()).also { bytes.get(it) }
                try { created.setPassword(password) } finally {
                    password.fill(0)
                    if (bytes.hasArray()) bytes.array().fill(0)
                }
            }
            is VpsCredential.PrivateKey -> {
                val pem = credential.pem.trim()
                if (pem.length > MAX_PRIVATE_KEY_CHARACTERS ||
                    !(pem.startsWith("-----BEGIN RSA PRIVATE KEY-----") || pem.startsWith("-----BEGIN EC PRIVATE KEY-----")))
                    throw ProvisioningException("SSH private key format is unsupported", category = ProvisioningFailureCategory.INVALID_INPUT)
                val key = pem.toByteArray(Charsets.UTF_8)
                val passphrase = credential.passphrase?.toByteArray(Charsets.UTF_8)
                try {
                    val parsed = KeyPair.load(jsch, key, null)
                    var identityOwned = false
                    try {
                        if (!parsed.decrypt(passphrase) || parsed.keyType !in SUPPORTED_KEY_TYPES || parsed.keySize > MAX_KEY_BITS)
                            throw ProvisioningException("SSH private key is unsupported", category = ProvisioningFailureCategory.INVALID_INPUT)
                        identityOwned = Api24Ecdsa.addIdentity(jsch, parsed)
                    } finally { if (!identityOwned) parsed.dispose() }
                    if (!identityOwned) jsch.addIdentity("vpn0wn", key, null, passphrase)
                } finally {
                    key.fill(0)
                    passphrase?.fill(0)
                }
                created.setConfig("PreferredAuthentications", "publickey")
                created.setConfig("PubkeyAcceptedAlgorithms", HOST_KEYS)
            }
        }
    }

    private fun checkOpen() {
        threadFailure.get()?.let { throw it }
        if (closed.get()) throw ProvisioningException("SSH client is closed")
    }

    // Cancellation callbacks retain close defects for teardown instead of replacing caller cancellation.
    @Suppress("TooGenericExceptionCaught")
    private fun abortSocket() {
        closed.set(true)
        try { socket.get()?.close() } catch (_: IOException) {
            retainCleanupFailure(ProvisioningException("SSH socket close failed"))
        } catch (problem: Throwable) {
            retainCleanupFailure(problem)
        }
        operationThread.get()?.interrupt()
    }

    // Dispose remaining owners even when disconnect fails, then report the retained failure or defect.
    @Suppress("TooGenericExceptionCaught")
    private fun disconnectAndJoin() {
        try { session?.disconnect() } catch (problem: Throwable) {
            retainCleanupFailure(safeFailure(problem))
        }
        try { jsch.removeAllIdentity() } catch (problem: Throwable) {
            retainCleanupFailure(safeFailure(problem))
        }
        synchronized(threads) { threads.toList() }.forEach { thread ->
            if (thread !== Thread.currentThread()) thread.join()
        }
        cleanupFailure.get()?.let { throw it }
    }

    private fun retainCleanupFailure(failure: Throwable) {
        if (!cleanupFailure.compareAndSet(null, failure)) {
            cleanupFailure.get()?.let { retained ->
                if (retained !== failure) retained.addSuppressed(failure)
            }
        }
    }

    private fun safeFailure(failure: Throwable): Throwable = when (failure) {
        is JSchException -> ProvisioningException("SSH operation failed")
        is SftpException -> ProvisioningException("SSH upload failed")
        is IOException -> ProvisioningException("SSH transport failed")
        else -> failure
    }

    private fun checkOutput(stdout: LimitedOutput, stderr: LimitedOutput) {
        if (stdout.overflowed || stderr.overflowed) {
            abortSocket()
            throw ProvisioningException("SSH output exceeded limit")
        }
    }

    companion object {
        private const val CHANNEL_POLL_MILLIS = 10L
        private const val OCTAL_RADIX = 8
        private const val MAX_PRIVATE_KEY_CHARACTERS = 65_536
        private const val MAX_KEY_BITS = 4096
        private const val HOST_KEYS = "ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256"
        private const val KEY_EXCHANGES = "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group14-sha256,diffie-hellman-group16-sha512"
        private const val CIPHERS = "aes128-ctr,aes192-ctr,aes256-ctr,aes128-gcm@openssh.com,aes256-gcm@openssh.com"
        private const val MACS = "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512"
        private val MODE = Regex("^0?[0-7]{3}$")
        private val SUPPORTED_KEY_TYPES = setOf(KeyPair.RSA, KeyPair.ECDSA)
    }
}

private class DiscoveredHostKey(host: String, port: Int) : HostKeyRepository {
    private val expectedHost = if (port == 22) host else "[$host]:$port"
    var fingerprint: String? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        if (host != expectedHost) return HostKeyRepository.CHANGED
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        fingerprint = "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        // Strict rejection ends JSch's verified key exchange before even userauth-none sends a username.
        return HostKeyRepository.NOT_INCLUDED
    }

    override fun getKnownHostsRepositoryID(): String = "vpn0wn host discovery"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    override fun add(hostkey: HostKey?, ui: UserInfo?) = throw UnsupportedOperationException("SSH discovery cannot trust a key")
    override fun remove(host: String?, type: String?) = throw UnsupportedOperationException("SSH discovery cannot change trust")
    override fun remove(host: String?, type: String?, key: ByteArray?) = throw UnsupportedOperationException("SSH discovery cannot change trust")
}

private class PinnedHostKey(host: String, port: Int, private val pin: ByteArray) : HostKeyRepository {
    private val expectedHost = if (port == 22) host else "[$host]:$port"
    override fun check(host: String, key: ByteArray): Int =
        if (host == expectedHost && MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(key))) HostKeyRepository.OK
        else HostKeyRepository.CHANGED
    override fun getKnownHostsRepositoryID(): String = "vpn0wn pinned host"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    override fun add(hostkey: HostKey?, ui: UserInfo?) = throw UnsupportedOperationException("SSH pin is immutable")
    override fun remove(host: String?, type: String?) = throw UnsupportedOperationException("SSH pin is immutable")
    override fun remove(host: String?, type: String?, key: ByteArray?) = throw UnsupportedOperationException("SSH pin is immutable")
}

private class LimitedOutput(private val limit: Int, private val progress: VpsInstallProgressReader? = null) : ByteArrayOutputStream() {
    @Volatile var overflowed = false
        private set
    @Synchronized override fun write(value: Int) {
        if (count >= limit) overflow()
        super.write(value)
        progress?.write(value)
    }
    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length > limit - count) overflow()
        super.write(bytes, offset, length)
        if (progress != null) for (index in offset until offset + length) progress.write(bytes[index].toInt())
    }
    private fun overflow(): Nothing {
        overflowed = true
        throw IOException("SSH output exceeded limit")
    }
}
