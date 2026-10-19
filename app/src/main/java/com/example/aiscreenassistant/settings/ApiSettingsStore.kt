package com.example.aiscreenassistant.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class ApiSettings(val baseUrl: String = "", val token: String = "", val selectedModel: String = "")

class ApiSettingsStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "api_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun read() = ApiSettings(prefs.getString("base_url", "") ?: "", prefs.getString("token", "") ?: "", prefs.getString("selected_model", "") ?: "")
    fun save(settings: ApiSettings) { prefs.edit().putString("base_url", settings.baseUrl.trim()).putString("token", settings.token).putString("selected_model", settings.selectedModel).apply() }
    fun clear() { prefs.edit().clear().apply() }
}
