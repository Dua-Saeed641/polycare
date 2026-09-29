package org.polycare.app.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
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
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: EventLog,
) {

    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private val paddleMutex = Mutex()
    private var latinPaddle: PaddleOCR? = null
    private var devanagariPaddle: PaddleOCR? = null

    data class Result(val latinText: String, val devanagariText: String, val ms: Long, val engine: String)

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): Result {
        val started = System.nanoTime()
        if (hasPaddleModels()) {
            runCatching { recognizeWithPaddle(bitmap, rotationDegrees) }.onSuccess { (latin, devanagari) ->
                val ms = (System.nanoTime() - started) / 1_000_000
                events.record(Category.MODEL, "PaddleOCR PP-OCRv5 ran", mapOf("latinChars" to latin.length, "devanagariChars" to devanagari.length, "ms" to ms))
                return Result(latin, devanagari, ms, "PaddleOCR PP-OCRv5")
            }.onFailure {
                events.record(Category.MODEL, "PaddleOCR failed; falling back to ML Kit", mapOf("error" to it.javaClass.simpleName), Level.WARN)
            }
        }
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        val latinText = runCatching { latin.process(image).await().text }.getOrElse {
            events.record(Category.MODEL, "OCR (Latin) failed", mapOf("error" to it.javaClass.simpleName), Level.ERROR)
            ""
        }
        val devanagariText = runCatching { devanagari.process(image).await().text }.getOrElse {
            events.record(Category.MODEL, "OCR (Devanagari) failed", mapOf("error" to it.javaClass.simpleName), Level.ERROR)
            ""
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        events.record(
            Category.MODEL, "OCR ran",
            mapOf("latinChars" to latinText.length, "devanagariChars" to devanagariText.length, "ms" to ms),
        )
        return Result(latinText, devanagariText, ms, "ML Kit fallback")
    }

    private fun hasPaddleModels(): Boolean = runCatching {
        context.assets.open("models/ocr/det/inference.onnx").close()
        context.assets.open("models/ocr/latin/inference.onnx").close()
        context.assets.open("models/ocr/latin/inference.yml").close()
        context.assets.open("models/ocr/devanagari/inference.onnx").close()
        context.assets.open("models/ocr/devanagari/inference.yml").close()
    }.isSuccess

    private suspend fun recognizeWithPaddle(bitmap: Bitmap, rotationDegrees: Int): Pair<String, String> {
        check(OpenCVUtils.init(context)) { "OpenCV native runtime did not initialize" }
        val rotated = if (rotationDegrees % 360 == 0) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
        )
        try {
            return paddleMutex.withLock {
                val config = PaddleOCRConfig(detLimitSideLen = 960, detLimitType = "max", recScoreThresh = 0.0f, recBatchSize = 1)
                suspend fun recognize(script: String, current: PaddleOCR?): Pair<String, PaddleOCR> {
                    val engine = current ?: PaddleOCR.create(
                        context = context,
                        config = config,
                        engineConfig = EngineConfig(numThreads = 4),
                        detModelAssetPath = "models/ocr/det/inference.onnx",
                        recModelAssetPath = "models/ocr/$script/inference.onnx",
                        recConfigAssetPath = "models/ocr/$script/inference.yml",
                    )
                    return engine.recognize(rotated).results.joinToString("\n") { it.text } to engine
                }
                val (latinText, loadedLatin) = recognize("latin", latinPaddle)
                latinPaddle = loadedLatin
                val (devanagariText, loadedDevanagari) = recognize("devanagari", devanagariPaddle)
                devanagariPaddle = loadedDevanagari
                latinText to devanagariText
            }
        } finally {
            if (rotated !== bitmap) rotated.recycle()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
