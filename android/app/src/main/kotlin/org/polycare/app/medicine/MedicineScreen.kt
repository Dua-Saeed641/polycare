package org.polycare.app.medicine

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.knowledge.KnowledgeHit
import org.polycare.app.ui.components.ChipRow
import org.polycare.app.ui.components.ChoiceChip
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.SecondaryButton
import org.polycare.app.ui.components.tapTarget
import org.polycare.app.ui.theme.Brand

private val Accent = Brand.Positive

@Composable
fun MedicineScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAsk: (query: String) -> Unit,
    viewModel: MedicineViewModel = hiltViewModel(),
) {
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader("Medicines & counselling", Accent, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        Text("Quick reference from the ASHA modules", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Every card looks the answer up in the official passages on this phone and shows the page it came from.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.InkMuted,
        )

        Spacer(Modifier.height(20.dp))
        ChipRow {
            CardKind.entries.forEach { k ->
                ChoiceChip(k.label, selected = kind == k, onClick = { viewModel.setKind(k) }, accent = Accent)
            }
        }

        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TopicCards.filter { it.kind == kind }.forEach { card ->
                TopicCardView(
                    card = card,
                    open = expanded == card.id,
                    state = results[card.id],
                    onToggle = { viewModel.toggle(card) },
                    onAsk = { onAsk(card.query) },
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TopicCardView(card: TopicCard, open: Boolean, state: CardState?, onToggle: () -> Unit, onAsk: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().animateContentSize(), padding = 0.dp, accent = Accent) {
        Row(
            Modifier
                .fillMaxWidth()
                .tapTarget(onClickLabel = if (open) "Collapse ${card.title}" else "Open ${card.title}", onClick = onToggle)
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
                Spacer(Modifier.height(2.dp))
                Text(card.caption, style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
            }
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = Brand.InkMuted,
            )
        }
        if (open) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                when (state) {
                    null, CardState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking it up on this phone…", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
                    }
                    CardState.NotInstalled -> Text(
                        "The knowledge base isn't installed on this phone yet.",
                        style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted,
                    )
                    CardState.NothingFound -> Text(
                        "Nothing in the indexed modules matched this topic. Ask your ANM or supervisor.",
                        style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted,
                    )
                    is CardState.Found -> state.hits.forEachIndexed { i, hit ->
                        if (i > 0) Spacer(Modifier.height(14.dp))
                        HitBlock(hit)
                    }
                }
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Ask a question about this", onAsk, icon = Icons.Outlined.Mic, accent = Accent)
            }
        }
    }
}

@Composable
private fun HitBlock(hit: KnowledgeHit) {
    Box(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${hit.title}, page ${hit.page}. ${hit.text}" }) {
        Column {
            Text(hit.text, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
            Spacer(Modifier.height(4.dp))
            Text("${hit.title} · p${hit.page}", style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
        }
    }
}
