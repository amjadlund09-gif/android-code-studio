package com.example.mynative.androidide

import android.content.Context
import java.io.File

/**
 * Google's build-tools only ship an x86-64 aapt2, which cannot run on an arm64 phone.
 * This installs the arm64 (bionic) aapt2 from the Termux repository into $PREFIX/bin, copies it over
 * the wrong-CPU aapt2 of every installed build-tools folder, and GradleTuner then passes
 * -Pandroid.aapt2FromMavenOverride=<that binary> so AGP uses it instead of its x86 download.
 */
object Aapt2Fix {

    fun binary(ctx: Context) = File(TermEnvironment.prefix(ctx), "bin/aapt2")

    /** An aapt2 for this phone's CPU is installed in $PREFIX/bin (SdkVerifier reads "<dir>/aapt2"). */
    fun ready(ctx: Context): Boolean {
        val f = binary(ctx)
        if (!f.isFile) return false
        val items = SdkVerifier.inspect("build-tools;x", f.parentFile!!)
        return items.isNotEmpty() && items.all { it.ok == true }
    }

    fun ensure(ctx: Context, log: TaskLog, cancelled: () -> Boolean) {
        if (!ready(ctx)) {
            log.line("")
            log.line("Google's aapt2 is x86-64 - installing an arm64 aapt2 for this phone...")
            JdkInstaller(ctx).installPackage("aapt2", log, cancelled)
            binary(ctx).setExecutable(true, false)
        }
        if (!ready(ctx)) {
            throw IllegalStateException("arm64 aapt2 was not found after install (${binary(ctx).path}).")
        }
        // replace the wrong-CPU aapt2 inside every installed build-tools folder
        val bt = File(SdkRepository.sdkRoot(ctx), "build-tools").listFiles { d -> d.isDirectory }.orEmpty()
        for (dir in bt) {
            if (SdkVerifier.inspect("build-tools;" + dir.name, dir).any { it.ok == false }) {
                runCatching {
                    binary(ctx).copyTo(File(dir, "aapt2"), overwrite = true)
                    File(dir, "aapt2").setExecutable(true, false)
                    log.line("  ✔ aapt2 replaced in build-tools ${dir.name}")
                }.onFailure { log.line("  ⚠ could not replace aapt2 in ${dir.name}: ${it.message}") }
            }
        }
        log.line("arm64 aapt2 ready ✔  (${binary(ctx).path})")
        log.line("Gradle will use it automatically (android.aapt2FromMavenOverride).")
    }
}
