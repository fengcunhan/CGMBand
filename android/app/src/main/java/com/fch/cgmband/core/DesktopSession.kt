package com.fch.cgmband.core

import org.json.JSONException
import org.json.JSONObject
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** This phone's long-term identity for the desktop protocol. */
class DesktopIdentity(val id: String, val name: String, val privateKey: PrivateKey, val publicKey: ByteArray) {
    companion object {
        fun generate(id: String, name: String): DesktopIdentity {
            val pair = DesktopCrypto.generateIdentity()
            return DesktopIdentity(id, name, pair.private, DesktopCrypto.encodePublicKey(pair.public as ECPublicKey))
        }
    }
}

/**
 * Phone side of one connection to a Mac (docs/desktop-protocol.md), as a pure state machine:
 * feed it received lines, send the frames it returns, react to its events. No I/O, no threads —
 * the caller serialises calls.
 *
 * @param trusted Mac id → public key of every Mac the user has paired with
 * @param allowPairing false for background auto-connects: pairing then closes instead of prompting
 */
class DesktopSession(
    private val identity: DesktopIdentity,
    private val trusted: Map<String, ByteArray>,
    private val allowPairing: Boolean,
) {
    data class Peer(val id: String, val name: String, val publicKey: ByteArray)

    sealed interface Event {
        data class PairingRequired(val macName: String, val code: String) : Event
        data class Ready(val mac: Peer, val newlyPaired: Boolean) : Event
        data object SyncRequested : Event
        data class Closed(val reason: String) : Event
    }

    data class Output(val frames: List<String> = emptyList(), val events: List<Event> = emptyList())

    private enum class State { NEW, AWAIT_HELLO, AWAIT_TRUST, PAIRING, READY, CLOSED }

    private var state = State.NEW
    private val nonce = DesktopCrypto.randomBytes(16)
    private val reveal = DesktopCrypto.randomBytes(16)
    private var keys: DesktopCrypto.SessionKeys? = null
    private var mac: Peer? = null
    private var macKnownByUs = false
    private var pairingCode: String? = null
    private var confirmedLocally = false
    private var confirmedByMac = false
    private var outSeq = 0L
    private var inSeq = 0L

    fun start(): Output {
        check(state == State.NEW) { "session already started" }
        state = State.AWAIT_HELLO
        val hello = JSONObject()
            .put("type", "hello").put("proto", PROTOCOL_VERSION)
            .put("id", identity.id).put("name", identity.name)
            .put("pub", b64(identity.publicKey)).put("nonce", b64(nonce))
            .put("commit", b64(DesktopCrypto.commit(identity.publicKey, reveal)))
        return Output(frames = listOf(hello.toString()))
    }

    fun onFrame(line: String): Output {
        if (state == State.CLOSED) return Output()
        val frame = try {
            JSONObject(line)
        } catch (e: JSONException) {
            return close("protocol")
        }
        return when (frame.optString("type")) {
            "error" -> close(frame.optString("code", "error"))
            "hello" -> if (state == State.AWAIT_HELLO) onHello(frame) else close("protocol")
            "enc" -> onEncrypted(frame)
            else -> close("protocol")
        }
    }

    /** The user's answer to [Event.PairingRequired]. */
    fun confirmPairing(accepted: Boolean): Output {
        if (state != State.PAIRING) return Output()
        if (!accepted) {
            val frame = sealFrame("""{"type":"rejected"}""")
            return close("rejected_by_user").copy(frames = listOfNotNull(frame))
        }
        confirmedLocally = true
        val frame = sealFrame("""{"type":"paired"}""")
        return readyIfBothConfirmed(listOfNotNull(frame))
    }

    /** Encrypts a data message (bg / history) for the Mac; null unless pairing is complete. */
    fun seal(message: String): String? = if (state == State.READY) sealFrame(message) else null

    /** Protocol frames (trust, paired, pong…) may be sent as soon as the session keys exist. */
    private fun sealFrame(message: String): String? {
        val sessionKeys = keys ?: return null
        val seq = outSeq++
        return JSONObject().put("type", "enc").put("seq", seq)
            .put("ct", DesktopCrypto.seal(sessionKeys.phoneToMac, seq, message)).toString()
    }

    /** Heartbeat frame for an idle connection; null unless ready. */
    fun keepAlive(): String? = if (state == State.READY) sealFrame("""{"type":"ping"}""") else null

    val isReady: Boolean
        get() = state == State.READY

    private fun onHello(frame: JSONObject): Output {
        if (frame.optInt("proto") != PROTOCOL_VERSION) return close("proto")
        val peer = try {
            Peer(frame.getString("id"), frame.getString("name"), unb64(frame.getString("pub")))
        } catch (e: RuntimeException) {
            return close("protocol")
        }
        val macNonce = unb64OrNull(frame.optString("nonce"))
        val pairNonce = unb64OrNull(frame.optString("pairNonce"))
        if (macNonce?.size != 16 || pairNonce?.size != 16) return close("protocol")
        val macKey = runCatching { DesktopCrypto.decodePublicKey(peer.publicKey) }.getOrNull() ?: return close("protocol")

        val shared = DesktopCrypto.sharedSecret(identity.privateKey, macKey)
        keys = DesktopCrypto.sessionKeys(shared, nonce, macNonce)
        pairingCode = DesktopCrypto.pairingCode(shared, identity.publicKey, peer.publicKey, reveal, pairNonce)
        mac = peer
        macKnownByUs = trusted[peer.id]?.contentEquals(peer.publicKey) == true
        state = State.AWAIT_TRUST

        val revealFrame = JSONObject().put("type", "reveal").put("r", b64(reveal)).toString()
        val trustFrame = sealFrame(JSONObject().put("type", "trust").put("known", macKnownByUs).toString())
        return Output(frames = listOfNotNull(revealFrame, trustFrame))
    }

    private fun onEncrypted(frame: JSONObject): Output {
        val sessionKeys = keys ?: return close("protocol")
        val seq = frame.optLong("seq", -1)
        if (seq != inSeq) return close("crypto")
        val plain = DesktopCrypto.open(sessionKeys.macToPhone, seq, frame.optString("ct")) ?: return close("crypto")
        inSeq++
        val inner = try {
            JSONObject(plain)
        } catch (e: JSONException) {
            return close("protocol")
        }
        return onMessage(inner)
    }

    private fun onMessage(inner: JSONObject): Output = when (inner.optString("type")) {
        "trust" -> if (state == State.AWAIT_TRUST) onTrust(inner.optBoolean("known")) else Output()
        "paired" -> if (state == State.PAIRING) {
            confirmedByMac = true
            readyIfBothConfirmed(emptyList())
        } else {
            Output()
        }
        "rejected" -> close("rejected")
        "ping" -> Output(frames = listOfNotNull(sealFrame("""{"type":"pong"}""")))
        "sync" -> if (state == State.READY) Output(events = listOf(Event.SyncRequested)) else Output()
        else -> Output() // pong and unknown types are ignored
    }

    private fun onTrust(macKnowsUs: Boolean): Output {
        val peer = mac ?: return close("protocol")
        if (macKnownByUs && macKnowsUs) {
            state = State.READY
            return Output(events = listOf(Event.Ready(peer, newlyPaired = false)))
        }
        if (!allowPairing) return close("needs_pairing")
        state = State.PAIRING
        return Output(events = listOf(Event.PairingRequired(peer.name, pairingCode.orEmpty())))
    }

    private fun readyIfBothConfirmed(frames: List<String>): Output {
        val peer = mac ?: return close("protocol")
        if (!(confirmedLocally && confirmedByMac)) return Output(frames = frames)
        state = State.READY
        return Output(frames = frames, events = listOf(Event.Ready(peer, newlyPaired = true)))
    }

    private fun close(reason: String): Output {
        state = State.CLOSED
        keys = null
        return Output(events = listOf(Event.Closed(reason)))
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
    private fun unb64OrNull(text: String): ByteArray? = runCatching { unb64(text) }.getOrNull()

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}
