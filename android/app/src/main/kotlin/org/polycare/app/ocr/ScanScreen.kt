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
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.SectionLabel
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(Brand.Glass, CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Brand.Ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            SectionLabel("Scan", color = Brand.Plum)
        }

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
                Text(state.reason, style = MaterialTheme.typography.titleMedium, color = Brand.Rose)
            }
            is ScanUi.Done -> ResultCard(state.result)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.clickable(onClick = onClick), padding = 18.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(40.dp).background(Brand.PinkMist, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Brand.Plum, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
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
