package com.example.mynative.androidide

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Looks inside a freshly built APK for the usual reasons Android answers "package appears to be invalid". */
object ApkCheck {

    /** Empty list = the APK looks installable. */
    fun problems(ctx: Context, apk: File): List<String> {
        val out = ArrayList<String>()
        if (!apk.isFile || apk.length() < 1024) return listOf("APK file is missing or empty")

        try {
            ZipFile(apk).use { z ->
                val names = z.entries().asSequence().map { it.name }.toSet()
                for (n in listOf("AndroidManifest.xml", "classes.dex")) if (n !in names) out.add("$n is missing inside the APK")
                z.getEntry("resources.arsc")?.let { e ->
                    if (e.method != ZipEntry.STORED) out.add("resources.arsc is compressed (apps targeting Android 11+ need it stored uncompressed)")
                }
                // read every entry to completion: a truncated / half-written file fails its CRC here
                val buf = ByteArray(64 * 1024)
                for (e in z.entries()) if (!e.isDirectory) z.getInputStream(e).use { s -> while (s.read(buf) >= 0) { /* verify CRC */ } }
                val v1 = names.any { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC")) }
                if (!v1 && !hasV2Block(apk)) out.add("APK is not signed")
            }
        } catch (e: Exception) {
            out.add("APK file is corrupt: ${e.message}")
        }

        // android:testOnly="true" -> the normal installer rejects it with "package appears to be invalid"
        runCatching { ctx.packageManager.getPackageArchiveInfo(apk.path, 0)?.applicationInfo }.getOrNull()?.let { ai ->
            if (ai.flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY != 0)
                out.add("Manifest has android:testOnly=\"true\" - Android refuses to install it (rebuild without -Pandroid.injected.* flags)")
        }

        if (out.isEmpty() && runCatching { ctx.packageManager.getPackageArchiveInfo(apk.path, 0) }.getOrNull() == null) {
            out.add("Android itself cannot parse this APK (getPackageArchiveInfo returned null)")
        }
        return out
    }

    /** APK Signature Scheme v2+ block sits right before the zip central directory. */
    private fun hasV2Block(apk: File): Boolean = runCatching {
        RandomAccessFile(apk, "r").use { f ->
            val len = f.length()
            val tail = minOf(len, 65557L).toInt()
            val b = ByteArray(tail)
            f.seek(len - tail); f.readFully(b)
            var i = tail - 22
            while (i >= 0 && !(b[i] == 0x50.toByte() && b[i + 1] == 0x4b.toByte() && b[i + 2] == 5.toByte() && b[i + 3] == 6.toByte())) i--
            if (i < 0) return@use false
            val cd = (b[i + 16].toLong() and 0xff) or ((b[i + 17].toLong() and 0xff) shl 8) or
                ((b[i + 18].toLong() and 0xff) shl 16) or ((b[i + 19].toLong() and 0xff) shl 24)
            if (cd < 16) return@use false
            val magic = ByteArray(16)
            f.seek(cd - 16); f.readFully(magic)
            String(magic, Charsets.ISO_8859_1) == "APK Sig Block 42"
        }
    }.getOrDefault(false)
}
