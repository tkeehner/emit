package com.example.emit.data

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * 32 random bytes created on first use and kept in a private prefs file.
 * Not part of the PC link payload. allowBackup is false, so a reinstall or
 * "clear data" mints a new secret and therefore new badges.
 */
object BadgeSecret {
    private const val FILE = "badge_secret"
    fun bytes(c: Context): ByteArray {
        val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        p.getString("k", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        p.edit().putString("k", Base64.encodeToString(fresh, Base64.NO_WRAP)).apply()
        return fresh
    }
}
