package com.example.mynative.androidide

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import java.io.File

/**
 * The project drawer: tool icon bar, search box and an expandable file tree
 * (folder icon + item count, per-folder actions, file icons, type badges, sizes).
 * Icons are the ones from res/drawable-nodpi (the app's icon sheet).
 */
class FileTree(
    private val ctx: Context,
    private val root: File,
    private val primary: Int,
    private val onOpen: (File) -> Unit,
    private val currentFile: () -> File?,
    private val onGone: (File) -> Unit,          // file/folder deleted or renamed -> close its tabs
    private val tools: List<Tool>
) {
    class Tool(val icon: Int, val label: String, val onClick: () -> Unit)

    private val expanded = mutableSetOf(root.path)
    private var query = ""
    private var hideBuild = false
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var filterBtn: ImageView

    private val grey = 0xFF8A8799.toInt()
    private val hidden = setOf("build", ".gradle", ".cxx", ".git", ".idea")
    private val heavy = setOf("build", ".gradle", ".cxx", ".git", ".idea", "node_modules")

    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFFFAF8FF.toInt())
    }

    init {
        view.addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(10), dp(14), dp(10), 0)
        })
        view.addView(buildTabs(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(10), dp(10), dp(10), dp(8))
        })
        view.addView(buildSearch(), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
            setMargins(dp(10), 0, dp(10), dp(6))
        })
        view.addView(ScrollView(ctx).apply { addView(list) }, MATCH_PARENT, MATCH_PARENT)
        refresh()
    }

    // ---- header ------------------------------------------------------------------------------

    private fun premium(radiusDp: Int, from: Int = 0xFFFFFFFF.toInt(), to: Int = 0xFFEFEBFD.toInt()) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), 0x225B4FC4)
        }

    /** Project card at the top of the drawer: gradient avatar with the first letter + name + path. */
    private fun buildHeader(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = premium(20)
            elevation = dp(3).toFloat()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        row.addView(TextView(ctx).apply {
            text = root.name.take(1).uppercase().ifEmpty { "P" }
            textSize = 20f; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7B6CF2.toInt(), 0xFF3B28CC.toInt()))
                .apply { cornerRadius = dp(14).toFloat() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = root.name; textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF1B1538.toInt()); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(ctx).apply {
            text = root.absolutePath; textSize = 11f; setTextColor(grey)
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        return row
    }

    private fun buildTabs(): View {
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = premium(22)
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        fun icon(res: Int, selected: Boolean, onClick: (View) -> Unit) {
            pill.addView(ImageView(ctx).apply {
                setImageResource(res)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = if (selected) premium(14, 0xFFE9E5FF.toInt(), 0xFFD9D3F7.toInt()) else null
                setOnClickListener { onClick(it) }
            }, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginEnd = dp(4) })
        }
        icon(R.drawable.ic_more, false) { anchor ->
            PopupMenu(ctx, anchor).apply {
                tools.forEachIndexed { i, t -> menu.add(0, i, i, t.label) }
                menu.add(0, 100, 100, "Collapse all")
                menu.add(0, 101, 101, "Refresh")
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        100 -> collapseAll()
                        101 -> refresh()
                        else -> tools.getOrNull(it.itemId)?.onClick?.invoke()
                    }
                    true
                }
            }.show()
        }
        icon(R.drawable.ic_folder, true) { }
        tools.forEach { t -> icon(t.icon, false) { t.onClick() } }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(pill)
        }
    }

    private fun buildSearch(): View {
        val box = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = premium(22, 0xFFFFFFFF.toInt(), 0xFFF3F0FC.toInt())
            setPadding(dp(12), 0, dp(6), 0)
        }
        box.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_search) }, dp(22), dp(22))
        val input = EditText(ctx).apply {
            hint = "Search files..."
            textSize = 14f
            background = null
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(8), 0, dp(4), 0)
            doAfterTextChanged { query = it?.toString().orEmpty(); refresh() }
        }
        box.addView(input, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        box.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_minus)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { collapseAll() }
        }, dp(32), dp(32))
        filterBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_filter)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            alpha = 0.55f
            setOnClickListener {
                hideBuild = !hideBuild
                alpha = if (hideBuild) 1f else 0.55f
                Toast.makeText(ctx, if (hideBuild) "Hiding build / .gradle / .cxx / .git" else "Showing all folders", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        box.addView(filterBtn, dp(32), dp(32))
        return box
    }

    // ---- tree --------------------------------------------------------------------------------

    fun refresh() {
        list.removeAllViews()
        if (query.isNotBlank()) { addSearchResults(); return }
        addDir(root, 0)
    }

    private fun collapseAll() {
        expanded.clear(); expanded.add(root.path); refresh()
    }

    private fun children(dir: File): List<File> =
        dir.listFiles()
            ?.filter { !(hideBuild && it.isDirectory && it.name in hidden) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()

    private fun addDir(dir: File, depth: Int) {
        list.addView(dirRow(dir, depth))
        if (dir.path in expanded) {
            children(dir).forEach { f -> if (f.isDirectory) addDir(f, depth + 1) else list.addView(fileRow(f, depth + 1)) }
        }
    }

    private fun baseRow(depth: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(40)
        setPadding(dp(8), 0, dp(8), 0)
        for (i in 0 until depth) {
            addView(FrameLayout(ctx).apply {
                addView(View(ctx).apply { setBackgroundColor(0xFFE4E1F1.toInt()) },
                    FrameLayout.LayoutParams(dp(1), MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(dp(16), MATCH_PARENT))
        }
    }

    private fun nameView(text: String, color: Int = Color.BLACK) = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTextColor(color)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun small(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        setTextColor(grey)
        setSingleLine(true)
    }

    private fun actionIcon(res: Int, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        setOnClickListener { onClick() }
    }

    private fun dirRow(dir: File, depth: Int): View {
        val open = dir.path in expanded
        val row = baseRow(depth)
        row.addView(TextView(ctx).apply {
            text = "›"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(grey)
            rotation = if (open) 90f else 0f
        }, LinearLayout.LayoutParams(dp(16), WRAP_CONTENT))
        row.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_folder) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(4) })
        row.addView(nameView(dir.name, 0xFF1B1538.toInt()).also { it.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        row.addView(small((dir.list()?.size ?: 0).toString()),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        if (open) {
            val lp = { LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(6) } }
            row.addView(actionIcon(R.drawable.ic_plus) { newFile(dir) }, lp())
            row.addView(actionIcon(R.drawable.ic_folder_add) { newFolder(dir) }, lp())
            row.addView(actionIcon(R.drawable.ic_refresh) { refresh() }, lp())
        }
        row.setOnClickListener {
            if (!expanded.add(dir.path)) expanded.remove(dir.path)
            refresh()
        }
        row.setOnLongClickListener { itemMenu(dir); true }
        return row
    }

    private fun fileRow(f: File, depth: Int): View {
        val sel = f == currentFile()
        val row = baseRow(depth)
        if (sel) row.background = premium(12, 0xFFEDE9FF.toInt(), 0xFFDAD4F8.toInt())
        row.addView(View(ctx), LinearLayout.LayoutParams(dp(16), 1))
        row.addView(ImageView(ctx).apply { setImageResource(iconFor(f)) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(4) })
        row.addView(nameView(f.name, if (sel) primary else 0xFF1B1538.toInt()).also { if (sel) it.setTypeface(null, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        badgeFor(f)?.let { b ->
            row.addView(TextView(ctx).apply {
                text = b; textSize = 10f; setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF55526A.toInt())
                background = rounded(0x14000000, 6)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        }
        row.addView(small(android.text.format.Formatter.formatShortFileSize(ctx, f.length())),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        row.setOnClickListener { onOpen(f) }
        row.setOnLongClickListener { itemMenu(f); true }
        return row
    }

    private fun iconFor(f: File): Int = when (f.extension.lowercase()) {
        "kt" -> R.drawable.ic_kotlin
        "java" -> R.drawable.ic_java
        "cpp", "cc", "hpp" -> R.drawable.ic_cpp
        "c", "h" -> R.drawable.ic_c
        "dart" -> R.drawable.ic_flutter
        "gradle", "kts", "toml", "properties", "pro" -> R.drawable.ic_wrench
        "png", "jpg", "jpeg", "webp", "gif" -> R.drawable.ic_image
        "sh", "bat" -> R.drawable.ic_terminal_box
        "xml", "json", "yml", "yaml", "mk", "txt", "md" -> R.drawable.ic_code
        else -> R.drawable.ic_file
    }

    private fun badgeFor(f: File): String? = when (f.extension.lowercase()) {
        "gradle", "kts" -> "GRADLE"
        "properties" -> "PROPERTIES"
        "pro" -> "PRO"
        "bat" -> "BAT"
        "jar" -> "JAR"
        "toml" -> "TOML"
        "lock" -> "LOCK"
        else -> null
    }

    // ---- search ------------------------------------------------------------------------------

    private fun addSearchResults() {
        val q = query.trim().lowercase()
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            if (depth > 10) return
            for (f in d.listFiles().orEmpty()) {
                if (out.size >= 200) return
                if (f.name.lowercase().contains(q)) out.add(f)
                if (f.isDirectory && f.name !in heavy) walk(f, depth + 1)
            }
        }
        walk(root, 0)
        if (out.isEmpty()) {
            list.addView(small("No matches").apply { setPadding(dp(18), dp(14), 0, 0); textSize = 13f })
            return
        }
        out.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { f ->
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(46)
                setPadding(dp(14), dp(4), dp(10), dp(4))
            }
            row.addView(ImageView(ctx).apply { setImageResource(if (f.isDirectory) R.drawable.ic_folder else iconFor(f)) }, dp(24), dp(24))
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(nameView(f.name))
            col.addView(small(f.parentFile?.relativeTo(root)?.path?.ifEmpty { "/" } ?: ""))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            row.setOnClickListener {
                if (f.isDirectory) {
                    var p: File? = f
                    while (p != null && p != root.parentFile) { expanded.add(p.path); p = p.parentFile }
                    query = ""
                    refresh()
                } else onOpen(f)
            }
            row.setOnLongClickListener { itemMenu(f); true }
            list.addView(row)
        }
    }

    // ---- file actions ------------------------------------------------------------------------

    private fun itemMenu(f: File) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (f.isDirectory) {
            items.add("New file" to { newFile(f) })
            items.add("New folder" to { newFolder(f) })
        }
        if (f != root) {
            items.add("Rename" to { rename(f) })
            items.add("Delete" to { delete(f) })
        }
        items.add("Copy path" to {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", f.path))
            Toast.makeText(ctx, "Path copied", Toast.LENGTH_SHORT).show()
        })
        AlertDialog.Builder(ctx)
            .setTitle(f.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun askName(title: String, initial: String, ok: (String) -> Unit) {
        val input = EditText(ctx).apply { setText(initial); setSingleLine(true); setSelection(initial.length) }
        val pad = FrameLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(pad)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() || n.contains('/')) Toast.makeText(ctx, "Invalid name", Toast.LENGTH_SHORT).show() else ok(n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newFile(dir: File) = askName("New file", "") { n ->
        val f = File(dir, n)
        if (f.exists()) { Toast.makeText(ctx, "Already exists", Toast.LENGTH_SHORT).show(); return@askName }
        val ok = runCatching { dir.mkdirs(); f.createNewFile() }.getOrDefault(false)
        if (!ok) { Toast.makeText(ctx, "Could not create file", Toast.LENGTH_SHORT).show(); return@askName }
        expanded.add(dir.path); refresh(); onOpen(f)
    }

    private fun newFolder(dir: File) = askName("New folder", "") { n ->
        val f = File(dir, n)
        if (f.exists()) { Toast.makeText(ctx, "Already exists", Toast.LENGTH_SHORT).show(); return@askName }
        if (!f.mkdirs()) { Toast.makeText(ctx, "Could not create folder", Toast.LENGTH_SHORT).show(); return@askName }
        expanded.add(dir.path); expanded.add(f.path); refresh()
    }

    private fun rename(f: File) = askName("Rename", f.name) { n ->
        val dest = File(f.parentFile, n)
        if (dest.exists()) { Toast.makeText(ctx, "Already exists", Toast.LENGTH_SHORT).show(); return@askName }
        onGone(f)
        if (!f.renameTo(dest)) Toast.makeText(ctx, "Rename failed", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun delete(f: File) {
        AlertDialog.Builder(ctx)
            .setTitle("Delete ${f.name}?")
            .setMessage(if (f.isDirectory) "The folder and everything inside it will be deleted." else "This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                onGone(f)
                if (!f.deleteRecursively()) Toast.makeText(ctx, "Could not delete everything", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }
}
