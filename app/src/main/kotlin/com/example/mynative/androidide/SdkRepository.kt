package com.example.mynative.androidide

import android.content.Context
import java.io.File

enum class SdkState { NOT_INSTALLED, INSTALLED, INVALID }

enum class SdkTab(val label: String, val section: String, val dirName: String, val icon: Int) {
    BUILD_TOOLS("Build Tools", "Build Tools", "build-tools", R.drawable.ic_wrench),
    NDK("NDK", "NDK Versions", "ndk", R.drawable.ic_terminal_box),
    CMAKE("CMake", "CMake Versions", "cmake", R.drawable.ic_cpp),
    PLATFORMS("Platforms", "Platforms", "platforms", R.drawable.ic_android),
    PLATFORM_TOOLS("Platform Tools", "Platform Tools", "platform-tools", R.drawable.ic_apps),
    JDK("JDK", "JDK Versions", "jvm", R.drawable.ic_java),
    FLUTTER("Flutter", "Flutter SDK", "flutter", R.drawable.ic_apps)
}

class SdkPkg(
    val tab: SdkTab,
    val title: String,
    val version: String,
    val id: String,        // sdkmanager package id, e.g. build-tools;36.1.0
    val dir: File,
    val state: SdkState,
    val size: Long = 0,            // download size in bytes (from the live index), 0 = unknown
    val updateTo: String? = null,  // newer revision available online, if any
    val warning: String? = null    // e.g. tools built for another CPU (see SdkVerifier)
)

/** Real SDK location and package state, read straight from the disk. */
object SdkRepository {

    fun homeDir(context: Context) = File(context.filesDir, "home")
    fun sdkRoot(context: Context) = File(homeDir(context), "android-sdk")

    private val knownBuildTools = listOf("37.0.0", "36.1.0", "36.0.0", "35.0.0", "34.0.0", "30.0.3")
    private val knownNdk = listOf("30.0.15729638", "29.0.14206865", "27.3.13750724")
    private val knownCmake = listOf("4.1.2", "3.22.1")
    private val knownPlatforms = (37 downTo 30).map { "android-$it" }

    fun list(sdkRoot: File, tab: SdkTab, remote: List<RemoteSdkPkg>? = null): List<SdkPkg> {
        val byId = remote?.associateBy { it.id }
        val base = File(sdkRoot, tab.dirName)

        if (tab == SdkTab.JDK) {
            // $PREFIX/lib/jvm/java-N-openjdk, with $PREFIX = files/usr (sdkRoot = files/home/android-sdk)
            val prefix = File(sdkRoot.parentFile?.parentFile, "usr")
            return listOf("17", "21").map { v ->
                val dir = File(prefix, "lib/jvm/java-$v-openjdk")
                val state = when {
                    File(dir, "bin/java").isFile -> SdkState.INSTALLED
                    dir.isDirectory -> SdkState.INVALID
                    else -> SdkState.NOT_INSTALLED
                }
                SdkPkg(tab, "JDK", v, "jdk;$v", dir, state)
            }
        }

        if (tab == SdkTab.FLUTTER) {
            // Flutter SDK lives in <home>/flutter (see FlutterInstaller)
            val dir = File(sdkRoot.parentFile, "flutter")
            val v = File(dir, "version").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: "SDK"
            val st = when {
                GradleTuner.isFlutterSdk(dir) -> SdkState.INSTALLED
                dir.isDirectory && dir.list()?.isNotEmpty() == true -> SdkState.INVALID
                else -> SdkState.NOT_INSTALLED
            }
            return listOf(SdkPkg(tab, "Flutter SDK", v, "flutter", dir, st))
        }

        if (tab == SdkTab.PLATFORM_TOOLS) {
            val state = stateOf(base)
            val revision = readRevision(base) ?: "latest"
            val r = byId?.get("platform-tools")
            return listOf(
                SdkPkg(
                    tab, "Platform Tools", revision, "platform-tools", base, state,
                    size = r?.size ?: 0L,
                    updateTo = updateOf(state, base, r),
                    warning = if (state == SdkState.INSTALLED) SdkVerifier.warning("platform-tools", base) else null
                )
            )
        }

        val idPrefix = when (tab) {
            SdkTab.BUILD_TOOLS -> "build-tools;"
            SdkTab.NDK -> "ndk;"
            SdkTab.CMAKE -> "cmake;"
            else -> "platforms;"
        }
        // Live list from Google (newest 15) when available, otherwise the built-in fallback list.
        val live = remote?.filter { it.id.startsWith(idPrefix) }?.map { it.id.removePrefix(idPrefix) }
            ?.sortedWith(versionComparator)?.take(15)?.takeIf { it.isNotEmpty() }
        val known = live ?: when (tab) {
            SdkTab.BUILD_TOOLS -> knownBuildTools
            SdkTab.NDK -> knownNdk
            SdkTab.CMAKE -> knownCmake
            else -> knownPlatforms
        }
        val onDisk = base.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
            .filter { tab != SdkTab.PLATFORMS || it.startsWith("android-") }

        return (known + onDisk).distinct().sortedWith(versionComparator).map { name ->
            val dir = File(base, name)
            val (title, label, id) = when (tab) {
                SdkTab.BUILD_TOOLS -> Triple("Build Tools", name, "build-tools;$name")
                SdkTab.NDK -> Triple("NDK", name, "ndk;$name")
                SdkTab.CMAKE -> Triple("CMake", name, "cmake;$name")
                else -> Triple("SDK Platform", "API " + name.removePrefix("android-"), "platforms;$name")
            }
            val state = stateOf(dir)
            val r = byId?.get(id)
            SdkPkg(
                tab, title, label, id, dir, state,
                size = r?.size ?: 0L,
                updateTo = updateOf(state, dir, r),
                warning = if (state == SdkState.INSTALLED) SdkVerifier.warning(id, dir) else null
            )
        }
    }

