package com.example.mynative.androidide

import android.content.Context
import java.io.File

/** Creates a new project on disk from a template stored in assets/template/<templateDir>. */
object ProjectGenerator {

    class Config(
        val templateDir: String,
        val name: String,
        val packageName: String,
        val location: String,
        val useKotlin: Boolean,
        val minSdk: Int,
        val useKts: Boolean,
        val nativeLanguage: String = "C++",   // NativeCpp only: "C++" or "C"
        val useCmake: Boolean = true,         // NativeCpp only: CMake or ndk-build
        val libraryType: String = "Android Library"   // Library only: "Android Library" (AAR) or "Java Library" (JVM jar)
    ) {
        val isJavaLib get() = templateDir == "Library" && libraryType == "Java Library"
    }

    private val textExtensions = setOf(
        "kt", "java", "gradle", "kts", "xml", "toml", "txt", "md", "properties", "pro",
        "cpp", "h", "c", "cmake", "mk", "dart", "yaml", "yml", "sh", "json", "gitignore"
    )

    /** @return the project root folder. Throws with a readable message on failure. */
    fun generate(context: Context, cfg: Config): File {
        val base = "template/${cfg.templateDir}"
        val root = File(cfg.location, cfg.name)

        if (root.exists() && (root.list()?.isNotEmpty() == true)) {
            error("Folder already exists and is not empty: ${root.path}")
        }
        if (!root.exists() && !root.mkdirs()) {
            error("Cannot create folder: ${root.path}")
        }

        val libName = "native-lib"
        val srcFile = if (cfg.nativeLanguage == "C") "native-lib.c" else "native-lib.cpp"
        val tokens = mapOf(
            "__PACKAGE_NAME__" to cfg.packageName,
            "__PROJECT_NAME__" to cfg.name,
            "__MIN_SDK__" to cfg.minSdk.toString(),
            "__JNI_PACKAGE__" to cfg.packageName.replace("_", "_1").replace('.', '_'),
            "__PUB_NAME__" to cfg.name.lowercase().replace(Regex("[^a-z0-9_]"), "_"),
            "__LANG__" to if (cfg.nativeLanguage == "C") "C" else "CXX",
            "__SRC_FILE__" to srcFile,
            "__STL_LINE__" to if (cfg.nativeLanguage == "C") "" else "APP_STL := c++_static",
            "__LIB_NAME__" to libName
        )

        when (cfg.templateDir) {
            "GameActivity" -> generateGame(context, cfg, base, root, tokens)
            "PlainEditor" -> {
                copyTree(context, base, root, tokens, skipTop = setOf("template.json"))
                File(root, "main.sh").setExecutable(true)
            }
            "FlutterActivity" -> generateFlutter(context, cfg, base, root, tokens)
            else -> generateStandard(context, cfg, base, root, tokens)
        }
        return root
    }

    // ---- Game (assets contain both DSL variants) ------------------------------------------

    private fun generateGame(context: Context, cfg: Config, base: String, root: File, tokens: Map<String, String>) {
        val dsl = if (cfg.useKts) ".kts" else ""
        val main = File(root, "app/src/main")

        copyWrapper(context, root, tokens)
        write(File(root, "settings.gradle$dsl"), ProjectTemplates.settings(cfg.name, "app"))
        write(File(root, "build.gradle$dsl"), ProjectTemplates.rootBuild("GameActivity"))
        copy(context, "$base/gradle/libs.versions.toml", File(root, "gradle/libs.versions.toml"), tokens)
        copyGameBuildFile(context, cfg, "$base/app.build.gradle$dsl", File(root, "app/build.gradle$dsl"), tokens)
        // R8 is on for this template: native code finds the GameActivity classes by name over JNI
        write(File(root, "app/proguard-rules.pro"),
            "# Add project specific ProGuard rules here.\n" +
                "-keep class com.google.androidgamesdk.** { *; }\n" +
                "-keep class androidx.games.** { *; }\n")

        copy(context, "$base/AndroidManifest.xml", File(main, "AndroidManifest.xml"), tokens)
        copyTree(context, "$base/resources", File(main, "res"), tokens)
        // CMake (default) or ndk-build, chosen with "Use CMake"
        copyTree(context, "$base/native/cpp", File(main, "cpp"), tokens,
            skipTop = if (cfg.useCmake) emptySet() else setOf("CMakeLists.txt"))
        if (!cfg.useCmake) {
            copy(context, "$base/native/Android.mk", File(main, "cpp/Android.mk"), tokens)
            copy(context, "$base/native/Application.mk", File(main, "cpp/Application.mk"), tokens)
        }
        copy(context, "$base/android_robot.png", File(main, "assets/android_robot.png"), tokens)
        copySources(context, cfg, base, main, tokens)
    }

