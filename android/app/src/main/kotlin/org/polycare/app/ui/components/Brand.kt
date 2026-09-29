package org.polycare.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.polycare.app.R
import org.polycare.app.ui.theme.Brand
import org.polycare.app.ui.theme.TenorSans

/** Clover + POLYCARE, set like the banner. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, logoSize: Dp = 28.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.polycare_logo), contentDescription = null, modifier = Modifier.size(logoSize))
        Text(
            "POLYCARE",
            fontFamily = TenorSans,
            fontSize = (logoSize.value * 0.72f).sp,
            letterSpacing = (logoSize.value * 0.06f).sp,
            color = Brand.Ink,
        )
    }
}

/**
 * Frosted card that lets the orbs glow through at the edges — brought back after a first attempt
 * replaced it with a solid opaque card over an "AI-generated" complaint that turned out to be
 * about something else (see `BrandBackground`'s doc comment). [accent] tints the glow shadow and
 * left edge — a cheap, consistent way to give each screen's cards a signature colour from the
 * brand palette (Ask=Plum, Triage=Rose, Search=Magenta, …) instead of every card looking
 * identical. Left `null` for the neutral default.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    accent: Color? = null,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.shadow(
            elevation = 10.dp,
            shape = shape,
            ambientColor = (accent ?: Brand.Plum).copy(alpha = 0.14f),
            spotColor = (accent ?: Brand.Plum).copy(alpha = 0.18f),
        ),
        shape = shape,
        color = Brand.Glass,
        border = BorderStroke(1.dp, Brand.White),
        shadowElevation = 0.dp,
    ) {
        if (accent != null) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
                Column(Modifier.padding(padding), content = content)
            }
        } else {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

/** Small tracked uppercase label used above sections. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Brand.InkMuted) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelMedium, color = color)
}

/** Rounded status chip with a coloured dot. */
@Composable
fun StatusPill(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Brand.Glass, CircleShape)
            .border(1.dp, Brand.Line, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brand.Ink)
    }
}

/** Label on the left, value on the right, hairline below. */
@Composable
fun MetricRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Brand.Ink) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Brand.LineSoft))
}

/** Plain white-filled text field with a hairline border — the form-field look Households/Scan
 * both use, so a form doesn't need to hand-roll `TextFieldDefaults.colors(...)` every time. */
@Composable
fun LabelledField(label: String, value: String, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().border(1.dp, Brand.Line, MaterialTheme.shapes.medium),
        placeholder = { Text(label, color = Brand.InkMuted) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Brand.White,
            unfocusedContainerColor = Brand.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
