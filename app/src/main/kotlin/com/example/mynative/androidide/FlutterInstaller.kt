package com.example.mynative.androidide

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Flutter SDK package for the SDK Manager ("flutter" id): lives in <home>/flutter.
 * Install = download an arm64 Flutter SDK build from the newest GitHub release of [DEFAULT_REPO]
 * (change it in prefs "flutter" / "repo"): a .zip / .tar.gz is unpacked into home/flutter, a Termux .deb
 * (what mumumusuc/termux-flutter publishes) into a staging folder and moved to home/flutter. Then it is checked to be a real SDK.
 */
object FlutterInstaller {

    const val ID = "flutter"
    const val DEFAULT_REPO = "mumumusuc/termux-flutter"

    /** Where the SDK lives now: an installed one (home/flutter or $PREFIX/opt/flutter) or the default home/flutter. */
    fun dir(ctx: Context): File = GradleTuner.findFlutterSdk(ctx) ?: File(SdkRepository.homeDir(ctx), "flutter")

    fun isInstalled(ctx: Context) = GradleTuner.isFlutterSdk(dir(ctx))

    fun state(ctx: Context): SdkState {
        val d = dir(ctx)
        return when {
            GradleTuner.isFlutterSdk(d) -> SdkState.INSTALLED
            d.isDirectory && d.list()?.isNotEmpty() == true -> SdkState.INVALID
            else -> SdkState.NOT_INSTALLED
        }
    }

    private fun repo(ctx: Context) =
        ctx.getSharedPreferences("flutter", Context.MODE_PRIVATE).getString("repo", null)?.trim()?.takeIf { it.contains('/') } ?: DEFAULT_REPO

    fun uninstall(ctx: Context, log: TaskLog) {
        for (d in listOf(dir(ctx), File(SdkRepository.homeDir(ctx), "flutter")).distinct()) {
            if (!d.exists()) continue
            log.line("~ \$ rm -rf \"${d.path}\"")
            if (!d.deleteRecursively()) throw IllegalStateException("Could not delete ${d.path}")
        }
        log.line("Flutter uninstalled.")
    }

    private class Asset(val name: String, val url: String, val size: Long)

    private fun isArchive(n: String) = Regex("\\.(zip|tar\\.gz|tgz)$").containsMatchIn(n)

