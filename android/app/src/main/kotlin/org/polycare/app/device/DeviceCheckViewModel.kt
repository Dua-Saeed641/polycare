package org.polycare.app.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.polycare.governor.DegradationLadder
import org.polycare.governor.DeviceProbe
import org.polycare.governor.DeviceSnapshot
import org.polycare.governor.Rung
import javax.inject.Inject

data class DeviceCheckState(
    val snapshot: DeviceSnapshot,
    val rung: Rung,
    val arm64: Boolean,
    val fitsKnowledgeSlice: Boolean,
)

@HiltViewModel
class DeviceCheckViewModel @Inject constructor(probe: DeviceProbe) : ViewModel() {

    val state: StateFlow<DeviceCheckState?> = flow {
        while (true) {
            val s = probe.snapshot()
            emit(
                DeviceCheckState(
                    snapshot = s,
                    rung = DegradationLadder.choose(s),
                    arm64 = DegradationLadder.supportsArm64(s),
                    fitsKnowledgeSlice = DegradationLadder.canHoldFullKnowledgeSlice(s),
                ),
            )
            delay(REFRESH_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private companion object {
        const val REFRESH_MS = 2_000L
    }
}
