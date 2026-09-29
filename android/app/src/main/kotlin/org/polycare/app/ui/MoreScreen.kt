package org.polycare.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.theme.Brand

private data class ToolLink(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
)

private data class ToolSection(val title: String, val tools: List<ToolLink>)

private val toolSections = listOf(
    ToolSection(
        "Find care information",
        listOf(
            ToolLink("Search guidance", "Find answers in saved health guidance", Icons.Outlined.Search, "search"),
            ToolLink("Medicines", "Doses and counselling", Icons.Outlined.Medication, "medicine"),
            ToolLink("Scan a document", "Read a card, report or prescription", Icons.Outlined.DocumentScanner, "scan"),
        ),
    ),
    ToolSection(
        "Families and visits",
        listOf(
            ToolLink("Households", "Family records and visit history", Icons.Outlined.Groups, "households"),
            ToolLink("Due list", "Plan today's follow-up visits", Icons.Outlined.CalendarMonth, "due-list"),
        ),
    ),
    ToolSection(
        "Work with your team",
        listOf(
            ToolLink("Team tips", "Practical notes shared by workers", Icons.Outlined.Lightbulb, "tips"),
            ToolLink("Outbreak Radar", "Check local symptom alerts", Icons.Outlined.Radar, "radar"),
            ToolLink("Conflict inbox", "Review records that do not match", Icons.AutoMirrored.Outlined.CallMerge, "conflicts"),
            ToolLink("Sync", "Share approved information with your team", Icons.Outlined.CloudSync, "sync"),
        ),
    ),
    ToolSection(
        "Phone and data",
        listOf(
            ToolLink("Memory on this phone", "Browse saved guidance and notes", Icons.Outlined.Psychology, "memory"),
            ToolLink("Phone status", "Models, storage and app health", Icons.Outlined.Tune, "system"),
        ),
    ),
)

/** A plain-language directory for tools that do not need a permanent bottom-navigation slot. */
@Composable
fun MoreScreen(contentPadding: PaddingValues, onNavigate: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(title = "More", accent = Brand.Plum)
        Spacer(Modifier.height(12.dp))
        Text("All tools", style = MaterialTheme.typography.headlineMedium, color = Brand.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "Choose a task. Your private family records stay on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = Brand.InkMuted,
        )
        toolSections.forEach { section ->
            Spacer(Modifier.height(24.dp))
            Text(
                section.title,
                style = MaterialTheme.typography.titleMedium,
                color = Brand.Ink,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brand.White)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                section.tools.forEachIndexed { index, tool ->
                    ToolRow(tool, onClick = { onNavigate(tool.route) })
                    if (index < section.tools.lastIndex) {
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = Brand.Line,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToolRow(tool: ToolLink, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Open ${tool.title}", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tool.icon, contentDescription = null, tint = Brand.Plum, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(tool.title, style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
            Text(tool.description, style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = Brand.InkMuted)
    }
}
