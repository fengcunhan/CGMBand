package com.fch.cgmband.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** Vectors from tools/desktop_vectors.swift (CryptoKit) — see docs/desktop-protocol.md §7. */
class DesktopCryptoTest {

    private fun bytes(b: Int, n: Int) = ByteArray(n) { b.toByte() }
    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val phonePrivate = DesktopCrypto.privateKeyFromScalar(bytes(0x11, 32))
    private val macPrivate = DesktopCrypto.privateKeyFromScalar(bytes(0x22, 32))
    private val phonePub = b64("BAIX5hfwtkQ5KCePlpmeaaI6TywVK99tbN9m5bgCgtTtGUp968uXcS0t2jyoWqh2Wlb0X8dYWZZS8ol8ZTBuV5Q=")
    private val macPub = b64("BNZak5d8qj0bCBhS/1ennkZfFmBXcwS66tUF3TpIWJzzUBheiVNy32Ih6joTdVfkc/3bZ1XwW9UHw8Uz/OnJEoU=")
    private val phoneNonce = bytes(0xA0, 16)
    private val macNonce = bytes(0xB0, 16)
    private val reveal = bytes(0xC0, 16)
    private val pairNonce = bytes(0xD0, 16)

    @Test
    fun `public keys round trip through x963`() {
        assertArrayEquals(macPub, DesktopCrypto.encodePublicKey(DesktopCrypto.decodePublicKey(macPub)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a public key that is not on the curve`() {
        val tampered = macPub.copyOf().also { it[64] = (it[64] + 1).toByte() }
        DesktopCrypto.decodePublicKey(tampered)
    }

    @Test
    fun `derives the commitment shared secret and session keys`() {
        assertArrayEquals(b64("WUDIKaJsGLh80Ociel3tFWLw+xCpTChZn1+dCw7AX3Y="), DesktopCrypto.commit(phonePub, reveal))

        val shared = DesktopCrypto.sharedSecret(phonePrivate, DesktopCrypto.decodePublicKey(macPub))
        assertEquals("ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6", hex(shared))
        // ECDH is symmetric: the Mac computes the same secret.
        assertArrayEquals(shared, DesktopCrypto.sharedSecret(macPrivate, DesktopCrypto.decodePublicKey(phonePub)))

        val keys = DesktopCrypto.sessionKeys(shared, phoneNonce, macNonce)
        assertEquals("94f16e12d76d5e02f2c75a16de0a0dc2c71225dbfde5b24e462e3fa2cb3b1303", hex(keys.phoneToMac))
        assertEquals("00d90b12dd5eb8bff60c5703754bb0236bc3b121d7715a71fa17d4ff44e97aa2", hex(keys.macToPhone))

        assertEquals("506367", DesktopCrypto.pairingCode(shared, phonePub, macPub, reveal, pairNonce))
    }

    @Test
    fun `seals and opens frames exactly like CryptoKit`() {
        val shared = DesktopCrypto.sharedSecret(phonePrivate, DesktopCrypto.decodePublicKey(macPub))
        val keys = DesktopCrypto.sessionKeys(shared, phoneNonce, macNonce)

        assertEquals("uOa/vkl8jorvL+vvUXQkUaBAky3CsdHK9/J+IDkjvA==", DesktopCrypto.seal(keys.phoneToMac, 0, """{"type":"ping"}"""))
        assertEquals("""{"type":"sync"}""", DesktopCrypto.open(keys.macToPhone, 5, "Z1MmDyXGsW26uezhzXXWNDRJ4a7hylV1sznn5JaHgQ=="))
    }

    @Test
    fun `opening with the wrong key or sequence fails`() {
        val shared = DesktopCrypto.sharedSecret(phonePrivate, DesktopCrypto.decodePublicKey(macPub))
        val keys = DesktopCrypto.sessionKeys(shared, phoneNonce, macNonce)
        val ct = "Z1MmDyXGsW26uezhzXXWNDRJ4a7hylV1sznn5JaHgQ=="

        assertNull(DesktopCrypto.open(keys.macToPhone, 4, ct))
        assertNull(DesktopCrypto.open(keys.phoneToMac, 5, ct))
        assertNull(DesktopCrypto.open(keys.macToPhone, 5, "not base64 !!"))
    }
}
