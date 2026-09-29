package org.polycare.app.ask

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.ai.VoiceRecorder
import org.polycare.app.team.TeamAnswer
import org.polycare.app.ui.components.AppIconButton
import org.polycare.app.ui.components.ChipRow
import org.polycare.app.ui.components.ChoiceChip
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.theme.Brand
import org.polycare.common.PolyCareConfig
import org.polycare.llm.LlmArtifacts

private val Suggestions = listOf(
    "Baby has fast breathing",
    "How to prepare ORS",
    "Heavy bleeding after delivery",
    "When is vitamin A given",
    "Newborn not feeding well",
)

@Composable
fun AskScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    initialQuery: String? = null,
    autoStartVoice: Boolean = false,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val question by viewModel.question.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording()
    }
    fun toggleMic() {
        when (voice) {
            VoiceUi.Recording -> viewModel.stopRecording()
            VoiceUi.Transcribing -> Unit
            else -> if (VoiceRecorder.hasPermission(context)) viewModel.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(initialQuery, autoStartVoice) {
        if (initialQuery != null) viewModel.ask(initialQuery)
        // Home's mic button navigates here with this set, so tapping it starts listening
        // immediately instead of landing on a blank Ask screen the user has to tap again.
        if (autoStartVoice) {
            if (VoiceRecorder.hasPermission(context)) viewModel.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader("Ask", Brand.Plum, onBack = onBack)

        Spacer(Modifier.height(12.dp))
        Text("Ask about a symptom or medicine", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            when (voice) {
                VoiceUi.Recording -> "Listening… tap the stop button when you're done."
                VoiceUi.Transcribing -> "Turning your voice into text on this phone…"
                is VoiceUi.Failed -> (voice as VoiceUi.Failed).reason
                VoiceUi.Idle -> "Type or speak in Hindi or English. Works without signal."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (voice is VoiceUi.Failed) Brand.Red else Brand.InkMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(
                value = question,
                onValueChange = viewModel::onQuestionChange,
                modifier = Modifier.weight(1f).border(1.dp, Brand.Line, MaterialTheme.shapes.large),
                label = { Text("Your question") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.ask() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Brand.White,
                    unfocusedContainerColor = Brand.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedLabelColor = Brand.Plum,
                    unfocusedLabelColor = Brand.InkMuted,
                ),
            )
            MicButton(voice) { toggleMic() }
            AppIconButton(
                Icons.AutoMirrored.Outlined.Send, "Ask", viewModel::ask,
                tint = Brand.Paper, container = Brand.Plum, size = 56.dp, enabled = question.isNotBlank(),
            )
        }

        if (ui == AskUi.Idle) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Try asking")
            Spacer(Modifier.height(10.dp))
            ChipRow { Suggestions.forEach { s -> ChoiceChip(s, selected = false, onClick = { viewModel.ask(s) }) } }
        }

        Spacer(Modifier.height(24.dp))
        when (val state = ui) {
            AskUi.Idle -> Unit
            AskUi.Asking -> Row(
                Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Plum, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Searching the guidance…", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
            }
            is AskUi.Unavailable -> GlassCard(Modifier.fillMaxWidth()) {
                Text(state.reason, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
            }
            is AskUi.NoAnswer -> NoAnswerCard(state)
            is AskUi.Answered -> AnswerCard(state)
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** 56dp mic button. While recording it turns red, shows a stop icon and pulses a soft ring. */
@Composable
private fun MicButton(voice: VoiceUi, onClick: () -> Unit) {
    val recording = voice == VoiceUi.Recording
    val pulse = rememberInfiniteTransition(label = "mic-pulse")
    val ring by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.45f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring",
    )
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (recording) Box(Modifier.size(56.dp).scale(ring).alpha(0.25f).background(Brand.Rose, CircleShape))
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (recording) Brand.Rose else Brand.PinkMist)
                .border(1.dp, if (recording) Brand.Rose else Brand.Line, CircleShape)
                .clickable(enabled = voice != VoiceUi.Transcribing, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = if (recording) "Stop recording" else "Speak your question"
                    stateDescription = when (voice) {
                        VoiceUi.Recording -> "Recording"
                        VoiceUi.Transcribing -> "Transcribing"
                        else -> "Ready"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                voice == VoiceUi.Transcribing -> CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Plum, strokeWidth = 2.dp)
                recording -> Icon(Icons.Outlined.Stop, contentDescription = null, tint = Brand.Paper)
                else -> Icon(Icons.Outlined.Mic, contentDescription = null, tint = Brand.Plum)
            }
        }
    }
}

@Composable
private fun AnswerCard(state: AskUi.Answered) {
    val confidencePct = (state.confidence * 100).toInt()
    val lowConfidence = state.confidence < PolyCareConfig.Routing.minSkillScore
    val confidenceColor = if (lowConfidence) Brand.Red else Brand.Positive
    var showSource by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.teamAnswer?.let { TeamAnswerCard(it) }

        GlassCard(Modifier.fillMaxWidth(), padding = 20.dp, accent = Brand.Plum) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(if (state.hit.lang == "hi") "हिंदी" else "English")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$confidencePct percent match, ${if (lowConfidence) "low" else "good"} confidence" },
                ) {
                    Box(Modifier.size(9.dp).background(confidenceColor, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("$confidencePct% match", style = MaterialTheme.typography.labelSmall, color = confidenceColor)
                }
            }

            Spacer(Modifier.height(12.dp))
            if (state.generated != null) {
                Text(
                    state.generated + if (state.generating) " ▍" else "",
                    style = MaterialTheme.typography.bodyLarge, color = Brand.Ink, fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                // The retrieved passage is already a complete, cited answer: show it at once and
                // let the generated plain-language version stream in above it.
                Text(state.hit.text, style = MaterialTheme.typography.bodyLarge, color = Brand.Ink, fontWeight = FontWeight.Medium)
                if (state.generating) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Brand.Plum, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Writing a simpler explanation…", style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
                    }
                }
            }

            if (state.generated != null && !state.generating && state.tokensPerSecond != null) {
                Spacer(Modifier.height(12.dp))
                SpeedRow(state)
            }

            Spacer(Modifier.height(12.dp))
            MetricRow("Source", "${state.hit.title} · p${state.hit.page}")

            if (state.generated != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = if (showSource) "Hide source passage" else "Show source passage", role = Role.Button) { showSource = !showSource },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showSource) "Hide source passage" else "Show source passage",
                        style = MaterialTheme.typography.titleSmall, color = Brand.Plum,
                    )
                }
                if (showSource) Text(state.hit.text, style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
            }

            if (lowConfidence) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Low confidence — this may not be the right passage. When in doubt, refer or ask a supervisor.",
                    style = MaterialTheme.typography.bodyMedium, color = Brand.Red,
                )
            }
            if (state.gapLogged) {
                Spacer(Modifier.height(6.dp))
                Text("Saved as a question for your supervisor on the next sync.", style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
            }
            if (state.generated == null && !state.generating) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Shown as the closest matching passage — install the on-device model for a generated explanation.",
                    style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted,
                )
            }
        }
    }
}

