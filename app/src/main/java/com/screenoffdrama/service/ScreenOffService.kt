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
import com.screenoffdrama.overlay.ControlPanelManager
import com.screenoffdrama.overlay.FloatingBallManager

/**
 * 息屏听剧前台服务
 *
 * - 类型声明为 mediaPlayback（媒体播放），Android 14+ 需同时声明
 *   FOREGROUND_SERVICE_MEDIA_PLAYBACK 权限，并在 startForeground 传入类型
 * - 维护悬浮球 + 全屏黑屏覆盖层，互不抢焦点，保证底层视频不暂停
 * - 监听 ACTION_SCREEN_OFF：处于“息屏模式”时按电源键 → 退出息屏
 * - START_STICKY + 忽略电池优化，尽量不被系统杀掉
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

    private var ballManager: FloatingBallManager? = null
    private var blackOverlay: BlackOverlayManager? = null
    private var controlPanelManager: ControlPanelManager? = null

    /** 是否处于“息屏听剧”状态（黑屏覆盖层显示中） */
    private var screenOffMode = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                // 息屏模式下按电源键 → 退出息屏（移除黑窗、恢复悬浮球）
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

        blackOverlay = BlackOverlayManager(this)
        ballManager = FloatingBallManager(this) { enterScreenOffMode() }
        ballManager?.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 被系统重建（START_STICKY）时确保仍以前台服务身份运行
        if (!isRunning) {
            isRunning = true
            startAsForeground()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉本应用时保持服务（前台服务不受影响）
    }

    override fun onDestroy() {
        super.onDestroy()
        ballManager?.hide()
        blackOverlay?.remove()
        blackOverlay = null
        controlPanelManager?.hide()
        controlPanelManager = null
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
        // minSdk 31 >= API 29，直接带媒体播放类型启动前台服务
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

    /** 进入息屏听剧：隐藏悬浮球，显示全屏黑窗（不抢焦点，底层视频继续播放） */
    private fun enterScreenOffMode() {
        if (screenOffMode) return
        screenOffMode = true
        ballManager?.hide()
        
        // 创建黑色覆盖层，点击右上角退出按钮时退出息屏模式
        blackOverlay = BlackOverlayManager(this) {
            exitScreenOffMode()
        }
        blackOverlay?.show()
    }

    /** 退出息屏听剧：移除黑窗，恢复悬浮球 */
    fun exitScreenOffMode() {
        if (!screenOffMode) return
        screenOffMode = false
        
        // 释放管理页
        controlPanelManager?.hide()
        controlPanelManager = null
        
        blackOverlay?.remove()
        blackOverlay = null
        ballManager?.show()
    }
}
