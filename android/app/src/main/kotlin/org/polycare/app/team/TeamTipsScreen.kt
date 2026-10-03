package org.polycare.app.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.LabelledField
import org.polycare.app.ui.components.PrimaryButton
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.SecondaryButton
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.StatusPill
import org.polycare.app.ui.components.agoLabel
import org.polycare.app.ui.theme.Brand
import javax.inject.Inject

@HiltViewModel
class TeamTipsViewModel @Inject constructor(private val memory: TeamMemoryRepository) : ViewModel() {
    val tips: StateFlow<List<TeamTip>> = memory.tips

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _sharing = MutableStateFlow(false)
    val sharing: StateFlow<Boolean> = _sharing

    /** Returns true when the tip was accepted, so the screen can clear the box. */
    fun share(text: String, onDone: (Boolean) -> Unit) {
        _sharing.value = true
        viewModelScope.launch {
            val r = memory.share(text)
            _message.value = when (r) {
                is ShareResult.Shared -> "Shared. It reaches the team on the next sync."
                is ShareResult.Voted ->
                    if (r.alreadyYours) "You already shared something very close to this."
                    else "The team already has a tip like this, so your vote was added to it."
                ShareResult.TooShort -> "Write a little more so others can use it."
                is ShareResult.HasIdentifier -> "That looks like it contains ${r.what}. Remove it: tips are visible to the whole team."
                is ShareResult.Unavailable -> r.reason
            }
            _sharing.value = false
            onDone(r is ShareResult.Shared || r is ShareResult.Voted)
        }
    }

    fun vote(id: String) {
        _message.value = if (memory.vote(id)) "Thanks. Your vote counts on the next sync." else null
    }
}

private val Accent = Brand.Positive

@Composable
fun TeamTipsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: TeamTipsViewModel = hiltViewModel(),
) {
    val tips by viewModel.tips.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val sharing by viewModel.sharing.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val visible = tips.filter { it.status != TipStatus.SUPERSEDED }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader("Team tips", Accent, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        Text("What works, shared by ASHAs like you.", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Share a practical tip other health workers can find when they ask a question. Tips are visible to the whole team: never write names, phone numbers or addresses.",
            style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted,
        )

        Spacer(Modifier.height(20.dp))
        GlassCard(Modifier.fillMaxWidth(), accent = Accent) {
            LabelledField("Your tip", draft, singleLine = false, supporting = "For example: what worked to get a family to attend immunisation day") { draft = it }
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                if (sharing) "Checking…" else "Share with the team",
                onClick = { viewModel.share(draft) { ok -> if (ok) draft = "" } },
                enabled = !sharing && draft.isNotBlank(), icon = Icons.Outlined.Send, accent = Accent,
            )
            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Shared tips · ${visible.size}")
        Spacer(Modifier.height(10.dp))
        if (visible.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("No tips yet.", style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
                Text("Tips you share and tips from other ASHAs appear here after a sync.", style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { visible.forEach { TipCard(it, viewModel::vote) } }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TipCard(tip: TeamTip, onVote: (String) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), accent = if (tip.status == TipStatus.DISPUTED) Brand.Red else Accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (tip.mine) "You" else "Another ASHA") + " · " + agoLabel(tip.wallMs),
                style = MaterialTheme.typography.labelLarge, color = Brand.InkMuted, modifier = Modifier.weight(1f),
            )
            if (tip.status == TipStatus.DISPUTED) StatusPill("May disagree with another tip", dot = Brand.Red)
        }
        Spacer(Modifier.height(8.dp))
        Text(tip.text, style = MaterialTheme.typography.bodyLarge, color = Brand.Ink)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tip.votes == 0) "No votes yet" else "${tip.votes} found this useful",
                style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            if (!tip.mine) {
                SecondaryButton(
                    if (tip.iVoted) "Voted" else "Useful",
                    { onVote(tip.id) }, Modifier.width(140.dp), icon = Icons.Outlined.ThumbUp, enabled = !tip.iVoted, accent = Accent,
                )
            }
        }
        if (tip.status == TipStatus.DISPUTED) {
            Spacer(Modifier.height(6.dp))
            Text("Check the Conflict inbox to compare it with a similar tip.", style = MaterialTheme.typography.bodySmall, color = Brand.RedInk)
        }
    }
}
