package org.polycare.app.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.chaos.Chaos
import org.polycare.app.ui.components.ChipRow
import org.polycare.app.ui.components.ChoiceChip
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.SecondaryButton
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.ToggleRow
import org.polycare.app.ui.theme.Brand
import org.polycare.governor.Rung

/** Debug builds only: reproduce the failures the architecture says it survives. See [Chaos]. */
@Composable
fun ChaosPanel() {
    if (!Chaos.enabled) return
    val offline by Chaos.offline.collectAsStateWithLifecycle()
    val drop by Chaos.dropAckedChunk.collectAsStateWithLifecycle()
    val kill by Chaos.killAfterAck.collectAsStateWithLifecycle()
    val skew by Chaos.clockSkewMs.collectAsStateWithLifecycle()
    val rung by Chaos.forcedRung.collectAsStateWithLifecycle()

    SectionLabel("Chaos (debug builds only)")
    Spacer(Modifier.height(12.dp))
    GlassCard(Modifier.fillMaxWidth(), accent = Brand.Red) {
        Text(
            "Each switch breaks something on purpose. After a sync, check that nothing was lost or sent twice.",
            style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted,
        )
        Spacer(Modifier.height(8.dp))
        ToggleRow("Gateway unreachable", offline, { Chaos.offline.value = it }, accent = Brand.Red)
        ToggleRow(
            "Lose the connection after the gateway accepts a chunk", drop, { Chaos.dropAckedChunk.value = it }, accent = Brand.Red,
            supporting = "The cursor is not saved. The next sync re-sends the chunk; the gateway must call it a duplicate.",
        )
        ToggleRow(
            "Kill the app after the gateway accepts a chunk", kill, { Chaos.killAfterAck.value = it }, accent = Brand.Red,
            supporting = "Same moment, but the whole process dies. Reopen the app and sync again.",
        )
        Spacer(Modifier.height(8.dp))
        Text("Clock", style = MaterialTheme.typography.labelLarge, color = Brand.InkMuted)
        Spacer(Modifier.height(6.dp))
        ChipRow {
            listOf("Correct" to 0L, "10 min fast" to 10 * 60_000L, "10 min slow" to -10 * 60_000L).forEach { (label, ms) ->
                ChoiceChip(label, selected = skew == ms, onClick = { Chaos.clockSkewMs.value = ms }, accent = Brand.Red)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Pretend this phone is on rung", style = MaterialTheme.typography.labelLarge, color = Brand.InkMuted)
        Spacer(Modifier.height(6.dp))
        ChipRow {
            ChoiceChip("Real", selected = rung == null, onClick = { Chaos.forcedRung.value = null }, accent = Brand.Red)
            Rung.entries.forEach { r -> ChoiceChip(r.label, selected = rung == r, onClick = { Chaos.forcedRung.value = r }, accent = Brand.Red) }
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Reset all", { Chaos.reset() }, accent = Brand.Red)
    }
    Spacer(Modifier.height(24.dp))
}
