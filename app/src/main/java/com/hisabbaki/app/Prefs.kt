package com.hisabbaki.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Prefs {
    private const val FILE = "hisab_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_BUSINESS = "current_business_id"
    private const val KEY_LOCK_ENABLED = "lock_enabled"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_LEN = "pin_len"
    private const val KEY_BIO_UNLOCK = "bio_unlock"
    private const val KEY_LAST_ACTIVE = "last_active_ms"
    private const val KEY_ALLOW_NEGATIVE = "allow_negative_stock"
    private const val KEY_AUTO_BACKUP = "auto_backup_enabled"
    private const val KEY_LAST_AUTO_BACKUP = "last_auto_backup_ms"
    private const val KEY_AI_BASE_URL = "ai_base_url"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_AI_KEY = "ai_api_key"

    private fun pref(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ theme

    fun getThemeMode(ctx: Context): Int =
        pref(ctx).getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setThemeMode(ctx: Context, mode: Int) {
        pref(ctx).edit().putInt(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    // ------------------------------------------------------------ business switch

    fun currentBusinessId(ctx: Context): Int = pref(ctx).getInt(KEY_BUSINESS, 1)

    fun setCurrentBusinessId(ctx: Context, id: Int) {
        pref(ctx).edit().putInt(KEY_BUSINESS, id).apply()
    }

    // ------------------------------------------------------------ app lock

    fun isLockEnabled(ctx: Context): Boolean = pref(ctx).getBoolean(KEY_LOCK_ENABLED, false)

    fun setLockEnabled(ctx: Context, enabled: Boolean) {
        pref(ctx).edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
    }

    /**
     * Stores only a salted PBKDF2 hash, never the PIN itself.
     * Returns true when a hash is now set.
     */
    fun setPin(ctx: Context, pin: String): Boolean {
        if (pin.length < 4 || pin.length > 8) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        pref(ctx).edit()
            .putString(KEY_PIN_SALT, salt.toHex())
            .putString(KEY_PIN_HASH, hash.toHex())
            .putInt(KEY_PIN_LEN, pin.length)
            .putBoolean(KEY_LOCK_ENABLED, true)
            .apply()
        return true
    }

    fun checkPin(ctx: Context, pin: String): Boolean {
        val saltHex = pref(ctx).getString(KEY_PIN_SALT, null) ?: return false
        val stored = pref(ctx).getString(KEY_PIN_HASH, null) ?: return false
        val test = pbkdf2(pin, saltHex.fromHex())
        return constantTimeEquals(test, stored.fromHex())
    }

    fun clearPin(ctx: Context) {
        pref(ctx).edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_LEN)
            .putBoolean(KEY_LOCK_ENABLED, false)
            .putBoolean(KEY_BIO_UNLOCK, false)
            .apply()
    }

    fun pinLength(ctx: Context): Int = pref(ctx).getInt(KEY_PIN_LEN, 4)

    fun isBioUnlock(ctx: Context): Boolean = pref(ctx).getBoolean(KEY_BIO_UNLOCK, false)

    fun setBioUnlock(ctx: Context, enabled: Boolean) {
        pref(ctx).edit().putBoolean(KEY_BIO_UNLOCK, enabled).apply()
    }

    fun hasPin(ctx: Context): Boolean = pref(ctx).getString(KEY_PIN_HASH, null) != null

    /** Lock screen shows again when the app returns to the foreground after this pause. */
    fun lastActiveMs(ctx: Context): Long = pref(ctx).getLong(KEY_LAST_ACTIVE, 0L)

    fun setLastActiveMs(ctx: Context, ms: Long) {
        pref(ctx).edit().putLong(KEY_LAST_ACTIVE, ms).apply()
    }

    // ------------------------------------------------------------ inventory setting

    fun isAllowNegativeStock(ctx: Context): Boolean = pref(ctx).getBoolean(KEY_ALLOW_NEGATIVE, false)

    fun setAllowNegativeStock(ctx: Context, allowed: Boolean) {
        pref(ctx).edit().putBoolean(KEY_ALLOW_NEGATIVE, allowed).apply()
    }

    // ------------------------------------------------------------ automatic backup

    fun isAutoBackupEnabled(ctx: Context): Boolean = pref(ctx).getBoolean(KEY_AUTO_BACKUP, false)

    fun setAutoBackupEnabled(ctx: Context, enabled: Boolean) {
        pref(ctx).edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply()
    }

    fun lastAutoBackupMs(ctx: Context): Long = pref(ctx).getLong(KEY_LAST_AUTO_BACKUP, 0L)

    fun setLastAutoBackupMs(ctx: Context, ms: Long) {
        pref(ctx).edit().putLong(KEY_LAST_AUTO_BACKUP, ms).apply()
    }

    // ------------------------------------------------------------ optional AI API

    fun aiBaseUrl(ctx: Context): String = pref(ctx).getString(KEY_AI_BASE_URL, "") ?: ""
    fun aiModel(ctx: Context): String = pref(ctx).getString(KEY_AI_MODEL, "") ?: ""
    fun aiApiKey(ctx: Context): String = pref(ctx).getString(KEY_AI_KEY, "") ?: ""

    fun setAiConfig(ctx: Context, baseUrl: String, model: String, apiKey: String) {
        pref(ctx).edit()
            .putString(KEY_AI_BASE_URL, baseUrl.trim())
            .putString(KEY_AI_MODEL, model.trim())
            .putString(KEY_AI_KEY, apiKey.trim())
            .apply()
    }

    fun clearAiConfig(ctx: Context) {
        pref(ctx).edit().remove(KEY_AI_BASE_URL).remove(KEY_AI_MODEL).remove(KEY_AI_KEY).apply()
    }

    // ------------------------------------------------------------ crypto helpers

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 30000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { String.format("%02x", it) }

    private fun String.fromHex(): ByteArray =
        ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
