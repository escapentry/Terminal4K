package com.thallyace.terminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thallyace.terminal.model.TerminalTheme

private data class ExtraKey(
    val label: String,
    val sequence: String? = null,
    val isCtrl: Boolean = false,
    val isAlt: Boolean = false
)

private val EXTRA_KEYS_2_ROWS = listOf(
    listOf(
        ExtraKey("ESC", sequence = "\u001b"),
        ExtraKey("TAB", sequence = "\t"),
        ExtraKey("CTRL", isCtrl = true),
        ExtraKey("ALT", isAlt = true),
        ExtraKey("/", sequence = "/"),
        ExtraKey("-", sequence = "-"),
        ExtraKey("|", sequence = "|")
    ),
    listOf(
        ExtraKey("~", sequence = "~"),
        ExtraKey("HOME", sequence = "\u001b[H"),
        ExtraKey("END", sequence = "\u001b[F"),
        ExtraKey("←", sequence = "\u001b[D"),
        ExtraKey("↓", sequence = "\u001b[B"),
        ExtraKey("↑", sequence = "\u001b[A"),
        ExtraKey("→", sequence = "\u001b[C")
    )
)

private val EXTRA_KEYS_3_ROWS = listOf(
    listOf(
        ExtraKey("ESC", sequence = "\u001b"),
        ExtraKey("TAB", sequence = "\t"),
        ExtraKey("CTRL", isCtrl = true),
        ExtraKey("ALT", isAlt = true),
        ExtraKey("~", sequence = "~")
    ),
    listOf(
        ExtraKey("/", sequence = "/"),
        ExtraKey("-", sequence = "-"),
        ExtraKey("|", sequence = "|"),
        ExtraKey("HOME", sequence = "\u001b[H"),
        ExtraKey("END", sequence = "\u001b[F")
    ),
    listOf(
        ExtraKey("←", sequence = "\u001b[D"),
        ExtraKey("↓", sequence = "\u001b[B"),
        ExtraKey("↑", sequence = "\u001b[A"),
        ExtraKey("→", sequence = "\u001b[C")
    )
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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.chromeSurfaceColor)
            .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        val rows = if (maxWidth < 290.dp) EXTRA_KEYS_3_ROWS else EXTRA_KEYS_2_ROWS
        val keyShape = RoundedCornerShape(5.dp)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            rows.forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowKeys.forEach { key ->
                        val armed = (key.isCtrl && ctrlArmed) || (key.isAlt && altArmed)
                        Surface(
                            shape = keyShape,
                            color = if (armed) theme.accentColor else theme.chromeBgColor,
                            contentColor = if (armed) theme.accentTextColor else theme.chromeTextColor,
                            border = BorderStroke(
                                1.dp,
                                if (armed) theme.accentColor else theme.chromeBorderColor
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(keyShape)
                                .clickable {
                                    when {
                                        key.isCtrl -> onToggleCtrl()
                                        key.isAlt -> onToggleAlt()
                                        key.sequence != null -> onSendSequence(key.sequence)
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key.label,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
