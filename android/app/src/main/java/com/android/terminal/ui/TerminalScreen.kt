package com.android.terminal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.terminal.model.BellMode
import com.android.terminal.model.ExtraKeysMode
import com.android.terminal.model.SessionStatus
import com.android.terminal.model.TerminalSettings
import com.android.terminal.model.TerminalSettingsRepository
import com.android.terminal.model.TerminalThemes
import com.android.terminal.service.TerminalService
import com.android.terminal.terminal.FontAutoSizer
import kotlinx.coroutines.delay

@Composable
fun TerminalScreen(
    service: TerminalService,
    onThemeChanged: (isLight: Boolean, barColor: Int) -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val settingsRepo = remember { TerminalSettingsRepository(context) }
    var settings by remember { mutableStateOf(settingsRepo.load()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var bellFlash by remember { mutableStateOf(false) }

    val session by service.sessionInfo.collectAsState()
    val revision by service.bufferRevision.collectAsState()
    val theme = remember(settings.themeId) { TerminalThemes.byId(settings.themeId) }

    LaunchedEffect(theme.id) {
        onThemeChanged(theme.isLight, theme.chromeSurface)
    }

    LaunchedEffect(service) {
        service.bellEvents.collect {
            when (settings.bellMode) {
                BellMode.VISUAL -> {
                    bellFlash = true
                    delay(120)
                    bellFlash = false
                }
                BellMode.HAPTIC -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                BellMode.OFF -> Unit
            }
        }
    }

    val computedMetrics = remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        density.density,
        settings.autoFontOffsetSp,
        settings.autoFontSize,
        settings.manualFontSizeSp
    ) {
        if (settings.autoFontSize) {
            FontAutoSizer.compute(
                screenWidthDp = configuration.screenWidthDp,
                screenHeightDp = configuration.screenHeightDp,
                density = density.density,
                offsetSp = settings.autoFontOffsetSp
            )
        } else {
            FontAutoSizer.compute(
                screenWidthDp = configuration.screenWidthDp,
                screenHeightDp = configuration.screenHeightDp,
                density = density.density,
                offsetSp = 0f
            ).copy(fontSizeSp = settings.manualFontSizeSp, deviceProfile = "Manual")
        }
    }

    val isTouchFormFactor = configuration.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
    val showExtraKeys = when (settings.extraKeysMode) {
        ExtraKeysMode.ALWAYS -> true
        ExtraKeysMode.NEVER -> false
        ExtraKeysMode.AUTO -> isTouchFormFactor
    }

    val shellShortName = session.shellPath.substringAfterLast('/').ifEmpty { "sh" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.chromeBgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Compact Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(theme.chromeSurfaceColor)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = theme.accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Terminal",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.chromeTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val statusMeta = if (session.status == SessionStatus.EXITED) {
                    "exited (${session.exitCode ?: 0})"
                } else {
                    "$shellShortName · ${session.cols}×${session.rows} · ${computedMetrics.fontSizeSp}sp"
                }
                Text(
                    text = "· $statusMeta",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = theme.chromeMutedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val text = service.buffer.dumpText()
                        if (text.isNotEmpty()) {
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal output", text))
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy output",
                        tint = theme.chromeMutedColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!clip.isNullOrEmpty() && session.status == SessionStatus.RUNNING) {
                            service.writeInput(clip)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentPaste,
                        contentDescription = "Paste",
                        tint = theme.chromeMutedColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(onClick = { service.buffer.clearAll() }) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = "Clear",
                        tint = theme.chromeMutedColor,
                        modifier = Modifier.size(19.dp)
                    )
                }

                IconButton(onClick = { settingsOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = theme.chromeTextColor,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = theme.chromeBorderColor, thickness = 1.dp)

        // Main Terminal Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            TerminalCanvas(
                modifier = Modifier.fillMaxSize(),
                buffer = service.buffer,
                revision = revision,
                theme = theme,
                settings = settings,
                resolvedFontSizeSp = computedMetrics.fontSizeSp,
                ctrlArmed = ctrlArmed,
                altArmed = altArmed,
                sessionExited = session.status == SessionStatus.EXITED,
                onSendBytes = { bytes -> service.writeInput(bytes) },
                onGridResize = { cols, rows -> service.resizeGrid(cols, rows) },
                onConsumeModifiers = {
                    ctrlArmed = false
                    altArmed = false
                },
                onRestartRequested = { service.restartSession() }
            )

            if (bellFlash) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (theme.isLight) Color.Black.copy(alpha = 0.08f)
                            else Color.White.copy(alpha = 0.08f)
                        )
                )
            }
        }

        if (session.status == SessionStatus.EXITED) {
            HorizontalDivider(color = theme.chromeBorderColor, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.chromeSurfaceColor)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Service stopped · exit code ${session.exitCode ?: 0}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = theme.chromeMutedColor
                )
                Button(
                    onClick = { service.restartSession() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.accentColor,
                        contentColor = theme.accentTextColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("New Session", fontSize = 12.sp)
                }
            }
        } else if (showExtraKeys) {
            ExtraKeysBar(
                theme = theme,
                ctrlArmed = ctrlArmed,
                altArmed = altArmed,
                onToggleCtrl = { ctrlArmed = !ctrlArmed },
                onToggleAlt = { altArmed = !altArmed },
                onSendSequence = { seq ->
                    var out = seq
                    if (ctrlArmed && out.length == 1) {
                        val c = out[0]
                        if (c in 'a'..'z') out = (c.code - 'a'.code + 1).toChar().toString()
                    }
                    if (altArmed) {
                        out = "\u001b$out"
                    }
                    if (ctrlArmed || altArmed) {
                        ctrlArmed = false
                        altArmed = false
                    }
                    service.writeInput(out)
                }
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            settings = settings,
            theme = theme,
            fontMetrics = computedMetrics,
            session = session,
            onDismiss = { settingsOpen = false },
            onUpdateSettings = { next ->
                settings = next
                settingsRepo.save(next)
                service.updateScrollbackLimit(next.scrollbackLines)
            },
            onResetDefaults = {
                val defaults = TerminalSettings()
                settings = defaults
                settingsRepo.save(defaults)
                service.updateScrollbackLimit(defaults.scrollbackLines)
            },
            onSendSigint = { service.sendSigint() },
            onRestartService = {
                service.restartSession()
                settingsOpen = false
            }
        )
    }
}
