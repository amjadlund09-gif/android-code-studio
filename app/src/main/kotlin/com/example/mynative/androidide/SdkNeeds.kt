package com.example.mynative.androidide

import android.content.Context
import java.io.File

/** Reads a project's build files and lists the SDK packages it needs but the phone does not have. */
object SdkNeeds {

    class Need(val id: String, val why: String)

    private val skipDirs = setOf("build", ".gradle", ".git", "node_modules", ".idea", "assets")

    private val compileSdkRx = Regex("""compileSdk(?:Version)?\s*[=(]?\s*['"]?(?:android-)?(\d+)""")
    private val releaseRx = Regex("""release\((\d+)""")
    private val tomlCompileRx = Regex("""(?i)compile[-_]?sdk\s*=\s*"(\d+)"""")
    private val buildToolsRx = Regex("""buildToolsVersion\s*[=(]?\s*['"]([\d.]+)['"]""")
    private val ndkRx = Regex("""ndkVersion\s*[=(]?\s*['"]([\d.]+)['"]""")
    private val cmakeRx = Regex("""cmake\s*\{[^{}]*?version\s*=?\s*["']([\d.]+)["']""", RegexOption.DOT_MATCHES_ALL)

    /** "platforms;android-37" -> "API 37", "ndk;29.0.1" -> "NDK 29.0.1" ... */
    fun label(id: String): String = when {
        id.startsWith("platforms;android-") -> "API " + id.removePrefix("platforms;android-").removeSuffix(".0")
        id.startsWith("build-tools;") -> "Build Tools " + id.removePrefix("build-tools;")
        id.startsWith("ndk;") -> "NDK " + id.removePrefix("ndk;")
        id.startsWith("cmake;") -> "CMake " + id.removePrefix("cmake;")
        id.startsWith("jdk;") -> "JDK " + id.removePrefix("jdk;")
        id == "platform-tools" -> "Platform Tools"
        id == "flutter" -> "Flutter SDK"
        else -> id
    }

    /** One requirement of a template: [ok] = already present on the phone. */
    class Req(val need: Need, val ok: Boolean)

    /**
     * Everything a NEW project of this template needs (platform, build-tools, JDK, NDK, CMake),
     * read from the template's template.json, each marked present or missing.
     */
    fun requirements(ctx: Context, templateId: String, useCmake: Boolean = true): List<Req> {
        val meta = TemplateMeta.load(ctx, templateId)
            ?: if (templateId == "FlutterActivity") return listOf(Req(Need("flutter", "Flutter SDK to build the project"), FlutterInstaller.isInstalled(ctx)))
            else if (templateId == "PlainEditor") return emptyList()
            else TemplateMeta(0, true, false, 0, templateId == "NativeCpp" || templateId == "GameActivity", null, "")
        val sdkRoot = SdkRepository.sdkRoot(ctx)
        val out = ArrayList<Req>()

        if (meta.platform) {
            val id = SdkRepository.resolveId(SdkIndex.cached(ctx), "platforms;android-${ProjectTemplates.COMPILE_SDK}")
            out.add(Req(Need(id, "compileSdk ${ProjectTemplates.COMPILE_SDK}"), installed(sdkRoot, id)))
        }
        if (meta.buildTools) {
            val have = File(sdkRoot, "build-tools").listFiles { d -> d.isDirectory && File(d, "source.properties").isFile }
                ?.map { it.name }.orEmpty()
            if (have.isNotEmpty()) {
                val newest = have.reduce { a, b -> if (SdkIndex.isNewer(b, a)) b else a }
                out.add(Req(Need("build-tools;$newest", "build tools to compile and package"), true))
            } else {
                val rec = SdkRepository.choices(SdkTab.BUILD_TOOLS, SdkIndex.cached(ctx)).first()
                out.add(Req(Need("build-tools;$rec", "no build-tools installed yet"), false))
            }
        }
        if (meta.jdk > 0) {
            val major = TermEnvironment.javaMajor(ctx)
            val ok = TermEnvironment.javaHome(ctx) != null && (major == null || major >= meta.jdk)
            out.add(Req(Need("jdk;${meta.jdk}", "JDK ${meta.jdk}+ runs Gradle"), ok))
        }
        if (meta.ndk) {
            val id = "ndk;${ProjectTemplates.NDK_VERSION}"
            out.add(Req(Need(id, "NDK for the C/C++ code"), installed(sdkRoot, id)))
        }
        if (useCmake && meta.cmake != null) {
            val id = "cmake;${meta.cmake}"
            out.add(Req(Need(id, "CMake for the native build"), installed(sdkRoot, id)))
        }
        if (templateId == "FlutterActivity") out.add(Req(Need("flutter", "Flutter SDK to build the project"), FlutterInstaller.isInstalled(ctx)))
        return out
    }

    /** Only the missing ones (what "SDK ready" offers to install). */
    fun forTemplate(ctx: Context, templateId: String, useCmake: Boolean = true): List<Need> =
        requirements(ctx, templateId, useCmake).filter { !it.ok }.map { it.need }

    private fun installed(sdkRoot: File, id: String) = SdkRepository.dirInstalled(sdkRoot, id)

    private fun buildFiles(dir: File, depth: Int, out: MutableList<File>) {
        if (depth > 3) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                if (f.name !in skipDirs) buildFiles(f, depth + 1, out)
            } else if (f.name == "build.gradle" || f.name == "build.gradle.kts" ||
                f.name == "libs.versions.toml" || f.name == "gradle.properties") {
                out.add(f)
            }
        }
    }

    /** Missing packages only (already installed ones are left out). [remote] is used for the "latest build-tools" fallback. */
    fun scan(projectDir: File, sdkRoot: File, remote: List<RemoteSdkPkg>?): List<Need> {
        val files = ArrayList<File>().also { buildFiles(projectDir, 0, it) }
        val found = LinkedHashMap<String, String>()          // id -> why

        fun add(id: String, why: String) { if (id !in found) found[id] = why }

        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val where = f.relativeTo(projectDir).path
            val isToml = f.name == "libs.versions.toml"
            if (f.name != "gradle.properties") {
                val api = if (isToml) tomlCompileRx.find(text)?.groupValues?.get(1)
                else (compileSdkRx.find(text) ?: releaseRx.find(text))?.groupValues?.get(1)
                if (api != null) add(SdkRepository.resolveId(remote, "platforms;android-$api"), "compileSdk $api ($where)")
            }
            buildToolsRx.find(text)?.groupValues?.get(1)?.let { add("build-tools;$it", "buildToolsVersion $it ($where)") }
            ndkRx.find(text)?.groupValues?.get(1)?.let { add("ndk;$it", "ndkVersion $it ($where)") }
            if (!isToml && f.name != "gradle.properties") {
                cmakeRx.find(text)?.groupValues?.get(1)?.let { add("cmake;$it", "CMake $it ($where)") }
            }
        }

        // No explicit buildToolsVersion anywhere and none installed: suggest the newest stable one.
        val anyBuildTools = File(sdkRoot, "build-tools").listFiles { d -> d.isDirectory }?.isNotEmpty() == true
        if (found.keys.none { it.startsWith("build-tools;") } && !anyBuildTools && files.any { it.name.startsWith("build.gradle") }) {
            val newest = remote?.filter { it.id.startsWith("build-tools;") }
                ?.maxWithOrNull(Comparator { a, b -> if (SdkIndex.isNewer(a.revision, b.revision)) 1 else if (SdkIndex.isNewer(b.revision, a.revision)) -1 else 0 })
            if (newest != null) add(newest.id, "no build-tools installed yet")
        }

        return found.filter { (id, _) -> !installed(sdkRoot, id) }.map { Need(it.key, it.value) }
    }
}
