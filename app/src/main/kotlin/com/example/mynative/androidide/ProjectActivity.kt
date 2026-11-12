package com.example.mynative.androidide

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.io.File

/**
 * Project screen: file tree, tabbed code editor, Gradle sync / build with live output,
 * clickable build errors, APK install, logcat and a dependency adder.
 */
class ProjectActivity : AppCompatActivity() {

    private class Doc(
        val file: File,
        var loaded: String,
        var text: String,
        val history: UndoHistory = UndoHistory(),
        var caret: Int = 0,
        var dirty: Boolean = false
    )

    private lateinit var root: File
    private lateinit var workDir: File            // folder that holds gradlew (root, or root/android for Flutter)

    private lateinit var drawer: DrawerLayout
    private lateinit var fileTree: FileTree
    private lateinit var welcome: View
    private lateinit var editorBox: View
    private lateinit var editor: ProEditor
    private lateinit var tabStrip: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findField: EditText
    private lateinit var replaceField: EditText
    private lateinit var suggestScroll: HorizontalScrollView
    private lateinit var suggestBox: LinearLayout
    private lateinit var fileLabel: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var statusChevron: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusBar: ProgressBar
    private lateinit var statusSpinner: ProgressBar
    private lateinit var statusIcon: TextView
    private lateinit var panel: View
    private lateinit var outputText: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var btnSync: TextView
    private lateinit var btnRun: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnInstall: TextView
    private val tabViews = mutableListOf<TextView>()

    private val expanded = mutableSetOf<String>()
    private val docs = LinkedHashMap<File, Doc>()
    private var current: Doc? = null
    private var tab = 0
    private val appLog = StringBuilder()
    private var shellText = ""
    private var runner: ShellRunner? = null
    private var lastApk: File? = null
    private var gitText = ""
    private val gitPrefs by lazy { getSharedPreferences("git", MODE_PRIVATE) }

    // logcat
    private val logcatBuf = StringBuilder()
    private var logcatProc: Process? = null
    private var logcatRoot = false
    private var logcatPkg: String? = null          // set after "install + launch": root logcat is limited to this app
    private var resumed = false                    // screen in front? (no installer popups / result notification then)
    private var pkgReceiver: BroadcastReceiver? = null
    private var lastWasBuild = false               // what a one-tap fix should re-run
    private var lastTask: String? = null           // explicit task from the ▶ menu (null = the variant's default)
    private var lastCheck = false                  // ...and whether that build was the quick ✓ check
    private lateinit var btnCheck: TextView
    private val preflightSeen = HashSet<String>()  // version warnings the user already chose to ignore
    private val ui = Handler(Looper.getMainLooper())
    private var logcatRefreshPending = false

