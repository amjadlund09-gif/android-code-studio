package com.example.mynative.androidide

import java.io.File

/**
 * One-tap fixes for "Could not resolve / find group:artifact:version" sync errors:
 *  - bump the library to its latest version (inline notation or libs.versions.toml)
 *  - make sure google() + mavenCentral() (and jitpack for com.github.*) are in the repositories
 * Refresh & retry (--refresh-dependencies) is done by ProjectActivity via GradleTuner.
 */
object SyncFixer {

    data class Failure(val group: String, val artifact: String, val version: String)

    private val depRe = Regex("Could not (?:resolve|find|download) ([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-+]+)")
    private val problemRe = Regex(
        "Could not (?:resolve|find|download)|No cached version|Cannot resolve external dependency",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeDependencyProblem(output: String) = problemRe.containsMatchIn(output)

    /** First "Could not find/resolve g:a:v" in the output, or null. */
    fun parse(output: String): Failure? {
        for (m in depRe.findAll(output)) {
            val v = m.groupValues[3].trimEnd('.')
            if (v.isEmpty()) continue
            return Failure(m.groupValues[1], m.groupValues[2], v)
        }
        return null
    }

    // ---- version bump ---------------------------------------------------------------------------

    /** Returns "Updated ..." when files changed, otherwise an explanation. */
    fun updateVersion(workDir: File, g: String, a: String, newV: String): String {
        val inline = Regex("(" + Regex.escape(g) + ":" + Regex.escape(a) + ":)([A-Za-z0-9_.\\-+]+)")
        val changed = mutableListOf<String>()
        for (f in GradleTuner.syncFiles(workDir)) {
            val old = runCatching { f.readText() }.getOrNull() ?: continue
            var text = inline.replace(old) { m -> m.groupValues[1] + newV }
            if (f.name.endsWith(".toml")) text = updateTomlParts(text, g, a, newV)
            if (text != old) {
                f.writeText(text)
                changed.add(f.relativeTo(workDir).path)
            }
        }
        return if (changed.isEmpty())
            "$g:$a not found in your build files (maybe it comes in through another library)"
        else
            "Updated $g:$a to $newV in " + changed.joinToString(", ")
    }

    /** libs.versions.toml entries written as { group = "g", name = "a", version... } or { module = "g:a", ... }. */
    private fun updateTomlParts(toml: String, g: String, a: String, newV: String): String {
        val eg = Regex.escape(g)
        val ea = Regex.escape(a)
        val lineRe = Regex(
            "(?m)^[^\\n]*(?:group\\s*=\\s*\"" + eg + "\"[^\\n]*name\\s*=\\s*\"" + ea + "\"|module\\s*=\\s*\"" + eg + ":" + ea + "\")[^\\n]*$"
        )
        val refs = mutableListOf<String>()
        var out = lineRe.replace(toml) { m ->
            val line = m.value
            val ref = Regex("version\\.ref\\s*=\\s*\"([^\"]+)\"").find(line)
            if (ref != null) {
                refs.add(ref.groupValues[1])
                line
            } else {
                Regex("(version\\s*=\\s*\")[^\"]+(\")").replace(line) { v -> v.groupValues[1] + newV + v.groupValues[2] }
            }
        }
        // version.ref = "x"  ->  update the [versions] entry  x = "..."
        for (r in refs.distinct()) {
            out = Regex("(?m)^(\\s*" + Regex.escape(r) + "\\s*=\\s*\")[^\"]*(\")")
                .replace(out) { m -> m.groupValues[1] + newV + m.groupValues[2] }
        }
        return out
    }

    // ---- repositories ---------------------------------------------------------------------------

    /**
     * Adds missing google() / mavenCentral() (+ jitpack) to the repositories block of settings.gradle(.kts)
     * (dependencyResolutionManagement) or the root build file (allprojects). Returns a message that
     * starts with "Added" when a file changed, another message when nothing needed changing, null if no block was found.
     */
    fun ensureRepos(workDir: File, jitpack: Boolean): String? {
        val candidates = listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")
            .map { File(workDir, it) }.filter { it.isFile }
        for (f in candidates) {
            val r = addToRepos(f, jitpack)
            if (r != null) return r
        }
        return null
    }

    /**
     * "Plugin [id: 'com.android.application'] was not found": makes sure pluginManagement { repositories { } }
     * in settings.gradle(.kts) has gradlePluginPortal(), google() and mavenCentral() (creates the block
     * when it is missing). Returns a message starting with "Added" when the file changed, another message
     * when nothing needed changing, null when there is no settings file.
     */
    fun ensurePluginRepos(workDir: File): String? {
        val f = listOf("settings.gradle.kts", "settings.gradle").map { File(workDir, it) }.firstOrNull { it.isFile } ?: return null
        val text = f.readText()
        val wanted = listOf("gradlePluginPortal()", "google()", "mavenCentral()")
        val pm = Regex("pluginManagement\\s*\\{").find(text)
        if (pm == null) {
            if (text.trimStart().startsWith("import ")) return null      // cannot safely put a block before imports
            val block = "pluginManagement {\n    repositories {\n" + wanted.joinToString("") { "        $it\n" } + "    }\n}\n\n"
            f.writeText(block + text)
            return "Added pluginManagement { repositories } to ${f.name}"
        }
        val pmOpen = pm.range.last
        val pmClose = matchBrace(text, pmOpen)
        if (pmClose < 0) return null
        val rep = Regex("repositories\\s*\\{").find(text, pmOpen)
        if (rep == null || rep.range.last > pmClose) {
            val ins = "\n    repositories {\n" + wanted.joinToString("") { "        $it\n" } + "    }"
            f.writeText(text.substring(0, pmOpen + 1) + ins + text.substring(pmOpen + 1))
            return "Added repositories to pluginManagement in ${f.name}"
        }
        val open = rep.range.last
        val close = matchBrace(text, open)
        if (close < 0) return null
        val body = text.substring(open + 1, close)
        val add = wanted.filter { !body.contains(it) }
        if (add.isEmpty())
            return "pluginManagement already has gradlePluginPortal(), google() and mavenCentral(): check the plugin id / version instead"
        val ins = add.joinToString("") { "\n        $it" }
        f.writeText(text.substring(0, open + 1) + ins + text.substring(open + 1))
        return "Added ${add.joinToString(", ")} to pluginManagement in ${f.name}"
    }

    private fun addToRepos(f: File, jitpack: Boolean): String? {
        val text = f.readText()
        val kts = f.name.endsWith(".kts")
        val dm = Regex("dependencyResolutionManagement\\s*\\{").find(text)
            ?: Regex("allprojects\\s*\\{").find(text)
            ?: return null
        val rep = Regex("repositories\\s*\\{").find(text, dm.range.last) ?: return null
        val open = rep.range.last
        val close = matchBrace(text, open)
        if (close < 0) return null
        val body = text.substring(open + 1, close)

        val add = mutableListOf<String>()
        if (!body.contains("google()")) add.add("google()")
        if (!body.contains("mavenCentral()")) add.add("mavenCentral()")
        if (jitpack && !body.contains("jitpack.io")) {
            add.add(if (kts) "maven { url = uri(\"https://jitpack.io\") }" else "maven { url 'https://jitpack.io' }")
        }
        if (add.isEmpty())
            return "Repositories in ${f.name} already have google() + mavenCentral(): check the library name / version instead"
        val ins = add.joinToString("") { "\n        $it" }
        f.writeText(text.substring(0, open + 1) + ins + text.substring(open + 1))
        return "Added ${add.joinToString(", ")} to ${f.name}"
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