    /** app/build.gradle of the game: with "Use CMake" off, the cmake { } block becomes an ndkBuild { } block. */
    private fun copyGameBuildFile(context: Context, cfg: Config, assetPath: String, dest: File, tokens: Map<String, String>) {
        copy(context, assetPath, dest, tokens)
        if (cfg.useCmake) return
        val ndkBuild = if (cfg.useKts)
            "ndkBuild {\n            path = file(\"src/main/cpp/Android.mk\")\n        }"
        else
            "ndkBuild {\n            path 'src/main/cpp/Android.mk'\n        }"
        val text = dest.readText()
        val replaced = text.replace(Regex("cmake \\{[^{}]*\\}"), java.util.regex.Matcher.quoteReplacement(ndkBuild))
        check(replaced != text) { "Could not switch the game build file to ndk-build" }
        dest.writeText(replaced)
    }

    // ---- Standard Android templates (code-generated Gradle + asset sources) ----------------

    private fun generateStandard(context: Context, cfg: Config, base: String, root: File, tokens: Map<String, String>) {
        val id = cfg.templateDir
        val dsl = if (cfg.useKts) ".kts" else ""
        val module = ProjectTemplates.moduleName(id)
        val lib = ProjectTemplates.isLibrary(id)
        val main = File(root, "$module/src/main")

        copyWrapper(context, root, tokens)
        write(File(root, "settings.gradle$dsl"), ProjectTemplates.settings(cfg.name, module))
        write(File(root, "build.gradle$dsl"),
            if (cfg.isJavaLib) ProjectTemplates.rootBuildJavaLib(cfg.useKotlin) else ProjectTemplates.rootBuild(id))
        write(File(root, "gradle/libs.versions.toml"), ProjectTemplates.catalog(cfg))
        write(File(root, "$module/build.gradle$dsl"), ProjectTemplates.moduleBuild(cfg))
        if (lib) {
            if (!cfg.isJavaLib) {       // AAR only: a plain JVM jar has no manifest, proguard or consumer rules
                write(File(root, "$module/consumer-rules.pro"), "")
                write(File(root, "$module/proguard-rules.pro"), "# Add project specific ProGuard rules here.\n")
            }
            write(File(root, ".gitignore"), "build/\n.gradle/\nlocal.properties\n.idea/\n*.iml\n")
        } else {
            write(File(root, "$module/proguard-rules.pro"), "# Add project specific ProGuard rules here.\n")
        }

        if (!cfg.isJavaLib) copy(context, "$base/AndroidManifest.xml", File(main, "AndroidManifest.xml"), tokens)
        if (!lib) copyLauncherResources(context, File(main, "res"), tokens)
        if (hasAsset(context, "$base/resources")) copyTree(context, "$base/resources", File(main, "res"), tokens)
        copySources(context, cfg, base, main, tokens)

        if (id == "NativeCpp") {
            val cpp = File(main, "cpp")
            val src = tokens.getValue("__SRC_FILE__")
            copy(context, "$base/native/$src", File(cpp, src), tokens)
            if (cfg.useCmake) {
                copy(context, "$base/native/CMakeLists.txt", File(cpp, "CMakeLists.txt"), tokens)
            } else {
                copy(context, "$base/native/Android.mk", File(cpp, "Android.mk"), tokens)
                copy(context, "$base/native/Application.mk", File(cpp, "Application.mk"), tokens)
            }
        }
    }

