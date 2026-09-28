package org.polycare.app.ask

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.knowledge.KnowledgeHit
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.theme.Brand

@Composable
fun AskScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    initialQuery: String? = null,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val question by viewModel.question.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (initialQuery != null) viewModel.ask(initialQuery) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(Brand.Glass, CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Brand.Ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            SectionLabel("Ask", color = Brand.Plum)
        }

        Spacer(Modifier.height(20.dp))
        Text("Ask about a symptom or medicine", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Text only for now — voice arrives once whisper.cpp is integrated.",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.InkMuted,
        )

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = question,
                onValueChange = viewModel::onQuestionChange,
                modifier = Modifier.weight(1f).border(1.dp, Brand.Line, MaterialTheme.shapes.large),
                placeholder = { Text("e.g. baby has fast breathing") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.ask() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Brand.White,
                    unfocusedContainerColor = Brand.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(52.dp).background(Brand.Plum, CircleShape).clickable { viewModel.ask() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Ask", tint = Brand.Paper)
            }
        }

        Spacer(Modifier.height(24.dp))
        when (val state = ui) {
            AskUi.Idle -> Unit
            AskUi.Asking -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Plum, strokeWidth = 2.dp)
            }
            is AskUi.Unavailable -> GlassCard(Modifier.fillMaxWidth()) {
                Text(state.reason, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
            }
            is AskUi.NoAnswer -> NoAnswerCard()
            is AskUi.Answered -> AnswerCard(state.hit, state.confidence, state.gapLogged)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AnswerCard(hit: KnowledgeHit, confidence: Float, gapLogged: Boolean) {
    val confidencePct = (confidence * 100).toInt()
    val lowConfidence = confidence < org.polycare.common.PolyCareConfig.Routing.minSkillScore
    val confidenceColor = if (lowConfidence) Brand.Rose else Brand.Positive

    GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (hit.lang == "hi") "हिंदी" else "English")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(confidenceColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("$confidencePct% match", style = MaterialTheme.typography.labelSmall, color = confidenceColor)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(hit.text, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        MetricRow("Source", "${hit.title} · p${hit.page}")

        if (lowConfidence) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Low confidence — this may not be the right passage. When in doubt, refer or ask a supervisor.",
                style = MaterialTheme.typography.bodySmall,
                color = Brand.Rose,
            )
        }
        if (gapLogged) {
            Spacer(Modifier.height(6.dp))
            Text("Saved as a gap for the next sync.", style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Shown as the closest matching passage — a generated explanation arrives once the on-device AI model is integrated.",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.InkMuted,
        )
    }
}

@Composable
private fun NoAnswerCard() {
    GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        Text("No matching passage found.", style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Saved as a gap for the next sync. When in doubt, refer or ask a supervisor.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.InkMuted,
        )
    }
}
