package org.polycare.app.conflicts

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.PrimaryButton
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.SecondaryButton
import org.polycare.app.ui.theme.Brand
import org.polycare.common.conflict.Resolution
import javax.inject.Inject

@HiltViewModel
class ConflictsViewModel @Inject constructor(private val repo: ConflictsRepository) : ViewModel() {
    val conflicts: StateFlow<List<ConflictRecord>> = repo.conflicts
    fun exportJson() = repo.exportJson()
    fun importJson(text: String) = repo.importJson(text)
    fun resolve(id: String, choice: Resolution) = repo.resolve(id, choice)
    fun reopen(id: String) = repo.reopen(id)
}

private val Accent = Brand.Magenta

@Composable
fun ConflictInboxScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: ConflictsViewModel = hiltViewModel(),
) {
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readText(context, uri)
        val s = if (text == null) null else viewModel.importJson(text)
        message = when {
            s == null -> "Could not read that file."
            s.invalid -> "That is not a PolyCare households file."
            else -> "Imported: ${s.added} new, ${s.conflicts} to review, ${s.unchanged} already the same" +
                if (s.refusedNoConsent > 0) ", ${s.refusedNoConsent} skipped (no consent)." else "."
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportJson().toByteArray()) } != null
        }.getOrDefault(false)
        message = if (ok) "Exported households that gave consent." else "Could not write the file."
    }

    val open = conflicts.filter { it.open }
    val resolved = conflicts.filterNot { it.open }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader("Conflict inbox", Accent, onBack = onBack)
        Spacer(Modifier.height(12.dp))
        Text("When two records disagree, both are kept. You decide.", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Import a teammate's file to compare records for the same family. Nothing is overwritten until you choose, and every choice can be undone.",
            style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted,
        )

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                "Import file", { importPicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                Modifier.weight(1f), icon = Icons.Outlined.FileDownload, accent = Accent,
            )
            SecondaryButton(
                "Export mine", { exportPicker.launch("polycare-households.json") },
                Modifier.weight(1f), icon = Icons.Outlined.FileUpload, accent = Accent,
            )
        }
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("To review · ${open.size}")
        Spacer(Modifier.height(10.dp))
        if (open.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Nothing to review.", style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
                Text("Conflicts appear here after you import a teammate's file.", style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                open.forEach { c -> OpenConflict(c, viewModel::resolve) }
            }
        }

        if (resolved.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Resolved · ${resolved.size}")
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth(), padding = 4.dp) {
                resolved.forEachIndexed { i, c ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.subject} · ${c.fieldLabel}", style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
                            Text(c.resolution?.label.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
                        }
                        SecondaryButton("Undo", { viewModel.reopen(c.id) }, Modifier.width(120.dp), icon = Icons.Outlined.Undo, accent = Accent)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun OpenConflict(c: ConflictRecord, onResolve: (String, Resolution) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), accent = Accent) {
        Text(c.subject, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
        Text(c.fieldLabel, style = MaterialTheme.typography.labelLarge, color = Brand.InkMuted)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Version("On this phone", c.localValue, Modifier.weight(1f))
            Version("From ${c.incomingAuthor.take(8)}", c.incomingValue, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Keep mine", { onResolve(c.id, Resolution.KEEP_LOCAL) }, Modifier.weight(1f), accent = Accent)
            PrimaryButton("Use theirs", { onResolve(c.id, Resolution.KEEP_INCOMING) }, Modifier.weight(1f), accent = Accent)
        }
        if (!c.numeric) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Keep both", { onResolve(c.id, Resolution.KEEP_BOTH) }, accent = Accent)
        }
    }
}

@Composable
private fun Version(title: String, value: String, modifier: Modifier) {
    Column(modifier.background(Brand.PinkMist, MaterialTheme.shapes.medium).padding(12.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
        Spacer(Modifier.height(4.dp))
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
    }
}

private fun readText(context: Context, uri: Uri): String? =
    runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
