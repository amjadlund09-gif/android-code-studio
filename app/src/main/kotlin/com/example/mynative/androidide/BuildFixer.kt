package com.example.mynative.androidide

import java.io.File

/**
 * One-tap fixes for the most common (non "Could not resolve") Gradle failures. SyncFixer keeps the
 * dependency-resolution fixes; this file handles:
 *  - Namespace not specified            -> adds namespace to the module's build file
 *  - minSdk / compileSdk too low        -> raises it to what the library asks for
 *  - Duplicate class                    -> Kotlin BOM, or bump the older of the two libraries
 *  - Java version problems              -> Gradle wrapper bump / open the JDK manager
 *  - Compose compiler vs Kotlin version -> sets the Kotlin version the compiler asks for
 *  - missing Android platform           -> opens the SDK manager
 *  - AAPT "failed to write ... to archive" -> cleans the build folders and retries
 * Every EDIT fix only touches build files and returns a message starting with "Updated" / "Added"
 * when something changed (ProjectActivity then reloads the tabs and re-syncs).
 */
object BuildFixer {

    enum class Kind { EDIT, OPEN_SDK, LATEST, FLUTTER_SDK }

    class Fix(
        val label: String,
        val kind: Kind = Kind.EDIT,
        val failure: SyncFixer.Failure? = null,          // Kind.LATEST
        val apply: (File) -> String = { "" }              // Kind.EDIT: workDir -> message
    )

    // ---- detection ------------------------------------------------------------------------------

    fun detect(output: String, workDir: File): List<Fix> {
        val out = ArrayList<Fix>()

        // 0. Flutter SDK missing (flutter.sdk not in local.properties / folder gone)
        if (output.contains("flutter.sdk not set", ignoreCase = true) || output.contains("flutter_tools/gradle", ignoreCase = true) &&
            output.contains("does not exist", ignoreCase = true) || output.contains("dev.flutter.flutter-plugin-loader") && output.contains("was not found")) {
            out.add(Fix("📁 Set Flutter SDK", Kind.FLUTTER_SDK))
        }

        // 1. Namespace not specified (AGP 8+)
        if (output.contains("Namespace not specified", ignoreCase = true)) {
            val path = Regex("build file:\\s*(\\S+build\\.gradle(?:\\.kts)?)").find(output)?.groupValues?.get(1)
            val f = path?.let { File(it) }?.takeIf { it.isFile && isInside(workDir, it) }
            out.add(Fix("＋ namespace" + (f?.parentFile?.name?.let { " ($it)" } ?: "")) { wd ->
                val target = f ?: findAppBuildFile(wd)
                if (target == null) "No module build file found" else addNamespace(wd, target)
            })
        }

        // 2. minSdk too low for a library
        Regex("minSdkVersion\\s+(\\d+)\\s+cannot be smaller than version\\s+(\\d+)\\s+declared in library").find(output)?.let {
            val need = it.groupValues[2].toInt()
            out.add(Fix("↑ minSdk $need") { wd -> raiseSdk(wd, "minSdk", need) })
        }

        // 3. compileSdk too low for a library
        Regex("compile against version\\s+(\\d+)\\s+or later").find(output)?.let {
            val need = it.groupValues[1].toInt()
            out.add(Fix("↑ compileSdk $need") { wd ->
                val m = raiseSdk(wd, "compileSdk", need)
                if (m.startsWith("Updated")) "$m. Install platform $need in the SDK manager if it is missing" else m
            })
        }

        // 4. Android platform not installed
        if (Regex("Failed to find (?:target with hash string|Platform SDK)|platforms;android-\\d+|SDK location not found", RegexOption.IGNORE_CASE)
                .containsMatchIn(output)) {
            out.add(Fix("📦 SDK manager", Kind.OPEN_SDK))
        }

        // 5. Java version problems
        Regex("Unsupported class file major version\\s+(\\d+)").find(output)?.let {
            val java = it.groupValues[1].toInt() - 44
            val g = CompatChecker.minGradleForJdk(java)
            if (g != null) out.add(Fix("⬆ Gradle $g (Java $java)") { wd -> setWrapperVersion(wd, g) })
            out.add(Fix("☕ JDK manager", Kind.OPEN_SDK))
        }
        Regex("Unsupported Java\\.\\s+Your build is currently configured to use Java\\s+(\\d+)").find(output)?.let {
            val java = it.groupValues[1].toInt()
            val g = CompatChecker.minGradleForJdk(java)
            if (g != null) out.add(Fix("⬆ Gradle $g (Java $java)") { wd -> setWrapperVersion(wd, g) })
        }
        Regex("requires Java\\s+(\\d+)|at least JVM runtime version\\s+(\\d+)|invalid (?:source|target) release", RegexOption.IGNORE_CASE)
            .find(output)?.let {
                val v = it.groupValues.drop(1).firstOrNull { s -> s.isNotEmpty() }
                out.add(Fix("☕ Install JDK" + (v?.let { n -> " $n" } ?: ""), Kind.OPEN_SDK))
            }

        // 6. Duplicate class
        Regex("Duplicate class \\S+ found in modules ([^\\n]+)").find(output)?.let { m ->
            val libs = Regex("\\(([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-+]+)\\)")
                .findAll(m.groupValues[1]).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                .distinct().toList()
            if (libs.size >= 2) {
                val kotlin = libs.filter { it.first == "org.jetbrains.kotlin" }
                if (kotlin.size >= 2) {
                    val newest = kotlin.maxWithOrNull(Comparator { a, b -> CompatChecker.cmp(a.third, b.third) })!!.third
                    out.add(Fix("＋ Kotlin BOM $newest") { wd -> addKotlinBom(wd, newest) })
                }
                val oldest = libs.minWithOrNull(Comparator { a, b -> CompatChecker.cmp(a.third, b.third) })!!
                out.add(Fix("⬆ Latest ${oldest.second}", Kind.LATEST, SyncFixer.Failure(oldest.first, oldest.second, oldest.third)))
            }
        }

        // 7. Compose compiler needs a different Kotlin version
        Regex("version \\(([\\d.]+)\\) of the Compose Compiler requires Kotlin version ([\\d.]+)").find(output)?.let {
            val kv = it.groupValues[2]
            out.add(Fix("↻ Kotlin $kv") { wd -> setKotlinVersion(wd, kv) })
        }

        // 8. AAPT could not write the resource archive (full storage / half-written build files)
        if (looksLikeCorruptBuild(output)) {
            out.add(Fix("🧹 Clean build & retry") { wd -> cleanBuildFolders(wd) })
        }

        return out
    }

