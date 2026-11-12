package com.example.mynative.androidide

import android.content.Context
import android.os.Build
import android.system.Os
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Installs OpenJDK 17 / 21 for Android (bionic) from the Termux package repository.
 *
 * Mini "apt": reads the repo index, resolves the dependency closure of openjdk-N, downloads
 * every .deb, and unpacks its data.tar into $PREFIX (files/usr), re-mapping Termux's
 * /data/data/com.termux/files/usr path to this app's prefix.
 *
 * Result: $PREFIX/lib/jvm/java-N-openjdk  (TermEnvironment already looks there for JAVA_HOME).
 */
class JdkInstaller(private val context: Context, private val targetPrefix: File? = null) {

    private class Pkg(
        val name: String, val depends: List<String>,
        val filename: String, val size: Long, val sha256: String?
    )

    companion object {
        private const val REPO = "https://packages.termux.dev/apt/termux-main/"
        private const val TERMUX_PREFIX = "data/data/com.termux/files/usr"
        private const val TERMUX_PREFIX_ABS = "/data/data/com.termux/files/usr"

        /** Packages that only make sense inside a full Termux and are not needed to run java. */
        private val SKIP = setOf(
            "termux-tools", "termux-exec", "termux-core", "termux-keyring",
            "termux-licenses", "termux-am", "termux-am-socket", "dpkg", "apt", "game-repo", "science-repo"
        )

        fun javaHome(prefix: File, version: String) = File(prefix, "lib/jvm/java-$version-openjdk")
    }

    private val prefix get() = targetPrefix ?: TermEnvironment.prefix(context)
    private val marks get() = File(prefix, "var/lib/androidide-pkgs")

    internal fun termuxArch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> throw IllegalStateException("Unsupported CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    // ---- install / uninstall --------------------------------------------------------------

    fun install(version: String, log: TaskLog, cancelled: () -> Boolean) {
        TermEnvironment.prepare(context)
        val arch = termuxArch()
        val root = "openjdk-$version"
        log.line("~ \$ install JDK $version ($arch)")
        log.line("Install path: ${javaHome(prefix, version).path}")
        log.line("Fetching package list...")
        val index = fetchIndex(arch, cancelled)
        if (!index.containsKey(root)) throw IllegalStateException("\"$root\" not found in Termux repo for $arch.")

        val order = resolve(root, index)
        val todo = order.filter { !File(marks, it.name).isFile || it.name == root }
        log.line("Packages: ${todo.joinToString { it.name }}")
        val total = todo.sumOf { it.size }
        log.line("Download size: ${mb(total)}")
        if (prefix.usableSpace < total * 4) {
            throw IllegalStateException("Not enough free space. Need about ${mb(total * 4)}, have ${mb(prefix.usableSpace)}.")
        }

        val tmpDir = File(prefix, "tmp/jdk-dl").apply { mkdirs() }   // kept on failure so the download can resume
        var ok = false
        try {
            todo.forEachIndexed { i, pkg ->
                if (cancelled()) throw InstallCancelled()
                log.line("")
                log.line("[${i + 1}/${todo.size}] ${pkg.name}")
                val deb = File(tmpDir, pkg.filename.substringAfterLast('/'))
                download(REPO + pkg.filename, deb, pkg.size, log, cancelled)
                if (pkg.sha256 != null) {
                    if (!sha256(deb).equals(pkg.sha256, ignoreCase = true)) {
                        deb.delete()
                        throw IllegalStateException("Checksum mismatch for ${pkg.name} (download corrupted). Try again.")
                    }
                }
                log.line("Extracting...")
                extractDeb(deb, log, cancelled)
                deb.delete()
                marks.mkdirs()
                File(marks, pkg.name).writeText(pkg.filename)
            }
            ok = true
        } finally {
            if (ok) tmpDir.deleteRecursively()
        }

        val java = File(javaHome(prefix, version), "bin/java")
        if (!java.isFile) throw IllegalStateException("Install finished but ${java.path} is missing.")
        java.setExecutable(true, false)
        log.line("")
        log.line("JDK $version installed ✔")
    }

    /** Installs any Termux package (and its dependencies) into $PREFIX, e.g. "aapt2". */
    fun installPackage(root: String, log: TaskLog, cancelled: () -> Boolean) {
        TermEnvironment.prepare(context)
        val arch = termuxArch()
        log.line("~ \$ install $root ($arch)")
        log.line("Fetching package list...")
        val index = fetchIndex(arch, cancelled)
        if (!index.containsKey(root)) throw IllegalStateException("\"$root\" not found in Termux repo for $arch.")
        val order = resolve(root, index)
        val todo = order.filter { !File(marks, it.name).isFile || it.name == root }
        log.line("Packages: ${todo.joinToString { it.name }}")
        val total = todo.sumOf { it.size }
        log.line("Download size: ${mb(total)}")
        if (prefix.usableSpace < total * 4) {
            throw IllegalStateException("Not enough free space. Need about ${mb(total * 4)}, have ${mb(prefix.usableSpace)}.")
        }
        val tmpDir = File(prefix, "tmp/pkg-dl").apply { mkdirs() }
        var ok = false
        try {
            todo.forEachIndexed { i, pkg ->
                if (cancelled()) throw InstallCancelled()
                log.line("")
                log.line("[${i + 1}/${todo.size}] ${pkg.name}")
                val deb = File(tmpDir, pkg.filename.substringAfterLast('/'))
                download(REPO + pkg.filename, deb, pkg.size, log, cancelled)
                if (pkg.sha256 != null && !sha256(deb).equals(pkg.sha256, ignoreCase = true)) {
                    deb.delete()
                    throw IllegalStateException("Checksum mismatch for ${pkg.name} (download corrupted). Try again.")
                }
                log.line("Extracting...")
                extractDeb(deb, log, cancelled)
                deb.delete()
                marks.mkdirs()
                File(marks, pkg.name).writeText(pkg.filename)
            }
            ok = true
        } finally {
            if (ok) tmpDir.deleteRecursively()
        }
    }

    fun uninstall(version: String, log: TaskLog) {
        val dir = javaHome(prefix, version)
        log.line("~ \$ rm -rf \"${dir.path}\"")
        if (dir.exists() && !dir.deleteRecursively()) throw IllegalStateException("Could not delete ${dir.path}")
        File(marks, "openjdk-$version").delete()
    }

    // ---- repo index ---------------------------------------------------------------------------

    internal fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AndroidIDE-SdkManager")
        }

