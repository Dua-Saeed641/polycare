package org.polycare.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.polycare.app.ui.theme.Brand

/** Smallest touch target Android accessibility guidance allows; every tappable here meets it. */
val MinTouch: Dp = 48.dp

/** Height shared by every full-width action button so stacked buttons line up. */
val ButtonHeight: Dp = 52.dp

/** Round icon-only button with a spoken label. The icon carries the description, so screen
 * readers announce "Back, button" rather than an unlabelled tap area. */
@Composable
fun AppIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Brand.Plum,
    container: Color = tint.copy(alpha = 0.10f),
    size: Dp = MinTouch,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Top of every secondary screen: one 48dp back (or menu) button, the screen's name as a proper
 * heading, and an optional trailing slot. Replaces seven hand-rolled copies that were 36-40dp
 * and had no role.
 */
@Composable
fun ScreenHeader(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            // Ink on a tinted disc: pink/rose accents on paper fail 3:1 contrast as an icon colour.
            AppIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, tint = Brand.Ink, container = accent.copy(alpha = 0.18f))
            Spacer(Modifier.width(12.dp))
        } else if (onMenu != null) {
            AppIconButton(Icons.Outlined.Menu, "Open menu", onMenu, tint = Brand.Ink, container = Brand.Glass)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Brand.Ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/** Filled, full-width action. [accent] is the fill; disabled looks (and is announced) disabled. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Color = Brand.Plum,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = ButtonHeight)
            .clip(MaterialTheme.shapes.large)
            .background(if (enabled) accent else Brand.Line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (enabled) Brand.Paper else Brand.InkMuted
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

/** Outlined companion to [PrimaryButton]; same height so a pair sits on one baseline. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Color = Brand.Plum,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = ButtonHeight)
            .clip(MaterialTheme.shapes.large)
            .background(Brand.White)
            .border(BorderStroke(1.dp, if (enabled) accent.copy(alpha = 0.45f) else Brand.Line), MaterialTheme.shapes.large)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (enabled) accent else Brand.InkMuted
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

/** A labelled checkbox whose whole row is the touch target and is announced as one control. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Brand.Plum,
    supporting: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = Brand.InkMuted, checkmarkColor = Brand.Paper),
        )
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
        }
    }
}

/** Single-choice pill (filters, tabs inside a screen). 48dp tall, announced as selected/not. */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Brand.Plum,
) {
    Box(
        modifier
            .heightIn(min = MinTouch)
            .clip(CircleShape)
            .background(if (selected) accent else Brand.White)
            .border(1.dp, if (selected) accent else Brand.Line, CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp)
            .defaultMinSize(minWidth = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Brand.Paper else Brand.Ink,
        )
    }
}

/** Wrapping row of [ChoiceChip]s with even gaps in both directions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Tappable card row (list items, tiles): merges children into one focusable, labelled button. */
fun Modifier.tapTarget(onClickLabel: String? = null, onClick: () -> Unit): Modifier =
    this.heightIn(min = MinTouch).clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
