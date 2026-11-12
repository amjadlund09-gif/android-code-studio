package com.example.mynative.androidide

import android.content.Context
import android.graphics.Typeface
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import org.eclipse.tm4e.core.registry.IThemeSource

/** One-time TextMate setup (grammars + themes from assets/textmate). Safe to call many times. */
object ProEditorSetup {

    @Volatile private var done = false

    fun init(ctx: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            runCatching {
                FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(ctx.applicationContext.assets))
                val themes = ThemeRegistry.getInstance()
                EditorSettings.THEMES.forEach { name ->
                    val path = "textmate/$name.json"
                    runCatching {
                        themes.loadTheme(
                            ThemeModel(
                                IThemeSource.fromInputStream(FileProviderRegistry.getInstance().tryGetInputStream(path), path, null),
                                name
                            ).apply { isDark = name != "quietlight" }
                        )
                    }
                }
                GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            }
            done = true
        }
    }

    /** TextMate scope for a file name, or null = plain text. Grammars live in assets/textmate/languages.json. */
    fun scopeFor(fileName: String): String? {
        val n = fileName.lowercase()
        val ext = n.substringAfterLast('.', "")
        return when (ext) {
            "kt", "kts" -> "source.kotlin"
            "java", "gradle", "groovy" -> "source.java"
            // no own grammar yet: the Java grammar is the closest C-style match
            "c", "h", "cc", "cpp", "hpp", "cs", "dart", "scala" -> "source.java"
            "xml", "svg", "plist", "xsd", "xsl" -> "text.xml"
            "html", "htm" -> "text.html.basic"
            "js", "mjs", "cjs", "json", "jsonc", "ts" -> "source.js"
            "py" -> "source.python"
            "md", "markdown" -> "text.html.markdown"
            "lua" -> "source.lua"
            else -> null
        }
    }
}

/**
 * Pro code editor engine (Sora Editor + TextMate grammars).
 *
 * Drop-in replacement for [CodeEditText]: same members used by ProjectActivity
 * (load / undo / redo / find / replace / gotoLine / insertSymbol ...), plus
 * line numbers, sticky scroll, block lines, bracket matching, whitespace + line-ending display,
 * word wrap, ligatures, completion popup, magnifier and selection handles from the library.
 */
class ProEditor(context: Context) : CodeEditor(context) {

    var onContentChanged: (() -> Unit)? = null
    var onCompletionsChanged: ((List<String>) -> Unit)? = null   // kept for API compatibility (the library has its own popup)

    private var applying = false
    private var languageSet = false
    private var currentScope: String? = null
    private var history = UndoHistory()
    private val mono: Typeface = runCatching {
        Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf")
    }.getOrDefault(Typeface.MONOSPACE)

    init {
        ProEditorSetup.init(context)
        setTypefaceText(mono)
        setTypefaceLineNumber(mono)
        setLineSpacing(2f, 1.1f)
        applySettings()
        subscribeEvent(ContentChangeEvent::class.java) { ev, _ ->
            if (!applying && ev.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) onContentChanged?.invoke()
        }
    }

    // ---- settings ---------------------------------------------------------------------------------

    /** Reads [EditorSettings] and applies everything live. */
    fun applySettings() {
        val c = context
        setTextSize(EditorSettings.fontSp(c).toFloat())
        setTabWidth(EditorSettings.tab(c))
        setWordwrap(EditorSettings.wrap(c))
        setLineNumberEnabled(EditorSettings.lineNumbers(c))
        setPinLineNumber(EditorSettings.pinLines(c))
        var flags = 0
        if (EditorSettings.whitespace(c)) {
            flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or CodeEditor.FLAG_DRAW_WHITESPACE_INNER or CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING
        }
        if (EditorSettings.lineEndings(c)) flags = flags or CodeEditor.FLAG_DRAW_LINE_SEPARATOR
        setNonPrintablePaintingFlags(flags)
        props.stickyScroll = EditorSettings.sticky(c)
        props.autoIndent = EditorSettings.autoIndent(c)
        props.symbolPairAutoCompletion = EditorSettings.autoPair(c)
        setCursorBlinkPeriod(if (EditorSettings.blink(c)) 500 else 0)
        setLigatureEnabled(EditorSettings.ligatures(c))
        setBlockLineEnabled(EditorSettings.blockLines(c))
        setHighlightBracketPair(EditorSettings.brackets(c))
        applyTheme()
    }

    private fun applyTheme() {
        runCatching {
            val reg = ThemeRegistry.getInstance()
            reg.setTheme(EditorSettings.theme(context))
            val cs = colorScheme
            colorScheme = if (cs is TextMateColorScheme) cs else TextMateColorScheme.create(reg)
        }
    }