    private fun fetchIndex(arch: String, cancelled: () -> Boolean): Map<String, Pkg> {
        val conn = open("${REPO}dists/stable/main/binary-$arch/Packages.gz")
        if (conn.responseCode != 200) throw IllegalStateException("Package list failed: HTTP ${conn.responseCode}")
        val map = HashMap<String, Pkg>()
        conn.inputStream.use { raw ->
            GZIPInputStream(BufferedInputStream(raw)).bufferedReader().use { r ->
                var name = ""; var dep = ""; var file = ""; var size = 0L; var sha: String? = null
                fun flush() {
                    if (name.isNotEmpty() && file.isNotEmpty()) {
                        map[name] = Pkg(name, parseDepends(dep), file, size, sha)
                    }
                    name = ""; dep = ""; file = ""; size = 0L; sha = null
                }
                var n = 0
                for (line in r.lineSequence()) {
                    if (++n % 5000 == 0 && cancelled()) throw InstallCancelled()
                    when {
                        line.isEmpty() -> flush()
                        line.startsWith("Package: ") -> name = line.substring(9).trim()
                        line.startsWith("Depends: ") -> dep = line.substring(9)
                        line.startsWith("Pre-Depends: ") -> dep = if (dep.isEmpty()) line.substring(13) else dep + ", " + line.substring(13)
                        line.startsWith("Filename: ") -> file = line.substring(10).trim()
                        line.startsWith("Size: ") -> size = line.substring(6).trim().toLongOrNull() ?: 0L
                        line.startsWith("SHA256: ") -> sha = line.substring(8).trim()
                    }
                }
                flush()
            }
        }
        conn.disconnect()
        return map
    }

    /** "a (>= 1), b | c" -> [a, b] (first alternative, version constraints dropped). */
    private fun parseDepends(s: String): List<String> =
        s.split(',').map { it.substringBefore('|').substringBefore('(').trim() }.filter { it.isNotEmpty() }

