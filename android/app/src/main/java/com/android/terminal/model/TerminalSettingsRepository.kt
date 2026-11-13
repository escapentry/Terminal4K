package com.android.terminal.model

import android.content.Context

class TerminalSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("com.android.terminal.settings", Context.MODE_PRIVATE)

    fun load(): TerminalSettings {
        return TerminalSettings(
            autoFontSize = prefs.getBoolean("autoFontSize", true),
            manualFontSizeSp = prefs.getFloat("manualFontSizeSp", 14.0f),
            autoFontOffsetSp = prefs.getFloat("autoFontOffsetSp", 0.0f),
            typeface = enumOrDefault(prefs.getString("typeface", null), MonoTypeface.DEFAULT_MONO),
            lineHeightMultiplier = prefs.getFloat("lineHeightMultiplier", 1.25f),
            boldBright = prefs.getBoolean("boldBright", true),
            themeId = prefs.getString("themeId", "android-dark") ?: "android-dark",
            cursorStyle = enumOrDefault(prefs.getString("cursorStyle", null), CursorStyle.BLOCK),
            cursorBlink = prefs.getBoolean("cursorBlink", true),
            scrollbackLines = prefs.getInt("scrollbackLines", 5000),
            backspaceMode = enumOrDefault(prefs.getString("backspaceMode", null), BackspaceMode.DEL),
            bellMode = enumOrDefault(prefs.getString("bellMode", null), BellMode.VISUAL),
            extraKeysMode = enumOrDefault(prefs.getString("extraKeysMode", null), ExtraKeysMode.AUTO)
        )
    }

    fun save(settings: TerminalSettings) {
        prefs.edit()
            .putBoolean("autoFontSize", settings.autoFontSize)
            .putFloat("manualFontSizeSp", settings.manualFontSizeSp)
            .putFloat("autoFontOffsetSp", settings.autoFontOffsetSp)
            .putString("typeface", settings.typeface.name)
            .putFloat("lineHeightMultiplier", settings.lineHeightMultiplier)
            .putBoolean("boldBright", settings.boldBright)
            .putString("themeId", settings.themeId)
            .putString("cursorStyle", settings.cursorStyle.name)
            .putBoolean("cursorBlink", settings.cursorBlink)
            .putInt("scrollbackLines", settings.scrollbackLines)
            .putString("backspaceMode", settings.backspaceMode.name)
            .putString("bellMode", settings.bellMode.name)
            .putString("extraKeysMode", settings.extraKeysMode.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T {
        if (name == null) return fallback
        return try {
            enumValueOf<T>(name)
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }
}
