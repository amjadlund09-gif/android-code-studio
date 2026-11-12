package com.example.mynative.androidide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/** Undo/redo stack that can be kept per open file. */
class UndoHistory {
    class Entry(val pos: Int, val removed: String, val added: String)
    val undo = ArrayList<Entry>()
    val redo = ArrayList<Entry>()
    /** Pro editor: the text buffer (with its own undo stack) kept per open file. */
    var content: io.github.rosemoe.sora.text.Content? = null
    fun clear() { undo.clear(); redo.clear(); content = null }
}

/**
 * Code editor: line numbers, syntax colours (Kotlin/Java/XML/Gradle/JSON/shell),
 * undo/redo, auto-indent, auto-close brackets, find/replace and word completion.
 */
class CodeEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    var onContentChanged: (() -> Unit)? = null
    var onCompletionsChanged: ((List<String>) -> Unit)? = null

    var history = UndoHistory()
        set(value) { field = value }

    var language: String = "text"
    private var applying = false           // true while we change text programmatically
    private var beforeText = ""
    private var changeStart = 0
    private val ui = Handler(Looper.getMainLooper())
    private val highlightJob = Runnable { applyHighlight() }

    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; color = 0xFF9A9AA8.toInt()
        textAlign = Paint.Align.RIGHT
    }
    private val gutterBg = Paint().apply { color = 0x10000000 }
    private val activeLinePaint = Paint().apply { color = 0x12000000 }
    private var gutterWidth = 0f

    init {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setBackgroundColor(Color.TRANSPARENT)
        setHorizontallyScrolling(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        isFocusableInTouchMode = true
        updateGutter()

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (applying) return
                beforeText = s.subSequence(start, start + count).toString()
                changeStart = start
            }
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (applying) return
                val added = s.subSequence(start, start + count).toString()
                if (beforeText.isNotEmpty() || added.isNotEmpty()) {
                    val h = history
                    val last = h.undo.lastOrNull()
                    // merge consecutive single-char typing into one undo step
                    if (last != null && beforeText.isEmpty() && last.removed.isEmpty() &&
                        added.length == 1 && added != "\n" && last.pos + last.added.length == start &&
                        last.added.length < 40 && !last.added.endsWith(" ")
                    ) {
                        h.undo[h.undo.size - 1] = UndoHistory.Entry(last.pos, "", last.added + added)
                    } else {
                        h.undo.add(UndoHistory.Entry(start, beforeText, added))
                        if (h.undo.size > 500) h.undo.removeAt(0)
                    }
                    h.redo.clear()
                }
            }
            override fun afterTextChanged(s: Editable) {
                if (applying) return
                updateGutter()
                ui.removeCallbacks(highlightJob)
                ui.postDelayed(highlightJob, 150)
                onContentChanged?.invoke()
                notifyCompletions()
            }
        })
    }

    // ---- file loading --------------------------------------------------------------------------

    fun setLanguageFor(fileName: String) {
        language = when (fileName.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> if (fileName.endsWith(".gradle.kts")) "gradle" else "kotlin"
            "java" -> "java"
            "gradle" -> "gradle"
            "xml", "html", "svg" -> "xml"
            "json" -> "json"
            "sh", "bash" -> "shell"
            "c", "cpp", "h", "hpp", "cc" -> "java"
            "js", "ts", "css" -> "java"
            "py" -> "python"
            else -> if (fileName == "gradlew") "shell" else "text"
        }
    }

    /** Loads text without touching undo history (history is swapped separately). */
    fun load(text: String, fileName: String, h: UndoHistory) {
        setLanguageFor(fileName)
        history = h
        applying = true
        setText(text)
        applying = false
        setSelection(0)
        updateGutter()
        applyHighlight()
    }

    // ---- undo / redo ---------------------------------------------------------------------------

    fun canUndo() = history.undo.isNotEmpty()
    fun canRedo() = history.redo.isNotEmpty()

    fun undo() {
        val e = history.undo.removeLastOrNull() ?: return
        applying = true
        text?.replace(e.pos, e.pos + e.added.length, e.removed)
        applying = false
        history.redo.add(e)
        setSelection((e.pos + e.removed.length).coerceIn(0, length()))
        afterProgrammaticChange()
    }

    fun redo() {
        val e = history.redo.removeLastOrNull() ?: return
        applying = true
        text?.replace(e.pos, e.pos + e.removed.length, e.added)
        applying = false
        history.undo.add(e)
        setSelection((e.pos + e.added.length).coerceIn(0, length()))
        afterProgrammaticChange()
    }

    private fun afterProgrammaticChange() {
        updateGutter(); applyHighlight(); onContentChanged?.invoke(); notifyCompletions()
    }

    // ---- editing helpers -----------------------------------------------------------------------

    /** Insert at cursor as one undoable step (used by the symbol bar). */
    fun insertSymbol(s: String) {
        val t = text ?: return
        val a = selectionStart.coerceAtLeast(0); val b = selectionEnd.coerceAtLeast(0)
        val start = minOf(a, b); val end = maxOf(a, b)
        t.replace(start, end, s)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        cacheLen = -1                       // line-number cache is stale now
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!applying) notifyCompletions()
    }

    init {
        // auto-indent + auto-close via an InputFilter
        filters = arrayOf(android.text.InputFilter { source, start, end, dest, dstart, dend ->
            if (applying || end - start != 1) return@InputFilter null
            val ch = source[start]
            when (ch) {
                '\n' -> {
                    val lineStart = dest.lastIndexOf('\n', dstart - 1) + 1
                    val line = dest.subSequence(lineStart, dstart).toString()
                    var indent = line.takeWhile { it == ' ' || it == '\t' }
                    val trimmed = line.trimEnd()
                    val opens = trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[") ||
                        (language == "python" && trimmed.endsWith(":"))
                    val nextCh = if (dend < dest.length) dest[dend] else ' '
                    val closes = nextCh == '}' || nextCh == ')' || nextCh == ']'
                    if (opens) indent += "    "
                    if (opens && closes) "\n$indent\n${indent.dropLast(4)}" else "\n$indent"
                }
                '{', '(', '[', '"', '\'' -> {
                    if (language == "text") return@InputFilter null
                    val close = when (ch) { '{' -> '}'; '(' -> ')'; '[' -> ']'; else -> ch }
                    val hasSel = dend > dstart
                    val nextCh = if (dend < dest.length) dest[dend] else ' '
                    val prevCh = if (dstart > 0) dest[dstart - 1] else ' '
                    when {
                        hasSel -> "$ch${dest.subSequence(dstart, dend)}$close"
                        (ch == '"' || ch == '\'') && (prevCh.isLetterOrDigit() || nextCh.isLetterOrDigit()) -> null
                        (ch == '"' || ch == '\'') && nextCh == ch -> { post { setSelection(selectionStart + 1) }; "" }  // type-over
                        nextCh.isLetterOrDigit() -> null
                        else -> { post { setSelection((selectionStart - 1).coerceAtLeast(0)) }; "$ch$close" }
                    }
                }
                '}', ')', ']' -> {
                    val nextCh = if (dend < dest.length) dest[dend] else ' '
                    if (nextCh == ch && dend == dstart) { post { setSelection(selectionStart + 1) }; "" } else null
                }
                else -> null
            }
        })
    }

    // ---- find / replace --------------------------------------------------------------------------

    fun findNext(query: String, from: Int = selectionEnd, ignoreCase: Boolean = true): Boolean {
        if (query.isEmpty()) return false
        val s = text?.toString() ?: return false
        var i = s.indexOf(query, from.coerceIn(0, s.length), ignoreCase)
        if (i < 0) i = s.indexOf(query, 0, ignoreCase)       // wrap
        if (i < 0) return false
        setSelection(i, i + query.length)
        bringPointIntoView(i)
        return true
    }

    fun findPrev(query: String, ignoreCase: Boolean = true): Boolean {
        if (query.isEmpty()) return false
        val s = text?.toString() ?: return false
        var i = if (selectionStart > 0) s.lastIndexOf(query, selectionStart - 1, ignoreCase) else -1
        if (i < 0) i = s.lastIndexOf(query, s.length, ignoreCase)  // wrap
        if (i < 0) return false
        setSelection(i, i + query.length)
        bringPointIntoView(i)
        return true
    }

    fun replaceCurrent(query: String, with: String): Boolean {
        val s = text?.toString() ?: return false
        if (selectionEnd > selectionStart && s.substring(selectionStart, selectionEnd).equals(query, true)) {
            text?.replace(selectionStart, selectionEnd, with)
        }
        return findNext(query, selectionEnd)
    }

    fun replaceAll(query: String, with: String): Int {
        if (query.isEmpty()) return 0
        val s = text?.toString() ?: return 0
        var count = 0
        var idx = s.indexOf(query, 0, true)
        while (idx >= 0) { count++; idx = s.indexOf(query, idx + query.length, true) }
        if (count == 0) return 0
        val out = s.replace(Regex(Regex.escape(query), RegexOption.IGNORE_CASE), Regex.escapeReplacement(with))
        text?.replace(0, s.length, out)      // one undo step
        return count
    }

    fun gotoLine(line: Int, col: Int = 1) {
        val s = text?.toString() ?: return
        var idx = 0; var cur = 1
        while (cur < line) {
            val n = s.indexOf('\n', idx)
            if (n < 0) { idx = s.length; break }
            idx = n + 1; cur++
        }
        val end = s.indexOf('\n', idx).let { if (it < 0) s.length else it }
        val pos = (idx + (col - 1).coerceAtLeast(0)).coerceIn(idx, end)
        requestFocus()
        setSelection(pos)
        post { bringPointIntoView(pos) }
    }

    // ---- completion ---------------------------------------------------------------------------------

    private fun currentPrefix(): String {
        val s = text ?: return ""
        val p = selectionStart
        if (p <= 0 || p != selectionEnd) return ""
        var i = p
        while (i > 0 && (s[i - 1].isLetterOrDigit() || s[i - 1] == '_')) i--
        return s.substring(i, p)
    }

    private fun notifyCompletions() {
        val cb = onCompletionsChanged ?: return
        val prefix = currentPrefix()
        if (prefix.length < 2) { cb(emptyList()); return }
        val set = LinkedHashSet<String>()
        Highlighter.keywords(language).forEach { if (it.startsWith(prefix) && it != prefix) set.add(it) }
        val body = text?.toString().orEmpty()
        if (body.length < 400_000) {
            Regex("[A-Za-z_][A-Za-z0-9_]{2,}").findAll(body).forEach {
                val w = it.value
                if (w.startsWith(prefix) && w != prefix) set.add(w)
            }
        }
        cb(set.take(12))
    }

    fun applyCompletion(word: String) {
        val prefix = currentPrefix()
        val p = selectionStart
        text?.replace(p - prefix.length, p, word)
    }

    // ---- highlighting ---------------------------------------------------------------------------------

    private fun applyHighlight() {
        val t = text ?: return
        if (t.length > 300_000) return              // keep huge files responsive
        applying = true
        try { Highlighter.apply(t, language) } finally { applying = false }
    }

    // ---- line numbers ---------------------------------------------------------------------------------

    private fun updateGutter() {
        gutterPaint.textSize = textSize * 0.9f
        val digits = lineCount.toString().length.coerceAtLeast(2)
        gutterWidth = gutterPaint.measureText("8".repeat(digits)) + dpF(16)
        val pad = gutterWidth.toInt() + dpF(8).toInt()
        if (paddingLeft != pad) setPadding(pad, paddingTop, paddingRight, paddingBottom)
    }

    private fun dpF(v: Int) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val l = layout
        if (l != null) {
            val sx = scrollX.toFloat()
            // gutter background
            canvas.drawRect(sx, scrollY.toFloat(), sx + gutterWidth, (scrollY + height).toFloat(), gutterBg)
            // current line band
            val curLine = l.getLineForOffset(selectionStart.coerceIn(0, length()))
            canvas.drawRect(
                sx + gutterWidth, (l.getLineTop(curLine) + paddingTop).toFloat(),
                sx + width, (l.getLineBottom(curLine) + paddingTop).toFloat(), activeLinePaint
            )
            val first = l.getLineForVertical(scrollY)
            val last = l.getLineForVertical(scrollY + height)
            val text = text
            for (i in first..last) {
                // number only on real line starts (not wrapped continuations)
                val start = l.getLineStart(i)
                if (start == 0 || (text != null && text[start - 1] == '\n')) {
                    val n = lineNumberAt(start)
                    val baseline = l.getLineBaseline(i) + paddingTop
                    canvas.drawText(n.toString(), sx + gutterWidth - dpF(8), baseline.toFloat(), gutterPaint)
                }
            }
        }
        super.onDraw(canvas)
    }

    // line-number lookup with a small cache so scrolling stays smooth
    private var cacheText: CharSequence? = null
    private var cacheLen = -1
    private var lineStarts = IntArray(0)
    private fun lineNumberAt(offset: Int): Int {
        val t = text ?: return 1
        if (cacheText !== t || cacheLen != t.length) {
            val list = ArrayList<Int>()
            list.add(0)
            for (i in 0 until t.length) if (t[i] == '\n') list.add(i + 1)
            lineStarts = list.toIntArray(); cacheText = t; cacheLen = t.length
        }
        var lo = 0; var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }
}

