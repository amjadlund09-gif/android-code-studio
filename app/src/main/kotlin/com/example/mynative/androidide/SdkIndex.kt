package com.example.mynative.androidide

import android.content.Context
import android.os.Build
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One installable package from Google's repository index (stable channel only). */
class RemoteSdkPkg(val id: String, val revision: String, val size: Long)

/**
 * Live package list from Google's repository index (the same file the installer reads).
 * Cached on disk for 24h so the SDK Manager opens instantly and works offline.
 */
object SdkIndex {
    private const val BASE = "https://dl.google.com/android/repository/"
    private val INDEXES = listOf("repository2-3.xml", "repository2-2.xml")
    private const val TTL = 24L * 60 * 60 * 1000

    private val wanted = Regex(
        "^(build-tools;\\d+(\\.\\d+)*|ndk;\\d+\\.\\d+\\.\\d+|cmake;\\d+(\\.\\d+)*|" +
            "platforms;android-\\d+(\\.\\d+)?|platform-tools)$"
    )

    private fun cacheFile(ctx: Context) = File(SdkRepository.homeDir(ctx), ".sdk-index.tsv")

    /** Whatever is on disk (any age), or null if never downloaded. */
    fun cached(ctx: Context): List<RemoteSdkPkg>? {
        val f = cacheFile(ctx)
        if (!f.isFile) return null
        return runCatching {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 3) null else RemoteSdkPkg(p[0], p[1], p[2].toLongOrNull() ?: 0L)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun isStale(ctx: Context): Boolean {
        val f = cacheFile(ctx)
        return !f.isFile || System.currentTimeMillis() - f.lastModified() > TTL
    }

    /** Blocking: call from a background thread. Falls back to the old cache if offline. */
    fun load(ctx: Context, force: Boolean = false): List<RemoteSdkPkg> {
        if (!force && !isStale(ctx)) cached(ctx)?.let { return it }
        var last: Exception? = null
        for (name in INDEXES) {
            try {
                val conn = (URL(BASE + name).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AndroidIDE-SdkManager")
                }
                if (conn.responseCode != 200) { conn.disconnect(); continue }
                val list = conn.inputStream.use { parse(it) }
                conn.disconnect()
                if (list.isNotEmpty()) {
                    runCatching {
                        cacheFile(ctx).apply { parentFile?.mkdirs() }
                            .writeText(list.joinToString("\n") { "${it.id}\t${it.revision}\t${it.size}" })
                    }
                    return list
                }
            } catch (e: Exception) {
                last = e
            }
        }
        return cached(ctx) ?: throw (last ?: IllegalStateException("Could not load the SDK package list."))
    }

    private fun parse(ins: InputStream): List<RemoteSdkPkg> {
        val deviceArm = Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        val out = ArrayList<RemoteSdkPkg>()
        val stack = ArrayList<String>()
        val text = StringBuilder()
        var path: String? = null
        var major = ""; var minor = ""; var micro = ""; var preview = ""
        var inComplete = false
        var hostOs: String? = null; var hostArch: String? = null; var archSize = 0L
        var sizeArch = -1L; var sizeGeneric = -1L; var sizeLinux = -1L

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val tag = p.name
                    stack.add(tag); text.setLength(0)
                    if (tag == "remotePackage") {
                        val id = p.getAttributeValue(null, "path")
                        path = if (id != null && wanted.matches(id)) id else null
                        major = ""; minor = ""; micro = ""; preview = ""
                        sizeArch = -1L; sizeGeneric = -1L; sizeLinux = -1L
                    } else if (path != null) {
                        if (tag == "archive") { hostOs = null; hostArch = null; archSize = 0L }
                        if (tag == "complete") inComplete = true
                    }
                }
                XmlPullParser.TEXT -> if (path != null) text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val tag = stack.removeAt(stack.lastIndex)
                    val parent = stack.lastOrNull()
                    val t = text.toString().trim()
                    text.setLength(0)
                    val id = path
                    if (id != null) {
                        when (tag) {
                            "major" -> if (parent == "revision") major = t
                            "minor" -> if (parent == "revision") minor = t
                            "micro" -> if (parent == "revision") micro = t
                            "preview" -> if (parent == "revision") preview = t
                            "host-os" -> hostOs = t
                            "host-arch" -> hostArch = t
                            "size" -> if (inComplete) archSize = t.toLongOrNull() ?: 0L
                            "complete" -> inComplete = false
                            "archive" -> {
                                if (hostOs == null) {
                                    if (sizeGeneric < 0) sizeGeneric = archSize
                                } else if (hostOs == "linux") {
                                    if (hostArch == "aarch64" || hostArch == "arm64") {
                                        if (sizeArch < 0) sizeArch = archSize
                                    } else if (hostArch == null || hostArch == "x64" || hostArch == "x86_64") {
                                        if (sizeLinux < 0) sizeLinux = archSize
                                    }
                                }
                            }
                            "remotePackage" -> {
                                if (preview.isEmpty()) {
                                    val rev = listOf(major, minor, micro).filter { it.isNotEmpty() }.joinToString(".")
                                    val size = when {
                                        deviceArm && sizeArch >= 0 -> sizeArch
                                        sizeGeneric >= 0 -> sizeGeneric
                                        else -> maxOf(sizeLinux, 0L)
                                    }
                                    out.add(RemoteSdkPkg(id, rev.ifEmpty { "1" }, size))
                                }
                                path = null
                            }
                        }
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    /** True if [remote] is a newer revision than [installed] (numeric parts compared). */
    fun isNewer(remote: String, installed: String): Boolean {
        val a = Regex("\\d+").findAll(remote).map { it.value.toLong() }.toList()
        val b = Regex("\\d+").findAll(installed.substringBefore(' ')).map { it.value.toLong() }.toList()
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }
}
