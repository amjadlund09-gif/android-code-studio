package com.example.mynative.androidide

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A real pseudo-terminal child process (native, see cpp/pty.cpp).
 * Gives you proper Ctrl+C (send 0x03), line editing, colours and window size.
 */
class Pty private constructor(private val pid: Int, private val pfd: ParcelFileDescriptor) {

    val input = FileInputStream(pfd.fileDescriptor)
    val output = FileOutputStream(pfd.fileDescriptor)
    private val fdInt = pfd.fd

    fun write(text: String) {
        try { output.write(text.toByteArray(Charsets.UTF_8)); output.flush() } catch (_: Exception) {}
    }

    fun write(bytes: ByteArray) {
        try { output.write(bytes); output.flush() } catch (_: Exception) {}
    }

    fun resize(rows: Int, cols: Int) = runCatching { PtyNative.nativeResize(fdInt, rows, cols) }

    fun waitFor(): Int = PtyNative.nativeWaitFor(pid)

    fun kill(sig: Int = 9) = runCatching { PtyNative.nativeKill(pid, sig) }

    fun close() {
        runCatching { pfd.close() }
    }

    companion object {
                /** True when libmyapplication.so loaded and has the PTY symbols. */
        val available: Boolean by lazy {
            runCatching { System.loadLibrary("myapplication"); true }.getOrDefault(false)
        }

        fun start(cmd: String, args: List<String>, env: Map<String, String>, cwd: File, rows: Int = 40, cols: Int = 90): Pty? {
            if (!available) return null
            val r = try {
                PtyNative.nativeStart(cmd, args.toTypedArray(), env.map { "${it.key}=${it.value}" }.toTypedArray(),
                    cwd.path, rows, cols)
            } catch (_: UnsatisfiedLinkError) { null } ?: return null
            return Pty(r[1], ParcelFileDescriptor.adoptFd(r[0]))
        }
    }
}

/** JNI entry points (symbols: Java_com_example_mynative_androidide_PtyNative_*). */
object PtyNative {
    external fun nativeStart(cmd: String, args: Array<String>, env: Array<String>, cwd: String, rows: Int, cols: Int): IntArray?
    external fun nativeResize(fd: Int, rows: Int, cols: Int)
    external fun nativeWaitFor(pid: Int): Int
    external fun nativeKill(pid: Int, sig: Int)
}
