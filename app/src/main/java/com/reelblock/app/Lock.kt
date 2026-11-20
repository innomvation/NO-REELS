package com.reelblock.app

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Self-protection lock, unlocked only with a password held by someone else (the "manager").
 *
 * Only a salted PBKDF2 hash of the password is stored; the password itself is generated on the PC
 * and sent straight to the manager, so the phone's owner never sees it. A hash can be installed
 * (via [setPasswordHash]) only while unlocked, so changing it needs the current password first.
 */
object Lock {
    private const val KEY_SALT = "lock_salt"
    private const val KEY_HASH = "lock_hash"
    private const val KEY_UNLOCKED = "lock_unlocked"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    fun hasPassword(context: Context): Boolean = prefs(context).contains(KEY_HASH)

    /** Locked = a password exists and the manager hasn't unlocked it. */
    fun isLocked(context: Context): Boolean =
        hasPassword(context) && !prefs(context).getBoolean(KEY_UNLOCKED, false)

    /** Installs a password hash (Base64 salt + Base64 PBKDF2 hash) and locks. Refused while locked. */
    fun setPasswordHash(context: Context, saltB64: String, hashB64: String): Boolean {
        if (isLocked(context)) return false
        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val hash = runCatching { Base64.decode(hashB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        if (salt.size < 16 || hash.size != KEY_BITS / 8) return false
        prefs(context).edit()
            .putString(KEY_SALT, saltB64)
            .putString(KEY_HASH, hashB64)
            .putBoolean(KEY_UNLOCKED, false)
            .apply()
        return true
    }

    /** Unlocks if [password] matches. */
    fun unlock(context: Context, password: String): Boolean {
        val p = prefs(context)
        val salt = Base64.decode(p.getString(KEY_SALT, null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(p.getString(KEY_HASH, null) ?: return false, Base64.NO_WRAP)
        val actual = pbkdf2(normalize(password), salt)
        if (!MessageDigest.isEqual(actual, expected)) return false
        p.edit().putBoolean(KEY_UNLOCKED, true).apply()
        return true
    }

    /** Locks again with the same password. */
    fun relock(context: Context) {
        if (hasPassword(context)) prefs(context).edit().putBoolean(KEY_UNLOCKED, false).apply()
    }

    /** Typed passwords may differ in case or include the dashes shown to the manager. */
    private fun normalize(password: String) = password.trim().lowercase().replace("-", "").replace(" ", "")

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
