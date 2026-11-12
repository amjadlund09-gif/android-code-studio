package com.example.mynative.androidide

import android.os.Build
import java.io.File

/**
 * Checks that the native tools of an installed SDK package can run on THIS phone, by reading the
 * ELF header (no process is started). The installer downloads Google's Linux x86-64 archives, and
 * an x86-64 binary cannot run on an arm64 phone.
 */
object SdkVerifier {

    /** One checked file: [ok] true = matches the phone, false = wrong CPU, null = not an ELF file. */
    class Item(val name: String, val machine: String, val ok: Boolean?)

    private fun deviceMachine(): Int? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when {
            abi.startsWith("arm64") -> 0xB7
            abi.startsWith("x86_64") -> 0x3E
            abi.startsWith("armeabi") -> 0x28
            abi == "x86" -> 0x03
            else -> null
        }
    }

    private fun machineName(m: Int) = when (m) {
        0xB7 -> "arm64"
        0x3E -> "x86-64"
        0x28 -> "arm32"
        0x03 -> "x86"
        else -> "cpu 0x" + m.toString(16)
    }

    private fun machineOf(f: File): Int? {
        if (!f.isFile) return null
        return try {
            f.inputStream().use { ins ->
                val h = ByteArray(20)
                if (ins.read(h) < 20) return null
                if (h[0] != 0x7f.toByte() || h[1] != 'E'.code.toByte() ||
                    h[2] != 'L'.code.toByte() || h[3] != 'F'.code.toByte()) return null
                (h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** true = it is an ELF binary (any CPU), null = not an ELF file. */
    fun isElf(f: File): Boolean? = machineOf(f)?.let { true }

    /** True when [f] is an ELF binary built for this phone's CPU. */
    fun matchesPhone(f: File): Boolean {
        val want = deviceMachine() ?: return false
        return machineOf(f) == want
    }

    private fun filesOf(id: String, dir: File): List<File> = when {
        id.startsWith("build-tools;") -> listOf(File(dir, "aapt2"))   // the only native tool Gradle/AGP runs; aapt/zipalign are unused
        id == "platform-tools" -> listOf("adb", "fastboot").map { File(dir, it) }
        id.startsWith("cmake;") -> listOf("bin/cmake", "bin/ninja").map { File(dir, it) }
        id.startsWith("jdk;") -> listOf(File(dir, "bin/java"))
        id.startsWith("ndk;") -> {
            val host = File(dir, "toolchains/llvm/prebuilt").listFiles { f -> f.isDirectory }?.firstOrNull()
            if (host == null) emptyList() else listOf(File(host, "bin/clang"))
        }
        else -> emptyList()
    }

    /** Every native tool of the package that could be inspected. Empty = nothing to check. */
    fun inspect(id: String, dir: File): List<Item> {
        val want = deviceMachine() ?: return emptyList()
        return filesOf(id, dir).mapNotNull { f ->
            val m = machineOf(f) ?: return@mapNotNull null
            Item(f.name, machineName(m), m == want)
        }
    }

    /** Short warning like "aapt2, zipalign are x86-64 (phone is arm64)", or null if all fine. */
    fun warning(id: String, dir: File): String? {
        val bad = inspect(id, dir).filter { it.ok == false }
        if (bad.isEmpty()) return null
        val phone = deviceMachine()?.let { machineName(it) } ?: "this CPU"
        return bad.joinToString(", ") { it.name } + " built for ${bad.first().machine} - will not run on this $phone phone"
    }

    /** Log lines for the install dialog. */
    fun report(id: String, dir: File, log: TaskLog) {
        val items = inspect(id, dir)
        if (items.isEmpty()) return
        log.line("Verifying tools for this phone...")
        items.forEach {
            if (it.ok == true) log.line("  ✔ ${it.name} (${it.machine})")
            else log.line("  ⚠ ${it.name} is ${it.machine} - will not run on this phone")
        }
        if (items.any { it.ok == false } && NativeToolsFix.fixable(id)) {
            log.line("These tools are for another CPU - arm64 versions are installed automatically next (or use \"Fix tools\").")
        }
    }
}
