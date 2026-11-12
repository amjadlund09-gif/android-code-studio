package com.example.mynative.androidide

import android.content.Context
import java.io.File

/**
 * Build variants of the app module: buildTypes x productFlavors, read from its build.gradle(.kts).
 * The chosen variant (and APK / AAB) is remembered per project and decides which Gradle task
 * ▶ (assembleXxx / bundleXxx) and ✓ (compileXxxSources) run.
 */
object BuildVariants {

    class Variant(val name: String, val buildType: String, val flavor: String) {
        /** FreeDebug (task suffix). */
        val cap: String get() = name.replaceFirstChar { it.uppercaseChar() }
        /** folder under outputs/apk: "free/debug" or just "debug". */
        val apkDir: String get() = if (flavor.isEmpty()) buildType else "$flavor/$buildType"
    }

    class Info(val buildFile: File, val variants: List<Variant>)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("build_variants", Context.MODE_PRIVATE)

    fun selected(ctx: Context, workDir: File): String? = prefs(ctx).getString("sel:" + workDir.path, null)
    fun setSelected(ctx: Context, workDir: File, name: String) =
        prefs(ctx).edit().putString("sel:" + workDir.path, name).apply()

    /** true = build an AAB (Play Store bundle) instead of an APK. */
    fun bundle(ctx: Context, workDir: File): Boolean = prefs(ctx).getBoolean("aab:" + workDir.path, false)
    fun setBundle(ctx: Context, workDir: File, v: Boolean) = prefs(ctx).edit().putBoolean("aab:" + workDir.path, v).apply()

    /** The variant to build now: the saved one if it still exists, else debug, else the first. null = not an Android app. */
    fun current(ctx: Context, workDir: File): Variant? {
        val info = detect(workDir) ?: return null
        val sel = selected(ctx, workDir)
        return info.variants.firstOrNull { it.name == sel }
            ?: info.variants.firstOrNull { it.buildType == "debug" }
            ?: info.variants.firstOrNull()
    }

    fun assembleTask(v: Variant) = "assemble${v.cap}"
    fun bundleTask(v: Variant) = "bundle${v.cap}"
    fun checkTask(v: Variant) = "compile${v.cap}Sources"

    // ---- parsing --------------------------------------------------------------------------------

    fun detect(workDir: File): Info? {
        val candidates = listOfNotNull(BuildFixer.findAppBuildFile(workDir)) +
            listOf("build.gradle.kts", "build.gradle").map { File(workDir, it) }
        val f = candidates.firstOrNull { it.isFile && runCatching { it.readText().contains("com.android.application") }.getOrDefault(false) }
            ?: return null
        val text = stripComments(f.readText())

        val customTypes = block(text, "buildTypes")?.let { childBlocks(it).map { c -> c.first } } ?: emptyList()
        val buildTypes = (listOf("debug", "release") + customTypes).distinct()

        val flavorBlocks = block(text, "productFlavors")?.let { childBlocks(it) } ?: emptyList()
        val dimOrder = Regex("flavorDimensions\\s*(?:\\+=|=)?\\s*\\(?\\s*\\[?([^\\n\\])]*)").find(text)
            ?.groupValues?.get(1)?.let { s -> Regex("[\"']([\\w.\\-]+)[\"']").findAll(s).map { it.groupValues[1] }.toList() }
            ?: emptyList()

        // dimension -> flavors
        val groups = LinkedHashMap<String, MutableList<String>>()
        for ((name, body) in flavorBlocks) {
            val dim = Regex("dimension\\s*=?\\s*[\"']([\\w.\\-]+)[\"']").find(body)?.groupValues?.get(1)
                ?: dimOrder.firstOrNull() ?: ""
            groups.getOrPut(dim) { mutableListOf() }.add(name)
        }
        val orderedDims = groups.keys.sortedBy { d -> dimOrder.indexOf(d).let { if (it < 0) Int.MAX_VALUE else it } }

        // cartesian product, one flavor per dimension: demo + free -> "demoFree"
        var combos: List<String> = listOf("")
        for (d in orderedDims) {
            val flavors = groups[d].orEmpty()
            combos = combos.flatMap { prefix -> flavors.map { fl -> if (prefix.isEmpty()) fl else prefix + fl.replaceFirstChar { c -> c.uppercaseChar() } } }
            if (combos.size > 24) { combos = combos.take(24); break }
        }

        val out = ArrayList<Variant>()
        for (combo in combos) for (bt in buildTypes) {
            val name = if (combo.isEmpty()) bt else combo + bt.replaceFirstChar { it.uppercaseChar() }
            out.add(Variant(name, bt, combo))
        }
        return Info(f, out.take(60))
    }

    private fun stripComments(t: String) =
        t.replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)^\\s*//.*$"), "")

    /** Body of the first `name { ... }` block, or null. */
    private fun block(text: String, name: String): String? {
        val m = Regex("\\b$name\\s*\\{").find(text) ?: return null
        val open = m.range.last
        val close = matchBrace(text, open)
        return if (close < 0) null else text.substring(open + 1, close)
    }

    /** Direct child blocks of [body]: debug { } / create("x") { } / val x by creating { } -> name to inner text. */
    private fun childBlocks(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var stmtStart = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '{') {
                val close = matchBrace(body, i)
                if (close < 0) break
                nameOf(body.substring(stmtStart, i))?.let { out.add(it to body.substring(i + 1, close)) }
                i = close + 1
                stmtStart = i
                continue
            }
            if (c == '\n' || c == ';') stmtStart = i + 1
            i++
        }
        return out
    }

    private fun nameOf(stmt: String): String? {
        val s = stmt.trim()
        if (s.isEmpty()) return null
        Regex("val\\s+(\\w+)\\s+by\\s+(?:getting|creating)").find(s)?.let { return it.groupValues[1] }
        Regex("[\"']([\\w.\\-]+)[\"']").find(s)?.let { return it.groupValues[1] }
        return Regex("(\\w+)\\s*$").find(s)?.groupValues?.get(1)
    }

    private fun matchBrace(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }
}
