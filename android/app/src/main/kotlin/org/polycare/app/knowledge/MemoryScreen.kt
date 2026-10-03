package org.polycare.app.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.MetricRow
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.theme.Brand

/**
 * Memory Inspector (M1): what the phone knows — how many passages, from which official sources,
 * in which languages — and lets you browse and filter them. Read-only today: it shows the
 * cloud-owned `knowledge` shard exactly as installed (invariant 9). Personal notes ("memory")
 * do not exist on the phone yet (M3/M4), so there is nothing here for a technician to have
 * written themselves — only what was shipped in the knowledge package.
 */
@Composable
fun MemoryScreen(contentPadding: PaddingValues, onBack: () -> Unit, viewModel: MemoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader("What this phone knows", Brand.PlumDeep, onBack = onBack)
        }

        item { OverviewCard(state.stats, state.filter, onSource = viewModel::setSource) }

        val stats = state.stats
        if (stats != null && stats.byLang.size > 1) {
            item {
                org.polycare.app.ui.components.ChipRow {
                    LangChip("All", state.filter.lang == null) { viewModel.setLang(null) }
                    stats.byLang.forEach { (lang, count) ->
                        LangChip("${langLabel(lang)} · $count", state.filter.lang == lang) { viewModel.setLang(lang) }
                    }
                }
            }
        }

        if (state.items.isEmpty() && !state.loadingMore) {
            item {
                Text("No passages match this filter.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
            }
        }

        items(state.items, key = { it.id }) { item -> PassageCard(item) }

        item {
            if (state.loadingMore) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Brand.Plum, strokeWidth = 2.dp)
                }
            } else if (state.hasMore && state.items.isNotEmpty()) {
                org.polycare.app.ui.components.SecondaryButton("Load more passages", viewModel::loadMore)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OverviewCard(stats: KnowledgeStats?, filter: MemoryFilter, onSource: (String?) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        if (stats == null) {
            Text("Opening knowledge base…", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
            return@GlassCard
        }
        val total = stats.bySource.sumOf { it.second }
        MetricRow("Total passages", "%,d".format(total), valueColor = Brand.Plum)
        Hairline()
        MetricRow("Official sources", stats.bySource.size.toString())
        if (stats.ambiguous > 0) {
            Hairline()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Brand.Rose, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${stats.ambiguous} row(s) print several vaccines together — flagged, not guessed at",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.RedInk,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel("By source")
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceChip("All · $total", filter.sourceId == null) { onSource(null) }
            stats.bySource.forEach { (id, count) ->
                val label = shortSource(id) + (if (id.endsWith("-hi")) " हिंदी" else "") + " · $count"
                SourceChip(label, filter.sourceId == id) { onSource(id) }
            }
        }
    }
}

@Composable
private fun SourceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    org.polycare.app.ui.components.ChoiceChip(text, selected, onClick)
}

@Composable
private fun LangChip(text: String, selected: Boolean, onClick: () -> Unit) {
    org.polycare.app.ui.components.ChoiceChip(text, selected, onClick)
}

@Composable
private fun PassageCard(item: KnowledgeItem) {
    GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(shortSource(item.sourceId) + " · p${item.page}", Brand.Blush, Brand.Plum)
            Tag(langLabel(item.lang), Brand.PinkMist, Brand.InkMuted)
            if (item.quality == "table-ambiguous") {
                Tag("Check schedule", Color(0xFFFDEBEE), Brand.Red)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.Ink,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun langLabel(lang: String) = if (lang == "hi") "हिंदी" else "EN"
