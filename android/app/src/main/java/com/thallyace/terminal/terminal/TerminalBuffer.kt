package com.thallyace.terminal.terminal

import com.thallyace.terminal.model.TerminalTheme
import com.thallyace.terminal.model.TerminalThemes
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

data class TerminalCell(
    var ch: Char = ' ',
    var fgColor: Int = COLOR_DEFAULT,
    var bgColor: Int = COLOR_DEFAULT,
    var bold: Boolean = false,
    var underline: Boolean = false,
    var inverse: Boolean = false
) {
    fun reset() {
        ch = ' '
        fgColor = COLOR_DEFAULT
        bgColor = COLOR_DEFAULT
        bold = false
        underline = false
        inverse = false
    }

    fun copyFrom(other: TerminalCell) {
        ch = other.ch
        fgColor = other.fgColor
        bgColor = other.bgColor
        bold = other.bold
        underline = other.underline
        inverse = other.inverse
    }

    companion object {
        const val COLOR_DEFAULT = Int.MIN_VALUE
        const val COLOR_INDEX_FLAG = 0x10000000
    }
}

class TerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private var maxScrollback: Int = 5000,
    private val onResponse: (ByteArray) -> Unit,
    private val onBell: () -> Unit,
    private val onUpdated: () -> Unit
) {
    val lock = Any()

    var cols: Int = max(10, initialCols)
        private set
    var rows: Int = max(2, initialRows)
        private set
    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set
    var cursorVisible: Boolean = true
        private set

    private var scrollTop: Int = 0
    private var scrollBottom: Int = rows - 1

    private var mainGrid: Array<Array<TerminalCell>> = createGrid(cols, rows)
    private var altGrid: Array<Array<TerminalCell>>? = null
    private var usingAltScreen: Boolean = false

    private val scrollback = ArrayDeque<Array<TerminalCell>>()
    private var savedCursorRow = 0
    private var savedCursorCol = 0

    // Current SGR pen attributes
    private var curFg: Int = TerminalCell.COLOR_DEFAULT
    private var curBg: Int = TerminalCell.COLOR_DEFAULT
    private var curBold: Boolean = false
    private var curUnderline: Boolean = false
    private var curInverse: Boolean = false

    // Incremental UTF-8 decoder
    private val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes = ByteArray(0)

    // ANSI escape parser state
    private enum class ParseState { NORMAL, ESC, CSI, OSC, OSC_ESC }
    private var parseState = ParseState.NORMAL
    private val csiParams = StringBuilder()

    private fun createRow(c: Int): Array<TerminalCell> = Array(c) { TerminalCell() }

    private fun createGrid(c: Int, r: Int): Array<Array<TerminalCell>> = Array(r) { createRow(c) }

    private fun activeGrid(): Array<Array<TerminalCell>> =
        if (usingAltScreen && altGrid != null) altGrid!! else mainGrid

    fun setMaxScrollback(lines: Int) {
        synchronized(lock) {
            maxScrollback = max(100, lines)
            while (scrollback.size > maxScrollback) {
                scrollback.removeFirst()
            }
        }
        onUpdated()
    }

    fun getScrollbackCount(): Int = synchronized(lock) {
        if (usingAltScreen) 0 else scrollback.size
    }

    fun getRowAt(scrollOffsetFromBottom: Int, screenRow: Int): Array<TerminalCell>? {
        synchronized(lock) {
            if (usingAltScreen) {
                val grid = activeGrid()
                return if (screenRow in 0 until rows) grid[screenRow] else null
            }
            val sbCount = scrollback.size
            val clampedOffset = scrollOffsetFromBottom.coerceIn(0, sbCount)
            val virtualIndex = sbCount - clampedOffset + screenRow
            return when {
                virtualIndex in 0 until sbCount -> scrollback[virtualIndex]
                virtualIndex - sbCount in 0 until rows -> mainGrid[virtualIndex - sbCount]
                else -> null
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            scrollback.clear()
            val grid = activeGrid()
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    grid[r][c].reset()
                }
            }
            cursorRow = 0
            cursorCol = 0
        }
        onUpdated()
    }

    fun resize(newCols: Int, newRows: Int) {
        val safeCols = max(10, newCols)
        val safeRows = max(2, newRows)
        synchronized(lock) {
            if (safeCols == cols && safeRows == rows) return
            val scrollOff = if (safeRows < rows) max(0, cursorRow - (safeRows - 1)) else 0
            if (!usingAltScreen && scrollOff > 0) {
                for (r in 0 until scrollOff) {
                    val archived = Array(cols) { idx -> TerminalCell().apply { copyFrom(mainGrid[r][idx]) } }
                    scrollback.addLast(archived)
                    while (scrollback.size > maxScrollback) {
                        scrollback.removeFirst()
                    }
                }
            }
            mainGrid = resizeGrid(
                oldGrid = mainGrid,
                oldCols = cols,
                oldRows = rows,
                newCols = safeCols,
                newRows = safeRows,
                rowOffset = if (!usingAltScreen) scrollOff else 0
            )
            altGrid = altGrid?.let {
                resizeGrid(
                    oldGrid = it,
                    oldCols = cols,
                    oldRows = rows,
                    newCols = safeCols,
                    newRows = safeRows,
                    rowOffset = if (usingAltScreen) scrollOff else 0
                )
            }
            cols = safeCols
            rows = safeRows
            scrollTop = 0
            scrollBottom = rows - 1
            cursorRow = (cursorRow - scrollOff).coerceIn(0, rows - 1)
            cursorCol = cursorCol.coerceIn(0, cols - 1)
        }
        onUpdated()
    }

    private fun resizeGrid(
        oldGrid: Array<Array<TerminalCell>>,
        oldCols: Int,
        oldRows: Int,
        newCols: Int,
        newRows: Int,
        rowOffset: Int
    ): Array<Array<TerminalCell>> {
        val newGrid = createGrid(newCols, newRows)
        val availableRows = max(0, oldRows - rowOffset)
        val copyRows = min(availableRows, newRows)
        val copyCols = min(oldCols, newCols)
        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) {
                newGrid[r][c].copyFrom(oldGrid[r + rowOffset][c])
            }
        }
        return newGrid
    }

    fun appendBytes(data: ByteArray, length: Int) {
        if (length <= 0) return
        val combined = if (pendingBytes.isEmpty()) {
            data.copyOf(length)
        } else {
            val out = ByteArray(pendingBytes.size + length)
            System.arraycopy(pendingBytes, 0, out, 0, pendingBytes.size)
            System.arraycopy(data, 0, out, pendingBytes.size, length)
            out
        }

        val byteBuf = ByteBuffer.wrap(combined)
        val charBuf = CharBuffer.allocate(combined.size + 4)
        utf8Decoder.decode(byteBuf, charBuf, false)

        pendingBytes = if (byteBuf.hasRemaining()) {
            val rem = ByteArray(byteBuf.remaining())
            byteBuf.get(rem)
            rem
        } else {
            ByteArray(0)
        }

        charBuf.flip()
        if (charBuf.hasRemaining()) {
            synchronized(lock) {
                while (charBuf.hasRemaining()) {
                    processChar(charBuf.get())
                }
            }
            onUpdated()
        }
    }

    fun appendLocalLine(text: String) {
        val bytes = "$text\r\n".toByteArray(StandardCharsets.UTF_8)
        appendBytes(bytes, bytes.size)
    }

    fun dumpText(): String {
        synchronized(lock) {
            val sb = StringBuilder()
            if (!usingAltScreen) {
                for (row in scrollback) {
                    appendRowText(sb, row)
                }
            }
            val grid = activeGrid()
            for (row in grid) {
                appendRowText(sb, row)
            }
            return sb.toString().trimEnd()
        }
    }

    private fun appendRowText(sb: StringBuilder, row: Array<TerminalCell>) {
        var lastNonSpace = -1
        for (i in row.indices) {
            if (row[i].ch != ' ') lastNonSpace = i
        }
        for (i in 0..lastNonSpace) {
            sb.append(row[i].ch)
        }
        sb.append('\n')
    }

    private fun processChar(ch: Char) {
        when (parseState) {
            ParseState.NORMAL -> when (ch) {
                '\u001b' -> parseState = ParseState.ESC
                '\u0007' -> onBell()
                '\b' -> cursorCol = max(0, cursorCol - 1)
                '\t' -> cursorCol = min(cols - 1, ((cursorCol / 8) + 1) * 8)
                '\r' -> cursorCol = 0
                '\n', '\u000b', '\u000c' -> lineFeed()
                else -> if (ch.code >= 32) writeChar(ch)
            }

            ParseState.ESC -> when (ch) {
                '[' -> {
                    csiParams.clear()
                    parseState = ParseState.CSI
                }
                ']' -> parseState = ParseState.OSC
                '7' -> {
                    savedCursorRow = cursorRow
                    savedCursorCol = cursorCol
                    parseState = ParseState.NORMAL
                }
                '8' -> {
                    cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                    cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                    parseState = ParseState.NORMAL
                }
                'M' -> {
                    reverseIndex()
                    parseState = ParseState.NORMAL
                }
                'c' -> {
                    resetTerminal()
                    parseState = ParseState.NORMAL
                }
                else -> parseState = ParseState.NORMAL
            }

            ParseState.CSI -> {
                if (ch.code in 0x40..0x7E) {
                    executeCsi(ch, csiParams.toString())
                    parseState = ParseState.NORMAL
                } else {
                    csiParams.append(ch)
                }
            }

            ParseState.OSC -> {
                when (ch) {
                    '\u0007' -> parseState = ParseState.NORMAL
                    '\u001b' -> parseState = ParseState.OSC_ESC
                }
            }

            ParseState.OSC_ESC -> {
                parseState = if (ch == '\\') ParseState.NORMAL else ParseState.OSC
            }
        }
    }

    private fun writeChar(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        val cell = activeGrid()[cursorRow][cursorCol]
        cell.ch = ch
        cell.fgColor = curFg
        cell.bgColor = curBg
        cell.bold = curBold
        cell.underline = curUnderline
        cell.inverse = curInverse
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun scrollUp(lines: Int) {
        val count = max(1, lines)
        val grid = activeGrid()
        repeat(count) {
            if (!usingAltScreen && scrollTop == 0 && scrollBottom == rows - 1) {
                val archived = Array(cols) { idx -> TerminalCell().apply { copyFrom(grid[0][idx]) } }
                scrollback.addLast(archived)
                while (scrollback.size > maxScrollback) {
                    scrollback.removeFirst()
                }
            }
            for (r in scrollTop until scrollBottom) {
                for (c in 0 until cols) {
                    grid[r][c].copyFrom(grid[r + 1][c])
                }
            }
            for (c in 0 until cols) {
                grid[scrollBottom][c].reset()
            }
        }
    }

    private fun scrollDown(lines: Int) {
        val count = max(1, lines)
        val grid = activeGrid()
        repeat(count) {
            for (r in scrollBottom downTo scrollTop + 1) {
                for (c in 0 until cols) {
                    grid[r][c].copyFrom(grid[r - 1][c])
                }
            }
            for (c in 0 until cols) {
                grid[scrollTop][c].reset()
            }
        }
    }

    private fun executeCsi(cmd: Char, rawParams: String) {
        val isPrivate = rawParams.startsWith("?")
        val clean = if (isPrivate) rawParams.substring(1) else rawParams
        val parts = if (clean.isEmpty()) emptyList() else clean.split(";")
        fun p(idx: Int, defaultVal: Int): Int =
            parts.getOrNull(idx)?.toIntOrNull() ?: defaultVal

        val grid = activeGrid()

        if (isPrivate) {
            when (cmd) {
                'h', 'l' -> {
                    val enable = (cmd == 'h')
                    for (token in parts) {
                        when (token.toIntOrNull()) {
                            25 -> cursorVisible = enable
                            1049, 47, 1047 -> switchAltScreen(enable)
                        }
                    }
                }
            }
            return
        }

        when (cmd) {
            'A' -> cursorRow = max(scrollTop, cursorRow - max(1, p(0, 1)))
            'B' -> cursorRow = min(scrollBottom, cursorRow + max(1, p(0, 1)))
            'C' -> cursorCol = min(cols - 1, cursorCol + max(1, p(0, 1)))
            'D' -> cursorCol = max(0, cursorCol - max(1, p(0, 1)))
            'E' -> {
                cursorRow = min(scrollBottom, cursorRow + max(1, p(0, 1)))
                cursorCol = 0
            }
            'F' -> {
                cursorRow = max(scrollTop, cursorRow - max(1, p(0, 1)))
                cursorCol = 0
            }
            'G' -> cursorCol = (max(1, p(0, 1)) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                val r = (max(1, p(0, 1)) - 1).coerceIn(0, rows - 1)
                val c = (max(1, p(1, 1)) - 1).coerceIn(0, cols - 1)
                cursorRow = r
                cursorCol = c
            }
            'J' -> {
                when (p(0, 0)) {
                    0 -> {
                        for (c in cursorCol until cols) grid[cursorRow][c].reset()
                        for (r in cursorRow + 1 until rows) {
                            for (c in 0 until cols) grid[r][c].reset()
                        }
                    }
                    1 -> {
                        for (r in 0 until cursorRow) {
                            for (c in 0 until cols) grid[r][c].reset()
                        }
                        for (c in 0..min(cursorCol, cols - 1)) grid[cursorRow][c].reset()
                    }
                    2, 3 -> {
                        for (r in 0 until rows) {
                            for (c in 0 until cols) grid[r][c].reset()
                        }
                        if (p(0, 0) == 3 && !usingAltScreen) scrollback.clear()
                    }
                }
            }
            'K' -> {
                val safeCol = cursorCol.coerceIn(0, cols - 1)
                when (p(0, 0)) {
                    0 -> for (c in safeCol until cols) grid[cursorRow][c].reset()
                    1 -> for (c in 0..safeCol) grid[cursorRow][c].reset()
                    2 -> for (c in 0 until cols) grid[cursorRow][c].reset()
                }
            }
            'L' -> {
                val count = max(1, p(0, 1))
                val savedTop = scrollTop
                scrollTop = cursorRow
                scrollDown(count)
                scrollTop = savedTop
            }
            'M' -> {
                val count = max(1, p(0, 1))
                val savedTop = scrollTop
                scrollTop = cursorRow
                scrollUp(count)
                scrollTop = savedTop
            }
            'P' -> {
                val count = max(1, p(0, 1))
                val row = grid[cursorRow]
                for (c in cursorCol until cols) {
                    if (c + count < cols) row[c].copyFrom(row[c + count]) else row[c].reset()
                }
            }
            '@' -> {
                val count = max(1, p(0, 1))
                val row = grid[cursorRow]
                for (c in cols - 1 downTo cursorCol + count) {
                    row[c].copyFrom(row[c - count])
                }
                for (c in cursorCol until min(cols, cursorCol + count)) {
                    row[c].reset()
                }
            }
            'S' -> scrollUp(max(1, p(0, 1)))
            'T' -> scrollDown(max(1, p(0, 1)))
            'r' -> {
                val top = (max(1, p(0, 1)) - 1).coerceIn(0, rows - 1)
                val bottom = (max(1, p(1, rows)) - 1).coerceIn(top, rows - 1)
                scrollTop = top
                scrollBottom = bottom
                cursorRow = 0
                cursorCol = 0
            }
            'd' -> cursorRow = (max(1, p(0, 1)) - 1).coerceIn(0, rows - 1)
            'n' -> {
                if (p(0, 0) == 6) {
                    val reply = "\u001b[${cursorRow + 1};${cursorCol + 1}R".toByteArray(StandardCharsets.US_ASCII)
                    onResponse(reply)
                }
            }
            'm' -> applySgr(parts)
        }
    }

    private fun applySgr(parts: List<String>) {
        if (parts.isEmpty()) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < parts.size) {
            val code = parts[i].toIntOrNull() ?: 0
            when (code) {
                0 -> resetAttributes()
                1 -> curBold = true
                4 -> curUnderline = true
                7 -> curInverse = true
                22 -> curBold = false
                24 -> curUnderline = false
                27 -> curInverse = false
                in 30..37 -> curFg = TerminalCell.COLOR_INDEX_FLAG or (code - 30)
                39 -> curFg = TerminalCell.COLOR_DEFAULT
                in 40..47 -> curBg = TerminalCell.COLOR_INDEX_FLAG or (code - 40)
                49 -> curBg = TerminalCell.COLOR_DEFAULT
                in 90..97 -> curFg = TerminalCell.COLOR_INDEX_FLAG or (code - 90 + 8)
                in 100..107 -> curBg = TerminalCell.COLOR_INDEX_FLAG or (code - 100 + 8)
                38, 48 -> {
                    val isFg = (code == 38)
                    val mode = parts.getOrNull(i + 1)?.toIntOrNull()
                    if (mode == 5) {
                        val idx = parts.getOrNull(i + 2)?.toIntOrNull()
                        if (idx != null) {
                            val encoded = TerminalCell.COLOR_INDEX_FLAG or idx.coerceIn(0, 255)
                            if (isFg) curFg = encoded else curBg = encoded
                        }
                        i += 2
                    } else if (mode == 2) {
                        val r = parts.getOrNull(i + 2)?.toIntOrNull()?.coerceIn(0, 255) ?: 255
                        val g = parts.getOrNull(i + 3)?.toIntOrNull()?.coerceIn(0, 255) ?: 255
                        val b = parts.getOrNull(i + 4)?.toIntOrNull()?.coerceIn(0, 255) ?: 255
                        val rgb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        if (isFg) curFg = rgb else curBg = rgb
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun resetAttributes() {
        curFg = TerminalCell.COLOR_DEFAULT
        curBg = TerminalCell.COLOR_DEFAULT
        curBold = false
        curUnderline = false
        curInverse = false
    }

    private fun switchAltScreen(enable: Boolean) {
        if (enable && !usingAltScreen) {
            usingAltScreen = true
            altGrid = createGrid(cols, rows)
            cursorRow = 0
            cursorCol = 0
        } else if (!enable && usingAltScreen) {
            usingAltScreen = false
            altGrid = null
        }
    }

    private fun resetTerminal() {
        usingAltScreen = false
        altGrid = null
        for (r in 0 until rows) {
            for (c in 0 until cols) mainGrid[r][c].reset()
        }
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = 0
        cursorCol = 0
        cursorVisible = true
        resetAttributes()
    }

    companion object {
        fun resolveColor(colorVal: Int, isFg: Boolean, bold: Boolean, boldBright: Boolean, theme: TerminalTheme): Int {
            if (colorVal == TerminalCell.COLOR_DEFAULT) {
                return if (isFg) theme.terminalFg else theme.terminalBg
            }
            if ((colorVal ushr 24) == 0x10) {
                var idx = colorVal and 0xFF
                if (isFg && bold && boldBright && idx in 0..7) {
                    idx += 8
                }
                return TerminalThemes.resolve256Color(idx, theme)
            }
            return colorVal
        }
    }
}
