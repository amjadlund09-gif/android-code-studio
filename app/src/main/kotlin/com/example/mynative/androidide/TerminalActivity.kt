package com.example.mynative.androidide

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.mynative.androidide.databinding.ActivityTerminalBinding

/**
 * Termux-style terminal: session tabs on top, the screen, and the extra-keys rows below.
 * Sessions live in [TermSessions] (kept alive by [TermService]); this screen only shows them.
 */
class TerminalActivity : AppCompatActivity(), TermSession.Callback {

    private lateinit var b: ActivityTerminalBinding
    private var current: TermSession? = null
    private var installing = false
    @Volatile private var cancelInstall = false
    private var keyboardShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.extraKeys.listener = object : ExtraKeysView.Listener {
            override fun onText(text: String) = b.terminalView.sendText(text)
            override fun onKey(keyCode: Int) = b.terminalView.sendKeyCode(keyCode)
            override fun onCtrl(on: Boolean) { b.terminalView.ctrlSticky = on }
            override fun onAlt(on: Boolean) { b.terminalView.altSticky = on }
        }
        b.terminalView.onStickyUsed = {
            b.extraKeys.ctrlOn = false
            b.extraKeys.altOn = false
        }
        b.terminalView.onFinishedEnter = { current?.let { closeSession(it) } }
        b.btnNewTab.setOnClickListener { newSession(false) }
        b.btnInstall.setOnClickListener { startInstall() }
        b.btnWipe.setOnClickListener { if (!installing) { TermuxEnv.wipe(this); startInstall() } }
        b.btnBasic.setOnClickListener { hideSetup(); newSession(true) }

        TermSessions.list.forEach { it.callback = this }
        if (TermSessions.list.isEmpty()) {
            if (TermuxEnv.isReady(this)) newSession(false) else showSetup()
        } else {
            select(TermSessions.list.last())
        }
    }

    override fun onResume() {
        super.onResume()
        TermSessions.list.forEach { it.callback = this }
        if (b.setupPanel.visibility != View.VISIBLE) {
            b.terminalView.requestFocus()
            if (!keyboardShown) { keyboardShown = true; b.terminalView.post { b.terminalView.showKeyboard() } }
        }
    }

    /** Like Termux: Volume Down acts as the Ctrl key for the next key (Volume Up is left to Android). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && b.setupPanel.visibility != View.VISIBLE && current != null) {
            val on = !b.terminalView.ctrlSticky
            b.terminalView.ctrlSticky = on
            b.extraKeys.ctrlOn = on
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && b.setupPanel.visibility != View.VISIBLE && current != null) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        cancelInstall = true
        TermSessions.list.forEach { if (it.callback === this) it.callback = null }
        super.onDestroy()
    }

    // ---- sessions ---------------------------------------------------------------------------------------------

    private fun newSession(basic: Boolean) {
        val rows = if (b.terminalView.rows > 0) b.terminalView.rows else 24
        val cols = if (b.terminalView.cols > 1) b.terminalView.cols else 80
        val s = TermSessions.create(this, rows, cols, basic)
        s.callback = this
        select(s)
    }

    private fun select(s: TermSession) {
        current = s
        b.terminalView.attach(s)
        b.extraKeys.ctrlOn = false
        b.extraKeys.altOn = false
        b.terminalView.ctrlSticky = false
        b.terminalView.altSticky = false
        rebuildTabs()
        b.terminalView.requestFocus()
    }

    private fun closeSession(s: TermSession) {
        val idx = TermSessions.list.indexOf(s)
        TermSessions.remove(this, s)
        if (TermSessions.list.isEmpty()) {
            finish()
            return
        }
        if (s === current) select(TermSessions.list[(idx - 1).coerceAtLeast(0).coerceAtMost(TermSessions.list.size - 1)])
        else rebuildTabs()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rebuildTabs() {
        b.tabs.removeAllViews()
        var selectedView: View? = null
        for (s in TermSessions.list) {
            val sel = s === current
            val tab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tab.addView(View(this).apply { setBackgroundColor(if (sel) 0xFFB9A8FF.toInt() else Color.TRANSPARENT) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(6), 0)
            }
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_terminal)
                setColorFilter(if (sel) 0xFFB9A8FF.toInt() else 0xFF8C8C98.toInt())
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            row.addView(TextView(this).apply {
                text = (if (s.finished) "✓ " else "") + s.title
                setTextColor(if (sel) Color.WHITE else 0xFF9A9AA6.toInt())
                textSize = 17f
                maxLines = 1
                setPadding(dp(10), 0, dp(8), 0)
            })
            row.addView(TextView(this).apply {
                text = "✕"
                setTextColor(0xFF8C8C98.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                setOnClickListener { closeSession(s) }
            }, LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.MATCH_PARENT))
            tab.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f))
            tab.setOnClickListener { if (s !== current) select(s) }
            tab.setOnLongClickListener { rename(s); true }
            b.tabs.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            if (sel) selectedView = tab
        }
        selectedView?.let { v -> b.tabScroll.post { b.tabScroll.smoothScrollTo(v.left - dp(20), 0) } }
    }

    private fun rename(s: TermSession) {
        val input = EditText(this).apply { setText(s.title); setSelectAllOnFocus(true); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Rename session").setView(input)
            .setPositiveButton("OK") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { s.title = t; rebuildTabs() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- TermSession.Callback (reader thread for onUpdate, main thread for the rest) ----------------------------

    override fun onUpdate(s: TermSession) { if (s === current) b.terminalView.requestRedraw() }
    override fun onTitle(s: TermSession) { runOnUiThread { rebuildTabs() } }
    override fun onExit(s: TermSession) { rebuildTabs() }
    override fun onBell(s: TermSession) { }

    // ---- first-run setup ----------------------------------------------------------------------------------------------------

    private fun showSetup() {
        b.setupPanel.visibility = View.VISIBLE
        b.btnInstall.isEnabled = true
        b.btnInstall.text = "Install"
        b.btnWipe.visibility = View.GONE
    }

    private fun hideSetup() {
        b.setupPanel.visibility = View.GONE
        b.terminalView.requestFocus()
    }

    private fun startInstall() {
        if (installing) return
        installing = true
        cancelInstall = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.btnInstall.isEnabled = false
        b.btnWipe.visibility = View.GONE
        b.setupTitle.text = "Setting up Termux..."
        val app = applicationContext
        val log = TaskLog { text ->
            runOnUiThread {
                b.setupLog.text = text
                b.setupScroll.post { b.setupScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        Thread({
            var err: String? = null
            try {
                TermuxBootstrap(app).install(log) { cancelInstall }
            } catch (e: InstallCancelled) {
                err = "Stopped"
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                log.line("")
                log.line("Error: $err")
            }
            runOnUiThread {
                installing = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (err == null) {
                    hideSetup()
                    newSession(false)
                } else {
                    b.setupTitle.text = "Setup stopped"
                    b.btnInstall.isEnabled = true
                    b.btnInstall.text = "Retry"
                    b.btnWipe.visibility = View.VISIBLE
                }
            }
        }, "termux-install").start()
    }
}
