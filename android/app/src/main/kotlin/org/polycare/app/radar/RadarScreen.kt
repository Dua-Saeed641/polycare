package org.polycare.app.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.agoLabel
import org.polycare.app.ui.theme.Brand
import org.polycare.common.PolyCareConfig
import org.polycare.common.radar.AlertLevel
import org.polycare.common.radar.RadarSignal
import javax.inject.Inject

@HiltViewModel
class RadarViewModel @Inject constructor(private val repo: SignalsRepository) : ViewModel() {
    val items: StateFlow<List<RadarItem>> = repo.items
    val signals: StateFlow<List<RadarSignal>> = repo.signals
    fun refresh() = repo.refresh()
}

private val Accent = Brand.Rose

@Composable
fun RadarScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: RadarViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val signals by viewModel.signals.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader("Outbreak Radar", Accent, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        Text("Early warning when similar illness appears in several villages.", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Built from danger signs marked in Triage, with no names and no households. " +
                "An alert needs ${PolyCareConfig.Radar.minSignals}+ similar cases from ${PolyCareConfig.Radar.minVillages}+ villages in the last 7 days.",
            style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted,
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("Alerts")
        Spacer(Modifier.height(10.dp))
        if (items.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Radar, contentDescription = null, tint = Brand.Positive, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("No clusters detected", style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
                        Text(
                            if (signals.isEmpty()) "Log cases from Triage to start building the picture."
                            else "${signals.size} signal${if (signals.size == 1) "" else "s"} recorded on this phone, none forming a cluster.",
                            style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted,
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { items.forEach { AlertCard(it) } }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("This phone's signals")
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            if (signals.isEmpty()) {
                Text("No signals yet.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted, modifier = Modifier.padding(16.dp))
            } else {
                signals.take(10).forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label.ifBlank { "Danger signs" }, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink, maxLines = 2)
                            Text("${s.village} · ${agoLabel(s.wallMs)}", style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AlertCard(item: RadarItem) {
    val a = item.alert
    val isAlert = a.level == AlertLevel.ALERT
    val color = if (isAlert) Brand.Red else Brand.Magenta
    val summary = "${a.level.label}: ${a.label}. ${a.signalCount} cases in ${a.villages.size} villages: ${a.villages.joinToString()}."
    GlassCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = summary }, accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(CircleShape).background(color).padding(horizontal = 12.dp, vertical = 5.dp),
            ) { Text(a.level.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brand.Paper) }
            Spacer(Modifier.width(10.dp))
            Text(
                if (item.source == AlertSource.CLOUD) "From all villages" else "From this phone",
                style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(a.label, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
        Spacer(Modifier.height(8.dp))
        MetricRow("Cases", "${a.signalCount}")
        Hairline()
        MetricRow("Villages", a.villages.joinToString().ifBlank { "—" })
        Hairline()
        MetricRow("Latest", agoLabel(a.lastMs))
        if (isAlert) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Tell your ANM or supervisor. Keep checking for the danger signs above at every visit.",
                style = MaterialTheme.typography.bodySmall, color = Brand.Ink,
            )
        }
    }
}
