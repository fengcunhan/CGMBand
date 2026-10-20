package com.fch.cgmband

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.fch.cgmband.core.DesktopCrypto
import com.fch.cgmband.core.DesktopIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * This phone's desktop-protocol identity and the Macs it has paired with. Kept in app-private
 * preferences, which data_extraction_rules exclude from backup and device transfer.
 */
object DesktopStore {

    data class TrustedMac(val id: String, val name: String, val publicKey: ByteArray)

    private const val PREFS = "cgm_desktop"
    private const val KEY_ID = "identity_id"
    private const val KEY_PRIVATE = "identity_private_pkcs8"
    private const val KEY_PUBLIC = "identity_public_x963"
    private const val KEY_TRUSTED = "trusted_macs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ADDRESSES = "mac_addresses"

    @Synchronized
    fun identity(context: Context): DesktopIdentity {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_ID, null)
        val privateKey = prefs.getString(KEY_PRIVATE, null)
        val publicKey = prefs.getString(KEY_PUBLIC, null)
        if (id != null && privateKey != null && publicKey != null) {
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(privateKey)))
            return DesktopIdentity(id, phoneName(context), key, unb64(publicKey))
        }
        val created = DesktopIdentity.generate(newId(), phoneName(context))
        prefs.edit()
            .putString(KEY_ID, created.id)
            .putString(KEY_PRIVATE, b64(created.privateKey.encoded))
            .putString(KEY_PUBLIC, b64(created.publicKey))
            .apply()
        return created
    }

    fun trustedMacs(context: Context): List<TrustedMac> {
        val json = JSONObject(prefs(context).getString(KEY_TRUSTED, "{}") ?: "{}")
        return json.keys().asSequence().mapNotNull { id ->
            val entry = json.optJSONObject(id) ?: return@mapNotNull null
            TrustedMac(id, entry.optString("name", id), unb64(entry.getString("pub")))
        }.toList()
    }

    @Synchronized
    fun trust(context: Context, mac: TrustedMac) = updateTrusted(context) {
        it.put(mac.id, JSONObject().put("name", mac.name).put("pub", b64(mac.publicKey)))
    }

    @Synchronized
    fun forget(context: Context, macId: String) {
        updateTrusted(context) { it.remove(macId) }
        val prefs = prefs(context)
        val addresses = JSONObject(prefs.getString(KEY_ADDRESSES, "{}") ?: "{}").apply { remove(macId) }
        prefs.edit().putString(KEY_ADDRESSES, addresses.toString()).apply()
    }

    /** Where a paired Mac was last reached, for reconnecting while Bonjour discovery is paused. */
    @Synchronized
    fun rememberAddress(context: Context, macId: String, hosts: List<InetAddress>, port: Int) {
        val prefs = prefs(context)
        val json = JSONObject(prefs.getString(KEY_ADDRESSES, "{}") ?: "{}")
        val entry = JSONObject().put("port", port).put("hosts", JSONArray(hosts.mapNotNull { it.hostAddress }))
        prefs.edit().putString(KEY_ADDRESSES, json.put(macId, entry).toString()).apply()
    }

    fun lastAddress(context: Context, macId: String): Pair<List<InetAddress>, Int>? {
        val entry = JSONObject(prefs(context).getString(KEY_ADDRESSES, "{}") ?: "{}").optJSONObject(macId) ?: return null
        val list = entry.optJSONArray("hosts") ?: return null
        val hosts = (0 until list.length()).mapNotNull { runCatching { InetAddress.getByName(list.getString(it)) }.getOrNull() }
        return if (hosts.isEmpty()) null else hosts to entry.optInt("port")
    }

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun updateTrusted(context: Context, change: (JSONObject) -> Unit) {
        val prefs = prefs(context)
        val json = JSONObject(prefs.getString(KEY_TRUSTED, "{}") ?: "{}").also(change)
        prefs.edit().putString(KEY_TRUSTED, json.toString()).apply()
    }

    /** The name the user gave the phone (e.g. "Xiaomi 13 Ultra"), shown on the Mac during pairing. */
    private fun phoneName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun newId(): String = DesktopCrypto.randomBytes(16).joinToString("") { "%02x".format(it) }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
