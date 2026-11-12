package com.example.mynative.androidide

import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.Os
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * First-run setup of the real Termux userland (see TermuxEnv):
 *  1. downloads the official Termux bootstrap zip (bash, coreutils, apt, dpkg, ... + package database)
 *  2. unpacks it into <files>/termux/usr
 *  3. installs proot (from the Termux repo, with the existing mini-apt) - it maps the Termux paths
 *  4. self-test: runs `bash` through proot, retries without seccomp if the phone needs it
 *  5. runs the bootstrap's "second stage" script if this bootstrap version has one
 *
 * Every step leaves a marker, so Retry continues where it stopped (downloads resume too).
 */
class TermuxBootstrap(private val ctx: Context) {

    private val root = TermuxEnv.root(ctx)
    private val prefix = TermuxEnv.prefix(ctx)
    private val http = JdkInstaller(ctx, prefix)

    private fun mark(name: String) = File(root, name)

    fun install(log: TaskLog, cancelled: () -> Boolean) {
        if (Build.VERSION.SDK_INT < 24) {
            throw IllegalStateException("Termux packages need Android 7.0 or newer (this phone: Android ${Build.VERSION.RELEASE}).")
        }
        TermEnvironment.prepare(ctx)
        root.mkdirs()
        val arch = http.termuxArch()
        log.line("~ \$ setup termux ($arch)")
        log.line("Install path: ${prefix.path}")

        val free = root.usableSpace
        if (free < 450L * 1024 * 1024) {
            throw IllegalStateException("Not enough free space: need about 450 MB, have ${free / 1048576} MB.")
        }

        // ---- 1 + 2: bootstrap ------------------------------------------------------------------------------
        if (!mark(".extracted").isFile) {
            val dl = File(root, "dl").apply { mkdirs() }
            val zip = File(dl, "bootstrap-$arch.zip")
            log.line("")
            log.line("[1/4] Termux bootstrap")
            // Offline first: a zip inside the APK (assets/termux/) or one dropped into /sdcard/AndroidIDE/ or Download/
            val offline = if (isValidZip(zip)) null else offlineSource(arch)
            if (offline != null) {
                log.line("Using offline bootstrap: ${offline.label}")
                copyOffline(offline, zip, log, cancelled)
            } else {
                val url = findBootstrapUrl(arch, log)
                log.line(url.substringAfterLast("download/").replace("%2B", "+"))
                http.download(url, zip, 30L * 1024 * 1024, log, cancelled)
            }
            if (cancelled()) throw InstallCancelled()
            if (!isValidZip(zip)) {
                zip.delete()
                throw IllegalStateException("The downloaded bootstrap is damaged. Tap Retry to download it again.")
            }
            log.line("Unpacking...")
            val staging = File(root, "usr-staging")
            extract(zip, staging, log, cancelled)
            prefix.deleteRecursively()
            if (!staging.renameTo(prefix)) throw IllegalStateException("Could not move the unpacked files into place.")
            mark(".extracted").writeText("1")
            zip.delete()
        }
        listOf("tmp", "var/tmp", "var/cache/apt/archives/partial", "var/lib/apt/lists/partial", "var/log/apt")
            .forEach { File(prefix, it).mkdirs() }

        // ---- 3: proot ------------------------------------------------------------------------------------------------
        if (!mark(".proot").isFile) {
            log.line("")
            log.line("[2/4] proot (maps the Termux paths)")
            val shipped = File(prefix, "bin/proot").isFile && TermuxEnv.findLoader(ctx) != null
            if (shipped) log.line("proot is already part of the bootstrap (no download needed)")
            else http.installPackage("proot", log, cancelled)
            File(prefix, "bin/proot").setExecutable(true, false)
            TermuxEnv.findLoader(ctx)?.setExecutable(true, false)
            File(prefix, "libexec/proot").listFiles()?.forEach { it.setExecutable(true, false) }
            if (!File(prefix, "bin/proot").isFile) throw IllegalStateException("proot did not install (bin/proot missing).")
            if (TermuxEnv.findLoader(ctx) == null) throw IllegalStateException("proot loader not found after install.")
            mark(".proot").writeText("1")
        }

        // ---- 4: self-test ------------------------------------------------------------------------------------------------
        log.line("")
        log.line("[3/4] Testing the environment")
        if (!selfTest(log)) {
            log.line("Retrying without seccomp acceleration...")
            TermuxEnv.setNoSeccomp(ctx, true)
            if (!selfTest(log)) {
                TermuxEnv.setNoSeccomp(ctx, false)
                throw IllegalStateException(
                    "proot could not start bash on this phone. Output above shows the reason. " +
                        "You can still use the basic Android shell."
                )
            }
        }

        // ---- 5: second stage (newer bootstraps) ---------------------------------------------------------------------------
        if (!mark(".second-stage").isFile) {
            log.line("")
            log.line("[4/4] Finishing setup")
            runSecondStage(log)
            mark(".second-stage").writeText("1")
        }

        TermuxEnv.markReady(ctx)
        File(root, "dl").deleteRecursively()
        log.line("")
        log.line("Termux environment ready ✔")
        log.line("Try:  pkg update && pkg install python git nano")
    }