    fun install(ctx: Context, log: TaskLog, cancelled: () -> Boolean) {
        val jdk = JdkInstaller(ctx)
        val arch = jdk.termuxArch()
        if (arch != "aarch64" && arch != "x86_64") throw IllegalStateException("Flutter needs an ARM64 (or x86_64) phone (this one is $arch).")
        val repo = repo(ctx)
        log.line("~ \$ install flutter ($arch)")
        log.line("Looking up the newest build in github.com/$repo ...")
        val c = jdk.open("https://api.github.com/repos/$repo/releases?per_page=10")
        if (c.responseCode != 200) throw IllegalStateException("GitHub answered HTTP ${c.responseCode} (no internet, or the repo has no release)")
        val releases = org.json.JSONArray(c.inputStream.bufferedReader().use { it.readText() })

        val archRx = Regex(if (arch == "aarch64") "(aarch64|arm64)" else "(x86_64|amd64)", RegexOption.IGNORE_CASE)
        var archive: Asset? = null
        var deb: Asset? = null
        val seen = ArrayList<String>()
        for (r in 0 until releases.length()) {
            val assets = releases.getJSONObject(r).optJSONArray("assets") ?: continue
            val list = ArrayList<Asset>()
            for (i in 0 until assets.length()) {
                val o = assets.getJSONObject(i)
                list.add(Asset(o.getString("name"), o.getString("browser_download_url"), o.optLong("size")))
            }
            seen.addAll(list.map { it.name })
            archive = list.filter { archRx.containsMatchIn(it.name) && isArchive(it.name) }.maxByOrNull { it.size }
            deb = list.filter { archRx.containsMatchIn(it.name) && it.name.endsWith(".deb") }.maxByOrNull { it.size }
            if (archive != null || deb != null) break
        }
        if (archive == null && deb == null) throw IllegalStateException(
            "No $arch .zip / .tar.gz / .deb in the latest releases of $repo.\nFound: " +
                (if (seen.isEmpty()) "no files" else seen.distinct().take(12).joinToString()) +
                "\nPut another repo (owner/name) in the app's \"flutter\" prefs or install the SDK by hand into ${dir(ctx).path}."
        )
        if (archive == null) { installDeb(ctx, jdk, deb!!, log, cancelled); return }
        val dest = File(SdkRepository.homeDir(ctx), "flutter")
        val pick = archive
        log.line("Package: ${pick.name} (${pick.size / 1048576} MB)")
        val tmp = File(SdkRepository.homeDir(ctx), "tmp").apply { mkdirs() }
        val file = File(tmp, pick.name)
        if (tmp.usableSpace < pick.size * 3) throw IllegalStateException("Not enough free space (need about ${pick.size * 3 / 1048576} MB).")
        JdkInstaller(ctx).download(pick.url, file, pick.size, log, cancelled)
        if (cancelled()) throw InstallCancelled()

        log.line("Unpacking...")
        val stage = File(SdkRepository.homeDir(ctx), "flutter.partial")
        stage.deleteRecursively(); stage.mkdirs()
        try {
            if (pick.name.endsWith(".zip")) unzip(file, stage, log, cancelled)
            else untar(GZIPInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)), stage, log, cancelled)
            // the archive may hold one top folder ("flutter/")
            val root = if (GradleTuner.isFlutterSdk(stage)) stage
            else stage.listFiles()?.singleOrNull { it.isDirectory }?.takeIf { GradleTuner.isFlutterSdk(it) }
                ?: throw IllegalStateException("The archive is not a Flutter SDK (bin/flutter or packages/flutter_tools/gradle missing).")
            dest.deleteRecursively()
            if (!root.renameTo(dest)) { root.copyRecursively(dest, true); root.deleteRecursively() }
            File(dest, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
            File(dest, "bin/cache/dart-sdk/bin").listFiles()?.forEach { it.setExecutable(true, false) }
        } finally {
            stage.deleteRecursively()
            file.delete()
        }
        log.line("Flutter SDK ready: ${dest.path}")
    }

    /** Termux .deb release: unpack it in a staging prefix, move opt/flutter to home/flutter (visible to the IDE and the terminal), add git/unzip/curl. */
    private fun installDeb(ctx: Context, jdk: JdkInstaller, pick: Asset, log: TaskLog, cancelled: () -> Boolean) {
        TermEnvironment.prepare(ctx)
        val dest = File(SdkRepository.homeDir(ctx), "flutter")
        log.line("Package: ${pick.name} (${pick.size / 1048576} MB, Termux .deb)")
        val tmp = File(SdkRepository.homeDir(ctx), "tmp").apply { mkdirs() }
        if (tmp.usableSpace < pick.size * 4) throw IllegalStateException("Not enough free space (need about ${pick.size * 4 / 1048576} MB).")
        val file = File(tmp, pick.name)
        jdk.download(pick.url, file, pick.size, log, cancelled)
        if (cancelled()) throw InstallCancelled()

        log.line("Unpacking...")
        val stage = File(ctx.filesDir, "flutter-stage")
        stage.deleteRecursively(); stage.mkdirs()
        try {
            JdkInstaller(ctx, stage).extractDeb(file, log, cancelled)
            // normally stage/opt/flutter; otherwise look for the SDK anywhere in the package
            val sdk = File(stage, "opt/flutter").takeIf { GradleTuner.isFlutterSdk(it) }
                ?: stage.walkTopDown().maxDepth(4).firstOrNull { it.isDirectory && GradleTuner.isFlutterSdk(it) }
                ?: throw IllegalStateException("The package unpacked, but no Flutter SDK (bin/flutter + packages/flutter_tools) is in it.")
            dest.deleteRecursively()
            if (!sdk.renameTo(dest)) { sdk.copyRecursively(dest, true); sdk.deleteRecursively() }
        } finally {
            stage.deleteRecursively()
            file.delete()
        }
        File(dest, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(dest, "bin/cache/dart-sdk/bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(dest, "bin/internal").listFiles()?.filter { it.name.endsWith(".sh") }?.forEach { it.setExecutable(true, false) }

        // Flutter's own scripts call these
        val prefix = TermEnvironment.prefix(ctx)
        for (tool in listOf("git", "unzip", "curl")) {
            if (File(prefix, "bin/$tool").exists()) continue
            if (cancelled()) throw InstallCancelled()
            try { jdk.installPackage(tool, log, cancelled) }
            catch (e: InstallCancelled) { throw e }
            catch (e: Exception) { log.line("Warning: could not install $tool (${e.message}). In the terminal run: pkg install $tool") }
        }
        if (!GradleTuner.isFlutterSdk(dest)) throw IllegalStateException("Install finished but ${dest.path} is not a Flutter SDK.")
        log.line("")
        log.line("Flutter SDK ready: ${dest.path}")
    }

    private fun unzip(f: File, out: File, log: TaskLog, cancelled: () -> Boolean) {
        var n = 0
        ZipInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (cancelled()) throw InstallCancelled()
                val t = File(out, e.name)
                if (!t.canonicalPath.startsWith(out.canonicalPath)) continue
                if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); FileOutputStream(t).use { z.copyTo(it) } }
                if (++n % 500 == 0) log.progress("  $n files")
            }
        }
    }

    /** Minimal tar reader (ustar + GNU long names), enough for SDK archives. */
    private fun untar(input: InputStream, out: File, log: TaskLog, cancelled: () -> Boolean) {
        val hdr = ByteArray(512)
        var longName: String? = null
        var n = 0
        fun readFully(b: ByteArray, len: Int): Boolean {
            var off = 0
            while (off < len) { val r = input.read(b, off, len - off); if (r < 0) return false; off += r }
            return true
        }
        input.use {
            while (readFully(hdr, 512)) {
                if (cancelled()) throw InstallCancelled()
                if (hdr.all { it == 0.toByte() }) break
                fun str(o: Int, l: Int) = String(hdr, o, l, Charsets.UTF_8).substringBefore('\u0000')
                var name = str(0, 100)
                val prefix = str(345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                if (longName != null) { name = longName!!; longName = null }
                val size = str(124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = hdr[156].toInt().toChar()
                val mode = str(100, 8).trim().ifEmpty { "0" }.toInt(8)
                val link = str(157, 100)
                val padded = (size + 511) / 512 * 512
                if (type == 'L') {
                    val b = ByteArray(padded.toInt()); readFully(b, b.size)
                    longName = String(b, 0, size.toInt(), Charsets.UTF_8).trimEnd('\u0000'); continue
                }
                val t = File(out, name.removePrefix("./"))
                if (!t.canonicalPath.startsWith(out.canonicalPath)) { input.skip(padded); continue }
                when (type) {
                    '5' -> t.mkdirs()
                    '2' -> { t.parentFile?.mkdirs(); runCatching { android.system.Os.symlink(link, t.path) } }
                    '0', '\u0000', '7' -> {
                        t.parentFile?.mkdirs()
                        FileOutputStream(t).use { o ->
                            var left = size
                            val buf = ByteArray(1 shl 16)
                            while (left > 0) {
                                val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (r < 0) throw IllegalStateException("Archive is cut off")
                                o.write(buf, 0, r); left -= r
                            }
                        }
                        input.skip(padded - size)
                        if (mode and 0b001_001_001 != 0) t.setExecutable(true, false)
                    }
                    else -> input.skip(padded)
                }
                if (++n % 500 == 0) log.progress("  $n files")
            }
        }
    }
}
