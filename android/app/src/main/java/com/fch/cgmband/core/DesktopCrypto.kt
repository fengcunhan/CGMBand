package com.fch.cgmband.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto for the phone ↔ Mac protocol (docs/desktop-protocol.md): P-256 identities and ECDH,
 * HKDF-SHA256 session keys per direction, a commit/reveal pairing code, and AES-256-GCM frames
 * whose nonce is the frame sequence number. Byte-compatible with CryptoKit (see the vectors test).
 */
object DesktopCrypto {

    data class SessionKeys(val phoneToMac: ByteArray, val macToPhone: ByteArray)

    private const val COORDINATE_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private val AAD = "cgmband/v1".toByteArray()

    private val curve: ECParameterSpec by lazy {
        (generateIdentity().public as ECPublicKey).params
    }

    fun generateIdentity(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun privateKeyFromScalar(scalar: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), curve))

    /** X9.63 uncompressed: 0x04 || X || Y. */
    fun encodePublicKey(key: ECPublicKey): ByteArray {
        val point = key.w
        return byteArrayOf(4) + fixed(point.affineX) + fixed(point.affineY)
    }

    /** Rejects anything that is not an uncompressed point on P-256 (guards against invalid-curve attacks). */
    fun decodePublicKey(x963: ByteArray): ECPublicKey {
        require(x963.size == 1 + 2 * COORDINATE_BYTES && x963[0] == 4.toByte()) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, x963.copyOfRange(1, 1 + COORDINATE_BYTES))
        val y = BigInteger(1, x963.copyOfRange(1 + COORDINATE_BYTES, x963.size))
        require(isOnCurve(x, y)) { "point is not on P-256" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    fun sharedSecret(own: PrivateKey, peer: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(peer, true)
            generateSecret()
        }

    fun sessionKeys(shared: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray): SessionKeys {
        val salt = phoneNonce + macNonce
        return SessionKeys(
            phoneToMac = hkdf(shared, salt, "cgmband/v1/p2m", 32),
            macToPhone = hkdf(shared, salt, "cgmband/v1/m2p", 32),
        )
    }

    fun commit(phonePublicKey: ByteArray, reveal: ByteArray): ByteArray =
        sha256("cgmband/v1/commit".toByteArray() + phonePublicKey + reveal)

    /** Six digits both screens show during pairing. */
    fun pairingCode(
        shared: ByteArray,
        phonePublicKey: ByteArray,
        macPublicKey: ByteArray,
        reveal: ByteArray,
        pairNonce: ByteArray,
    ): String {
        val salt = sha256(phonePublicKey + macPublicKey + reveal + pairNonce)
        val value = ByteBuffer.wrap(hkdf(shared, salt, "cgmband/v1/sas", 4)).int.toLong() and 0xFFFFFFFFL
        return "%06d".format(value % 1_000_000)
    }

    fun seal(key: ByteArray, seq: Long, plaintext: String): String {
        val cipher = gcm(Cipher.ENCRYPT_MODE, key, seq)
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /** Null when the frame fails authentication or is malformed. */
    fun open(key: ByteArray, seq: Long, ciphertext: String): String? = runCatching {
        val cipher = gcm(Cipher.DECRYPT_MODE, key, seq)
        String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), Charsets.UTF_8)
    }.getOrNull()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { java.security.SecureRandom().nextBytes(it) }

    private fun gcm(mode: Int, key: ByteArray, seq: Long): Cipher {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(AAD)
        }
    }

    /** RFC 5869 HKDF-SHA256. */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        var block = ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info.toByteArray() + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(COORDINATE_BYTES - raw.size) + raw
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val field = curve.curve.field as java.security.spec.ECFieldFp
        val p = field.p
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val left = y.modPow(BigInteger.valueOf(2), p)
        val right = x.modPow(BigInteger.valueOf(3), p).add(curve.curve.a.multiply(x)).add(curve.curve.b).mod(p)
        return left == right
    }
}
