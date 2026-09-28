package org.polycare.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.polycare.app.R

/**
 * Tenor Sans, the banner typeface, is deliberately rationed to two roles: big display/headline
 * moments and small tracked-uppercase labels (`SectionLabel`, `StatusPill`) — the two places a
 * distinctive face reads as intentional. Everything in between (titles, body, metrics) uses the
 * system sans, so the brand face stands out instead of blurring into "the app's only font."
 */
val TenorSans = FontFamily(Font(R.font.tenor_sans, FontWeight.Normal))

private val Body = FontFamily.Default

val BrandTypography = Typography(
    displayLarge = TextStyle(fontFamily = TenorSans, fontSize = 48.sp, lineHeight = 54.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = TenorSans, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.25).sp),
    displaySmall = TextStyle(fontFamily = TenorSans, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = TenorSans, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = TenorSans, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = TenorSans, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Body, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Body, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Body, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = TenorSans, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp),
    labelSmall = TextStyle(fontFamily = TenorSans, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp),
)
