package com.fch.cgmband.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey
import java.util.Base64

class DesktopSessionTest {

    /** Minimal Mac side of docs/desktop-protocol.md, used to drive the phone session. */
    private class FakeMac(val knowsPhone: Boolean = false, val proto: Int = 1) {
        val keys = DesktopCrypto.generateIdentity()
        val pub = DesktopCrypto.encodePublicKey(keys.public as ECPublicKey)
        val id = "mac-id-0001"
        private val nonce = DesktopCrypto.randomBytes(16)
        private val pairNonce = DesktopCrypto.randomBytes(16)
        private lateinit var phonePub: ByteArray
        private lateinit var commit: ByteArray
        private lateinit var shared: ByteArray
        lateinit var session: DesktopCrypto.SessionKeys
        var code: String? = null
        private var outSeq = 0L
        private var inSeq = 0L

        fun hello(phoneHello: String): String {
            val h = JSONObject(phoneHello)
            phonePub = b64(h.getString("pub"))
            commit = b64(h.getString("commit"))
            shared = DesktopCrypto.sharedSecret(keys.private, DesktopCrypto.decodePublicKey(phonePub))
            session = DesktopCrypto.sessionKeys(shared, b64(h.getString("nonce")), nonce)
            return JSONObject()
                .put("type", "hello").put("proto", proto).put("id", id).put("name", "Test Mac")
                .put("pub", enc(pub)).put("nonce", enc(nonce)).put("pairNonce", enc(pairNonce))
                .toString()
        }

        fun reveal(frame: String) {
            val r = b64(JSONObject(frame).getString("r"))
            assertTrue("commit must verify", DesktopCrypto.commit(phonePub, r).contentEquals(commit))
            code = DesktopCrypto.pairingCode(shared, phonePub, pub, r, pairNonce)
        }

        fun read(frame: String): JSONObject {
            val f = JSONObject(frame)
            assertEquals("enc", f.getString("type"))
            assertEquals(inSeq++, f.getLong("seq"))
            return JSONObject(DesktopCrypto.open(session.phoneToMac, f.getLong("seq"), f.getString("ct"))!!)
        }

        fun send(inner: String): String {
            val seq = outSeq++
            return JSONObject().put("type", "enc").put("seq", seq)
                .put("ct", DesktopCrypto.seal(session.macToPhone, seq, inner)).toString()
        }

        fun trust() = send("""{"type":"trust","known":$knowsPhone}""")

        private fun b64(s: String) = Base64.getDecoder().decode(s)
        private fun enc(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    }

    private val phoneIdentity = DesktopIdentity.generate("phone-id-0001", "Test Phone")

    private fun session(trusted: Map<String, ByteArray> = emptyMap(), allowPairing: Boolean = true) =
        DesktopSession(phoneIdentity, trusted, allowPairing)

    /** Runs hello → hello → reveal + trust → mac trust; returns the session output after the Mac's trust. */
    private fun handshake(phone: DesktopSession, mac: FakeMac): DesktopSession.Output {
        val hello = phone.start().frames.single()
        val afterHello = phone.onFrame(mac.hello(hello))
        val (reveal, trust) = afterHello.frames
        mac.reveal(reveal)
        assertEquals("trust", mac.read(trust).getString("type"))
        return phone.onFrame(mac.trust())
    }

    @Test
    fun `first connection pairs after both sides confirm the same code`() {
        val mac = FakeMac(knowsPhone = false)
        val phone = session()

        val pairing = handshake(phone, mac).events.single() as DesktopSession.Event.PairingRequired
        assertEquals(mac.code, pairing.code)
        assertEquals("Test Mac", pairing.macName)

        assertNull("no data before pairing completes", phone.seal("""{"type":"bg"}"""))

        val confirmed = phone.confirmPairing(true)
        assertEquals("paired", mac.read(confirmed.frames.single()).getString("type"))
        assertTrue(confirmed.events.isEmpty())
        assertNull("still waiting for the Mac to confirm", phone.seal("""{"type":"bg"}"""))

        val ready = phone.onFrame(mac.send("""{"type":"paired"}""")).events.single() as DesktopSession.Event.Ready
        assertTrue(ready.newlyPaired)
        assertEquals(mac.id, ready.mac.id)
        assertTrue(ready.mac.publicKey.contentEquals(mac.pub))

        val bg = phone.seal("""{"type":"bg","value":"6.3"}""")!!
        assertEquals("6.3", mac.read(bg).getString("value"))
    }