    private val corruptBuildRx = Regex(
        "failed to write .{0,200}? to archive|No space left on device|Invalid entry name|failed to parse proto XML|\\.flat[: ]|failed to read file|file is corrupt",
        RegexOption.IGNORE_CASE
    )

    /** AAPT / resource files half-written (interrupted build, full storage): a clean build fixes it. */
    fun looksLikeCorruptBuild(output: String) = corruptBuildRx.containsMatchIn(output)

    private fun mb(bytes: Long) = "%.0f MB".format(bytes / 1048576.0)

    /** Deletes every module's build/ folder (not .cxx, not sources). Reports free space; re-runs only if there is room. */
    fun cleanBuildFolders(workDir: File): String {
        val modules = LinkedHashSet<File>()
        modules.add(workDir)
        GradleTuner.syncFiles(workDir).forEach { f -> f.parentFile?.let { modules.add(it) } }
        var freed = 0L
        for (m in modules) {
            val b = File(m, "build")
            if (b.isDirectory && isInside(workDir, b)) {
                freed += runCatching { b.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
                b.deleteRecursively()
            }
        }
        val free = workDir.usableSpace
        return if (free < 500L * 1024 * 1024) {
            "Low storage: only ${mb(free)} free on this drive. Free some space, then build again"
        } else {
            "Updated: cleaned build folders (${mb(freed)}), ${mb(free)} free"
        }
    }

    private fun isInside(root: File, f: File): Boolean =
        runCatching { f.canonicalPath.startsWith(root.canonicalPath + File.separator) }.getOrDefault(false)

    // ---- build file helpers ---------------------------------------------------------------------

    /** Module build files (not the root one). */
    private fun moduleBuildFiles(workDir: File): List<File> =
        GradleTuner.syncFiles(workDir).filter { it.name.startsWith("build.gradle") && it.parentFile != workDir }

    fun findAppBuildFile(workDir: File): File? {
        val mods = moduleBuildFiles(workDir)
        return mods.firstOrNull { runCatching { it.readText().contains("com.android.application") }.getOrDefault(false) }
            ?: mods.firstOrNull { it.parentFile?.name == "app" }
            ?: mods.firstOrNull()
    }

    private fun addNamespace(workDir: File, f: File): String {
        val text = f.readText()
        if (Regex("(?m)^\\s*namespace\\b").containsMatchIn(text))
            return "namespace is already set in ${f.name}: check the package name it declares"
        val manifest = File(f.parentFile, "src/main/AndroidManifest.xml")
        val pkg = runCatching { Regex("package\\s*=\\s*\"([^\"]+)\"").find(manifest.readText())?.groupValues?.get(1) }.getOrNull()
            ?: Regex("applicationId\\s*=?\\s*[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1)
            ?: return "Could not guess the package name: add  namespace \"your.package\"  to ${f.name} by hand"
        val open = Regex("(?m)^\\s*android\\s*\\{").find(text) ?: return "No android { } block in ${f.name}"
        val line = if (f.name.endsWith(".kts")) "namespace = \"$pkg\"" else "namespace '$pkg'"
        f.writeText(text.substring(0, open.range.last + 1) + "\n    $line" + text.substring(open.range.last + 1))
        return "Added namespace $pkg to ${f.relativeTo(workDir).path}"
    }

    /** minSdk / compileSdk: raise plain numeric values that are lower than [need]. */
    private fun raiseSdk(workDir: File, key: String, need: Int): String {
        val re = Regex("(\\b$key(?:Version)?\\s*=?\\s*)(\\d+)")
        val changed = mutableListOf<String>()
        for (f in moduleBuildFiles(workDir)) {
            val old = runCatching { f.readText() }.getOrNull() ?: continue
            val text = re.replace(old) { m -> if (m.groupValues[2].toInt() < need) m.groupValues[1] + need else m.value }
            if (text != old) { f.writeText(text); changed.add(f.relativeTo(workDir).path) }
        }
        return if (changed.isEmpty()) "No plain $key number found to change (maybe set in a variable or already high enough)"
        else "Updated $key to $need in " + changed.joinToString(", ")
    }

    fun setWrapperVersion(workDir: File, version: String): String {
        val f = File(workDir, "gradle/wrapper/gradle-wrapper.properties")
        if (!f.isFile) return "gradle-wrapper.properties not found"
        val old = f.readText()
        val text = Regex("gradle-[\\d.]+(?:-(?:rc|milestone)-\\d+)?-(bin|all)\\.zip").replace(old) { m ->
            "gradle-$version-${m.groupValues[1]}.zip"
        }
        if (text == old) return "Gradle wrapper is already $version (or its URL has an unknown format)"
        f.writeText(text)
        return "Updated Gradle wrapper to $version (it downloads on the next sync)"
    }

    private fun addKotlinBom(workDir: File, version: String): String {
        val f = findAppBuildFile(workDir) ?: return "No module build file found"
        val text = f.readText()
        if (text.contains("kotlin-bom")) return "kotlin-bom is already in ${f.name}: try ⬆ Latest on the older library"
        val open = Regex("(?m)^\\s*dependencies\\s*\\{").find(text) ?: return "No dependencies { } block in ${f.name}"
        val line = if (f.name.endsWith(".kts")) "implementation(platform(\"org.jetbrains.kotlin:kotlin-bom:$version\"))"
        else "implementation platform('org.jetbrains.kotlin:kotlin-bom:$version')"
        f.writeText(text.substring(0, open.range.last + 1) + "\n    $line" + text.substring(open.range.last + 1))
        return "Added kotlin-bom:$version to ${f.relativeTo(workDir).path}"
    }

    /** Kotlin plugin / catalog version, in every notation the templates and Android Studio use. */
    private fun setKotlinVersion(workDir: File, v: String): String {
        val res = listOf(
            Regex("(id\\s*\\(?\\s*['\"]org\\.jetbrains\\.kotlin\\.[\\w.\\-]+['\"]\\s*\\)?\\s*version\\s*\\(?\\s*['\"])[^'\"]+(['\"])"),
            Regex("(kotlin\\(['\"][\\w.\\-]+['\"]\\)\\s*version\\s*['\"])[^'\"]+(['\"])"),
            Regex("(org\\.jetbrains\\.kotlin:kotlin-gradle-plugin:)[\\w.\\-]+()"),
            Regex("(kotlin_version\\s*=\\s*['\"])[^'\"]+(['\"])"),
            Regex("(?m)^(\\s*kotlin(?:Version)?\\s*=\\s*\")[^\"]*(\")")
        )
        val changed = mutableListOf<String>()
        for (f in GradleTuner.syncFiles(workDir)) {
            val old = runCatching { f.readText() }.getOrNull() ?: continue
            var text = old
            for (re in res) text = re.replace(text) { m -> m.groupValues[1] + v + m.groupValues[2] }
            if (text != old) { f.writeText(text); changed.add(f.relativeTo(workDir).path) }
        }
        return if (changed.isEmpty()) "Kotlin version not found in your build files"
        else "Updated Kotlin to $v in " + changed.joinToString(", ")
    }
}
