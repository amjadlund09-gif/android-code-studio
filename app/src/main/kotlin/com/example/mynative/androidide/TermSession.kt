package com.example.mynative.androidide

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.mynative.androidide.term.TerminalEmulator
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * One terminal tab: a PTY child process (bash inside the Termux userland, or Android's sh as a
 * fallback) + the emulator that holds its screen. Lives in [TermSessions], so it survives the
 * activity being closed and re-opened.
 */
class TermSession(private val ctx: Context, val id: Int) {

    interface Callback {
        fun onUpdate(s: TermSession)
        fun onTitle(s: TermSession)
        fun onExit(s: TermSession)
        fun onBell(s: TermSession)
    }

    @Volatile var callback: Callback? = null
    @Volatile var title: String = "Session $id"
    @Volatile var finished = false
        private set
    @Volatile var exitCode = 0
        private set
    var basic = false
        private set

    private var pty: Pty? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val emulator = TerminalEmulator(80, 24, 5000, object : TerminalEmulator.Listener {
        override fun write(reply: String) { this@TermSession.write(reply) }
        override fun onTitle(title: String) {
            if (title.isNotBlank()) this@TermSession.title = title
            callback?.onTitle(this@TermSession)
        }
        override fun onBell() { callback?.onBell(this@TermSession) }
    })

    fun start(rows: Int, cols: Int, basicShell: Boolean) {
        basic = basicShell
        emulator.resize(cols, rows)
        val launch = if (basicShell) TermuxEnv.basicLaunch(ctx) else TermuxEnv.launch(ctx, TermuxEnv.shellArgv())
        val env = HashMap(launch.env)
        env["COLUMNS"] = cols.toString()
        env["LINES"] = rows.toString()
        val p = Pty.start(launch.cmd, launch.args, env, launch.cwd, rows, cols)
        if (p == null) {
            emulator.feed("Cannot open a terminal device (native library missing).\r\n")
            finish(-1)
            return
        }
        pty = p
        Thread({ readLoop(p) }, "term-$id").start()
    }

    private fun readLoop(p: Pty) {
        val reader = InputStreamReader(p.input, Charsets.UTF_8)
        val buf = CharArray(8192)
        try {
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                emulator.feed(buf, 0, n)
                callback?.onUpdate(this)
            }
        } catch (_: Exception) { /* EIO when the child exits */ }
        val code = p.waitFor()
        p.close()
        emulator.feed("\r\n[Process completed (code $code) - press Enter to close]\r\n")
        finish(code)
    }

    private fun finish(code: Int) {
        exitCode = code
        finished = true
        callback?.onUpdate(this)
        main.post { callback?.onExit(this) }
    }

    /** Text typed / pasted / replied by the emulator. Written on a background thread (a big paste can block). */
    fun write(s: String) {
        if (finished) return
        val b = s.toByteArray(Charsets.UTF_8)
        try { writer.execute { pty?.write(b) } } catch (_: Exception) { }
    }

    fun resize(rows: Int, cols: Int) {
        if (rows < 1 || cols < 2) return
        emulator.resize(cols, rows)
        pty?.resize(rows, cols)
    }

    /** Hang up the shell (SIGHUP), and kill it if it is still there a moment later. */
    fun close() {
        val p = pty
        if (p != null && !finished) {
            p.kill(1)
            main.postDelayed({ if (!finished) p.kill(9) }, 600)
        }
        callback = null
        try { writer.shutdown() } catch (_: Exception) { }
    }
}

/** All open terminal tabs of the app (process wide). */
object TermSessions {
    val list = CopyOnWriteArrayList<TermSession>()
    private var counter = 0

    @Synchronized
    fun create(ctx: Context, rows: Int, cols: Int, basic: Boolean): TermSession {
        val s = TermSession(ctx.applicationContext, ++counter)
        list.add(s)
        s.start(rows, cols, basic)
        TermService.sync(ctx)
        return s
    }

    fun remove(ctx: Context, s: TermSession) {
        s.close()
        list.remove(s)
        TermService.sync(ctx)
    }

    fun killAll(ctx: Context) {
        list.forEach { it.close() }
        list.clear()
        TermService.sync(ctx)
    }
}