    // ---- Flutter ---------------------------------------------------------------------------

    private fun generateFlutter(context: Context, cfg: Config, base: String, root: File, tokens: Map<String, String>) {
        val android = File(root, "android")
        val main = File(android, "app/src/main")

        copyTree(context, "$base/flutter-root", root, tokens)
        File(root, "gitignore.txt").let { if (it.exists()) it.renameTo(File(root, ".gitignore")) }

        copyTree(context, "$base/android-root", android, tokens)
        // wrapper script + jar from the shared files (properties come from the template: Gradle 8.x for the Flutter plugin)
        copy(context, "template/common/gradlew", File(android, "gradlew"), tokens)
        File(android, "gradlew").setExecutable(true)
        copy(context, "template/common/gradlew.bat", File(android, "gradlew.bat"), tokens)
        copy(context, "template/common/gradle/wrapper/gradle-wrapper.jar",
            File(android, "gradle/wrapper/gradle-wrapper.jar"), tokens)

        copyLauncherResources(context, File(main, "res"), tokens)
        copySources(context, cfg, base, main, tokens)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun copySources(context: Context, cfg: Config, base: String, main: File, tokens: Map<String, String>) {
        val kotlin = cfg.useKotlin || ProjectTemplates.isCompose(cfg.templateDir)
        val lang = if (kotlin) "kotlin" else "java"
        val pkgPath = cfg.packageName.replace('.', '/')
        if (hasAsset(context, "$base/$lang")) {
            copyTree(context, "$base/$lang", File(main, "$lang/$pkgPath"), tokens)
        }
    }

    private fun copyWrapper(context: Context, root: File, tokens: Map<String, String>) {
        copy(context, "template/common/gradlew", File(root, "gradlew"), tokens)
        File(root, "gradlew").setExecutable(true)
        copy(context, "template/common/gradlew.bat", File(root, "gradlew.bat"), tokens)
        copy(context, "template/common/gradle.properties", File(root, "gradle.properties"), tokens)
        copy(context, "template/common/gradle/wrapper/gradle-wrapper.jar",
            File(root, "gradle/wrapper/gradle-wrapper.jar"), tokens)
        copy(context, "template/common/gradle/wrapper/gradle-wrapper.properties",
            File(root, "gradle/wrapper/gradle-wrapper.properties"), tokens)
    }

    /** Launcher icons + backup rules (shared, taken from the GameActivity template). */
    private fun copyLauncherResources(context: Context, res: File, tokens: Map<String, String>) {
        copyTree(context, "template/GameActivity/resources", res, tokens, skipTop = setOf("values", "values-night"))
        // <adaptive-icon> needs API 26+: keep it in a -v26 folder so projects with minSdk < 26 still link
        // (the PNG icons in mipmap-*dpi are the fallback for older phones)
        val any = File(res, "mipmap-anydpi")
        if (any.isDirectory) any.renameTo(File(res, "mipmap-anydpi-v26"))
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun hasAsset(context: Context, path: String): Boolean {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return context.assets.list(parent)?.contains(name) == true
    }

    private fun copyTree(
        context: Context, assetPath: String, dest: File, tokens: Map<String, String>,
        skipTop: Set<String> = emptySet()
    ) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {            // a file
            copy(context, assetPath, dest, tokens)
            return
        }
        dest.mkdirs()
        children.filter { it !in skipTop }
            .forEach { copyTree(context, "$assetPath/$it", File(dest, it), tokens) }
    }

    private fun copy(context: Context, assetPath: String, dest: File, tokens: Map<String, String>) {
        dest.parentFile?.mkdirs()
        val ext = dest.extension.lowercase()
        context.assets.open(assetPath).use { input ->
            if (ext in textExtensions || dest.name == "gradlew") {
                var text = input.readBytes().toString(Charsets.UTF_8)
                tokens.forEach { (k, v) -> text = text.replace(k, v) }
                dest.writeText(text)
            } else {
                dest.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
