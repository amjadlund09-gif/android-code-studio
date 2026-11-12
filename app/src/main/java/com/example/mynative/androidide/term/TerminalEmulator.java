package com.example.mynative.androidide.term;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * VT100 / xterm terminal emulator (screen model + escape-sequence parser). No Android classes,
 * so it can be unit-tested on a desktop JVM.
 *
 * Supported: cursor addressing, scroll regions (DECSTBM), insert/delete line + char, erase,
 * SGR (16 / 256 / truecolor, bold, dim, italic, underline, inverse, strike), alternate screen
 * (47 / 1047 / 1049), application cursor keys, bracketed paste, origin mode, auto-wrap, tab stops,
 * DEC line drawing charset, wide (CJK / emoji) characters, scrollback, DA / DSR replies.
 *
 * Threading: every public method that touches the screen is synchronized on this object; the
 * view must hold the same lock while it draws.
 */
public final class TerminalEmulator {

    public interface Listener {
        /** Reply to the program running in the terminal (DA, DSR ...). */
        void write(String reply);
        void onTitle(String title);
        void onBell();
    }

    // ---- cell attributes (one long per cell) --------------------------------------------------
    public static final long BOLD = 1, DIM = 2, ITALIC = 4, UNDERLINE = 8, BLINK = 16,
            INVERSE = 32, INVISIBLE = 64, STRIKE = 128;
    private static final long FG_SET = 1L << 8;
    private static final long BG_SET = 1L << 33;
    private static final int FG_SHIFT = 9, BG_SHIFT = 34;
    private static final long RGB = 0xFFFFFFL;
    private static final long BG_MASK = BG_SET | (RGB << BG_SHIFT);

    public static boolean hasFg(long a) { return (a & FG_SET) != 0; }
    public static boolean hasBg(long a) { return (a & BG_SET) != 0; }
    public static int fg(long a) { return (int) ((a >>> FG_SHIFT) & RGB); }
    public static int bg(long a) { return (int) ((a >>> BG_SHIFT) & RGB); }

    private static long withFg(long a, int rgb) {
        return (a & ~(FG_SET | (RGB << FG_SHIFT))) | FG_SET | (((long) rgb & RGB) << FG_SHIFT);
    }

    private static long withBg(long a, int rgb) {
        return (a & ~BG_MASK) | BG_SET | (((long) rgb & RGB) << BG_SHIFT);
    }

    /** Cell value of the second half of a wide character. */
    public static final int WIDE_TAIL = -1;

    private static final int[] PALETTE = new int[256];

    static {
        int[] base = {
                0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
                0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF};
        System.arraycopy(base, 0, PALETTE, 0, 16);
        for (int i = 16; i < 232; i++) {
            int v = i - 16;
            PALETTE[i] = (lvl(v / 36) << 16) | (lvl((v / 6) % 6) << 8) | lvl(v % 6);
        }
        for (int i = 232; i < 256; i++) {
            int g = 8 + (i - 232) * 10;
            PALETTE[i] = (g << 16) | (g << 8) | g;
        }
    }

    private static int lvl(int x) { return x == 0 ? 0 : 55 + x * 40; }

    public static int palette(int idx) { return PALETTE[idx & 255]; }

    // ---- lines ---------------------------------------------------------------------------------
    public static final class Line {
        public int[] ch;          // 0 = blank, WIDE_TAIL = right half of a wide char, else code point
        public long[] at;
        public boolean wrapped;   // this line continues on the next one (soft wrap)

        Line(int cols, long fill) {
            ch = new int[cols];
            at = new long[cols];
            if (fill != 0) Arrays.fill(at, fill);
        }

        Line resized(int cols) {
            Line n = new Line(cols, 0);
            int m = Math.min(cols, ch.length);
            System.arraycopy(ch, 0, n.ch, 0, m);
            System.arraycopy(at, 0, n.at, 0, m);
            n.wrapped = wrapped;
            return n;
        }
    }

    // ---- state ---------------------------------------------------------------------------------
    private final Listener listener;
    private final int maxScrollback;
    private int cols, rows;
    private Line[] main, alt, screen;
    private boolean altActive;
    private final ArrayList<Line> scrollback = new ArrayList<>();

    private int row, col;
    private boolean pendingWrap;
    private long attr;
    private int scrollTop, scrollBottom;
    private boolean[] tabs;

