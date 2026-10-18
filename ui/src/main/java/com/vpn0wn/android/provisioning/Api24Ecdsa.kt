/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.os.Build
import androidx.annotation.Keep
import com.jcraft.jsch.Buffer
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.SignatureECDSA
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.SignatureException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers

// API24/25 support EC keys and signatures, but not AlgorithmParameters("EC").
internal object Api24Ecdsa {
    fun configure(session: Session) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        session.setConfig(EcdsaCurve.NISTP256.algorithm, Api24EcdsaNistp256::class.java.name)
        session.setConfig(EcdsaCurve.NISTP384.algorithm, Api24EcdsaNistp384::class.java.name)
        session.setConfig(EcdsaCurve.NISTP521.algorithm, Api24EcdsaNistp521::class.java.name)
    }

    // JSch's private-key signer reads global config instead of the session's verifier mapping.
    fun addIdentity(jsch: JSch, keyPair: KeyPair): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || keyPair.keyType != KeyPair.ECDSA) return false
        jsch.addIdentity(Api24EcdsaIdentity(keyPair), null)
        return true
    }
}

internal enum class EcdsaCurve(val nameInSsh: String, val bits: Int, val oid: ASN1ObjectIdentifier, val signatureAlgorithm: String) {
    NISTP256("nistp256", 256, SECObjectIdentifiers.secp256r1, "SHA256withECDSA"),
    NISTP384("nistp384", 384, SECObjectIdentifiers.secp384r1, "SHA384withECDSA"),
    NISTP521("nistp521", 521, SECObjectIdentifiers.secp521r1, "SHA512withECDSA");

    val algorithm: String get() = "ecdsa-sha2-$nameInSsh"
    val coordinateBytes: Int get() = (bits + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
    val identifier: AlgorithmIdentifier get() = AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, oid)
}

// JSch constructs configured implementations by their public no-argument constructors.
@Keep internal class Api24EcdsaNistp256 : Api24EcdsaSignature(EcdsaCurve.NISTP256)
@Keep internal class Api24EcdsaNistp384 : Api24EcdsaSignature(EcdsaCurve.NISTP384)
@Keep internal class Api24EcdsaNistp521 : Api24EcdsaSignature(EcdsaCurve.NISTP521)

internal open class Api24EcdsaSignature(private val curve: EcdsaCurve) : SignatureECDSA {
    private lateinit var signature: Signature

    override fun init() {
        signature = Signature.getInstance(curve.signatureAlgorithm)
    }

    override fun setPubKey(r: ByteArray, s: ByteArray) {
        if (r.size != curve.coordinateBytes || s.size != curve.coordinateBytes) throw SignatureException("Invalid SSH EC point")
        val point = byteArrayOf(UNCOMPRESSED_POINT) + r + s
        val encoded = SubjectPublicKeyInfo(curve.identifier, point).encoded
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        signature.initVerify(key)
    }

    override fun setPrvKey(d: ByteArray) {
        val privateKey = ECPrivateKey(curve.bits, BigInteger(1, d), curve.oid)
        val encoded = PrivateKeyInfo(curve.identifier, privateKey).encoded
        try {
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))
            signature.initSign(key)
        } finally { encoded.fill(0) }
    }

    override fun update(data: ByteArray) = signature.update(data)
    override fun sign(): ByteArray = derEcdsaSignatureToSsh(signature.sign())
    override fun verify(encoded: ByteArray): Boolean = signature.verify(sshEcdsaSignatureToDer(curve.algorithm, encoded))

    private companion object {
        const val UNCOMPRESSED_POINT: Byte = 4
    }
}

private class Api24EcdsaIdentity(keyPair: KeyPair) : Identity {
    private var keyPair: KeyPair? = keyPair
    private val publicKeyBlob = keyPair.publicKeyBlob
    private val algorithm = readSshString(Buffer(publicKeyBlob), publicKeyBlob.size).toString(Charsets.US_ASCII)
    private val curve = EcdsaCurve.entries.firstOrNull { it.algorithm == algorithm }
        ?: throw com.jcraft.jsch.JSchException("SSH EC identity is unsupported")

