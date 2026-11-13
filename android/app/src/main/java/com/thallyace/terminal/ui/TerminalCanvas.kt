package com.thallyace.terminal.ui

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
import com.thallyace.terminal.model.BackspaceMode
import com.thallyace.terminal.model.CursorStyle
import com.thallyace.terminal.model.MonoTypeface
import com.thallyace.terminal.model.TerminalSettings
import com.thallyace.terminal.model.TerminalTheme
import com.thallyace.terminal.terminal.TerminalBuffer
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
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
    private var initialImeRequested = false

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

    private var deadKeyAccent = 0

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showSoftKeyboard()
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
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!initialImeRequested) {
            initialImeRequested = true
            post { showSoftKeyboard() }
        }
    }

    fun showSoftKeyboard() {
        requestFocusFromTouch()
        requestFocus()
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return@post
            imm.restartInput(this)
            val shown = imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            if (!shown) {
                postDelayed({
                    requestFocus()
                    imm.restartInput(this)
                    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }, 120)
            }
        }
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
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            private var composingBuffer: String = ""

            private fun backspaceByte(): Byte =
                if (settings.backspaceMode == BackspaceMode.CTRL_H) 0x08.toByte() else 0x7F.toByte()

            private fun sendBackspaces(count: Int) {
                if (count <= 0) return
                val bs = backspaceByte()
                sendBytes(ByteArray(count) { bs })
            }

            private fun applyComposingUpdate(nextText: String, keepComposing: Boolean) {
                val maxPrefix = min(composingBuffer.length, nextText.length)
                var common = 0
                while (common < maxPrefix && composingBuffer[common] == nextText[common]) {
                    common++
                }
                val eraseCount = composingBuffer.length - common
                if (eraseCount > 0) {
                    sendBackspaces(eraseCount)
                }
                if (common < nextText.length) {
                    handleCommittedText(nextText.substring(common))
                }
                composingBuffer = if (keepComposing) nextText else ""
            }

            override fun beginBatchEdit(): Boolean = true

            override fun endBatchEdit(): Boolean = true

            override fun setComposingRegion(start: Int, end: Int): Boolean = true

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                applyComposingUpdate(text?.toString() ?: "", keepComposing = true)
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val next = text?.toString() ?: ""
                if (composingBuffer.isNotEmpty()) {
                    applyComposingUpdate(next, keepComposing = false)
                } else if (next.isNotEmpty()) {
                    handleCommittedText(next)
                }
                return true
            }

            override fun finishComposingText(): Boolean {
                composingBuffer = ""
                return true
            }

            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                if (n <= 0) return ""
                return if (composingBuffer.isNotEmpty()) {
                    composingBuffer.takeLast(n)
                } else {
                    // Non-whitespace sentinel prevents IME auto-space/double-space-period
                    // suppression while keeping Backspace enabled on empty lines.
                    "a"
                }
            }

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

            override fun getSelectedText(flags: Int): CharSequence? = null

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    if (composingBuffer.isNotEmpty()) {
                        composingBuffer = if (beforeLength >= composingBuffer.length) {
                            ""
                        } else {
                            composingBuffer.dropLast(beforeLength)
                        }
                    }
                    sendBackspaces(beforeLength)
                }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                return deleteSurroundingText(beforeLength, afterLength)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                composingBuffer = ""
                handleCommittedText("\r")
                return true
            }

            @Suppress("DEPRECATION")
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (isTerminalHandledKey(event) && handleKeyCode(event)) {
                        composingBuffer = ""
                        return true
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (isTerminalHandledKey(event)) {
                        return true
                    }
                } else if (event.action == KeyEvent.ACTION_MULTIPLE) {
                    if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                        val chars = event.characters
                        if (!chars.isNullOrEmpty()) {
                            composingBuffer = ""
                            handleCommittedText(chars)
                            return true
                        }
                    } else if (isTerminalHandledKey(event)) {
                        val repeats = event.repeatCount.coerceAtLeast(1)
                        var handled = false
                        repeat(repeats) {
                            if (handleKeyCode(event)) handled = true
                        }
                        if (handled) {
                            composingBuffer = ""
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isTerminalHandledKey(event) && handleKeyCode(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isTerminalHandledKey(event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent?): Boolean {
        val chars = event?.characters
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && !chars.isNullOrEmpty()) {
            handleCommittedText(chars)
            return true
        }
        if (event != null && isTerminalHandledKey(event)) {
            val repeats = repeatCount.coerceAtLeast(1)
            var handled = false
            repeat(repeats) {
                if (handleKeyCode(event)) handled = true
            }
            if (handled) return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun isSystemPassThroughKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_FUNCTION,
            KeyEvent.KEYCODE_CAPS_LOCK,
            KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_SCROLL_LOCK -> true
            else -> false
        }
    }

    private fun isTerminalHandledKey(event: KeyEvent): Boolean {
        return !isSystemPassThroughKey(event.keyCode)
    }

    private fun handleKeyCode(event: KeyEvent): Boolean {
        if (isSystemPassThroughKey(event.keyCode)) {
            return false
        }

        if (sessionExited) {
            if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                onRestartRequested?.invoke()
                return true
            }
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                if (event.isCtrlPressed) {
                    sendBytes(byteArrayOf(0x00))
                } else if (event.isAltPressed) {
                    sendBytes(byteArrayOf(0x1B, ' '.code.toByte()))
                } else {
                    sendTextWithModifiers(" ")
                }
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.isAltPressed) {
                    sendBytes(byteArrayOf(0x1B, '\r'.code.toByte()))
                } else {
                    sendTextWithModifiers("\r")
                }
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                val bs = if (settings.backspaceMode == BackspaceMode.CTRL_H) "\u0008" else "\u007f"
                if (event.isAltPressed) {
                    sendTextWithModifiers("\u001b$bs")
                } else {
                    sendTextWithModifiers(bs)
                }
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                sendTextWithModifiers("\u001b[3~")
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                if (event.isShiftPressed) {
                    sendTextWithModifiers("\u001b[Z")
                } else {
                    sendTextWithModifiers("\t")
                }
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
            KeyEvent.KEYCODE_PAGE_UP -> {
                sendTextWithModifiers("\u001b[5~")
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                sendTextWithModifiers("\u001b[6~")
                return true
            }
            KeyEvent.KEYCODE_INSERT -> {
                sendTextWithModifiers("\u001b[2~")
                return true
            }
            KeyEvent.KEYCODE_F1 -> { sendTextWithModifiers("\u001bOP"); return true }
            KeyEvent.KEYCODE_F2 -> { sendTextWithModifiers("\u001bOQ"); return true }
            KeyEvent.KEYCODE_F3 -> { sendTextWithModifiers("\u001bOR"); return true }
            KeyEvent.KEYCODE_F4 -> { sendTextWithModifiers("\u001bOS"); return true }
            KeyEvent.KEYCODE_F5 -> { sendTextWithModifiers("\u001b[15~"); return true }
            KeyEvent.KEYCODE_F6 -> { sendTextWithModifiers("\u001b[17~"); return true }
            KeyEvent.KEYCODE_F7 -> { sendTextWithModifiers("\u001b[18~"); return true }
            KeyEvent.KEYCODE_F8 -> { sendTextWithModifiers("\u001b[19~"); return true }
            KeyEvent.KEYCODE_F9 -> { sendTextWithModifiers("\u001b[20~"); return true }
            KeyEvent.KEYCODE_F10 -> { sendTextWithModifiers("\u001b[21~"); return true }
            KeyEvent.KEYCODE_F11 -> { sendTextWithModifiers("\u001b[23~"); return true }
            KeyEvent.KEYCODE_F12 -> { sendTextWithModifiers("\u001b[24~"); return true }
        }

        val shiftOnlyMeta = event.metaState and KeyEvent.META_SHIFT_MASK
        var unicode = event.getUnicodeChar(event.metaState)
        if (unicode == 0 && (event.isCtrlPressed || event.isAltPressed)) {
            unicode = event.getUnicodeChar(shiftOnlyMeta)
        }
        if (unicode == 0) {
            unicode = android.view.KeyCharacterMap
                .load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .get(event.keyCode, if (event.isCtrlPressed || event.isAltPressed) shiftOnlyMeta else event.metaState)
        }

        if ((unicode and android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) {
            deadKeyAccent = unicode and android.view.KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }

        if (unicode > 0) {
            var codePoint = unicode
            if (deadKeyAccent != 0) {
                val combined = android.view.KeyCharacterMap.getDeadChar(deadKeyAccent, unicode)
                deadKeyAccent = 0
                if (combined != 0) {
                    codePoint = combined
                }
            }
            val text = String(Character.toChars(codePoint))
            if (event.isCtrlPressed && text.length == 1) {
                val ctrlChar = toControlChar(text[0])
                if (ctrlChar != null) {
                    if (event.isAltPressed) {
                        sendBytes(byteArrayOf(0x1B, ctrlChar))
                    } else {
                        sendBytes(byteArrayOf(ctrlChar))
                    }
                    return true
                }
            }
            if (event.isAltPressed) {
                sendTextWithModifiers("\u001b$text")
                return true
            }
            handleCommittedText(text)
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
            ' ', '@' -> 0.toByte()
            '[' -> 27.toByte()
            '\\' -> 28.toByte()
            ']' -> 29.toByte()
            '^', '6' -> 30.toByte()
            '_', '-', '/' -> 31.toByte()
            '?' -> 127.toByte()
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
    onRestartRequested: () -> Unit,
    onSurfaceReady: ((TerminalSurfaceView) -> Unit)? = null
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

    LaunchedEffect(revision, surfaceViewRef) {
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
                setPadding(padPx, padPx / 4, padPx, padPx / 2)
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
                surfaceViewRef = this
                onSurfaceReady?.invoke(this)
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
            onSurfaceReady?.invoke(view)
        }
    )
}
