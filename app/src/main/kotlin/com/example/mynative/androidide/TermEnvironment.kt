package com.example.mynative.androidide

import android.content.Context
import java.io.File

/**
 * Termux-style folder layout inside the app's private storage:
 *   files/home   -> $HOME  (projects, android-sdk, configuration)
 *   files/usr    -> $PREFIX (bin, lib, etc, tmp)
 */
object TermEnvironment {

    fun home(ctx: Context) = File(ctx.filesDir, "home")
    fun prefix(ctx: Context) = File(ctx.filesDir, "usr")
    fun sdk(ctx: Context) = File(home(ctx), "android-sdk")

    /** The JDK the shell / Gradle will use (same search order as JAVA_HOME below), or null. */
    fun javaHome(ctx: Context): File? =
        listOf(File(home(ctx), "jdk"), File(prefix(ctx), "lib/jvm/java-21-openjdk"), File(prefix(ctx), "lib/jvm/java-17-openjdk"))
            .firstOrNull { File(it, "bin/java").exists() }

    /** Major Java version of [javaHome] (8, 11, 17, 21...), read from its `release` file; null if unknown. */
    fun javaMajor(ctx: Context): Int? {
        val h = javaHome(ctx) ?: return null
        val v = runCatching {
            Regex("JAVA_VERSION=\"([^\"]+)\"").find(File(h, "release").readText())?.groupValues?.get(1)
        }.getOrNull() ?: Regex("java-(\\d+)").find(h.name)?.groupValues?.get(1)
        ?: return null
        val parts = v.split('.')
        return (if (parts[0] == "1") parts.getOrNull(1) else parts[0])?.takeWhile { it.isDigit() }?.toIntOrNull()
    }

    /** Creates the folders. Safe to call many times. */
    fun prepare(ctx: Context) {
        home(ctx).mkdirs()
        File(home(ctx), "AndroidIDEProjects").mkdirs()
        File(home(ctx), "bin").mkdirs()
        listOf("bin", "lib", "etc", "tmp", "var").forEach { File(prefix(ctx), it).mkdirs() }
    }

    /** bash from $PREFIX if it was installed there, otherwise Android's own shell. */
    fun shellPath(ctx: Context): String =
        listOf(File(prefix(ctx), "bin/bash"), File(prefix(ctx), "bin/sh"))
            .firstOrNull { it.isFile && it.canExecute() }?.path ?: "/system/bin/sh"

    fun applyTo(ctx: Context, env: MutableMap<String, String>) {
        val home = home(ctx)
        val prefix = prefix(ctx)
        val sdk = sdk(ctx)
        val path = listOf(
            File(prefix, "bin").path,
            File(home, "bin").path,
            File(sdk, "cmdline-tools/latest/bin").path,
            File(sdk, "platform-tools").path
        ).joinToString(":") + ":" + (env["PATH"] ?: "/system/bin:/system/xbin")

        env["HOME"] = home.path
        env["PREFIX"] = prefix.path
        env["TMPDIR"] = File(prefix, "tmp").path
        env["PATH"] = path
        env["LD_LIBRARY_PATH"] = File(prefix, "lib").path +
            (env["LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
        env["LANG"] = "en_US.UTF-8"
        env["TERM"] = "xterm-256color"
        env["ANDROID_HOME"] = sdk.path
        env["ANDROID_SDK_ROOT"] = sdk.path

        javaHome(ctx)?.let { env["JAVA_HOME"] = it.path }

        GradleTuner.findFlutterSdk(ctx)?.let { fl ->
            env["FLUTTER_ROOT"] = fl.path
            env["PUB_CACHE"] = File(home, ".pub-cache").path
            env["PATH"] = File(fl, "bin").path + ":" + File(home, ".pub-cache/bin").path + ":" + env["PATH"]
        }

        // The Termux-built JDK has /data/data/com.termux/... baked in as user.home / java.io.tmpdir.
        // Point Java and Gradle at this app's own folders instead.
        val gradleHome = File(home, ".gradle").apply { mkdirs() }
        env["GRADLE_USER_HOME"] = gradleHome.path
        val javaOpts = "-Duser.home=${home.path} -Djava.io.tmpdir=${File(prefix, "tmp").path}"
        env["JAVA_TOOL_OPTIONS"] = listOfNotNull(javaOpts, env["JAVA_TOOL_OPTIONS"]).joinToString(" ")
    }
}
