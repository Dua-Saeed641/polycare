package org.polycare.app.triage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.knowledge.DangerSign
import org.polycare.app.knowledge.TriageCategory
import org.polycare.app.knowledge.TriageDecision
import org.polycare.app.knowledge.TriageEngine
import org.polycare.app.ui.components.ChipRow
import org.polycare.app.ui.components.ChoiceChip
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.SecondaryButton
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
        ScreenHeader("Triage", Brand.Rose, onBack = onBack)

        Spacer(Modifier.height(12.dp))
        Text("Danger-sign check", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
        Spacer(Modifier.height(4.dp))
        Text("Who is the patient?", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)

        Spacer(Modifier.height(12.dp))
        ChipRow {
            TriageCategory.entries.forEach { category ->
                ChoiceChip(category.label, selected = state.category == category, onClick = { viewModel.onCategoryChange(category) }, accent = Brand.Plum)
            }
        }

        Spacer(Modifier.height(20.dp))
        DecisionCard(state.result.decision, state.result.explanation, state.result.sourceTitle, state.aiExplanation, state.generating)

        if (state.selected.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RadarCard(state, viewModel::setVillage, viewModel::setSex, viewModel::setAgeBand, viewModel::reportToRadar)
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Mark what you see")
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            TriageEngine.signs(state.category).forEachIndexed { i, sign ->
                if (i > 0) HorizontalDivider(color = Brand.LineSoft)
                SignRow(sign, checked = sign.id in state.selected) { viewModel.toggle(sign.id) }
            }
        }
        Spacer(Modifier.height(32.dp))
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
    // Two colours per severity: [color] fills the dot and the card's accent edge, [inkColor]
    // is the readable one for the decision text itself. REFER_NOW is the single most
    // important sentence in the app and Brand.Red reaches only 3.73:1 on paper, so it uses
    // Brand.RedInk (5.9:1) for the text.
    val color = when (decision) {
        TriageDecision.REFER_NOW -> Brand.Red
        TriageDecision.REFER_24H -> Brand.Magenta
        TriageDecision.CARE_AT_HOME -> Brand.Positive
    }
    val inkColor = when (decision) {
        TriageDecision.REFER_NOW -> Brand.RedInk
        TriageDecision.REFER_24H -> Brand.Magenta
        TriageDecision.CARE_AT_HOME -> Brand.Positive
    }
    // The decision is spoken when it changes, so marking a sign gives immediate feedback to a screen reader.
    GlassCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, padding = 20.dp, accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(decision.label, style = MaterialTheme.typography.headlineSmall, color = inkColor, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(10.dp))
        Text(explanation, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
        Spacer(Modifier.height(10.dp))
        // Source titles are full sentences in Hindi; stacked so they wrap under the label.
        MetricRow("Source", sourceTitle, stacked = true)

        when {
            aiExplanation != null -> {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Brand.LineSoft)
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

/**
 * Optional, explicit: report this case to the Outbreak Radar as a de-identified signal. Shows
 * exactly what would be sent (danger signs, village, week, an age band and a sex) so the choice is
 * informed. No name, no household, no free text.
 */
@Composable
private fun RadarCard(
    state: TriageUiState,
    onVillage: (String) -> Unit,
    onSex: (String) -> Unit,
    onAgeBand: (String) -> Unit,
    onReport: () -> Unit,
) {
    val category = state.category
    val postpartum = category == TriageCategory.POSTPARTUM
    val sex = state.sex ?: if (postpartum) "F" else "U"
    val ageBand = state.ageBand ?: when (category) {
        TriageCategory.NEWBORN -> "0-1"
        TriageCategory.CHILD -> "1-4"
        TriageCategory.POSTPARTUM -> "20-29"
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Radar, contentDescription = null, tint = Brand.Rose, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Help spot outbreaks", style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Shares only the danger signs, the village, this week, an age band and a sex. No name, no household.",
            style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted,
        )
        Spacer(Modifier.height(12.dp))
        SectionLabel("Village")
        Spacer(Modifier.height(6.dp))
        if (state.villages.isNotEmpty()) {
            ChipRow { state.villages.forEach { v -> ChoiceChip(v, selected = v == state.village, onClick = { onVillage(v) }, accent = Brand.Rose) } }
        } else {
            org.polycare.app.ui.components.LabelledField("Village", state.village, onChange = onVillage)
        }
        if (!postpartum) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Sex")
            Spacer(Modifier.height(6.dp))
            ChipRow {
                listOf("F" to "Girl", "M" to "Boy", "U" to "Not stated").forEach { (code, label) ->
                    ChoiceChip(label, selected = sex == code, onClick = { onSex(code) }, accent = Brand.Rose)
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Mother's age")
            Spacer(Modifier.height(6.dp))
            ChipRow {
                listOf("15-19", "20-29", "30-39", "40-49").forEach { band ->
                    ChoiceChip(band, selected = ageBand == band, onClick = { onAgeBand(band) }, accent = Brand.Rose)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.reported) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Brand.Positive)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (state.shareable) "Logged for the Outbreak Radar" else "Logged on this phone only: install the search model to share it",
                    style = MaterialTheme.typography.titleSmall, color = Brand.Positive,
                )
            }
        } else {
            SecondaryButton("Log this case for the radar", onReport, enabled = state.village.isNotBlank(), icon = Icons.Outlined.Radar, accent = Brand.Rose)
        }
    }
}

@Composable
private fun SignRow(sign: DangerSign, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (checked) Brand.Plum else Color.Transparent)
                .border(1.5.dp, if (checked) Brand.Plum else Brand.InkMuted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = Brand.Paper, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(sign.label, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink, modifier = Modifier.weight(1f))
    }
}
