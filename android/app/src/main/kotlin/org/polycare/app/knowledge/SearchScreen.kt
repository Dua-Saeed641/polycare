package org.polycare.app.knowledge

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.theme.Brand

private val Suggestions = listOf(
    "Danger signs in a newborn",
    "ORS कैसे बनाएं",
    "When is BCG given?",
    "गर्भावस्था में खतरे के लक्षण",
    "Iron tablets in pregnancy",
    "बच्चे को दस्त हो तो क्या करें",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    initialQuery: String? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val knowledge by viewModel.knowledgeState.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (initialQuery != null) viewModel.searchNow(initialQuery) else focus.requestFocus()
    }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Brand.Glass).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Brand.Ink, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                SectionLabel("Search guidance", color = Brand.Plum)
            }
        }
        item {
            SearchField(query, viewModel::onQueryChange, { viewModel.searchNow() }, focus)
        }
        item { KnowledgeStatusLine(knowledge, ui) }

        when (val state = ui) {
            SearchUi.Idle -> item {
                Column {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Try")
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Suggestions.forEach { s -> Chip(s) { viewModel.searchNow(s) } }
                    }
                }
            }
            SearchUi.Searching -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Plum, strokeWidth = 2.dp)
                }
            }
            is SearchUi.Unavailable -> item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(state.reason, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Development builds install it with tools/knowledge/push_knowledge.sh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.InkMuted,
                    )
                }
            }
            is SearchUi.Results -> {
                if (state.result.hits.isEmpty()) {
                    item { Text("No matching guidance.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted) }
                }
                items(state.result.hits, key = { it.id }) { hit -> HitCard(hit) }
                item {
                    Text(
                        "From official NHM training material. PolyCare supports decisions; it does not diagnose. When unsure, refer to the ANM or PHC.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.InkMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSubmit: () -> Unit, focus: FocusRequester) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Brand.White)
            .border(1.dp, Brand.Line, CircleShape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = Brand.Plum, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Symptom, medicine, vaccine… English या हिंदी", style = MaterialTheme.typography.bodyLarge, color = Brand.InkMuted, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Brand.Ink),
                cursorBrush = SolidColor(Brand.Plum),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Clear",
                tint = Brand.InkMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun KnowledgeStatusLine(state: KnowledgeRepository.State, ui: SearchUi) {
    val text = when (state) {
        is KnowledgeRepository.State.Ready -> {
            val base = "%,d passages · %d official sources · offline".format(state.points, state.manifest.sources.size)
            if (ui is SearchUi.Results) {
                "${ui.result.hits.size} results · embed %.0f ms · search %.0f ms".format(ui.result.embedMs, ui.result.searchMs)
            } else base
        }
        KnowledgeRepository.State.Loading -> "Opening knowledge base…"
        KnowledgeRepository.State.NotInstalled -> "Knowledge base not installed"
        is KnowledgeRepository.State.Failed -> "Knowledge base unavailable"
    }
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted, modifier = Modifier.padding(start = 6.dp))
}

@Composable
private fun HitCard(hit: KnowledgeHit) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth().animateContentSize().clip(MaterialTheme.shapes.large).clickable { expanded = !expanded }, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(shortSource(hit.sourceId) + " · p${hit.page}", Brand.Blush, Brand.Plum)
            Tag(if (hit.lang == "hi") "हिंदी" else "EN", Brand.PinkMist, Brand.InkMuted)
        }
        if (hit.quality == "table-ambiguous") {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Brand.Rose, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Several vaccines share this printed row. Check the printed schedule.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Rose,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            hit.text,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.Ink,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            hit.title,
            style = MaterialTheme.typography.bodySmall,
            color = Brand.InkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Tag(text: String, background: androidx.compose.ui.graphics.Color, color: androidx.compose.ui.graphics.Color) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.background(background, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Brand.Plum,
        modifier = Modifier
            .clip(CircleShape)
            .background(Brand.Glass)
            .border(1.dp, Brand.Line, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun shortSource(id: String): String = when (id.removeSuffix("-hi")) {
    "asha-module-6" -> "Module 6"
    "asha-module-7" -> "Module 7"
    "asha-induction" -> "Induction module"
    "nis" -> "Immunization schedule"
    else -> id
}