    /** Dependencies first, root last. */
    private fun resolve(root: String, index: Map<String, Pkg>): List<Pkg> {
        val out = ArrayList<Pkg>()
        val seen = HashSet<String>()
        fun visit(name: String) {
            if (name in SKIP || !seen.add(name)) return
            val p = index[name] ?: return          // virtual / unknown package: ignore
            p.depends.forEach { visit(it) }
            out.add(p)
        }
        visit(root)
        return out
    }

    // ---- download ---------------------------------------------------------------------------------

    internal fun download(url: String, out: File, expected: Long, log: TaskLog, cancelled: () -> Boolean) {
        val existing = if (out.isFile) out.length() else 0L
        val conn = open(url).also { if (existing > 0) it.setRequestProperty("Range", "bytes=$existing-") }
        if (existing > 0 && conn.responseCode == 416) { conn.disconnect(); return }   // already complete
        val resumed = existing > 0 && conn.responseCode == 206
        if (conn.responseCode != 200 && !resumed) throw IllegalStateException("Download failed: HTTP ${conn.responseCode}")
        val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (len > 0) len + (if (resumed) existing else 0L) else expected
        var done = if (resumed) existing else 0L
        var last = 0L
        conn.inputStream.use { ins ->
            FileOutputStream(out, resumed).use { fos ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) throw InstallCancelled()
                    val r = ins.read(buf)
                    if (r < 0) break
                    fos.write(buf, 0, r)
                    done += r
                    val now = System.currentTimeMillis()
                    if (now - last > 250) {
                        last = now
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        log.progress("  ${mb(done)} / ${mb(total)}  ($pct%)")
                    }
                }
            }
        }
        log.progress("  ${mb(done)} / ${mb(total)}  (100%)")
        log.line("")
        conn.disconnect()
    }

    // ---- .deb (ar) + tar ---------------------------------------------------------------------------

    private class Bounded(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = src.read()
            if (b >= 0) left--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
    }

    internal fun extractDeb(deb: File, log: TaskLog, cancelled: () -> Boolean) {
        DataInputStream(BufferedInputStream(FileInputStream(deb), 64 * 1024)).use { ins ->
            val magic = ByteArray(8)
            ins.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != "!<arch>\n") throw IllegalStateException("Not a .deb file: ${deb.name}")
            val hdr = ByteArray(60)
            while (true) {
                try { ins.readFully(hdr) } catch (e: EOFException) { break }
                val name = String(hdr, 0, 16, Charsets.ISO_8859_1).trim().trimEnd('/')
                val size = String(hdr, 48, 10, Charsets.ISO_8859_1).trim().toLong()
                val pad = size % 2
                if (name.startsWith("data.tar")) {
                    val bounded = Bounded(ins, size)
                    val data: InputStream = when {
                        name.endsWith(".xz") -> XZInputStream(bounded)
                        name.endsWith(".gz") -> GZIPInputStream(bounded)
                        name == "data.tar" -> bounded
                        else -> throw IllegalStateException("Unsupported package compression: $name")
                    }
                    untar(BufferedInputStream(data, 64 * 1024), log, cancelled)
                    return
                }
                skipFully(ins, size + pad)
            }
            throw IllegalStateException("No data.tar in ${deb.name}")
        }
    }

    private fun untar(ins: InputStream, log: TaskLog, cancelled: () -> Boolean) {
        val hdr = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var count = 0
        var last = 0L
        while (true) {
            if (cancelled()) throw InstallCancelled()
            if (!readBlock(ins, hdr)) break
            if (hdr.all { it == 0.toByte() }) break

            var name = cstr(hdr, 0, 100)
            val mode = oct(hdr, 100, 8).toInt()
            val size = oct(hdr, 124, 12)
            val type = (hdr[156].toInt() and 0xff).toChar()
            var link = cstr(hdr, 157, 100)
            if (cstr(hdr, 257, 5) == "ustar") {
                val p = cstr(hdr, 345, 155)
                if (p.isNotEmpty()) name = "$p/$name"
            }
            val padded = (size + 511) / 512 * 512

            when (type) {
                'L' -> { longName = readString(ins, size); skipFully(ins, padded - size); continue }
                'K' -> { longLink = readString(ins, size); skipFully(ins, padded - size); continue }
                'x' -> {
                    val pax = readString(ins, size); skipFully(ins, padded - size)
                    for (rec in pax.split('\n')) {
                        val kv = rec.substringAfter(' ', "")
                        when {
                            kv.startsWith("path=") -> longName = kv.substring(5)
                            kv.startsWith("linkpath=") -> longLink = kv.substring(9)
                        }
                    }
                    continue
                }
                'g' -> { skipFully(ins, padded); continue }
            }
            longName?.let { name = it }; longName = null
            longLink?.let { link = it }; longLink = null

            val rel = name.removePrefix("./").trimStart('/')
            val inPrefix = rel == TERMUX_PREFIX || rel.startsWith("$TERMUX_PREFIX/")
            val sub = rel.removePrefix(TERMUX_PREFIX).trimStart('/').trimEnd('/')
            if (!inPrefix || sub.isEmpty() || sub.split('/').any { it == ".." }) {
                skipFully(ins, padded); continue
            }
            val dest = File(prefix, sub)

            when (type) {
                '5' -> { dest.mkdirs(); skipFully(ins, padded) }
                '2' -> {
                    skipFully(ins, padded)
                    dest.parentFile?.mkdirs()
                    if (dest.exists() || isLink(dest)) dest.delete()
                    Os.symlink(remapLink(link), dest.path)
                }
                '1' -> {
                    skipFully(ins, padded)
                    val srcRel = link.removePrefix("./").trimStart('/').removePrefix(TERMUX_PREFIX).trimStart('/')
                    val src = File(prefix, srcRel)
                    if (src.isFile) {
                        dest.parentFile?.mkdirs()
                        if (dest.exists()) dest.delete()
                        src.copyTo(dest, overwrite = true)
                        if (mode and 73 != 0) dest.setExecutable(true, false)
                    }
                }
                '0', '\u0000', '7' -> {
                    dest.parentFile?.mkdirs()
                    if (dest.exists() || isLink(dest)) dest.delete()
                    FileOutputStream(dest).use { fos ->
                        var left = size
                        val buf = ByteArray(64 * 1024)
                        while (left > 0) {
                            val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (r < 0) throw EOFException("Unexpected end of archive")
                            fos.write(buf, 0, r)
                            left -= r
                        }
                    }
                    skipFully(ins, padded - size)
                    dest.setReadable(true, false)
                    if (mode and 73 != 0) dest.setExecutable(true, false)
                }
                else -> skipFully(ins, padded)
            }
            count++
            val now = System.currentTimeMillis()
            if (now - last > 250) { last = now; log.progress("  $count files") }
        }
        log.progress("  $count files")
        log.line("")
    }

    private fun remapLink(target: String): String =
        if (target.startsWith(TERMUX_PREFIX_ABS)) prefix.path + target.removePrefix(TERMUX_PREFIX_ABS) else target

    private fun isLink(f: File): Boolean =
        try { Os.lstat(f.path); true } catch (e: Exception) { false }

    // ---- small helpers ---------------------------------------------------------------------------------

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun oct(b: ByteArray, off: Int, len: Int): Long {
        // GNU base-256 for very large sizes
        if (b[off].toInt() and 0x80 != 0) {
            var v = (b[off].toInt() and 0x7f).toLong()
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xff)
            return v
        }
        val s = cstr(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun readBlock(ins: InputStream, buf: ByteArray): Boolean {
        var got = 0
        while (got < buf.size) {
            val r = ins.read(buf, got, buf.size - got)
            if (r < 0) return got != 0 && throw EOFException("Truncated archive")
            got += r
        }
        return true
    }

    private fun readString(ins: InputStream, size: Long): String {
        val b = ByteArray(size.toInt())
        var got = 0
        while (got < b.size) {
            val r = ins.read(b, got, b.size - got)
            if (r < 0) throw EOFException("Truncated archive")
            got += r
        }
        return String(b, Charsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException("Unexpected end of archive")
            left -= r
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)
}
