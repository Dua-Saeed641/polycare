package org.polycare.app.triage

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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.knowledge.DangerSign
import org.polycare.app.knowledge.TriageCategory
import org.polycare.app.knowledge.TriageDecision
import org.polycare.app.knowledge.TriageEngine
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.theme.Brand

@Composable
fun TriageScreen(contentPadding: PaddingValues, onBack: () -> Unit, viewModel: TriageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(Brand.Rose.copy(alpha = 0.10f), CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Brand.Rose, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            SectionLabel("Triage", color = Brand.Rose)
        }

        Spacer(Modifier.height(20.dp))
        Text("Danger-sign check", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TriageCategory.entries.forEach { category ->
                CategoryChip(category.label, selected = state.category == category) { viewModel.onCategoryChange(category) }
            }
        }

        Spacer(Modifier.height(20.dp))
        DecisionCard(state.result.decision, state.result.explanation, state.result.sourceTitle, state.aiExplanation, state.generating)

        Spacer(Modifier.height(24.dp))
        SectionLabel("Mark what you see")
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = 8.dp) {
            TriageEngine.signs(state.category).forEachIndexed { i, sign ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = Brand.LineSoft)
                SignRow(sign, checked = sign.id in state.selected) { viewModel.toggle(sign.id) }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) Brand.Plum else Brand.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) Brand.Paper else Brand.InkMuted)
    }
}

@Composable
private fun DecisionCard(
    decision: TriageDecision,
    explanation: String,
    sourceTitle: String,
    aiExplanation: String?,
    generating: Boolean,
) {
    val color = when (decision) {
        TriageDecision.REFER_NOW -> Brand.Red
        TriageDecision.REFER_24H -> Brand.Magenta
        TriageDecision.CARE_AT_HOME -> Brand.Positive
    }
    GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(decision.label, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(10.dp))
        Text(explanation, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
        Spacer(Modifier.height(10.dp))
        MetricRow("Source", sourceTitle)

        when {
            aiExplanation != null -> {
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.HorizontalDivider(color = Brand.LineSoft)
                Spacer(Modifier.height(10.dp))
                SectionLabel("In plain words", color = Brand.Plum)
                Spacer(Modifier.height(6.dp))
                Text(aiExplanation, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
                if (generating) {
                    Spacer(Modifier.height(6.dp))
                    Text("Generating on-device…", style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
                }
            }
            generating -> {
                Spacer(Modifier.height(10.dp))
                Text("Generating a plain-language explanation…", style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
            }
            else -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Decided by rules, not the on-device model — install the model for a plain-language explanation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.InkMuted,
                )
            }
        }
    }
}

@Composable
private fun SignRow(sign: DangerSign, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) Brand.Plum else Color.Transparent)
                .border(1.dp, if (checked) Brand.Plum else Brand.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = Brand.Paper, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(sign.label, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
    }
}
