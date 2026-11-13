package com.android.terminal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.terminal.model.BackspaceMode
import com.android.terminal.model.CursorStyle
import com.android.terminal.model.MonoTypeface
import com.android.terminal.model.TerminalSettings
import com.android.terminal.model.TerminalTheme
import com.android.terminal.terminal.TerminalBuffer
import com.android.terminal.terminal.TerminalCell
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class TerminalSurfaceView(context: Context) : View(context) {

    var buffer: TerminalBuffer? = null
    var theme: TerminalTheme? = null
    var settings: TerminalSettings = TerminalSettings()
    var resolvedFontSizeSp: Float = 14f
    var ctrlArmed: Boolean = false
    var altArmed: Boolean = false
    var sessionExited: Boolean = false

    var onSendBytes: ((ByteArray) -> Unit)? = null
    var onGridResize: ((Int, Int) -> Unit)? = null
    var onConsumeModifiers: (() -> Unit)? = null
    var onRestartRequested: (() -> Unit)? = null

    private var scrollOffsetRows = 0
    private var scrollAccumulatorPx = 0f
    private var cursorBlinkVisible = true

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint()

    private var cellWidth = 20f
    private var cellHeight = 40f
    private var baselineOffset = 30f
    private var lastReportedCols = 0
    private var lastReportedRows = 0

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(this@TerminalSurfaceView, InputMethodManager.SHOW_IMPLICIT)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val buf = buffer ?: return false
                scrollAccumulatorPx += distanceY
                val step = cellHeight.coerceAtLeast(12f)
                val rowsDelta = (scrollAccumulatorPx / step).toInt()
                if (rowsDelta != 0) {
                    scrollAccumulatorPx -= rowsDelta * step
                    val maxScroll = buf.getScrollbackCount()
                    scrollOffsetRows = (scrollOffsetRows - rowsDelta).coerceIn(0, maxScroll)
                    invalidate()
                }
                return true
            }
        }
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun applyConfig(
        newBuffer: TerminalBuffer,
        newTheme: TerminalTheme,
        newSettings: TerminalSettings,
        fontSizeSp: Float,
        ctrl: Boolean,
        alt: Boolean,
        exited: Boolean
    ) {
        val metricsDirty = (resolvedFontSizeSp != fontSizeSp) ||
            (settings.typeface != newSettings.typeface) ||
            (settings.lineHeightMultiplier != newSettings.lineHeightMultiplier)

        buffer = newBuffer
        theme = newTheme
        settings = newSettings
        resolvedFontSizeSp = fontSizeSp
        ctrlArmed = ctrl
        altArmed = alt
        sessionExited = exited

        setBackgroundColor(newTheme.terminalBg)
        if (metricsDirty || textPaint.textSize <= 0f) {
            recomputeCellMetrics()
        }
        invalidate()
    }

    fun setCursorBlinkState(visible: Boolean) {
        if (cursorBlinkVisible != visible) {
            cursorBlinkVisible = visible
            invalidate()
        }
    }

    fun resetScrollToBottom() {
        scrollOffsetRows = 0
        invalidate()
    }

    private fun recomputeCellMetrics() {
        val baseTypeface = when (settings.typeface) {
            MonoTypeface.DEFAULT_MONO -> Typeface.MONOSPACE
            MonoTypeface.SANS_MONO -> Typeface.create("monospace", Typeface.NORMAL)
            MonoTypeface.SERIF_MONO -> Typeface.create("serif-monospace", Typeface.NORMAL)
        }
        textPaint.typeface = baseTypeface
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            resolvedFontSizeSp,
            resources.displayMetrics
        )

        val fm = textPaint.fontMetrics
        val rawLineHeight = fm.descent - fm.ascent
        cellWidth = max(6f, textPaint.measureText("M"))
        cellHeight = max(12f, ceil(rawLineHeight * settings.lineHeightMultiplier))
        val extraLeading = (cellHeight - rawLineHeight) / 2f
        baselineOffset = -fm.ascent + extraLeading

        notifyGridSize()
    }

    private fun notifyGridSize() {
        if (width <= 0 || height <= 0) return
        val usableW = max(1f, (width - paddingLeft - paddingRight).toFloat())
        val usableH = max(1f, (height - paddingTop - paddingBottom).toFloat())
        val cols = max(10, (usableW / cellWidth).toInt())
        val rows = max(2, (usableH / cellHeight).toInt())
        if (cols != lastReportedCols || rows != lastReportedRows) {
            lastReportedCols = cols
            lastReportedRows = rows
            onGridResize?.invoke(cols, rows)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeCellMetrics()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    handleCommittedText(text.toString())
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    val bsByte = if (settings.backspaceMode == BackspaceMode.CTRL_H) 0x08.toByte() else 0x7F.toByte()
                    val payload = ByteArray(beforeLength) { bsByte }
                    sendBytes(payload)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (handleKeyCode(event)) return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKeyCode(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleKeyCode(event: KeyEvent): Boolean {
        if (sessionExited) {
            if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                onRestartRequested?.invoke()
                return true
            }
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                sendTextWithModifiers("\r")
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                val bs = if (settings.backspaceMode == BackspaceMode.CTRL_H) "\u0008" else "\u007f"
                sendTextWithModifiers(bs)
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                sendTextWithModifiers("\t")
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                sendBytes(byteArrayOf(0x1B))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                sendTextWithModifiers("\u001b[A")
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                sendTextWithModifiers("\u001b[B")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                sendTextWithModifiers("\u001b[C")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                sendTextWithModifiers("\u001b[D")
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                sendTextWithModifiers("\u001b[H")
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                sendTextWithModifiers("\u001b[F")
                return true
            }
        }

        val unicode = event.unicodeChar
        if (unicode != 0) {
            val ch = unicode.toChar()
            if (event.isCtrlPressed) {
                val ctrlChar = toControlChar(ch)
                if (ctrlChar != null) {
                    sendBytes(byteArrayOf(ctrlChar))
                    return true
                }
            }
            if (event.isAltPressed) {
                sendTextWithModifiers("\u001b$ch")
                return true
            }
            handleCommittedText(ch.toString())
            return true
        }
        return false
    }

    private fun handleCommittedText(raw: String) {
        if (sessionExited) {
            if (raw.contains('\n') || raw.contains('\r')) {
                onRestartRequested?.invoke()
            }
            return
        }
        val normalized = raw.replace('\n', '\r')
        sendTextWithModifiers(normalized)
    }

    private fun sendTextWithModifiers(raw: String) {
        var out = raw
        val hadCtrl = ctrlArmed
        val hadAlt = altArmed

        if (hadCtrl && out.length == 1) {
            val ctrlByte = toControlChar(out[0])
            if (ctrlByte != null) {
                out = ctrlByte.toInt().toChar().toString()
            }
        }
        if (hadAlt) {
            out = "\u001b$out"
        }
        if (hadCtrl || hadAlt) {
            onConsumeModifiers?.invoke()
        }
        sendBytes(out.toByteArray(Charsets.UTF_8))
    }

    private fun sendBytes(bytes: ByteArray) {
        scrollOffsetRows = 0
        onSendBytes?.invoke(bytes)
    }

    private fun toControlChar(ch: Char): Byte? {
        return when (ch) {
            in 'a'..'z' -> (ch.code - 'a'.code + 1).toByte()
            in 'A'..'Z' -> (ch.code - 'A'.code + 1).toByte()
            ' ' -> 0.toByte()
            '[' -> 27.toByte()
            '\\' -> 28.toByte()
            ']' -> 29.toByte()
            else -> null
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val buf = buffer ?: return
        val activeTheme = theme ?: return

        canvas.drawColor(activeTheme.terminalBg)

        val padLeft = paddingLeft.toFloat()
        val padTop = paddingTop.toFloat()
        val charBuf = CharArray(1)

        synchronized(buf.lock) {
            val sbCount = buf.getScrollbackCount()
            val offset = scrollOffsetRows.coerceIn(0, sbCount)
            val rows = buf.rows
            val cols = buf.cols

            for (r in 0 until rows) {
                val rowCells = buf.getRowAt(offset, r) ?: continue
                val topY = padTop + r * cellHeight
                val baselineY = topY + baselineOffset

                for (c in 0 until minOf(cols, rowCells.size)) {
                    val cell = rowCells[c]
                    val leftX = padLeft + c * cellWidth

                    var fg = TerminalBuffer.resolveColor(
                        cell.fgColor,
                        isFg = true,
                        bold = cell.bold,
                        boldBright = settings.boldBright,
                        theme = activeTheme
                    )
                    var bg = TerminalBuffer.resolveColor(
                        cell.bgColor,
                        isFg = false,
                        bold = false,
                        boldBright = false,
                        theme = activeTheme
                    )

                    if (cell.inverse) {
                        val tmp = fg
                        fg = bg
                        bg = tmp
                    }

                    if (bg != activeTheme.terminalBg) {
                        bgPaint.color = bg
                        canvas.drawRect(leftX, topY, leftX + cellWidth + 0.5f, topY + cellHeight, bgPaint)
                    }

                    if (cell.ch != ' ') {
                        textPaint.color = fg
                        textPaint.isFakeBoldText = cell.bold
                        textPaint.isUnderlineText = cell.underline
                        charBuf[0] = cell.ch
                        canvas.drawText(charBuf, 0, 1, leftX, baselineY, textPaint)
                    }
                }
            }

            // Draw cursor when scrolled to live bottom
            if (offset == 0 && buf.cursorVisible && (!settings.cursorBlink || cursorBlinkVisible)) {
                val cr = buf.cursorRow.coerceIn(0, rows - 1)
                val cc = buf.cursorCol.coerceIn(0, cols - 1)
                val cx = padLeft + cc * cellWidth
                val cy = padTop + cr * cellHeight

                cursorPaint.color = activeTheme.cursorColor
                when (settings.cursorStyle) {
                    CursorStyle.BLOCK -> {
                        cursorPaint.alpha = 185
                        canvas.drawRect(cx, cy, cx + cellWidth, cy + cellHeight, cursorPaint)
                    }
                    CursorStyle.UNDERLINE -> {
                        cursorPaint.alpha = 255
                        val stroke = max(3f, cellHeight * 0.12f)
                        canvas.drawRect(cx, cy + cellHeight - stroke, cx + cellWidth, cy + cellHeight, cursorPaint)
                    }
                    CursorStyle.BAR -> {
                        cursorPaint.alpha = 255
                        val barW = max(3f, cellWidth * 0.16f)
                        canvas.drawRect(cx, cy, cx + barW, cy + cellHeight, cursorPaint)
                    }
                }
            }
        }
        textPaint.isFakeBoldText = false
        textPaint.isUnderlineText = false
    }
}

@Composable
fun TerminalCanvas(
    modifier: Modifier = Modifier,
    buffer: TerminalBuffer,
    revision: Long,
    theme: TerminalTheme,
    settings: TerminalSettings,
    resolvedFontSizeSp: Float,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    sessionExited: Boolean,
    onSendBytes: (ByteArray) -> Unit,
    onGridResize: (Int, Int) -> Unit,
    onConsumeModifiers: () -> Unit,
    onRestartRequested: () -> Unit
) {
    var surfaceViewRef by remember { mutableStateOf<TerminalSurfaceView?>(null) }

    LaunchedEffect(settings.cursorBlink, sessionExited) {
        if (!settings.cursorBlink || sessionExited) {
            surfaceViewRef?.setCursorBlinkState(true)
            return@LaunchedEffect
        }
        var visible = true
        while (true) {
            delay(530)
            visible = !visible
            surfaceViewRef?.setCursorBlinkState(visible)
        }
    }

    LaunchedEffect(revision) {
        surfaceViewRef?.invalidate()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalSurfaceView(ctx).apply {
                val padPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    8f,
                    ctx.resources.displayMetrics
                ).roundToInt()
                setPadding(padPx, padPx / 2, padPx, padPx / 2)
                this.onSendBytes = onSendBytes
                this.onGridResize = onGridResize
                this.onConsumeModifiers = onConsumeModifiers
                this.onRestartRequested = onRestartRequested
                applyConfig(
                    newBuffer = buffer,
                    newTheme = theme,
                    newSettings = settings,
                    fontSizeSp = resolvedFontSizeSp,
                    ctrl = ctrlArmed,
                    alt = altArmed,
                    exited = sessionExited
                )
                requestFocus()
                surfaceViewRef = this
            }
        },
        update = { view ->
            view.onSendBytes = onSendBytes
            view.onGridResize = onGridResize
            view.onConsumeModifiers = onConsumeModifiers
            view.onRestartRequested = onRestartRequested
            view.applyConfig(
                newBuffer = buffer,
                newTheme = theme,
                newSettings = settings,
                fontSizeSp = resolvedFontSizeSp,
                ctrl = ctrlArmed,
                alt = altArmed,
                exited = sessionExited
            )
        }
    )
}