    // modes
    private boolean appCursorKeys, autoWrap = true, originMode, cursorVisible = true,
            bracketedPaste, insertMode, appKeypad;
    private int cursorStyle;
    private int mouseMode;      // 0 none, 1000 click, 1002 drag, 1003 any
    private boolean mouseSgr;

    // charsets: 0 = ASCII, 1 = DEC special graphics
    private final int[] charset = new int[4];
    private int gl;

    private int savedRow, savedCol;
    private long savedAttr;
    private boolean savedWrap, savedOrigin;
    private int savedGl;
    private final int[] savedCharset = new int[4];
    private int altSavedRow, altSavedCol;

    private int lastChar;
    private String title = "";

    // parser
    private static final int S_GROUND = 0, S_ESC = 1, S_CSI = 2, S_OSC = 3, S_STR = 4, S_CHARSET = 5,
            S_HASH = 6, S_ESC_PCT = 7;
    private int state = S_GROUND;
    private boolean strEsc;
    private final StringBuilder seq = new StringBuilder();
    private int charsetTarget;
    private char pendingHigh;

    public TerminalEmulator(int cols, int rows, int maxScrollback, Listener listener) {
        this.cols = Math.max(2, cols);
        this.rows = Math.max(1, rows);
        this.maxScrollback = maxScrollback;
        this.listener = listener;
        main = newScreen(this.cols, this.rows);
        alt = newScreen(this.cols, this.rows);
        screen = main;
        reset();
    }

    private static Line[] newScreen(int cols, int rows) {
        Line[] s = new Line[rows];
        for (int i = 0; i < rows; i++) s[i] = new Line(cols, 0);
        return s;
    }

    // ---- public read API (call inside synchronized(emulator)) --------------------------------------
    public int getCols() { return cols; }
    public int getRows() { return rows; }
    public int getScrollbackSize() { return scrollback.size(); }
    public boolean isAltScreen() { return altActive; }
    public boolean isAppCursorKeys() { return appCursorKeys; }
    public boolean isAppKeypad() { return appKeypad; }
    public boolean isBracketedPaste() { return bracketedPaste; }
    public boolean isCursorVisible() { return cursorVisible; }
    public int getCursorStyle() { return cursorStyle; }
    public int getCursorRow() { return row; }
    public int getCursorCol() { return pendingWrap ? cols - 1 : col; }
    public String getTitle() { return title; }
    public int getMouseMode() { return mouseMode; }
    public boolean isMouseSgr() { return mouseSgr; }

    /** r in [-scrollbackSize, rows-1]. */
    public Line getLine(int r) {
        if (r >= 0) return r < rows ? screen[r] : null;
        int i = scrollback.size() + r;
        return i >= 0 ? scrollback.get(i) : null;
    }

    public synchronized String getText(int r1, int c1, int r2, int c2) {
        StringBuilder sb = new StringBuilder();
        for (int r = r1; r <= r2; r++) {
            Line l = getLine(r);
            if (l == null) continue;
            int start = r == r1 ? c1 : 0;
            int end = r == r2 ? c2 : l.ch.length - 1;
            end = Math.min(end, l.ch.length - 1);
            int lineStart = sb.length();
            for (int c = Math.max(0, start); c <= end; c++) {
                int cp = l.ch[c];
                if (cp == WIDE_TAIL) continue;
                sb.appendCodePoint(cp == 0 ? ' ' : cp);
            }
            // a selection that runs to the end of the row drops the blank cells after the text
            if (end >= l.ch.length - 1) {
                int e = sb.length();
                while (e > lineStart && sb.charAt(e - 1) == ' ') e--;
                sb.setLength(e);
            }
            if (r < r2 && !l.wrapped) sb.append('\n');
        }
        return sb.toString();
    }

    /** Whole screen + scrollback as text (for "select all"). */
    public synchronized String getAllText() {
        int first = -scrollback.size();
        return getText(first, 0, rows - 1, cols - 1);
    }

    // ---- reset / resize ----------------------------------------------------------------------------
    public synchronized void reset() {
        attr = 0;
        row = col = 0;
        pendingWrap = false;
        scrollTop = 0;
        scrollBottom = rows - 1;
        appCursorKeys = false;
        appKeypad = false;
        autoWrap = true;
        originMode = false;
        cursorVisible = true;
        bracketedPaste = false;
        insertMode = false;
        mouseMode = 0;
        mouseSgr = false;
        cursorStyle = 0;
        Arrays.fill(charset, 0);
        gl = 0;
        initTabs();
        if (altActive) { altActive = false; screen = main; }
        for (int i = 0; i < rows; i++) { main[i] = new Line(cols, 0); alt[i] = new Line(cols, 0); }
        savedRow = savedCol = 0;
        savedAttr = 0;
        state = S_GROUND;
        lastChar = 0;
    }

