package org.screenlite.webkiosk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.screenlite.webkiosk.MainActivity
import org.screenlite.webkiosk.data.AutoStart
import org.screenlite.webkiosk.data.KioskSettingsFactory

class StayOnTopService : Service() {
    companion object {
        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isActivityVisible = false

        /** この時刻（elapsedRealtime）までは前面に戻さない。設定画面を開いたとき用。 */
        @Volatile
        var pauseUntil = 0L

        fun pauseForSettings() {
            pauseUntil = SystemClock.elapsedRealtime() + AutoStart.SETTINGS_PAUSE_MS
        }

        fun restart(context: Context) {
            if (isRunning) {
                context.stopService(Intent(context, StayOnTopService::class.java))
            }
            context.startService(Intent(context, StayOnTopService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var checkInterval: Long = 10_000L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var visibilityJob: Job? = null

    // 待機からの復帰（画面オン・ロック解除）では BOOT_COMPLETED が来ないので、ここで前面に戻す
    private val wakeTask = Runnable {
        if (AutoStart.shouldBringToFront(isActivityVisible, SystemClock.elapsedRealtime(), pauseUntil)) bringAppToFront()
    }
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i("StayOnTopService", "Wake broadcast: ${intent?.action}")
            handler.removeCallbacks(wakeTask)
            handler.postDelayed(wakeTask, 1500L)
        }
    }
    private var wakeReceiverRegistered = false

    private val checkTask = object : Runnable {
        override fun run() {
            if (AutoStart.shouldBringToFront(isActivityVisible, SystemClock.elapsedRealtime(), pauseUntil)) {
                bringAppToFront()
            } else {
                Log.i("StayOnTopService", "Activity is visible or paused for settings, skipping bring to front")
            }
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val kioskSettings = KioskSettingsFactory.get(this)

        serviceScope.launch {
            kioskSettings.getCheckInterval().collect { interval ->
                Log.i("StayOnTopService", "Interval updated: $interval")
                updateCheckInterval(interval)
            }
        }

        startForegroundService()
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // USER_PRESENT は SystemUI から送られ NOT_EXPORTED では届かない。どちらも保護ブロードキャストで他アプリは偽装できない
            ContextCompat.registerReceiver(this, wakeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            wakeReceiverRegistered = true
        } catch (e: Exception) {
            Log.e("StayOnTopService", "Failed to register wake receiver: ${e.message}")
        }
        handler.post(checkTask)
        isRunning = true
        Log.i("StayOnTopService", "Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkTask)
        handler.removeCallbacks(wakeTask)
        if (wakeReceiverRegistered) {
            try { unregisterReceiver(wakeReceiver) } catch (_: Exception) { }
            wakeReceiverRegistered = false
        }
        visibilityJob?.cancel()
        serviceScope.cancel()
        isRunning = false
        isActivityVisible = false
        Log.i("StayOnTopService", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bringAppToFront() {
        Log.i("StayOnTopService", "Bringing app to front")
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("StayOnTopService", "Failed to bring app to front: ${e.message}")
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundServiceOreo()
        } else {
            startForegroundServiceLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundServiceOreo() {
        val channelId = "kiosk_channel"
        val channel = NotificationChannel(
            channelId,
            "Screenlite Web Kiosk",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Screenlite Web Kiosk")
            .setContentText("Keeping app on top")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, notification)
    }

    @Suppress("DEPRECATION")
    private fun startForegroundServiceLegacy() {
        val notification: Notification = Notification.Builder(this)
            .setContentTitle("Screenlite Web Kiosk")
            .setContentText("Keeping app on top")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, notification)
    }

    private fun updateCheckInterval(newInterval: Long) {
        checkInterval = newInterval
        handler.removeCallbacks(checkTask)
        handler.post(checkTask)
    }
}