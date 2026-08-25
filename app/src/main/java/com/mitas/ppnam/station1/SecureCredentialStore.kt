package com.mitas.ppnam.station1

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the provisioned MQTT broker password at rest with an Android Keystore AES-256-GCM key,
 * per the Schema 4.1 handoff §5. Ported from Station 2's SecureCredentialStore (minus Hilt) so
 * both apps meet the handoff the same way.
 *
 * The handoff's requirements, and where each is met:
 *
 *  - non-exportable key under an app-specific alias in `AndroidKeystore` — [getOrCreateKey];
 *  - a fresh random GCM IV for each encryption — [encrypt] lets the provider generate it, which is
 *    mandatory here: GCM catastrophically loses confidentiality AND integrity if an IV is ever
 *    reused under the same key, and `AndroidKeyStore` actively rejects a caller-supplied IV for
 *    exactly that reason;
 *  - ciphertext and IV in app-private storage — [prefs], which is `MODE_PRIVATE`;
 *  - excluded from Android backup — see `data_extraction_rules.xml` / `backup_rules.xml`, which
 *    exclude this preferences file by name;
 *  - rotation — [store] simply overwrites, and [clear] deletes both key and ciphertext for a
 *    decommissioned handheld.
 *
 * StrongBox is requested where the device advertises it and silently skipped where it does not:
 * the handoff asks to "prefer StrongBox without making unsupported hardware a runtime failure",
 * and plenty of handhelds have no secure element.
 */
class SecureCredentialStore(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "SecureCredentialStore"
        private const val KEY_ALIAS = "ppnam_station1_mqtt_credential"
        private const val PREFS_NAME = "ppnam_secure_credentials"
        private const val KEY_CIPHERTEXT = "mqtt_password_ct"
        private const val KEY_IV = "mqtt_password_iv"
        private const val KEY_HARDWARE_BACKED = "mqtt_password_hw"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"
    }

    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** True when a broker password has been provisioned on this device. */
    fun hasCredential(): Boolean =
        prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    /** Whether the key protecting the credential lives in hardware. Recorded for the sign-off. */
    fun isHardwareBacked(): Boolean = prefs.getBoolean(KEY_HARDWARE_BACKED, false)

    /**
     * Encrypts and stores [password], replacing any previous value (this is also key rotation).
     * Returns false if the platform refused — the caller must not fall back to storing plaintext.
     */
    fun store(password: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, encode(ciphertext))
            // The provider generated this IV; storing it alongside is correct and not a secret.
            .putString(KEY_IV, encode(cipher.iv))
            .putBoolean(KEY_HARDWARE_BACKED, keyIsHardwareBacked())
            .apply()
        true
    } catch (e: Exception) {
        // No password in the message — an exception from the crypto layer must not become the
        // one place the credential leaks into a log.
        Log.e(TAG, "Could not store the broker credential securely", e)
        false
    }

    /**
     * Decrypts the stored password, or null when none is provisioned or the ciphertext can no
     * longer be read.
     *
     * The latter is a real case, not a theoretical one: the Keystore key is invalidated by a
     * factory reset, an app data wipe, or (on some devices) a lock-screen credential change, after
     * which the stored bytes are permanently undecryptable. Returning null makes the app ask for
     * re-provisioning, which is the only recovery.
     */
    fun retrieve(): String? {
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return try {
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, decode(iv)))
            }
            String(cipher.doFinal(decode(ciphertext)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Stored broker credential could not be decrypted — re-provisioning required", e)
            null
        }
    }

    /** Deletes the credential and its key. For decommissioning a handheld. */
    fun clear() {
        prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).remove(KEY_HARDWARE_BACKED).apply()
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete the credential key", e)
        }
    }

    private fun existingKey(): SecretKey? = try {
        KeyStore.getInstance(KEYSTORE)
            .apply { load(null) }
            .let { it.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry }
            ?.secretKey
    } catch (e: Exception) {
        Log.w(TAG, "Could not load the credential key", e)
        null
    }

    private fun getOrCreateKey(): SecretKey = existingKey() ?: createKey()

    private fun createKey(): SecretKey {
        // StrongBox first where the device claims it; fall back to the TEE-backed key otherwise.
        // StrongBoxUnavailableException is only thrown from API 28, hence the version guard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
        ) {
            try {
                return generateKey(useStrongBox = true)
            } catch (e: Exception) {
                Log.i(TAG, "StrongBox unavailable for the credential key; using the standard keystore")
            }
        }
        return generateKey(useStrongBox = false)
    }

    private fun generateKey(useStrongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Deliberately NOT setUserAuthenticationRequired: the handheld reconnects to the
            // broker unattended (background reconnect, boot), and gating the broker password on a
            // screen unlock would break that. The operator secret is SCRAM, not this.
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun keyIsHardwareBacked(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
        ) true else Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    } catch (e: Exception) {
        false
    }

    // NO_WRAP: a newline inside a preference value would survive a round trip and corrupt decode.
    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