    // ---- offline bootstrap (bundled in the APK or placed on shared storage) ---------------------------------------------

    private class Offline(val label: String, val asset: String?, val file: File?)

    private fun offlineSource(arch: String): Offline? {
        val name = "bootstrap-$arch.zip"
        if (runCatching { ctx.assets.list("termux")?.contains(name) == true }.getOrDefault(false))
            return Offline("built into the app", "termux/$name", null)
        val sd = Environment.getExternalStorageDirectory()
        listOf(File(sd, "AndroidIDE/$name"), File(sd, "Download/$name"))
            .firstOrNull { it.isFile && it.canRead() && it.length() > 1_000_000 }
            ?.let { return Offline(it.path, null, it) }
        return null
    }

    private fun copyOffline(src: Offline, dest: File, log: TaskLog, cancelled: () -> Boolean) {
        dest.parentFile?.mkdirs()
        val input = if (src.asset != null) ctx.assets.open(src.asset) else FileInputStream(src.file!!)
        input.use { ins ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                var last = 0L
                while (true) {
                    if (cancelled()) throw InstallCancelled()
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    val now = System.currentTimeMillis()
                    if (now - last > 250) { last = now; log.progress("  copying ${total / 1048576} MB") }
                }
                log.progress("  copied ${total / 1048576} MB")
                log.line("")
            }
        }
    }

    // ---- download location ----------------------------------------------------------------------------------------------------

    private fun findBootstrapUrl(arch: String, log: TaskLog): String {
        val asset = "bootstrap-$arch.zip"
        try {
            val conn = http.open("https://api.github.com/repos/termux/termux-packages/releases?per_page=40")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until arr.length()) {
                    val rel = arr.getJSONObject(i)
                    if (rel.optBoolean("draft")) continue
                    if (!rel.optString("tag_name").startsWith("bootstrap-")) continue
                    val assets = rel.optJSONArray("assets") ?: continue
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        if (a.optString("name") == asset) return a.getString("browser_download_url")
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            log.line("(release lookup failed: ${e.message}; using the built-in address)")
        }
        return "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.08.30-r1%2Bapt.android-7/$asset"
    }

    private fun isValidZip(f: File): Boolean =
        try { ZipFile(f).use { it.size() > 0 } } catch (e: Exception) { false }

    // ---- unzip ---------------------------------------------------------------------------------------------------------------------------

    private fun looksExecutable(f: File): Boolean {
        return try {
            FileInputStream(f).use { ins ->
                val h = ByteArray(4)
                val n = ins.read(h)
                (n >= 4 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()) ||
                    (n >= 2 && h[0] == '#'.code.toByte() && h[1] == '!'.code.toByte())
            }
        } catch (e: Exception) { false }
    }

    private fun extract(zip: File, dest: File, log: TaskLog, cancelled: () -> Boolean) {
        dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.canonicalPath + File.separator
        val links = ArrayList<Pair<String, String>>()
        var count = 0
        var last = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(zip), 64 * 1024)).use { zin ->
            val buf = ByteArray(64 * 1024)
            var e = zin.nextEntry
            while (e != null) {
                if (cancelled()) throw InstallCancelled()
                val name = e.name
                if (name == "SYMLINKS.txt") {
                    val text = String(zin.readBytes(), Charsets.UTF_8)
                    for (line in text.lineSequence()) {
                        if (line.isBlank()) continue
                        val parts = line.split("←")
                        if (parts.size == 2) links.add(parts[0] to parts[1])
                    }
                } else {
                    val out = File(dest, name)
                    if (!(out.canonicalPath + File.separator).startsWith(destPath)) {
                        throw IllegalStateException("Unsafe path in archive: $name")
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { f ->
                            while (true) {
                                val n = zin.read(buf)
                                if (n < 0) break
                                f.write(buf, 0, n)
                            }
                        }
                        out.setReadable(true, false)
                        if (looksExecutable(out)) out.setExecutable(true, false)
                        count++
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { last = now; log.progress("  $count files") }
                    }
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        for ((target, linkPath) in links) {
            val link = File(dest, linkPath)
            link.parentFile?.mkdirs()
            try { Os.lstat(link.path); link.delete() } catch (_: Exception) { }
            Os.symlink(target, link.path)
        }
        log.progress("  $count files, ${links.size} links")
        log.line("")
    }

    // ---- run something inside the userland -------------------------------------------------------------------------------------

    /** Runs [guestArgv] through proot without a terminal. Returns (exit code, output). */
    private fun runGuest(guestArgv: List<String>, timeoutMs: Long): Pair<Int, String> {
        val l = TermuxEnv.launch(ctx, guestArgv)
        val pb = ProcessBuilder(l.args).directory(l.cwd).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(l.env)
        val p = try { pb.start() } catch (e: Exception) { return -1 to "cannot start ${l.cmd}: ${e.message}" }
        val watchdog = Thread {
            try { Thread.sleep(timeoutMs); p.destroy() } catch (_: InterruptedException) { }
        }
        watchdog.isDaemon = true
        watchdog.start()
        val out = try { p.inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
        val code = p.waitFor()
        watchdog.interrupt()
        return code to out
    }

    private fun selfTest(log: TaskLog): Boolean {
        val (code, out) = runGuest(listOf("${TermuxEnv.GUEST_PREFIX}/bin/bash", "-c", "echo termux-ok; uname -m"), 30_000)
        val ok = code == 0 && out.contains("termux-ok")
        if (ok) log.line("bash OK (${out.lines().firstOrNull { it.isNotBlank() && !it.contains("termux-ok") } ?: ""})")
        else log.line("bash failed (exit $code): ${out.trim().take(600)}")
        return ok
    }

    private fun runSecondStage(log: TaskLog) {
        val etc = File(prefix, "etc/termux")
        val script = etc.takeIf { it.isDirectory }?.walkTopDown()?.firstOrNull { it.isFile && it.name == "termux-bootstrap-second-stage.sh" }
            ?: return
        val guestPath = TermuxEnv.GUEST_PREFIX + "/" + script.relativeTo(prefix).path
        script.setExecutable(true, false)
        val (code, out) = runGuest(listOf("${TermuxEnv.GUEST_PREFIX}/bin/bash", guestPath), 180_000)
        if (code != 0) log.line("second stage exit $code (continuing): ${out.trim().take(400)}")
        else log.line("second stage done")
    }
}
