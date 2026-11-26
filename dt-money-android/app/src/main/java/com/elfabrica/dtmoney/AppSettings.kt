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
import org.json.JSONArray
import java.time.Instant

internal class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("dt_money", Context.MODE_PRIVATE)

    val baseUrl: String get() = prefs.getString("api", null)
        ?: if (BuildConfig.DEBUG) "http://10.0.2.2:3001" else ""

    fun scope(session: Session): String = "${session.userId}@$baseUrl"

    fun captureConfig(): CaptureConfig? = synchronized(lock) {
        val snapshot = prefs.all
        val scope = snapshot["scope"] as? String ?: return@synchronized null
        val banks = if (BuildConfig.DEBUG) Bank.entries.mapNotNull { bank ->
            (snapshot["enabled:$scope:" + bank.name] as? Long)?.let { bank to Instant.ofEpochMilli(it) }
        }.toMap() else emptyMap()
        CaptureConfig(scope, banks, snapshot["bank:$scope"] as? String ?: "")
    }

    fun withCapture(config: CaptureConfig, block: () -> Unit) = synchronized(lock) {
        if (captureConfig() == config) block()
    }

    fun setBankEnabled(scope: String, bank: Bank, enabled: Boolean) = synchronized(lock) {
        if (!BuildConfig.DEBUG || prefs.getString("scope", null) != scope) return@synchronized
        val key = "enabled:$scope:" + bank.name
        val editor = prefs.edit()
        if (enabled && !prefs.contains(key)) editor.putLong(key, System.currentTimeMillis())
        if (!enabled) editor.remove(key)
        editor.apply()
    }
    fun bank(scope: String): String = prefs.getString("bank:$scope", "").orEmpty()
    fun saveBank(scope: String, bank: String) = synchronized(lock) {
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

    fun saveSession(url: String, session: Session) = synchronized(lock) {
        val json = JSONObject().put("id", session.userId).put("name", session.name).put("token", session.token)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(json.toString().toByteArray())
        prefs.edit().putString("api", url).putString("scope", "${session.userId}@$url")
            .putString("session", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun signOut() = synchronized(lock) {
        prefs.edit().remove("session").remove("scope").apply()
    }

    fun credentials(scope: String): Pair<String, Session>? = synchronized(lock) {
        if (prefs.getString("scope", null) != scope) return@synchronized null
        val session = loadSession() ?: return@synchronized null
        baseUrl to session
    }

    fun categories(scope: String): List<Category> = runCatching {
        val values = JSONArray(prefs.getString("categories:$scope", "[]"))
        List(values.length()) { index ->
            val category = values.getJSONObject(index)
            Category(category.getInt("id"), category.getString("name"))
        }
    }.getOrDefault(emptyList())

    fun saveCategories(scope: String, categories: List<Category>) {
        val values = JSONArray()
        categories.forEach { values.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString("categories:$scope", values.toString()).apply()
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
        private val lock = Any()
    }
}

internal data class CaptureConfig(val scope: String, val enabledSince: Map<Bank, Instant>, val legacyPackage: String) {
    fun accepts(bank: Bank, postedAt: Instant): Boolean =
        enabledSince[bank]?.let { !postedAt.isBefore(it) } ?: false
}
