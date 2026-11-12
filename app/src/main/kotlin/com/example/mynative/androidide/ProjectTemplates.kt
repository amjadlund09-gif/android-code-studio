package com.example.mynative.androidide

/** Gradle files (Groovy or Kotlin DSL) and version catalog for the code-generated templates. */
object ProjectTemplates {

    /** SDK levels the generated Gradle files ask for (also used by the "SDK ready" check). */
    const val COMPILE_SDK = 37
    const val NDK_VERSION = "29.0.14206865"

    private class Dsl(val kts: Boolean) {
        fun str(s: String) = if (kts) "\"$s\"" else "'$s'"
        fun set(k: String, v: String) = if (kts) "$k = $v" else "$k $v"
        fun call(fn: String, arg: String) = if (kts) "$fn($arg)" else "$fn $arg"
        fun file(p: String) = if (kts) "file(\"$p\")" else "'$p'"
    }

    private val versions = linkedMapOf(
        "agp" to "9.4.1",
        "kotlin" to "2.4.21",
        "coreKtx" to "1.19.1",
        "appcompat" to "1.8.0",
        "material" to "1.14.0",
        "activityCompose" to "1.10.1",
        "composeBom" to "2025.05.01",
        "navigationCompose" to "2.9.0",
        "lifecycle" to "2.9.0",
        "datastore" to "1.1.4"
    )

    private class Lib(val group: String, val name: String, val versionKey: String?)

    private val libs = linkedMapOf(
        "androidx-core-ktx" to Lib("androidx.core", "core-ktx", "coreKtx"),
        "androidx-appcompat" to Lib("androidx.appcompat", "appcompat", "appcompat"),
        "material" to Lib("com.google.android.material", "material", "material"),
        "androidx-activity-compose" to Lib("androidx.activity", "activity-compose", "activityCompose"),
        "androidx-compose-bom" to Lib("androidx.compose", "compose-bom", "composeBom"),
        "androidx-ui" to Lib("androidx.compose.ui", "ui", null),
        "androidx-ui-graphics" to Lib("androidx.compose.ui", "ui-graphics", null),
        "androidx-ui-tooling" to Lib("androidx.compose.ui", "ui-tooling", null),
        "androidx-ui-tooling-preview" to Lib("androidx.compose.ui", "ui-tooling-preview", null),
        "androidx-material3" to Lib("androidx.compose.material3", "material3", null),
        "androidx-navigation-compose" to Lib("androidx.navigation", "navigation-compose", "navigationCompose"),
        "androidx-lifecycle-viewmodel-compose" to Lib("androidx.lifecycle", "lifecycle-viewmodel-compose", "lifecycle"),
        "androidx-lifecycle-runtime-compose" to Lib("androidx.lifecycle", "lifecycle-runtime-compose", "lifecycle"),
        "androidx-datastore-preferences" to Lib("androidx.datastore", "datastore-preferences", "datastore")
    )

    private val viewDeps = listOf(
        "implementation" to "androidx-core-ktx",
        "implementation" to "androidx-appcompat",
        "implementation" to "material"
    )

    private val composeDeps = listOf(
        "implementation" to "androidx-core-ktx",
        "implementation" to "androidx-activity-compose",
        "implementation" to "platform:androidx-compose-bom",
        "implementation" to "androidx-ui",
        "implementation" to "androidx-ui-graphics",
        "implementation" to "androidx-ui-tooling-preview",
        "implementation" to "androidx-material3"
    )

    private val composeNavDeps = composeDeps + listOf(
        "implementation" to "androidx-navigation-compose"
    )

    private val composeNotesDeps = composeDeps + listOf(
        "implementation" to "androidx-lifecycle-viewmodel-compose",
        "implementation" to "androidx-lifecycle-runtime-compose",
        "implementation" to "androidx-datastore-preferences"
    )

    private fun deps(cfg: ProjectGenerator.Config): List<Pair<String, String>> = when (cfg.templateDir) {
        "ComposeEmptyActivity" -> composeDeps
        "ComposeNavigation" -> composeNavDeps
        "ComposeNotes" -> composeNotesDeps
        "Library" -> if (cfg.useKotlin && !cfg.isJavaLib) listOf("implementation" to "androidx-core-ktx") else emptyList()
        else -> viewDeps
    }

