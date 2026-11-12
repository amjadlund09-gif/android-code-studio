package com.example.mynative.androidide

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.util.zip.CRC32

/**
 * Everything that makes Gradle sync / build faster on a phone, in one place.
 * Nothing here edits the user's project: all tuning goes in as command-line flags
 * (-D / -P override gradle.properties), so old projects get the speed-up too.
 */
object GradleTuner {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gradle_tuner", Context.MODE_PRIVATE)

    // ---- settings -------------------------------------------------------------------------------

    /** Gradle daemon + configuration cache. Much faster 2nd build, but keeps ~1 GB of RAM busy. */
    fun fastMode(ctx: Context) = prefs(ctx).getBoolean("fast", false)
    fun setFastMode(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("fast", v).apply()

    /** Sync automatically a moment after build.gradle / settings / libs.versions.toml is saved. */
    fun autoSync(ctx: Context) = prefs(ctx).getBoolean("auto_sync", false)
    fun setAutoSync(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("auto_sync", v).apply()

    /** Try `--offline` first; retry online only if something is missing from the cache. */
    fun offlineFirst(ctx: Context) = prefs(ctx).getBoolean("offline_first", true)
    /** Fresh install / first project: nothing cached yet, so `--offline` would only waste ~10s before failing. */
    fun cacheLooksEmpty(ctx: Context): Boolean {
        val dir = File(SdkRepository.homeDir(ctx), ".gradle/caches/modules-2/files-2.1")
        return (dir.list()?.size ?: 0) < 20
    }

    fun setOfflineFirst(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("offline_first", v).apply()

    /** Disable lint + unit-test tasks when the plain `build` task is used. */
    fun skipLint(ctx: Context) = prefs(ctx).getBoolean("skip_lint", true)
    fun setSkipLint(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("skip_lint", v).apply()

    /** Build (▶) runs the needed sync in the SAME Gradle run, instead of leaving a stale "sync needed" state. */
    fun syncBeforeBuild(ctx: Context) = prefs(ctx).getBoolean("sync_before_build", true)
    fun setSyncBeforeBuild(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("sync_before_build", v).apply()

    /** After a successful assembleDebug: install the APK, launch it and open its log. */
    fun autoRun(ctx: Context) = prefs(ctx).getBoolean("auto_run", false)
    fun setAutoRun(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("auto_run", v).apply()
    /** After a successful assemble: open the installer by itself (default on). */
    fun autoInstall(ctx: Context) = prefs(ctx).getBoolean("auto_install", true)
    fun setAutoInstall(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("auto_install", v).apply()
    fun autoRunRoot(ctx: Context) = prefs(ctx).getBoolean("auto_run_root", false)
    fun setAutoRunRoot(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("auto_run_root", v).apply()

    // ---- device based numbers -------------------------------------------------------------------

    /** Memory profiles: a run that dies of OOM / gets killed is retried once with another profile. */
    const val MEM_NORMAL = 0
    /** Out of memory inside Gradle: more heap, one worker. */
    const val MEM_MORE = 1
    /** Process killed by Android (exit 137): less heap, one worker, no parallel. */
    const val MEM_LESS = 2

    /** Gradle heap = 1/4 of the phone's RAM, between 768 MB and 3 GB (other profiles: see MEM_*). */
    fun heapMb(ctx: Context, mode: Int = MEM_NORMAL): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = (mi.totalMem / (1024L * 1024L)).toInt()
        return when (mode) {
            MEM_MORE -> (totalMb * 2 / 5).coerceIn(1024, 4096)
            MEM_LESS -> (totalMb / 6).coerceIn(512, 1536)
            else -> (totalMb / 4).coerceIn(768, 3072)
        }
    }

    /** True when an internet connection is available (assumes yes if the check itself is not possible). */
    fun hasInternet(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true
    }

    fun cores(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    private fun jvmArgs(ctx: Context, mode: Int = MEM_NORMAL) =
        "-Xmx${heapMb(ctx, mode)}m -XX:MaxMetaspaceSize=512m -XX:+IgnoreUnrecognizedVMOptions " +
            "-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Dfile.encoding=UTF-8"

    fun summary(ctx: Context) = "Gradle heap ${heapMb(ctx)} MB · ${cores()} workers · ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"

    // ---- command line ---------------------------------------------------------------------------

    /**
     * Full shell command for one Gradle run.
     * @param isSync  true -> resolve every dependency (ideResolveDeps) instead of just `help`
     * @param offline true -> add --offline (caller retries online if the cache was incomplete)
     * @param refresh true -> --refresh-dependencies (re-check remote metadata, drops "not found" cache)
     * @param preSync sync task(s) (e.g. "ideResolveDeps" or ":app:ideResolveDeps") to run in the same
     *                Gradle invocation before [task]: one JVM start + one configuration for sync + build
     */
    fun command(
        ctx: Context, workDir: File, task: String, isSync: Boolean, offline: Boolean,
        refresh: Boolean = false, preSync: String? = null, memMode: Int = MEM_NORMAL
    ): String {
        val withSync = isSync || preSync != null
        val fast = fastMode(ctx)
        val cores = if (memMode == MEM_NORMAL) cores() else 1
        val (syncInit, buildInit) = ensureInitScripts(ctx)

        val flags = StringBuilder()
        flags.append("-Dorg.gradle.jvmargs='${jvmArgs(ctx, memMode)}' ")
        flags.append("-Dorg.gradle.daemon=$fast ")
        flags.append("-Dorg.gradle.caching=true ")
        flags.append("-Dorg.gradle.parallel=${cores > 2} ")
        flags.append("-Dorg.gradle.workers.max=$cores ")
        flags.append("-Dorg.gradle.vfs.watch=false ")
        flags.append("-Pkotlin.compiler.execution.strategy=in-process ")
        if (Aapt2Fix.ready(ctx)) flags.append("-Pandroid.aapt2FromMavenOverride=${Aapt2Fix.binary(ctx).path} ")
        flags.append("-Pkotlin.incremental=true ")
        if (!offline) {
            // flaky phone networks: longer timeouts + more retries (with back-off) per artifact download
            flags.append("-Dorg.gradle.internal.http.connectionTimeout=60000 ")
            flags.append("-Dorg.gradle.internal.http.socketTimeout=60000 ")
            flags.append("-Dorg.gradle.internal.repository.max.tentatives=5 ")
            flags.append("-Dorg.gradle.internal.repository.initial.backoff=500 ")
        }
        if (fast) {
            flags.append("-Dorg.gradle.daemon.idletimeout=600000 ")
            if (!withSync) {   // ideResolveDeps resolves in doLast -> not configuration-cache compatible
                flags.append("-Dorg.gradle.configuration-cache=true ")
                flags.append("-Dorg.gradle.configuration-cache.problems=warn ")
            }
        }
        // NOTE: do NOT pass -Pandroid.injected.build.abi here. AGP treats it as an "IDE deploy build" and writes
        // android:testOnly="true" into the manifest, and Android then refuses to install the APK with
        // "App not installed as package appears to be invalid".
        if (task.startsWith("assemble") || task.startsWith("bundle")) {
            flags.append("-Pandroid.injected.testOnly=false ")
        }

        val sb = StringBuilder()
        sb.append("cd \"${workDir.path}\" && ")
        sb.append("if [ -z \"\$JAVA_HOME\" ] && ! command -v java >/dev/null 2>&1; then ")
        sb.append("echo 'ERROR: JDK not found. Gradle needs Java 17+.'; ")
        sb.append("echo 'Put a JDK in: ${TermEnvironment.home(ctx).path}/jdk (bin/java must exist)'; exit 3; fi; ")
        // Without a daemon, giving the client JVM the same flags lets Gradle build in-process
        // instead of forking a second single-use JVM.
        if (!fast) sb.append("export GRADLE_OPTS='${jvmArgs(ctx, memMode)}'; ")
        sb.append("sh ./gradlew --console=plain ")
        if (offline) sb.append("--offline ")
        if (refresh) sb.append("--refresh-dependencies ")
        if (withSync) sb.append("-I '${syncInit.path}' --no-configuration-cache ")
        if (!isSync && skipLint(ctx) && task == "build") sb.append("-I '${buildInit.path}' ")
        // release / custom build types: sign with the keystore from the signing dialog (if one was set up)
        if (!isSync && Signing.enabled(ctx) && (task.startsWith("assemble") || task.startsWith("bundle")) && !task.endsWith("Debug"))
            sb.append("-I '${Signing.initScript(ctx).path}' ")
        sb.append(flags)
        if (preSync != null) sb.append(preSync).append(' ')
        sb.append(task)
        return sb.toString()
    }

    /** Output that means "the offline cache did not have everything" -> worth an online retry. */
    private val offlineMiss = Regex(
        "no cached version|offline mode|could not resolve|cannot resolve|could not download|no cached",
        RegexOption.IGNORE_CASE
    )
    fun looksLikeOfflineMiss(output: String) = offlineMiss.containsMatchIn(output)

    /** A per-module sync named a project/task Gradle does not know -> redo it as a full sync. */
    private val missingProject = Regex(
        "not found in root project|Task 'ideResolveDeps' not found|Cannot locate tasks that match|Project '[^']*' not found",
        RegexOption.IGNORE_CASE
    )
    fun looksLikeMissingProject(output: String) = missingProject.containsMatchIn(output)

    /** Network hiccup (timeout, reset, DNS, 5xx): the same run usually works a moment later. */
    private val transientNet = Regex(
        "Read timed out|Connect timed out|connection timed out|Connection reset|Connection refused|" +
            "UnknownHostException|Unknown host|SSLHandshakeException|SSLException|Premature end of Content-Length|" +
            "Remote host terminated|Broken pipe|Network is unreachable|Software caused connection abort|" +
            "status code 5\\d\\d|status code 429|Unexpected end of file from server",
        RegexOption.IGNORE_CASE
    )
    fun looksLikeTransientNetwork(output: String) = transientNet.containsMatchIn(output)

    /** Gradle itself ran out of memory (or its daemon vanished). */
    private val oomRe = Regex(
        "java\\.lang\\.OutOfMemoryError|Java heap space|GC overhead limit|Gradle build daemon disappeared",
        RegexOption.IGNORE_CASE
    )
    fun looksLikeOom(output: String) = oomRe.containsMatchIn(output)

    /** "Plugin [id: 'com.android.application'] was not found in any of the following sources" */
    private val pluginNotFound = Regex(
        "Plugin \\[id: '[^']+'[^\\]]*\\] was not found|Plugin '[^']+' not found|plugin with id '[^']+' not found",
        RegexOption.IGNORE_CASE
    )
    fun looksLikePluginNotFound(output: String) = pluginNotFound.containsMatchIn(output)

    /** Stops all Gradle daemons (used when the project closes and from the settings dialog). */
    fun stopDaemons(ctx: Context, workDir: File) {
        val app = ctx.applicationContext
        Thread {
            ShellRunner.runSilently(app, "cd \"${workDir.path}\" && sh ./gradlew --stop", workDir)
        }.start()
    }

    // ---- init scripts ---------------------------------------------------------------------------

    private val SYNC_INIT = """
allprojects { proj ->
    proj.tasks.register('ideResolveDeps') {
        doLast {
            def wanted = ['debugCompileClasspath', 'debugRuntimeClasspath', 'compileClasspath', 'runtimeClasspath']
            int n = 0
            proj.configurations.each { c ->
                if (wanted.contains(c.name) && c.canBeResolved) {
                    c.resolvedConfiguration.rethrowFailure()
                    n++
                }
            }
            println '[ide-sync] ' + proj.path + ': ' + n + ' classpath(s) resolved'
        }
    }
}
""".trimStart()

    private val BUILD_INIT = """
allprojects { proj ->
    proj.tasks.configureEach { t ->
        def n = t.name
        if (n == 'lint' || n.startsWith('lint') || n == 'test' || n.endsWith('UnitTest')) {
            t.enabled = false
        }
    }
}
""".trimStart()

    private fun ensureInitScripts(ctx: Context): Pair<File, File> {
        val dir = File(TermEnvironment.home(ctx), ".gradle").apply { mkdirs() }
        val sync = File(dir, "ide-sync.init.gradle")
        val build = File(dir, "ide-build.init.gradle")
        if (!sync.isFile || sync.readText() != SYNC_INIT) sync.writeText(SYNC_INIT)
        if (!build.isFile || build.readText() != BUILD_INIT) build.writeText(BUILD_INIT)
        return sync to build
    }

    // ---- local.properties -----------------------------------------------------------------------

    /** Keeps sdk.dir correct even if the SDK moved; leaves every other line alone. */
    /**
     * Records the standard Android SDK license acceptances in <sdk>/licenses so Gradle (AGP) may
     * download missing platforms / build-tools itself instead of failing with
     * "licences have not been accepted". Only adds missing hashes; never removes any.
     */
    fun ensureLicenses(ctx: Context) {
        val dir = File(SdkRepository.sdkRoot(ctx), "licenses")
        val wanted = mapOf(
            "android-sdk-license" to listOf(
                "24333f8a63b6825ea9c5514f83c2829b004d1fee",
                "d56f5187479451eabf01fb78af6dfcb131a6481e",
                "8933bad161af4178b1185d1a37fbf41ea5269c55"
            ),
            "android-sdk-preview-license" to listOf("84831b9409646a918e30573bab4c9c91346d8abd")
        )
        dir.mkdirs()
        for ((name, hashes) in wanted) {
            val f = File(dir, name)
            val have = if (f.exists()) f.readLines().map { it.trim() }.toSet() else emptySet()
            val missing = hashes.filter { it !in have }
            if (missing.isEmpty()) continue
            val prefix = if (f.exists() && f.length() > 0) "" else "\n"
            f.appendText(prefix + missing.joinToString("\n") + "\n")
        }
    }

    fun ensureLocalProperties(ctx: Context, workDir: File) {
        runCatching { ensureLicenses(ctx) }
        setProperty(workDir, "sdk.dir", SdkRepository.sdkRoot(ctx).path)
        // Flutter projects: settings.gradle reads flutter.sdk from local.properties
        if (usesFlutter(workDir)) {
            val cur = readProperty(workDir, "flutter.sdk")
            if (cur == null || !isFlutterSdk(File(cur))) findFlutterSdk(ctx)?.let { setProperty(workDir, "flutter.sdk", it.path) }
        }
    }

    fun usesFlutter(workDir: File) = listOf("settings.gradle", "settings.gradle.kts").map { File(workDir, it) }
        .any { it.isFile && runCatching { it.readText().contains("flutter") }.getOrDefault(false) }

    fun isFlutterSdk(d: File) = File(d, "bin/flutter").exists() && File(d, "packages/flutter_tools/gradle").isDirectory

    /** Looks for an installed Flutter SDK (Termux home, app files, sdcard). */
    fun findFlutterSdk(ctx: Context): File? {
        val home = runCatching { TermuxEnv.home(ctx) }.getOrNull()
        val c = listOfNotNull(
            home?.let { File(it, "flutter") }, home?.let { File(it, "development/flutter") }, home?.let { File(it, "sdk/flutter") },
            File(TermuxEnv.prefix(ctx), "opt/flutter"), File(ctx.filesDir, "flutter"), File(ctx.filesDir, "home/flutter"),
            File("/storage/emulated/0/flutter"), File("/sdcard/flutter")
        )
        return c.firstOrNull { isFlutterSdk(it) }
    }

    fun readProperty(workDir: File, key: String): String? =
        File(workDir, "local.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.trimStart().startsWith("$key=") }?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }

    fun setProperty(workDir: File, key: String, value: String) {
        val f = File(workDir, "local.properties")
        val want = "$key=$value"
        if (!f.exists()) { f.writeText(want + "\n"); return }
        val lines = f.readLines()
        val idx = lines.indexOfFirst { it.trimStart().startsWith("$key=") || it.trimStart().startsWith("$key ") }
        val out = when {
            idx < 0 -> lines + want
            lines[idx].trim() == want -> return
            else -> lines.toMutableList().also { it[idx] = want }
        }
        f.writeText(out.joinToString("\n") + "\n")
    }

    // ---- "sync needed" tracking -----------------------------------------------------------------

    /**
     * Gradle path -> folder (relative to the project) of every module: the `include` lines of
     * settings.gradle(.kts) (nested ones too: ':feature:login' -> feature/login), plus top-level
     * folders that have a build file.
     */
    fun moduleDirs(workDir: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun hasBuild(dir: String) = File(workDir, "$dir/build.gradle").isFile || File(workDir, "$dir/build.gradle.kts").isFile
        listOf("settings.gradle", "settings.gradle.kts").map { File(workDir, it) }.filter { it.isFile }.forEach { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            for (m in includeRe.findAll(text)) {
                for (q in quotedRe.findAll(m.groupValues[1])) {
                    val path = q.groupValues[1].trim().trimStart(':')
                    if (path.isEmpty()) continue
                    val dir = path.replace(':', '/')
                    if (hasBuild(dir)) out[":" + path.replace('/', ':')] = dir
                }
            }
        }
        workDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" && it.name != "buildSrc" }
            ?.sortedBy { it.name }?.forEach { d ->
                if (hasBuild(d.name)) out.putIfAbsent(":" + d.name, d.name)
            }
        return out
    }

    private val includeRe = Regex("(?s)\\binclude\\s*\\(?\\s*((?:['\"][^'\"\\n]+['\"]\\s*,?\\s*)+)")
    private val quotedRe = Regex("['\"]([^'\"\\n]+)['\"]")

    /** Files whose change means Gradle must be synced again. */
    fun syncFiles(workDir: File): List<File> {
        val list = mutableListOf<File>()
        listOf(
            "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts",
            "gradle.properties", "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
            "buildSrc/build.gradle", "buildSrc/build.gradle.kts"
        ).forEach { list.add(File(workDir, it)) }
        File(workDir, "gradle").listFiles()?.filter { it.isFile && it.name.endsWith(".versions.toml") }
            ?.forEach { list.add(it) }
        moduleDirs(workDir).values.forEach { d ->
            list.add(File(workDir, "$d/build.gradle")); list.add(File(workDir, "$d/build.gradle.kts"))
        }
        return list.filter { it.isFile }.distinctBy { it.path }
    }

    fun isSyncFile(workDir: File, f: File): Boolean = syncFiles(workDir).any { it.canonicalPath == f.canonicalPath }

    private fun crcOf(f: File): String {
        val crc = CRC32()
        runCatching { crc.update(f.readBytes()) }
        return crc.value.toString(16)
    }

    /** relative path -> content hash, for every file that matters to a sync. */
    private fun hashes(workDir: File): Map<String, String> =
        syncFiles(workDir).associate { it.relativeTo(workDir).path to crcOf(it) }

    private fun fpKey(workDir: File) = "fps:" + workDir.path

    private fun load(ctx: Context, workDir: File): Map<String, String>? {
        val raw = prefs(ctx).getString(fpKey(workDir), null) ?: return null
        return raw.lines().filter { it.contains('\t') }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
    }

    fun markSynced(ctx: Context, workDir: File) {
        val raw = hashes(workDir).entries.sortedBy { it.key }.joinToString("\n") { it.key + "\t" + it.value }
        prefs(ctx).edit().putString(fpKey(workDir), raw.ifEmpty { "-" }).apply()
    }

    fun needsSync(ctx: Context, workDir: File): Boolean = !syncScope(ctx, workDir).upToDate

    /**
     * What a sync has to do right now.
     * upToDate = nothing changed since the last successful sync/build -> no Gradle run needed.
     * modules  = only these modules' build files changed (null = something global changed -> sync all).
     */
    class SyncScope(val upToDate: Boolean, val modules: List<String>?)

    fun syncScope(ctx: Context, workDir: File): SyncScope {
        val old = load(ctx, workDir) ?: return SyncScope(false, null)
        val now = hashes(workDir)
        val changed = (old.keys + now.keys).filter { old[it] != now[it] }
        if (changed.isEmpty()) return SyncScope(true, emptyList())
        // folder -> Gradle path without the leading colon ("feature/login" -> "feature:login")
        val dirToPath = moduleDirs(workDir).entries.associate { it.value to it.key.trimStart(':') }
        val modules = LinkedHashSet<String>()
        for (rel in changed) {
            val name = rel.substringAfterLast('/')
            val dir = if (rel.contains('/')) rel.substringBeforeLast('/') else ""
            val isModuleBuild = dir.isNotEmpty() && (name == "build.gradle" || name == "build.gradle.kts")
            val path = if (isModuleBuild) dirToPath[dir] else null
            if (path == null) return SyncScope(false, null)      // settings / root / catalog / properties / unknown folder
            modules.add(path)
        }
        return SyncScope(false, modules.toList())
    }

    // ---- progress -------------------------------------------------------------------------------

    private val taskRe = Regex("(?m)^> Task (\\S+)")

    /** How many tasks the previous successful run of this task had (0 = unknown). */
    fun lastTaskTotal(ctx: Context, workDir: File, task: String) =
        prefs(ctx).getInt("total:${workDir.path}|$task", 0)

    fun saveTaskTotal(ctx: Context, workDir: File, task: String, output: String) {
        val n = taskRe.findAll(output).count()
        if (n > 0) prefs(ctx).edit().putInt("total:${workDir.path}|$task", n).apply()
    }

    /** Progress key for a run: a sync+build run has extra tasks, so it learns its own total. */
    fun totalKey(task: String, preSync: String?) = if (preSync != null) "$task+sync" else task

    private fun countOf(text: String, needle: String): Int {
        var n = 0
        var i = text.indexOf(needle)
        while (i >= 0) { n++; i = text.indexOf(needle, i + needle.length) }
        return n
    }

    /** 0..99 while the run has a known task total, -1 = unknown (show an indeterminate bar). */
    fun progressPercent(output: String, total: Int): Int {
        if (total <= 0) return -1
        val n = taskRe.findAll(output).count()
        return if (n == 0) 0 else (n * 100 / total).coerceAtMost(99)
    }

    /**
     * One status line, e.g.
     *   "[12/40 · 30%] :app:compileDebugKotlin · 18s · ~42s left"
     *   "⬇ 14 files · kotlin-stdlib-1.9.0.jar · 21s"      (sync: downloading)
     *   "Configuring :app · 6s"
     */
    fun progressLine(output: String, total: Int, startMs: Long): String {
        var n = 0
        var last = ""
        for (m in taskRe.findAll(output)) { n++; last = m.groupValues[1] }
        val secs = (System.currentTimeMillis() - startMs) / 1000
        val downloads = countOf(output, "\nDownload ")
        val lastDl = output.lastIndexOf("\nDownload ")
        val lastTask = output.lastIndexOf("\n> Task ")
        val resolved = countOf(output, "[ide-sync] ")

        if (lastDl >= 0 && lastDl > lastTask) {
            val url = output.substring(lastDl + 10).substringBefore('\n').trim().substringBefore(' ')
            val file = url.substringAfterLast('/')
            val mods = if (resolved > 0) " · $resolved module(s) resolved" else ""
            return "⬇ $downloads file(s) · $file$mods · ${secs}s"
        }
        if (n == 0) {
            val cfg = Regex("(?m)^> Configure project (\\S+)").findAll(output).lastOrNull()?.groupValues?.get(1)
            return if (cfg != null) "Configuring $cfg · ${secs}s" else "Starting Gradle... ${secs}s"
        }
        val head = if (total > 0) "[$n/$total · ${(n * 100 / total).coerceAtMost(99)}%]" else "[$n tasks]"
        val eta = if (total > n && n >= 3 && secs >= 3) " · ~${secs * (total - n) / n}s left" else ""
        val mods = if (resolved > 0 && total <= 0) " · $resolved module(s) resolved" else ""
        return "$head $last$mods · ${secs}s$eta"
    }
}
