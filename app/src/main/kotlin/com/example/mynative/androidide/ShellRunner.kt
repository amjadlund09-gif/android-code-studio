package com.example.mynative.androidide

import android.content.Context
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/** Runs a real shell command and streams its output as a terminal-like text. */
class ShellRunner(private val context: Context) {

    @Volatile private var process: Process? = null
    private val stopped = AtomicBoolean(false)

    /** true = Fast Mode: the warm Gradle daemon must survive Stop (only the client is killed). */
    @Volatile var keepDaemon = false

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun start(
        command: String,
        onOutput: (String) -> Unit,
        onExit: (Int, Boolean) -> Unit,     // exit code, was it stopped by the user
        echo: String? = null                // shown as the first line instead of the (long) real command
    ) {
        Thread {
            val term = TerminalBuffer()
            term.feed("~ $ ${echo ?: command}\n")
            onOutput(term.text())
            var code = -1
            try {
                TermEnvironment.prepare(context)
                val home = TermEnvironment.home(context)
                val pb = ProcessBuilder(TermEnvironment.shellPath(context), "-c", command)
                    .directory(home)
                    .redirectErrorStream(true)
                TermEnvironment.applyTo(context, pb.environment())
                val p = pb.start()
                process = p

                val reader = InputStreamReader(p.inputStream, Charsets.UTF_8)
                val buf = CharArray(4096)
                var last = 0L
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    term.feed(ansi.replace(String(buf, 0, n), ""))
                    val now = System.currentTimeMillis()
                    if (now - last > 80) { last = now; onOutput(term.text()) }
                }
                code = p.waitFor()
            } catch (e: Exception) {
                term.feed("\n${e.javaClass.simpleName}: ${e.message}\n")
            }
            if (code == 127) term.feed("\n[exit 127] Command not found.\n")
            onOutput(term.text())
            onExit(code, stopped.get())
        }.start()
    }

    /**
     * Stop = kill the shell AND everything it started (gradlew -> java). Plain destroy() only
     * killed `sh`, so the Gradle JVM kept building in the background.
     */
    fun stop() {
        stopped.set(true)
        val p = process ?: return
        Thread {
            val root = pidOf(p) ?: android.os.Process.myPid()
            val victims = descendants(root, onlyThisRoot = pidOf(p) != null)
            victims.asReversed().forEach { runCatching { android.os.Process.killProcess(it) } }
            p.destroyForcibly()
        }.start()
    }

    private fun pidOf(p: Process): Int? = runCatching {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    }.getOrNull()

    private fun cmdline(pid: Int): String =
        runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrDefault("")

    /** All child / grand-child pids of [root] (including root itself when it is a known pid). */
    private fun descendants(root: Int, onlyThisRoot: Boolean): List<Int> {
        val kids = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val ppid = runCatching {
                val s = File(d, "stat").readText()
                s.substring(s.lastIndexOf(')') + 2).split(' ')[1].toInt()
            }.getOrNull() ?: return@forEach
            kids.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val out = ArrayList<Int>()
        if (onlyThisRoot) out.add(root)
        var i = 0
        val queue = ArrayList<Int>().apply { add(root) }
        while (i < queue.size) {
            val parent = queue[i++]
            for (child in kids[parent].orEmpty()) {
                val cmd = cmdline(child)
                // fallback mode (pid unknown): do not touch logcat
                if (!onlyThisRoot && parent == root && "logcat" in cmd) continue
                // Fast Mode: leave the warm daemon alone, killing the client cancels the build
                if (keepDaemon && "GradleDaemon" in cmd) continue
                out.add(child); queue.add(child)
            }
        }
        return out
    }

    companion object {
        /**
         * Runs a shell command in the background (same environment as the terminal, no UI)
         * and waits for it. Returns the exit code, or -1 if it could not be started.
         */
        fun runSilently(context: Context, command: String, dir: java.io.File): Int =
            try {
                TermEnvironment.prepare(context)
                val pb = ProcessBuilder(TermEnvironment.shellPath(context), "-c", command)
                    .directory(dir)
                    .redirectErrorStream(true)
                TermEnvironment.applyTo(context, pb.environment())
                val p = pb.start()
                p.inputStream.use { it.readBytes() }      // drain output so the process never blocks
                p.waitFor()
            } catch (e: Exception) {
                -1
            }
    }
}
