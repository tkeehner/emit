package com.example.emit.link

import android.content.Context
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional app lock (OFF by default). Unlock with biometrics OR the device PIN/pattern (always allowed, so the user
 * can't be locked out) OR the app PIN. No attempt limits, never wipes anything.
 */
object AppLock {
    const val RELOCK_MS = 60_000L
    private fun p(c: Context) = c.getSharedPreferences("app_lock", 0)
    fun enabled(c: Context) = p(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("on", on).apply()
    fun hasPin(c: Context) = p(c).contains("pin_hash")

    /** Pure: PIN rules (4-12 digits). */
    fun validPin(pin: String) = pin.length in 4..12 && pin.all { it.isDigit() }
    /** Pure: PBKDF2-SHA256 hash. */
    fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)).encoded

    fun setPin(c: Context, pin: String): Boolean {
        if (!validPin(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p(c).edit().putString("pin_salt", android.util.Base64.encodeToString(salt, 2)).putString("pin_hash", android.util.Base64.encodeToString(hash(pin, salt), 2)).apply()
        return true
    }
    fun clearPin(c: Context) = p(c).edit().remove("pin_salt").remove("pin_hash").apply()
    fun checkPin(c: Context, pin: String): Boolean {
        val salt = p(c).getString("pin_salt", null)?.let { android.util.Base64.decode(it, 2) } ?: return false
        val want = p(c).getString("pin_hash", null)?.let { android.util.Base64.decode(it, 2) } ?: return false
        return java.security.MessageDigest.isEqual(hash(pin, salt), want)
    }
    /** Pure: lock again after being in the background longer than RELOCK_MS. */
    fun shouldLock(enabled: Boolean, unlockedAt: Long, backgroundedAt: Long, now: Long): Boolean =
        enabled && (unlockedAt == 0L || (backgroundedAt > 0 && now - backgroundedAt > RELOCK_MS))
}
