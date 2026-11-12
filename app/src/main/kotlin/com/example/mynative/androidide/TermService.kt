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
 * Keeps the terminal sessions (and whatever runs in them, e.g. `pkg install`) alive while the app is
 * in the background: foreground service + notification with "Exit" and a wake-lock switch.
 */
class TermService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // always satisfy startForegroundService() first
        startForeground(ID, notification(this))
        when (intent?.action) {
            ACTION_EXIT -> {
                TermSessions.killAll(this)
                releaseWake()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WAKE -> {
                if (wake?.isHeld == true) releaseWake() else acquireWake()
                startForeground(ID, notification(this))     // refresh the action label
            }
        }
        if (TermSessions.list.isEmpty()) {
            stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWake()
        super.onDestroy()
    }

    private fun acquireWake() {
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androidide:terminal").apply { setReferenceCounted(false) }
        }
        wake?.takeIf { !it.isHeld }?.acquire(6 * 60 * 60 * 1000L)     // never longer than 6 h
    }

    private fun releaseWake() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
    }

    companion object {
        const val ACTION_EXIT = "com.example.mynative.androidide.TERM_EXIT"
        const val ACTION_WAKE = "com.example.mynative.androidide.TERM_WAKE"
        private const val CH = "terminal"
        private const val ID = 4201

        @Volatile private var wake: PowerManager.WakeLock? = null

        /** Starts / refreshes the service while sessions exist, stops it when none are left. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            runCatching {
                if (TermSessions.list.isEmpty()) {
                    app.stopService(Intent(app, TermService::class.java))
                } else {
                    val i = Intent(app, TermService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
                }
            }
        }

        private fun notification(ctx: Context): Notification {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CH) == null) {
                    nm.createNotificationChannel(NotificationChannel(CH, "Terminal", NotificationManager.IMPORTANCE_LOW))
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(
                ctx, 11,
                Intent(ctx, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                flags
            )
            val exit = PendingIntent.getService(ctx, 12, Intent(ctx, TermService::class.java).setAction(ACTION_EXIT), flags)
            val wakeIntent = PendingIntent.getService(ctx, 13, Intent(ctx, TermService::class.java).setAction(ACTION_WAKE), flags)
            val n = TermSessions.list.size
            val held = wake?.isHeld == true
            return NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("Terminal")
                .setContentText(if (n == 1) "1 session" else "$n sessions")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "Exit", exit)
                .addAction(0, if (held) "Release wake lock" else "Acquire wake lock", wakeIntent)
                .build()
        }
    }
}
