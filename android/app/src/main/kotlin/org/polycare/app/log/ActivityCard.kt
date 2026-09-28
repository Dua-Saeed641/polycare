package org.polycare.app.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.theme.Brand
import org.polycare.common.EventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ActivityViewModel @Inject constructor(log: FileEventLog) : ViewModel() {
    val recent = log.recent
}

/** The newest on-device events: what ran, how long it took, what failed. */
@Composable
fun ActivityCard(limit: Int = 15, viewModel: ActivityViewModel = hiltViewModel()) {
    val events by viewModel.recent.collectAsStateWithLifecycle()
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        if (events.isEmpty()) {
            Text("No activity yet.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
            return@GlassCard
        }
        events.take(limit).forEachIndexed { i, e ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(7.dp)
                        .background(
                            when (e.level) {
                                EventLog.Level.INFO -> Brand.Positive
                                EventLog.Level.WARN -> Brand.Rose
                                EventLog.Level.ERROR -> Brand.Red
                            },
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(e.message, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink, modifier = Modifier.weight(1f))
                        Text(time.format(Date(e.wallMs)), style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
                    }
                    Text(
                        e.category.name + if (e.fields.isEmpty()) "" else " · " + e.fields.entries.joinToString(" · ") { "${it.key} ${it.value}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.InkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