    override fun getName(): String = "vpn0wn"
    override fun getAlgName(): String = algorithm
    override fun getPublicKeyBlob(): ByteArray? = keyPair?.publicKeyBlob
    override fun isEncrypted(): Boolean = keyPair?.isEncrypted ?: false
    override fun setPassphrase(passphrase: ByteArray?): Boolean = keyPair?.decrypt(passphrase) ?: false
    override fun getSignature(data: ByteArray): ByteArray? = getSignature(data, algorithm)

    override fun getSignature(data: ByteArray, alg: String): ByteArray? {
        val parsed = keyPair ?: return null
        if (alg != algorithm) return null
        val exported = parsed.forSSHAgent()
        var scalar: ByteArray? = null
        try {
            val buffer = Buffer(exported)
            val exportedAlgorithm = readSshString(buffer, exported.size)
            val exportedCurve = readSshString(buffer, exported.size)
            readSshString(buffer, exported.size)
            scalar = readSshString(buffer, exported.size)
            readSshString(buffer, exported.size)
            if (exportedAlgorithm.toString(Charsets.US_ASCII) != algorithm ||
                exportedCurve.toString(Charsets.US_ASCII) != curve.nameInSsh || buffer.offSet != exported.size)
                throw SignatureException("Invalid SSH EC identity")
            val signer = Api24EcdsaSignature(curve)
            signer.init()
            signer.setPrvKey(scalar)
            signer.update(data)
            val signed = Buffer()
            signed.putString(exportedAlgorithm)
            signed.putString(signer.sign())
            return signed.copyBytes()
        } finally {
            scalar?.fill(0)
            exported.fill(0)
        }
    }

    override fun clear() {
        keyPair?.dispose()
        keyPair = null
    }
}

// Keep independent algorithm and nested-frame rejection guards ahead of DER conversion.
@Suppress("ThrowsCount")
internal fun sshEcdsaSignatureToDer(algorithm: String, encoded: ByteArray): ByteArray {
    val outer = Buffer(encoded)
    if (readSshString(outer, encoded.size).toString(Charsets.US_ASCII) != algorithm)
        throw SignatureException("Invalid SSH ECDSA algorithm")
    val payload = readSshString(outer, encoded.size)
    if (outer.offSet != encoded.size) throw SignatureException("Invalid SSH ECDSA signature")
    val inner = Buffer(payload)
    val r = positiveInteger(readSshString(inner, payload.size))
    val s = positiveInteger(readSshString(inner, payload.size))
    if (inner.offSet != payload.size) throw SignatureException("Invalid SSH ECDSA signature")
    return DERSequence(ASN1Integer(r), ASN1Integer(s)).encoded
}

internal fun derEcdsaSignatureToSsh(encoded: ByteArray): ByteArray {
    val sequence = ASN1Sequence.getInstance(encoded)
    if (sequence.size() != 2) throw SignatureException("Invalid ECDSA signature")
    val r = ASN1Integer.getInstance(sequence.getObjectAt(0)).value
    val s = ASN1Integer.getInstance(sequence.getObjectAt(1)).value
    if (r.signum() <= 0 || s.signum() <= 0) throw SignatureException("Invalid ECDSA signature")
    val buffer = Buffer()
    buffer.putMPInt(r.toByteArray())
    buffer.putMPInt(s.toByteArray())
    return buffer.copyBytes()
}

private fun positiveInteger(encoded: ByteArray): BigInteger {
    if (encoded.isEmpty()) throw SignatureException("Invalid SSH ECDSA integer")
    val value = BigInteger(encoded)
    if (value.signum() <= 0) throw SignatureException("Invalid SSH ECDSA integer")
    return value
}

private fun readSshString(buffer: Buffer, size: Int): ByteArray {
    if (size - buffer.offSet < Int.SIZE_BYTES) throw SignatureException("Invalid SSH ECDSA encoding")
    val length = buffer.int
    if (length < 0 || length > size - buffer.offSet) throw SignatureException("Invalid SSH ECDSA encoding")
    return ByteArray(length).also(buffer::getByte)
}

private fun Buffer.copyBytes(): ByteArray = ByteArray(length).also { getByte(it) }
