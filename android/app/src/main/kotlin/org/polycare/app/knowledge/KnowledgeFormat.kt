package org.polycare.app.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Small rounded label used on passage cards (Search, Ask, Memory Inspector). */
@Composable
fun Tag(text: String, background: Color, color: Color) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.background(background, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Short display name for a source id, both languages sharing one label. */
fun shortSource(id: String): String = when (id.removeSuffix("-hi")) {
    "asha-module-6" -> "Module 6"
    "asha-module-7" -> "Module 7"
    "asha-induction" -> "Induction module"
    "nis" -> "Immunization schedule"
    else -> id
}
