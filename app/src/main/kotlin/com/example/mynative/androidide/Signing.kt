package com.example.mynative.androidide

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Release signing without touching the user's build files.
 *  - the keystore details live in <home>/.gradle/ide-signing.properties (app-private storage, owner-only)
 *  - an init script (passed with -I for release / non-debug runs) creates a signing config from it and
 *    attaches it to every non-debug build type that has no signingConfig of its own, so a signing
 *    config written by the user in build.gradle always wins
 *  - keytool (from the installed JDK) creates / verifies keystores; passwords go in via environment
 *    variables (-storepass:env), never on the command line
 */
object Signing {

    class Info(val storeFile: String, val storePassword: String, val keyAlias: String, val keyPassword: String)

    private fun gradleDir(ctx: Context) = File(TermEnvironment.home(ctx), ".gradle").apply { mkdirs() }
    private fun propsFile(ctx: Context) = File(gradleDir(ctx), "ide-signing.properties")

    fun defaultKeystore(ctx: Context) = File(TermEnvironment.home(ctx), "keystore/release.jks")

    fun enabled(ctx: Context): Boolean = propsFile(ctx).isFile

    fun load(ctx: Context): Info? = runCatching {
        val p = Properties()
        propsFile(ctx).inputStream().use { p.load(it) }
        Info(
            p.getProperty("storeFile") ?: return null, p.getProperty("storePassword") ?: return null,
            p.getProperty("keyAlias") ?: return null, p.getProperty("keyPassword") ?: return null
        )
    }.getOrNull()

    fun save(ctx: Context, i: Info) {
        val p = Properties().apply {
            setProperty("storeFile", i.storeFile); setProperty("storePassword", i.storePassword)
            setProperty("keyAlias", i.keyAlias); setProperty("keyPassword", i.keyPassword)
        }
        val f = propsFile(ctx)
        f.outputStream().use { p.store(it, "AndroidIDE release signing (private)") }
        f.setReadable(false, false); f.setReadable(true, true)       // owner only
        f.setWritable(false, false); f.setWritable(true, true)
    }

    /** Turns release signing off (the keystore file itself is NOT deleted). */
    fun clear(ctx: Context) { propsFile(ctx).delete() }

    // ---- keytool --------------------------------------------------------------------------------

    private fun keytool(ctx: Context): File? =
        TermEnvironment.javaHome(ctx)?.let { File(it, "bin/keytool") }?.takeIf { it.exists() }

    private fun runKeytool(ctx: Context, args: List<String>, storePass: String, keyPass: String?): Pair<Int, String> {
        val kt = keytool(ctx) ?: return -1 to "keytool not found in the installed JDK (install a full JDK in the SDK / JDK manager)"
        return try {
            val pb = ProcessBuilder(listOf(kt.path) + args).redirectErrorStream(true)
            TermEnvironment.applyTo(ctx, pb.environment())
            pb.environment()["IDE_KS_PASS"] = storePass
            if (keyPass != null) pb.environment()["IDE_KEY_PASS"] = keyPass
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() to out.trim()
        } catch (e: Exception) {
            -1 to "keytool failed: ${e.message}"
        }
    }

    /** Creates a new RSA 2048 keystore valid ~27 years. Returns null on success, else an error text. */
    fun generate(ctx: Context, file: File, alias: String, storePass: String, keyPass: String, name: String): String? {
        if (file.exists()) return "${file.name} already exists - pick another path (never overwrite a keystore you published with)"
        file.parentFile?.mkdirs()
        val cn = name.filter { it !in ",=+\"\\<>#;" }.ifBlank { "Android" }
        val (code, out) = runKeytool(
            ctx,
            listOf(
                "-genkeypair", "-keystore", file.path, "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000", "-storepass:env", "IDE_KS_PASS", "-keypass:env", "IDE_KEY_PASS", "-dname", "CN=$cn"
            ),
            storePass, keyPass
        )
        return if (code == 0 && file.isFile) null else out.ifBlank { "keytool exit $code" }
    }

    /** Checks that the keystore opens with this password and has the alias. Returns null when fine. */
    fun verify(ctx: Context, file: File, alias: String, storePass: String): String? {
        if (!file.isFile) return "Keystore file not found: ${file.path}"
        val (code, out) = runKeytool(ctx, listOf("-list", "-keystore", file.path, "-alias", alias, "-storepass:env", "IDE_KS_PASS"), storePass, null)
        return if (code == 0) null else out.lines().lastOrNull { it.isNotBlank() } ?: "keytool exit $code"
    }

    // ---- Gradle init script ---------------------------------------------------------------------

    private val INIT = """
allprojects { proj ->
    proj.pluginManager.withPlugin('com.android.application') {
        def f = new File(gradle.gradleUserHomeDir, 'ide-signing.properties')
        if (f.isFile()) {
            def p = new Properties()
            f.withInputStream { p.load(it) }
            def android = proj.extensions.getByName('android')
            def cfg = android.signingConfigs.create('ideSigning')
            cfg.storeFile = new File(p.getProperty('storeFile'))
            cfg.storePassword = p.getProperty('storePassword')
            cfg.keyAlias = p.getProperty('keyAlias')
            cfg.keyPassword = p.getProperty('keyPassword')
            android.buildTypes.configureEach { bt ->
                if (bt.name != 'debug' && bt.signingConfig == null) bt.signingConfig = cfg
            }
        }
    }
}
""".trimStart()

    fun initScript(ctx: Context): File {
        val f = File(gradleDir(ctx), "ide-sign.init.gradle")
        if (!f.isFile || f.readText() != INIT) f.writeText(INIT)
        return f
    }
}
