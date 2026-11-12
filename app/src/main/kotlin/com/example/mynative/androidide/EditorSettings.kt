package com.example.mynative.androidide

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

/** Persistent options of the Pro editor (prefs "editor_pro"). */
object EditorSettings {

    val THEMES = listOf("darcula", "ayu-dark", "solarized_dark", "quietlight")

    private fun p(c: Context) = c.getSharedPreferences("editor_pro", Context.MODE_PRIVATE)

    fun bool(c: Context, k: String, def: Boolean) = p(c).getBoolean(k, def)
    fun int(c: Context, k: String, def: Int) = p(c).getInt(k, def)
    fun str(c: Context, k: String, def: String) = p(c).getString(k, def) ?: def
    fun put(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    fun put(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).apply()
    fun put(c: Context, k: String, v: String) = p(c).edit().putString(k, v).apply()

    fun fontSp(c: Context) = int(c, "font", 14).coerceIn(8, 28)
    fun tab(c: Context) = int(c, "tab", 4).coerceIn(1, 8)
    fun wrap(c: Context) = bool(c, "wrap", true)
    fun lineNumbers(c: Context) = bool(c, "lines", true)
    fun pinLines(c: Context) = bool(c, "pin", false)
    fun whitespace(c: Context) = bool(c, "ws", false)
    fun lineEndings(c: Context) = bool(c, "eol", false)
    fun sticky(c: Context) = bool(c, "sticky", true)
    fun autoIndent(c: Context) = bool(c, "indent", true)
    fun autoPair(c: Context) = bool(c, "pair", true)
    fun blink(c: Context) = bool(c, "blink", true)
    fun ligatures(c: Context) = bool(c, "liga", true)
    fun blockLines(c: Context) = bool(c, "blocks", true)
    fun brackets(c: Context) = bool(c, "brackets", true)
    fun theme(c: Context) = str(c, "theme", "darcula").let { if (it in THEMES) it else "darcula" }
}

/** Small programmatic settings screen (no XML needed). Every change is applied live. */
object EditorSettingsDialog {

    fun show(activity: Activity, onChanged: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        fun header(t: String) {
            col.addView(TextView(activity).apply {
                text = t
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(4))
            })
        }

        fun toggle(title: String, sub: String, key: String, def: Boolean) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(activity).apply { text = title; textSize = 16f })
            texts.addView(TextView(activity).apply { text = sub; textSize = 12f; alpha = 0.65f })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = SwitchCompat(activity).apply {
                isChecked = EditorSettings.bool(activity, key, def)
                setOnCheckedChangeListener { _, on ->
                    EditorSettings.put(activity, key, on)
                    onChanged()
                }
            }
            row.addView(sw)
            col.addView(row)
        }

        fun slider(title: String, key: String, def: Int, min: Int, max: Int, unit: String) {
            val label = TextView(activity).apply { textSize = 16f; setPadding(0, dp(8), 0, 0) }
            val cur = EditorSettings.int(activity, key, def).coerceIn(min, max)
            label.text = "$title: $cur$unit"
            val bar = SeekBar(activity).apply {
                this.max = max - min
                progress = cur - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        label.text = "$title: ${p + min}$unit"
                        if (fromUser) {
                            EditorSettings.put(activity, key, p + min)
                            onChanged()
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            col.addView(label)
            col.addView(bar)
        }

        header("Theme")
        val themeBtn = TextView(activity).apply {
            textSize = 16f
            setPadding(0, dp(10), 0, dp(10))
            text = "Editor theme: ${EditorSettings.theme(activity)}  ▾"
            setOnClickListener {
                val names = EditorSettings.THEMES.toTypedArray()
                val at = names.indexOf(EditorSettings.theme(activity)).coerceAtLeast(0)
                AlertDialog.Builder(activity)
                    .setTitle("Editor theme")
                    .setSingleChoiceItems(names, at) { dlg, which ->
                        EditorSettings.put(activity, "theme", names[which])
                        text = "Editor theme: ${names[which]}  ▾"
                        onChanged()
                        dlg.dismiss()
                    }
                    .show()
            }
        }
        col.addView(themeBtn)

        header("Display")
        slider("Font size", "font", 14, 8, 28, "sp")
        slider("Tab size", "tab", 4, 1, 8, " spaces")
        toggle("Line numbers", "Show line numbers in the gutter", "lines", true)
        toggle("Pin line numbers", "Keep them visible when scrolling sideways", "pin", false)
        toggle("Word wrap", "Wrap long lines to the screen", "wrap", true)
        toggle("Show whitespace", "Draw spaces and tabs", "ws", false)
        toggle("Show line endings", "Draw line break characters", "eol", false)
        toggle("Font ligatures", "JetBrains Mono ligatures (=> != ->)", "liga", true)

        header("Code structure")
        toggle("Sticky scroll", "Pin the current class / function header on top", "sticky", true)
        toggle("Block lines", "Vertical guides for code blocks", "blocks", true)
        toggle("Bracket matching", "Highlight the matching bracket", "brackets", true)

        header("Behavior")
        toggle("Auto indent", "Indent new lines automatically", "indent", true)
        toggle("Auto pairing", "Auto-close brackets and quotes", "pair", true)
        toggle("Cursor blink", "Animate the cursor", "blink", true)

        val scroll = ScrollView(activity).apply { addView(col) }
        AlertDialog.Builder(activity)
            .setTitle("Editor settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }
}
