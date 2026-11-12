package com.example.mynative.androidide

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import com.example.mynative.androidide.databinding.ActivitySetupBinding
import com.example.mynative.androidide.databinding.DialogSdkTerminalBinding
import com.example.mynative.androidide.databinding.ItemSetupComponentBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * First-run "SDK Installation" screen.
 *
 *  - Recommended versions are pre-selected (SDK platform, Build Tools, JDK; NDK = Skip).
 *  - "Automatic installation" ON  -> everything selected is downloaded.
 *    OFF                          -> offline: only components with "Use my ZIP" are installed.
 *  - Every component can use the user's own ZIP instead of a download.
 *  - "Skip" leaves everything for later (SDK Manager still works, and a project that needs a
 *    package offers to install it).
 */
class SetupActivity : AppCompatActivity() {

    private class Choice(val label: String, val value: String?)      // value == null -> Skip

    private class Job(val id: String, val label: String, val zip: Uri?)

    private inner class Row(val tab: SdkTab, val title: String) {
        lateinit var ui: ItemSetupComponentBinding
        var choices: List<Choice> = emptyList()
        var selected: Choice? = null
        var picked: String? = null          // version the user chose (null = Skip)
        var touched = false                 // user changed it -> keep it when the live list refreshes
        var zipUri: Uri? = null
        var zipName: String? = null
    }

    private lateinit var binding: ActivitySetupBinding
    private lateinit var sdkRoot: File
    private lateinit var rows: List<Row>
    private var remote: List<RemoteSdkPkg>? = null
    private var pendingRow: Row? = null

    @Volatile private var installing = false
    @Volatile private var taskStopped = false
    private var lastOk = false

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val r = pendingRow ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        r.zipUri = uri
        r.zipName = queryName(uri) ?: "selected.zip"
        if (r.selected?.value == null) {
            // A ZIP needs a version (it decides the target folder): use the recommended one.
            val values = SdkRepository.choices(r.tab, remote)
            val v = recommended(r.tab, values) ?: values.firstOrNull()
            r.selected = r.choices.firstOrNull { it.value == v } ?: r.choices.firstOrNull { it.value != null }
            r.picked = r.selected?.value
            r.touched = true
            r.selected?.let {
                Toast.makeText(this, "ZIP will be installed as ${it.label}. Change the version above if it differs.", Toast.LENGTH_LONG).show()
            }
        }
        render(r)
        updateSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sdkRoot = SdkRepository.sdkRoot(this)
        remote = SdkIndex.cached(this)

        rows = listOf(
            Row(SdkTab.PLATFORMS, "Android SDK platform"),
            Row(SdkTab.BUILD_TOOLS, "Build Tools version"),
            Row(SdkTab.JDK, "JDK version"),
            Row(SdkTab.NDK, "Android NDK version")
        )
        rows.forEach { r ->
            r.ui = ItemSetupComponentBinding.inflate(layoutInflater, binding.rows, false)
            r.ui.til.hint = r.title
            r.ui.btnZip.setOnClickListener {
                pendingRow = r
                pickZip.launch(arrayOf("*/*"))
            }
            r.ui.btnZipClear.setOnClickListener {
                r.zipUri = null; r.zipName = null
                render(r); updateSummary()
            }
            binding.rows.addView(r.ui.root)
        }
        fillChoices()

