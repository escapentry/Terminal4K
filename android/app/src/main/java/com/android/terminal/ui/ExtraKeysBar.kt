package com.android.terminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.terminal.model.TerminalTheme

private data class ExtraKey(
    val label: String,
    val sequence: String? = null,
    val isCtrl: Boolean = false,
    val isAlt: Boolean = false
)

private val EXTRA_KEYS = listOf(
    ExtraKey("ESC", sequence = "\u001b"),
    ExtraKey("TAB", sequence = "\t"),
    ExtraKey("CTRL", isCtrl = true),
    ExtraKey("ALT", isAlt = true),
    ExtraKey("↑", sequence = "\u001b[A"),
    ExtraKey("↓", sequence = "\u001b[B"),
    ExtraKey("←", sequence = "\u001b[D"),
    ExtraKey("→", sequence = "\u001b[C"),
    ExtraKey("/", sequence = "/"),
    ExtraKey("-", sequence = "-"),
    ExtraKey("|", sequence = "|"),
    ExtraKey("~", sequence = "~"),
    ExtraKey("HOME", sequence = "\u001b[H"),
    ExtraKey("END", sequence = "\u001b[F")
)

@Composable
fun ExtraKeysBar(
    theme: TerminalTheme,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendSequence: (String) -> Unit
) {
    HorizontalDivider(color = theme.chromeBorderColor, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.chromeSurfaceColor)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EXTRA_KEYS.forEach { key ->
            val armed = (key.isCtrl && ctrlArmed) || (key.isAlt && altArmed)
            Surface(
                onClick = {
                    when {
                        key.isCtrl -> onToggleCtrl()
                        key.isAlt -> onToggleAlt()
                        key.sequence != null -> onSendSequence(key.sequence)
                    }
                },
                shape = RoundedCornerShape(6.dp),
                color = if (armed) theme.accentColor else theme.chromeBgColor,
                contentColor = if (armed) theme.accentTextColor else theme.chromeTextColor,
                border = BorderStroke(
                    1.dp,
                    if (armed) theme.accentColor else theme.chromeBorderColor
                ),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .widthIn(min = 42.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = key.label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
