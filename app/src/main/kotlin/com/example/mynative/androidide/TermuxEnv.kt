package com.example.mynative.androidide

import android.content.Context
import java.io.File

/** What to start in a PTY: argv[0] is [cmd]. */
class TermuxLaunch(val cmd: String, val args: List<String>, val env: Map<String, String>, val cwd: File)

/**
 * A real Termux userland (the official bootstrap + the whole Termux package repository) living in
 * this app's private storage.
 *
 * Termux packages have the path /data/data/com.termux/files/usr baked into their binaries. This app
 * has another package name, so every program is started through proot, which makes the app's own
 * folders appear at exactly those paths:
 *
 *   <files>/termux/usr  ->  /data/data/com.termux/files/usr   ($PREFIX: bin, lib, etc, var ...)
 *   <files>/home        ->  /data/data/com.termux/files/home  ($HOME: same folder the IDE uses,
 *                           so projects, android-sdk and ~/.gradle are visible in the terminal)
 *
 * With that, `pkg install`, `apt`, `dpkg`, python, git, nano, vim, ssh ... run unmodified.
 */
object TermuxEnv {

    const val GUEST_PREFIX = "/data/data/com.termux/files/usr"
    const val GUEST_HOME = "/data/data/com.termux/files/home"

    fun root(ctx: Context) = File(ctx.filesDir, "termux")
    fun prefix(ctx: Context) = File(root(ctx), "usr")
    fun home(ctx: Context) = TermEnvironment.home(ctx)

    private fun readyMark(ctx: Context) = File(root(ctx), ".ready")
    private fun noSeccompMark(ctx: Context) = File(root(ctx), ".no-seccomp")

    fun isReady(ctx: Context): Boolean =
        readyMark(ctx).isFile && File(prefix(ctx), "bin/bash").exists() && File(prefix(ctx), "bin/proot").exists()

    fun markReady(ctx: Context) { readyMark(ctx).writeText("1") }

    fun setNoSeccomp(ctx: Context, on: Boolean) {
        val f = noSeccompMark(ctx)
        if (on) f.writeText("1") else f.delete()
    }

