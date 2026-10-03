package org.polycare.app.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.log.ActivityCard
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.PrimaryButton
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.theme.Brand
import org.polycare.governor.Rung

@Composable
fun DeviceCheckScreen(
    contentPadding: PaddingValues,
    autoBenchPoints: Int? = null,
    onMenu: () -> Unit = {},
    viewModel: DeviceCheckViewModel = hiltViewModel(),
    benchViewModel: VectorBenchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bench by benchViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(autoBenchPoints) { autoBenchPoints?.let { benchViewModel.run(it) } }
    val current = state

    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Brand.Plum, strokeWidth = 2.dp)
        }
        return
    }
    val s = current.snapshot

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader("System", Brand.Plum, onMenu = onMenu)
        Spacer(Modifier.height(12.dp))
        Text("How this phone runs PolyCare", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)

        Spacer(Modifier.height(28.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("Current mode")
            Spacer(Modifier.height(8.dp))
            Text(current.rung.label, style = MaterialTheme.typography.headlineLarge, color = Brand.Plum)
            Spacer(Modifier.height(4.dp))
            Text(current.rung.description, style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
            Spacer(Modifier.height(20.dp))
            RungLadder(current.rung)
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Hardware")
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            // Device model strings are long (and longer in some locales); stack to avoid overflow.
            MetricRow("Phone", s.model, stacked = true)
            Hairline()
            MetricRow("Android API", s.sdkInt.toString())
            Hairline()
            MetricRow("CPU cores", s.cpuCores.toString())
            Hairline()
            MetricRow(
                "64-bit ARM",
                if (current.arm64) "Supported" else "Not supported",
                valueColor = if (current.arm64) Brand.Positive else Brand.Red,
            )
            Hairline()
            MetricRow("Memory", "${s.availableRamMb.gb()} free of ${s.totalRamMb.gb()}")
            Hairline()
            MetricRow("Storage free", s.freeStorageMb.gb())
            Hairline()
            MetricRow(
                "1 M-point knowledge slice",
                if (current.fitsKnowledgeSlice) "Fits" else "Needs a smaller slice",
                valueColor = if (current.fitsKnowledgeSlice) Brand.Positive else Brand.RoseInk,
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Vector engine")
        Spacer(Modifier.height(12.dp))
        VectorEngineCard(bench, onRun = benchViewModel::run)

        Spacer(Modifier.height(24.dp))
        SectionLabel("Right now")
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            MetricRow("Battery", "${s.batteryPct}%" + if (s.charging) " · charging" else "")
            Hairline()
            MetricRow("Temperature", s.thermal.name.lowercase().replaceFirstChar { it.uppercase() })
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Activity")
        Spacer(Modifier.height(12.dp))
        ActivityCard()
        Spacer(Modifier.height(24.dp))
        ExperimentalCard()
        ChaosPanel()
        Spacer(Modifier.height(8.dp))
    }
}

/** The five rungs as a row of dots; the active rung is filled plum. */
@Composable
private fun RungLadder(active: Rung) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Rung.entries.forEach { rung ->
            val on = rung == active
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(if (on) 14.dp else 10.dp)
                        .background(if (on) Brand.Plum else Brand.Line, CircleShape),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    rung.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) Brand.Plum else Brand.InkMuted,
                )
            }
        }
    }
}

private fun Long.gb(): String = if (this >= 1024) "%.1f GB".format(this / 1024f) else "$this MB"

@Composable
private fun VectorEngineCard(state: BenchState, onRun: (Int) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("Qdrant Edge", style = MaterialTheme.typography.titleLarge, color = Brand.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "Loads random 384-d vectors into a real on-device shard (int8 quantized, HNSW m=8), then measures search.",
            style = MaterialTheme.typography.bodySmall,
            color = Brand.InkMuted,
        )
        Spacer(Modifier.height(16.dp))
        when (state) {
            BenchState.Idle -> Unit
            is BenchState.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Brand.Plum, strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(state.step, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
                }
                Spacer(Modifier.height(16.dp))
            }
            is BenchState.Done -> {
                val r = state.result
                MetricRow("Points", "%,d".format(r.points))
                Hairline()
                MetricRow("Search p50 / p95", "%.2f / %.2f ms".format(r.p50Ms, r.p95Ms), valueColor = Brand.Plum)
                Hairline()
                MetricRow("Recall@${r.k}", "%.0f%%".format(r.recallAtK * 100))
                Hairline()
                MetricRow("Load · index build", "${r.loadMs / 1000.0}s · ${r.optimizeMs / 1000.0}s")
                Hairline()
                MetricRow("On disk", r.diskBytes?.let { (it / (1024 * 1024)).toString() + " MB" } ?: "–")
                Spacer(Modifier.height(16.dp))
            }
            is BenchState.Failed -> {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = Brand.RedInk)
                Spacer(Modifier.height(16.dp))
            }
        }
        val enabled = state !is BenchState.Running
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(10_000, 100_000).forEach { n ->
                BenchButton("Run ${if (n >= 1000) "${n / 1000}K" else "$n"} points", enabled, Modifier.weight(1f)) { onRun(n) }
            }
        }
    }
}

@Composable
private fun BenchButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PrimaryButton(label, onClick, modifier, enabled = enabled)
}
