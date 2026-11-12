package com.example.mynative.androidide

import android.content.Context
import org.json.JSONObject

/**
 * assets/template/<id>/template.json - what a template needs on the phone before its first build
 * (and a short note). The "SDK ready" check, the template details sheet and the SDK manager all
 * read it, so a new template only has to ship its own template.json.
 *
 *   { "needs": { "platform": true, "buildTools": true, "jdk": 17, "ndk": false, "cmake": "4.1.2" },
 *     "minSdk": 30, "note": "..." }
 */
class TemplateMeta(
    val minSdk: Int,            // 0 = no special minimum
    val platform: Boolean,      // SDK platform (compileSdk)
    val buildTools: Boolean,
    val jdk: Int,               // minimum JDK major version, 0 = not needed
    val ndk: Boolean,
    val cmake: String?,         // CMake version the generated build file asks for (only if CMake is chosen)
    val note: String
) {
    companion object {
        fun load(ctx: Context, templateId: String): TemplateMeta? = runCatching {
            val text = ctx.assets.open("template/$templateId/template.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val j = JSONObject(text)
            val n = j.optJSONObject("needs") ?: JSONObject()
            TemplateMeta(
                minSdk = j.optInt("minSdk", 0),
                platform = n.optBoolean("platform", false),
                buildTools = n.optBoolean("buildTools", false),
                jdk = n.optInt("jdk", 0),
                ndk = n.optBoolean("ndk", false),
                cmake = n.optString("cmake", "").ifEmpty { null },
                note = j.optString("note", "")
            )
        }.getOrNull()
    }
}