    /** proot's loader (path differs a little between proot versions). */
    fun findLoader(ctx: Context): File? {
        val p = prefix(ctx)
        listOf("libexec/proot/loader", "lib/proot/loader", "libexec/proot-loader")
            .map { File(p, it) }.firstOrNull { it.isFile }?.let { return it }
        return File(p, "libexec").walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name == "loader" }
    }

    /** Environment seen by programs INSIDE the Termux userland. */
    private fun guestEnv(ctx: Context): HashMap<String, String> {
        val env = HashMap<String, String>()
        // Android's own variables (needed by anything that talks to ART / the system)
        listOf(
            "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_RUNTIME_ROOT",
            "ANDROID_TZDATA_ROOT", "ANDROID_STORAGE", "ANDROID_ASSETS", "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH",
            "EXTERNAL_STORAGE", "STANDALONE_SYSTEMSERVER_JARS", "SYSTEMSERVERCLASSPATH"
        ).forEach { k -> System.getenv(k)?.let { env[k] = it } }

        env["HOME"] = GUEST_HOME
        env["PREFIX"] = GUEST_PREFIX
        env["PATH"] = "$GUEST_PREFIX/bin"
        env["TMPDIR"] = "$GUEST_PREFIX/tmp"
        env["LANG"] = "en_US.UTF-8"
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["SHELL"] = "$GUEST_PREFIX/bin/bash"
        env["TERMUX_VERSION"] = "0.118.3"
        // newer Termux packages read these instead of guessing the paths
        env["TERMUX__PREFIX"] = GUEST_PREFIX
        env["TERMUX__HOME"] = GUEST_HOME
        env["TERMUX__ROOTFS"] = "/data/data/com.termux/files"
        env["TERMUX_APP__PACKAGE_NAME"] = "com.termux"
        env["TERMUX_APP__DATA_DIR"] = "/data/data/com.termux"
        env["TERMUX_APP__FILES_DIR"] = "/data/data/com.termux/files"

        val home = home(ctx)
        if (File(home, "android-sdk").isDirectory) {
            env["ANDROID_HOME"] = "$GUEST_HOME/android-sdk"
            env["ANDROID_SDK_ROOT"] = "$GUEST_HOME/android-sdk"
        }
        if (File(home, "jdk/bin/java").exists()) env["JAVA_HOME"] = "$GUEST_HOME/jdk"
        // Flutter SDK (home/flutter)
        val flutterGuest = if (GradleTuner.isFlutterSdk(File(home, "flutter"))) "$GUEST_HOME/flutter" else null
        if (flutterGuest != null) {
            env["FLUTTER_ROOT"] = flutterGuest
            env["PUB_CACHE"] = "$GUEST_HOME/.pub-cache"
            env["PATH"] = "${env["PATH"]}:$flutterGuest/bin:$GUEST_HOME/.pub-cache/bin"
        }
        return env
    }

    /** Interactive login shell (prints Termux's welcome text first, like the real app). */
    fun shellArgv(): List<String> = listOf(
        "$GUEST_PREFIX/bin/bash", "-c",
        "[ -r \"\$PREFIX/etc/motd\" ] && cat \"\$PREFIX/etc/motd\"; exec bash -l"
    )

    /** [guestArgv] runs inside the userland (absolute guest paths). */
    fun launch(ctx: Context, guestArgv: List<String>): TermuxLaunch {
        val hostPrefix = prefix(ctx).path
        val proot = "$hostPrefix/bin/proot"
        val env = guestEnv(ctx)

        // proot itself runs on the host: it needs its own libs and loader
        env["LD_LIBRARY_PATH"] = "$hostPrefix/lib"
        env["PROOT_LOADER"] = (findLoader(ctx)?.path ?: "$hostPrefix/libexec/proot/loader")
        env["PROOT_TMP_DIR"] = "$hostPrefix/tmp"
        if (noSeccompMark(ctx).isFile) env["PROOT_NO_SECCOMP"] = "1"

        ensureShims(ctx)

        val args = ArrayList<String>()
        args += proot
        args += "--kill-on-exit"
        args += "--link2symlink"                       // app storage forbids hard links; apt / dpkg / git need them
        args += listOf("-b", "$hostPrefix:$GUEST_PREFIX")
        // ordinary Linux paths: "#!/usr/bin/env python", "#!/bin/sh", /tmp (Termux's own termux-exec does this on a real Termux)
        args += listOf("-b", "$hostPrefix:/usr")
        args += listOf("-b", "$hostPrefix/bin:/bin")
        args += listOf("-b", "$hostPrefix/tmp:/tmp")
        args += listOf("-b", "${home(ctx).path}:$GUEST_HOME")
        args += listOf("-w", GUEST_HOME)
        // proot-only variables must not leak into the programs
        args += "$GUEST_PREFIX/bin/env"
        args += listOf("-u", "LD_LIBRARY_PATH", "-u", "PROOT_LOADER", "-u", "PROOT_TMP_DIR", "-u", "PROOT_NO_SECCOMP")
        args += guestArgv
        return TermuxLaunch(proot, args, env, home(ctx))
    }

    /**
     * Small helpers a real Termux has: termux-setup-storage (~/storage links), termux-reload-settings,
     * a starter ~/.bashrc. Idempotent, cheap, never overwrites the user's own files.
     */
    fun ensureShims(ctx: Context) {
        runCatching {
            val bin = File(prefix(ctx), "bin")
            fun shim(name: String, body: String) {
                val f = File(bin, name)
                if (f.isFile) return
                f.writeText("#!$GUEST_PREFIX/bin/bash\n$body\n")
                f.setExecutable(true, false)
            }
            shim("termux-setup-storage", """
d="${'$'}HOME/storage"
if [ ! -r /storage/emulated/0 ] || [ -z "${'$'}(ls /storage/emulated/0 2>/dev/null)" ]; then
  echo "Shared storage is not readable. Give the app 'All files access' in Android Settings > Apps > this app > Permissions, then run termux-setup-storage again."
  exit 1
fi
mkdir -p "${'$'}d"
ln -sfn /storage/emulated/0 "${'$'}d/shared"
for p in Download:downloads DCIM:dcim Pictures:pictures Music:music Movies:movies; do
  ln -sfn "/storage/emulated/0/${'$'}{p%%:*}" "${'$'}d/${'$'}{p##*:}"
done
echo "Storage ready: ~/storage/shared, downloads, dcim, pictures, music, movies"
""".trim())
            shim("termux-reload-settings", "echo 'Settings are read when a new session starts.'")
            // IDE-managed extras (aliases, git prompt, proj / ide-info): rewritten every start
            runCatching {
                ctx.assets.open("termux/ide_profile.sh").use { ins ->
                    File(home(ctx), ".ide_profile").outputStream().use { ins.copyTo(it) }
                }
            }
            val hook = "[ -f ~/.ide_profile ] && . ~/.ide_profile"
            val rc = File(home(ctx), ".bashrc")
            if (!rc.exists()) {
                rc.writeText(
                    "# starter .bashrc (created once, edit freely)\n" +
                        "export EDITOR=nano\n\n" +
                        "# AndroidIDE shell extras (remove this line to disable)\n$hook\n"
                )
            } else if (!rc.readText().contains(".ide_profile")) {
                rc.appendText("\n# AndroidIDE shell extras (remove this line to disable)\n$hook\n")
            }
        }
    }

    /** Fallback when the Termux userland is not installed: Android's own sh (old behaviour). */
    fun basicLaunch(ctx: Context): TermuxLaunch {
        TermEnvironment.prepare(ctx)
        val shell = TermEnvironment.shellPath(ctx)
        val env = HashMap<String, String>(System.getenv())
        TermEnvironment.applyTo(ctx, env)
        env["PS1"] = "\\w $ "
        env["COLORTERM"] = "truecolor"
        return TermuxLaunch(shell, listOf(shell, "-i"), env, TermEnvironment.home(ctx))
    }

    /** Deletes the Termux userland only (never the shared home folder). */
    fun wipe(ctx: Context) {
        root(ctx).deleteRecursively()
    }
}
