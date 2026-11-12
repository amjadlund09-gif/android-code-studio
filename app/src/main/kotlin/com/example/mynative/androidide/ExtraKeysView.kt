package com.example.mynative.androidide

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Two rows of terminal keys under the screen:
 *   ESC  /  -  HOME  UP   END  PGUP
 *   TAB  CTRL ALT LEFT DOWN RIGHT PGDN
 * CTRL and ALT stay on until the next key. Arrows and PgUp/PgDn repeat while held.
 */
class ExtraKeysView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    interface Listener {
        fun onText(text: String)
        fun onKey(keyCode: Int)
        fun onCtrl(on: Boolean)
        fun onAlt(on: Boolean)
    }

    var listener: Listener? = null

    private class Key(val label: String, val text: String? = null, val code: Int = 0, val repeat: Boolean = false, val mod: Int = 0)

    private val handler = Handler(Looper.getMainLooper())
    private var ctrlView: TextView? = null
    private var altView: TextView? = null

    var ctrlOn = false
        set(v) { field = v; style(ctrlView, v) }
    var altOn = false
        set(v) { field = v; style(altView, v) }

    init {
        orientation = VERTICAL
        setBackgroundColor(TerminalView.DEFAULT_BG)
        val pad = (4 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        val rows = listOf(
            listOf(
                Key("ESC", code = KeyEvent.KEYCODE_ESCAPE), Key("/", text = "/"), Key("—", text = "-"),
                Key("HOME", code = KeyEvent.KEYCODE_MOVE_HOME), Key("↑", code = KeyEvent.KEYCODE_DPAD_UP, repeat = true),
                Key("END", code = KeyEvent.KEYCODE_MOVE_END), Key("PGUP", code = KeyEvent.KEYCODE_PAGE_UP, repeat = true)
            ),
            listOf(
                Key("⇥", code = KeyEvent.KEYCODE_TAB), Key("CTRL", mod = 1), Key("ALT", mod = 2),
                Key("←", code = KeyEvent.KEYCODE_DPAD_LEFT, repeat = true), Key("↓", code = KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
                Key("→", code = KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true), Key("PGDN", code = KeyEvent.KEYCODE_PAGE_DOWN, repeat = true)
            )
        )
        val h = (50 * resources.displayMetrics.density).toInt()
        for (row in rows) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            val m = (2 * resources.displayMetrics.density).toInt()
            for (k in row) line.addView(makeKey(k), LayoutParams(0, h, 1f).apply { setMargins(m, m, m, m) })
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, h))
        }
    }

    private fun makeKey(k: Key): TextView {
        val tv = TextView(context).apply {
            text = k.label
            gravity = Gravity.CENTER
            setTextColor(KEY_TEXT)
            textSize = if (k.label.length > 1 && k.label[0].isLetter()) 15f else 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isClickable = true
            background = keyBackground()
        }
        if (k.mod == 1) ctrlView = tv
        if (k.mod == 2) altView = tv

        val repeater = object : Runnable {
            override fun run() { fire(k); handler.postDelayed(this, 60) }
        }
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    fire(k)
                    if (k.repeat) handler.postDelayed(repeater, 380)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeater)
                }
            }
            true
        }
        return tv
    }

    private fun fire(k: Key) {
        when {
            k.mod == 1 -> { ctrlOn = !ctrlOn; listener?.onCtrl(ctrlOn) }
            k.mod == 2 -> { altOn = !altOn; listener?.onAlt(altOn) }
            k.text != null -> listener?.onText(k.text)
            else -> listener?.onKey(k.code)
        }
    }

    private fun style(v: TextView?, on: Boolean) {
        v ?: return
        v.setTextColor(if (on) KEY_ON else KEY_TEXT)
        v.background = if (on) rounded(0xFF3A3466.toInt(), 0xFFB9A8FF.toInt()) else keyBackground()
    }

    private fun rounded(fill: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = 10 * resources.displayMetrics.density
        if (stroke != 0) setStroke((1.5f * resources.displayMetrics.density).toInt(), stroke)
    }

    /** Rounded key: soft grey, lighter while pressed. */
    private fun keyBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFF3A3A48.toInt()))
        addState(intArrayOf(), rounded(0xFF1E1E26.toInt()))
    }

    companion object {
        private val KEY_TEXT = 0xFFE8E8F0.toInt()
        private val KEY_ON = 0xFFB9A8FF.toInt()
    }
}
