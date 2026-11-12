package com.example.mynative.androidide

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

class InstallCancelled : Exception("Stopped")

/** Terminal-like log: normal lines + one live "progress" line at the bottom. */
class TaskLog(private val onChange: (String) -> Unit) {
    private val sb = StringBuilder()
    @Volatile private var progress = ""

    fun line(s: String) { synchronized(sb) { sb.append(s).append('\n') }; progress = ""; push() }
    fun progress(s: String) { progress = s; push() }
    private fun push() = onChange(synchronized(sb) { sb.toString() } + progress)
}

/**
 * Pure Kotlin SDK installer. It reads Google's official repository index and downloads
 * + unzips the package into $ANDROID_HOME. No java / sdkmanager / shell needed, so it
 * works on Android even when cmdline-tools is missing.
 */
class SdkInstaller(private val context: Context) {

    private class Archive(
        var url: String = "", var size: Long = 0, var sha1: String? = null,
        var hostOs: String? = null, var hostArch: String? = null
    )

    private class Remote(
        val path: String, val revision: String, val name: String, val archive: Archive
    )

    companion object {
        private const val BASE = "https://dl.google.com/android/repository/"
        private val INDEXES = listOf("repository2-3.xml", "repository2-2.xml")
    }

    fun install(id: String, log: TaskLog, cancelled: () -> Boolean) {
        if (id == FlutterInstaller.ID) {
            FlutterInstaller.install(context, log, cancelled)
            return
        }
        if (id.startsWith("jdk;")) {
            JdkInstaller(context).install(id.removePrefix("jdk;"), log, cancelled)
            return
        }
        val sdkRoot = SdkRepository.sdkRoot(context).apply { mkdirs() }
        val target = File(sdkRoot, id.replace(';', '/'))

        log.line("~ \$ install \"$id\"")
        log.line("SDK root: ${sdkRoot.path}")
        log.line("Fetching package list...")
        val pkg = findPackage(id, cancelled)
            ?: throw IllegalStateException("Package \"$id\" not found in Google repository.")
        val a = pkg.archive
        log.line("Found: ${pkg.name} (rev ${pkg.revision})")
        log.line("Download size: ${mb(a.size)}")

        if (sdkRoot.usableSpace < a.size * 2) {
            throw IllegalStateException("Not enough free space. Need about ${mb(a.size * 2)}, have ${mb(sdkRoot.usableSpace)}.")
        }

        val dlDir = File(sdkRoot, ".downloads").apply { mkdirs() }
        val zip = File(dlDir, a.url.substringAfterLast('/'))
        val tmp = File(sdkRoot, ".tmp-" + id.replace(Regex("[^A-Za-z0-9.]"), "_"))
        var ok = false     // partial zip is kept on failure/cancel so the download can resume
        try {
            download(BASE + a.url, zip, a, log, cancelled)
            log.line("Download complete ✔")

            if (a.sha1 != null) {
                log.line("Verifying checksum...")
                val got = sha1(zip)
                if (!got.equals(a.sha1, ignoreCase = true)) {
                    zip.delete()
                    throw IllegalStateException("Checksum mismatch (download corrupted). Try again.")
                }
            }

            log.line("Extracting...")
            tmp.deleteRecursively(); tmp.mkdirs()
            unzip(zip, tmp, log, cancelled)

            if (!File(tmp, "source.properties").isFile) {
                File(tmp, "source.properties").writeText(
                    "Pkg.Revision=${pkg.revision}\nPkg.Desc=${pkg.name}\n"
                )
            }

            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("Could not move files to ${target.path}")
            }
            log.line("Installed to ${target.path}")
            ok = true
            SdkVerifier.report(id, target, log)
            autoFix(id, target, log, cancelled)
        } finally {
            if (ok) zip.delete()
            tmp.deleteRecursively()
        }
    }

    fun uninstall(id: String, log: TaskLog) {
        if (id == FlutterInstaller.ID) {
            FlutterInstaller.uninstall(context, log)
            return
        }
        if (id.startsWith("jdk;")) {
            JdkInstaller(context).uninstall(id.removePrefix("jdk;"), log)
            return
        }
        val dir = File(SdkRepository.sdkRoot(context), id.replace(';', '/'))
        log.line("~ \$ rm -rf \"${dir.path}\"")
        if (dir.exists() && !dir.deleteRecursively()) {
            throw IllegalStateException("Could not delete ${dir.path}")
        }
    }

    /**
     * Installs a package from a ZIP the user supplied (no download). The ZIP is checked, unpacked
     * and moved into place exactly like a downloaded one. [id] decides the target folder, so the
     * version the user picked must match the ZIP.
     */
    fun installFromZip(id: String, zip: File, log: TaskLog, cancelled: () -> Boolean) {
        val isJdk = id.startsWith("jdk;")
        val sdkRoot = SdkRepository.sdkRoot(context).apply { mkdirs() }
        val target = if (isJdk) JdkInstaller.javaHome(TermEnvironment.prefix(context), id.removePrefix("jdk;"))
        else File(sdkRoot, id.replace(';', '/'))

        log.line("~ \$ install \"$id\" from ZIP")
        log.line("ZIP: ${zip.name} (${mb(zip.length())})")
        log.line("Target: ${target.path}")
        if (sdkRoot.usableSpace < zip.length() * 2) {
            throw IllegalStateException("Not enough free space. Need about ${mb(zip.length() * 2)}, have ${mb(sdkRoot.usableSpace)}.")
        }
        try { ZipFile(zip).close() } catch (e: Exception) {
            throw IllegalStateException("This file is not a valid ZIP archive.")
        }

        val tmp = File(sdkRoot, ".tmp-" + id.replace(Regex("[^A-Za-z0-9.]"), "_"))
        try {
            log.line("Extracting...")
            tmp.deleteRecursively(); tmp.mkdirs()
            unzip(zip, tmp, log, cancelled)

            if (!looksRight(id, tmp)) flattenSingleDir(tmp)     // ZIP wrapped in extra folder(s)
            if (!looksRight(id, tmp)) {
                throw IllegalStateException(
                    "This ZIP does not look like ${SdkNeeds.label(id).ifEmpty { id }} (key files not found). " +
                        "Check that you picked the right ZIP."
                )
            }

            if (isJdk) {
                // A desktop (x86-64 / glibc) JDK cannot run on the phone - refuse it instead of installing a dead JDK.
                SdkVerifier.inspect(id, tmp).firstOrNull { it.ok == false }?.let {
                    throw IllegalStateException(
                        "This JDK is built for ${it.machine}. The phone needs an Android (bionic) build for its own CPU."
                    )
                }
                File(tmp, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
            } else if (!File(tmp, "source.properties").isFile) {
                File(tmp, "source.properties").writeText(
                    "Pkg.Revision=${revisionOf(id)}\nPkg.Desc=${SdkNeeds.label(id)} (from ZIP)\n"
                )
            }

            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) throw IllegalStateException("Could not move files to ${target.path}")
            log.line("Installed to ${target.path}")
            if (!isJdk) { SdkVerifier.report(id, target, log); autoFix(id, target, log, cancelled) }
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** After every install: if the tools are for the wrong CPU, swap in arm64 ones. Never fails the install itself. */
    private fun autoFix(id: String, target: File, log: TaskLog, cancelled: () -> Boolean) {
        if (!NativeToolsFix.fixable(id) || !NativeToolsFix.needsFix(id, target)) return
        try {
            NativeToolsFix.fix(context, id, target, log, cancelled)
        } catch (e: InstallCancelled) {
            throw e
        } catch (e: Exception) {
            log.line("⚠ Could not make the tools run on this phone: ${e.message}")
            log.line("  Open SDK Manager > select it > \"Fix tools\" to try again.")
        }
    }

    /** Key file(s) every real package of this kind has. */
    private fun looksRight(id: String, dir: File): Boolean = when {
        id.startsWith("build-tools;") -> File(dir, "aapt2").exists() || File(dir, "aapt").exists() || File(dir, "lib/d8.jar").exists()
        id.startsWith("platforms;") -> File(dir, "android.jar").exists()
        id.startsWith("ndk;") -> File(dir, "ndk-build").exists() || File(dir, "toolchains").isDirectory
        id.startsWith("cmake;") -> File(dir, "bin/cmake").exists()
        id == "platform-tools" -> File(dir, "adb").exists() || File(dir, "fastboot").exists()
        id.startsWith("jdk;") -> File(dir, "bin/java").exists()
        else -> true
    }

    /** ZIP(folder(folder(real content))) -> moves the real content up to [dir]. */
    private fun flattenSingleDir(dir: File) {
        repeat(3) {
            val kids = dir.listFiles() ?: return
            if (kids.size != 1 || !kids[0].isDirectory) return
            val hold = File(dir.parentFile, dir.name + "-inner")
            hold.deleteRecursively()
            if (!kids[0].renameTo(hold)) return
            dir.deleteRecursively()
            if (!hold.renameTo(dir)) return
        }
    }

    private fun revisionOf(id: String): String = when {
        id.startsWith("build-tools;") || id.startsWith("ndk;") || id.startsWith("cmake;") -> id.substringAfter(';')
        else -> "1"
    }

    // ---- repository index --------------------------------------------------------------

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AndroidIDE-SdkManager")
        }

    private fun findPackage(id: String, cancelled: () -> Boolean): Remote? {
        var lastError: Exception? = null
        for (index in INDEXES) {
            try {
                val conn = open(BASE + index)
                if (conn.responseCode != 200) { conn.disconnect(); continue }
                val r = conn.inputStream.use { parse(it, id, cancelled) }
                if (r != null) return r
            } catch (e: InstallCancelled) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        return null
    }

    private fun parse(ins: java.io.InputStream, id: String, cancelled: () -> Boolean): Remote? {
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        val stack = ArrayList<String>()
        val text = StringBuilder()
        var inTarget = false
        var inComplete = false
        var major = ""; var minor = ""; var micro = ""; var preview = ""
        var name = id
        val archives = ArrayList<Archive>()
        var cur: Archive? = null

        var ev = p.eventType
        var n = 0
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (++n % 2000 == 0 && cancelled()) throw InstallCancelled()
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val tag = p.name
                    stack.add(tag); text.setLength(0)
                    if (!inTarget) {
                        if (tag == "remotePackage" && p.getAttributeValue(null, "path") == id) inTarget = true
                    } else {
                        if (tag == "archive") cur = Archive()
                        if (tag == "complete") inComplete = true
                        if (tag == "checksum" && inComplete && cur != null) {
                            val type = p.getAttributeValue(null, "type")
                            if (type == null || type.equals("sha1", true)) cur.sha1 = "" // filled on text
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inTarget) text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val tag = stack.removeAt(stack.lastIndex)
                    val parent = stack.lastOrNull()
                    val t = text.toString().trim()
                    text.setLength(0)
                    if (inTarget) {
                        when (tag) {
                            "major" -> if (parent == "revision") major = t
                            "minor" -> if (parent == "revision") minor = t
                            "micro" -> if (parent == "revision") micro = t
                            "preview" -> if (parent == "revision") preview = t
                            "display-name" -> if (parent == "remotePackage") name = t
                            "host-os" -> cur?.hostOs = t
                            "host-arch" -> cur?.hostArch = t
                            "url" -> if (inComplete) cur?.url = t
                            "size" -> if (inComplete) cur?.size = t.toLongOrNull() ?: 0L
                            "checksum" -> if (inComplete && cur != null && cur.sha1 != null) cur.sha1 = t
                            "complete" -> inComplete = false
                            "archive" -> { cur?.let { archives.add(it) }; cur = null }
                            "remotePackage" -> {
                                val arch = pick(archives)
                                    ?: throw IllegalStateException("No archive for this device OS in \"$id\".")
                                var rev = listOf(major, minor, micro).filter { it.isNotEmpty() }.joinToString(".")
                                if (preview.isNotEmpty()) rev += " rc$preview"
                                if (arch.sha1.isNullOrEmpty()) arch.sha1 = null
                                return Remote(id, rev.ifEmpty { "1" }, name, arch)
                            }
                        }
                    }
                }
            }
            ev = p.next()
        }
        return null
    }

    /** Generic archive first, otherwise the Linux one (zip contents are only unpacked here). */
    private fun pick(list: List<Archive>): Archive? =
        (if (android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true)
            list.firstOrNull {
                it.url.isNotEmpty() && it.hostOs == "linux" &&
                    (it.hostArch == "aarch64" || it.hostArch == "arm64")
            } else null)
            ?: list.firstOrNull { it.url.isNotEmpty() && it.hostOs == null }
            ?: list.firstOrNull {
                it.url.isNotEmpty() && it.hostOs == "linux" &&
                    (it.hostArch == null || it.hostArch == "x64" || it.hostArch == "x86_64")
            }

    // ---- download / unzip ------------------------------------------------------------------

    private fun download(url: String, out: File, a: Archive, log: TaskLog, cancelled: () -> Boolean) {
        log.line("Downloading ${a.url}")
        val existing = if (out.isFile) out.length() else 0L
        val conn = open(url).also { if (existing > 0) it.setRequestProperty("Range", "bytes=$existing-") }
        if (existing > 0 && conn.responseCode == 416) { conn.disconnect(); return }   // already complete
        val resumed = existing > 0 && conn.responseCode == 206
        if (conn.responseCode != 200 && !resumed) throw IllegalStateException("Download failed: HTTP ${conn.responseCode}")
        val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (len > 0) len + (if (resumed) existing else 0L) else a.size
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

    private fun unzip(zipFile: File, dest: File, log: TaskLog, cancelled: () -> Boolean) {
        ZipFile(zipFile).use { z ->
            val entries = z.entries().toList()
            // If everything sits in ONE top-level folder (android-34/, android-ndk-r27/ ...), strip it.
            val tops = entries.map { it.name.trimStart('/').substringBefore('/') }.toSet()
            val strip = tops.size == 1 && entries.any { it.name.trimStart('/').contains('/') }
            val destPath = dest.canonicalPath + File.separator
            var count = 0
            val total = entries.size
            var last = 0L
            for (e in entries) {
                if (cancelled()) throw InstallCancelled()
                var rel = e.name.trimStart('/')
                if (strip) rel = rel.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val f = File(dest, rel)
                if (!f.canonicalPath.startsWith(destPath)) continue   // zip-slip guard
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    z.getInputStream(e).use { ins -> FileOutputStream(f).use { ins.copyTo(it, 64 * 1024) } }
                    val ext = f.name.substringAfterLast('.', "")
                    if (ext.isEmpty() || ext == "sh" || f.parentFile?.name == "bin") f.setExecutable(true, false)
                }
                count++
                val now = System.currentTimeMillis()
                if (now - last > 250) {
                    last = now
                    log.progress("  $count / $total files")
                }
            }
            log.progress("  $count / $total files")
            log.line("")
        }
    }

    private fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)
}
