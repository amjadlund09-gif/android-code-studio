package com.example.mynative.androidide

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.mynative.androidide.databinding.ActivitySdkManagerBinding
import com.example.mynative.androidide.databinding.DialogSdkTerminalBinding
import com.example.mynative.androidide.databinding.ItemSdkComponentBinding
import com.example.mynative.androidide.databinding.ItemSdkTabBinding
import java.io.File

class SdkManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySdkManagerBinding
    private lateinit var sdkRoot: File

    private var currentTab = SdkTab.BUILD_TOOLS
    private var selectedId: String? = null
    private var packages: List<SdkPkg> = emptyList()
    private val tabViews = mutableMapOf<SdkTab, ItemSdkTabBinding>()

    private var runner: ShellRunner? = null
    private var remote: List<RemoteSdkPkg>? = null   // live package list (cached 24h)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySdkManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sdkRoot = SdkRepository.sdkRoot(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnInfo.setOnClickListener { showSdkInfo() }
        binding.btnTerminal.setOnClickListener { startActivity(android.content.Intent(this, TerminalActivity::class.java)) }

        SdkTab.values().forEach { tab ->
            val item = ItemSdkTabBinding.inflate(layoutInflater, binding.tabs, false)
            item.ivTab.setImageResource(tab.icon)
            item.tvTab.text = tab.label
            item.root.setOnClickListener { selectTab(tab) }
            binding.tabs.addView(item.root)
            tabViews[tab] = item
        }

        binding.btnInstall.setOnClickListener { selected()?.let { runInstall(it, reinstall = false) } }
        binding.btnReinstall.setOnClickListener { selected()?.let { runInstall(it, reinstall = true) } }
        binding.btnInstallFlutter.setOnClickListener {
            packages.firstOrNull { it.tab == SdkTab.FLUTTER }?.let { runInstall(it, reinstall = false) }
        }
        binding.btnUninstall.setOnClickListener { selected()?.let { runUninstall(it) } }

        binding.btnProject.setOnClickListener { pickProject() }

        remote = SdkIndex.cached(this)
        selectTab(SdkTab.BUILD_TOOLS)
        loadIndex(false)

        // Opened from a project (missing platform etc.): offer to install what that project needs.
        intent.getStringExtra(EXTRA_PROJECT)?.let { path ->
            binding.root.post { showProjectNeeds(File(path), auto = true) }
        }

        // Opened from "New project" with the packages it still needs.
        intent.getStringArrayExtra(EXTRA_IDS)?.let { ids ->
            binding.root.post { showIdNeeds(ids.toList()) }
        }
    }

    /** Downloads Google's package index in the background (cached 24h) and refreshes the list. */
    private fun loadIndex(force: Boolean) {
        if (!force && remote != null && !SdkIndex.isStale(this)) return
        Thread {
            val r = runCatching { SdkIndex.load(this, force) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { remote = it; refreshList() }
                if (force) {
                    val msg = r.fold({ "Package list updated" }, { "Offline: ${it.message}" })
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun selected(): SdkPkg? = packages.firstOrNull { it.id == selectedId }

    private fun selectTab(tab: SdkTab) {
        currentTab = tab
        selectedId = null
        tabViews.forEach { (t, v) ->
            val on = t == tab
            v.root.setBackgroundResource(if (on) R.drawable.bg_sdk_chip_sel else R.drawable.bg_sdk_chip)
            v.tvTab.setTextColor(if (on) getColor(R.color.sdk_primary) else 0xFF6B6B6B.toInt())
            v.tvTab.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        binding.tvSection.text = tab.section.uppercase()
        if (tab == SdkTab.FLUTTER) selectedId = "flutter"
        refreshList()
    }

    private fun refreshList() {
        packages = SdkRepository.list(sdkRoot, currentTab, remote)
        binding.list.removeAllViews()
        packages.forEachIndexed { index, pkg ->
            val row = ItemSdkComponentBinding.inflate(layoutInflater, binding.list, false)
            row.tvTitle.text = pkg.title
            val baseText = if (pkg.tab == SdkTab.PLATFORM_TOOLS) "Version ${pkg.version}" else
                if (pkg.tab == SdkTab.PLATFORMS) pkg.version else "Version ${pkg.version}"
            val extra = ArrayList<String>()
            if (pkg.state == SdkState.NOT_INSTALLED && pkg.size > 0) extra.add(Formatter.formatShortFileSize(this, pkg.size))
            if (pkg.updateTo != null) extra.add("update → ${pkg.updateTo}")
            row.tvVersion.text = if (pkg.tab == SdkTab.FLUTTER) flutterSubtitle(pkg)
                else if (extra.isEmpty()) baseText else baseText + " · " + extra.joinToString(" · ")
            when (pkg.state) {
                SdkState.INSTALLED -> {
                    row.tvBadge.visibility = View.VISIBLE
                    when {
                        pkg.warning != null && pkg.tab == SdkTab.PLATFORM_TOOLS -> {
                            // adb/fastboot are optional for building; Google ships them for x86-64 only
                            row.tvBadge.text = "adb: x86 only"
                            row.tvBadge.setTextColor(0xFF8A5A00.toInt())
                            row.tvBadge.setBackgroundResource(R.drawable.bg_badge_update)
                        }
                        pkg.warning != null -> {
                            row.tvBadge.text = "Incompatible"
                            row.tvBadge.setTextColor(0xFFB3261E.toInt())
                            row.tvBadge.setBackgroundResource(R.drawable.bg_badge_invalid)
                        }
                        pkg.updateTo != null -> {
                            row.tvBadge.text = "Update"
                            row.tvBadge.setTextColor(0xFF8A5A00.toInt())
                            row.tvBadge.setBackgroundResource(R.drawable.bg_badge_update)
                        }
                        else -> {
                            row.tvBadge.text = "Installed"
                            row.tvBadge.setBackgroundResource(R.drawable.bg_badge_installed)
                        }
                    }
                }
                SdkState.INVALID -> {
                    row.tvBadge.visibility = View.VISIBLE
                    row.tvBadge.text = "Invalid"
                    row.tvBadge.setTextColor(0xFFB3261E.toInt())
                    row.tvBadge.setBackgroundResource(R.drawable.bg_badge_invalid)
                }
                SdkState.NOT_INSTALLED -> row.tvBadge.visibility = View.GONE
            }
            val isSel = pkg.id == selectedId
            row.ivRadio.setImageResource(if (isSel) R.drawable.ring_on else R.drawable.ring_off)
            if (isSel) row.root.setBackgroundResource(R.color.sdk_row_selected)
            row.root.setOnClickListener { selectedId = pkg.id; refreshList() }
            binding.list.addView(row.root)

            if (index < packages.lastIndex) {
                binding.list.addView(View(this).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2)
                    setBackgroundColor(0xFFFFFFFF.toInt())
                })
            }
        }
        updateFlutterPanel()
        updateBottomBar()
    }

    /** "Not installed" / "Invalid - tap Repair" / "Version 3.x" (the SDK's version file) for the Flutter row. */
    private fun flutterSubtitle(pkg: SdkPkg): String = when (pkg.state) {
        SdkState.NOT_INSTALLED -> "Not installed"
        SdkState.INVALID -> "Invalid install - use Repair"
        SdkState.INSTALLED -> if (pkg.version == "SDK") "Installed" else "Version ${pkg.version}"
    }

    /** Requirements card + big "Install Flutter" button (Flutter tab only). */
    private fun updateFlutterPanel() {
        val isFlutter = currentTab == SdkTab.FLUTTER
        binding.flutterPanel.visibility = if (isFlutter) View.VISIBLE else View.GONE
        if (!isFlutter) return
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val archOk = abi == "arm64-v8a"
        binding.tvReqArch.text = if (archOk) "ARM64 only ✔" else "ARM64 only ✖ ($abi)"
        binding.tvReqArch.setTextColor(if (archOk) 0xFF2E7D32.toInt() else 0xFFB3261E.toInt())
        binding.tvReqStorage.text = "~2 GB (keep 6 GB free)"
        val free = SdkRepository.homeDir(this).apply { mkdirs() }.usableSpace
        val freeOk = free >= FLUTTER_MIN_FREE
        binding.tvReqFree.text = Formatter.formatShortFileSize(this, free) + if (freeOk) " ✔" else " ✖"
        binding.tvReqFree.setTextColor(if (freeOk) 0xFF2E7D32.toInt() else 0xFFB3261E.toInt())
        val warn = when {
            !archOk -> "This phone is $abi. Flutter needs a 64-bit ARM (arm64-v8a) phone."
            !freeOk -> "Low storage: free up space before installing (install needs about 6 GB free while unpacking)."
            else -> null
        }
        binding.tvFlutterWarn.text = warn ?: ""
        binding.tvFlutterWarn.visibility = if (warn != null) View.VISIBLE else View.GONE
        val st = packages.firstOrNull { it.tab == SdkTab.FLUTTER }?.state ?: SdkState.NOT_INSTALLED
        binding.btnInstallFlutter.visibility = if (st == SdkState.NOT_INSTALLED) View.VISIBLE else View.GONE
        binding.btnInstallFlutter.isEnabled = archOk
    }

    private fun updateBottomBar() {
        val pkg = selected()
        if (pkg == null) { binding.bottomBar.visibility = View.GONE; return }
        // Flutter not installed: the big "Install Flutter" button is used instead
        if (pkg.tab == SdkTab.FLUTTER && pkg.state == SdkState.NOT_INSTALLED) { binding.bottomBar.visibility = View.GONE; return }
        binding.bottomBar.visibility = View.VISIBLE
        binding.tvSelTitle.text = pkg.title
        val parts = ArrayList<String>()
        if (pkg.tab == SdkTab.FLUTTER) { if (pkg.version != "SDK") parts.add("v${pkg.version}") } else parts.add("v${pkg.version}")
        if (pkg.state == SdkState.NOT_INSTALLED && pkg.size > 0) parts.add("download " + Formatter.formatShortFileSize(this, pkg.size))
        if (pkg.updateTo != null) parts.add("update available: ${pkg.updateTo}")
        pkg.warning?.let { parts.add("⚠ $it") }
        binding.tvSelVersion.text = parts.joinToString("\n")
        binding.tvSelVersion.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        val present = pkg.state != SdkState.NOT_INSTALLED
        binding.btnReinstall.text = when {
            pkg.state == SdkState.INVALID -> "Repair"
            pkg.warning != null && NativeToolsFix.fixable(pkg.id) -> "Fix tools"
            pkg.updateTo != null -> "Update"
            else -> "Reinstall"
        }
        binding.btnInstall.visibility = if (present) View.GONE else View.VISIBLE
        binding.btnUninstall.visibility = if (present) View.VISIBLE else View.GONE
        binding.btnReinstall.visibility = if (present) View.VISIBLE else View.GONE
    }

    // ---- real commands -------------------------------------------------------------------

    private fun runInstall(pkg: SdkPkg, reinstall: Boolean) {
        if (reinstall && pkg.warning != null && NativeToolsFix.fixable(pkg.id)) {
            // wrong-CPU tools: re-downloading the same x86 files would not help, swap in arm64 ones instead
            runTask(runningTitle = "Fixing tools...", doneTitle = "Tools fixed for this phone ✅") { _, log, cancelled ->
                NativeToolsFix.fix(this, pkg.id, pkg.dir, log, cancelled)
            }
            return
        }
        runTask(
            runningTitle = if (reinstall) "Reinstalling..." else "Installing...",
            doneTitle = if (reinstall) "Successfully reinstalled ✅" else "Successfully installed ✅"
        ) { installer, log, cancelled ->
            if (reinstall) installer.uninstall(pkg.id, log)
            installer.install(pkg.id, log, cancelled)
        }
    }

    private fun runUninstall(pkg: SdkPkg) {
        runTask(runningTitle = "Uninstalling...", doneTitle = "Successfully uninstalled ✅") { installer, log, _ ->
            installer.uninstall(pkg.id, log)
        }
    }

    private var taskRunning = false
    @Volatile private var taskStopped = false

    /** Runs the built-in installer (no sdkmanager / java needed) inside the terminal dialog. */
    private fun runTask(
        runningTitle: String,
        doneTitle: String,
        work: (SdkInstaller, TaskLog, () -> Boolean) -> Unit
    ) {
        if (taskRunning || runner != null) return
        taskRunning = true
        taskStopped = false
        val d = DialogSdkTerminalBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this).setView(d.root).setCancelable(false).create()

        d.tvDialogTitle.text = runningTitle
        d.btnStopClose.setOnClickListener { taskStopped = true; dialog.dismiss() }
        d.btnStop.setOnClickListener { taskStopped = true }
        d.tvClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { taskRunning = false; refreshList() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val log = TaskLog { text ->
            runOnUiThread {
                d.tvTerminal.text = text
                d.termScroll.post { d.termScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        val installer = SdkInstaller(this)

        Thread {
            var title = doneTitle
            try {
                work(installer, log) { taskStopped }
            } catch (e: InstallCancelled) {
                title = "Stopped"
                log.line("Stopped by user.")
            } catch (e: Exception) {
                title = "Failed ❌"
                log.line("")
                log.line("Error: ${e.message ?: e.javaClass.simpleName}")
            }
            runOnUiThread {
                d.tvDialogTitle.text = title
                d.tvClose.visibility = View.VISIBLE
                d.btnStop.isEnabled = false
                d.btnStopClose.text = "Close Window"
                d.btnStopClose.setOnClickListener { dialog.dismiss() }
                refreshList()
            }
        }.start()
    }

    private fun runCommand(command: String, runningTitle: String, doneTitle: String) {
        if (runner != null) return          // one command at a time
        val d = DialogSdkTerminalBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this).setView(d.root).setCancelable(false).create()
        val shell = ShellRunner(this)
        runner = shell

        d.tvDialogTitle.text = runningTitle
        d.btnStopClose.setOnClickListener { shell.stop(); dialog.dismiss() }
        d.btnStop.setOnClickListener { shell.stop() }
        d.tvClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { runner = null; refreshList() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        shell.start(command,
            onOutput = { text ->
                runOnUiThread {
                    d.tvTerminal.text = text
                    d.termScroll.post { d.termScroll.fullScroll(View.FOCUS_DOWN) }
                }
            },
            onExit = { code, stoppedByUser ->
                runOnUiThread {
                    d.tvDialogTitle.text = when {
                        stoppedByUser -> "Stopped"
                        code == 0 -> doneTitle
                        else -> "Failed ❌ (exit $code)"
                    }
                    d.tvClose.visibility = View.VISIBLE
                    d.btnStop.isEnabled = false
                    d.btnStopClose.text = "Close Window"
                    d.btnStopClose.setOnClickListener { dialog.dismiss() }
                    refreshList()
                }
            })
    }

    private fun showSdkInfo() {
        val sdkManager = File(sdkRoot, "cmdline-tools/latest/bin/sdkmanager")
        val free = Formatter.formatFileSize(this, sdkRoot.apply { mkdirs() }.usableSpace)
        AlertDialog.Builder(this)
            .setTitle("SDK location")
            .setMessage(
                "${sdkRoot.path}\n\n" +
                    "Free space: $free\n" +
                    "Installer: built-in (downloads from dl.google.com)\n" +
                    "sdkmanager: " + if (sdkManager.exists()) "found" else "not needed"
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Refresh list") { _, _ -> loadIndex(true) }
            .setNegativeButton("Setup wizard") { _, _ -> startActivity(android.content.Intent(this, SetupActivity::class.java)) }
            .show()
    }

    // ---- "Install what this project needs" ----------------------------------------------------

    private fun pickProject() {
        val dir = File(SdkRepository.homeDir(this), "AndroidIDEProjects")
        val projects = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()
        if (projects.isEmpty()) {
            Toast.makeText(this, "No projects found", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Install for project")
            .setItems(projects.map { it.name }.toTypedArray()) { _, i -> showProjectNeeds(projects[i], auto = false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Reads the project's build files; lists missing SDK packages; installs them all in one go. */
    private fun showProjectNeeds(project: File, auto: Boolean) {
        Thread {
            val rem = remote ?: SdkIndex.cached(this)
            val needs = runCatching { SdkNeeds.scan(project, sdkRoot, rem) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                presentNeeds(project.name, needs, rem, auto)
            }
        }.start()
    }

    /** Packages named by id (from the New project screen); already installed ones are skipped. */
    private fun showIdNeeds(ids: List<String>) {
        val needs = ids.filter { !SdkRepository.isInstalled(this, it) }
            .map { SdkNeeds.Need(it, SdkNeeds.label(it)) }
        presentNeeds("New project", needs, remote ?: SdkIndex.cached(this), auto = false)
    }

    private fun presentNeeds(name: String, needs: List<SdkNeeds.Need>, rem: List<RemoteSdkPkg>?, auto: Boolean) {
        if (needs.isEmpty()) {
            if (!auto) {
                AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage("Everything needed is already installed ✔")
                    .setPositiveButton("OK", null)
                    .show()
            }
            return
        }
        var total = 0L
        val lines = needs.joinToString("\n") { n ->
            val size = rem?.firstOrNull { it.id == n.id }?.size ?: 0L
            total += size
            "• ${n.id}\n   ${n.why}" + if (size > 0) " · " + Formatter.formatShortFileSize(this, size) else ""
        }
        val totalText = if (total > 0) "\n\nTotal download: " + Formatter.formatShortFileSize(this, total) else ""
        AlertDialog.Builder(this)
            .setTitle("$name needs")
            .setMessage(lines + totalText)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install all (${needs.size})") { _, _ -> installNeeds(needs) }
            .show()
    }

    private fun installNeeds(needs: List<SdkNeeds.Need>) {
        runTask(runningTitle = "Installing ${needs.size} package(s)...", doneTitle = "All installed ✅") { installer, log, cancelled ->
            needs.forEachIndexed { i, n ->
                if (cancelled()) throw InstallCancelled()
                log.line("")
                log.line("[${i + 1}/${needs.size}] ${n.id}  (${n.why})")
                installer.install(n.id, log, cancelled)
            }
        }
    }

    companion object {
        const val EXTRA_PROJECT = "project_dir"
        const val EXTRA_IDS = "install_ids"
        private const val FLUTTER_MIN_FREE = 6L * 1024 * 1024 * 1024   // ~2 GB SDK + download + unpack space
    }

    override fun onDestroy() {
        super.onDestroy()
        runner?.stop()
        taskStopped = true
    }
}