    private val primary = 0xFF5B4FC4.toInt()
    private val dp get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !File(path).isDirectory) {
            Toast.makeText(this, "Project folder not found", Toast.LENGTH_LONG).show()
            finish(); return
        }
        root = File(path)
        workDir = if (!File(root, "gradlew").exists() && File(root, "android/gradlew").exists())
            File(root, "android") else root
        expanded.add(root.path)
        DependencyManager.init(this)

        buildUi()
        log("Opened project: ${root.path}")
        refreshTree()
        setStatus(Status.READY, "Build Output", "Ready")
        updateSyncBadge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                    findBar.visibility == View.VISIBLE -> findBar.visibility = View.GONE
                    current != null -> closeTab(current!!)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_SYNC, false)) sync()
    }

    // ---- UI -------------------------------------------------------------------------------------

    private enum class Status { READY, BUSY, OK, FAIL }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        background = rounded(0x14000000, 14)
        minimumWidth = dp(44); minimumHeight = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
    }

    /** Small key for the symbol / suggestion bars. */
    private fun keyChip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        background = rounded(0x14000000, 10)
        minimumWidth = dp(38); minimumHeight = dp(38)
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun hRow(vararg views: View, pad: Int = 4): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        views.forEach { v ->
            row.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(pad), dp(3), dp(pad), dp(3)) })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAF8FF.toInt())
            fitsSystemWindows = true
        }

        // top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFFF3F0FA.toInt(), 22)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(chip("☰") { drawer.openDrawer(GravityCompat.START) })
        fileLabel = TextView(this).apply {
            textSize = 14f; setTextColor(0xFF444444.toInt()); maxLines = 1
            setPadding(dp(12), 0, dp(8), 0)
        }
        bar.addView(fileLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        btnInstall = chip("⬇") { installApk(false) }.also {
            it.visibility = View.GONE
            it.setOnLongClickListener { installApk(true); true }
        }
        btnSave = chip("💾") { saveCurrent(true) }.also { it.visibility = View.GONE }
        btnCheck = chip("✓") { runBuild(checkOnly = true) }
        btnRun = chip("▶") { if (runner != null) runner?.stop() else showBuildMenu() }.also { it.setOnLongClickListener { showVariantDialog(); true } }
        btnSync = chip("⟳") { sync() }.also { it.setOnLongClickListener { showBuildSettings(); true } }
        bar.addView(btnInstall)
        bar.addView(btnSave, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        bar.addView(btnCheck, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        bar.addView(btnRun, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        bar.addView(btnSync, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        content.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(12), dp(8), dp(12), dp(8))
        })

        // main area: welcome / editor
        val main = android.widget.FrameLayout(this)
        welcome = buildWelcome()
        editorBox = buildEditorArea()
        editorBox.visibility = View.GONE
        main.addView(welcome, MATCH_PARENT, MATCH_PARENT)
        main.addView(editorBox, MATCH_PARENT, MATCH_PARENT)
        content.addView(main, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // output panel (hidden until opened)
        panel = buildPanel()
        panel.visibility = View.GONE
        content.addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, dp(280)).apply { setMargins(dp(12), 0, dp(12), 0) })

        // status card
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFFF3F0FA.toInt(), 24)
            elevation = dp(3).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                statusChevron.text = if (panel.visibility == View.VISIBLE) "⌄" else "⌃"
            }
        }
        statusCard = status
        statusSpinner = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        statusIcon = TextView(this).apply {
            textSize = 16f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1F2E7D32) }
        }
        status.addView(statusSpinner, dp(30), dp(30))
        status.addView(statusIcon, dp(30), dp(30))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = TextView(this).apply { textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK) }
        statusSub = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()); maxLines = 1 }
        statusBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; max = 100; visibility = View.GONE
            runCatching {
                progressTintList = android.content.res.ColorStateList.valueOf(primary)
                indeterminateTintList = android.content.res.ColorStateList.valueOf(primary)
            }
        }
        texts.addView(statusTitle); texts.addView(statusSub)
        texts.addView(statusBar, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).apply { topMargin = dp(6) })
        status.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        statusChevron = TextView(this).apply { text = "⌃"; textSize = 20f; setTextColor(0xFF777777.toInt()); gravity = Gravity.CENTER }
        status.addView(statusChevron, dp(32), dp(32))
        content.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(12), dp(6), dp(12), dp(12))
        })

        // drawer = file tree
        val drawerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAF8FF.toInt())
            fitsSystemWindows = true
        }
        fileTree = FileTree(
            this, root, primary,
            onOpen = { openFile(it) },
            currentFile = { current?.file },
            onGone = { dropDocs(it) },
            tools = listOf(
                FileTree.Tool(R.drawable.ic_link, "Git") { showGitMenu() },
                FileTree.Tool(R.drawable.ic_apps, "Add dependency") { showAddDependency() },
                FileTree.Tool(R.drawable.ic_list, "Build variant") { drawer.closeDrawer(GravityCompat.START); showVariantDialog() },
                FileTree.Tool(R.drawable.ic_settings, "Build settings") { drawer.closeDrawer(GravityCompat.START); showBuildSettings() }
            )
        )
        drawerBox.addView(fileTree.view, MATCH_PARENT, MATCH_PARENT)

        drawer = DrawerLayout(this)
        drawer.addView(content, DrawerLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        drawer.addView(drawerBox, DrawerLayout.LayoutParams(dp(320), MATCH_PARENT, Gravity.START))
        setContentView(drawer)
    }

    private fun buildEditorArea(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // file tabs
        tabStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabStrip)
            setPadding(dp(8), 0, dp(8), 0)
        }, MATCH_PARENT, WRAP_CONTENT)

        // find / replace bar
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF3F0FA.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        findField = EditText(this).apply {
            hint = "Find"; textSize = 14f; setSingleLine(); typeface = Typeface.MONOSPACE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString().orEmpty()
                    if (q.isNotEmpty()) editor.findNext(q, editor.selectionStart)
                }
            })
        }
        replaceField = EditText(this).apply { hint = "Replace with"; textSize = 14f; setSingleLine(); typeface = Typeface.MONOSPACE }
        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row1.addView(findField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row1.addView(keyChip("▲") { if (!editor.findPrev(findField.text.toString())) noMatch() })
        row1.addView(keyChip("▼") { if (!editor.findNext(findField.text.toString())) noMatch() })
        row1.addView(keyChip("✕") { findBar.visibility = View.GONE })
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row2.addView(replaceField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row2.addView(keyChip("Replace") {
            if (!editor.replaceCurrent(findField.text.toString(), replaceField.text.toString())) noMatch()
        })
        row2.addView(keyChip("All") {
            val n = editor.replaceAll(findField.text.toString(), replaceField.text.toString())
            Toast.makeText(this, "$n replaced", Toast.LENGTH_SHORT).show()
        })
        findBar.addView(row1); findBar.addView(row2)
        box.addView(findBar, MATCH_PARENT, WRAP_CONTENT)

        // the editor itself
        editor = ProEditor(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            onContentChanged = { onEditorChanged() }
            onCompletionsChanged = { showSuggestions(it) }
        }
        box.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // word suggestions
        suggestBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        suggestScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setBackgroundColor(0xFFEFEBF9.toInt())
            addView(suggestBox)
        }
        box.addView(suggestScroll, MATCH_PARENT, WRAP_CONTENT)

        // symbol bar
        val keys = mutableListOf<View>()
        keys.add(keyChip("↶") { editor.undo() })
        keys.add(keyChip("↷") { editor.redo() })
        keys.add(keyChip("🔍") { toggleFind() })
        keys.add(keyChip("⚙") { EditorSettingsDialog.show(this) { editor.applySettings() } })
        keys.add(keyChip("⇥") { editor.insertSymbol("    ") })
        listOf("{", "}", "(", ")", "[", "]", ";", "\"", "'", "=", "<", ">", "/", "\\", ":", ",", ".", "!", "&", "|", "_", "#", "$", "%", "*", "+", "-")
            .forEach { s -> keys.add(keyChip(s) { editor.insertSymbol(s) }) }
        box.addView(hRow(*keys.toTypedArray(), pad = 3).apply { setBackgroundColor(0xFFF3F0FA.toInt()) }, MATCH_PARENT, WRAP_CONTENT)
        return box
    }

    private fun noMatch() = Toast.makeText(this, "No match", Toast.LENGTH_SHORT).show()

    private fun toggleFind() {
        if (findBar.visibility == View.VISIBLE) { findBar.visibility = View.GONE; return }
        findBar.visibility = View.VISIBLE
        val sel = editor.text?.let { t ->
            val a = minOf(editor.selectionStart, editor.selectionEnd); val b = maxOf(editor.selectionStart, editor.selectionEnd)
            if (b > a && b - a < 80) t.subSequence(a, b).toString() else ""
        }.orEmpty()
        if (sel.isNotEmpty()) findField.setText(sel)
        findField.requestFocus()
    }

    private fun showSuggestions(list: List<String>) {
        suggestBox.removeAllViews()
        list.forEach { w ->
            suggestBox.addView(keyChip(w) { editor.applyCompletion(w) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        suggestScroll.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildWelcome(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = root.name; textSize = 30f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK)
        })
        titleRow.addView(TextView(this).apply {
            text = if (isGradleProject()) "Gradle project" else "Project"
            textSize = 14f; setTextColor(primary); setTypeface(null, Typeface.BOLD)
            background = rounded(0xFFE8E4F5.toInt(), 8)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        box.addView(titleRow)
        box.addView(TextView(this).apply {
            text = "📂  Browse Files"
            textSize = 18f; setTextColor(Color.BLACK)
            background = rounded(0xFFF3F0FA.toInt(), 16)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        return box
    }

    private val panelTabs = listOf("Output", "Problems", "App Logs", "Logcat", "Git")

    private fun buildPanel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFF6F4FB.toInt(), 20)
            clipToOutline = true
            elevation = dp(2).toFloat()
        }
        val tabs = LinearLayout(this).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
        panelTabs.forEachIndexed { i, name ->
            val t = TextView(this).apply {
                text = name; textSize = 14f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { selectPanelTab(i) }
                if (i == 3) setOnLongClickListener {
                    logcatRoot = !logcatRoot
                    Toast.makeText(this@ProjectActivity, if (logcatRoot) "Logcat: root mode ON" else "Logcat: root mode OFF", Toast.LENGTH_SHORT).show()
                    stopLogcat(); logcatBuf.setLength(0)
                    if (tab == 3) startLogcat()
                    true
                }
            }
            tabViews.add(t)
            tabs.addView(t)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), dp(8), dp(2)) }
        header.addView(tabScroll, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fun small(c: TextView) = c.apply {
            minimumWidth = dp(40); minimumHeight = dp(40); textSize = 16f; setPadding(dp(8), 0, dp(8), 0)
        }
        val gap = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(4) } }
        header.addView(small(chip("🧹") { clearCurrentPanelTab() }), gap())
        header.addView(small(chip("⧉") { copyOutput() }), gap())
        header.addView(small(chip("⤴") { shareOutput() }), gap())
        box.addView(header)
        box.addView(View(this).apply { setBackgroundColor(0x14000000) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        box.addView(buildFixBar())
        outputText = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(0xFF1C1B22.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)                       // long-press to select + copy lines
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = 0x335B4FC4
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        outputScroll = ScrollView(this).apply {
            addView(outputText)
            // follow the newest line only while the user is at the bottom; scrolling up pauses it
            setOnScrollChangeListener { v, _, y, _, _ ->
                val sv = v as ScrollView
                val content = sv.getChildAt(0) ?: return@setOnScrollChangeListener
                followTail = y + sv.height >= content.height - dp(48)
            }
        }
        box.addView(outputScroll, MATCH_PARENT, MATCH_PARENT)
        refreshOutput()
        return box
    }

    private fun selectPanelTab(i: Int) {
        if (tab == 3 && i != 3) stopLogcat()
        tab = i
        followTail = true
        if (i == 3) startLogcat()
        refreshOutput()
    }

    private fun clearCurrentPanelTab() {
        when (tab) {
            0, 1 -> shellText = ""
            2 -> appLog.setLength(0)
            3 -> logcatBuf.setLength(0)
            4 -> gitText = ""
        }
        refreshOutput()
    }

    // ---- status / output -----------------------------------------------------------------------

    private fun setStatus(s: Status, title: String, sub: String) {
        statusTitle.text = title
        statusSub.text = sub
        statusSpinner.visibility = if (s == Status.BUSY) View.VISIBLE else View.GONE
        statusIcon.visibility = if (s == Status.BUSY) View.GONE else View.VISIBLE
        if (::statusBar.isInitialized) {
            statusBar.visibility = if (s == Status.BUSY) View.VISIBLE else View.GONE
            if (s == Status.BUSY) statusBar.isIndeterminate = true
        }
        val (cardBg, accent) = when (s) {
            Status.READY -> 0xFFF3F0FA.toInt() to 0xFF5B4FC4.toInt()
            Status.OK -> 0xFFE8F5E9.toInt() to 0xFF2E7D32.toInt()
            Status.FAIL -> 0xFFFDECEA.toInt() to 0xFFB3261E.toInt()
            Status.BUSY -> 0xFFEDEBFA.toInt() to 0xFF5B4FC4.toInt()
        }
        if (::statusCard.isInitialized) statusCard.background = rounded(cardBg, 24)
        (statusIcon.background as? GradientDrawable)?.setColor((accent and 0x00FFFFFF) or 0x26000000)
        statusIcon.setTextColor(accent)
        statusTitle.setTextColor(if (s == Status.FAIL) 0xFF8C1D18.toInt() else Color.BLACK)
        when (s) {
            Status.READY, Status.OK -> statusIcon.text = "✔"
            Status.FAIL -> statusIcon.text = "✖"
            Status.BUSY -> {}
        }
    }

    private fun log(line: String) { appLog.append(line).append('\n') }

    private val pathRe = Regex("(/[^\\s:'\"()]+\\.(?:kt|kts|java|xml|gradle|json|cpp|c|h|properties|toml|md|txt)):(\\d+)(?::(\\d+))?")
    private val buildFileRe = Regex("Build file '([^']+)' line: (\\d+)")

    /** Colours error/warning lines and turns file:line references into tappable links. */
    private fun styleOutput(text: String, links: Boolean, numbered: Boolean = false): CharSequence {
        val rawLines = text.split('\n')
        val okRun = links && buildOk(text)
        val width = rawLines.size.toString().length
        val sb = SpannableStringBuilder()
        if (numbered) {
            rawLines.forEachIndexed { i, ln ->
                sb.append((i + 1).toString().padStart(width)).append("  ").append(ln)
                if (i < rawLines.size - 1) sb.append('\n')
            }
        } else sb.append(text)
        val gutter = if (numbered) width + 2 else 0
        var off = 0
        for (line in rawLines) {
            val len = line.length
            if (numbered) {
                sb.setSpan(ForegroundColorSpan(0xFF9A9A9A.toInt()), off, off + gutter, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                off += gutter
            }
            val l = line.lowercase()
            val color = when {
                !okRun && (errLineRe.containsMatchIn(line) || (!numbered && !links && line.contains(" E/"))) -> 0xFFB3261E.toInt()
                line.startsWith("w: ") || "warning" in l || line.contains(" W/") -> 0xFFB26A00.toInt()
                "build successful" in l || "configure successful" in l -> 0xFF2E7D32.toInt()
                else -> 0
            }
            if (len > 0 && color != 0) {
                sb.setSpan(ForegroundColorSpan(color), off, off + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                val tint = when (color) {
                    0xFFB3261E.toInt() -> 0x1AB3261E
                    0xFFB26A00.toInt() -> 0x1AB26A00
                    else -> 0
                }
                if (tint != 0) sb.setSpan(BackgroundColorSpan(tint), off, off + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (line.startsWith("BUILD ") || line.startsWith("* What went wrong") || line.startsWith("FAILURE:"))
                    sb.setSpan(StyleSpan(Typeface.BOLD), off, off + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (len > 0 && links) {
                // Gradle task headers + sync markers: dim / accent so the real errors stand out
                if (line.startsWith("> Task ") || line.startsWith("> Configure")) {
                    sb.setSpan(ForegroundColorSpan(0xFF8A8A99.toInt()), off, off + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (line.startsWith("[sync]") || line.startsWith("Running:") || line.startsWith("gradlew ")) {
                    sb.setSpan(ForegroundColorSpan(primary), off, off + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), off, off + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (links && len > 0) {
                pathRe.findAll(line).forEach { m ->
                    val p = "/" + m.groupValues[1].trimStart('/')
                    val ln = m.groupValues[2].toIntOrNull() ?: 1
                    val col = m.groupValues[3].toIntOrNull() ?: 1
                    addLink(sb, off + m.range.first, off + m.range.last + 1, p, ln, col)
                }
                buildFileRe.find(line)?.let { m ->
                    addLink(sb, off + m.range.first, off + m.range.last + 1, m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 1, 1)
                }
            }
            off += len + 1
        }
        return sb
    }

    private fun addLink(sb: SpannableStringBuilder, start: Int, end: Int, path: String, line: Int, col: Int) {
        sb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) { openFileAt(path, line, col) }
            override fun updateDrawState(ds: TextPaint) { ds.color = primary; ds.isUnderlineText = true }
        }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun openFileAt(path: String, line: Int, col: Int) {
        val f = File(path)
        if (!f.isFile) { Toast.makeText(this, "File not found: ${f.name}", Toast.LENGTH_SHORT).show(); return }
        panel.visibility = View.GONE
        openFile(f, line, col)
    }

    /** Error lines + the whole "What went wrong" block (its indented detail lines hold the real reason). */
    /** Real error lines only (not every line that happens to contain "error" / "exception" in a name). */
    private val errLineRe = Regex(
        "^e: |: error:|^error:|^FAILURE:|^BUILD FAILED|^\\* What went wrong|^Execution failed for task|AAPT: error|" +
            "^[\\w.$]+(?:Exception|Error)(?::|$)|^Caused by: |^\\s*> (?:Could not|Failed|Execution failed|Compilation error)"
    )

    /** A finished successful run never shows red lines or problems. */
    private fun buildOk(text: String) = text.contains("BUILD SUCCESSFUL") || text.contains("CONFIGURE SUCCESSFUL")

    private fun problemLines(text: String): String {
        if (buildOk(text)) return ""
        val out = ArrayList<String>()
        var inBlock = false
        for (line in text.lines()) {
            if (line.startsWith("* What went wrong")) { inBlock = true; out.add(line); continue }
            if (inBlock && (line.startsWith("* Try") || line.startsWith("BUILD "))) inBlock = false
            if (inBlock) { if (line.isNotBlank()) out.add(line); continue }
            if (errLineRe.containsMatchIn(line)) out.add(line)
        }
        return out.joinToString("\n")
    }

    private fun refreshOutput() {
        tabViews.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) primary else 0xFF666666.toInt())
            t.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
            t.background = if (i == tab) rounded(0xFFE2E0F2.toInt(), 12) else null
        }
        val shown: CharSequence = when (tab) {
            0 -> styleOutput(collapseStack(shellText.trimEnd()).ifBlank { "No build yet." }, true, numbered = true)
            1 -> styleOutput(problemLines(shellText).ifBlank { "No problems." }, true)
            2 -> appLog.toString().ifBlank { "No logs." }
            4 -> styleDiff(gitText.ifBlank { "No git output yet. Open the Git menu in the file drawer." })
            else -> styleOutput(
                logcatBuf.toString().ifBlank {
                    "Waiting for logcat...\n\nWithout root you only see this app's own logs.\n" +
                        "Long-press the Logcat tab to switch root mode (su -c logcat)."
                }, false
            )
        }
        val follow = followTail
        outputText.text = shown
        if (follow) {
            // scroll in the same layout pass as the new text, so the view never flashes at the top
            outputText.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                    outputText.removeOnLayoutChangeListener(this)
                    if (followTail) scrollOutputToEnd()
                }
            })
            outputScroll.post { if (followTail) scrollOutputToEnd() }
        }
    }

    private var followTail = true
    private var outRefreshPending = false
    private val outRefresh = Runnable { outRefreshPending = false; refreshOutput() }

    /** Live output arrives many times a second: redraw at most every 150 ms (less CPU, no flicker). */
    private fun refreshOutputSoon() {
        if (outRefreshPending) return
        outRefreshPending = true
        ui.postDelayed(outRefresh, 150)
    }

    private fun scrollOutputToEnd() {
        val c = outputScroll.getChildAt(0) ?: return
        val target = c.height - outputScroll.height
        if (target > 0) outputScroll.scrollTo(0, target)
    }

    /** Folds runs of Java stack frames ("at org.gradle...") into one line; Copy / Share still give the full text. */
    private fun collapseStack(text: String): String {
        val frame = Regex("^\\s+at [\\w$.<>/]+\\(.*\\)\\s*$|^\\s*\\.\\.\\. \\d+ more\\s*$")
        val out = StringBuilder()
        var n = 0
        fun flush() { if (n > 0) { out.append("    ⋯ ").append(n).append(" stack lines hidden (Copy / Share keep them)\n"); n = 0 } }
        for (ln in text.split('\n')) {
            if (frame.containsMatchIn(ln)) n++ else { flush(); out.append(ln).append('\n') }
        }
        flush()
        return out.toString().trimEnd('\n')
    }

    /** The text of the open tab without line numbers (what Copy and Share use). */
    private fun rawOutput(): String = when (tab) {
        0 -> shellText.trimEnd()
        1 -> problemLines(shellText)
        2 -> appLog.toString()
        4 -> gitText
        else -> logcatBuf.toString()
    }

    private fun copyOutput() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("output", rawOutput()))
        Toast.makeText(this, "Output copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareOutput() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, rawOutput()),
                "Share output"
            )
        )
    }


    // ---- git ------------------------------------------------------------------------------------

    private fun styleDiff(text: String): CharSequence {
        val sb = SpannableStringBuilder(text)
        var off = 0
        for (line in text.split('\n')) {
            val len = line.length
            val color = when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff --git") -> 0
                line.startsWith("+") -> 0xFF2E7D32.toInt()
                line.startsWith("-") -> 0xFFB3261E.toInt()
                line.startsWith("@@") -> 0xFF1565C0.toInt()
                line.startsWith("Error") -> 0xFFB3261E.toInt()
                else -> 0
            }
            if (len > 0 && color != 0) sb.setSpan(ForegroundColorSpan(color), off, off + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (len > 0 && (line.startsWith("diff --git") || line.startsWith("+++") || line.startsWith("---")))
                sb.setSpan(StyleSpan(Typeface.BOLD), off, off + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            off += len + 1
        }
        return sb
    }

    private fun promptFields(
        title: String, hints: List<String>, defaults: List<String>,
        secret: Set<Int> = emptySet(), checkText: String? = null, checkDefault: Boolean = false,
        onOk: (List<String>, Boolean) -> Unit
    ) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(4)) }
        val fields = hints.mapIndexed { i, h ->
            EditText(this).apply {
                hint = h; setSingleLine(); setText(defaults.getOrElse(i) { "" })
                inputType = if (i in secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }.also { box.addView(it) }
        }
        val check = checkText?.let { t -> CheckBox(this).apply { text = t; isChecked = checkDefault }.also { box.addView(it) } }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(fields.map { it.text.toString().trim() }, check?.isChecked ?: false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun withCreds(title: String, then: (String, String) -> Unit) {
        val savedToken = gitPrefs.getString("token", "") ?: ""
        promptFields(
            title, listOf("Username (optional)", "Token / password (blank = none)"),
            listOf(gitPrefs.getString("user", "") ?: "", savedToken),
            secret = setOf(1), checkText = "Remember token on this device", checkDefault = savedToken.isNotEmpty()
        ) { v, remember ->
            gitPrefs.edit().putString("user", v[0]).apply {
                if (remember) putString("token", v[1]) else remove("token")
            }.apply()
            then(v[0], v[1])
        }
    }

    private fun showGitMenu() {
        val repo = GitManager.isRepo(root)
        val items = if (!repo) arrayOf("Init repository here", "Clone repository into new project…")
        else arrayOf("Status", "Diff", "Commit all…", "Log", "Pull", "Push", "Branches…", "Remote URL…")
        AlertDialog.Builder(this)
            .setTitle("Git")
            .setItems(items) { _, i ->
                when (items[i]) {
                    "Init repository here" -> runGit("git init") { GitManager.init(root) }
                    "Clone repository into new project…" -> promptClone()
                    "Status" -> runGit("git status") { GitManager.status(root) }
                    "Diff" -> runGit("git diff") { GitManager.diff(root) }
                    "Commit all…" -> promptCommit()
                    "Log" -> runGit("git log") { GitManager.log(root) }
                    "Pull" -> withCreds("Pull") { u, t -> runGit("git pull", reload = true) { GitManager.pull(root, u, t) } }
                    "Push" -> withCreds("Push") { u, t -> runGit("git push") { GitManager.push(root, u, t) } }
                    "Branches…" -> showBranches()
                    "Remote URL…" -> promptRemote()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runGit(title: String, reload: Boolean = false, block: () -> String) {
        saveAll()
        setStatus(Status.BUSY, "Git", title)
        Thread {
            val out = try { block() } catch (e: Throwable) { "Error: ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread {
                gitText = "$ $title\n\n$out"
                setStatus(if (out.startsWith("Error")) Status.FAIL else Status.OK, "Git", title)
                panel.visibility = View.VISIBLE
                selectPanelTab(4)
                if (reload) reloadCleanDocs()
                refreshTree()
            }
        }.start()
    }

    /** After pull / checkout: re-read open files that have no unsaved edits. */
    private fun reloadCleanDocs() {
        docs.values.filter { !it.dirty }.forEach {
            runCatching { val t = it.file.readText(); it.loaded = t; it.text = t }
        }
        current?.let { if (!it.dirty && it.file.isFile) showDoc(it) }
    }

    private fun promptCommit() {
        promptFields(
            "Commit all changes", listOf("Commit message", "Your name", "Your email"),
            listOf("", gitPrefs.getString("name", "") ?: "", gitPrefs.getString("email", "") ?: "")
        ) { v, _ ->
            if (v[0].isEmpty()) { Toast.makeText(this, "Message is empty", Toast.LENGTH_SHORT).show(); return@promptFields }
            val name = v[1].ifBlank { "AndroidIDE" }
            val email = v[2].ifBlank { "ide@localhost" }
            gitPrefs.edit().putString("name", name).putString("email", email).apply()
            runGit("git commit") { GitManager.commitAll(root, v[0], name, email) }
        }
    }

    private fun promptRemote() {
        val current = runCatching { GitManager.remoteUrl(root) }.getOrDefault("")
        promptFields("Remote (origin) URL", listOf("https://github.com/user/repo.git"), listOf(current)) { v, _ ->
            if (v[0].isNotEmpty()) runGit("git remote") { GitManager.setRemote(root, v[0]) }
        }
    }

    private fun promptClone() {
        promptFields(
            "Clone repository", listOf("Repository URL", "Folder name (optional)", "Username (optional)", "Token (optional)"),
            listOf("", "", gitPrefs.getString("user", "") ?: "", gitPrefs.getString("token", "") ?: ""),
            secret = setOf(3)
        ) { v, _ ->
            val url = v[0]
            if (url.isEmpty()) return@promptFields
            val folder = v[1].ifBlank { url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" } }
            val dest = File(root.parentFile ?: root, folder)
            if (dest.exists() && dest.list()?.isNotEmpty() == true) {
                Toast.makeText(this, "Folder already exists: ${dest.name}", Toast.LENGTH_LONG).show(); return@promptFields
            }
            setStatus(Status.BUSY, "Cloning...", url)
            Thread {
                val result = try { GitManager.clone(url, dest, v[2], v[3]); null } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
                runOnUiThread {
                    if (result == null) {
                        setStatus(Status.OK, "Cloned", dest.name)
                        startActivity(Intent(this, ProjectActivity::class.java).putExtra(EXTRA_PATH, dest.path))
                    } else {
                        gitText = "$ git clone $url\n\nError: $result"
                        setStatus(Status.FAIL, "Clone failed", result.take(80))
                        panel.visibility = View.VISIBLE; selectPanelTab(4)
                    }
                }
            }.start()
        }
    }

    private fun showBranches() {
        Thread {
            val res = try { GitManager.branches(root) } catch (e: Throwable) { null }
            runOnUiThread {
                if (res == null) { Toast.makeText(this, "Cannot read branches (commit first?)", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val (cur, names) = res
                val labels = names.map { if (it == cur) "● $it (current)" else it } + "+ New branch…"
                AlertDialog.Builder(this)
                    .setTitle("Branches")
                    .setItems(labels.toTypedArray()) { _, i ->
                        if (i == names.size) {
                            promptFields("New branch", listOf("Branch name"), listOf("")) { v, _ ->
                                if (v[0].isNotEmpty()) runGit("git checkout -b ${v[0]}", reload = true) { GitManager.checkout(root, v[0], true) }
                            }
                        } else if (names[i] != cur) {
                            runGit("git checkout ${names[i]}", reload = true) { GitManager.checkout(root, names[i], false) }
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }.start()
    }

    // ---- logcat ---------------------------------------------------------------------------------

    private fun startLogcat() {
        if (logcatProc != null) return
        Thread {
            try {
                val pkg = logcatPkg
                val cmd = when {
                    logcatRoot && pkg != null -> listOf("su", "-c",
                        // wait up to 6 s for the app's process, then follow only its log
                        "p=; for i in 1 2 3 4 5 6; do p=\$(pidof -s $pkg); [ -n \"\$p\" ] && break; sleep 1; done; " +
                            "if [ -n \"\$p\" ]; then logcat -v time --pid=\$p; else logcat -v time -T 300; fi")
                    logcatRoot -> listOf("su", "-c", "logcat -v time -T 300")
                    else -> listOf("logcat", "-v", "time", "-T", "300")
                }
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                logcatProc = p
                p.inputStream.bufferedReader().useLines { seq ->
                    for (line in seq) {
                        synchronized(logcatBuf) {
                            logcatBuf.append(line).append('\n')
                            if (logcatBuf.length > 150_000) logcatBuf.delete(0, logcatBuf.length - 100_000)
                        }
                        scheduleLogcatRefresh()
                    }
                }
            } catch (e: Exception) {
                logcatBuf.append("logcat failed: ${e.message}\n")
                scheduleLogcatRefresh()
            }
            logcatProc = null
        }.start()
    }

    private fun scheduleLogcatRefresh() {
        if (logcatRefreshPending) return
        logcatRefreshPending = true
        ui.postDelayed({
            logcatRefreshPending = false
            if (tab == 3) refreshOutput()
        }, 400)
    }

    private fun stopLogcat() {
        logcatProc?.destroy()
        logcatProc = null
    }

    // ---- Gradle ---------------------------------------------------------------------------------

    private fun isGradleProject() =
        listOf("settings.gradle", "settings.gradle.kts").any { File(workDir, it).exists() }

    private var gradleStartMs = 0L
    private val autoSyncRun = Runnable {
        if (runner == null && GradleTuner.needsSync(this, workDir)) sync(auto = true)
    }

    private var lastSkipTapMs = 0L

    /**
     * Smart sync: nothing changed -> no Gradle run (tap again within 4s to force);
     * only module build files changed -> sync just those modules; anything else -> sync all.
     */
    private fun sync(auto: Boolean = false) {
        if (runner != null) { runner?.stop(); return }
        val scope = GradleTuner.syncScope(this, workDir)
        val now = System.currentTimeMillis()
        if (scope.upToDate && isGradleProject() && now - lastSkipTapMs > 4000) {
            lastSkipTapMs = now
            setStatus(Status.OK, "Already synced", "Nothing changed · tap ⟳ again to force a sync")
            return
        }
        lastSkipTapMs = 0
        lastWasBuild = false
        val task = scope.modules?.takeIf { it.isNotEmpty() }
            ?.joinToString(" ") { ":$it:ideResolveDeps" } ?: "ideResolveDeps"
        if (preflightBlocks()) return
        sdkPreflight(auto) { runGradle("Syncing project...", "Sync finished", task, isSync = true) }
    }

    private val sdkSeen = HashSet<String>()     // missing-SDK warnings the user chose to run through

    /**
     * Before Gradle starts: the SDK Manager's parts this project needs (platform, build-tools, NDK, CMake,
     * Flutter SDK) must be on the phone. Missing -> dialog "Install now" (opens the SDK Manager with exactly
     * those packages) instead of a long build that ends in a red error. [quiet] = automatic sync: just skip.
     */
    private fun sdkPreflight(quiet: Boolean = false, go: () -> Unit) {
        if (!isGradleProject()) { go(); return }
        val flutterMissing = GradleTuner.usesFlutter(workDir) && !FlutterInstaller.isInstalled(this)
        Thread {
            val needs = ArrayList(
                runCatching { SdkNeeds.scan(workDir, SdkRepository.sdkRoot(this), SdkIndex.cached(this)) }.getOrDefault(emptyList())
            )
            if (flutterMissing) needs.add(SdkNeeds.Need("flutter", "Flutter SDK (flutter.sdk)"))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val sig = needs.joinToString("|") { it.id }
                if (needs.isEmpty() || sig in sdkSeen) { go(); return@runOnUiThread }
                if (quiet) { updateSyncBadge(); return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setTitle("SDK parts missing")
                    .setMessage(needs.joinToString("\n") { "• ${SdkNeeds.label(it.id)} - ${it.why}" } +
                        "\n\nInstall them in the SDK Manager first, otherwise the build will fail.")
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Run anyway") { _, _ -> sdkSeen.add(sig); go() }
                    .setPositiveButton("Install now") { _, _ ->
                        startActivity(Intent(this, SdkManagerActivity::class.java)
                            .putExtra(SdkManagerActivity.EXTRA_IDS, needs.map { it.id }.toTypedArray()))
                    }
                    .show()
            }
        }.start()
    }

    /**
     * Build. When build files changed since the last good sync/build, the (module-scoped if possible)
     * sync is chained in front of the build in ONE Gradle run: no second JVM start, and a wrong
     * dependency fails in the sync step before any compile work starts.
     */
    private fun runBuild(checkOnly: Boolean = false, taskOverride: String? = null) {
        if (runner != null) { runner?.stop(); return }
        lastWasBuild = true
        lastCheck = checkOnly
        lastTask = taskOverride
        if (preflightBlocks()) return
        var pre: String? = null
        if (GradleTuner.syncBeforeBuild(this) && isGradleProject() && File(workDir, "gradlew").exists()) {
            val scope = GradleTuner.syncScope(this, workDir)
            if (!scope.upToDate) {
                pre = scope.modules?.takeIf { it.isNotEmpty() }
                    ?.joinToString(" ") { ":$it:ideResolveDeps" } ?: "ideResolveDeps"
            }
        }
        val task = taskOverride ?: if (checkOnly) chooseCheckTask() else chooseBuildTask()
        val v = BuildVariants.current(this, workDir)?.name?.let { " ($it)" } ?: ""
        val verb = if (checkOnly) "Checking" else "Building"
        val done = if (checkOnly) "Check finished" else "Build finished"
        sdkPreflight {
            if (pre != null) runGradle("Syncing + ${verb.lowercase()}$v...", done, task, isSync = false, preSync = pre)
            else runGradle("$verb$v...", done, task, isSync = false)
        }
    }

    /** assembleDebug for anything that has the Android plugin, plain `build` otherwise. */
    private fun chooseBuildTask(): String {
        BuildVariants.current(this, workDir)?.let { v ->
            return if (BuildVariants.bundle(this, workDir)) BuildVariants.bundleTask(v) else BuildVariants.assembleTask(v)
        }
        if (File(workDir, "app").isDirectory) return "assembleDebug"
        val androidRe = Regex("com\\.android\\.(application|library)|android\\.(application|library)\\b")
        val hasAndroid = GradleTuner.syncFiles(workDir)
            .filter { it.name.startsWith("build.gradle") }
            .any { f -> runCatching { androidRe.containsMatchIn(f.readText()) }.getOrDefault(false) }
        return if (hasAndroid) "assembleDebug" else "build"
    }

    /** ✓ quick check: compile the selected variant's sources only (no resources merge into an APK, no dex, no packaging). */
    private fun chooseCheckTask(): String {
        BuildVariants.current(this, workDir)?.let { return BuildVariants.checkTask(it) }
        return if (chooseBuildTask() == "build") "classes" else "compileDebugSources"
    }

    private fun runGradle(
        busyTitle: String, doneTitle: String, task: String, isSync: Boolean,
        forceOnline: Boolean = false, carry: String = "", refresh: Boolean = false,
        preSync: String? = null, memMode: Int = GradleTuner.MEM_NORMAL
    ) {
        if (runner != null) {                        // second tap = stop
            runner?.stop(); return
        }
        if (carry.isEmpty() && BuildService.running) {   // the screen was closed while a build kept running
            Toast.makeText(this, "A build is still running in the background - use Stop in its notification", Toast.LENGTH_LONG).show()
            return
        }
        if (!File(workDir, "gradlew").exists()) {
            setStatus(Status.FAIL, "Not a Gradle project", "gradlew not found")
            log("gradlew not found in ${workDir.path}")
            return
        }
        saveAll()
        panel.visibility = View.VISIBLE
        selectPanelTab(0)
        shellText = carry
        lastApk = null
        btnInstall.visibility = View.GONE
        refreshOutput()
        setStatus(Status.BUSY, busyTitle, "Starting Gradle...")
        btnSync.text = "■"; btnRun.text = "■"
        btnSync.setTextColor(Color.BLACK)
        if (::fixBar.isInitialized) fixBar.visibility = View.GONE

        // local.properties: tell Gradle where the SDK is (and fix it if the SDK moved)
        runCatching { GradleTuner.ensureLocalProperties(this, workDir) }

        val offline = (isSync || preSync != null) && !forceOnline && GradleTuner.offlineFirst(this) && !GradleTuner.cacheLooksEmpty(this)
        val cmd = GradleTuner.command(this, workDir, task, isSync, offline, refresh, preSync, memMode)
        val totalKey = GradleTuner.totalKey(task, preSync)
        val total = GradleTuner.lastTaskTotal(this, workDir, totalKey)
        gradleStartMs = System.currentTimeMillis()
        val fast = GradleTuner.fastMode(this)
        log("Running: gradlew ${if (preSync != null) "$preSync " else ""}$task" + (if (offline) " (offline-first)" else "") + (if (fast) " [fast mode]" else ""))

        val shell = ShellRunner(this)
        shell.keepDaemon = fast
        runner = shell
        followTail = true
        var failFastHit = false
        BuildService.begin(this, busyTitle, workDir.path) { runner?.stop() }
        shell.start(cmd,
            echo = "gradlew ${if (preSync != null) "$preSync " else ""}$task" + (if (offline) " --offline" else "") + (if (refresh) " --refresh-dependencies" else "") + (if (fast) "   [fast mode]" else ""),
            onOutput = { text ->
                runOnUiThread {
                    shellText = carry + text
                    if (tab == 0 || tab == 1) refreshOutputSoon()
                    // first real failure: let Gradle print the reason (1.2 s), then stop - nothing more is built
                    if (!failFastHit && text.contains("FAILURE: Build failed with an exception")) {
                        failFastHit = true
                        ui.postDelayed({ if (runner === shell) shell.stop() }, 1200)
                    }
                    val pl = GradleTuner.progressLine(text, total, gradleStartMs)
                    statusSub.text = pl
                    if (::statusBar.isInitialized) {
                        val pct = GradleTuner.progressPercent(text, total)
                        if (pct < 0) statusBar.isIndeterminate = true
                        else { statusBar.isIndeterminate = false; statusBar.progress = pct }
                    }
                    BuildService.progress(this, busyTitle, pl)
                }
            },
            onExit = { code, stopped ->
                runOnUiThread {
                    runner = null
                    val secs = (System.currentTimeMillis() - gradleStartMs) / 1000
                    val ownText = shellText.removePrefix(carry)
                    val userStopped = stopped && !failFastHit
                    when {
                        userStopped -> setStatus(Status.READY, "Stopped", "Cancelled by user")
                        code == 0 -> {
                            if (isSync) {
                                val vs = BuildVariants.detect(workDir)?.variants?.map { it.name }.orEmpty()
                                val cur = BuildVariants.current(this, workDir)?.name
                                val sb = StringBuilder(shellText.trimEnd()).append("\n")
                                if (vs.isNotEmpty()) {
                                    sb.append(" ${vs.size} build variants found for project ':app': ${vs.joinToString(", ", "[", "]")}\n")
                                    if (cur != null) sb.append(" Selected build variant '$cur' for project ':app'\n")
                                }
                                sb.append("CONFIGURE SUCCESSFUL in ${secs}s\n")
                                shellText = sb.toString()
                            }
                            GradleTuner.markSynced(this, workDir)
                            GradleTuner.saveTaskTotal(this, workDir, totalKey, ownText)
                            val variant = BuildVariants.current(this, workDir)
                            val apk = if (task.startsWith("assemble")) findApk(variant)?.let { publishApk(it, variant) } else null
                            val aab = if (task.startsWith("bundle")) findBundle(variant) else null
                            if (apk != null && apk.name.contains("unsigned")) {
                                setStatus(Status.OK, doneTitle, "Unsigned release APK (can't be installed) · long-press ▶ → 🔑 Signing · ${secs}s")
                                log("Unsigned APK: ${apk.path}")
                                shellText = shellText.trimEnd() + "\n\nAPK: ${apk.path}\n"
                            } else if (apk != null) {
                                lastApk = apk
                                btnInstall.visibility = View.VISIBLE
                                setStatus(Status.OK, doneTitle, "Tap ⬇ to install ${apk.name} · ${secs}s")
                                shellText = shellText.trimEnd() + "\n\nReady to install: ${apk.name}\nAPK: ${apk.path}\nTap ⬇ to deploy the APK.\n"
                                log("APK: ${apk.path}")
                                val issues = ApkCheck.problems(this, apk)
                                shellText += if (issues.isEmpty()) "APK check: OK (zip, manifest, dex, signature)\n"
                                else "APK check found problems - install would fail:\n" + issues.joinToString("\n") { " - $it" } + "\n"
                                // one-tap Run: install + launch + open logs (UI installer only while the screen is in front)
                                if (issues.isEmpty() && GradleTuner.autoRun(this) && (GradleTuner.autoRunRoot(this) || resumed))
                                    installApk(GradleTuner.autoRunRoot(this), thenLaunch = true)
                                else if (issues.isEmpty() && GradleTuner.autoInstall(this) && (GradleTuner.autoRunRoot(this) || resumed)) {
                                    shellText += "\nTriggering APK installation...\n"
                                    installApk(GradleTuner.autoRunRoot(this))
                                }
                            } else if (aab != null) {
                                setStatus(Status.OK, doneTitle, "${aab.name} · ${secs}s · ${aab.parentFile?.relativeTo(workDir)?.path}")
                                log("Bundle: ${aab.path}")
                            } else {
                                setStatus(Status.OK, doneTitle, "Success · ${secs}s")
                                // say clearly why there is no APK instead of a silent "Success"
                                shellText = shellText.trimEnd() + "\n\n" + when {
                                    task.startsWith("assemble") -> {
                                        val ran = ownText.contains("Task :app:packageDebug") || ownText.contains("Task :app:package")
                                        val found = runCatching {
                                            workDir.walkTopDown().maxDepth(8)
                                                .onEnter { it.name != ".gradle" && it.name != ".git" }
                                                .filter { it.isFile && it.extension == "apk" }
                                                .take(5).map { it.relativeTo(workDir).path }.toList()
                                        }.getOrDefault(emptyList())
                                        "Task '$task' finished but no APK was found in app/build/outputs/apk.\n" +
                                            "Packaging task (package*) ran: ${if (ran) "yes" else "NO - Gradle stopped before packaging"}\n" +
                                            "Any .apk under the project: ${if (found.isEmpty()) "none" else found.joinToString(", ")}"
                                    }
                                    task.startsWith("bundle") -> "Task '$task' finished but no AAB was found."
                                    task == "ideResolveDeps" || isSync -> "Sync finished (no APK is built by a sync)."
                                    else -> "Ran '$task' only - this does not build an APK. Tap ▶ to build the APK."
                                } + "\n"
                            }
                        }
                        code == 3 -> setStatus(Status.FAIL, "JDK not found", "Install Java 17+ (see Output)")
                        isSync && task != "ideResolveDeps" && GradleTuner.looksLikeMissingProject(ownText) -> {
                            // module name from the folder did not match a Gradle project -> sync everything
                            log("Per-module sync failed, running full sync")
                            updateSyncBadge()
                            runGradle(busyTitle, doneTitle, "ideResolveDeps", isSync, forceOnline = forceOnline,
                                carry = shellText + "\n[sync] Module not found -> full sync...\n\n", refresh = refresh, memMode = memMode)
                            return@runOnUiThread
                        }
                        preSync != null && preSync != "ideResolveDeps" && GradleTuner.looksLikeMissingProject(ownText) -> {
                            // module-scoped sync in front of the build named an unknown project -> full sync + build
                            log("Per-module sync failed, running full sync + build")
                            updateSyncBadge()
                            runGradle(busyTitle, doneTitle, task, isSync, forceOnline = forceOnline,
                                carry = shellText + "\n[sync] Module not found -> full sync...\n\n", refresh = refresh,
                                preSync = "ideResolveDeps", memMode = memMode)
                            return@runOnUiThread
                        }
                        memMode == GradleTuner.MEM_NORMAL && !carry.contains("[retry] memory") &&
                            (GradleTuner.looksLikeOom(ownText) || code == 137) -> {
                            // daemon killed / out of heap: one more try with a bigger (or, if Android killed it, smaller) heap
                            val more = GradleTuner.looksLikeOom(ownText)
                            log("Gradle ran out of memory (exit $code), retrying with a ${if (more) "bigger" else "smaller"} heap")
                            runGradle(busyTitle, doneTitle, task, isSync, forceOnline = forceOnline,
                                carry = shellText + "\n[retry] memory: " + (if (more) "heap too small -> retrying with more memory" else "process killed by Android -> retrying with less memory") + "...\n\n",
                                refresh = refresh, preSync = preSync,
                                memMode = if (more) GradleTuner.MEM_MORE else GradleTuner.MEM_LESS)
                            return@runOnUiThread
                        }
                        !GradleTuner.hasInternet(this) && !offline && GradleTuner.looksLikeTransientNetwork(ownText) -> {
                            val miss = SyncFixer.parse(ownText)
                            setStatus(Status.FAIL, "No internet", "Connect to the internet and tap Sync again" + (miss?.let { " · missing ${it.group}:${it.artifact}" } ?: ""))
                            shellText = shellText.trimEnd() + "\n\n[sync] No internet connection. Dependencies that are not in the cache need it." +
                                (miss?.let { "\nMissing: ${it.group}:${it.artifact}:${it.version}" } ?: "") + "\n"
                            showFixes(ownText)
                        }
                        offline && GradleTuner.looksLikeOfflineMiss(ownText) && !GradleTuner.hasInternet(this) -> {
                            val miss = SyncFixer.parse(ownText)
                            setStatus(Status.FAIL, "Offline - dependencies missing", "Connect to the internet and sync again" + (miss?.let { " · ${it.group}:${it.artifact}" } ?: ""))
                            shellText = shellText.trimEnd() + "\n\n[sync] The offline cache is missing dependencies and there is no internet.\n" +
                                (miss?.let { "Missing: ${it.group}:${it.artifact}:${it.version}\n" } ?: "")
                            showFixes(ownText)
                        }
                        offline && GradleTuner.looksLikeOfflineMiss(ownText) -> {
                            // cache did not have everything -> same sync again, this time with internet
                            log("Offline sync incomplete, retrying online")
                            updateSyncBadge()
                            runGradle(busyTitle, doneTitle, task, isSync, forceOnline = true,
                                carry = shellText + "\n[sync] Offline cache incomplete -> retrying online...\n\n", refresh = refresh,
                                preSync = preSync, memMode = memMode)
                            return@runOnUiThread
                        }
                        !offline && GradleTuner.looksLikeTransientNetwork(ownText) && GradleTuner.hasInternet(this) &&
                            Regex("\\[retry\\] network").findAll(carry).count() < 3 -> {
                            // flaky connection (timeout / reset / 5xx): wait a bit and run the same thing again, up to 3 times
                            val n = Regex("\\[retry\\] network").findAll(carry).count() + 1
                            val wait = 2L * n
                            log("Network error, retry $n/3 in ${wait}s")
                            setStatus(Status.BUSY, "Network problem", "Retry $n/3 in ${wait}s...")
                            val keep = shellText + "\n[retry] network $n/3 -> trying again in ${wait}s...\n\n"
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isDestroyed && runner == null)
                                    runGradle(busyTitle, doneTitle, task, isSync, forceOnline = true, carry = keep,
                                        refresh = refresh, preSync = preSync, memMode = memMode)
                            }, wait * 1000)
                            return@runOnUiThread
                        }
                        GradleTuner.looksLikePluginNotFound(ownText) && !carry.contains("[fix] Added plugin repos") &&
                            SyncFixer.ensurePluginRepos(workDir)?.startsWith("Added") == true -> {
                            log("Plugin not found, added plugin repositories and retrying")
                            reloadSyncDocs(); updateSyncBadge()
                            runGradle(busyTitle, doneTitle, task, isSync, forceOnline = true,
                                carry = shellText + "\n[fix] Added plugin repos (google, mavenCentral, gradlePluginPortal) -> retrying...\n\n",
                                refresh = refresh, preSync = preSync, memMode = memMode)
                            return@runOnUiThread
                        }
                        !offline && SyncFixer.looksLikeDependencyProblem(ownText) && !GradleTuner.looksLikeTransientNetwork(ownText) &&
                            !carry.contains("[fix] Added repos") &&
                            SyncFixer.parse(ownText).let { f ->
                                SyncFixer.ensureRepos(workDir, f?.group?.startsWith("com.github.") == true)?.startsWith("Added") == true
                            } -> {
                            log("Dependency not found, added repositories and retrying")
                            reloadSyncDocs(); updateSyncBadge()
                            runGradle(busyTitle, doneTitle, task, isSync, forceOnline = true,
                                carry = shellText + "\n[fix] Added missing repositories -> retrying...\n\n",
                                refresh = refresh, preSync = preSync, memMode = memMode)
                            return@runOnUiThread
                        }
                        BuildFixer.looksLikeCorruptBuild(ownText) && !carry.contains("[fix] Cleaned") &&
                            BuildFixer.cleanBuildFolders(workDir).startsWith("Updated") -> {
                            // half-written build files (corrupt .flat etc.): clean once and retry automatically
                            log("Corrupt build files, cleaned build folders and retrying")
                            runGradle(busyTitle, doneTitle, task, isSync, forceOnline = forceOnline,
                                carry = shellText + "\n[fix] Cleaned corrupt build files -> retrying...\n\n", refresh = refresh,
                                preSync = preSync, memMode = memMode)
                            return@runOnUiThread
                        }
                        else -> {
                            setStatus(Status.FAIL, "Failed (exit $code)", "See Output / Problems (tap file:line) · ${secs}s")
                            if (tab == 0 && shellText.lines().any { it.startsWith("e: ") || pathRe.containsMatchIn(it) }) selectPanelTab(1)
                            showFixes(ownText)
                        }
                    }
                    log("Gradle $task exit code: $code (${secs}s)")
                    val ok = code == 0 && !userStopped
                    BuildService.end(
                        this, ok,
                        if (userStopped) "Stopped" else if (ok) doneTitle else "Build failed",
                        if (ok) "Success · ${secs}s" + (lastApk?.let { " · ${it.name}" } ?: "") else "Failed (exit $code) · ${secs}s · open the app to see the errors",
                        showResult = !resumed
                    )
                    // the screen was closed while this ran: do not leave a Fast Mode daemon behind
                    if (isDestroyed && GradleTuner.fastMode(this)) GradleTuner.stopDaemons(this, workDir)
                    updateSyncBadge()
                    refreshOutput()
                    refreshTree()
                }
            })
    }

    // ---- one-tap sync fixes ---------------------------------------------------------------------

    private lateinit var fixBar: HorizontalScrollView
    private lateinit var fixRow: LinearLayout

    private fun buildFixBar(): View {
        fixRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(4))
        }
        fixBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(fixRow)
        }
        return fixBar
    }

    private fun fixChip(text: String, onClick: () -> Unit) = keyChip(text, onClick).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(primary)
        background = rounded(0xFFE2E0F2.toInt(), 12)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    /** Shows fix buttons under the tabs: dependency problems (SyncFixer) + the other common errors (BuildFixer). */
    private fun showFixes(output: String) {
        fixRow.removeAllViews()
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
        var any = false
        if (SyncFixer.looksLikeDependencyProblem(output)) {
            val f = SyncFixer.parse(output)
            if (f != null) fixRow.addView(fixChip("⬆ Latest ${f.artifact}") { fixLatest(f) }, lp)
            fixRow.addView(fixChip("＋ Repos") { fixRepos(f) }, lp)
            fixRow.addView(fixChip("🔄 Refresh & retry") { fixRefresh() }, lp)
            any = true
        }
        if (GradleTuner.looksLikeTransientNetwork(output) || GradleTuner.looksLikeOom(output)) {
            fixRow.addView(fixChip("↻ Retry") { rerunLast() }, lp)
            any = true
        }
        for (fix in runCatching { BuildFixer.detect(output, workDir) }.getOrDefault(emptyList())) {
            fixRow.addView(fixChip(fix.label) { applyFix(fix) }, lp)
            any = true
        }
        fixBar.visibility = if (any) View.VISIBLE else View.GONE
    }

    /** Runs a BuildFixer fix, then re-runs what failed (sync or build). */
    private fun applyFix(fix: BuildFixer.Fix) {
        when (fix.kind) {
            BuildFixer.Kind.OPEN_SDK -> startActivity(
                Intent(this, SdkManagerActivity::class.java).putExtra(SdkManagerActivity.EXTRA_PROJECT, workDir.path)
            )
            BuildFixer.Kind.LATEST -> fix.failure?.let { fixLatest(it) }
            BuildFixer.Kind.FLUTTER_SDK -> askFlutterSdk()
            BuildFixer.Kind.EDIT -> {
                saveAll()
                val msg = runCatching { fix.apply(workDir) }.getOrElse { "Fix failed: ${it.message}" }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                log(msg)
                if (msg.startsWith("Updated") || msg.startsWith("Added")) { reloadSyncDocs(); updateSyncBadge(); rerunLast() }
            }
        }
    }

    // ---- ▶ build menu ------------------------------------------------------------------------------

    private fun customTasks(): MutableList<String> =
        getSharedPreferences("build_tasks", MODE_PRIVATE).getString("t:" + workDir.path, "")!!
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    private fun saveCustomTasks(l: List<String>) =
        getSharedPreferences("build_tasks", MODE_PRIVATE).edit().putString("t:" + workDir.path, l.joinToString("\n")).apply()

    /** Picks the debug / release variant (keeps the chosen flavor), sets APK or AAB, then builds. */
    private fun buildType(type: String, aab: Boolean) {
        val info = BuildVariants.detect(workDir)
        if (info != null) {
            val flavor = BuildVariants.current(this, workDir)?.flavor.orEmpty()
            val v = info.variants.firstOrNull { it.buildType == type && it.flavor == flavor }
                ?: info.variants.firstOrNull { it.buildType == type }
            if (v != null) BuildVariants.setSelected(this, workDir, v.name)
            BuildVariants.setBundle(this, workDir, aab)
            runBuild()
        } else {
            val cap = type.replaceFirstChar { it.uppercaseChar() }
            runBuild(taskOverride = (if (aab) "bundle" else "assemble") + cap)
        }
    }

    private fun showBuildMenu() {
        val pm = android.widget.PopupMenu(this, btnRun)
        val m = pm.menu
        m.add(0, 1, 0, "Build debug (APK)")
        m.add(0, 2, 1, "Build release (APK)")
        m.add(0, 3, 2, "Bundle release (AAB)")
        val saved = customTasks()
        saved.forEachIndexed { i, t -> m.add(1, 100 + i, 10 + i, t) }
        m.add(2, 4, 50, "Add task...")
        m.add(2, 5, 51, "Custom...")
        if (saved.isNotEmpty()) m.add(2, 6, 52, "Remove task...")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> buildType("debug", false)
                2 -> buildType("release", false)
                3 -> buildType("release", true)
                4 -> askTask("Add task", "Saved in the ▶ menu", save = true)
                5 -> askTask("Custom task", "Runs once, e.g. lintDebug or app:testDebugUnitTest", save = false)
                6 -> {
                    AlertDialog.Builder(this).setTitle("Remove task")
                        .setItems(saved.toTypedArray()) { _, i -> saved.removeAt(i); saveCustomTasks(saved) }
                        .setNegativeButton("Cancel", null).show()
                }
                else -> if (item.itemId >= 100) runBuild(taskOverride = saved.getOrNull(item.itemId - 100))
            }
            true
        }
        pm.show()
    }

    private fun askTask(title: String, hint: String, save: Boolean) {
        val input = EditText(this).apply { this.hint = "task name"; setSingleLine(); typeface = Typeface.MONOSPACE }
        AlertDialog.Builder(this).setTitle(title).setMessage(hint).setView(input)
            .setPositiveButton(if (save) "Save & run" else "Run") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                if (save) saveCustomTasks((customTasks() + t).distinct())
                runBuild(taskOverride = t)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun askFlutterSdk() {
        val input = EditText(this).apply {
            setSingleLine(); typeface = Typeface.MONOSPACE
            setText(GradleTuner.readProperty(workDir, "flutter.sdk") ?: GradleTuner.findFlutterSdk(this@ProjectActivity)?.path ?: "")
            hint = "/path/to/flutter"
        }
        AlertDialog.Builder(this).setTitle("Flutter SDK folder")
            .setMessage("The folder that contains bin/flutter. The Flutter SDK is installed separately (e.g. in the terminal: git clone https://github.com/flutter/flutter).")
            .setView(input)
            .setPositiveButton("Save & sync") { _, _ ->
                val d = File(input.text.toString().trim())
                if (!GradleTuner.isFlutterSdk(d)) {
                    Toast.makeText(this, "Not a Flutter SDK (bin/flutter or packages/flutter_tools missing)", Toast.LENGTH_LONG).show()
                } else {
                    GradleTuner.setProperty(workDir, "flutter.sdk", d.path)
                    reloadSyncDocs(); updateSyncBadge(); rerunLast()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun rerunLast() {
        if (lastWasBuild) runBuild(lastCheck, lastTask) else { lastSkipTapMs = System.currentTimeMillis(); sync() }
    }

    // ---- version pre-flight (AGP / Gradle / JDK) ------------------------------------------------

    /**
     * Checks AGP <-> Gradle <-> JDK before Gradle starts. Returns true when a dialog was shown
     * (the caller must stop: the dialog re-runs the sync / build itself). A warning that was
     * answered with "Run anyway" is not shown again in this session.
     */
    private fun preflightBlocks(): Boolean {
        if (!isGradleProject()) return false
        val issues = runCatching { CompatChecker.check(this, workDir) }.getOrDefault(emptyList())
        if (issues.isEmpty()) return false
        val sig = issues.joinToString("|") { it.message }
        if (sig in preflightSeen) return false
        val fix = issues.first().fixes.firstOrNull()
        val b = AlertDialog.Builder(this)
            .setTitle("Version problem")
            .setMessage(issues.joinToString("\n\n") { "• " + it.message } + "\n\nGradle will most likely fail with this setup.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Run anyway") { _, _ -> preflightSeen.add(sig); rerunLast() }
        if (fix != null) b.setPositiveButton(fix.label) { _, _ -> applyFix(fix) }
        b.show()
        return true
    }

    private fun fixLatest(f: SyncFixer.Failure) {
        setStatus(Status.BUSY, "Looking up latest version...", "${f.group}:${f.artifact}")
        Thread {
            val lib = runCatching { DependencyManager.search("${f.group}:${f.artifact}") }.getOrDefault(emptyList()).firstOrNull()
            runOnUiThread {
                setStatus(Status.READY, "Build Output", "Ready")
                when {
                    lib == null ->
                        Toast.makeText(this, "No version found for ${f.group}:${f.artifact} (name wrong or repo missing - try ＋ Repos)", Toast.LENGTH_LONG).show()
                    lib.version == f.version ->
                        Toast.makeText(this, "Already on latest (${lib.version}) - try ＋ Repos or 🔄 Refresh", Toast.LENGTH_LONG).show()
                    else -> {
                        saveAll()
                        val msg = SyncFixer.updateVersion(workDir, f.group, f.artifact, lib.version)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        log(msg)
                        if (msg.startsWith("Updated")) { reloadSyncDocs(); updateSyncBadge(); sync() }
                    }
                }
            }
        }.start()
    }

    private fun fixRepos(f: SyncFixer.Failure?) {
        saveAll()
        val msg = SyncFixer.ensureRepos(workDir, jitpack = f?.group?.startsWith("com.github.") == true)
            ?: "No repositories { } block found in settings / root build file - add google() and mavenCentral() by hand"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        log(msg)
        if (msg.startsWith("Added")) { reloadSyncDocs(); updateSyncBadge(); sync() }
    }

    private fun fixRefresh() {
        if (runner != null) return
        runGradle("Refreshing dependencies...", "Sync finished", "ideResolveDeps", isSync = true,
            forceOnline = true, refresh = true)
    }

    /** Reload open build files from disk after a fix changed them. */
    private fun reloadSyncDocs() {
        for (f in docs.keys.toList()) {
            if (!GradleTuner.isSyncFile(workDir, f)) continue
            val wasCurrent = current?.file == f
            if (docs.remove(f) != null) {
                if (wasCurrent) { current = null; openFile(f) } else refreshTabs()
            }
        }
    }

    // ---- "sync needed" badge + auto-sync --------------------------------------------------------

    private fun updateSyncBadge() {
        if (!::btnSync.isInitialized || !::workDir.isInitialized || runner != null) return
        btnRun.text = "▶"
        val dirty = isGradleProject() && GradleTuner.needsSync(this, workDir)
        btnSync.text = if (dirty) "⟳●" else "⟳"
        btnSync.setTextColor(if (dirty) 0xFFE65100.toInt() else Color.BLACK)
        btnSync.background = rounded(if (dirty) 0x33E65100 else 0x14000000, 14)
    }

    private fun onFileSaved(f: File) {
        if (!::workDir.isInitialized || !GradleTuner.isSyncFile(workDir, f)) return
        updateSyncBadge()
        if (GradleTuner.autoSync(this)) {
            ui.removeCallbacks(autoSyncRun)
            ui.postDelayed(autoSyncRun, 1500)
        }
    }

    private fun showBuildSettings() {
        fun box(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
            text = label; isChecked = checked
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        col.addView(TextView(this).apply {
            text = GradleTuner.summary(this@ProjectActivity)
            textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, dp(6))
        })
        col.addView(box("⚡ Fast Mode (Gradle daemon + config cache)\n2nd+ builds much faster, uses more RAM", GradleTuner.fastMode(this)) { v ->
            GradleTuner.setFastMode(this, v)
            if (!v) GradleTuner.stopDaemons(this, workDir)
        })
        col.addView(box("Sync + build in one run (▶ syncs first if build files changed)", GradleTuner.syncBeforeBuild(this)) { GradleTuner.setSyncBeforeBuild(this, it) })
        col.addView(box("After build: install + launch the app + open its log", GradleTuner.autoRun(this)) { GradleTuner.setAutoRun(this, it) })
        col.addView(box("   ...use root for install + launch (no installer popup)", GradleTuner.autoRunRoot(this)) { GradleTuner.setAutoRunRoot(this, it) })
        col.addView(box("Auto-sync when build files are saved", GradleTuner.autoSync(this)) { GradleTuner.setAutoSync(this, it) })
        col.addView(box("Offline-first sync (retries online if needed)", GradleTuner.offlineFirst(this)) { GradleTuner.setOfflineFirst(this, it) })
        col.addView(box("Skip lint + unit tests on plain \"build\"", GradleTuner.skipLint(this)) { GradleTuner.setSkipLint(this, it) })
        AlertDialog.Builder(this)
            .setTitle("Build settings")
            .setView(col)
            .setPositiveButton("Done", null)
            .setNeutralButton("Stop daemons") { _, _ ->
                GradleTuner.stopDaemons(this, workDir)
                Toast.makeText(this, "Stopping Gradle daemons...", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---- build variants + release signing -------------------------------------------------------

    /** Pick the build variant (buildType x flavor) and APK / AAB. Long-press ▶ or 🏷 in the drawer. */
    private fun showVariantDialog() {
        val info = BuildVariants.detect(workDir)
        if (info == null) {
            Toast.makeText(this, "No Android app module (com.android.application) found", Toast.LENGTH_LONG).show()
            return
        }
        val cur = BuildVariants.current(this, workDir)?.name
        val group = RadioGroup(this)
        info.variants.forEachIndexed { i, v ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                text = v.name
                isChecked = v.name == cur
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
        }
        val aab = CheckBox(this).apply {
            text = "AAB (Play Store bundle) instead of APK"
            isChecked = BuildVariants.bundle(this@ProjectActivity, workDir)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        col.addView(TextView(this).apply {
            text = "▶ builds the selected variant, ✓ only compiles it (faster).\nRelease builds need a keystore: tap 🔑 Signing."
            textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, dp(6))
        })
        col.addView(group)
        col.addView(aab)
        AlertDialog.Builder(this)
            .setTitle("Build variant")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Select") { _, _ ->
                val v = info.variants.getOrNull(group.checkedRadioButtonId - 1000)
                if (v != null) BuildVariants.setSelected(this, workDir, v.name)
                BuildVariants.setBundle(this, workDir, aab.isChecked)
                Toast.makeText(this, "Variant: ${v?.name ?: cur} (${if (aab.isChecked) "AAB" else "APK"})", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("🔑 Signing") { _, _ -> showSigningDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Create a keystore (keytool) or point to an existing one; release builds are then signed with it. */
    private fun showSigningDialog() {
        val saved = Signing.load(this)
        fun field(hintText: String, value: String, secret: Boolean = false) = EditText(this).apply {
            hint = hintText; setText(value); setSingleLine()
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fPath = field("Keystore path", saved?.storeFile ?: Signing.defaultKeystore(this).path)
        val fAlias = field("Key alias", saved?.keyAlias ?: "release")
        val fStorePw = field("Keystore password (min 6 characters)", saved?.storePassword ?: "", true)
        val fKeyPw = field("Key password (empty = same as keystore)",
            saved?.let { if (it.keyPassword != it.storePassword) it.keyPassword else "" } ?: "", true)
        val fName = field("Your name or app name (only for a new keystore)", "")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        col.addView(TextView(this).apply {
            text = (if (saved != null) "✔ Release builds are signed with:\n${saved.storeFile}\n\n"
                    else "Not set up: release APKs are unsigned and cannot be installed.\n\n") +
                "BACK UP the keystore file. If you lose it you can never update an app you published with it. " +
                "Passwords are kept in this app's private storage."
            textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, dp(8))
        })
        listOf(fPath, fAlias, fStorePw, fKeyPw, fName).forEach { col.addView(it) }
        val d = AlertDialog.Builder(this)
            .setTitle("Release signing")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Create new", null)
            .setNeutralButton("Use existing", null)
            .setNegativeButton("Turn off", null)
            .create()
        d.show()

        fun read(): Info4? {
            val path = fPath.text.toString().trim()
            val alias = fAlias.text.toString().trim()
            val sp = fStorePw.text.toString()
            val kp = fKeyPw.text.toString().ifEmpty { sp }
            if (path.isEmpty() || alias.isEmpty()) { Toast.makeText(this, "Path and alias are required", Toast.LENGTH_SHORT).show(); return null }
            if (sp.length < 6 || kp.length < 6) { Toast.makeText(this, "Passwords need at least 6 characters", Toast.LENGTH_SHORT).show(); return null }
            return Info4(File(path), alias, sp, kp)
        }

        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val i = read() ?: return@setOnClickListener
            Toast.makeText(this, "Creating keystore...", Toast.LENGTH_SHORT).show()
            Thread {
                val err = Signing.generate(this, i.file, i.alias, i.storePw, i.keyPw, fName.text.toString())
                runOnUiThread {
                    if (err != null) Toast.makeText(this, "Keystore not created: $err", Toast.LENGTH_LONG).show()
                    else {
                        Signing.save(this, Signing.Info(i.file.path, i.storePw, i.alias, i.keyPw))
                        log("Keystore created: ${i.file.path}")
                        Toast.makeText(this, "Keystore created. Release builds are signed now. BACK UP ${i.file.path}", Toast.LENGTH_LONG).show()
                        d.dismiss()
                    }
                }
            }.start()
        }
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val i = read() ?: return@setOnClickListener
            Thread {
                val err = Signing.verify(this, i.file, i.alias, i.storePw)
                runOnUiThread {
                    if (err != null) Toast.makeText(this, "Cannot use this keystore: $err", Toast.LENGTH_LONG).show()
                    else {
                        Signing.save(this, Signing.Info(i.file.path, i.storePw, i.alias, i.keyPw))
                        Toast.makeText(this, "Release builds are signed with ${i.file.name}", Toast.LENGTH_LONG).show()
                        d.dismiss()
                    }
                }
            }.start()
        }
        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
            Signing.clear(this)
            Toast.makeText(this, "Release signing is off (the keystore file was not deleted)", Toast.LENGTH_LONG).show()
            d.dismiss()
        }
    }

    private class Info4(val file: File, val alias: String, val storePw: String, val keyPw: String)

    // ---- APK install ----------------------------------------------------------------------------

    /** The APK of [v] (outputs/apk/<flavor>/<buildType>/), else the newest APK of any variant. */
    /**
     * Newer AGP leaves the APK in build/intermediates/apk/<variant>/. Copy it to the classic
     * build/outputs/apk/<variant>/ so it is where everyone (and every file manager) looks for it.
     */
    private fun publishApk(apk: File, v: BuildVariants.Variant?): File {
        val marker = "/intermediates/apk/"
        val p = apk.invariantSeparatorsPath
        if (marker !in p) return apk
        val buildDir = p.substringBefore(marker)
        val sub = p.substringAfter(marker).substringBeforeLast('/', v?.apkDir ?: "debug")
        val dest = File("$buildDir/outputs/apk/$sub/${apk.name}")
        return runCatching { dest.parentFile?.mkdirs(); apk.copyTo(dest, overwrite = true); dest }.getOrDefault(apk)
    }

    private fun findApk(v: BuildVariants.Variant? = null): File? {
        fun apksIn(dirs: List<String>) = dirs
            .map { File(workDir, it) }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList() }
        val all = apksIn(listOf("app/build/outputs/apk", "build/outputs/apk"))
            .ifEmpty { apksIn(listOf("app/build/intermediates/apk", "build/intermediates/apk")) }
        val mine = v?.let { all.filter { f -> f.parentFile?.invariantSeparatorsPath?.endsWith("/apk/" + it.apkDir) == true } }.orEmpty()
        return (mine.ifEmpty { all }).maxByOrNull { it.lastModified() }
    }

    /** The AAB of [v] (outputs/bundle/<variant>/), else the newest AAB. */
    private fun findBundle(v: BuildVariants.Variant? = null): File? {
        val all = listOf("app/build/outputs/bundle", "build/outputs/bundle")
            .map { File(workDir, it) }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "aab" }.toList() }
        val mine = v?.let { all.filter { f -> f.parentFile?.name == it.name } }.orEmpty()
        return (mine.ifEmpty { all }).maxByOrNull { it.lastModified() }
    }

    private fun installApk(asRoot: Boolean, thenLaunch: Boolean = false) {
        val apk = lastApk ?: findApk()
        if (apk == null || !apk.isFile) { Toast.makeText(this, "No APK found - build first", Toast.LENGTH_SHORT).show(); return }
        val pkg = runCatching { packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName }.getOrNull()
        if (asRoot) {
            Toast.makeText(this, "Installing with root...", Toast.LENGTH_SHORT).show()
            Thread {
                val tmp = "/data/local/tmp/ide-install.apk"
                var installed = false
                val msg = try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp \"${apk.path}\" $tmp && chmod 644 $tmp && pm install -t -r $tmp; rm -f $tmp"))
                    val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
                    p.waitFor()
                    installed = out.contains("Success")
                    out.trim().lines().lastOrNull().orEmpty().ifBlank { "Done" }
                } catch (e: Exception) { "Root install failed: ${e.message}" }
                if (thenLaunch && installed && pkg != null) {
                    runCatching {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "monkey -p $pkg -c android.intent.category.LAUNCHER 1")).waitFor()
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); log("Root install: $msg")
                    if (thenLaunch && installed && pkg != null) openAppLogs(pkg)
                }
            }.start()
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow \"Install unknown apps\" for this app, then tap ⬇ again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            if (thenLaunch && pkg != null) launchAfterInstall(pkg)
            sessionInstall(apk)
        } catch (e: Exception) {
            unregisterPkgReceiver()
            Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
            log("Install failed: ${e.message}")
        }
    }

    /**
     * Installs through PackageInstaller so Android reports the REAL failure reason
     * (e.g. INSTALL_FAILED_TEST_ONLY, INSTALL_PARSE_FAILED_NO_CERTIFICATES) instead of the generic
     * "App not installed as package appears to be invalid" of the ACTION_VIEW installer.
     */
    private fun sessionInstall(apk: File) {
        val installer = packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31)
            params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = installer.createSession(params)
        val action = "$packageName.INSTALL_STATUS.$id"
        try {
            installer.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        val status = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
                        val msg = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                        when (status) {
                            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                if (confirm != null) runCatching { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                return            // keep the receiver: the final status comes later
                            }
                            android.content.pm.PackageInstaller.STATUS_SUCCESS -> log("Installed OK")
                            else -> {
                                val txt = "Install failed (status $status): $msg"
                                log(txt)
                                unregisterPkgReceiver()
                                runOnUiThread {
                                    AlertDialog.Builder(this@ProjectActivity)
                                        .setTitle("Install failed")
                                        .setMessage(msg.ifBlank { "status $status" })
                                        .setPositiveButton("OK", null).show()
                                }
                            }
                        }
                        runCatching { unregisterReceiver(this) }
                    }
                }
                androidx.core.content.ContextCompat.registerReceiver(
                    this, r, IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                val pi = android.app.PendingIntent.getBroadcast(this, id, Intent(action).setPackage(packageName), flags)
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Waits (max 3 min) for the system installer to finish this package, then launches it. */
    private fun launchAfterInstall(pkg: String) {
        unregisterPkgReceiver()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.data?.schemeSpecificPart != pkg) return
                unregisterPkgReceiver()
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                openAppLogs(pkg)
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        registerReceiver(r, f)
        pkgReceiver = r
        ui.postDelayed({ if (pkgReceiver === r) unregisterPkgReceiver() }, 3 * 60_000L)
    }

    private fun unregisterPkgReceiver() {
        pkgReceiver?.let { runCatching { unregisterReceiver(it) } }
        pkgReceiver = null
    }

    /** Opens the Logcat tab for [pkg] (root: only that app's process; without root only this IDE's own log exists). */
    private fun openAppLogs(pkg: String) {
        logcatPkg = pkg
        stopLogcat()
        synchronized(logcatBuf) {
            logcatBuf.setLength(0)
            if (!logcatRoot) logcatBuf.append("[logcat] Without root Android only shows this IDE's own log.\n" +
                "Long-press the Logcat tab to switch root mode and follow $pkg.\n\n")
        }
        panel.visibility = View.VISIBLE
        selectPanelTab(3)
    }

    // ---- dependencies ---------------------------------------------------------------------------

    private fun showAddDependency() {
        val bf = DependencyManager.findBuildFile(workDir)
        if (bf == null) { Toast.makeText(this, "No build.gradle found", Toast.LENGTH_SHORT).show(); return }
        val input = EditText(this).apply {
            hint = "retrofit   or   androidx.room:room-runtime"
            setSingleLine(); setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("Add dependency")
            .setMessage("Library name to search, or group:artifact[:version].\nAdds to ${bf.relativeTo(workDir).path}")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString()
                setStatus(Status.BUSY, "Searching...", q)
                Thread {
                    val res = runCatching { DependencyManager.search(q) }.getOrDefault(emptyList())
                    runOnUiThread { setStatus(Status.READY, "Build Output", "Ready"); showDependencyResults(bf, res) }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDependencyResults(bf: File, res: List<DependencyManager.Lib>) {
        if (res.isEmpty()) { Toast.makeText(this, "Nothing found (check internet / spelling)", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Pick library")
            .setItems(res.map { it.notation }.toTypedArray()) { _, i ->
                saveAll()
                val msg = DependencyManager.add(bf, res[i])
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                log(msg)
                val toml = File(workDir, "gradle/libs.versions.toml")
                for (f in listOf(bf, toml)) {
                    val wasCurrent = current?.file == f
                    if (docs.remove(f) != null) {
                        if (wasCurrent) { current = null; openFile(f) } else refreshTabs()
                    }
                }
                updateSyncBadge()
                if (msg.startsWith("Added")) ui.postDelayed({ if (runner == null) sync() }, 400)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- file tree + tabs + editor --------------------------------------------------------------

    private fun refreshTree() {
        if (::fileTree.isInitialized) fileTree.refresh()
    }

    /** A file/folder was deleted or renamed: close its tabs WITHOUT writing them back. */
    private fun dropDocs(f: File) {
        val hit = docs.keys.filter { it.path == f.path || it.path.startsWith(f.path + "/") }
        if (hit.isEmpty()) return
        val wasCurrent = hit.any { it == current?.file }
        hit.forEach { docs.remove(it) }
        if (wasCurrent) {
            current = null
            val next = docs.values.lastOrNull()
            if (next != null) showDoc(next) else showWelcome()
        } else refreshTabs()
    }

    private fun looksBinary(f: File): Boolean {
        if (f.extension.lowercase() in setOf("jar", "png", "webp", "jpg", "jpeg", "gif", "zip", "so", "apk", "lock", "class", "dex", "ttf", "otf")) return true
        return runCatching {
            f.inputStream().use { ins ->
                val buf = ByteArray(2048)
                val n = ins.read(buf)
                (0 until maxOf(n, 0)).any { buf[it].toInt() == 0 }
            }
        }.getOrDefault(false)
    }

    private fun stashCurrent() {
        val d = current ?: return
        d.text = editor.text?.toString().orEmpty()
        d.caret = editor.selectionStart.coerceAtLeast(0)
    }

    private fun onEditorChanged() {
        val d = current ?: return
        if (!d.dirty) { d.dirty = true; refreshTabs() }
    }

    private fun openFile(f: File, line: Int = -1, col: Int = 1, force: Boolean = false) {
        val existing = docs[f]
        if (existing == null && f.length() > 1_000_000) {
            Toast.makeText(this, "File is too large to open", Toast.LENGTH_SHORT).show()
            return
        }
        if (existing == null && !force && looksBinary(f)) {
            AlertDialog.Builder(this)
                .setTitle("Open file?")
                .setMessage("This file looks like a binary file and may not display correctly in the editor.\n\nOpen \"${f.name}\" anyway?")
                .setPositiveButton("Open") { _, _ -> openFile(f, line, col, true) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        stashCurrent()
        val d = existing ?: run {
            val text = runCatching { f.readText() }.getOrElse {
                Toast.makeText(this, "Cannot read file", Toast.LENGTH_SHORT).show(); return
            }
            Doc(f, text, text).also { docs[f] = it }
        }
        showDoc(d)
        if (line > 0) editor.post { editor.gotoLine(line, col) }
    }

    private fun showDoc(d: Doc) {
        current = d
        editor.load(d.text, d.file.name, d.history)
        editor.setSelection(d.caret.coerceIn(0, editor.length()))
        welcome.visibility = View.GONE
        editorBox.visibility = View.VISIBLE
        btnSave.visibility = View.VISIBLE
        fileLabel.text = d.file.name
        drawer.closeDrawer(GravityCompat.START)
        showSuggestions(emptyList())
        refreshTabs()
        refreshTree()
    }

    private fun refreshTabs() {
        tabStrip.removeAllViews()
        docs.values.forEach { d ->
            val active = d === current
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (active) rounded(0xFFE2E0F2.toInt(), 12) else null
            }
            cell.addView(TextView(this).apply {
                text = d.file.name + if (d.dirty) " •" else ""
                textSize = 14f
                setTextColor(if (active) primary else 0xFF666666.toInt())
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener { if (!active) { stashCurrent(); showDoc(d) } }
            })
            cell.addView(TextView(this).apply {
                text = "✕"; textSize = 13f
                setTextColor(0xFF888888.toInt())
                setPadding(dp(6), dp(8), dp(10), dp(8))
                setOnClickListener { closeTab(d) }
            })
            tabStrip.addView(cell, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        }
    }

    private fun closeTab(d: Doc) {
        if (d === current) stashCurrent()
        writeDoc(d)
        docs.remove(d.file)
        if (d === current) {
            current = null
            val next = docs.values.lastOrNull()
            if (next != null) showDoc(next) else showWelcome()
        } else refreshTabs()
    }

    private fun showWelcome() {
        findBar.visibility = View.GONE
        editorBox.visibility = View.GONE
        btnSave.visibility = View.GONE
        welcome.visibility = View.VISIBLE
        fileLabel.text = ""
        refreshTabs()
        refreshTree()
    }

    private fun writeDoc(d: Doc): Boolean {
        if (!d.dirty) return true
        val ok = runCatching { d.file.writeText(d.text); d.loaded = d.text; d.dirty = false; log("Saved ${d.file.name}"); true }
            .getOrElse {
                Toast.makeText(this, "Save failed: ${it.message}", Toast.LENGTH_LONG).show(); false
            }
        if (ok) onFileSaved(d.file)
        return ok
    }

    private fun saveCurrent(toast: Boolean) {
        val d = current ?: return
        stashCurrent()
        if (writeDoc(d)) { refreshTabs(); if (toast) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
    }

    private fun saveAll() {
        stashCurrent()
        var changed = false
        docs.values.forEach { if (it.dirty && writeDoc(it)) changed = true }
        if (changed) refreshTabs()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        if (::editor.isInitialized) saveAll()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateSyncBadge()          // build files may have changed from the terminal / git
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::editor.isInitialized) runCatching { editor.release() }
        // A build started from this screen keeps running (foreground service + notification) when the
        // screen is closed; its Stop button in the notification still works.
        val keepBuilding = isFinishing && runner != null && BuildService.running
        if (!keepBuilding) runner?.stop()
        unregisterPkgReceiver()
        stopLogcat()
        ui.removeCallbacks(autoSyncRun)
        // Fast Mode keeps a daemon alive: do not leave it eating RAM after the project closes
        // (when a build is still running, the daemon is stopped as soon as that build ends)
        if (isFinishing && !keepBuilding && ::workDir.isInitialized && GradleTuner.fastMode(this)) GradleTuner.stopDaemons(this, workDir)
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_AUTO_SYNC = "auto_sync"
    }
}
