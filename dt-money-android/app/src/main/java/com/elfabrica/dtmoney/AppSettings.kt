package com.elfabrica.dtmoney

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("dt_money", Context.MODE_PRIVATE)

    val baseUrl: String get() = prefs.getString("api", null)
        ?: if (BuildConfig.DEBUG) "http://10.0.2.2:3001" else ""

    fun scope(session: Session): String = "${session.userId}@$baseUrl"

    fun captureConfig(): CaptureConfig? {
        val snapshot = prefs.all
        val scope = snapshot["scope"] as? String ?: return null
        val bank = snapshot["bank:$scope"] as? String ?: return null
        return CaptureConfig(scope, bank)
    }

    fun bank(scope: String): String = prefs.getString("bank:$scope", "").orEmpty()
    fun saveBank(scope: String, bank: String) {
        prefs.edit().putString("bank:$scope", bank).apply()
    }

    fun loadSession(): Session? = runCatching {
        val cipherText = prefs.getString("session", null) ?: return null
        val data = Base64.decode(cipherText, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        Session(json.getLong("id"), json.getString("name"), json.getString("token"))
    }.getOrElse { signOut(); null }

    fun saveSession(url: String, session: Session) {
        val json = JSONObject().put("id", session.userId).put("name", session.name).put("token", session.token)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(json.toString().toByteArray())
        prefs.edit().putString("api", url).putString("scope", "${session.userId}@$url")
            .putString("session", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun signOut() {
        prefs.edit().remove("session").remove("scope").apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "dt_money_session"
    }
}

internal data class CaptureConfig(val scope: String, val packageName: String)
