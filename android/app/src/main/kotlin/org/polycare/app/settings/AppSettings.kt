package org.polycare.app.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Small per-phone preferences: where to sync, and which village signals are reported for. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("polycare_settings", Context.MODE_PRIVATE)

    private val _gatewayUrl = MutableStateFlow(prefs.getString(KEY_GATEWAY, "").orEmpty())
    /** Base URL of the PolyCare gateway, e.g. `http://192.168.1.20:8080`. Blank = sync off. */
    val gatewayUrl: StateFlow<String> = _gatewayUrl.asStateFlow()

    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, "").orEmpty())
    val token: StateFlow<String> = _token.asStateFlow()

    private val _village = MutableStateFlow(prefs.getString(KEY_VILLAGE, "").orEmpty())
    /** Coarse location attached to de-identified signals. Never a street or a household. */
    val village: StateFlow<String> = _village.asStateFlow()

    private val _autoSync = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()

    fun setGatewayUrl(value: String) {
        val v = value.trim().trimEnd('/')
        prefs.edit().putString(KEY_GATEWAY, v).apply()
        _gatewayUrl.value = v
    }

    fun setToken(value: String) {
        prefs.edit().putString(KEY_TOKEN, value.trim()).apply()
        _token.value = value.trim()
    }

    fun setVillage(value: String) {
        prefs.edit().putString(KEY_VILLAGE, value.trim()).apply()
        _village.value = value.trim()
    }

    fun setAutoSync(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, value).apply()
        _autoSync.value = value
    }

    private companion object {
        const val KEY_GATEWAY = "gateway_url"
        const val KEY_TOKEN = "gateway_token"
        const val KEY_VILLAGE = "village"
        const val KEY_AUTO = "auto_sync"
    }
}
