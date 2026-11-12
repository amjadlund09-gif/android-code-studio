package com.example.mynative.androidide

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Look up libraries on Maven Central / Google Maven and add them to the module's build file.
 * - answers are cached for 1 hour (and an old cached answer is used when the network is down)
 * - Maven search has a second host as fallback
 * - if the project uses a version catalog (gradle/libs.versions.toml) the library goes there
 *   and the build file gets `implementation libs.xxx`
 */
object DependencyManager {

    data class Lib(val group: String, val artifact: String, val version: String) {
        val notation get() = "$group:$artifact:$version"
    }

    private const val CACHE_TTL_MS = 60L * 60L * 1000L
    private var cacheDir: File? = null

    /** Call once (ProjectActivity.onCreate). */
    fun init(ctx: Context) {
        cacheDir = File(ctx.cacheDir, "dep-cache").apply { mkdirs() }
    }

    fun findBuildFile(workDir: File): File? =
        listOf("app/build.gradle.kts", "app/build.gradle", "build.gradle.kts", "build.gradle")
            .map { File(workDir, it) }.firstOrNull { it.isFile }

    private fun cacheFile(url: String): File? =
        cacheDir?.let { File(it, Integer.toHexString(url.hashCode()) + "_" + url.length) }

    private fun fetch(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "AndroidIDE-Deps")
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }

    private fun get(url: String): String? {
        val cf = cacheFile(url)
        if (cf != null && cf.isFile && System.currentTimeMillis() - cf.lastModified() < CACHE_TTL_MS) {
            val hit = runCatching { cf.readText() }.getOrNull()
            if (!hit.isNullOrEmpty()) return hit
        }
        for (attempt in 0..1) {
            val body = fetch(url)
            if (body != null) {
                if (cf != null) runCatching { cf.writeText(body) }
                return body
            }
        }
        // offline / server down: an old answer is better than nothing
        if (cf != null && cf.isFile) return runCatching { cf.readText() }.getOrNull()
        return null
    }

    private fun latestFromMetadata(base: String, group: String, artifact: String): String? {
        val xml = get("$base/${group.replace('.', '/')}/$artifact/maven-metadata.xml") ?: return null
        return Regex("<release>([^<]+)</release>").find(xml)?.groupValues?.get(1)
            ?: Regex("<latest>([^<]+)</latest>").find(xml)?.groupValues?.get(1)
            ?: Regex("<version>([^<]+)</version>").findAll(xml).lastOrNull()?.groupValues?.get(1)
    }

    /** "group:artifact" (or with :version) -> Lib; plain words -> Maven Central text search. Call off the main thread. */
    fun search(query: String): List<Lib> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val parts = q.split(':')
        if (parts.size >= 2) {
            val g = parts[0].trim(); val a = parts[1].trim()
            if (parts.size >= 3 && parts[2].isNotBlank()) return listOf(Lib(g, a, parts[2].trim()))
            val v = latestFromMetadata("https://dl.google.com/dl/android/maven2", g, a)
                ?: latestFromMetadata("https://repo1.maven.org/maven2", g, a)
            return if (v != null) listOf(Lib(g, a, v)) else emptyList()
        }
        val enc = URLEncoder.encode(q, "UTF-8")
        val body = get("https://search.maven.org/solrsearch/select?q=$enc&rows=15&wt=json")
            ?: get("https://central.sonatype.com/solrsearch/select?q=$enc&rows=15&wt=json")
            ?: return emptyList()
        return try {
            val docs = JSONObject(body).getJSONObject("response").getJSONArray("docs")
            (0 until docs.length()).map {
                val d = docs.getJSONObject(it)
                Lib(d.getString("g"), d.getString("a"), d.optString("latestVersion", d.optString("v", "")))
            }.filter { it.version.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
    }

    // ---- adding ---------------------------------------------------------------------------------

    /** Returns a short message describing what happened. A message starting with "Added" means the file changed. */
    fun add(buildFile: File, lib: Lib): String {
        val text = buildFile.readText()
        val kts = buildFile.name.endsWith(".kts")
        val where = buildFile.name

        // already inline?
        if (text.contains("${lib.group}:${lib.artifact}:")) return "${lib.group}:${lib.artifact} is already in $where"

        val catalog = findCatalog(buildFile)
        if (catalog != null && text.contains("libs.")) {
            val toml = catalog.readText()
            val existing = aliasOf(toml, lib)
            val alias = existing ?: freeAlias(toml, lib)
            val accessor = "libs." + alias.lowercase().replace('-', '.').replace('_', '.')
            if (text.contains(accessor)) return "${lib.group}:${lib.artifact} is already in $where"
            val line = if (kts) "    implementation($accessor)" else "    implementation $accessor"
            if (existing == null) {
                val entry = "$alias = { group = \"${lib.group}\", name = \"${lib.artifact}\", version = \"${lib.version}\" }"
                catalog.writeText(insertIntoLibraries(toml, entry))
            }
            val msg = insertDependency(buildFile, text, line, lib)
            return if (msg.startsWith("Added")) "Added $accessor (${lib.notation}) to $where + ${catalog.name}" else msg
        }

        val line = if (kts) "    implementation(\"${lib.notation}\")" else "    implementation '${lib.notation}'"
        return insertDependency(buildFile, text, line, lib)
    }

    private fun insertDependency(buildFile: File, text: String, line: String, lib: Lib): String {
        // last top-level "dependencies {" block
        val m = Regex("(?m)^dependencies\\s*\\{").findAll(text).toList().lastOrNull()
        if (m == null) {
            buildFile.writeText(text.trimEnd() + "\n\ndependencies {\n$line\n}\n")
            return "Added dependencies block with ${lib.notation}"
        }
        var depth = 0
        var i = m.range.last
        var end = -1
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
            i++
        }
        if (end < 0) return "Could not parse ${buildFile.name}"
        val before = text.substring(0, end).trimEnd()
        buildFile.writeText(before + "\n" + line + "\n" + text.substring(end))
        return "Added ${lib.notation} to ${buildFile.name}"
    }

    // ---- version catalog ------------------------------------------------------------------------

    /** gradle/libs.versions.toml next to the module, its parent or grand-parent. */
    private fun findCatalog(buildFile: File): File? {
        var d: File? = buildFile.parentFile
        repeat(3) {
            val f = d?.let { File(it, "gradle/libs.versions.toml") }
            if (f != null && f.isFile) return f
            d = d?.parentFile
        }
        return null
    }

    /** Alias of this exact library if the catalog already has it. */
    private fun aliasOf(toml: String, lib: Lib): String? {
        val rx = Regex("(?m)^\\s*([A-Za-z0-9_.\\-]+)\\s*=\\s*(\\{[^}\\n]*\\}|\"[^\"\\n]*\")")
        for (m in rx.findAll(toml)) {
            val body = m.groupValues[2]
            val byModule = body.contains("\"${lib.group}:${lib.artifact}")
            val byParts = body.contains("\"${lib.group}\"") && body.contains("\"${lib.artifact}\"")
            if (byModule || byParts) return m.groupValues[1]
        }
        return null
    }

    private fun freeAlias(toml: String, lib: Lib): String {
        fun taken(a: String) = Regex("(?m)^\\s*" + Regex.escape(a) + "\\s*=").containsMatchIn(toml)
        val base = if (lib.group.startsWith("androidx.")) "androidx-${lib.artifact}" else lib.artifact
        var a = base.lowercase()
        if (taken(a)) a = (lib.group.substringAfterLast('.') + "-" + lib.artifact).lowercase()
        var n = 2
        val start = a
        while (taken(a)) { a = "$start-$n"; n++ }
        return a
    }

    private fun insertIntoLibraries(toml: String, entry: String): String {
        val header = Regex("(?m)^\\[libraries\\]\\s*$").find(toml)
            ?: return toml.trimEnd() + "\n\n[libraries]\n$entry\n"
        val next = Regex("(?m)^\\[").find(toml, header.range.last + 1)
        val cut = next?.range?.first ?: toml.length
        val head = toml.substring(0, cut).trimEnd()
        val tail = toml.substring(cut)
        return head + "\n" + entry + "\n" + (if (tail.isEmpty()) "" else "\n$tail")
    }
}
