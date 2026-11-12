package com.example.mynative.androidide

import android.content.Context
import java.io.File

/**
 * Pre-flight version check, run before a sync / build: AGP <-> Gradle <-> JDK.
 * A mismatch here is the cause of many confusing sync errors, so it is reported (with a fix)
 * before Gradle is even started. Only versions that are in the tables below are checked;
 * anything newer is left alone so a new AGP never produces a false warning.
 */
object CompatChecker {

    class Issue(val message: String, val fixes: List<BuildFixer.Fix>)

    // AGP major.minor -> (minimum Gradle, minimum JDK to run AGP)   [Android Gradle plugin release notes]
    private val agpTable = mapOf(
        "7.0" to ("7.0" to 11), "7.1" to ("7.2" to 11), "7.2" to ("7.3.3" to 11),
        "7.3" to ("7.4" to 11), "7.4" to ("7.5" to 11),
        "8.0" to ("8.0" to 17), "8.1" to ("8.0" to 17), "8.2" to ("8.2" to 17),
        "8.3" to ("8.4" to 17), "8.4" to ("8.6" to 17), "8.5" to ("8.7" to 17),
        "8.6" to ("8.7" to 17), "8.7" to ("8.9" to 17), "8.8" to ("8.10.2" to 17),
        "8.9" to ("8.11.1" to 17), "8.10" to ("8.11.1" to 17), "8.11" to ("8.13" to 17),
        "8.12" to ("8.13" to 17), "8.13" to ("8.13" to 17), "9.0" to ("9.1.0" to 17)
    )

    // minimum Gradle that can RUN on a given Java version, newest first   [Gradle compatibility matrix]
    private val gradleForJava = listOf(
        25 to "9.1.0", 24 to "8.14", 23 to "8.10", 22 to "8.8", 21 to "8.5",
        20 to "8.3", 19 to "7.6", 18 to "7.5", 17 to "7.3"
    )

    fun minGradleForJdk(java: Int): String? = gradleForJava.firstOrNull { it.first == java }?.second

    /** Highest Java a Gradle version supports; null = unknown (newer than the table). */
    fun maxJavaForGradle(gradle: String): Int? {
        if (cmp(gradle, "9.1.0") >= 0) return null
        for ((java, g) in gradleForJava) if (cmp(gradle, g) >= 0) return java
        return 16
    }

    /** Compares dotted versions numerically: 8.10 > 8.9, 8.13 == 8.13.0. Non-numeric tails are ignored. */
    fun cmp(a: String, b: String): Int {
        fun parts(v: String) = v.split('.', '-', '+').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    // ---- reading versions -----------------------------------------------------------------------

    fun gradleVersion(workDir: File): String? = runCatching {
        Regex("gradle-([\\d.]+)(?:-(?:rc|milestone)-\\d+)?-(?:bin|all)\\.zip")
            .find(File(workDir, "gradle/wrapper/gradle-wrapper.properties").readText())?.groupValues?.get(1)
    }.getOrNull()

    fun agpVersion(workDir: File): String? {
        val res = listOf(
            Regex("com\\.android\\.tools\\.build:gradle:([\\d.]+)"),
            Regex("id\\s*\\(?\\s*['\"]com\\.android\\.(?:application|library)['\"]\\s*\\)?\\s*version\\s*\\(?\\s*['\"]([\\d.]+)"),
            Regex("(?m)^\\s*(?:agp|androidGradlePlugin|android-gradle-plugin|androidGradle)\\s*=\\s*\"([\\d.]+)")
        )
        for (f in GradleTuner.syncFiles(workDir)) {
            val t = runCatching { f.readText() }.getOrNull() ?: continue
            for (re in res) re.find(t)?.let { return it.groupValues[1] }
        }
        return null
    }

    // ---- check ----------------------------------------------------------------------------------

    fun check(ctx: Context, workDir: File): List<Issue> {
        val jdk = TermEnvironment.javaMajor(ctx) ?: return emptyList()     // no JDK: the run itself reports that
        val gradle = gradleVersion(workDir)
        val agp = agpVersion(workDir)
        val issues = ArrayList<Issue>()
        val openJdk = BuildFixer.Fix("☕ JDK manager", BuildFixer.Kind.OPEN_SDK)

        val row = agp?.split('.')?.take(2)?.joinToString(".")?.let { agpTable[it] }
        if (agp != null && row != null) {
            val (needGradle, needJdk) = row
            if (jdk < needJdk)
                issues.add(Issue("Android Gradle plugin $agp needs Java $needJdk or newer, but Java $jdk is installed.", listOf(openJdk)))
            if (gradle != null && cmp(gradle, needGradle) < 0)
                issues.add(Issue(
                    "Android Gradle plugin $agp needs Gradle $needGradle or newer, but the wrapper uses $gradle.",
                    listOf(BuildFixer.Fix("⬆ Gradle $needGradle") { wd -> BuildFixer.setWrapperVersion(wd, needGradle) })
                ))
        }
        if (gradle != null) {
            val maxJava = maxJavaForGradle(gradle)
            if (maxJava != null && jdk > maxJava) {
                val fixes = ArrayList<BuildFixer.Fix>()
                minGradleForJdk(jdk)?.let { g -> fixes.add(BuildFixer.Fix("⬆ Gradle $g") { wd -> BuildFixer.setWrapperVersion(wd, g) }) }
                fixes.add(openJdk)
                issues.add(Issue("Gradle $gradle supports Java up to $maxJava, but Java $jdk is installed.", fixes))
            }
        }
        return issues
    }
}
