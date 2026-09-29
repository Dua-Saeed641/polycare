package org.polycare.app.households

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.LabelledField
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.StatusPill
import org.polycare.app.ui.components.PrimaryButton
import org.polycare.app.ui.components.SecondaryButton
import org.polycare.app.ui.components.ToggleRow
import org.polycare.app.ui.theme.Brand

private val Accent = Brand.Pink

/**
 * M3: household and member records with consent capture. In-memory MVP — see
 * [HouseholdsRepository]'s doc comment for why, and what M4 changes about it. A member can only
 * be added once the household's consent checkbox was checked when it was created; there is no
 * way to add one without it, matching invariant 7 in the UI, not just in the repository.
 */
@Composable
fun HouseholdsScreen(contentPadding: PaddingValues, onBack: () -> Unit, viewModel: HouseholdsViewModel = hiltViewModel()) {
    val households by viewModel.households.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    var expandedId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader("Households", Accent, onBack = onBack)
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("Families & visits", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "Only the households you register on this phone are stored — nothing here syncs anywhere.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.InkMuted,
            )
        }

        item { AddHouseholdCard(onAdd = { head, village, consent -> viewModel.addHousehold(head, village, consent) }) }

        if (households.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("No households registered yet.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
                }
            }
        }

        items(households, key = { it.id }) { household ->
            HouseholdCard(
                household = household,
                members = members.filter { it.householdId == household.id },
                expanded = expandedId == household.id,
                onToggle = { expandedId = if (expandedId == household.id) null else household.id },
                onAddMember = { name, age, relation -> viewModel.addMember(household.id, name, age, relation) },
            )
        }
    }
}

@Composable
private fun AddHouseholdCard(onAdd: (String, String, Boolean) -> Unit) {
    var head by remember { mutableStateOf("") }
    var village by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }

    GlassCard(Modifier.fillMaxWidth(), accent = Accent) {
        SectionLabel("Register a household", color = Accent)
        Spacer(Modifier.height(12.dp))
        LabelledField("Head of household", head) { head = it }
        Spacer(Modifier.height(10.dp))
        LabelledField("Village / area", village) { village = it }
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            "This household has given consent for their information to be recorded",
            consent, { consent = it }, accent = Accent,
        )
        Spacer(Modifier.height(8.dp))
        val canAdd = head.isNotBlank() && village.isNotBlank() && consent
        PrimaryButton(
            "Add household",
            onClick = {
                onAdd(head, village, consent)
                head = ""; village = ""; consent = false
            },
            enabled = canAdd, accent = Accent,
        )
        if (head.isNotBlank() && village.isNotBlank() && !consent) {
            Spacer(Modifier.height(8.dp))
            Text("Consent is required before a household can be registered.", style = MaterialTheme.typography.labelSmall, color = Brand.Red)
        }
    }
}

@Composable
private fun HouseholdCard(
    household: Household,
    members: List<Member>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAddMember: (String, Int, String) -> Boolean,
) {
    GlassCard(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Collapse household" else "Open household", role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(household.headOfHousehold, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
                Text(household.village, style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill("${members.size} member${if (members.size == 1) "" else "s"}", dot = Accent)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = Brand.InkMuted,
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))
            members.forEach { member ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${member.name} · ${member.relation}", style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
                    Text("${member.age}y", style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
                }
            }
            if (members.isNotEmpty()) Spacer(Modifier.height(8.dp))
            AddMemberRow(onAdd = onAddMember)
        }
    }
}

@Composable
private fun AddMemberRow(onAdd: (String, Int, String) -> Boolean) {
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }

    Column {
        LabelledField("Member name", name) { name = it }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelledField("Age", age, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { age = it.filter(Char::isDigit) }
            LabelledField("Relation", relation, modifier = Modifier.weight(1f)) { relation = it }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            "Add member",
            onClick = {
                if (onAdd(name, age.toIntOrNull() ?: 0, relation.ifBlank { "Member" })) {
                    name = ""; age = ""; relation = ""
                }
            },
            icon = Icons.Outlined.PersonAdd, enabled = name.isNotBlank(), accent = Accent,
        )
    }
}