    private void softReset() {
        attr = 0;
        scrollTop = 0;
        scrollBottom = rows - 1;
        appCursorKeys = false;
        autoWrap = true;
        originMode = false;
        cursorVisible = true;
        insertMode = false;
        Arrays.fill(charset, 0);
        gl = 0;
    }

    private void initTabs() {
        tabs = new boolean[cols];
        for (int i = 8; i < cols; i += 8) tabs[i] = true;
    }

    public synchronized void resize(int newCols, int newRows) {
        newCols = Math.max(2, newCols);
        newRows = Math.max(1, newRows);
        if (newCols == cols && newRows == rows) return;

        // main screen: keep the cursor line visible by pushing top lines into scrollback,
        // or pull lines back from scrollback when the screen grows
        Line[] m = main;
        int cursorOnMain = altActive ? altSavedRow : row;
        if (newRows < rows) {
            int push = Math.max(0, cursorOnMain - (newRows - 1));
            for (int i = 0; i < push; i++) addScrollback(m[i]);
            Line[] n = new Line[newRows];
            for (int i = 0; i < newRows; i++) n[i] = m[Math.min(rows - 1, i + push)];
            m = n;
            cursorOnMain -= push;
        } else if (newRows > rows) {
            int pull = Math.min(scrollback.size(), newRows - rows);
            Line[] n = new Line[newRows];
            int base = scrollback.size() - pull;
            for (int i = 0; i < pull; i++) n[i] = scrollback.get(base + i);
            scrollback.subList(base, scrollback.size()).clear();
            for (int i = 0; i < rows; i++) n[pull + i] = m[i];
            for (int i = pull + rows; i < newRows; i++) n[i] = new Line(cols, 0);
            m = n;
            cursorOnMain += pull;
        }
        for (int i = 0; i < m.length; i++) if (m[i].ch.length != newCols) m[i] = m[i].resized(newCols);
        for (int i = 0; i < scrollback.size(); i++) if (scrollback.get(i).ch.length != newCols) scrollback.set(i, scrollback.get(i).resized(newCols));

        Line[] a = new Line[newRows];
        for (int i = 0; i < newRows; i++) a[i] = i < rows ? alt[i].resized(newCols) : new Line(newCols, 0);

        main = m;
        alt = a;
        cols = newCols;
        rows = newRows;
        screen = altActive ? alt : main;
        if (altActive) altSavedRow = clamp(cursorOnMain, 0, rows - 1); else row = clamp(cursorOnMain, 0, rows - 1);
        if (altActive) row = clamp(row, 0, rows - 1);
        col = Math.min(col, cols - 1);
        altSavedCol = Math.min(altSavedCol, cols - 1);
        pendingWrap = false;
        scrollTop = 0;
        scrollBottom = rows - 1;
        initTabs();
        savedRow = clamp(savedRow, 0, rows - 1);
        savedCol = Math.min(savedCol, cols - 1);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // ---- input -------------------------------------------------------------------------------------------
    public synchronized void feed(char[] buf, int off, int len) {
        for (int i = off; i < off + len; i++) {
            char c = buf[i];
            if (pendingHigh != 0) {
                if (Character.isLowSurrogate(c)) { processCp(Character.toCodePoint(pendingHigh, c)); pendingHigh = 0; continue; }
                pendingHigh = 0;
            }
            if (Character.isHighSurrogate(c)) { pendingHigh = c; continue; }
            processCp(c);
        }
    }

    public void feed(String s) {
        char[] b = s.toCharArray();
        feed(b, 0, b.length);
    }

    private void processCp(int cp) {
        if (cp == 0x18 || cp == 0x1A) { state = S_GROUND; return; }

        if (state == S_OSC || state == S_STR) {
            if (cp == 0x07 && state == S_OSC) { finishOsc(); state = S_GROUND; return; }
            if (strEsc) {
                strEsc = false;
                if (cp == '\\') { if (state == S_OSC) finishOsc(); state = S_GROUND; return; }
                state = S_ESC;
                processCp(cp);
                return;
            }
            if (cp == 0x1B) { strEsc = true; return; }
            if (state == S_OSC && seq.length() < 4096) seq.appendCodePoint(cp);
            return;
        }

        if (cp < 0x20) {
            if (cp == 0x1B) { state = S_ESC; return; }
            control(cp);
            return;
        }

        switch (state) {
            case S_GROUND:
                if (cp == 0x7F || (cp >= 0x80 && cp < 0xA0)) return;
                put(cp);
                break;
            case S_ESC:
                esc(cp);
                break;
            case S_CSI:
                if (cp >= 0x40 && cp <= 0x7E) {
                    state = S_GROUND;
                    csi((char) cp);
                } else if (cp >= 0x20 && cp <= 0x3F) {
                    if (seq.length() < 256) seq.append((char) cp); else state = S_GROUND;
                } else {
                    state = S_GROUND;
                }
                break;
            case S_CHARSET:
                if (charsetTarget >= 0 && charsetTarget < 4) charset[charsetTarget] = cp == '0' ? 1 : 0;
                state = S_GROUND;
                break;
            case S_HASH:
                if (cp == '8') {
                    for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) { screen[r].ch[c] = 'E'; screen[r].at[c] = 0; }
                }
                state = S_GROUND;
                break;
            case S_ESC_PCT:
                state = S_GROUND;
                break;
            default:
                state = S_GROUND;
        }
    }