/** Colours for the editor (light theme, matches the app). */
object Highlighter {
    private const val KEYWORD = 0xFF7B3FB5.toInt()
    private const val STRING = 0xFF2E7D32.toInt()
    private const val COMMENT = 0xFF8A8A98.toInt()
    private const val NUMBER = 0xFFB35C00.toInt()
    private const val TAG = 0xFF1565C0.toInt()
    private const val ATTR = 0xFFAD1457.toInt()
    private const val ANNOT = 0xFF9E7A00.toInt()

    private val KOTLIN = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for", "while",
        "do", "return", "break", "continue", "in", "is", "as", "try", "catch", "finally", "throw", "null", "true", "false",
        "this", "super", "override", "private", "public", "protected", "internal", "open", "abstract", "sealed", "data",
        "enum", "companion", "lateinit", "by", "lazy", "suspend", "inline", "const", "typealias", "init", "constructor",
        "Int", "String", "Boolean", "Long", "Float", "Double", "Unit", "List", "Map", "Set", "Any"
    )
    private val JAVA = setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public", "private", "protected",
        "static", "final", "abstract", "void", "int", "long", "float", "double", "boolean", "char", "byte", "short",
        "if", "else", "for", "while", "do", "switch", "case", "default", "return", "break", "continue", "try", "catch",
        "finally", "throw", "throws", "new", "this", "super", "null", "true", "false", "instanceof", "synchronized",
        "volatile", "String", "const", "struct", "typedef", "include", "define", "var", "let", "function"
    )
    private val GRADLE = KOTLIN + setOf(
        "plugins", "android", "dependencies", "implementation", "api", "kapt", "ksp", "compileSdk", "minSdk", "targetSdk",
        "buildTypes", "release", "debug", "defaultConfig", "repositories", "mavenCentral", "google", "id", "apply",
        "versionCode", "versionName", "applicationId", "namespace", "testImplementation", "androidTestImplementation"
    )
    private val SHELL = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "in", "echo",
        "export", "cd", "exit", "return", "local", "set", "unset", "source", "alias", "test"
    )
    private val PYTHON = setOf(
        "def", "class", "import", "from", "as", "if", "elif", "else", "for", "while", "return", "try", "except",
        "finally", "with", "lambda", "pass", "break", "continue", "None", "True", "False", "and", "or", "not", "in", "is"
    )

    fun keywords(lang: String): Set<String> = when (lang) {
        "kotlin" -> KOTLIN; "java" -> JAVA; "gradle" -> GRADLE; "shell" -> SHELL; "python" -> PYTHON; else -> emptySet()
    }

    fun apply(text: Editable, lang: String) {
        for (sp in text.getSpans(0, text.length, ForegroundColorSpan::class.java)) text.removeSpan(sp)
        if (lang == "text") return
        val s = text.toString()
        fun span(color: Int, start: Int, end: Int) =
            text.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        if (lang == "xml") {
            Regex("<!--[\\s\\S]*?-->").findAll(s).forEach { span(COMMENT, it.range.first, it.range.last + 1) }
            Regex("</?[A-Za-z_][\\w.:-]*").findAll(s).forEach { span(TAG, it.range.first, it.range.last + 1) }
            Regex("[A-Za-z_][\\w.:-]*(?==)").findAll(s).forEach { span(ATTR, it.range.first, it.range.last + 1) }
            Regex("\"[^\"]*\"").findAll(s).forEach { span(STRING, it.range.first, it.range.last + 1) }
            return
        }
        if (lang == "json") {
            Regex("\"(\\\\.|[^\"\\\\])*\"(?=\\s*:)").findAll(s).forEach { span(ATTR, it.range.first, it.range.last + 1) }
            Regex("\"(\\\\.|[^\"\\\\])*\"").findAll(s).forEach {
                if (text.getSpans(it.range.first, it.range.first + 1, ForegroundColorSpan::class.java).isEmpty())
                    span(STRING, it.range.first, it.range.last + 1)
            }
            Regex("\\b(true|false|null)\\b").findAll(s).forEach { span(KEYWORD, it.range.first, it.range.last + 1) }
            Regex("-?\\b\\d+(\\.\\d+)?\\b").findAll(s).forEach {
                if (text.getSpans(it.range.first, it.range.first + 1, ForegroundColorSpan::class.java).isEmpty())
                    span(NUMBER, it.range.first, it.range.last + 1)
            }
            return
        }

        val kw = keywords(lang)
        // order matters: words/numbers first, then strings and comments overwrite them
        Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b").findAll(s).forEach {
            if (it.value in kw) span(KEYWORD, it.range.first, it.range.last + 1)
        }
        Regex("\\b\\d+(\\.\\d+)?[fFLlxX]?\\b").findAll(s).forEach { span(NUMBER, it.range.first, it.range.last + 1) }
        if (lang == "kotlin" || lang == "java" || lang == "gradle")
            Regex("@[A-Za-z_][A-Za-z0-9_]*").findAll(s).forEach { span(ANNOT, it.range.first, it.range.last + 1) }
        val strRe = if (lang == "python" || lang == "shell")
            Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(\\\\.|[^\"\\\\\\n])*\"|'[^'\\n]*'")
        else Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(\\\\.|[^\"\\\\\\n])*\"|'(\\\\.|[^'\\\\\\n])*'")
        strRe.findAll(s).forEach {
            for (sp in text.getSpans(it.range.first, it.range.last + 1, ForegroundColorSpan::class.java)) text.removeSpan(sp)
            span(STRING, it.range.first, it.range.last + 1)
        }
        val commentRe = when (lang) {
            "shell", "python" -> Regex("#[^\\n]*")
            else -> Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")
        }
        commentRe.findAll(s).forEach {
            for (sp in text.getSpans(it.range.first, it.range.last + 1, ForegroundColorSpan::class.java)) text.removeSpan(sp)
            span(COMMENT, it.range.first, it.range.last + 1)
        }
    }
}