    // ---- file loading -----------------------------------------------------------------------------

    private fun applyLanguage(fileName: String) {
        val scope = ProEditorSetup.scopeFor(fileName)
        if (languageSet && scope == currentScope) return
        currentScope = scope
        languageSet = true
        val lang: Language = if (scope == null) {
            EmptyLanguage()
        } else {
            runCatching<Language> { TextMateLanguage.create(scope, true) }.getOrElse { EmptyLanguage() }
        }
        setEditorLanguage(lang)
    }

    /**
     * Shows [text] for [fileName]. The undo stack lives in the Content object kept inside [h],
     * so every tab keeps its own history (same behaviour as before).
     */
    fun load(text: String, fileName: String, h: UndoHistory) {
        history = h
        val kept = h.content
        val content = if (kept != null && kept.toString() == text) kept else Content(text).also { h.content = it }
        applying = true
        try {
            applyLanguage(fileName)
            setText(content, true, null)
        } finally {
            applying = false
        }
    }

    // ---- selection helpers (offset based, like the old editor) ----------------------------------

    val selectionStart: Int get() = cursor.left
    val selectionEnd: Int get() = cursor.right

    fun length(): Int = text.length

    fun setSelection(offset: Int) {
        val o = offset.coerceIn(0, text.length)
        val p = text.indexer.getCharPosition(o)
        setSelection(p.line, p.column)
    }

    fun gotoLine(line: Int, col: Int = 1) {
        val l = (line - 1).coerceIn(0, text.lineCount - 1)
        val c = (col - 1).coerceIn(0, text.getColumnCount(l))
        setSelection(l, c, true)
    }

    fun insertSymbol(s: String) {
        insertText(s, s.length)
    }

    /** Replaces the identifier before the caret with [word]. */
    fun applyCompletion(word: String) {
        val end = selectionStart
        var st = end
        while (st > 0 && (text[st - 1].isLetterOrDigit() || text[st - 1] == '_')) st--
        text.replace(st, end, word)
    }

    // ---- find / replace ---------------------------------------------------------------------------

    private fun select(index: Int, len: Int): Boolean {
        if (index < 0) return false
        val a = text.indexer.getCharPosition(index)
        val b = text.indexer.getCharPosition(index + len)
        setSelectionRegion(a.line, a.column, b.line, b.column, true)
        return true
    }

    /** Marks all matches in the text (the library draws them); empty query clears. */
    private fun markAll(q: String, ignoreCase: Boolean) {
        runCatching {
            if (q.isEmpty()) searcher.stopSearch()
            else searcher.search(q, EditorSearcher.SearchOptions(ignoreCase, false))
        }
    }

    fun findNext(query: String, from: Int = selectionEnd, ignoreCase: Boolean = true): Boolean {
        markAll(query, ignoreCase)
        if (query.isEmpty()) return false
        val s = text.toString()
        var i = s.indexOf(query, from.coerceIn(0, s.length), ignoreCase)
        if (i < 0) i = s.indexOf(query, 0, ignoreCase)
        return select(i, query.length)
    }

    fun findPrev(query: String, ignoreCase: Boolean = true): Boolean {
        markAll(query, ignoreCase)
        if (query.isEmpty()) return false
        val s = text.toString()
        var i = if (selectionStart > 0) s.lastIndexOf(query, selectionStart - 1, ignoreCase) else -1
        if (i < 0) i = s.lastIndexOf(query, s.length, ignoreCase)
        return select(i, query.length)
    }

    fun replaceCurrent(query: String, with: String): Boolean {
        if (query.isEmpty()) return false
        val a = selectionStart
        val b = selectionEnd
        if (b > a && text.substring(a, b).equals(query, ignoreCase = true)) {
            text.replace(a, b, with)
            setSelection(a + with.length)
            findNext(query, a + with.length)
            return true
        }
        return findNext(query, a)
    }

    fun replaceAll(query: String, with: String): Int {
        if (query.isEmpty()) return 0
        val s = text.toString()
        val hits = ArrayList<Int>()
        var i = s.indexOf(query, 0, true)
        while (i >= 0) {
            hits.add(i)
            i = s.indexOf(query, i + query.length, true)
        }
        if (hits.isEmpty()) return 0
        text.beginBatchEdit()
        try {
            for (k in hits.indices.reversed()) text.replace(hits[k], hits[k] + query.length, with)
        } finally {
            text.endBatchEdit()
        }
        runCatching { searcher.stopSearch() }
        return hits.size
    }
}