        binding.switchAuto.setOnCheckedChangeListener { _, _ -> updateMode() }
        binding.cbPlatformTools.setOnCheckedChangeListener { _, _ -> updateSummary() }
        binding.btnSkip.setOnClickListener { confirmSkip() }
        binding.btnInstall.setOnClickListener { startInstall() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!installing) confirmSkip()
            }
        })

        updateMode()
        loadIndex()
    }

    // ---- choices -------------------------------------------------------------------------

    /** Newest stable list from Google (cached 24h); the built-in list is used until/unless it loads. */
    private fun loadIndex() {
        if (remote != null && !SdkIndex.isStale(this)) return
        Thread {
            val r = runCatching { SdkIndex.load(this) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { remote = it; fillChoices(); updateSummary() }
            }
        }.start()
    }

    private fun labelOf(tab: SdkTab, v: String) = when (tab) {
        SdkTab.PLATFORMS -> "API " + v.removePrefix("android-")
        SdkTab.JDK -> "JDK $v"
        else -> v
    }

    /** What we recommend for each component (what the app's own templates and Gradle setup expect). */
    private fun recommended(tab: SdkTab, values: List<String>): String? = when (tab) {
        SdkTab.PLATFORMS -> "android-${ProjectTemplates.COMPILE_SDK}".takeIf { it in values } ?: values.firstOrNull()
        SdkTab.BUILD_TOOLS -> values.firstOrNull()
        SdkTab.JDK -> "17".takeIf { it in values } ?: values.firstOrNull()
        SdkTab.NDK -> ProjectTemplates.NDK_VERSION.takeIf { it in values } ?: values.firstOrNull()
        else -> null
    }

    /** Pre-selected value: the recommendation, except NDK which is Skip (only native projects need it). */
    private fun defaultValue(tab: SdkTab, values: List<String>): String? =
        if (tab == SdkTab.NDK) null else recommended(tab, values)

    private fun fillChoices() {
        for (r in rows) {
            val values = SdkRepository.choices(r.tab, remote)
            val list = values.map { Choice(labelOf(r.tab, it), it) } + Choice("Skip", null)
            r.choices = list
            val want = if (r.touched) r.picked else defaultValue(r.tab, values)
            r.selected = list.firstOrNull { it.value == want } ?: list.last()
            r.picked = r.selected?.value
            render(r)
        }
    }

    private fun render(r: Row) {
        val labels = r.choices.map { it.label }
        r.ui.act.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        r.ui.act.setText(r.selected?.label ?: "", false)
        r.ui.act.setOnItemClickListener { parent, _, pos, _ ->
            val label = parent.getItemAtPosition(pos) as String
            r.selected = r.choices.firstOrNull { it.label == label }
            r.picked = r.selected?.value
            r.touched = true
            updateSummary()
        }
        val hasZip = r.zipUri != null
        r.ui.tvZip.visibility = if (hasZip) View.VISIBLE else View.GONE
        r.ui.btnZipClear.visibility = if (hasZip) View.VISIBLE else View.GONE
        r.ui.tvZip.text = r.zipName.orEmpty()
        r.ui.btnZip.text = if (hasZip) "Change ZIP" else "Use my ZIP"
    }

    private fun updateMode() {
        val auto = binding.switchAuto.isChecked
        binding.tvMode.text = if (auto)
            "The selected versions are downloaded (SDK from Google, JDK from the Termux repository)."
        else
            "Offline mode: nothing is downloaded. Only components where you pick \"Use my ZIP\" are installed."
        binding.cbPlatformTools.isEnabled = auto
        updateSummary()
    }

    private fun updateSummary() {
        val auto = binding.switchAuto.isChecked
        val jobs = buildJobs()
        val free = Formatter.formatFileSize(this, filesDir.usableSpace)
        var bytes = 0L
        var jdk = false
        var zips = 0
        for (j in jobs) {
            if (j.zip != null) { zips++; continue }
            if (j.id.startsWith("jdk;")) { jdk = true; continue }
            if (!SdkRepository.isInstalled(this, j.id)) bytes += remote?.firstOrNull { it.id == j.id }?.size ?: 0L
        }
        val parts = ArrayList<String>()
        if (auto) {
            val dl = if (bytes > 0) "~" + Formatter.formatShortFileSize(this, bytes) else ""
            parts += "Download: " + listOf(dl, if (jdk) "+ JDK" else "").filter { it.isNotEmpty() }
                .joinToString(" ").ifEmpty { "nothing" }
        }
        if (zips > 0) parts += "$zips ZIP file(s) will be installed"
        parts += "Free space: $free"
        binding.tvSummary.text = parts.joinToString("\n")
        binding.btnInstall.isEnabled = !installing
        binding.btnSkip.isEnabled = !installing
    }

    // ---- build the install list ----------------------------------------------------------

    private fun buildJobs(): List<Job> {
        val auto = binding.switchAuto.isChecked
        val jobs = ArrayList<Job>()
        for (r in rows) {
            val v = r.selected?.value ?: continue
            if (r.zipUri == null && !auto) continue
            jobs += Job(SdkRepository.idOf(r.tab, v), r.selected?.label?.let { labelWithTab(r.tab, it) } ?: v, r.zipUri)
        }
        if (auto && binding.cbPlatformTools.isChecked) jobs += Job("platform-tools", "Platform Tools", null)
        return jobs.sortedBy { order(it.id) }
    }

    private fun labelWithTab(tab: SdkTab, label: String) = when (tab) {
        SdkTab.PLATFORMS -> "SDK Platform $label"
        SdkTab.BUILD_TOOLS -> "Build Tools $label"
        SdkTab.NDK -> "NDK $label"
        else -> label
    }

    private fun order(id: String) = when {
        id.startsWith("platforms;") -> 0
        id.startsWith("build-tools;") -> 1
        id == "platform-tools" -> 2
        id.startsWith("jdk;") -> 3
        else -> 4
    }

    // ---- skip ------------------------------------------------------------------------------

    private fun confirmSkip() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Skip installation?")
            .setMessage("You can install the tools later from SDK Manager. Until then projects cannot be built.")
            .setNegativeButton("Go back", null)
            .setPositiveButton("Skip") { _, _ ->
                markDone(this)
                finish()
            }
            .show()
    }

    // ---- install -----------------------------------------------------------------------------

    private fun startInstall() {
        if (installing) return
        val jobs = buildJobs()
        if (jobs.isEmpty()) {
            Toast.makeText(this, "Nothing selected. Pick a version or a ZIP, or tap Skip.", Toast.LENGTH_LONG).show()
            return
        }
        runInstall(jobs)
    }

    private fun runInstall(jobs: List<Job>) {
        installing = true
        taskStopped = false
        lastOk = false
        binding.btnInstall.isEnabled = false
        binding.btnSkip.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The log dialog has its own light look, so inflate it with the app's normal theme.
        val themed = ContextThemeWrapper(this, R.style.AppTheme)
        val d = DialogSdkTerminalBinding.inflate(LayoutInflater.from(themed))
        val dialog = AlertDialog.Builder(themed).setView(d.root).setCancelable(false).create()
        d.tvDialogTitle.text = "Installing tools..."
        d.btnStopClose.setOnClickListener { taskStopped = true; dialog.dismiss() }
        d.btnStop.setOnClickListener { taskStopped = true }
        d.tvClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { if (!installing && lastOk) complete() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val log = TaskLog { text ->
            runOnUiThread {
                d.tvTerminal.text = text
                d.termScroll.post { d.termScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        Thread {
            val installer = SdkInstaller(this)
            val summary = ArrayList<String>()
            var failed = 0
            var stopped = false

            for ((i, job) in jobs.withIndex()) {
                if (taskStopped) { stopped = true; break }
                log.line("")
                log.line("[${i + 1}/${jobs.size}] ${job.label}")
                var local: File? = null
                try {
                    when {
                        job.zip != null -> {
                            local = copyZip(job.zip, log)
                            installer.installFromZip(job.id, local, log) { taskStopped }
                            summary += "✔ ${job.label} (from ZIP)"
                        }
                        SdkRepository.isInstalled(this, job.id) -> {
                            log.line("Already installed ✔")
                            summary += "✔ ${job.label} (already installed)"
                        }
                        else -> {
                            installer.install(job.id, log) { taskStopped }
                            summary += "✔ ${job.label}"
                        }
                    }
                } catch (e: InstallCancelled) {
                    stopped = true
                    log.line("Stopped by user.")
                    summary += "■ ${job.label} (stopped)"
                    break
                } catch (e: Exception) {
                    failed++
                    val msg = e.message ?: e.javaClass.simpleName
                    log.line("")
                    log.line("Error: $msg")
                    summary += "✖ ${job.label}: $msg"
                } finally {
                    local?.delete()
                }
            }

            log.line("")
            log.line("==== Summary ====")
            summary.forEach { log.line(it) }

            val ok = failed == 0 && !stopped
            val title = when {
                stopped -> "Stopped"
                failed > 0 -> "Finished with errors ⚠"
                else -> "All set ✅"
            }
            runOnUiThread {
                installing = false
                lastOk = ok
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                d.tvDialogTitle.text = title
                d.tvClose.visibility = View.VISIBLE
                d.btnStop.isEnabled = false
                d.btnStopClose.text = "Close Window"
                d.btnStopClose.setOnClickListener { dialog.dismiss() }
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.btnInstall.isEnabled = true
                if (!ok) binding.btnInstall.text = "Retry"
                updateSummary()
                if (!dialog.isShowing && ok) complete()
            }
        }.start()
    }

    private fun complete() {
        markDone(this)
        Toast.makeText(this, "Development tools are ready", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Copies the chosen ZIP into the app's storage (ZipFile needs a real file) with live progress. */
    private fun copyZip(uri: Uri, log: TaskLog): File {
        val dir = File(sdkRoot, ".downloads").apply { mkdirs() }
        val out = File(dir, "user-import.zip")
        val total = querySize(uri)
        if (total > 0 && sdkRoot.usableSpace < total * 3) {
            throw IllegalStateException(
                "Not enough free space for this ZIP. Need about ${mb(total * 3)}, have ${mb(sdkRoot.usableSpace)}."
            )
        }
        log.line("Reading ZIP...")
        val ins = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open the selected file.")
        var done = 0L
        var last = 0L
        try {
            ins.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (taskStopped) throw InstallCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 250) {
                            last = now
                            log.progress("  ${mb(done)}" + if (total > 0) " / ${mb(total)}" else "")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        log.progress("  ${mb(done)}" + if (total > 0) " / ${mb(total)}" else "")
        log.line("")
        return out
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)

    companion object {
        private const val PREFS = "setup"

        /** True when at least one Build Tools version is really installed (folder with source.properties). */
        fun buildToolsInstalled(ctx: Context): Boolean =
            File(SdkRepository.sdkRoot(ctx), "build-tools").listFiles { f -> f.isDirectory }
                ?.any { File(it, "source.properties").isFile } == true

        /**
         * App start check: the setup page is shown only when the tools are NOT installed yet
         * (and the user did not already finish/skip it). If Build Tools are there, the app opens
         * straight into the normal home screen.
         */
        fun isDone(ctx: Context): Boolean =
            buildToolsInstalled(ctx) ||
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("done", false)

        fun markDone(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done", true).apply()
        }
    }
}