    @Test
    fun `known mac that also knows the phone connects without pairing`() {
        val mac = FakeMac(knowsPhone = true)
        val phone = session(trusted = mapOf(mac.id to mac.pub))

        val ready = handshake(phone, mac).events.single() as DesktopSession.Event.Ready

        assertTrue(!ready.newlyPaired)
    }

    @Test
    fun `background connection never pairs`() {
        val mac = FakeMac(knowsPhone = true)
        val phone = session(allowPairing = false)

        val closed = handshake(phone, mac).events.single() as DesktopSession.Event.Closed

        assertEquals("needs_pairing", closed.reason)
    }

    @Test
    fun `a changed mac key is treated as unknown`() {
        val mac = FakeMac(knowsPhone = true)
        val stalePub = FakeMac().pub
        val phone = session(trusted = mapOf(mac.id to stalePub))

        assertTrue(handshake(phone, mac).events.single() is DesktopSession.Event.PairingRequired)
    }

    @Test
    fun `rejecting the code tells the mac and closes`() {
        val mac = FakeMac()
        val phone = session()
        handshake(phone, mac)

        val out = phone.confirmPairing(false)

        assertEquals("rejected", mac.read(out.frames.single()).getString("type"))
        assertEquals("rejected_by_user", (out.events.single() as DesktopSession.Event.Closed).reason)
        assertNull(phone.seal("""{"type":"bg"}"""))
    }

    @Test
    fun `answers ping and surfaces sync requests once ready`() {
        val mac = FakeMac(knowsPhone = true)
        val phone = session(trusted = mapOf(mac.id to mac.pub))
        handshake(phone, mac)

        val pong = phone.onFrame(mac.send("""{"type":"ping"}"""))
        assertEquals("pong", mac.read(pong.frames.single()).getString("type"))

        val sync = phone.onFrame(mac.send("""{"type":"sync"}"""))
        assertEquals(DesktopSession.Event.SyncRequested, sync.events.single())
    }

    @Test
    fun `keep alive pings only once ready`() {
        val mac = FakeMac(knowsPhone = true)
        val phone = session(trusted = mapOf(mac.id to mac.pub))
        phone.start()
        assertNull(phone.keepAlive())

        val ready = session(trusted = mapOf(mac.id to mac.pub))
        handshake(ready, mac)
        assertEquals("ping", mac.read(ready.keepAlive()!!).getString("type"))
    }

    @Test
    fun `tampered or replayed frames close the session`() {
        val mac = FakeMac(knowsPhone = true)
        val phone = session(trusted = mapOf(mac.id to mac.pub))
        handshake(phone, mac)

        val frame = JSONObject(mac.send("""{"type":"ping"}"""))
        val tampered = JSONObject(frame.toString()).put("ct", "AAAA" + frame.getString("ct").drop(4))
        assertEquals("crypto", (phone.onFrame(tampered.toString()).events.single() as DesktopSession.Event.Closed).reason)

        val replayMac = FakeMac(knowsPhone = true)
        val replayPhone = session(trusted = mapOf(replayMac.id to replayMac.pub))
        handshake(replayPhone, replayMac)
        val ping = replayMac.send("""{"type":"ping"}""")
        replayPhone.onFrame(ping)
        assertEquals("crypto", (replayPhone.onFrame(ping).events.single() as DesktopSession.Event.Closed).reason)
    }

    @Test
    fun `unsupported protocol version and error frames close the session`() {
        val phone = session()
        val hello = phone.start().frames.single()
        val closed = phone.onFrame(FakeMac(proto = 2).hello(hello)).events.single() as DesktopSession.Event.Closed
        assertEquals("proto", closed.reason)

        val other = session()
        other.start()
        val error = other.onFrame("""{"type":"error","code":"busy"}""").events.single() as DesktopSession.Event.Closed
        assertEquals("busy", error.reason)
    }

    @Test
    fun `garbage input closes the session`() {
        val phone = session()
        phone.start()

        assertEquals("protocol", (phone.onFrame("not json").events.single() as DesktopSession.Event.Closed).reason)
    }
}
