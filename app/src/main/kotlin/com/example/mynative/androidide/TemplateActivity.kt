package com.example.mynative.androidide

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.mynative.androidide.databinding.ActivityTemplateBinding
import com.example.mynative.androidide.databinding.ItemTemplateBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

class TemplateActivity : AppCompatActivity() {

    private class Template(
        val title: String,
        val assetDir: String?,      // folder inside assets/template/ (null = no template files)
        val preview: Int,
        val caption: String? = null,
        val category: String = "Other",
        val desc: String = "",
        val gets: List<String> = emptyList()
    )

    private val categories = listOf("All", "View", "Compose", "Native", "Other")

    private val templates = listOf(
        Template(
            "Plain Editor", "PlainEditor", R.drawable.tpl_plain_editor,
            category = "Other",
            desc = "Just a folder with a script and a README - no Gradle, no Android. For quick experiments in the terminal.",
            gets = listOf("main.sh", "README.md")
        ),
        Template(
            "Library", "Library", R.drawable.tpl_library, "AAR/JVM",
            category = "Other",
            desc = "A reusable module with a sample class that other projects can depend on.",
            gets = listOf("Greeter class (Kotlin or Java)", "Library manifest")
        ),
        Template(
            "No Activity", "NoActivity", R.drawable.tpl_no_activity,
            category = "View",
            desc = "An app with theme and resources but no screen yet. Start from nothing.",
            gets = listOf("Manifest, theme, colors, strings", "No Activity class")
        ),
        Template(
            "Basic Activity", "BasicActivity", R.drawable.tpl_basic,
            category = "View",
            desc = "A screen with a toolbar, an options menu and a content area.",
            gets = listOf("MainActivity (Kotlin or Java)", "Toolbar + options menu", "activity_main and content_main layouts")
        ),
        Template(
            "Empty Activity", "EmptyActivity", R.drawable.tpl_empty,
            category = "View",
            desc = "One screen with one layout. The simplest starting point.",
            gets = listOf("MainActivity (Kotlin or Java)", "activity_main layout", "Theme, colors, strings")
        ),
        Template(
            "Compose Activity", "ComposeEmptyActivity", R.drawable.tpl_compose,
            category = "Compose",
            desc = "One Jetpack Compose screen with Material3. Kotlin only.",
            gets = listOf("MainActivity with setContent", "Compose BOM + Material3", "Theme and strings")
        ),
        Template(
            "Compose Navigation", "ComposeNavigation", R.drawable.tpl_compose, "Material3 + NavHost",
            category = "Compose",
            desc = "Material3 bottom bar with a NavHost and a detail screen.",
            gets = listOf("AppRoot (NavHost)", "Screens.kt", "navigation-compose")
        ),
        Template(
            "Compose Notes", "ComposeNotes", R.drawable.tpl_compose, "ViewModel + DataStore",
            category = "Compose",
            desc = "A small notes app: ViewModel + StateFlow, saved with DataStore.",
            gets = listOf("NotesScreen", "NotesViewModel", "NotesStore (DataStore)")
        ),
        Template(
            "Bottom Navigation", "BottomNavigationActivity", R.drawable.tpl_bottom_nav,
            category = "View",
            desc = "A bottom navigation bar that switches between fragment pages.",
            gets = listOf("MainActivity", "PageFragment", "Bottom navigation menu")
        ),
        Template(
            "Navigation drawer", "NavigationDrawerActivity", R.drawable.tpl_nav_drawer,
            category = "View",
            desc = "A side drawer with a header and fragment pages.",
            gets = listOf("MainActivity", "PageFragment", "Drawer header layout")
        ),
        Template(
            "Responsive activity", "ResponsiveActivity", R.drawable.tpl_responsive,
            category = "View",
            desc = "A different layout for wide screens (tablets, foldables) through layout-w600dp.",
            gets = listOf("layout + layout-w600dp", "MainActivity", "PageFragment")
        ),
        Template(
            "Game Activity", "GameActivity", R.drawable.tpl_game,
            category = "Native",
            desc = "A native C++ game with its own renderer. Needs Android 11 (API 30) or newer.",
            gets = listOf("MainActivity", "Native Renderer, Shader, TextureAsset", "CMakeLists.txt")
        ),
        Template(
            "Native C++", "NativeCpp", R.drawable.tpl_native_cpp,
            category = "Native",
            desc = "An app that calls C++ (or C) through JNI. Build with CMake or ndk-build.",
            gets = listOf("MainActivity", "native-lib.cpp / native-lib.c", "CMakeLists.txt or Android.mk")
        ),
        Template(
            "Flutter Activity", "FlutterActivity", R.drawable.tpl_flutter,
            category = "Other",
            desc = "A Flutter project: Dart code plus the Android host app.",
            gets = listOf("Dart project files", "Android host (Gradle)")
        )
    )

