package org.polycare.app.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.EventLog.Level
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Two script's worth of text out of one photo (M0: "ML Kit reads a sample MCP card, English +
 * Devanagari"). MCP cards, lab reports and prescriptions mix a printed English form with
 * handwritten Hindi notes, and a script-specific recognizer reads its own script far better than
 * a general one reads either — so both run over the whole image and are shown as separate
 * results, rather than guessing which recognizer "wins" per line (that fusion, plus turning
 * these lines into actual household-record fields, is M3's job, once households exist to fill).
 *
 * Both models are ML Kit's on-device recognizers. The Latin model ships inside the app; the
 * Devanagari model is downloaded once via Google Play services the first time it is used on a
 * phone (a real online moment on an otherwise offline feature) and is then cached on-device —
 * see CLAUDE.md / STATUS.md for this caveat.
 */
@Singleton
class OcrEngine @Inject constructor(private val events: EventLog) {

    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    data class Result(val latinText: String, val devanagariText: String, val ms: Long)

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): Result {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        val t0 = System.nanoTime()
        val latinText = runCatching { latin.process(image).await().text }.getOrElse {
            events.record(Category.MODEL, "OCR (Latin) failed", mapOf("error" to it.javaClass.simpleName), Level.ERROR)
            ""
        }
        val devanagariText = runCatching { devanagari.process(image).await().text }.getOrElse {
            events.record(Category.MODEL, "OCR (Devanagari) failed", mapOf("error" to it.javaClass.simpleName), Level.ERROR)
            ""
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        events.record(
            Category.MODEL, "OCR ran",
            mapOf("latinChars" to latinText.length, "devanagariChars" to devanagariText.length, "ms" to ms),
        )
        return Result(latinText, devanagariText, ms)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
