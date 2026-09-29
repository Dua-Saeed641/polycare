package org.polycare.app.sync

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.LabelledField
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.PrimaryButton
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.SecondaryButton
import org.polycare.app.ui.components.StatusPill
import org.polycare.app.ui.components.ToggleRow
import org.polycare.app.ui.components.agoLabel
import org.polycare.app.ui.components.bytesLabel
import org.polycare.app.ui.theme.Brand

private val Accent = Brand.Plum

@Composable
fun SyncScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val url by viewModel.gatewayUrl.collectAsStateWithLifecycle()
    val token by viewModel.token.collectAsStateWithLifecycle()
    val village by viewModel.village.collectAsStateWithLifecycle()
    val auto by viewModel.autoSync.collectAsStateWithLifecycle()
    val answers by viewModel.answers.collectAsStateWithLifecycle()
    val gaps by viewModel.gaps.collectAsStateWithLifecycle()
    val test by viewModel.testResult.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()

    val running = state is SyncState.Running
    val configured = url.isNotBlank()

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader("Sync", Accent, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        Text("Share what helps the team. Keep families private.", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)

        Spacer(Modifier.height(20.dp))
        GlassCard(Modifier.fillMaxWidth(), accent = Accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(headline(state, configured), style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
                    Text(subline(state, pending, configured), style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (running) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Syncing…", style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
                }
            } else {
                PrimaryButton(
                    if (configured) "Sync now" else "Set the gateway address below",
                    onClick = viewModel::syncNow, enabled = configured, icon = Icons.Outlined.CloudSync, accent = Accent,
                )
            }
            (state as? SyncState.Failed)?.let {
                Spacer(Modifier.height(10.dp))
                Text(it.reason, style = MaterialTheme.typography.bodySmall, color = Brand.Red)
            }
        }

        (state as? SyncState.Done)?.let { done ->
            Spacer(Modifier.height(24.dp))
            SectionLabel("Last sync · ${agoLabel(done.atMs)}")
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                MetricRow("Sent to the team", "${done.stats.pushedOps} items")
                Hairline()
                MetricRow("Kept on this phone", "${done.stats.keptLocalOps} records", valueColor = Brand.Positive)
                Hairline()
                MetricRow("Sent as “+1” only", "${done.stats.plusOnes}")
                Hairline()
                MetricRow("Data sent", bytesLabel(done.stats.bytesSent))
                Hairline()
                MetricRow("Data not sent", bytesLabel(done.stats.bytesNotSent), valueColor = Brand.Positive)
                if (done.stats.answersReceived > 0 || done.stats.alertsReceived > 0) {
                    Hairline()
                    MetricRow("Received", "${done.stats.answersReceived} answers · ${done.stats.alertsReceived} alerts")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Stays on this phone")
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = Brand.Positive, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Households, members, visits and notes", style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "These are encrypted on the phone and never sent. Only de-identified symptom signals for the Outbreak Radar, " +
                            "and questions you couldn't get answered, can sync.",
                        style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted,
                    )
                }
            }
        }

        if (gaps.isNotEmpty() || answers.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Questions and answers")
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                MetricRow("Waiting for a supervisor", "${gaps.size}")
                Hairline()
                MetricRow("Answers received", "${answers.size}", valueColor = Brand.Positive)
                answers.take(3).forEach { a ->
                    Hairline()
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Text(a.question, style = MaterialTheme.typography.labelLarge, color = Brand.InkMuted)
                        Spacer(Modifier.height(2.dp))
                        Text(a.answer, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Connection")
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            LabelledField(
                "Gateway address", url, keyboardType = KeyboardType.Uri,
                supporting = "For example http://192.168.1.20:8080", onChange = viewModel::setGatewayUrl,
            )
            Spacer(Modifier.height(12.dp))
            LabelledField("Access token (optional)", token, imeAction = ImeAction.Next, onChange = viewModel::setToken)
            Spacer(Modifier.height(12.dp))
            LabelledField(
                "Your village", village, imeAction = ImeAction.Done,
                supporting = "Attached to symptom signals. Never a street or a household.", onChange = viewModel::setVillage,
            )
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                "Sync automatically when the connection is steady", auto, viewModel::setAutoSync, accent = Accent,
                supporting = "Waits 30 seconds of steady signal before starting.",
            )
            Spacer(Modifier.height(8.dp))
            if (testing) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                }
            } else {
                SecondaryButton("Test connection", viewModel::testConnection, enabled = configured, icon = Icons.Outlined.NetworkCheck, accent = Accent)
            }
            test?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Connected")) Brand.Positive else Brand.Red,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun headline(state: SyncState, configured: Boolean): String = when {
    !configured -> "Sync is off"
    state is SyncState.Running -> "Syncing…"
    state is SyncState.Failed -> "Last sync didn't finish"
    state is SyncState.Done -> "Up to date · ${agoLabel(state.atMs)}"
    else -> "Ready to sync"
}

private fun subline(state: SyncState, pending: Int, configured: Boolean): String = when {
    !configured -> "Everything works offline. Add a gateway to share signals and get supervisor answers."
    state is SyncState.Failed -> "Nothing was lost. It will try again when the connection is steady."
    pending == 0 -> "Nothing waiting."
    else -> "$pending change${if (pending == 1) "" else "s"} waiting on this phone."
}
