package com.example.mynative.androidide

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.mynative.androidide.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    
    private var setupPending = false        // first run: show the SDK Installation screen
    private var waitingSettings = false      // user went to the "All files access" settings page

    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding
        get() = checkNotNull(_binding) { "Activity has been destroyed" }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPending = savedInstanceState == null && !SetupActivity.isDone(this)
        if (savedInstanceState == null) requestAllPermissions()

        binding.btnTerminal.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        binding.cardSdkManager.setOnClickListener {
            startActivity(Intent(this, SdkManagerActivity::class.java))
        }
        binding.cardOpen.setOnClickListener { openFolder.launch(null) }
        binding.cardNewProject.setOnClickListener {
            startActivity(Intent(this, TemplateActivity::class.java))
        }
    }
    
    private val openFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val volume = docId.substringBefore(':')
            val rest = docId.substringAfter(':', "")
            val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
            startActivity(
                Intent(this, ProjectActivity::class.java)
                    .putExtra(ProjectActivity.EXTRA_PATH, if (rest.isEmpty()) base else "$base/$rest")
            )
        }

    // ---- permissions -------------------------------------------------------------------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            askAllFilesAccess()
        }

    /** Notifications + media/storage runtime permissions, then "All files access". */
    private fun requestAllPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
            wanted += Manifest.permission.READ_MEDIA_IMAGES
            wanted += Manifest.permission.READ_MEDIA_VIDEO
            wanted += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= 28) wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else askAllFilesAccess()
    }

    /** First run only: open the SDK Installation screen once the permission prompts are finished. */
    private fun showSetupIfNeeded() {
        if (!setupPending) return
        setupPending = false
        startActivity(Intent(this, SetupActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (waitingSettings) {            // back from the "All files access" settings page
            waitingSettings = false
            showSetupIfNeeded()
        }
    }

    private fun askAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
            showSetupIfNeeded()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("All files access")
            .setMessage("Allow access to all files so projects can be opened and saved anywhere on your storage.")
            .setPositiveButton("Allow") { _, _ ->
                waitingSettings = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            .setNegativeButton("Not now") { _, _ -> showSetupIfNeeded() }
            .setOnCancelListener { showSetupIfNeeded() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
    
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'myapplication' library on application startup.
        init {
            System.loadLibrary("myapplication")
        }
    }
}