package org.polycare.app.device

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.polycare.app.settings.AppSettings
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.ToggleRow
import org.polycare.app.ui.theme.Brand
import javax.inject.Inject

@HiltViewModel
class ExperimentalViewModel @Inject constructor(private val settings: AppSettings) : ViewModel() {
    val useGpu: StateFlow<Boolean> = settings.useGpu
    fun setUseGpu(v: Boolean) = settings.setUseGpu(v)
}

/** Switches that are off by default because they have not been measured on real phones yet. */
@Composable
fun ExperimentalCard(viewModel: ExperimentalViewModel = hiltViewModel()) {
    val gpu by viewModel.useGpu.collectAsStateWithLifecycle()
    SectionLabel("Experimental")
    Spacer(Modifier.height(12.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        ToggleRow(
            "Use the GPU for the language model", gpu, viewModel::setUseGpu, accent = Brand.Plum,
            supporting = "Takes effect the next time the model loads (restart the app).",
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Only works in a build made with the Vulkan option (-PpolycareVulkan=true); otherwise it is ignored. " +
                "If the GPU can't load the model, the phone falls back to the CPU automatically.",
            style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted,
        )
    }
    Spacer(Modifier.height(24.dp))
}
