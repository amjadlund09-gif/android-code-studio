package com.example.mynative.androidide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Keeps a Gradle sync / build alive while the app is in the background:
 *  - foreground service (Android does not kill the process, the Gradle JVM keeps running)
 *  - partial wake lock (CPU keeps running with the screen off)
 *  - ongoing notification with live progress and a Stop button
 *  - "Build finished / failed" notification when the app is not on screen
 * The Gradle process itself is still started by ProjectActivity (ShellRunner); this class only
 * keeps it alive and shows its state.
 */
class BuildService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStop?.invoke()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Building..."
        val path = intent?.getStringExtra(EXTRA_PATH)
        projectPath = path
        startForeground(ID_RUN, runNotification(this, title, "Starting Gradle...", path))
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androidide:build").apply {
                setReferenceCounted(false)
                acquire(90 * 60 * 1000L)            // safety net: never hold the CPU longer than 90 min
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.mynative.androidide.STOP_BUILD"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PATH = "path"
        private const val CH_RUN = "build_running"
        private const val CH_DONE = "build_done"
        private const val ID_RUN = 4101
        private const val ID_DONE = 4102

        /** true from begin() until end(): a Gradle run is alive (even when ProjectActivity is gone). */
        @Volatile var running = false
            private set

        /** Set by ProjectActivity: stops the current Gradle run (Stop button in the notification). */
        @Volatile var onStop: (() -> Unit)? = null

        @Volatile private var projectPath: String? = null
        private var lastUpdate = 0L
        private var lastText = ""

        private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        private fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val m = nm(ctx)
            if (m.getNotificationChannel(CH_RUN) == null)
                m.createNotificationChannel(NotificationChannel(CH_RUN, "Build in progress", NotificationManager.IMPORTANCE_LOW))
            if (m.getNotificationChannel(CH_DONE) == null)
                m.createNotificationChannel(NotificationChannel(CH_DONE, "Build result", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openProject(ctx: Context, path: String?): PendingIntent {
            val i = Intent(ctx, ProjectActivity::class.java)
                .putExtra(ProjectActivity.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun runNotification(ctx: Context, title: String, text: String, path: String?): Notification {
            ensureChannels(ctx)
            val stop = PendingIntent.getService(
                ctx, 2, Intent(ctx, BuildService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(ctx, CH_RUN)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openProject(ctx, path))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
                .build()
        }

        /** Call right before the Gradle process starts. Safe to call again for a retry. */
        fun begin(ctx: Context, title: String, projectPath: String, stopper: () -> Unit) {
            val app = ctx.applicationContext
            running = true
            onStop = stopper
            this.projectPath = projectPath
            lastText = ""
            val i = Intent(app, BuildService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PATH, projectPath)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            }.onFailure { running = false }
            runCatching { nm(app).cancel(ID_DONE) }
        }

        /** Live progress line (throttled to ~1 update / second). */
        fun progress(ctx: Context, title: String, text: String) {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 1000 || text == lastText) return
            lastUpdate = now; lastText = text
            val app = ctx.applicationContext
            runCatching { nm(app).notify(ID_RUN, runNotification(app, title, text, projectPath)) }
        }

        /**
         * Call when a run is really over (not between an offline run and its online retry).
         * [showResult]: post the result notification (use it when the screen is not in front).
         */
        fun end(ctx: Context, ok: Boolean, title: String, text: String, showResult: Boolean) {
            val app = ctx.applicationContext
            running = false
            onStop = null
            // Stopping a service in the same instant it was started (a build that fails at once) would
            // crash with "did not call startForeground", so stop it a moment later - unless a retry began.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!running) {
                    runCatching { app.stopService(Intent(app, BuildService::class.java)) }
                    runCatching { nm(app).cancel(ID_RUN) }
                }
            }, 1500)
            if (!showResult) return
            ensureChannels(app)
            val n = NotificationCompat.Builder(app, CH_DONE)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openProject(app, projectPath))
                .build()
            runCatching { nm(app).notify(ID_DONE, n) }
        }
    }
}
