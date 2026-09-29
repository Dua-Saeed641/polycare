package org.polycare.app.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.polycare.app.knowledge.GapsRepository
import org.polycare.app.settings.AppSettings
import org.polycare.app.team.TeamAnswer
import org.polycare.app.team.TeamGuidanceRepository
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val sync: SyncRepository,
    private val settings: AppSettings,
    team: TeamGuidanceRepository,
    gaps: GapsRepository,
) : ViewModel() {
    val state: StateFlow<SyncState> = sync.state
    val pending: StateFlow<Int> = sync.pendingOps
    val gatewayUrl: StateFlow<String> = settings.gatewayUrl
    val token: StateFlow<String> = settings.token
    val village: StateFlow<String> = settings.village
    val autoSync: StateFlow<Boolean> = settings.autoSync
    val answers: StateFlow<List<TeamAnswer>> = team.answers
    val gaps = gaps.gaps

    private val _test = MutableStateFlow<String?>(null)
    /** Result line of the last "Test connection", or null. */
    val testResult: StateFlow<String?> = _test.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    fun setGatewayUrl(v: String) = settings.setGatewayUrl(v)
    fun setToken(v: String) = settings.setToken(v)
    fun setVillage(v: String) = settings.setVillage(v)
    fun setAutoSync(v: Boolean) = settings.setAutoSync(v)

    fun syncNow() {
        viewModelScope.launch { sync.syncNow() }
    }

    fun testConnection() {
        _testing.value = true
        _test.value = null
        viewModelScope.launch {
            val r = sync.testConnection()
            _test.value = r.fold(onSuccess = { "Connected: $it" }, onFailure = { "Could not connect: ${it.message ?: it.javaClass.simpleName}" })
            _testing.value = false
        }
    }
}
