package org.polycare.app.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ScanUi {
    data object Idle : ScanUi
    data object Recognizing : ScanUi
    data class Done(val result: OcrEngine.Result) : ScanUi
    data class Failed(val reason: String) : ScanUi
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocr: OcrEngine,
) : ViewModel() {
    private val _ui = MutableStateFlow<ScanUi>(ScanUi.Idle)
    val ui: StateFlow<ScanUi> = _ui.asStateFlow()

    var pendingCaptureUri: Uri? = null
        private set

    fun preparePhotoUri(): Uri {
        val file = CaptureUtils.newPhotoFile(context)
        val uri = CaptureUtils.photoUri(context, file)
        pendingCaptureUri = uri
        return uri
    }

    fun onImageChosen(uri: Uri) {
        _ui.value = ScanUi.Recognizing
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { CaptureUtils.loadForOcr(context, uri) }
            if (loaded == null) {
                _ui.value = ScanUi.Failed("Could not read that image")
                return@launch
            }
            val (bitmap, degrees) = loaded
            _ui.value = runCatching { ocr.recognize(bitmap, degrees) }
                .fold({ ScanUi.Done(it) }, { ScanUi.Failed(it.message ?: "OCR failed") })
        }
    }

    /**
     * Debug builds only: reads a file already on the phone, bypassing the camera/gallery picker
     * (the picker needs a tap; this doesn't). See `--es ocr_image_path` in MainActivity.
     */
    fun onDebugImagePath(path: String) {
        _ui.value = ScanUi.Recognizing
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            if (bitmap == null) {
                _ui.value = ScanUi.Failed("Could not decode $path")
                return@launch
            }
            _ui.value = runCatching { ocr.recognize(bitmap) }
                .fold({ ScanUi.Done(it) }, { ScanUi.Failed(it.message ?: "OCR failed") })
        }
    }
}
