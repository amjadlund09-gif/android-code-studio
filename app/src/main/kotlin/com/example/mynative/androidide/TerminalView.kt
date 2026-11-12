package com.example.mynative.androidide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.example.mynative.androidide.term.TerminalEmulator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [TermSession]'s screen and turns touch / keyboard input into terminal input.
 * Scroll = drag, fling; zoom = pinch; select = long press (+ drag), then Copy / Paste.
 */
class TerminalView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var session: TermSession? = null
        private set

    /** Enter pressed on a finished session ("press Enter to close"). */
    var onFinishedEnter: (() -> Unit)? = null

    /** Sticky CTRL / ALT from the extra-keys row. */
    var ctrlSticky = false
    var altSticky = false
    var onStickyUsed: (() -> Unit)? = null

    var cols = 0
        private set
    var rows = 0
        private set

    private val prefs = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)
    private var textPx = prefs.getFloat("text_px", sp(13f))

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val tfNormal: Typeface = Typeface.MONOSPACE
    private val tfBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val tfItalic: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private val tfBoldItalic: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    private var cellW = 1f
    private var cellH = 1
    private var ascent = 1f
    private val runBuf = CharArray(1024)

    /** External row shown at the top of the view: 0 = live screen, negative = scrolled back. */
    private var topRow = 0
    private var lastSb = 0

    // selection (external row, col)
    private var selecting = false
    private var selActive = false
    private var selAr = 0; private var selAc = 0
    private var selBr = 0; private var selBc = 0
    private var popup: PopupWindow? = null

    private var scrollAccum = 0f
    private var flingV = 0f
    private var flingAcc = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(DEFAULT_BG)
        applyMetrics()
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun applyMetrics() {
        paint.typeface = tfNormal
        paint.textSize = textPx
        cellW = paint.measureText("M")
        val fm = paint.fontMetrics
        cellH = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        ascent = -fm.ascent
    }

    // ---- session ----------------------------------------------------------------------------------------------

    fun attach(s: TermSession?) {
        session = s
        topRow = 0
        clearSelection()
        s?.let {
            lastSb = synchronized(it.emulator) { it.emulator.scrollbackSize }
            if (cols > 1 && rows > 0) it.resize(rows, cols)
        }
        invalidate()
    }

    /** Safe from any thread. */
    fun requestRedraw() { postInvalidateOnAnimation() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val c = max(2, (w / cellW).toInt())
        val r = max(1, h / cellH)
        if (c != cols || r != rows) {
            cols = c; rows = r
            session?.resize(r, c)
            topRow = topRow.coerceAtMost(0)
        }
    }

    private fun changeTextSize(px: Float) {
        textPx = px.coerceIn(sp(6f), sp(32f))
        applyMetrics()
        val c = max(2, (width / cellW).toInt())
        val r = max(1, height / cellH)
        cols = c; rows = r
        session?.resize(r, c)
        invalidate()
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    private fun cellFg(at: Long): Int {
        var fg = if (TerminalEmulator.hasFg(at)) TerminalEmulator.fg(at) or (0xFF shl 24) else DEFAULT_FG
        var bg = if (TerminalEmulator.hasBg(at)) TerminalEmulator.bg(at) or (0xFF shl 24) else DEFAULT_BG
        if (at and TerminalEmulator.INVERSE != 0L) { val t = fg; fg = bg; bg = t }
        if (at and TerminalEmulator.INVISIBLE != 0L) return bg
        if (at and TerminalEmulator.DIM != 0L) fg = blend(fg, bg, 0.45f)
        return fg
    }

    private fun cellBg(at: Long): Int {
        var fg = if (TerminalEmulator.hasFg(at)) TerminalEmulator.fg(at) or (0xFF shl 24) else DEFAULT_FG
        var bg = if (TerminalEmulator.hasBg(at)) TerminalEmulator.bg(at) or (0xFF shl 24) else DEFAULT_BG
        if (at and TerminalEmulator.INVERSE != 0L) { val t = fg; fg = bg; bg = t }
        return bg
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun typefaceFor(at: Long): Typeface {
        val bold = at and TerminalEmulator.BOLD != 0L
        val italic = at and TerminalEmulator.ITALIC != 0L
        return when {
            bold && italic -> tfBoldItalic
            bold -> tfBold
            italic -> tfItalic
            else -> tfNormal
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(DEFAULT_BG)
        val s = session ?: return
        val emu = s.emulator
        synchronized(emu) {
            val sb = emu.scrollbackSize
            if (topRow < 0 && sb != lastSb) topRow -= (sb - lastSb)
            lastSb = sb
            topRow = topRow.coerceIn(-sb, 0)

            for (r in 0 until rows) {
                val line = emu.getLine(topRow + r) ?: continue
                drawLine(canvas, line, r)
            }
            if (topRow == 0 && emu.isCursorVisible) drawCursor(canvas, emu)
            if (selActive) drawSelection(canvas)
        }
    }

    private fun drawLine(c: Canvas, line: TerminalEmulator.Line, r: Int) {
        val y = (r * cellH).toFloat()
        val n = min(cols, line.ch.size)

        // backgrounds
        var i = 0
        while (i < n) {
            val bg = cellBg(line.at[i])
            var j = i + 1
            while (j < n && cellBg(line.at[j]) == bg) j++
            if (bg != DEFAULT_BG) {
                bgPaint.color = bg
                c.drawRect(i * cellW, y, j * cellW, y + cellH, bgPaint)
            }
            i = j
        }

        // text
        val base = y + ascent
        i = 0
        while (i < n) {
            val cp = line.ch[i]
            if (cp == 0 || cp == TerminalEmulator.WIDE_TAIL) { i++; continue }
            val at = line.at[i]
            paint.typeface = typefaceFor(at)
            paint.color = cellFg(at)
            if (cp >= 0x80) {
                val wide = i + 1 < n && line.ch[i + 1] == TerminalEmulator.WIDE_TAIL
                c.drawText(String(Character.toChars(cp)), i * cellW, base, paint)
                decorate(c, at, i * cellW, (i + if (wide) 2 else 1) * cellW, y)
                i += if (wide) 2 else 1
                continue
            }
            // run of plain ASCII cells with the same attributes (blank cells count as spaces)
            var j = i
            var len = 0
            while (j < n && len < runBuf.size) {
                val ch = line.ch[j]
                if (line.at[j] != at || ch == TerminalEmulator.WIDE_TAIL || ch >= 0x80) break
                runBuf[len++] = if (ch == 0) ' ' else ch.toChar()
                j++
            }
            c.drawText(runBuf, 0, len, i * cellW, base, paint)
            decorate(c, at, i * cellW, j * cellW, y)
            i = j
        }
    }

    private fun decorate(c: Canvas, at: Long, x1: Float, x2: Float, y: Float) {
        if (at and (TerminalEmulator.UNDERLINE or TerminalEmulator.STRIKE) == 0L) return
        bgPaint.color = cellFg(at)
        val t = max(1f, cellH / 14f)
        if (at and TerminalEmulator.UNDERLINE != 0L) c.drawRect(x1, y + cellH - t - 1f, x2, y + cellH - 1f, bgPaint)
        if (at and TerminalEmulator.STRIKE != 0L) c.drawRect(x1, y + cellH / 2f, x2, y + cellH / 2f + t, bgPaint)
    }

    private fun drawCursor(c: Canvas, emu: TerminalEmulator) {
        val cr = emu.cursorRow
        val cc = emu.cursorCol
        if (cr < 0 || cr >= rows || cc >= cols) return
        val line = emu.getLine(cr) ?: return
        val x = cc * cellW
        val y = (cr * cellH).toFloat()
        val cp = if (cc < line.ch.size) line.ch[cc] else 0
        val wide = cc + 1 < line.ch.size && line.ch[cc + 1] == TerminalEmulator.WIDE_TAIL
        val w = if (wide) cellW * 2 else cellW
        bgPaint.color = CURSOR
        if (!hasFocus()) {
            bgPaint.style = Paint.Style.STROKE
            bgPaint.strokeWidth = max(2f, cellH / 12f)
            c.drawRect(x + 1, y + 1, x + w - 1, y + cellH - 1, bgPaint)
            bgPaint.style = Paint.Style.FILL
            return
        }
        when (emu.cursorStyle) {
            3, 4 -> c.drawRect(x, y + cellH - max(2f, cellH / 8f), x + w, y + cellH, bgPaint)
            5, 6 -> c.drawRect(x, y, x + max(2f, cellW / 6f), y + cellH, bgPaint)
            else -> {
                c.drawRect(x, y, x + w, y + cellH, bgPaint)
                if (cp != 0 && cp != TerminalEmulator.WIDE_TAIL) {
                    paint.typeface = typefaceFor(line.at[cc])
                    paint.color = DEFAULT_BG
                    c.drawText(String(Character.toChars(cp)), x, y + ascent, paint)
                }
            }
        }
    }

    private fun drawSelection(c: Canvas) {
        var r1 = selAr; var c1 = selAc; var r2 = selBr; var c2 = selBc
        if (r1 > r2 || (r1 == r2 && c1 > c2)) { r1 = selBr; c1 = selBc; r2 = selAr; c2 = selAc }
        bgPaint.color = SELECTION
        for (er in max(r1, topRow)..min(r2, topRow + rows - 1)) {
            val a = if (er == r1) c1 else 0
            val b = if (er == r2) c2 else cols - 1
            val y = ((er - topRow) * cellH).toFloat()
            c.drawRect(a * cellW, y, (b + 1) * cellW, y + cellH, bgPaint)
        }
    }

    // ---- scrolling ----------------------------------------------------------------------------------------------------

    private fun scrollLines(n: Int) {
        val s = session ?: return
        if (n == 0) return
        val emu = s.emulator
        val alt = synchronized(emu) { emu.isAltScreen }
        if (alt) {
            // full-screen apps (less, vim, htop): scrolling = arrow keys
            val seq = if (synchronized(emu) { emu.isAppCursorKeys }) { if (n > 0) "\u001bOB" else "\u001bOA" } else { if (n > 0) "\u001b[B" else "\u001b[A" }
            val sb = StringBuilder()
            repeat(min(abs(n), 30)) { sb.append(seq) }
            s.write(sb.toString())
        } else {
            val sbSize = synchronized(emu) { emu.scrollbackSize }
            topRow = (topRow + n).coerceIn(-sbSize, 0)
            invalidate()
        }
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            flingAcc += flingV / 60f
            val n = flingAcc.toInt()
            flingAcc -= n
            scrollLines(n)
            flingV *= 0.94f
            if (abs(flingV) > 2f) postOnAnimation(this)
        }
    }

    private fun stopFling() { flingV = 0f; removeCallbacks(flingRunnable) }

    // ---- touch --------------------------------------------------------------------------------------------------------------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { stopFling(); scrollAccum = 0f; return true }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            clearSelection()
            requestFocus()
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (selecting) return true
            scrollAccum += dy
            val lines = (scrollAccum / cellH).toInt()
            if (lines != 0) { scrollAccum -= lines * cellH; scrollLines(lines) }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (selecting) return true
            flingV = -vy / cellH
            flingAcc = 0f
            if (abs(flingV) > 4f) postOnAnimation(flingRunnable)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            startSelection(e.x, e.y)
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            changeTextSize(textPx * d.scaleFactor)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            prefs.edit().putFloat("text_px", textPx).apply()
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        if (selecting) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> extendSelection(e.x, e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { selecting = false; showPopup() }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) { scrollLines((-v * 3).toInt().let { if (it == 0) (if (v > 0) -1 else 1) else it }); return true }
        }
        return super.onGenericMotionEvent(e)
    }

    // ---- selection / clipboard ----------------------------------------------------------------------------------------------------

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val emu = session?.emulator ?: return 0 to 0
        val sb = synchronized(emu) { emu.scrollbackSize }
        val er = (topRow + (y / cellH).toInt()).coerceIn(-sb, max(0, rows - 1))
        val ec = (x / cellW).toInt().coerceIn(0, max(0, cols - 1))
        return er to ec
    }

    private fun startSelection(x: Float, y: Float) {
        val emu = session?.emulator ?: return
        val (r, c) = cellAt(x, y)
        var a = c
        var b = c
        synchronized(emu) {
            val line = emu.getLine(r)
            if (line != null) {
                fun blank(i: Int) = i >= line.ch.size || line.ch[i] == 0
                if (!blank(c)) {
                    while (a > 0 && !blank(a - 1)) a--
                    while (b + 1 < line.ch.size && !blank(b + 1)) b++
                }
            }
        }
        selAr = r; selAc = a; selBr = r; selBc = b
        selActive = true
        selecting = true
        dismissPopup()
        invalidate()
    }

    private fun extendSelection(x: Float, y: Float) {
        val (r, c) = cellAt(x, y)
        selBr = r; selBc = c
        invalidate()
    }

    fun clearSelection() {
        selActive = false
        selecting = false
        dismissPopup()
        invalidate()
    }

    private fun selectedText(): String {
        val emu = session?.emulator ?: return ""
        var r1 = selAr; var c1 = selAc; var r2 = selBr; var c2 = selBc
        if (r1 > r2 || (r1 == r2 && c1 > c2)) { r1 = selBr; c1 = selBc; r2 = selAr; c2 = selAc }
        return emu.getText(r1, c1, r2, c2)
    }

    private fun dismissPopup() { popup?.dismiss(); popup = null }

    private fun showPopup() {
        if (!selActive) return
        dismissPopup()
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0xFF2B2B36.toInt()); cornerRadius = 14f * resources.displayMetrics.density }
            elevation = 8f * resources.displayMetrics.density
        }
        fun item(label: String, action: () -> Unit) {
            bar.addView(TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                val p = (14 * resources.displayMetrics.density).toInt()
                setPadding(p, p / 2 + 4, p, p / 2 + 4)
                setOnClickListener { action() }
            })
        }
        item("Copy") { copySelection() }
        item("Paste") { paste(); clearSelection() }
        item("Select all") { selectAll() }
        val pw = PopupWindow(bar, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        pw.isOutsideTouchable = false
        pw.isFocusable = false
        val sel = IntArray(2)
        getLocationInWindow(sel)
        val er = min(selAr, selBr)
        val y = sel[1] + (er - topRow).coerceAtLeast(0) * cellH - (56 * resources.displayMetrics.density).toInt()
        pw.showAtLocation(this, Gravity.NO_GRAVITY, sel[0] + (12 * resources.displayMetrics.density).toInt(), max(sel[1], y))
        popup = pw
    }

    private fun copySelection() {
        val t = selectedText()
        if (t.isNotEmpty()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", t))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
        clearSelection()
    }

    private fun selectAll() {
        val emu = session?.emulator ?: return
        val sb = synchronized(emu) { emu.scrollbackSize }
        selAr = -sb; selAc = 0; selBr = max(0, rows - 1); selBc = max(0, cols - 1)
        selActive = true
        invalidate()
        showPopup()
    }

    fun paste() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (t.isEmpty()) return
        val s = session ?: return
        val text = t.replace("\r\n", "\r").replace('\n', '\r')
        val bracketed = synchronized(s.emulator) { s.emulator.isBracketedPaste }
        topRow = 0
        s.write(if (bracketed) "\u001b[200~$text\u001b[201~" else text)
    }

    // ---- keyboard -----------------------------------------------------------------------------------------------------------------------

    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this@TerminalView, true) {
            private fun flush() {
                val e = editable ?: return
                if (e.isNotEmpty()) {
                    val t = e.toString()
                    e.clear()
                    sendText(t)
                }
            }

            override fun finishComposingText(): Boolean { super.finishComposingText(); flush(); return true }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // some keyboards ask to delete several characters at once
                repeat(beforeLength) { sendKeyCode(KeyEvent.KEYCODE_DEL) }
                return true
            }
        }
    }

    /** Typed text (soft keyboard commit, extra keys). Applies sticky CTRL / ALT to the first character. */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val s = session ?: return
        if (s.finished) { if (text.contains('\n') || text.contains('\r')) onFinishedEnter?.invoke(); return }
        var out = text
        if (ctrlSticky) {
            val c = ctrlChar(text[0])
            if (c != null) out = c.toString() + text.substring(1)
        }
        if (altSticky) out = "\u001b" + out
        consumeSticky()
        topRow = 0
        s.write(out)
        if (selActive) clearSelection()
    }

    /** A named key (extra-keys row, IME key events). */
    fun sendKeyCode(keyCode: Int) {
        val s = session ?: return
        if (s.finished) { if (keyCode == KeyEvent.KEYCODE_ENTER) onFinishedEnter?.invoke(); return }
        val seq = keySequence(keyCode, ctrlSticky, altSticky, false, s) ?: return
        consumeSticky()
        topRow = 0
        s.write(seq)
    }

    private fun consumeSticky() {
        if (ctrlSticky || altSticky) {
            ctrlSticky = false
            altSticky = false
            onStickyUsed?.invoke()
        }
    }

    private fun ctrlChar(ch: Char): Char? = when {
        ch in 'a'..'z' -> (ch.code - 'a'.code + 1).toChar()
        ch in '@'..'_' -> (ch.code and 0x1f).toChar()
        ch == ' ' -> 0.toChar()
        ch == '?' -> 0x7f.toChar()
        else -> null
    }

    private fun modParam(ctrl: Boolean, alt: Boolean, shift: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    private fun keySequence(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, s: TermSession): String? {
        val app = synchronized(s.emulator) { s.emulator.isAppCursorKeys }
        val m = modParam(ctrl, alt, shift)
        fun cursorKey(letter: Char): String =
            if (m == 1) (if (app) "\u001bO$letter" else "\u001b[$letter") else "\u001b[1;$m$letter"
        fun tilde(n: Int): String = if (m == 1) "\u001b[$n~" else "\u001b[$n;$m~"
        fun fn(letter: Char): String = if (m == 1) "\u001bO$letter" else "\u001b[1;$m$letter"
        val esc = if (alt) "\u001b" else ""
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> esc + "\r"
            KeyEvent.KEYCODE_DEL -> esc + (if (ctrl) "\b" else "\u007f")
            KeyEvent.KEYCODE_FORWARD_DEL -> tilde(3)
            KeyEvent.KEYCODE_TAB -> if (shift) "\u001b[Z" else esc + "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> cursorKey('A')
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorKey('B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorKey('C')
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorKey('D')
            KeyEvent.KEYCODE_MOVE_HOME -> cursorKey('H')
            KeyEvent.KEYCODE_MOVE_END -> cursorKey('F')
            KeyEvent.KEYCODE_PAGE_UP -> tilde(5)
            KeyEvent.KEYCODE_PAGE_DOWN -> tilde(6)
            KeyEvent.KEYCODE_INSERT -> tilde(2)
            KeyEvent.KEYCODE_F1 -> fn('P')
            KeyEvent.KEYCODE_F2 -> fn('Q')
            KeyEvent.KEYCODE_F3 -> fn('R')
            KeyEvent.KEYCODE_F4 -> fn('S')
            KeyEvent.KEYCODE_F5 -> tilde(15)
            KeyEvent.KEYCODE_F6 -> tilde(17)
            KeyEvent.KEYCODE_F7 -> tilde(18)
            KeyEvent.KEYCODE_F8 -> tilde(19)
            KeyEvent.KEYCODE_F9 -> tilde(20)
            KeyEvent.KEYCODE_F10 -> tilde(21)
            KeyEvent.KEYCODE_F11 -> tilde(23)
            KeyEvent.KEYCODE_F12 -> tilde(24)
            else -> null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_POWER) {
            return super.onKeyDown(keyCode, event)
        }
        if (s.finished) {
            if (keyCode == KeyEvent.KEYCODE_ENTER) { onFinishedEnter?.invoke(); return true }
            return true
        }
        val ctrl = event.isCtrlPressed || ctrlSticky
        val alt = event.isAltPressed || altSticky
        val shift = event.isShiftPressed

        keySequence(keyCode, ctrl, alt, shift, s)?.let {
            consumeSticky()
            topRow = 0
            s.write(it)
            return true
        }

        // printable key from a hardware keyboard
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val u = event.getUnicodeChar(meta)
        if (u == 0) return super.onKeyDown(keyCode, event)
        if (u < 0) return true                                   // dead key / combining accent: ignore
        var text = String(Character.toChars(u))
        if (ctrl) {
            val c = ctrlChar(text[0])
            if (c != null) text = c.toString()
        }
        if (alt) text = "\u001b$text"
        consumeSticky()
        topRow = 0
        s.write(text)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        return true
    }

    override fun onDetachedFromWindow() {
        stopFling()
        dismissPopup()
        super.onDetachedFromWindow()
    }

    companion object {
        val DEFAULT_BG = 0xFF141418.toInt()
        val DEFAULT_FG = 0xFFE4E4EC.toInt()
        private val CURSOR = 0xFFE3DEF7.toInt()
        private val SELECTION = 0x6660A5FA
    }
}
