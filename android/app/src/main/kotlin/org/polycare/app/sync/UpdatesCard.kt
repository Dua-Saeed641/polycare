package org.polycare.app.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.polycare.app.settings.AppSettings
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.LabelledField
import org.polycare.app.ui.components.PrimaryButton
import org.polycare.app.ui.components.SecondaryButton
import org.polycare.app.ui.components.bytesLabel
import org.polycare.app.ui.theme.Brand
import javax.inject.Inject

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val artifacts: ArtifactsRepository,
    private val settings: AppSettings,
) : ViewModel() {
    val status: StateFlow<UpdateStatus> = artifacts.status
    val publisherKey: StateFlow<String> = settings.publisherKey
    fun setPublisherKey(v: String) = settings.setPublisherKey(v)
    fun check() { viewModelScope.launch { artifacts.checkForUpdates() } }
    fun install(e: ArtifactEntry) { viewModelScope.launch { artifacts.install(e) } }
}

/** Signed skill and knowledge updates from the gateway. Nothing installs without the pinned publisher key. */
@Composable
fun UpdatesCard(viewModel: UpdatesViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val key by viewModel.publisherKey.collectAsStateWithLifecycle()

    GlassCard(Modifier.fillMaxWidth()) {
        LabelledField(
            "Publisher key", key,
            supporting = "The public key printed by tools/publish_artifact.py keygen. Updates not signed by it are refused.",
            onChange = viewModel::setPublisherKey,
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Check for updates", viewModel::check, icon = Icons.Outlined.Refresh, enabled = key.isNotBlank() && status !is UpdateStatus.Downloading, accent = Brand.Plum)
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (val s = status) {
                UpdateStatus.Idle -> Unit
                UpdateStatus.Checking -> Note("Checking…", Brand.InkMuted)
                is UpdateStatus.Failed -> Note(s.reason, Brand.Red)
                is UpdateStatus.Installed -> Note("Installed: ${s.label}", Brand.Positive)
                is UpdateStatus.Downloading -> {
                    Note("Downloading ${s.label}: ${bytesLabel(s.done)} of ${bytesLabel(s.total)}", Brand.Ink)
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Brand.Plum,
                    )
                }
                is UpdateStatus.Available -> {
                    if (s.entries.isEmpty()) Note("Up to date." + if (s.refused > 0) " ${s.refused} offered update(s) were refused (unsigned or not for this phone)." else "", Brand.InkMuted)
                    s.entries.forEach { e ->
                        Spacer(Modifier.height(12.dp))
                        Text(e.label, style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
                        Text(bytesLabel(e.size), style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
                        Spacer(Modifier.height(6.dp))
                        PrimaryButton("Download and install", { viewModel.install(e) }, icon = Icons.Outlined.CloudDownload, accent = Brand.Plum)
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color) {
    Spacer(Modifier.height(10.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}