    private void control(int cp) {
        switch (cp) {
            case 0x07: listener.onBell(); break;
            case 0x08: if (col > 0) col--; pendingWrap = false; break;
            case 0x09: tab(1); break;
            case 0x0A: case 0x0B: case 0x0C: lineFeed(); break;
            case 0x0D: col = 0; pendingWrap = false; break;
            case 0x0E: gl = 1; break;
            case 0x0F: gl = 0; break;
            default: break;
        }
    }

    private void esc(int cp) {
        state = S_GROUND;
        switch (cp) {
            case '[': state = S_CSI; seq.setLength(0); break;
            case ']': state = S_OSC; seq.setLength(0); strEsc = false; break;
            case 'P': case 'X': case '^': case '_': state = S_STR; strEsc = false; break;
            case '(': state = S_CHARSET; charsetTarget = 0; break;
            case ')': state = S_CHARSET; charsetTarget = 1; break;
            case '*': state = S_CHARSET; charsetTarget = 2; break;
            case '+': state = S_CHARSET; charsetTarget = 3; break;
            case '#': state = S_HASH; break;
            case '%': state = S_ESC_PCT; break;
            case '7': saveCursor(); break;
            case '8': restoreCursor(); break;
            case 'D': lineFeed(); break;
            case 'E': col = 0; pendingWrap = false; lineFeed(); break;
            case 'M': reverseIndex(); break;
            case 'H': if (col < tabs.length) tabs[col] = true; break;
            case 'c': reset(); break;
            case 'Z': listener.write("\u001b[?64;1;2;6;9;15;18;21;22c"); break;
            case '=': appKeypad = true; break;
            case '>': appKeypad = false; break;
            default: break;
        }
    }

    // ---- printing --------------------------------------------------------------------------------------------
    private static final char[] LINE_DRAW = {
            // 0x5F..0x7E in DEC special graphics
            ' ', '◆', '▒', '␉', '␌', '␍', '␊', '°', '±', '␤',
            '␋', '┘', '┐', '┌', '└', '┼', '⎺', '⎻', '─',
            '⎼', '⎽', '├', '┤', '┴', '┬', '│', '≤', '≥',
            'π', '≠', '£', '·'};