    private fun accessor(alias: String) = "libs." + alias.replace('-', '.')

    fun isLibrary(id: String) = id == "Library"
    fun isCompose(id: String) = id in setOf("ComposeEmptyActivity", "ComposeNavigation", "ComposeNotes")
    fun moduleName(id: String) = if (isLibrary(id)) "library" else "app"

    fun catalog(cfg: ProjectGenerator.Config): String {
        val used = deps(cfg).map { it.second.removePrefix("platform:") }.distinct()
        val usedVersions = if (cfg.isJavaLib) mutableSetOf("kotlin") else mutableSetOf("agp", "kotlin")
        used.forEach { libs[it]?.versionKey?.let(usedVersions::add) }

        val sb = StringBuilder("[versions]\n")
        versions.filterKeys { it in usedVersions }.forEach { (k, v) -> sb.append("$k = \"$v\"\n") }
        sb.append("\n[plugins]\n")
        if (cfg.isJavaLib) {
            if (cfg.useKotlin) sb.append("kotlin-jvm = { id = \"org.jetbrains.kotlin.jvm\", version.ref = \"kotlin\" }\n")
        } else if (isLibrary(cfg.templateDir)) {
            sb.append("android-library = { id = \"com.android.library\", version.ref = \"agp\" }\n")
        } else {
            sb.append("android-application = { id = \"com.android.application\", version.ref = \"agp\" }\n")
        }
        if (isCompose(cfg.templateDir)) {
            sb.append("kotlin-compose = { id = \"org.jetbrains.kotlin.plugin.compose\", version.ref = \"kotlin\" }\n")
        }
        sb.append("\n[libraries]\n")
        used.forEach { alias ->
            val l = libs.getValue(alias)
            sb.append("$alias = { group = \"${l.group}\", name = \"${l.name}\"")
            l.versionKey?.let { sb.append(", version.ref = \"$it\"") }
            sb.append(" }\n")
        }
        return sb.toString()
    }

    fun settings(name: String, module: String) = """
        pluginManagement {
            repositories {
                google()
                mavenCentral()
                gradlePluginPortal()
            }
        }

        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }

        rootProject.name = "${name.replace("\"", "")}"

        include(":$module")
    """.trimIndent() + "\n"

    fun rootBuild(id: String): String {
        val sb = StringBuilder("// Top-level build file where you can add configuration options common to all sub-projects/modules.\nplugins {\n")
        if (isLibrary(id)) sb.append("    alias(libs.plugins.android.library) apply false\n")
        else sb.append("    alias(libs.plugins.android.application) apply false\n")
        if (isCompose(id)) sb.append("    alias(libs.plugins.kotlin.compose) apply false\n")
        sb.append("}\n")
        return sb.toString()
    }

    /** Root build file of a plain Java/Kotlin (JVM) library: no Android plugin at all. */
    fun rootBuildJavaLib(kotlin: Boolean): String {
        val sb = StringBuilder("// Top-level build file where you can add configuration options common to all sub-projects/modules.\nplugins {\n")
        if (kotlin) sb.append("    alias(libs.plugins.kotlin.jvm) apply false\n")
        sb.append("}\n")
        return sb.toString()
    }

    /** Module build file of a plain Java/Kotlin (JVM) library (produces a .jar, no Android SDK needed). */
    private fun javaLibBuild(cfg: ProjectGenerator.Config): String {
        val d = Dsl(cfg.useKts)
        val sb = StringBuilder("plugins {\n")
        sb.append(if (cfg.useKts) "    `java-library`\n" else "    id 'java-library'\n")
        if (cfg.useKotlin) sb.append("    alias(libs.plugins.kotlin.jvm)\n")
        sb.append("}\n\njava {\n")
        sb.append("    ${d.set("sourceCompatibility", "JavaVersion.VERSION_17")}\n")
        sb.append("    ${d.set("targetCompatibility", "JavaVersion.VERSION_17")}\n")
        sb.append("}\n")
        if (cfg.useKotlin) {
            sb.append("\nkotlin {\n    compilerOptions {\n")
            sb.append("        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)\n")
            sb.append("    }\n}\n")
        }
        return sb.toString()
    }

