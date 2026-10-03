package org.polycare.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Colours sampled from assets/banner.png and assets/logo.png. */
object Brand {
    val Paper = Color(0xFFF8F8F8)
    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF0E0A0D)
    val InkMuted = Color(0xFF6B5F68)

    val PlumDeep = Color(0xFF3A0633)
    val Plum = Color(0xFF5E0B53)
    val Magenta = Color(0xFFBC16A6)

    val Blush = Color(0xFFF9DCF1)
    val PinkMist = Color(0xFFFBEEF6)
    val Orchid = Color(0xFFF7DAF8)
    val Pink = Color(0xFFF285C6)
    val Rose = Color(0xFFFB2E66)
    val Red = Color(0xFC1825)

    val Line = Color(0xFFE9DCE5)
    val LineSoft = Color(0xFFF1E8EE)
    val Glass = Color(0xD9FFFFFF)

    val Positive = Color(0xFF1F7A5A)

    /**
     * Text-safe variants of the rose/red accents.
     *
     * [Rose] and [Red] are brand-accurate but reach only 3.48:1 and 3.73:1 on paper, below WCAG
     * AA's 4.5:1 for body text. They stay the fill colour for chips, dots and accent bars, where
     * they are decorative or always sit beside a text label; anything the ASHA actually reads (an
     * overdue date, a high-risk reason, a warning) uses these instead, at 6.6:1 and 5.9:1.
     * Ratios measured and recorded in STATUS.md.
     */
    val RoseInk = Color(0xFFB3003F)
    val RedInk = Color(0xFFC1121F)
}