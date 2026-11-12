package com.example.mynative.androidide

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Google's SDK packages ship Linux x86-64 binaries. On an arm64 phone they cannot run, and the
 * SDK Manager shows "Incompatible". This swaps those binaries for arm64 (bionic) ones from the
 * Termux repository, the same idea Aapt2Fix already uses for build-tools:
 *
 *  - build-tools : arm64 aapt2                      (Aapt2Fix)
 *  - cmake       : $PREFIX/bin/cmake + ninja linked into <cmake>/bin
 *  - ndk         : arm64 clang / lld / llvm-* / make copied into the NDK's llvm toolchain,
 *                  the NDK's own sysroot + compiler-rt stay (they are CPU independent)
 */
object NativeToolsFix {

    fun fixable(id: String) = id.startsWith("build-tools;") || id.startsWith("cmake;") || id.startsWith("ndk;")

    fun needsFix(id: String, dir: File) = SdkVerifier.inspect(id, dir).any { it.ok == false }

    /** Repairs [id] (installed in [dir]) so its tools run on this phone. Throws when it cannot. */
    fun fix(ctx: Context, id: String, dir: File, log: TaskLog, cancelled: () -> Boolean) {
        when {
            id.startsWith("build-tools;") -> Aapt2Fix.ensure(ctx, log, cancelled)
            id.startsWith("cmake;") -> fixCmake(ctx, dir, log, cancelled)
            id.startsWith("ndk;") -> fixNdk(ctx, dir, log, cancelled)
            else -> return
        }
        val bad = SdkVerifier.inspect(id, dir).filter { it.ok == false }
        if (bad.isEmpty()) log.line("✔ All tools of this package now match the phone.")
        else throw IllegalStateException("Still wrong CPU: " + bad.joinToString { it.name })
    }

    private fun pbin(ctx: Context, name: String) = File(TermEnvironment.prefix(ctx), "bin/$name")

    private fun ready(f: File) = f.isFile && SdkVerifier.matchesPhone(f)

    private fun need(ctx: Context, pkg: String, probe: String, log: TaskLog, cancelled: () -> Boolean) {
        if (ready(pbin(ctx, probe))) return
        log.line("")
        log.line("Installing arm64 \"$pkg\" for this phone...")
        JdkInstaller(ctx).installPackage(pkg, log, cancelled)
        if (!ready(pbin(ctx, probe))) {
            throw IllegalStateException("arm64 $probe was not found after installing \"$pkg\".")
        }
    }

    private fun isLink(f: File) = try { OsConstants.S_ISLNK(Os.lstat(f.path).st_mode) } catch (e: Exception) { false }

    private fun link(dst: File, src: File) {
        dst.parentFile?.mkdirs()
        if (isLink(dst) || dst.exists()) dst.delete()
        try { Os.symlink(src.path, dst.path) }
        catch (e: Exception) { src.copyTo(dst, overwrite = true) }
        dst.setExecutable(true, false)
    }

    private fun copy(src: File, dst: File) {
        if (isLink(dst) || dst.exists()) dst.delete()
        src.copyTo(dst, overwrite = true)
        dst.setExecutable(true, false)
    }

    // ---- CMake -------------------------------------------------------------------------------

    private fun fixCmake(ctx: Context, dir: File, log: TaskLog, cancelled: () -> Boolean) {
        need(ctx, "cmake", "cmake", log, cancelled)
        need(ctx, "ninja", "ninja", log, cancelled)
        val bin = File(dir, "bin")
        // symlinks (not copies): cmake finds its Modules/ next to the REAL binary in $PREFIX
        for (name in listOf("cmake", "ninja", "ctest", "cpack")) {
            val src = pbin(ctx, name)
            if (!ready(src)) continue
            link(File(bin, name), src)
            log.line("  ✔ $name -> arm64")
        }
    }

    // ---- NDK ---------------------------------------------------------------------------------

    private fun fixNdk(ctx: Context, dir: File, log: TaskLog, cancelled: () -> Boolean) {
        val host = File(dir, "toolchains/llvm/prebuilt").listFiles { f -> f.isDirectory }?.firstOrNull()
            ?: throw IllegalStateException("NDK has no toolchains/llvm/prebuilt folder - reinstall it.")
        need(ctx, "clang", "clang", log, cancelled)
        need(ctx, "lld", "ld.lld", log, cancelled)
        need(ctx, "llvm", "llvm-ar", log, cancelled)
        need(ctx, "make", "make", log, cancelled)

        log.line("")
        log.line("Replacing x86-64 compiler tools in the NDK with arm64 ones...")
        val termuxClang = pbin(ctx, "clang").canonicalFile
        var replaced = 0
        var removed = 0
        val swap = { folder: File, useClangFor: Regex? ->
            folder.listFiles().orEmpty().forEach { f ->
                if (cancelled()) throw InstallCancelled()
                if (isLink(f) || !f.isFile) return@forEach           // links follow the real file
                if (SdkVerifier.isElf(f) != true || SdkVerifier.matchesPhone(f)) return@forEach
                val src = if (useClangFor != null && useClangFor.matches(f.name)) termuxClang
                          else pbin(ctx, f.name).takeIf { it.exists() }?.canonicalFile
                if (src != null && ready(src)) { copy(src, f); replaced++ }
                else { f.delete(); removed++ }                       // no arm64 twin: a dead x86 file helps nobody
            }
        }
        swap(File(host, "bin"), Regex("clang-\\d+"))
        swap(File(dir, "prebuilt/linux-x86_64/bin"), null)             // make, yasm ...
        log.line("  ✔ $replaced tools replaced" + if (removed > 0) ", $removed x86-only tools removed" else "")

        // The copied clang looks for its headers in lib/clang/<its major version>.
        val clangLib = File(host, "lib/clang")
        fun newest(d: File) = d.listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
            ?.maxByOrNull { it.name.toInt() }
        val ndkRes = newest(clangLib)
        val termuxRes = newest(File(TermEnvironment.prefix(ctx), "lib/clang"))
        if (ndkRes != null && termuxRes != null && ndkRes.name != termuxRes.name) {
            val dst = File(clangLib, termuxRes.name).apply { mkdirs() }
            File(termuxRes, "include").copyRecursively(File(dst, "include"), overwrite = true)
            link(File(dst, "lib"), File(ndkRes, "lib"))               // NDK's Android compiler-rt libs
            log.line("  ✔ clang resource dir ${termuxRes.name} linked to the NDK's ${ndkRes.name}")
        }
    }
}