    /**
     * Keeps APKs small (≈2-4 MB instead of 8-10+): R8 code shrinking + resource shrinking for both build types
     * (unused appcompat / material / compose classes and resources are dropped) and no licence / metadata junk.
     * For faster debug builds while developing, set isMinifyEnabled / isShrinkResources to false inside debug { }.
     */
    fun appSizeBlock(kts: Boolean): String = if (kts) """

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/DebugProbesKt.bin", "/kotlin-tooling-metadata.json")
        }
    }
""" else """

    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    packaging {
        resources {
            excludes += ['/META-INF/{AL2.0,LGPL2.1}', '/META-INF/*.version', '/DebugProbesKt.bin', '/kotlin-tooling-metadata.json']
        }
    }
"""

    fun moduleBuild(cfg: ProjectGenerator.Config): String {
        if (cfg.isJavaLib) return javaLibBuild(cfg)
        val d = Dsl(cfg.useKts)
        val id = cfg.templateDir
        val lib = isLibrary(id)
        val compose = isCompose(id)
        val native = id == "NativeCpp"
        val kotlin = cfg.useKotlin || compose
        val sb = StringBuilder()

        sb.append("plugins {\n")
        sb.append("    alias(libs.plugins.android.${if (lib) "library" else "application"})\n")
        if (compose) sb.append("    alias(libs.plugins.kotlin.compose)\n")
        sb.append("}\n\nandroid {\n")
        sb.append("    ${d.set("namespace", d.str(cfg.packageName))}\n")
        sb.append("    ${d.set("compileSdk", COMPILE_SDK.toString())}\n")
        if (native) sb.append("    ${d.set("ndkVersion", d.str(NDK_VERSION))}\n")

        sb.append("\n    defaultConfig {\n")
        if (!lib) sb.append("        ${d.set("applicationId", d.str(cfg.packageName))}\n")
        sb.append("        ${d.set("minSdk", cfg.minSdk.toString())}\n")
        if (!lib) {
            sb.append("        ${d.set("targetSdk", "34")}\n")
            sb.append("        ${d.set("versionCode", "1")}\n")
            sb.append("        ${d.set("versionName", d.str("1.0"))}\n")
        } else {
            sb.append("        ${d.call("consumerProguardFiles", d.str("consumer-rules.pro"))}\n")
        }
        if (native) {
            // build only 64-bit ARM: much faster native build on the phone
            sb.append("\n        ndk {\n")
            sb.append(if (cfg.useKts) "            abiFilters += \"arm64-v8a\"\n" else "            abiFilters 'arm64-v8a'\n")
            sb.append("        }\n")
        }
        sb.append("    }\n")

        if (compose) {
            sb.append("\n    buildFeatures {\n        ${d.set("compose", "true")}\n    }\n")
        }

        if (native) {
            sb.append("\n    externalNativeBuild {\n")
            if (cfg.useCmake) {
                sb.append("        cmake {\n")
                sb.append("            ${d.set("path", d.file("src/main/cpp/CMakeLists.txt"))}\n")
                sb.append("            ${d.set("version", d.str("4.1.2"))}\n")
                sb.append("        }\n")
            } else {
                sb.append("        ndkBuild {\n")
                sb.append("            ${d.set("path", d.file("src/main/cpp/Android.mk"))}\n")
                sb.append("        }\n")
            }
            sb.append("    }\n")
        }

        if (!lib) sb.append(appSizeBlock(cfg.useKts))

        sb.append("\n    compileOptions {\n")
        sb.append("        ${d.set("sourceCompatibility", "JavaVersion.VERSION_17")}\n")
        sb.append("        ${d.set("targetCompatibility", "JavaVersion.VERSION_17")}\n")
        sb.append("    }\n")
        if (kotlin) {
            sb.append("    kotlin {\n        compilerOptions {\n")
            sb.append("            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)\n")
            sb.append("        }\n    }\n")
        }
        sb.append("}\n")

        val list = deps(cfg)
        if (list.isNotEmpty()) {
            sb.append("\ndependencies {\n")
            list.forEach { (conf, alias) ->
                val notation = if (alias.startsWith("platform:"))
                    "platform(${accessor(alias.removePrefix("platform:"))})"
                else accessor(alias)
                sb.append("    ${d.call(conf, notation)}\n")
            }
            sb.append("}\n")
        }
        return sb.toString()
    }
}