    private lateinit var binding: ActivityTemplateBinding
    private val prefs by lazy { getSharedPreferences("templates", MODE_PRIVATE) }

    private var filter = "All"
    private var query = ""
    private val chipViews = LinkedHashMap<String, TextView>()

    private val configLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == ProjectConfigActivity.RESULT_CLOSE_ALL) finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        buildChips()
        binding.etSearch.doAfterTextChanged {
            query = it?.toString().orEmpty().trim().lowercase()
            render()
        }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- search + filter chips -------------------------------------------------------------

    private fun buildChips() {
        categories.forEach { cat ->
            val chip = TextView(this).apply {
                text = cat
                textSize = 14f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { filter = cat; updateChips(); render() }
            }
            chipViews[cat] = chip
            binding.chips.addView(chip)
        }
        updateChips()
    }

    private fun updateChips() {
        val primary = ContextCompat.getColor(this, R.color.sdk_primary)
        chipViews.forEach { (cat, v) ->
            val on = cat == filter
            v.setBackgroundResource(if (on) R.drawable.bg_sdk_chip_sel else R.drawable.bg_sdk_chip)
            v.setTextColor(if (on) primary else 0xFF6B6B6B.toInt())
            v.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ---- recent --------------------------------------------------------------------------------

    private fun recent(): List<String> =
        (prefs.getString("recent", "") ?: "").split(',').filter { it.isNotEmpty() }

    private fun pushRecent(dir: String) {
        val list = (listOf(dir) + recent().filter { it != dir }).take(3)
        prefs.edit().putString("recent", list.joinToString(",")).apply()
    }

    // ---- grid ------------------------------------------------------------------------------------

    private fun render() {
        binding.container.removeAllViews()
        val q = query
        val list = templates.filter { t ->
            (filter == "All" || t.category == filter) &&
                (q.isEmpty() || (t.title + " " + (t.caption ?: "") + " " + t.category + " " + t.desc).lowercase().contains(q))
        }
        if (list.isEmpty()) {
            binding.container.addView(TextView(this).apply {
                text = "No template matches"
                textSize = 16f
                setTextColor(0xFF6B6B6B.toInt())
                setPadding(dp(8), dp(24), dp(8), dp(8))
            })
            return
        }
        if (q.isEmpty() && filter == "All") {
            val rec = recent().mapNotNull { d -> templates.firstOrNull { it.assetDir == d } }
            if (rec.isNotEmpty()) {
                addHeader("RECENT")
                addRows(rec)
                addHeader("ALL TEMPLATES")
            }
        }
        addRows(list)
    }

    private fun addHeader(title: String) {
        binding.container.addView(TextView(this).apply {
            text = title
            textSize = 13f
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@TemplateActivity, R.color.sdk_section))
            setPadding(dp(8), dp(14), dp(8), dp(4))
        })
    }

    private fun addRows(list: List<Template>) {
        val gap = dp(5)
        val cardHeight = dp(210)
        list.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            pair.forEach { tpl ->
                val item = ItemTemplateBinding.inflate(layoutInflater, row, false)
                item.tvTitle.text = tpl.title
                item.ivPreview.setImageResource(tpl.preview)
                if (tpl.caption != null) {
                    item.tvCaption.text = tpl.caption
                    item.tvCaption.visibility = View.VISIBLE
                }
                item.root.layoutParams = LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                    setMargins(gap, gap, gap, gap)
                }
                item.root.setOnClickListener { onTemplateSelected(tpl) }      // straight to Project Configuration
                item.root.setOnLongClickListener { showSheet(tpl); true }   // details sheet on long-press
                row.addView(item.root)
            }
            binding.container.addView(row)
        }
    }

    // ---- details sheet -----------------------------------------------------------------------------

    private fun showSheet(tpl: Template) {
        val primary = ContextCompat.getColor(this, R.color.sdk_primary)
        val dark = ContextCompat.getColor(this, R.color.text_primary)
        val sheet = BottomSheetDialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }

        fun label(s: String, size: Float, color: Int, bold: Boolean, top: Int) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(top) }
        }

        col.addView(ImageView(this).apply {
            setImageResource(tpl.preview)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
        })
        col.addView(label(tpl.title, 22f, dark, true, 12))
        col.addView(label(tpl.category + (tpl.caption?.let { " · $it" } ?: ""), 14f, primary, true, 2))
        if (tpl.desc.isNotEmpty()) col.addView(label(tpl.desc, 16f, 0xFF444444.toInt(), false, 12))
        if (tpl.gets.isNotEmpty()) {
            col.addView(label("YOU GET", 12f, 0xFF6B6B6B.toInt(), true, 16))
            tpl.gets.forEach { col.addView(label("• $it", 15f, dark, false, 4)) }
        }
        tpl.assetDir?.let { dir ->
            val reqs = runCatching { SdkNeeds.requirements(this, dir) }.getOrDefault(emptyList())
            val meta = TemplateMeta.load(this, dir)
            if (reqs.isNotEmpty() || !meta?.note.isNullOrEmpty()) {
                col.addView(label("NEEDS ON THIS PHONE", 12f, 0xFF6B6B6B.toInt(), true, 16))
                reqs.forEach { r ->
                    col.addView(
                        label(
                            (if (r.ok) "✔ " else "⚠ ") + SdkNeeds.label(r.need.id) + " - " + r.need.why,
                            15f, if (r.ok) dark else 0xFFB26A00.toInt(), false, 4
                        )
                    )
                }
                meta?.note?.takeIf { it.isNotEmpty() }?.let { col.addView(label(it, 14f, 0xFF6B6B6B.toInt(), false, 6)) }
            }
        }
        col.addView(MaterialButton(this).apply {
            text = "Use this template"
            backgroundTintList = ColorStateList.valueOf(primary)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(20)
            }
            setOnClickListener { sheet.dismiss(); onTemplateSelected(tpl) }
        })
        val surface = android.graphics.drawable.GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@TemplateActivity, R.color.bg_page))
            val r = dp(28).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        sheet.setContentView(ScrollView(this).apply { background = surface; addView(col) })
        sheet.show()
        // the sheet's own (theme-coloured) frame must not show behind our light surface
        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun onTemplateSelected(tpl: Template) {
        val dir = tpl.assetDir ?: return
        if (dir == "FlutterActivity" && !FlutterInstaller.isInstalled(this)) {
            // a Flutter project can only be built with the Flutter SDK: offer the package instead of opening the template
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Flutter package not installed")
                .setMessage("The Flutter template needs the Flutter SDK (about 2 GB on this phone). Install the Flutter package in the SDK Manager first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install Flutter package") { _, _ ->
                    startActivity(Intent(this, SdkManagerActivity::class.java)
                        .putExtra(SdkManagerActivity.EXTRA_IDS, arrayOf(FlutterInstaller.ID)))
                }
                .show()
            return
        }
        pushRecent(dir)
        val intent = Intent(this, ProjectConfigActivity::class.java)
            .putExtra(ProjectConfigActivity.EXTRA_TEMPLATE, dir)
        configLauncher.launch(intent)
    }
}
