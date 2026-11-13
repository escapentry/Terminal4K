package com.thallyace.terminal.model

import androidx.compose.ui.graphics.Color

data class TerminalTheme(
    val id: String,
    val name: String,
    val isLight: Boolean,
    val chromeBg: Int,
    val chromeSurface: Int,
    val chromeBorder: Int,
    val chromeText: Int,
    val chromeMuted: Int,
    val accent: Int,
    val accentText: Int,
    val terminalBg: Int,
    val terminalFg: Int,
    val cursorColor: Int,
    val palette16: IntArray
) {
    val chromeBgColor: Color get() = Color(chromeBg)
    val chromeSurfaceColor: Color get() = Color(chromeSurface)
    val chromeBorderColor: Color get() = Color(chromeBorder)
    val chromeTextColor: Color get() = Color(chromeText)
    val chromeMutedColor: Color get() = Color(chromeMuted)
    val accentColor: Color get() = Color(accent)
    val accentTextColor: Color get() = Color(accentText)
    val terminalBgColor: Color get() = Color(terminalBg)
}

object TerminalThemes {
    val all: List<TerminalTheme> = listOf(
        TerminalTheme(
            id = "android-dark",
            name = "Android Material Dark",
            isLight = false,
            chromeBg = 0xFF101214.toInt(),
            chromeSurface = 0xFF181B1F.toInt(),
            chromeBorder = 0xFF272B31.toInt(),
            chromeText = 0xFFECEFF4.toInt(),
            chromeMuted = 0xFF94A3B8.toInt(),
            accent = 0xFF26A69A.toInt(),
            accentText = 0xFF081C1A.toInt(),
            terminalBg = 0xFF101214.toInt(),
            terminalFg = 0xFFECEFF4.toInt(),
            cursorColor = 0xFF26A69A.toInt(),
            palette16 = intArrayOf(
                0xFF1E2229.toInt(), 0xFFEF5350.toInt(), 0xFF66BB6A.toInt(), 0xFFFFCA28.toInt(),
                0xFF42A5F5.toInt(), 0xFFAB47BC.toInt(), 0xFF26C6DA.toInt(), 0xFFECEFF4.toInt(),
                0xFF64748B.toInt(), 0xFFFF8A80.toInt(), 0xFFA5D6A7.toInt(), 0xFFFFE082.toInt(),
                0xFF82B1FF.toInt(), 0xFFEA80FC.toInt(), 0xFF84FFFF.toInt(), 0xFFFFFFFF.toInt()
            )
        ),
        TerminalTheme(
            id = "oled-black",
            name = "Pure Black (OLED)",
            isLight = false,
            chromeBg = 0xFF000000.toInt(),
            chromeSurface = 0xFF0D0E10.toInt(),
            chromeBorder = 0xFF222428.toInt(),
            chromeText = 0xFFF4F4F5.toInt(),
            chromeMuted = 0xFFA1A1AA.toInt(),
            accent = 0xFF38BDF8.toInt(),
            accentText = 0xFF082F49.toInt(),
            terminalBg = 0xFF000000.toInt(),
            terminalFg = 0xFFF4F4F5.toInt(),
            cursorColor = 0xFF38BDF8.toInt(),
            palette16 = intArrayOf(
                0xFF18181B.toInt(), 0xFFF87171.toInt(), 0xFF4ADE80.toInt(), 0xFFFACC15.toInt(),
                0xFF60A5FA.toInt(), 0xFFC084FC.toInt(), 0xFF22D3EE.toInt(), 0xFFE4E4E7.toInt(),
                0xFF71717A.toInt(), 0xFFFCA5A5.toInt(), 0xFF86EFAC.toInt(), 0xFFFDE047.toInt(),
                0xFF93C5FD.toInt(), 0xFFD8B4FE.toInt(), 0xFF67E8F9.toInt(), 0xFFFFFFFF.toInt()
            )
        ),
        TerminalTheme(
            id = "solarized-dark",
            name = "Solarized Dark",
            isLight = false,
            chromeBg = 0xFF002B36.toInt(),
            chromeSurface = 0xFF073642.toInt(),
            chromeBorder = 0xFF154B59.toInt(),
            chromeText = 0xFFEEE8D5.toInt(),
            chromeMuted = 0xFF93A1A1.toInt(),
            accent = 0xFF2AA198.toInt(),
            accentText = 0xFF002B36.toInt(),
            terminalBg = 0xFF002B36.toInt(),
            terminalFg = 0xFF93A1A1.toInt(),
            cursorColor = 0xFF2AA198.toInt(),
            palette16 = intArrayOf(
                0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
                0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
                0xFF586E75.toInt(), 0xFFCB4B16.toInt(), 0xFF93A1A1.toInt(), 0xFF839496.toInt(),
                0xFF657B83.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt()
            )
        ),
        TerminalTheme(
            id = "solarized-light",
            name = "Solarized Light",
            isLight = true,
            chromeBg = 0xFFFDF6E3.toInt(),
            chromeSurface = 0xFFEEE8D5.toInt(),
            chromeBorder = 0xFFD8D0BA.toInt(),
            chromeText = 0xFF073642.toInt(),
            chromeMuted = 0xFF586E75.toInt(),
            accent = 0xFF268BD2.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            terminalBg = 0xFFFDF6E3.toInt(),
            terminalFg = 0xFF475B62.toInt(),
            cursorColor = 0xFF268BD2.toInt(),
            palette16 = intArrayOf(
                0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
                0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
                0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
                0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt()
            )
        ),
        TerminalTheme(
            id = "green-phosphor",
            name = "Green Phosphor",
            isLight = false,
            chromeBg = 0xFF060D08.toInt(),
            chromeSurface = 0xFF0B160E.toInt(),
            chromeBorder = 0xFF183020.toInt(),
            chromeText = 0xFF86EFAC.toInt(),
            chromeMuted = 0xFF4ADE80.toInt(),
            accent = 0xFF22C55E.toInt(),
            accentText = 0xFF052E16.toInt(),
            terminalBg = 0xFF060D08.toInt(),
            terminalFg = 0xFF4ADE80.toInt(),
            cursorColor = 0xFF86EFAC.toInt(),
            palette16 = intArrayOf(
                0xFF0A1A0F.toInt(), 0xFFF87171.toInt(), 0xFF4ADE80.toInt(), 0xFFA3E635.toInt(),
                0xFF34D399.toInt(), 0xFF6EE7B7.toInt(), 0xFF2DD4BF.toInt(), 0xFFBBF7D0.toInt(),
                0xFF166534.toInt(), 0xFFFCA5A5.toInt(), 0xFF86EFAC.toInt(), 0xFFBEF264.toInt(),
                0xFF6EE7B7.toInt(), 0xFFA7F3D0.toInt(), 0xFF5EEAD4.toInt(), 0xFFDCFCE7.toInt()
            )
        ),
        TerminalTheme(
            id = "amber-crt",
            name = "Amber Monochrome",
            isLight = false,
            chromeBg = 0xFF110B04.toInt(),
            chromeSurface = 0xFF1B1207.toInt(),
            chromeBorder = 0xFF38250D.toInt(),
            chromeText = 0xFFFDE68A.toInt(),
            chromeMuted = 0xFFD97706.toInt(),
            accent = 0xFFF59E0B.toInt(),
            accentText = 0xFF1C1003.toInt(),
            terminalBg = 0xFF110B04.toInt(),
            terminalFg = 0xFFFBBF24.toInt(),
            cursorColor = 0xFFFDE68A.toInt(),
            palette16 = intArrayOf(
                0xFF1F1407.toInt(), 0xFFF87171.toInt(), 0xFFFCD34D.toInt(), 0xFFFBBF24.toInt(),
                0xFFF59E0B.toInt(), 0xFFFB923C.toInt(), 0xFFFDBA74.toInt(), 0xFFFEF3C7.toInt(),
                0xFF92400E.toInt(), 0xFFFCA5A5.toInt(), 0xFFFDE68A.toInt(), 0xFFFEF08A.toInt(),
                0xFFFCD34D.toInt(), 0xFFFDBA74.toInt(), 0xFFFED7AA.toInt(), 0xFFFFFBEB.toInt()
            )
        )
    )

    fun byId(id: String): TerminalTheme = all.firstOrNull { it.id == id } ?: all.first()

    fun resolve256Color(index: Int, theme: TerminalTheme): Int {
        if (index in 0..15) {
            return theme.palette16[index]
        }
        if (index in 16..231) {
            val c = index - 16
            val r = (c / 36) * 51
            val g = ((c / 6) % 6) * 51
            val b = (c % 6) * 51
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        if (index in 232..255) {
            val gray = 8 + (index - 232) * 10
            return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        return theme.terminalFg
    }
}