    static int width(int cp) {
        if (cp < 0x300) return 1;
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x200E || cp == 0x200F || cp == 0x2060 || cp == 0xFEFF) return 0;
        if (cp >= 0xFE00 && cp <= 0xFE0F) return 0;
        int t = Character.getType(cp);
        if (t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.COMBINING_SPACING_MARK && cp < 0x1000) return 0;
        if ((cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE6F) || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x1F300 && cp <= 0x1F64F)
                || (cp >= 0x1F900 && cp <= 0x1F9FF) || (cp >= 0x20000 && cp <= 0x3FFFD)) return 2;
        return 1;
    }

    private void put(int cp) {
        int set = charset[gl];
        if (set == 1 && cp >= 0x5F && cp <= 0x7E) cp = LINE_DRAW[cp - 0x5F];
        int w = width(cp);
        if (w == 0) return;

        if (pendingWrap) {
            pendingWrap = false;
            if (autoWrap) {
                screen[row].wrapped = true;
                col = 0;
                lineFeed();
            }
        }
        if (w == 2 && col == cols - 1) {
            if (!autoWrap) return;
            Line l0 = screen[row];
            l0.ch[col] = 0;
            l0.at[col] = attr & BG_MASK;
            l0.wrapped = true;
            col = 0;
            lineFeed();
        }
        if (insertMode) insertChars(w);

        Line l = screen[row];
        // overwriting half of a wide character: blank the other half
        if (l.ch[col] == WIDE_TAIL && col > 0) l.ch[col - 1] = 0;
        if (col + 1 < cols && l.ch[col] != 0 && l.ch[col] != WIDE_TAIL && l.ch[col + 1] == WIDE_TAIL) l.ch[col + 1] = 0;
        l.ch[col] = cp;
        l.at[col] = attr;
        if (w == 2 && col + 1 < cols) {
            if (col + 2 < cols && l.ch[col + 2] == WIDE_TAIL) l.ch[col + 2] = 0;
            l.ch[col + 1] = WIDE_TAIL;
            l.at[col + 1] = attr;
        }
        lastChar = cp;
        col += w;
        if (col >= cols) { col = cols - 1; pendingWrap = true; }
    }

    // ---- cursor / scrolling -----------------------------------------------------------------------------------------
    private void lineFeed() {
        if (row == scrollBottom) scrollUp(1);
        else if (row < rows - 1) row++;
    }

    private void reverseIndex() {
        if (row == scrollTop) scrollDown(1);
        else if (row > 0) row--;
    }

    private void addScrollback(Line l) {
        if (maxScrollback <= 0) return;
        scrollback.add(l);
        if (scrollback.size() > maxScrollback + 200) {
            scrollback.subList(0, scrollback.size() - maxScrollback).clear();
        }
    }

    private void scrollUp(int n) {
        n = Math.min(n, scrollBottom - scrollTop + 1);
        boolean keep = !altActive && scrollTop == 0 && scrollBottom == rows - 1;
        for (int i = 0; i < n; i++) {
            Line removed = screen[scrollTop];
            System.arraycopy(screen, scrollTop + 1, screen, scrollTop, scrollBottom - scrollTop);
            screen[scrollBottom] = new Line(cols, attr & BG_MASK);
            if (keep) addScrollback(removed);
        }
    }

    private void scrollDown(int n) {
        n = Math.min(n, scrollBottom - scrollTop + 1);
        for (int i = 0; i < n; i++) {
            System.arraycopy(screen, scrollTop, screen, scrollTop + 1, scrollBottom - scrollTop);
            screen[scrollTop] = new Line(cols, attr & BG_MASK);
        }
    }

    private void tab(int n) {
        for (int k = 0; k < n; k++) {
            int c = col + 1;
            while (c < cols - 1 && !tabs[c]) c++;
            col = Math.min(c, cols - 1);
        }
        pendingWrap = false;
    }

    private void backTab(int n) {
        for (int k = 0; k < n; k++) {
            int c = col - 1;
            while (c > 0 && !tabs[c]) c--;
            col = Math.max(c, 0);
        }
        pendingWrap = false;
    }

    private void moveTo(int r, int c) {
        // r, c are 0-based, already origin-adjusted by the caller where needed
        int top = originMode ? scrollTop : 0;
        int bottom = originMode ? scrollBottom : rows - 1;
        row = clamp(r, top, bottom);
        col = clamp(c, 0, cols - 1);
        pendingWrap = false;
    }

    private void saveCursor() {
        savedRow = row; savedCol = col; savedAttr = attr; savedWrap = pendingWrap; savedOrigin = originMode;
        savedGl = gl;
        System.arraycopy(charset, 0, savedCharset, 0, 4);
    }

    private void restoreCursor() {
        row = clamp(savedRow, 0, rows - 1);
        col = clamp(savedCol, 0, cols - 1);
        attr = savedAttr;
        pendingWrap = savedWrap;
        originMode = savedOrigin;
        gl = savedGl;
        System.arraycopy(savedCharset, 0, charset, 0, 4);
    }

    private void switchAlt(boolean on, boolean saveRestoreCursor, boolean clear) {
        if (on == altActive) return;
        if (on) {
            if (saveRestoreCursor) { saveCursor(); }
            altSavedRow = row; altSavedCol = col;
            altActive = true;
            screen = alt;
            if (clear) for (int i = 0; i < rows; i++) alt[i] = new Line(cols, 0);
        } else {
            if (clear) for (int i = 0; i < rows; i++) alt[i] = new Line(cols, 0);
            altActive = false;
            screen = main;
            if (saveRestoreCursor) restoreCursor();
            else { row = clamp(altSavedRow, 0, rows - 1); col = clamp(altSavedCol, 0, cols - 1); }
            pendingWrap = false;
        }
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    // ---- erase / insert / delete ---------------------------------------------------------------------------------------------
    private void eraseCells(int r, int from, int to) {   // [from, to)
        Line l = screen[r];
        from = Math.max(0, from);
        to = Math.min(cols, to);
        long fill = attr & BG_MASK;
        for (int c = from; c < to; c++) { l.ch[c] = 0; l.at[c] = fill; }
        if (to >= cols) l.wrapped = false;
        // do not leave half a wide char behind
        if (from > 0 && l.ch[from] == WIDE_TAIL) l.ch[from - 1] = 0;
        if (to < cols && l.ch[to] == WIDE_TAIL) l.ch[to] = 0;
    }

    private void insertChars(int n) {
        Line l = screen[row];
        n = Math.min(n, cols - col);
        System.arraycopy(l.ch, col, l.ch, col + n, cols - col - n);
        System.arraycopy(l.at, col, l.at, col + n, cols - col - n);
        long fill = attr & BG_MASK;
        for (int c = col; c < col + n; c++) { l.ch[c] = 0; l.at[c] = fill; }
    }

    private void deleteChars(int n) {
        Line l = screen[row];
        n = Math.min(n, cols - col);
        System.arraycopy(l.ch, col + n, l.ch, col, cols - col - n);
        System.arraycopy(l.at, col + n, l.at, col, cols - col - n);
        long fill = attr & BG_MASK;
        for (int c = cols - n; c < cols; c++) { l.ch[c] = 0; l.at[c] = fill; }
        if (col < cols && l.ch[col] == WIDE_TAIL) l.ch[col] = 0;
    }

    private void insertLines(int n) {
        if (row < scrollTop || row > scrollBottom) return;
        n = Math.min(n, scrollBottom - row + 1);
        for (int i = 0; i < n; i++) {
            System.arraycopy(screen, row, screen, row + 1, scrollBottom - row);
            screen[row] = new Line(cols, attr & BG_MASK);
        }
        col = 0;
        pendingWrap = false;
    }

    private void deleteLines(int n) {
        if (row < scrollTop || row > scrollBottom) return;
        n = Math.min(n, scrollBottom - row + 1);
        for (int i = 0; i < n; i++) {
            System.arraycopy(screen, row + 1, screen, row, scrollBottom - row);
            screen[scrollBottom] = new Line(cols, attr & BG_MASK);
        }
        col = 0;
        pendingWrap = false;
    }

    // ---- CSI -------------------------------------------------------------------------------------------------------------------
    private static int[] parseParams(String s) {
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split(";", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            int colon = p.indexOf(':');
            if (colon >= 0) p = p.substring(0, colon);
            int v = -1;
            if (!p.isEmpty()) {
                try { v = Integer.parseInt(p); } catch (NumberFormatException e) { v = -1; }
            }
            out[i] = v;
        }
        return out;
    }

    private static int arg(int[] p, int i, int def) { return i < p.length && p[i] >= 0 ? p[i] : def; }
    private static int cnt(int[] p, int i) { return Math.max(1, arg(p, i, 1)); }

    private void csi(char f) {
        String s = seq.toString();
        int i = 0;
        char priv = 0;
        if (!s.isEmpty() && "<=>?".indexOf(s.charAt(0)) >= 0) { priv = s.charAt(0); i = 1; }
        int pe = i;
        while (pe < s.length() && s.charAt(pe) >= 0x30) pe++;
        String params = s.substring(i, pe);
        String inter = s.substring(pe);
        int[] p = parseParams(params);

        if (!inter.isEmpty()) {
            if (inter.equals(" ") && f == 'q') cursorStyle = arg(p, 0, 0);
            else if (inter.equals("!") && f == 'p') softReset();
            return;
        }
        if (priv == '?') {
            if (f == 'h' || f == 'l') for (int v : p) decMode(v, f == 'h');
            return;
        }
        if (priv == '>') {
            if (f == 'c') listener.write("\u001b[>1;10;0c");
            return;
        }
        if (priv != 0) return;

        switch (f) {
            case 'A': moveTo(row - cnt(p, 0), col); break;
            case 'B': case 'e': moveTo(row + cnt(p, 0), col); break;
            case 'C': case 'a': moveTo(row, col + cnt(p, 0)); break;
            case 'D': moveTo(row, col - cnt(p, 0)); break;
            case 'E': moveTo(row + cnt(p, 0), 0); break;
            case 'F': moveTo(row - cnt(p, 0), 0); break;
            case 'G': case '`': moveTo(row, cnt(p, 0) - 1); break;
            case 'd': {
                int r = cnt(p, 0) - 1 + (originMode ? scrollTop : 0);
                moveTo(r, col);
                break;
            }
            case 'H': case 'f': {
                int r = cnt(p, 0) - 1 + (originMode ? scrollTop : 0);
                moveTo(r, cnt(p, 1) - 1);
                break;
            }
            case 'I': tab(cnt(p, 0)); break;
            case 'Z': backTab(cnt(p, 0)); break;
            case 'J': {
                int m = arg(p, 0, 0);
                if (m == 0) {
                    eraseCells(row, col, cols);
                    for (int r = row + 1; r < rows; r++) eraseCells(r, 0, cols);
                } else if (m == 1) {
                    for (int r = 0; r < row; r++) eraseCells(r, 0, cols);
                    eraseCells(row, 0, col + 1);
                } else if (m == 2) {
                    for (int r = 0; r < rows; r++) eraseCells(r, 0, cols);
                } else if (m == 3) {
                    scrollback.clear();
                }
                pendingWrap = false;
                break;
            }
            case 'K': {
                int m = arg(p, 0, 0);
                if (m == 0) eraseCells(row, col, cols);
                else if (m == 1) eraseCells(row, 0, col + 1);
                else if (m == 2) eraseCells(row, 0, cols);
                break;
            }
            case 'L': insertLines(cnt(p, 0)); break;
            case 'M': deleteLines(cnt(p, 0)); break;
            case 'P': deleteChars(cnt(p, 0)); pendingWrap = false; break;
            case '@': insertChars(cnt(p, 0)); pendingWrap = false; break;
            case 'X': eraseCells(row, col, col + cnt(p, 0)); break;
            case 'S': scrollUp(cnt(p, 0)); break;
            case 'T': scrollDown(cnt(p, 0)); break;
            case 'b': {
                int n = Math.min(cnt(p, 0), 4096);
                if (lastChar != 0) for (int k = 0; k < n; k++) put(lastChar);
                break;
            }
            case 'g': {
                int m = arg(p, 0, 0);
                if (m == 0) { if (col < tabs.length) tabs[col] = false; }
                else if (m == 3) Arrays.fill(tabs, false);
                break;
            }
            case 'h': case 'l':
                for (int v : p) {
                    if (v == 4) insertMode = f == 'h';
                }
                break;
            case 'm': sgr(params); break;
            case 'n': {
                int m = arg(p, 0, 0);
                if (m == 5) listener.write("\u001b[0n");
                else if (m == 6) {
                    int r = row - (originMode ? scrollTop : 0) + 1;
                    listener.write("\u001b[" + r + ";" + (getCursorCol() + 1) + "R");
                }
                break;
            }
            case 'c': if (arg(p, 0, 0) == 0) listener.write("\u001b[?64;1;2;6;9;15;18;21;22c"); break;
            case 'r': {
                int t = arg(p, 0, 1), b = arg(p, 1, rows);
                if (t < 1) t = 1;
                if (b > rows || b < 1) b = rows;
                if (t < b) {
                    scrollTop = t - 1;
                    scrollBottom = b - 1;
                    moveTo(originMode ? scrollTop : 0, 0);
                }
                break;
            }
            case 's': saveCursor(); break;
            case 'u': restoreCursor(); break;
            case 't': if (arg(p, 0, 0) == 18) listener.write("\u001b[8;" + rows + ";" + cols + "t"); break;
            default: break;
        }
    }

    private void decMode(int mode, boolean on) {
        switch (mode) {
            case 1: appCursorKeys = on; break;
            case 6: originMode = on; moveTo(on ? scrollTop : 0, 0); break;
            case 7: autoWrap = on; break;
            case 25: cursorVisible = on; break;
            case 47: case 1047: switchAlt(on, false, mode == 1047 && !on); break;
            case 1048: if (on) saveCursor(); else restoreCursor(); break;
            case 1049: switchAlt(on, true, on); break;
            case 2004: bracketedPaste = on; break;
            case 1000: case 1002: case 1003: mouseMode = on ? mode : 0; break;
            case 1006: mouseSgr = on; break;
            default: break;
        }
    }

    // ---- SGR ------------------------------------------------------------------------------------------------------------------------
    private void sgr(String params) {
        if (params.isEmpty()) { attr = 0; return; }
        String[] parts = params.split(";", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.indexOf(':') >= 0) { sgrColon(part); continue; }
            int c = 0;
            if (!part.isEmpty()) {
                try { c = Integer.parseInt(part); } catch (NumberFormatException e) { continue; }
            }
            switch (c) {
                case 0: attr = 0; break;
                case 1: attr |= BOLD; break;
                case 2: attr |= DIM; break;
                case 3: attr |= ITALIC; break;
                case 4: case 21: attr |= UNDERLINE; break;
                case 5: case 6: attr |= BLINK; break;
                case 7: attr |= INVERSE; break;
                case 8: attr |= INVISIBLE; break;
                case 9: attr |= STRIKE; break;
                case 22: attr &= ~(BOLD | DIM); break;
                case 23: attr &= ~ITALIC; break;
                case 24: attr &= ~UNDERLINE; break;
                case 25: attr &= ~BLINK; break;
                case 27: attr &= ~INVERSE; break;
                case 28: attr &= ~INVISIBLE; break;
                case 29: attr &= ~STRIKE; break;
                case 39: attr &= ~(FG_SET | (RGB << FG_SHIFT)); break;
                case 49: attr &= ~BG_MASK; break;
                case 38: case 48: {
                    boolean isFg = c == 38;
                    if (i + 1 >= parts.length) break;
                    String mode = parts[i + 1];
                    if (mode.equals("5") && i + 2 < parts.length) {
                        int idx = num(parts[i + 2]);
                        attr = isFg ? withFg(attr, palette(idx)) : withBg(attr, palette(idx));
                        i += 2;
                    } else if (mode.equals("2") && i + 4 < parts.length) {
                        int rgb = (clamp(num(parts[i + 2]), 0, 255) << 16) | (clamp(num(parts[i + 3]), 0, 255) << 8) | clamp(num(parts[i + 4]), 0, 255);
                        attr = isFg ? withFg(attr, rgb) : withBg(attr, rgb);
                        i += 4;
                    } else {
                        i = parts.length;
                    }
                    break;
                }
                default:
                    if (c >= 30 && c <= 37) attr = withFg(attr, palette(c - 30));
                    else if (c >= 40 && c <= 47) attr = withBg(attr, palette(c - 40));
                    else if (c >= 90 && c <= 97) attr = withFg(attr, palette(c - 90 + 8));
                    else if (c >= 100 && c <= 107) attr = withBg(attr, palette(c - 100 + 8));
            }
        }
    }

    private static int num(String s) {
        try { return s.isEmpty() ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Colon form: 38:2::r:g:b, 38:2:r:g:b, 38:5:n, 4:3. */
    private void sgrColon(String part) {
        String[] t = part.split(":", -1);
        int c = num(t[0]);
        if (c == 4) {
            if (t.length > 1 && num(t[1]) == 0) attr &= ~UNDERLINE; else attr |= UNDERLINE;
        } else if (c == 38 || c == 48) {
            boolean isFg = c == 38;
            if (t.length >= 3 && t[1].equals("5")) {
                int idx = num(t[2]);
                attr = isFg ? withFg(attr, palette(idx)) : withBg(attr, palette(idx));
            } else if (t.length >= 5 && t[1].equals("2")) {
                int o = t.length >= 6 ? 3 : 2;
                int rgb = (clamp(num(t[o]), 0, 255) << 16) | (clamp(num(t[o + 1]), 0, 255) << 8) | clamp(num(t[o + 2]), 0, 255);
                attr = isFg ? withFg(attr, rgb) : withBg(attr, rgb);
            }
        }
    }

    // ---- OSC ---------------------------------------------------------------------------------------------------------------------------------
    private void finishOsc() {
        String s = seq.toString();
        int semi = s.indexOf(';');
        if (semi < 0) return;
        int code = num(s.substring(0, semi));
        if (code == 0 || code == 2) {
            title = s.substring(semi + 1);
            listener.onTitle(title);
        }
    }
}
