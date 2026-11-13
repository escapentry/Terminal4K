package com.thallyace.terminal.model

enum class CursorStyle(val label: String) {
    BLOCK("Block"),
    UNDERLINE("Underline"),
    BAR("Bar")
}

enum class BackspaceMode(val label: String) {
    DEL("DEL (0x7F)"),
    CTRL_H("Ctrl-H (0x08)")
}

enum class BellMode(val label: String) {
    VISUAL("Visual"),
    HAPTIC("Haptic"),
    OFF("Off")
}

enum class ExtraKeysMode(val label: String) {
    AUTO("Auto"),
    ALWAYS("Show"),
    NEVER("Hide")
}

enum class MonoTypeface(val label: String) {
    DEFAULT_MONO("System Monospace"),
    SANS_MONO("Clean Sans Mono"),
    SERIF_MONO("Slab Serif Mono")
}

enum class SessionStatus {
    CONNECTING,
    RUNNING,
    EXITED
}

data class TerminalSettings(
    val autoFontSize: Boolean = true,
    val manualFontSizeSp: Float = 14.0f,
    val autoFontOffsetSp: Float = 0.0f,
    val typeface: MonoTypeface = MonoTypeface.DEFAULT_MONO,
    val lineHeightMultiplier: Float = 1.25f,
    val boldBright: Boolean = true,
    val themeId: String = "android-dark",
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val cursorBlink: Boolean = true,
    val scrollbackLines: Int = 5000,
    val backspaceMode: BackspaceMode = BackspaceMode.DEL,
    val bellMode: BellMode = BellMode.VISUAL,
    val extraKeysMode: ExtraKeysMode = ExtraKeysMode.AUTO
)

data class SessionInfo(
    val pid: Int = -1,
    val status: SessionStatus = SessionStatus.CONNECTING,
    val exitCode: Int? = null,
    val startedAtMillis: Long = 0L,
    val endedAtMillis: Long? = null,
    val cols: Int = 80,
    val rows: Int = 24,
    val shellPath: String = "/system/bin/sh"
)

data class ComputedFontMetrics(
    val fontSizeSp: Float,
    val deviceProfile: String,
    val targetColsHint: Int
)
