package com.thallyace.terminal.terminal

import com.thallyace.terminal.model.ComputedFontMetrics
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object FontAutoSizer {
    fun compute(
        screenWidthDp: Int,
        screenHeightDp: Int,
        density: Float,
        offsetSp: Float
    ): ComputedFontMetrics {
        val safeWidth = max(280, screenWidthDp)
        val isLandscape = screenWidthDp > screenHeightDp

        val baseSize: Float
        val profile: String
        val targetCols: Int

        when {
            safeWidth < 520 -> {
                profile = if (isLandscape) "Phone Landscape" else "Phone Portrait"
                targetCols = if (isLandscape) 64 else 48
                val raw = safeWidth / (targetCols * 0.61f)
                baseSize = max(12.5f, min(14.5f, raw))
            }
            safeWidth < 840 -> {
                profile = if (isLandscape) "Compact Landscape" else "Foldable / Tablet"
                targetCols = 80
                val raw = safeWidth / (targetCols * 0.61f)
                baseSize = max(13.5f, min(15.0f, raw))
            }
            else -> {
                profile = if (density >= 2.5f) "HiDPI Tablet / Desktop" else "Large Tablet / Desktop"
                targetCols = 110
                baseSize = if (safeWidth >= 1200) 15.5f else 14.5f
            }
        }

        val adjusted = max(10.0f, min(26.0f, round((baseSize + offsetSp) * 2f) / 2f))
        return ComputedFontMetrics(
            fontSizeSp = adjusted,
            deviceProfile = profile,
            targetColsHint = targetCols
        )
    }
}
