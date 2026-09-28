package org.polycare.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.ai.EmbedderProvider
import org.polycare.app.device.DeviceCheckViewModel
import org.polycare.app.knowledge.KnowledgeRepository
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.StatusPill
import org.polycare.app.ui.components.Wordmark
import org.polycare.app.ui.theme.Brand

private data class Feature(
    val title: String,
    val caption: String,
    val icon: ImageVector,
    val milestone: String,
    val accent: Color,
    val route: String? = null,
)

/** Each tool gets its own accent from the brand palette instead of one repeated chip colour —
 * the grid should read as eight distinct tools at a glance, not eight copies of one tile. */
private val Features = listOf(
    Feature("Ask", "Text answers, offline", Icons.Outlined.Mic, "M2", Brand.Plum, route = "ask"),
    Feature("Triage", "Danger signs & referral", Icons.Outlined.MonitorHeart, "M2", Brand.Rose, route = "triage"),
    Feature("Search", "Hybrid search, offline", Icons.Outlined.Search, "M1", Brand.Magenta, route = "search"),
    Feature("Memory", "What this phone knows", Icons.Outlined.Psychology, "M1", Brand.PlumDeep, route = "memory"),
    Feature("Scan", "MCP cards & reports", Icons.Outlined.DocumentScanner, "M3", Brand.Positive, route = "scan"),
    Feature("Households", "Families & visits", Icons.Outlined.Groups, "M3", Brand.Pink, route = "households"),
    Feature("Due list", "Today's visits", Icons.Outlined.CalendarMonth, "M3", Brand.Red, route = "due-list"),
    Feature("Sync", "Qdrant Cloud", Icons.Outlined.Sync, "M6", Brand.InkMuted),
)

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onAsk: () -> Unit,
    onNavigate: (route: String) -> Unit,
    onNotReady: (feature: String, milestone: String) -> Unit,
    onMenu: () -> Unit,
    viewModel: DeviceCheckViewModel = hiltViewModel(),
    status: HomeStatusViewModel = hiltViewModel(),
) {
    val device by viewModel.state.collectAsStateWithLifecycle()
    val knowledge by status.knowledge.collectAsStateWithLifecycle()
    val embedder by status.embedder.collectAsStateWithLifecycle()

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Brand.Glass).clickable(onClick = onMenu),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = Brand.Ink, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Wordmark(logoSize = 26.dp)
            }
            StatusPill("Offline ready", dot = Brand.Positive)
        }

        Spacer(Modifier.height(56.dp))
        SectionLabel("Namaste", color = Brand.Plum)
        Spacer(Modifier.height(10.dp))
        Text(
            "Care guidance for every doorstep, even without signal.",
            style = MaterialTheme.typography.displaySmall,
            color = Brand.Ink,
        )

        Spacer(Modifier.height(28.dp))
        AskBar(onClick = onAsk)

        Spacer(Modifier.height(36.dp))
        SectionLabel("Tools")
        Spacer(Modifier.height(12.dp))
        Features.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { f ->
                    FeatureTile(f, Modifier.weight(1f)) {
                        if (f.route != null) onNavigate(f.route) else onNotReady(f.title, f.milestone)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("On this phone")
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            MetricRow("Mode", device?.rung?.label ?: "Checking…", valueColor = Brand.Plum)
            Hairline()
            MetricRow(
                "Knowledge passages",
                when (val k = knowledge) {
                    is KnowledgeRepository.State.Ready -> "%,d · %d sources".format(k.points, k.manifest.sources.size)
                    KnowledgeRepository.State.Loading -> "Opening…"
                    else -> "Not installed"
                },
            )
            Hairline()
            MetricRow(
                "Language model",
                when (embedder) {
                    is EmbedderProvider.State.Ready -> "Ready · e5-small"
                    EmbedderProvider.State.Loading -> "Loading…"
                    is EmbedderProvider.State.Unavailable -> "Not installed"
                    EmbedderProvider.State.NotLoaded -> "Idle"
                },
            )
            Hairline()
            MetricRow("Skills", "Not installed")
            Hairline()
            MetricRow("Last sync", "Never")
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AskBar(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Brand.White)
            .border(1.dp, Brand.Line, CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Ask about a symptom or medicine",
            style = MaterialTheme.typography.bodyLarge,
            color = Brand.InkMuted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Brand.Magenta, Brand.Plum, Brand.PlumDeep))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = "Ask by voice", tint = Brand.White)
        }
    }
}

@Composable
private fun FeatureTile(feature: Feature, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick), padding = 18.dp, accent = feature.accent) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(feature.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(feature.icon, contentDescription = null, tint = feature.accent, modifier = Modifier.size(20.dp))
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = Brand.InkMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(feature.title, style = MaterialTheme.typography.titleLarge, color = Brand.Ink)
        Spacer(Modifier.height(2.dp))
        Text(feature.caption, style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
    }
}
