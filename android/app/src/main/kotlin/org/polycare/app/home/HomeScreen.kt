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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.ai.EmbedderProvider
import org.polycare.app.ai.LlmProvider
import org.polycare.app.device.DeviceCheckViewModel
import org.polycare.app.households.Visit
import org.polycare.app.knowledge.KnowledgeRepository
import org.polycare.app.sync.SyncState
import org.polycare.app.team.GuidanceCard
import org.polycare.app.ui.components.AppIconButton
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.PrimaryButton
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.Wordmark
import org.polycare.app.ui.components.agoLabel
import org.polycare.app.ui.theme.Brand
import org.polycare.common.radar.AlertLevel
import org.polycare.llm.LlmArtifacts

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onAsk: (voice: Boolean) -> Unit,
    onNavigate: (route: String) -> Unit,
    onMenu: () -> Unit,
    viewModel: DeviceCheckViewModel = hiltViewModel(),
    status: HomeStatusViewModel = hiltViewModel(),
) {
    val device by viewModel.state.collectAsStateWithLifecycle()
    val knowledge by status.knowledge.collectAsStateWithLifecycle()
    val embedder by status.embedder.collectAsStateWithLifecycle()
    val llm by status.llm.collectAsStateWithLifecycle()
    val recentVisits by status.recentVisits.collectAsStateWithLifecycle()
    val radar by status.radar.collectAsStateWithLifecycle()
    val syncState by status.syncState.collectAsStateWithLifecycle()
    val pending by status.pendingOps.collectAsStateWithLifecycle()
    val conflicts by status.conflictList.collectAsStateWithLifecycle()
    val cards by status.guidanceCards.collectAsStateWithLifecycle()
    var openCard by remember { mutableStateOf<GuidanceCard?>(null) }
    val liveCards = cards.filter { !it.dismissed }

    openCard?.let { card ->
        AlertDialog(
            onDismissRequest = { openCard = null },
            title = { Text(card.title) },
            text = { Text(card.body + if (card.author.isNotBlank()) "\n\n— ${card.author}" else "") },
            confirmButton = { TextButton(onClick = { status.dismissGuidance(card.id); openCard = null }) { Text("Got it") } },
            dismissButton = { TextButton(onClick = { openCard = null }) { Text("Keep for later") } },
        )
    }

    val topAlert = radar.firstOrNull { it.alert.level == AlertLevel.ALERT }
    val openConflicts = conflicts.count { it.open }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(Icons.Outlined.Apps, "Open all tools", onMenu, tint = Brand.Ink, container = Brand.Glass)
            Spacer(Modifier.width(12.dp))
            Wordmark(logoSize = 26.dp)
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("TODAY", color = Brand.Plum)
        Spacer(Modifier.height(8.dp))
        Text(
            "What do you need help with?",
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.Ink,
        )

        Spacer(Modifier.height(16.dp))
        AskBar(onClick = { onAsk(false) }, onMicClick = { onAsk(true) })

        if (topAlert != null) {
            Spacer(Modifier.height(20.dp))
            AttentionBanner(
                title = "Outbreak alert: ${topAlert.alert.label}",
                detail = "${topAlert.alert.signalCount} cases in ${topAlert.alert.villages.size} villages",
                color = Brand.Red,
            ) { onNavigate("radar") }
        }
        liveCards.take(2).forEach { card ->
            Spacer(Modifier.height(12.dp))
            AttentionBanner(title = card.title, detail = "Guidance from your supervisor", color = Brand.Positive) { openCard = card }
        }
        if (openConflicts > 0) {
            Spacer(Modifier.height(12.dp))
            AttentionBanner(
                title = "$openConflicts record${if (openConflicts == 1) "" else "s"} to review",
                detail = "Two workers wrote different details for the same family",
                color = Brand.Magenta,
            ) { onNavigate("conflicts") }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("QUICK ACTIONS")
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = "Check danger signs",
            onClick = { onNavigate("triage") },
            icon = Icons.Outlined.MonitorHeart,
            accent = Brand.PlumDeep,
        )
        Spacer(Modifier.height(8.dp))
        HomeActionRow("Plan today's visits", "See who needs a follow-up", Icons.Outlined.CalendarMonth) {
            onNavigate("due-list")
        }
        Spacer(Modifier.height(8.dp))
        HomeActionRow("Scan a card or report", "Read details on this phone", Icons.Outlined.DocumentScanner) {
            onNavigate("scan")
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Recent activity")
        Spacer(Modifier.height(12.dp))
        if (recentVisits.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("No visits recorded yet.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
                Spacer(Modifier.height(2.dp))
                Text("Visits you log from Households or Due list will show up here.", style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
            }
        } else {
            GlassCard(Modifier.fillMaxWidth(), padding = 4.dp) {
                recentVisits.take(5).forEachIndexed { i, visit ->
                    if (i > 0) Hairline()
                    ActivityRow(visit)
                }
            }
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
                when (llm) {
                    is LlmProvider.State.Ready -> "Ready · ${LlmArtifacts.shortName}"
                    LlmProvider.State.Loading -> "Loading…"
                    is LlmProvider.State.Unavailable -> "Not installed"
                    LlmProvider.State.NotLoaded -> "Idle"
                },
            )
            Hairline()
            MetricRow(
                "Search model",
                when (embedder) {
                    is EmbedderProvider.State.Ready -> "Ready · e5-small"
                    EmbedderProvider.State.Loading -> "Loading…"
                    is EmbedderProvider.State.Unavailable -> "Not installed"
                    EmbedderProvider.State.NotLoaded -> "Idle"
                },
            )
            Hairline()
            MetricRow("Skills", if (status.skillCount == 0) "Not installed" else "${status.skillCount} installed")
            Hairline()
            MetricRow(
                "Sync",
                when (val s = syncState) {
                    is SyncState.Done -> agoLabel(s.atMs)
                    is SyncState.Running -> "Syncing…"
                    is SyncState.Failed -> "Last try failed"
                    SyncState.Idle -> if (pending == 0) "Never" else "$pending waiting"
                },
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AskBar(onClick: () -> Unit, onMicClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clip(CircleShape)
                .background(Brand.White)
                .border(1.dp, Brand.Line, CircleShape)
                .clickable(onClickLabel = "Ask a question", role = Role.Button, onClick = onClick)
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Ask about a symptom or medicine",
                style = MaterialTheme.typography.bodyLarge,
                color = Brand.InkMuted,
                maxLines = 2,
            )
        }
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Brand.Magenta, Brand.Plum, Brand.PlumDeep)))
                .clickable(onClickLabel = "Ask by voice", role = Role.Button, onClick = onMicClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = "Ask by voice", tint = Brand.White)
        }
    }
}

/** Something that needs attention now: a strong colour, one line of why, one tap to act. */
@Composable
private fun AttentionBanner(title: String, detail: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.large)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), MaterialTheme.shapes.large)
            .clickable(onClickLabel = "Open", role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Warning, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = Brand.InkMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun HomeActionRow(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(MaterialTheme.shapes.large)
            .background(Brand.White)
            .border(1.dp, Brand.Line, MaterialTheme.shapes.large)
            .clickable(onClickLabel = title, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Brand.Plum, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = Brand.InkMuted)
    }
}

@Composable
private fun ActivityRow(visit: Visit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(visit.type.label, style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
            visit.memberName?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
            }
        }
        if (visit.highRisk) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Brand.Red))
            Spacer(Modifier.width(8.dp))
            Text("High risk", style = MaterialTheme.typography.labelSmall, color = Brand.Red)
            Spacer(Modifier.width(12.dp))
        }
        Text(visit.date, style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
    }
}
