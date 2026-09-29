package org.polycare.app.ocr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
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
import org.polycare.app.ui.components.ToggleRow
import org.polycare.app.ui.theme.Brand

@Composable
fun ScanScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    debugImagePath: String? = null,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(debugImagePath) { debugImagePath?.let(viewModel::onDebugImagePath) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) viewModel.pendingCaptureUri?.let(viewModel::onImageChosen)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::onImageChosen)
    }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePicture.launch(viewModel.preparePhotoUri())
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader("Scan", Brand.Positive, onBack = onBack)

        Spacer(Modifier.height(20.dp))
        Text("MCP cards & reports", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Reads printed and handwritten text on-device, English and Devanagari (Hindi script).",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.InkMuted,
        )

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Take photo", Icons.Outlined.CameraAlt, Modifier.weight(1f)) {
                val hasPermission = androidx.core.content.ContextCompat
                    .checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) takePicture.launch(viewModel.preparePhotoUri()) else requestCamera.launch(Manifest.permission.CAMERA)
            }
            ActionButton("From gallery", Icons.Outlined.PhotoLibrary, Modifier.weight(1f)) { pickImage.launch("image/*") }
        }

        Spacer(Modifier.height(24.dp))
        when (val state = ui) {
            ScanUi.Idle -> Unit
            ScanUi.Recognizing -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Brand.Plum, strokeWidth = 2.dp)
            }
            is ScanUi.Failed -> GlassCard(Modifier.fillMaxWidth()) {
                Text(state.reason, style = MaterialTheme.typography.titleMedium, color = Brand.Red)
            }
            is ScanUi.Done -> {
                McpConfirmationCard(
                    candidates = state.candidates,
                    saved = state.saved,
                    onSave = { name, village, consent, age, notes ->
                        viewModel.saveAsHousehold(name, village, consent, age, notes)
                    },
                )
                Spacer(Modifier.height(20.dp))
                ResultCard(state.result)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), padding = 18.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(40.dp).background(Brand.PinkMist, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Brand.Plum, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
        }
    }
}

@Composable
private fun McpConfirmationCard(
    candidates: McpFieldExtractor.Candidates,
    saved: Boolean,
    onSave: (String, String, Boolean, Int?, String?) -> Boolean,
) {
    var name by remember(candidates.name) { mutableStateOf(candidates.name.orEmpty()) }
    var village by remember(candidates.village) { mutableStateOf(candidates.village.orEmpty()) }
    var age by remember(candidates.age) { mutableStateOf(candidates.age?.toString().orEmpty()) }
    var notes by remember(candidates.clinicalNotes) { mutableStateOf(candidates.clinicalNotes.orEmpty()) }
    var consent by remember { mutableStateOf(false) }

    GlassCard(Modifier.fillMaxWidth(), accent = Brand.Positive) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Confirmed Fields", color = Brand.Positive)
            StatusPill(candidates.docType, dot = Brand.Positive)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Review pre-filled fields from OCR before recording into household memory:",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.InkMuted,
        )

        Spacer(Modifier.height(12.dp))
        LabelledField("Head of household / Mother", name) { name = it }
        Spacer(Modifier.height(10.dp))
        LabelledField("Village / Area", village) { village = it }
        Spacer(Modifier.height(10.dp))
        LabelledField("Age", age, keyboardType = KeyboardType.Number) { age = it.filter(Char::isDigit) }
        Spacer(Modifier.height(10.dp))
        LabelledField("Clinical notes / Rx", notes, singleLine = false) { notes = it }

        Spacer(Modifier.height(12.dp))
        ToggleRow("Family gave verbal consent to store this health record on this phone", consent, { consent = it }, accent = Brand.Positive)

        Spacer(Modifier.height(14.dp))
        if (saved) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brand.Positive.copy(alpha = 0.12f), MaterialTheme.shapes.large)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Brand.Positive, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Saved to household & visit records", style = MaterialTheme.typography.titleSmall, color = Brand.Positive)
            }
        } else {
            val canSave = name.isNotBlank() && village.isNotBlank() && consent
            PrimaryButton(
                "Save to Households",
                onClick = { onSave(name, village, consent, age.toIntOrNull(), notes.ifBlank { null }) },
                enabled = canSave, accent = Brand.Positive,
            )
            if (name.isNotBlank() && village.isNotBlank() && !consent) {
                Spacer(Modifier.height(8.dp))
                Text("Consent is required before saving to household memory.", style = MaterialTheme.typography.labelSmall, color = Brand.Red)
            }
        }
    }
}

@Composable
private fun ResultCard(result: OcrEngine.Result) {
    Column {
        if (result.latinText.isBlank() && result.devanagariText.isBlank()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("No text recognised.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
            }
            return
        }
        if (result.latinText.isNotBlank()) {
            SectionLabel("Latin script (English)")
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text(result.latinText, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
            }
            Spacer(Modifier.height(16.dp))
        }
        if (result.devanagariText.isNotBlank()) {
            SectionLabel("Devanagari script (हिंदी)")
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text(result.devanagariText, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
            }
            Spacer(Modifier.height(16.dp))
        }
        Text("Recognised on-device in ${result.ms} ms.", style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
    }
}
