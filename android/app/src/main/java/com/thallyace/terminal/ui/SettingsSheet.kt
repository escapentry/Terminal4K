package com.thallyace.terminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thallyace.terminal.model.BackspaceMode
import com.thallyace.terminal.model.BellMode
import com.thallyace.terminal.model.ComputedFontMetrics
import com.thallyace.terminal.model.CursorStyle
import com.thallyace.terminal.model.ExtraKeysMode
import com.thallyace.terminal.model.MonoTypeface
import com.thallyace.terminal.model.SessionInfo
import com.thallyace.terminal.model.SessionStatus
import com.thallyace.terminal.model.TerminalSettings
import com.thallyace.terminal.model.TerminalTheme
import com.thallyace.terminal.model.TerminalThemes
import kotlinx.coroutines.delay
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: TerminalSettings,
    theme: TerminalTheme,
    fontMetrics: ComputedFontMetrics,
    session: SessionInfo,
    onDismiss: () -> Unit,
    onUpdateSettings: (TerminalSettings) -> Unit,
    onResetDefaults: () -> Unit,
    onSendSigint: () -> Unit,
    onRestartService: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.chromeSurfaceColor,
        contentColor = theme.chromeTextColor,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = theme.accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Terminal Settings",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.chromeTextColor
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close settings",
                        tint = theme.chromeMutedColor
                    )
                }
            }

            HorizontalDivider(color = theme.chromeBorderColor)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Monospace & Auto-Scale
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Monospace & Auto-Scale",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.chromeMutedColor
                        )
                        Text(
                            text = "${fontMetrics.fontSizeSp}sp · ${fontMetrics.deviceProfile}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = theme.accentColor
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Auto-adjust font per device",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Adapts readable monospace size from device width and DPI",
                                fontSize = 12.sp,
                                color = theme.chromeMutedColor
                            )
                        }
                        Switch(
                            checked = settings.autoFontSize,
                            onCheckedChange = {
                                onUpdateSettings(settings.copy(autoFontSize = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = theme.accentColor
                            )
                        )
                    }

                    if (settings.autoFontSize) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Auto scale offset", fontSize = 12.sp, color = theme.chromeMutedColor)
                                val sign = if (settings.autoFontOffsetSp > 0f) "+" else ""
                                Text(
                                    text = "$sign${settings.autoFontOffsetSp}sp",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                            Slider(
                                value = settings.autoFontOffsetSp,
                                onValueChange = { raw ->
                                    val snapped = round(raw * 2f) / 2f
                                    onUpdateSettings(settings.copy(autoFontOffsetSp = snapped))
                                },
                                valueRange = -3f..6f,
                                colors = SliderDefaults.colors(
                                    thumbColor = theme.accentColor,
                                    activeTrackColor = theme.accentColor
                                )
                            )
                        }
                    } else {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Manual font size", fontSize = 12.sp, color = theme.chromeMutedColor)
                                Text(
                                    text = "${settings.manualFontSizeSp}sp",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                            Slider(
                                value = settings.manualFontSizeSp,
                                onValueChange = { raw ->
                                    val snapped = round(raw * 2f) / 2f
                                    onUpdateSettings(settings.copy(manualFontSizeSp = snapped))
                                },
                                valueRange = 10f..26f,
                                colors = SliderDefaults.colors(
                                    thumbColor = theme.accentColor,
                                    activeTrackColor = theme.accentColor
                                )
                            )
                        }
                    }

                    Text("Monospace typeface", fontSize = 12.sp, color = theme.chromeMutedColor)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonoTypeface.entries.forEach { face ->
                            val selected = settings.typeface == face
                            OptionRow(
                                label = face.label,
                                trailing = "0O1lI",
                                selected = selected,
                                theme = theme,
                                onClick = { onUpdateSettings(settings.copy(typeface = face)) }
                            )
                        }
                    }

                    Text("Line height", fontSize = 12.sp, color = theme.chromeMutedColor)
                    SegmentedRow(
                        options = listOf(1.15f to "1.15", 1.25f to "1.25", 1.4f to "1.4"),
                        selected = settings.lineHeightMultiplier,
                        theme = theme,
                        onSelect = { onUpdateSettings(settings.copy(lineHeightMultiplier = it)) }
                    )

                    SwitchRow(
                        title = "Bright bold colors",
                        checked = settings.boldBright,
                        theme = theme,
                        onCheckedChange = { onUpdateSettings(settings.copy(boldBright = it)) }
                    )
                }

                HorizontalDivider(color = theme.chromeBorderColor)

                // 2. Color Scheme & Cursor
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Color Scheme & Cursor",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.chromeMutedColor
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TerminalThemes.all.forEach { item ->
                            val selected = settings.themeId == item.id
                            Surface(
                                onClick = { onUpdateSettings(settings.copy(themeId = item.id)) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) theme.chromeBgColor else Color.Transparent,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) theme.accentColor else theme.chromeBorderColor
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.name, fontSize = 14.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(Color(item.terminalBg), RoundedCornerShape(3.dp))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(Color(item.terminalFg), RoundedCornerShape(3.dp))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(item.accentColor, RoundedCornerShape(3.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text("Cursor style", fontSize = 12.sp, color = theme.chromeMutedColor)
                    SegmentedRow(
                        options = CursorStyle.entries.map { it to it.label },
                        selected = settings.cursorStyle,
                        theme = theme,
                        onSelect = { onUpdateSettings(settings.copy(cursorStyle = it)) }
                    )

                    SwitchRow(
                        title = "Cursor blink",
                        checked = settings.cursorBlink,
                        theme = theme,
                        onCheckedChange = { onUpdateSettings(settings.copy(cursorBlink = it)) }
                    )
                }

                HorizontalDivider(color = theme.chromeBorderColor)

                // 3. Input & Buffer
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Input & Buffer",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.chromeMutedColor
                    )

                    Text("Extra keys toolbar", fontSize = 12.sp, color = theme.chromeMutedColor)
                    SegmentedRow(
                        options = ExtraKeysMode.entries.map { it to it.label },
                        selected = settings.extraKeysMode,
                        theme = theme,
                        onSelect = { onUpdateSettings(settings.copy(extraKeysMode = it)) }
                    )

                    Text("Backspace key sequence", fontSize = 12.sp, color = theme.chromeMutedColor)
                    SegmentedRow(
                        options = BackspaceMode.entries.map { it to it.label },
                        selected = settings.backspaceMode,
                        theme = theme,
                        onSelect = { onUpdateSettings(settings.copy(backspaceMode = it)) }
                    )

                    Text("Terminal bell", fontSize = 12.sp, color = theme.chromeMutedColor)
                    SegmentedRow(
                        options = BellMode.entries.map { it to it.label },
                        selected = settings.bellMode,
                        theme = theme,
                        onSelect = { onUpdateSettings(settings.copy(bellMode = it)) }
                    )

                    Text("Scrollback buffer lines", fontSize = 12.sp, color = theme.chromeMutedColor)
                    SegmentedRow(
                        options = listOf(1000, 3000, 5000, 10000).map { it to it.toString() },
                        selected = settings.scrollbackLines,
                        theme = theme,
                        onSelect = { onUpdateSettings(settings.copy(scrollbackLines = it)) }
                    )
                }

                HorizontalDivider(color = theme.chromeBorderColor)

                // 4. Persistent Service Info & Controls
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Persistent Terminal Service",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.chromeMutedColor
                    )

                    InfoRow("Package", "com.thallyace.terminal", theme)
                    InfoRow(
                        "Service Status",
                        when (session.status) {
                            SessionStatus.RUNNING -> "Active until exit"
                            SessionStatus.EXITED -> "Exited (${session.exitCode ?: 0})"
                            SessionStatus.CONNECTING -> "Starting"
                        },
                        theme
                    )
                    InfoRow("Shell / PID", "${session.shellPath} · ${if (session.pid > 0) session.pid else "—"}", theme)
                    InfoRow("Grid Size", "${session.cols} × ${session.rows}", theme)
                    InfoRow("Uptime", formatUptime(session.startedAtMillis, session.endedAtMillis ?: nowMillis), theme)

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onSendSigint,
                            enabled = session.status == SessionStatus.RUNNING,
                            border = BorderStroke(1.dp, theme.chromeBorderColor),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                        ) {
                            Text("SIGINT (^C)", fontSize = 12.sp, color = theme.chromeTextColor)
                        }

                        Button(
                            onClick = onRestartService,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = theme.accentColor,
                                contentColor = theme.accentTextColor
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Restart", fontSize = 12.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = onResetDefaults,
                        border = BorderStroke(1.dp, theme.chromeBorderColor),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 42.dp)
                    ) {
                        Text("Reset settings to defaults", fontSize = 12.sp, color = theme.chromeMutedColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    trailing: String,
    selected: Boolean,
    theme: TerminalTheme,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) theme.chromeBgColor else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) theme.accentColor else theme.chromeBorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text(trailing, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = theme.chromeMutedColor)
        }
    }
}

@Composable
private fun <T> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    theme: TerminalTheme,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) theme.accentColor else theme.chromeBgColor,
                contentColor = if (isSelected) theme.accentTextColor else theme.chromeTextColor,
                border = BorderStroke(1.dp, if (isSelected) theme.accentColor else theme.chromeBorderColor),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    theme: TerminalTheme,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = theme.accentColor
            )
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, theme: TerminalTheme) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = theme.chromeMutedColor)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = theme.chromeTextColor)
    }
}

private fun formatUptime(startedAt: Long, endAt: Long): String {
    if (startedAt <= 0L) return "0s"
    val totalSec = ((endAt - startedAt).coerceAtLeast(0L)) / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return when {
        h > 0L -> "${h}h ${m}m ${s}s"
        m > 0L -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
