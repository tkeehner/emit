package com.example.emit.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LinkedPc(val pcId: String, val name: String, val pub: ByteArray, val pairedAt: Long, val lastSeen: Long)

/** Phone identity (X25519 secret wrapped by an Android Keystore AES-GCM key) + paired PCs + toggles. */
class LinkStore(context: Context) {
    private val p = context.getSharedPreferences("link_pc", Context.MODE_PRIVATE)

    var enabled: Boolean get() = p.getBoolean("enabled", false); set(v) { p.edit().putBoolean("enabled", v).apply() }
    /** When false, sends can only be approved on the phone itself. */
    var allowPcApproval: Boolean get() = p.getBoolean("pc_approval", true); set(v) { p.edit().putBoolean("pc_approval", v).apply() }

    // --- AWS relay (pushed from the PC over the encrypted LAN link, or typed on the Link PC screen) ---
    var relayEnabled: Boolean get() = p.getBoolean("relay_on", false); set(v) { p.edit().putBoolean("relay_on", v).apply() }
    var relayHost: String get() = p.getString("relay_host", "") ?: ""; set(v) { p.edit().putString("relay_host", v.trim()).apply() }
    var relayPort: Int get() = p.getInt("relay_port", 443); set(v) { p.edit().putInt("relay_port", v).apply() }
    var relayFingerprint: String get() = p.getString("relay_fp", "") ?: ""; set(v) { p.edit().putString("relay_fp", RelayWire.normFingerprint(v)).apply() }
    var relayToken: String get() = p.getString("relay_token", "") ?: ""; set(v) { p.edit().putString("relay_token", v.trim()).apply() }
    val relayConfigured: Boolean get() = relayHost.isNotBlank() && relayToken.length >= 16 && RelayWire.validFingerprint(relayFingerprint)
    /** Network fingerprints (see RelayPolicy) where a PC connected over the LAN -> last time. */
    fun homes(): Map<String, Long> = JSONObject(p.getString("homes", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
    @Synchronized fun markHome(fp: String) {
        val now = System.currentTimeMillis()
        val m = homes().filterValues { now - it < RelayPolicy.HOME_TTL_MS } + (fp to now)
        p.edit().putString("homes", JSONObject(m.entries.sortedByDescending { it.value }.take(8).associate { it.key to it.value }).toString()).apply()
    }

    val phoneId: String get() = p.getString("phone_id", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        .also { p.edit().putString("phone_id", it).apply() }

    @Volatile private var sk: ByteArray? = null
    @Synchronized fun secretKey(): ByteArray {
        sk?.let { return it }
        val wrapped = p.getString("sk", null)
        val key = if (wrapped != null) unwrap(unb64(wrapped)) else LinkCrypto.genSecret().also { p.edit().putString("sk", b64(wrap(it))).apply() }
        sk = key; return key
    }

    fun pcs(): List<LinkedPc> {
        val a = JSONArray(p.getString("pcs", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            LinkedPc(it.getString("id"), it.getString("name"), unb64(it.getString("pub")), it.optLong("paired"), it.optLong("seen")) }
    }
    private fun savePcs(l: List<LinkedPc>) = p.edit().putString("pcs", JSONArray(l.map {
        JSONObject().put("id", it.pcId).put("name", it.name).put("pub", b64(it.pub)).put("paired", it.pairedAt).put("seen", it.lastSeen) }).toString()).apply()
    @Synchronized fun addPc(id: String, name: String, pub: ByteArray) = savePcs(pcs().filter { it.pcId != id } + LinkedPc(id, name, pub, System.currentTimeMillis(), 0))
    @Synchronized fun revoke(id: String) = savePcs(pcs().filter { it.pcId != id })
    @Synchronized fun touch(id: String) = savePcs(pcs().map { if (it.pcId == id) it.copy(lastSeen = System.currentTimeMillis()) else it })
    fun pubFor(id: String): ByteArray? = pcs().firstOrNull { it.pcId == id }?.pub

    private fun ksKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun wrap(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, ksKey())
        return c.iv + c.doFinal(b)
    }
    private fun unwrap(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, ksKey(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return c.doFinal(b, 12, b.size - 12)
    }
    companion object { private const val ALIAS = "matrixsms_link_v1" }
}
