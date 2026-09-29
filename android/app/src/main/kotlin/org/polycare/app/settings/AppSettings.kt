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

    private val _enrollmentToken = MutableStateFlow(prefs.getString(KEY_ENROLLMENT, "").orEmpty())
    /** Shared secret that lets this phone register its key with the gateway (`X-Enrollment-Token`). */
    val enrollmentToken: StateFlow<String> = _enrollmentToken.asStateFlow()

    private val _publisherKey = MutableStateFlow(prefs.getString(KEY_PUBLISHER, "").orEmpty())
    /** Base64 Ed25519 public key updates must be signed by. Blank = updates are refused. */
    val publisherKey: StateFlow<String> = _publisherKey.asStateFlow()

    private val _useGpu = MutableStateFlow(prefs.getBoolean(KEY_GPU, false))
    /** Ask llama.cpp to offload layers to the GPU (only has an effect in a build made with -PpolycareVulkan=true). */
    val useGpu: StateFlow<Boolean> = _useGpu.asStateFlow()

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

    fun setEnrollmentToken(value: String) {
        prefs.edit().putString(KEY_ENROLLMENT, value.trim()).apply()
        _enrollmentToken.value = value.trim()
    }

    fun setPublisherKey(value: String) {
        prefs.edit().putString(KEY_PUBLISHER, value.trim()).apply()
        _publisherKey.value = value.trim()
    }

    fun setUseGpu(value: Boolean) {
        prefs.edit().putBoolean(KEY_GPU, value).apply()
        _useGpu.value = value
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
        const val KEY_ENROLLMENT = "gateway_enrollment_token"
        const val KEY_PUBLISHER = "artifact_publisher_key"
        const val KEY_GPU = "use_gpu"
        const val KEY_VILLAGE = "village"
        const val KEY_AUTO = "auto_sync"
    }
}
