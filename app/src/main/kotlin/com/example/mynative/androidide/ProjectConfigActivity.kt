package com.example.mynative.androidide

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.mynative.androidide.databinding.ActivityProjectConfigBinding
import java.io.File

class ProjectConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectConfigBinding

    private val templateId by lazy { intent.getStringExtra(EXTRA_TEMPLATE) ?: "NativeCpp" }
    private val isGame get() = templateId == "GameActivity"
    private val isNative get() = templateId == "NativeCpp"
    private val isPlain get() = templateId == "PlainEditor"
    private val isCompose get() = ProjectTemplates.isCompose(templateId)
    private val isFlutter get() = templateId == "FlutterActivity"
    private val isLibrary get() = templateId == "Library"
    private val isJavaLib get() = isLibrary && binding.etLibType.text.toString() == "Java Library"

    private var packageEditedByUser = false
    private var settingPackage = false

    // Remembered between projects (package prefix, language, min SDK, location, Gradle DSL, Git).
    private val prefs by lazy { getSharedPreferences("newproject", MODE_PRIVATE) }
    private var defaultPrefix = "com.example.mynative."
    private var sdkNeeds: List<SdkNeeds.Need> = emptyList()

    private val nameRegex = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    private val packageRegex = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    private val sdkLevels = listOf(
        23 to "Android 6.0 (Marshmallow)",
        24 to "Android 7.0 (Nougat)",
        25 to "Android 7.1 (Nougat)",
        26 to "Android 8.0 (Oreo)",
        27 to "Android 8.1 (Oreo)",
        28 to "Android 9.0 (Pie)",
        29 to "Android 10 (Q)",
        30 to "Android 11 (R)",
        31 to "Android 12 (S)",
        32 to "Android 12L (SnowCone)",
        33 to "Android 13 (Tiramisu)",
        34 to "Android 14 (UpsideDownCake)",
        35 to "Android 15 (VanillaIceCream)",
        36 to "Android 16 (BakLava)",
        37 to "Android 17 (CinnamonBun)"
    ).map { (api, name) -> "API $api: $name" }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) binding.etLocation.setText(uriToPath(uri))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClose.setOnClickListener {
            setResult(RESULT_CLOSE_ALL)
            finish()
        }

        setupDropdown(binding.etLanguage, listOf("Kotlin", "Java"))
        // GameActivity: native code uses AImageDecoder, so API 30+ only.
        setupDropdown(binding.etMinSdk, if (isGame) sdkLevels.filter { apiOf(it) >= 30 } else sdkLevels)
        // The game's code is C++ (Renderer, Shader ...), so Game only offers C++; NativeCpp also offers C.
        setupDropdown(binding.etNative, if (isGame) listOf("C++") else listOf("C++", "C"))
        setupDropdown(binding.etLibType, listOf("Android Library", "Java Library"))

        // Restore what was used last time.
        prefs.getString("lang", null)?.takeIf { it == "Kotlin" || it == "Java" }
            ?.let { binding.etLanguage.setText(it, false) }
        val minItems = if (isGame) sdkLevels.filter { apiOf(it) >= 30 } else sdkLevels
        prefs.getInt("minSdk", 0).takeIf { it > 0 }?.let { api ->
            minItems.firstOrNull { apiOf(it) == api }?.let { binding.etMinSdk.setText(it, false) }
        }
        prefs.getString("pkgPrefix", null)
            ?.takeIf { Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*\\.$").matches(it) }
            ?.let { defaultPrefix = it }
        binding.swKts.isChecked = prefs.getBoolean("kts", false)
        binding.swGit.isChecked = prefs.getBoolean("git", false)
        binding.swCmake.isChecked = true
        binding.swCmake.setOnCheckedChangeListener { _, _ -> checkSdk() }   // CMake on/off changes what is needed
        // Native C++ and Game Activity have the native language + CMake options.
        if (!isNative && !isGame) {
            binding.tilNative.visibility = android.view.View.GONE
            (binding.swCmake.parent as android.view.View).visibility = android.view.View.GONE
        }
        // Library only: "Android Library" (AAR) or "Java Library" (plain JVM jar - no Android SDK, so no Minimum SDK).
        if (isLibrary) {
            binding.tilLibType.visibility = android.view.View.VISIBLE
            binding.etLibType.setOnItemClickListener { _, _, _, _ ->
                binding.tilMinSdk.visibility = if (isJavaLib) android.view.View.GONE else android.view.View.VISIBLE
                checkSdk()
            }
        }
        if (isCompose) binding.tilLanguage.visibility = android.view.View.GONE      // Compose = Kotlin only
        if (isFlutter || isPlain) (binding.swKts.parent as android.view.View).visibility = android.view.View.GONE
        if (isPlain) {
            binding.tilPackage.visibility = android.view.View.GONE
            binding.tilLanguage.visibility = android.view.View.GONE
            binding.tilMinSdk.visibility = android.view.View.GONE
        }

        val privateDir = File(filesDir, "home/AndroidIDEProjects").absolutePath
        binding.etLocation.setText(
            // a location the user chose before is kept; the old private default moves to /storage/emulated/0/AndroidIDE
            prefs.getString("location", null)?.takeIf { it != privateDir && File(it).isDirectory }
                ?: defaultLocation(privateDir)
        )
        binding.tilLocation.setEndIconOnClickListener { folderPicker.launch(null) }

        binding.etName.doAfterTextChanged { text ->
            if (!packageEditedByUser) {
                settingPackage = true
                binding.etPackage.setText(defaultPackage(text?.toString().orEmpty()))
                settingPackage = false
            }
            validate()
        }
        binding.etPackage.doAfterTextChanged {
            if (!settingPackage) packageEditedByUser = true
            validate()
        }
        binding.etLocation.doAfterTextChanged { validate() }

        binding.btnCreate.setOnClickListener { onCreateClicked() }

        // Default name, same as the reference screen (shows the validation error).
        binding.etName.setText(
            when {
                isGame -> "MyGame"
                isNative -> "MyNativeC++"
                isPlain -> "MyProject"
                templateId == "Library" -> "MyLibrary"
                isFlutter -> "MyFlutterApp"
                else -> "MyApplication"
            }
        )
    }

    private fun setupDropdown(
        view: com.google.android.material.textfield.MaterialAutoCompleteTextView,
        items: List<String>
    ) {
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items))
        view.setText(items.first(), false)
    }

    private fun defaultPackage(name: String): String =
        defaultPrefix + name.lowercase().filter { it.isLetterOrDigit() }

    private fun validate() {
        val name = binding.etName.text?.toString().orEmpty()
        val pkg = binding.etPackage.text?.toString().orEmpty()
        val location = binding.etLocation.text?.toString().orEmpty()

        val nameOk = nameRegex.matches(name)
        binding.tilName.error = when {
            name.isEmpty() -> "Project name is required"
            !nameOk -> "Project name must start with a letter and contain only letters, numbers, and underscores"
            else -> null
        }

        val pkgOk = isPlain || packageRegex.matches(pkg)
        binding.tilPackage.error = if (pkg.isNotEmpty() && !pkgOk) "Invalid package name" else null

        binding.btnCreate.isEnabled = nameOk && pkgOk && location.isNotBlank()
    }

    private fun uriToPath(uri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(uri)   // e.g. "primary:nee"
        val volume = docId.substringBefore(':')
        val rest = docId.substringAfter(':', "")
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rest.isEmpty()) base else "$base/$rest"
    }

    private fun apiOf(item: String): Int =
        Regex("API (\\d+)").find(item)?.groupValues?.get(1)?.toInt() ?: 23

    private fun onCreateClicked() {
        val cfg = ProjectGenerator.Config(
            templateDir = templateId,
            name = binding.etName.text.toString().trim(),
            packageName = binding.etPackage.text.toString().trim(),
            location = binding.etLocation.text.toString().trim(),
            useKotlin = isCompose || binding.etLanguage.text.toString() == "Kotlin",
            minSdk = apiOf(binding.etMinSdk.text.toString()),
            useKts = binding.swKts.isChecked,
            nativeLanguage = binding.etNative.text.toString(),
            useCmake = binding.swCmake.isChecked,
            libraryType = if (isLibrary) binding.etLibType.text.toString() else "Android Library"
        )
        val wantGit = binding.swGit.isChecked
        prefs.edit().also { ed ->
            if (!isCompose && !isPlain) ed.putString("lang", binding.etLanguage.text.toString())
            if (!isGame && !isPlain && !cfg.isJavaLib) ed.putInt("minSdk", cfg.minSdk)
            if (!isFlutter && !isPlain) ed.putBoolean("kts", cfg.useKts)
            if (!isPlain && cfg.packageName.contains('.')) ed.putString("pkgPrefix", cfg.packageName.substringBeforeLast('.') + ".")
            ed.putBoolean("git", wantGit)
            ed.putString("location", cfg.location)
        }.apply()
        binding.btnCreate.isEnabled = false
        Thread {
            val result = runCatching {
                ProjectGenerator.generate(applicationContext, cfg).also { dir ->
                    // Background shell setup right after "Create" (no UI): make gradlew runnable
                    // and strip the inner quotes from its JVM opts (Android's xargs keeps them).
                    ShellRunner.runSilently(
                        applicationContext,
                        "[ -f gradlew ] && chmod +x gradlew && " +
                            "sed -i \"s/^DEFAULT_JVM_OPTS=.*/DEFAULT_JVM_OPTS='-Xmx64m -Xms64m'/\" gradlew; true",
                        dir
                    )
                    // Optional: git init + .gitignore + first commit (same identity as the Git panel).
                    if (wantGit) {
                        runCatching {
                            GitManager.init(dir)
                            val gp = getSharedPreferences("git", MODE_PRIVATE)
                            GitManager.commitAll(
                                dir, "Initial commit",
                                gp.getString("name", "").orEmpty().ifBlank { "AndroidIDE" },
                                gp.getString("email", "").orEmpty().ifBlank { "ide@localhost" }
                            )
                        }
                    }
                }
            }
            runOnUiThread {
                result.onSuccess { dir ->
                    startActivity(
                        android.content.Intent(this, ProjectActivity::class.java)
                            .putExtra(ProjectActivity.EXTRA_PATH, dir.path)
                            .putExtra(ProjectActivity.EXTRA_AUTO_SYNC, true)
                    )
                    setResult(RESULT_CLOSE_ALL)
                    finish()
                }.onFailure { e ->
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    validate()
                }
            }
        }.start()
    }

    // ---- "SDK ready?" check ----------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        checkSdk()      // also runs again after coming back from the SDK manager
    }

    private fun checkSdk() {
        val useCmake = binding.swCmake.isChecked
        val javaLib = isJavaLib          // read the view here, not on the worker thread
        Thread {
            val needs = runCatching { SdkNeeds.forTemplate(this, templateId, useCmake) }
                .getOrDefault(emptyList())
                .let { all -> if (javaLib) all.filter { it.id.startsWith("jdk") } else all }   // a JVM library only needs the JDK
            val rem = SdkIndex.cached(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sdkNeeds = needs
                val tv = binding.tvSdk
                if (needs.isEmpty()) {
                    tv.text = "✔ SDK ready"
                    tv.setTextColor(0xFF2E7D32.toInt())
                    tv.isClickable = false
                    tv.setOnClickListener(null)
                } else {
                    val size = needs.sumOf { n -> rem?.firstOrNull { it.id == n.id }?.size ?: 0L }
                    tv.text = "⚠ Missing: " + needs.joinToString(", ") { SdkNeeds.label(it.id) } +
                        (if (size > 0) " (≈ " + Formatter.formatShortFileSize(this, size) + ")" else "") +
                        "\nTap to install"
                    tv.setTextColor(0xFFB26A00.toInt())
                    tv.isClickable = true
                    tv.setOnClickListener {
                        startActivity(
                            Intent(this, SdkManagerActivity::class.java)
                                .putExtra(SdkManagerActivity.EXTRA_IDS, sdkNeeds.map { it.id }.toTypedArray())
                        )
                    }
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_TEMPLATE = "template"
        const val RESULT_CLOSE_ALL = 100
    }

    /**
     * New projects go to <storage>/AndroidIDE/<project name> so they are visible in the file manager and
     * every edit is saved there. Needs "All files access"; without it the private folder is used.
     */
    private fun defaultLocation(privateDir: String): String {
        val allFiles = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
        if (!allFiles) return privateDir
        val shared = File(android.os.Environment.getExternalStorageDirectory(), "AndroidIDE")
        return if (shared.isDirectory || shared.mkdirs()) shared.absolutePath else privateDir
    }
}
