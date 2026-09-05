package com.screenoffdrama.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.screenoffdrama.MainActivity
import com.screenoffdrama.R
import com.screenoffdrama.overlay.BlackOverlayManager

/**
 * 息屏听剧前台服务
 *
 * - 启动后立即显示全屏黑屏覆盖层（息屏模式）
 * - 点击覆盖层右上角 X 按钮退出息屏模式
 * - 按电源键熄屏也会退出息屏模式
 * - 通知栏提供"停止服务"操作
 */
class ScreenOffService : Service() {

    companion object {
        private const val CHANNEL_ID = "screen_off_drama_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.screenoffdrama.action.STOP"

        @Volatile
        var isRunning = false
            private set
    }

    private var blackOverlay: BlackOverlayManager? = null
    private var screenOffMode = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                exitScreenOffMode()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startAsForeground()
        registerScreenOffReceiver()
        // 启动后立即进入息屏模式
        enterScreenOffMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning) {
            isRunning = true
            startAsForeground()
        }
        // 如果不在息屏模式，重新进入
        if (!screenOffMode) {
            enterScreenOffMode()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        exitScreenOffMode()
        runCatching { unregisterReceiver(screenOffReceiver) }
        isRunning = false
    }

    // ---------- 前台通知 ----------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenOffService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    // ---------- 屏幕熄灭广播 ----------

    private fun registerScreenOffReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenOffReceiver, filter)
        }
    }

    // ---------- 息屏状态切换 ----------

    private fun enterScreenOffMode() {
        if (screenOffMode) return
        screenOffMode = true
        blackOverlay = BlackOverlayManager(this) {
            exitScreenOffMode()
        }
        blackOverlay?.show()
    }

    fun exitScreenOffMode() {
        if (!screenOffMode) return
        screenOffMode = false
        blackOverlay?.remove()
        blackOverlay = null
    }
}