/** Speed at a glance: tokens/s, how much of the reply was predicted from the passage, cached prompt. */
@Composable
private fun SpeedRow(state: AskUi.Answered) {
    val model = if (state.skill != null) "${LlmArtifacts.shortName} + ${state.skill}" else LlmArtifacts.shortName
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Bolt, contentDescription = null, tint = Brand.Plum, modifier = Modifier.size(16.dp))
        val parts = buildList {
            add("%.1f tok/s".format(state.tokensPerSecond))
            state.draftAcceptance?.let { add("${(it * 100).toInt()}% predicted") }
            if (state.cachedPromptTokens > 0) add("${state.cachedPromptTokens} tokens cached")
        }
        Text(
            "$model · " + parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted,
        )
    }
}

@Composable
private fun TeamAnswerCard(answer: TeamAnswer) {
    GlassCard(Modifier.fillMaxWidth(), padding = 20.dp, accent = Brand.Positive) {
        SectionLabel("Answer from your supervisor", color = Brand.Positive)
        Spacer(Modifier.height(8.dp))
        Text(answer.answer, style = MaterialTheme.typography.bodyLarge, color = Brand.Ink, fontWeight = FontWeight.Medium)
        if (answer.author.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("— ${answer.author}", style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Team guidance, not an official protocol. Check it against the passage below.",
            style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted,
        )
    }
}

@Composable
private fun NoAnswerCard(state: AskUi.NoAnswer) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.teamAnswer?.let { TeamAnswerCard(it) }
        GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            Text("No matching passage found.", style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.gapLogged) "Saved as a question for your supervisor on the next sync. When in doubt, refer or ask a supervisor."
                else "When in doubt, refer or ask a supervisor.",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.InkMuted,
            )
        }
    }
}
