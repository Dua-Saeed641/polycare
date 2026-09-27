package org.polycare.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.polycare.app.ui.theme.Brand
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** An orb as in the banner: orchid core, pink body, red rim, fading out. Positions are fractions. */
private data class Orb(val x: Float, val y: Float, val radius: Float, val drift: Float, val phase: Float)

private val HeroOrbs = listOf(
    Orb(x = 0.05f, y = 0.10f, radius = 0.55f, drift = 0.020f, phase = 0.0f),
    Orb(x = 0.92f, y = 0.02f, radius = 0.42f, drift = 0.025f, phase = 1.7f),
    Orb(x = 0.80f, y = 0.42f, radius = 0.50f, drift = 0.018f, phase = 3.1f),
    Orb(x = 0.28f, y = 0.55f, radius = 0.22f, drift = 0.030f, phase = 4.4f),
)

/**
 * The banner backdrop: paper background, drifting blurred orbs and film grain.
 * [intensity] lowers the orbs on dense screens so text stays readable.
 */
@Composable
fun BrandBackground(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val grain = rememberGrain()
    val t by rememberInfiniteTransition(label = "orbs").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbPhase",
    )
    Box(modifier.fillMaxSize().background(Brand.Paper)) {
        Canvas(Modifier.fillMaxSize()) {
            HeroOrbs.forEach { drawOrb(it, t, intensity) }
            // Veil so content below the hero sits on calm paper.
            drawRect(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.45f to Brand.Paper.copy(alpha = 0.35f),
                    0.75f to Brand.Paper.copy(alpha = 0.92f),
                    1.0f to Brand.Paper,
                ),
            )
            drawRect(ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)))
        }
        content()
    }
}

private fun DrawScope.drawOrb(orb: Orb, t: Float, intensity: Float) {
    val w = size.width
    val r = orb.radius * w
    val center = Offset(
        x = (orb.x + orb.drift * cos(t + orb.phase)) * w,
        y = (orb.y + orb.drift * sin(t + orb.phase)) * w,
    )
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to Brand.Orchid.copy(alpha = 0.95f * intensity),
            0.45f to Brand.Orchid.copy(alpha = 0.85f * intensity),
            0.70f to Brand.Pink.copy(alpha = 0.75f * intensity),
            0.86f to Brand.Red.copy(alpha = 0.55f * intensity),
            1.00f to Brand.Red.copy(alpha = 0f),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

@Composable
private fun rememberGrain(): ImageBitmap = remember {
    val size = 160
    val random = Random(7)
    val pixels = IntArray(size * size) {
        val v = random.nextInt(256)
        val alpha = random.nextInt(10, 26)
        (alpha shl 24) or (v shl 16) or (v shl 8) or v
    }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
