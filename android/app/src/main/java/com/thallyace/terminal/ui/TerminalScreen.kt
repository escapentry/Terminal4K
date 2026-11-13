package com.thallyace.terminal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.thallyace.terminal.model.BellMode
import com.thallyace.terminal.model.ExtraKeysMode
import com.thallyace.terminal.model.RootAccessState
import com.thallyace.terminal.model.SessionStatus
import com.thallyace.terminal.model.TerminalSettingsRepository
import com.thallyace.terminal.model.TerminalTheme
import com.thallyace.terminal.model.TerminalThemes
import com.thallyace.terminal.service.TerminalService
import com.thallyace.terminal.terminal.FontAutoSizer
import kotlinx.coroutines.delay

@Composable
fun TerminalScreen(
    service: TerminalService,
    onThemeChanged: (isLight: Boolean, barColor: Int) -> Unit,
    onFinalShellExit: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val settingsRepo = remember { TerminalSettingsRepository(context) }
    var settings by remember { mutableStateOf(settingsRepo.load()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var backMenuOpen by remember { mutableStateOf(false) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var bellFlash by remember { mutableStateOf(false) }
    var surfaceViewRef by remember { mutableStateOf<TerminalSurfaceView?>(null) }

    val session by service.sessionInfo.collectAsState()
    val revision by service.bufferRevision.collectAsState()
    val theme = remember(settings.themeId) { TerminalThemes.byId(settings.themeId) }

    // Pressing Back brings up the Options Menu list instead of exiting;
    // exiting happens only on final exit command in the sh binary.
    BackHandler(enabled = true) {
        when {
            settingsOpen -> settingsOpen = false
            backMenuOpen -> backMenuOpen = false
            else -> backMenuOpen = true
        }
    }

    LaunchedEffect(session.finalShExited) {
        if (session.finalShExited) {
            onFinalShellExit()
        }
    }

    LaunchedEffect(theme.id) {
        onThemeChanged(theme.isLight, theme.terminalBg)
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

    val menuIconTint = if (theme.isLight) Color(0xFF0F172A) else Color.White

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.terminalBgColor)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Full-Screen Terminal Viewport (top bar removed in favor of Back Options Menu)
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
                onRestartRequested = { service.restartSession() },
                onSurfaceReady = { view -> surfaceViewRef = view }
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

    if (backMenuOpen) {
        BackOptionsDialog(
            theme = theme,
            iconTint = menuIconTint,
            rootState = session.rootState,
            deviceName = session.deviceName,
            isRunning = session.status == SessionStatus.RUNNING,
            onDismiss = { backMenuOpen = false },
            onShowKeyboard = {
                backMenuOpen = false
                surfaceViewRef?.showSoftKeyboard()
            },
            onRequestRoot = {
                backMenuOpen = false
                service.requestRootShell()
            },
            onOpenSettings = {
                backMenuOpen = false
                settingsOpen = true
            },
            onCopyOutput = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = service.buffer.dumpText()
                if (text.isNotEmpty()) {
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal output", text))
                }
                backMenuOpen = false
            },
            onPaste = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val primary = clipboard?.primaryClip
                val clip = if (primary != null && primary.itemCount > 0) {
                    primary.getItemAt(0)?.coerceToText(context)?.toString()
                } else {
                    null
                }
                if (!clip.isNullOrEmpty() && session.status == SessionStatus.RUNNING) {
                    service.writeInput(clip)
                }
                backMenuOpen = false
            },
            onRestart = {
                backMenuOpen = false
                service.restartSession()
            }
        )
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
            onSendSigint = { service.sendSigint() },
            onRequestRoot = {
                service.requestRootShell()
                settingsOpen = false
            }
        )
    }
}

@Composable
private fun BackOptionsDialog(
    theme: TerminalTheme,
    iconTint: Color,
    rootState: RootAccessState,
    deviceName: String,
    isRunning: Boolean,
    onDismiss: () -> Unit,
    onShowKeyboard: () -> Unit,
    onRequestRoot: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopyOutput: () -> Unit,
    onPaste: () -> Unit,
    onRestart: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = theme.chromeSurfaceColor,
            contentColor = theme.chromeTextColor,
            border = BorderStroke(1.dp, theme.chromeBorderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Terminal Options",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = iconTint
                )

                HorizontalDivider(color = theme.chromeBorderColor)

                BackMenuRow(
                    icon = Icons.Filled.Keyboard,
                    label = "Show Keyboard",
                    iconTint = iconTint,
                    theme = theme,
                    onClick = onShowKeyboard
                )
                BackMenuRow(
                    icon = Icons.Filled.AdminPanelSettings,
                    label = if (rootState == RootAccessState.ROOT_GRANTED) {
                        "Root Shell Active ($deviceName:#)"
                    } else {
                        "Elevate to Root (su)"
                    },
                    iconTint = iconTint,
                    theme = theme,
                    onClick = onRequestRoot
                )
                BackMenuRow(
                    icon = Icons.Filled.Settings,
                    label = "Terminal Settings",
                    iconTint = iconTint,
                    theme = theme,
                    onClick = onOpenSettings
                )
                BackMenuRow(
                    icon = Icons.Filled.ContentCopy,
                    label = "Copy Terminal Output",
                    iconTint = iconTint,
                    theme = theme,
                    onClick = onCopyOutput
                )
                BackMenuRow(
                    icon = Icons.Filled.ContentPaste,
                    label = "Paste from Clipboard",
                    iconTint = iconTint,
                    theme = theme,
                    enabled = isRunning,
                    onClick = onPaste
                )
                BackMenuRow(
                    icon = Icons.Filled.Refresh,
                    label = "Restart Session",
                    iconTint = iconTint,
                    theme = theme,
                    onClick = onRestart
                )

                HorizontalDivider(color = theme.chromeBorderColor)

                Text(
                    text = "Execute \"exit\" command to close",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = theme.chromeMutedColor
                )

                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, theme.chromeBorderColor),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 42.dp)
                ) {
                    Text("Resume Terminal", fontSize = 13.sp, color = iconTint)
                }
            }
        }
    }
}

@Composable
private fun BackMenuRow(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    theme: TerminalTheme,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = theme.chromeBgColor,
        border = BorderStroke(1.dp, theme.chromeBorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) iconTint else theme.chromeMutedColor,
                modifier = Modifier.size(19.dp)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) theme.chromeTextColor else theme.chromeMutedColor
            )
        }
    }
}
