package com.example.mynative.androidide

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Small terminal "renderer": handles \r, backspace, SGR colours (16/256/truecolor, bold),
 * erase-line / clear-screen. Full-screen cursor apps (vim, htop) are NOT emulated.
 */
class TerminalBuffer {

    private class Cell(var ch: Char, var fg: Int, var bg: Int, var bold: Boolean)

    private val lines = ArrayList<ArrayList<Cell>>().apply { add(ArrayList()) }
    private var col = 0
    private var fg = NONE
    private var bg = NONE
    private var bold = false

    // escape-sequence parser state
    private var esc = StringBuilder()
    private var inEsc = false

    @Synchronized
    fun feed(chunk: String) {
        for (ch in chunk) {
            if (inEsc) {
                esc.append(ch)
                if (esc.length == 1) {                       // char right after ESC
                    if (ch != '[' && ch != ']') { inEsc = false; esc.setLength(0) }
                    continue
                }
                if (esc[0] == '[') {
                    if (ch in '@'..'~') { csi(esc.substring(1, esc.length - 1), ch); inEsc = false; esc.setLength(0) }
                } else {                                     // OSC: ends with BEL or ESC \
                    if (ch == '\u0007' || (ch == '\\' && esc.length >= 2 && esc[esc.length - 2] == '\u001B')) {
                        inEsc = false; esc.setLength(0)
                    }
                }
                if (esc.length > 200) { inEsc = false; esc.setLength(0) }
                continue
            }
            when (ch) {
                '\u001B' -> { inEsc = true; esc.setLength(0) }
                '\n' -> { lines.add(ArrayList()); col = 0 }
                '\r' -> col = 0
                '\b' -> if (col > 0) col--
                '\u0007' -> {}
                '\t' -> { val n = 8 - (col % 8); repeat(n) { put(' ') } }
                else -> if (ch >= ' ') put(ch)
            }
        }
        trim()
    }

    private fun put(ch: Char) {
        val line = lines.last()
        while (line.size < col) line.add(Cell(' ', NONE, NONE, false))
        if (col < line.size) { line[col].apply { this.ch = ch; fg = this@TerminalBuffer.fg; bg = this@TerminalBuffer.bg; bold = this@TerminalBuffer.bold } }
        else line.add(Cell(ch, fg, bg, bold))
        col++
    }

    private fun csi(params: String, cmd: Char) {
        val nums = params.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        when (cmd) {
            'm' -> sgr(if (params.isEmpty()) listOf(0) else nums)
            'K' -> {                                          // erase in line
                val line = lines.last()
                when (nums.firstOrNull() ?: 0) {
                    0 -> while (line.size > col) line.removeAt(line.size - 1)
                    1 -> for (i in 0 until minOf(col, line.size)) line[i].ch = ' '
                    2 -> line.clear()
                }
            }
            'J' -> if ((nums.firstOrNull() ?: 0) >= 2) { lines.clear(); lines.add(ArrayList()); col = 0 }
            'D' -> col = maxOf(0, col - maxOf(1, nums.firstOrNull() ?: 1))
            'C' -> col += maxOf(1, nums.firstOrNull() ?: 1)
            'G' -> col = maxOf(0, (nums.firstOrNull() ?: 1) - 1)
            'P' -> { val line = lines.last(); repeat(maxOf(1, nums.firstOrNull() ?: 1)) { if (col < line.size) line.removeAt(col) } }
            '@' -> { val line = lines.last(); repeat(maxOf(1, nums.firstOrNull() ?: 1)) { if (col <= line.size) line.add(col, Cell(' ', NONE, NONE, false)) } }
            'H', 'f' -> if (nums.size <= 1 || (nums[0] <= 1 && nums[1] <= 1)) col = 0
        }
    }

    private fun sgr(n: List<Int>) {
        var i = 0
        while (i < n.size) {
            val c = n[i]
            when {
                c == 0 -> { fg = NONE; bg = NONE; bold = false }
                c == 1 -> bold = true
                c == 22 -> bold = false
                c in 30..37 -> fg = PALETTE[c - 30]
                c == 39 -> fg = NONE
                c in 40..47 -> bg = PALETTE[c - 40]
                c == 49 -> bg = NONE
                c in 90..97 -> fg = PALETTE[c - 90 + 8]
                c in 100..107 -> bg = PALETTE[c - 100 + 8]
                (c == 38 || c == 48) && i + 1 < n.size -> {
                    val target38 = c == 38
                    if (n[i + 1] == 5 && i + 2 < n.size) {
                        val v = color256(n[i + 2]); if (target38) fg = v else bg = v; i += 2
                    } else if (n[i + 1] == 2 && i + 4 < n.size) {
                        val v = (0xFF shl 24) or (n[i + 2].coerceIn(0, 255) shl 16) or (n[i + 3].coerceIn(0, 255) shl 8) or n[i + 4].coerceIn(0, 255)
                        if (target38) fg = v else bg = v; i += 4
                    }
                }
            }
            i++
        }
    }

    private fun trim() {
        if (lines.size > 3000) { repeat(lines.size - 2500) { lines.removeAt(0) } }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n") { l -> String(CharArray(l.size) { l[it].ch }) }

    /** Coloured text for the TextView. */
    @Synchronized
    fun spannable(): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { idx, line ->
            var i = 0
            while (i < line.size) {
                var j = i + 1
                while (j < line.size && line[j].fg == line[i].fg && line[j].bg == line[i].bg && line[j].bold == line[i].bold) j++
                val start = sb.length
                for (k in i until j) sb.append(line[k].ch)
                val c = line[i]
                if (c.fg != NONE) sb.setSpan(ForegroundColorSpan(c.fg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (c.bg != NONE) sb.setSpan(BackgroundColorSpan(c.bg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (c.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = j
            }
            if (idx < lines.size - 1) sb.append('\n')
        }
        return sb
    }

    @Synchronized
    fun clear() { lines.clear(); lines.add(ArrayList()); col = 0; fg = NONE; bg = NONE; bold = false; inEsc = false; esc.setLength(0) }

    companion object {
        private const val NONE = 0
        private val PALETTE = intArrayOf(
            0xFF000000.toInt(), 0xFFE05561.toInt(), 0xFF8CC265.toInt(), 0xFFE5C07B.toInt(),
            0xFF4AA5F0.toInt(), 0xFFC162DE.toInt(), 0xFF42B3C2.toInt(), 0xFFD7DAE0.toInt(),
            0xFF7F848E.toInt(), 0xFFFF6B77.toInt(), 0xFFA5E075.toInt(), 0xFFEBCB8B.toInt(),
            0xFF61AFEF.toInt(), 0xFFD27AF0.toInt(), 0xFF56D4E4.toInt(), 0xFFFFFFFF.toInt()
        )

        private fun color256(n: Int): Int = when {
            n < 16 -> PALETTE[n.coerceAtLeast(0)]
            n < 232 -> {
                val v = n - 16
                fun lvl(x: Int) = if (x == 0) 0 else 55 + x * 40
                (0xFF shl 24) or (lvl(v / 36) shl 16) or (lvl((v / 6) % 6) shl 8) or lvl(v % 6)
            }
            else -> { val g = 8 + (n - 232) * 10; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        }
    }
}
