package org.polycare.app.ocr

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaddleOcrInstrumentedTest {
    @Test
    fun ppOcrV5ReadsEnglishAndDevanagariOffline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("OpenCV native runtime failed", OpenCVUtils.init(context))
        val bitmap = instrumentation.context.assets.open("mcp_sample.png").use { BitmapFactory.decodeStream(it) }
        try {
            val english = recognize(context, bitmap, "latin")
            assertTrue("English recognizer returned no text: $english", english.contains("Mother", ignoreCase = true))
            val devanagari = recognize(context, bitmap, "devanagari")
            assertTrue("Hindi recognizer returned no Devanagari text: $devanagari", devanagari.any { it in '\u0900'..'\u097F' })
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognize(context: android.content.Context, bitmap: android.graphics.Bitmap, script: String): String {
        val engine = PaddleOCR.create(
            context = context,
            config = PaddleOCRConfig(detLimitSideLen = 960, detLimitType = "max", recScoreThresh = 0f),
            engineConfig = EngineConfig(numThreads = 4),
            detModelAssetPath = "models/ocr/det/inference.onnx",
            recModelAssetPath = "models/ocr/$script/inference.onnx",
            recConfigAssetPath = "models/ocr/$script/inference.yml",
        )
        return try { engine.recognize(bitmap).results.joinToString("\n") { it.text } }
        finally { engine.release() }
    }
}