    // ---- helpers for the first-run setup screen -----------------------------------------------

    /** Version names offered for a tab (newest first): the live Google list when known, else the built-in list. */
    fun choices(tab: SdkTab, remote: List<RemoteSdkPkg>?): List<String> {
        if (tab == SdkTab.JDK) return listOf("17", "21")
        if (tab == SdkTab.PLATFORM_TOOLS) return listOf("latest")
        if (tab == SdkTab.FLUTTER) return listOf("latest")
        val prefix = idOf(tab, "")
        val live = remote?.filter { it.id.startsWith(prefix) }?.map { it.id.removePrefix(prefix) }
            ?.sortedWith(versionComparator)?.take(15)?.takeIf { it.isNotEmpty() }
        return live ?: when (tab) {
            SdkTab.BUILD_TOOLS -> knownBuildTools
            SdkTab.NDK -> knownNdk
            SdkTab.CMAKE -> knownCmake
            else -> knownPlatforms
        }
    }

    /** Package id for a version name from [choices]: (BUILD_TOOLS, "36.1.0") -> "build-tools;36.1.0". */
    fun idOf(tab: SdkTab, value: String): String = when (tab) {
        SdkTab.BUILD_TOOLS -> "build-tools;$value"
        SdkTab.NDK -> "ndk;$value"
        SdkTab.CMAKE -> "cmake;$value"
        SdkTab.PLATFORMS -> "platforms;$value"
        SdkTab.PLATFORM_TOOLS -> "platform-tools"
        SdkTab.JDK -> "jdk;$value"
        SdkTab.FLUTTER -> "flutter"
    }

    /** True when the package is really on the phone (same rule the SDK Manager uses). */
    fun isInstalled(context: Context, id: String): Boolean =
        if (id == "flutter") {
            FlutterInstaller.isInstalled(context)
        } else if (id.startsWith("jdk;")) {
            File(JdkInstaller.javaHome(TermEnvironment.prefix(context), id.removePrefix("jdk;")), "bin/java").isFile
        } else {
            dirInstalled(sdkRoot(context), id)
        }

    /** Google names a platform either "android-37" or "android-37.0": both mean the same API level. */
    fun idAliases(id: String): List<String> {
        val m = Regex("^platforms;android-(\\d+)(\\.0)?$").matchEntire(id) ?: return listOf(id)
        val n = m.groupValues[1]
        return listOf("platforms;android-$n", "platforms;android-$n.0")
    }

    fun dirInstalled(sdkRoot: File, id: String): Boolean =
        idAliases(id).any { File(sdkRoot, it.replace(';', '/') + "/source.properties").isFile }

    /** The id Google's index really uses for this platform (android-37 or android-37.0); falls back to [id]. */
    fun resolveId(remote: List<RemoteSdkPkg>?, id: String): String {
        val ids = remote?.map { it.id }?.toSet() ?: return id
        return idAliases(id).firstOrNull { it in ids } ?: id
    }

    private fun updateOf(state: SdkState, dir: File, r: RemoteSdkPkg?): String? {
        if (state != SdkState.INSTALLED || r == null) return null
        val have = readRevision(dir) ?: return null
        return if (SdkIndex.isNewer(r.revision, have)) r.revision else null
    }

    /** Installed = folder with source.properties (written by sdkmanager). Folder without it = invalid. */
    private fun stateOf(dir: File): SdkState = when {
        !dir.isDirectory -> SdkState.NOT_INSTALLED
        File(dir, "source.properties").isFile -> SdkState.INSTALLED
        else -> SdkState.INVALID
    }

    private fun readRevision(dir: File): String? =
        runCatching {
            File(dir, "source.properties").readLines()
                .firstOrNull { it.startsWith("Pkg.Revision=") }
                ?.substringAfter('=')?.trim()
        }.getOrNull()

    /** Newest first, comparing the numeric parts of the names. */
    private val versionComparator = Comparator<String> { a, b ->
        val pa = Regex("\\d+").findAll(a).map { it.value.toLong() }.toList()
        val pb = Regex("\\d+").findAll(b).map { it.value.toLong() }.toList()
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { -1 }
            val y = pb.getOrElse(i) { -1 }
            if (x != y) return@Comparator y.compareTo(x)
        }
        0
    }
}
