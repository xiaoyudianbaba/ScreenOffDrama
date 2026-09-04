package com.screenoffdrama

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import com.screenoffdrama.service.AdSkipService
import com.screenoffdrama.service.ScreenOffService

/**
 * 主界面：权限引导 + 服务启停 + 广告跳过开关
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnBattery: Button
    private lateinit var btnNotification: Button
    private lateinit var btnService: Button
    private lateinit var switchAdSkip: Switch
    private lateinit var adSkipStatus: TextView
    private lateinit var btnAdSkipSettings: Button

    /** 防止程序化设置 Switch 状态时误触监听器写回偏好 */
    private var updatingUi = false

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateUi() }

    private val batteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateUi() }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateUi() }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnBattery = findViewById(R.id.btnBattery)
        btnNotification = findViewById(R.id.btnNotification)
        btnService = findViewById(R.id.btnService)
        switchAdSkip = findViewById(R.id.switchAdSkip)
        adSkipStatus = findViewById(R.id.adSkipStatusText)
        btnAdSkipSettings = findViewById(R.id.btnAdSkipSettings)

        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnBattery.setOnClickListener { openBatterySettings() }
        btnNotification.setOnClickListener { requestNotificationPermission() }
        btnService.setOnClickListener { toggleService() }

        // 广告跳过独立开关：仅控制 AdSkipService 是否监听，与息屏听剧服务互不影响
        switchAdSkip.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.edit { putBoolean(AdSkipService.KEY_AD_SKIP_ENABLED, isChecked) }
            updateUi()
        }
        btnAdSkipSettings.setOnClickListener { openAccessibilitySettings() }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        maybeAutoStartService()
        // 服务为异步启动，延迟几次刷新状态，避免按钮/文案与真实运行状态不一致
        btnService.postDelayed({ updateUi() }, 500)
        btnService.postDelayed({ updateUi() }, 1500)
    }
    
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Log.d(TAG, "PiP mode changed: $isInPictureInPictureMode")
        
        if (isInPictureInPictureMode) {
            // 进入 PiP 模式，隐藏 UI 元素
            hideUiForPip()
        } else {
            // 退出 PiP 模式，恢复 UI 元素
            showUiFromPip()
        }
    }
    
    private fun hideUiForPip() {
        // 在 PiP 模式下隐藏部分 UI 元素
        statusText.visibility = android.view.View.GONE
        btnOverlay.visibility = android.view.View.GONE
        btnBattery.visibility = android.view.View.GONE
        btnNotification.visibility = android.view.View.GONE
        switchAdSkip.visibility = android.view.View.GONE
        adSkipStatus.visibility = android.view.View.GONE
        btnAdSkipSettings.visibility = android.view.View.GONE
    }
    
    private fun showUiFromPip() {
        // 恢复所有 UI 元素
        statusText.visibility = android.view.View.VISIBLE
        btnOverlay.visibility = android.view.View.VISIBLE
        btnBattery.visibility = android.view.View.VISIBLE
        btnNotification.visibility = android.view.View.VISIBLE
        switchAdSkip.visibility = android.view.View.VISIBLE
        adSkipStatus.visibility = android.view.View.VISIBLE
        btnAdSkipSettings.visibility = android.view.View.VISIBLE
        updateUi()
    }

    // ---------- UI 状态 ----------

    private fun updateUi() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasBattery = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        val hasNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val serviceRunning = ScreenOffService.isRunning

        // 广告跳过：主开关偏好 + 系统无障碍服务是否已开启
        val adSkipUserOn = prefs.getBoolean(AdSkipService.KEY_AD_SKIP_ENABLED, true)
        val adSkipAccOn = isAccessibilityServiceEnabled(AdSkipService::class.java)

        btnOverlay.text = if (hasOverlay) "✔ 悬浮窗权限已开启" else "去开启悬浮窗权限"
        btnOverlay.isEnabled = !hasOverlay

        btnBattery.text = if (hasBattery) "✔ 电池优化已忽略" else "去设置忽略电池优化"
        btnBattery.isEnabled = !hasBattery

        btnNotification.isEnabled = !hasNotification
        btnNotification.text = if (hasNotification) "✔ 通知权限已开启" else "去开启通知权限"

        btnService.text = if (serviceRunning) {
            getString(R.string.btn_service_stop)
        } else {
            getString(R.string.btn_service_start)
        }
        btnService.isEnabled = hasOverlay && hasBattery

        statusText.text = buildString {
            append("服务状态：").append(if (serviceRunning) "运行中" else "未运行").append('\n')
            append("悬浮窗权限：").append(if (hasOverlay) "已开启" else "未开启").append('\n')
            append("电池优化：").append(if (hasBattery) "已忽略（无限制）" else "未忽略").append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append("通知权限：").append(if (hasNotification) "已开启" else "未开启").append('\n')
            }
            append("广告跳过：").append(
                if (adSkipAccOn && adSkipUserOn) "开启" else "未开启"
            )
        }

        // 广告跳过控制区
        updatingUi = true
        switchAdSkip.isChecked = adSkipUserOn
        updatingUi = false
        btnAdSkipSettings.isEnabled = !adSkipAccOn
        btnAdSkipSettings.text = if (adSkipAccOn) {
            getString(R.string.btn_ad_skip_settings_done)
        } else {
            getString(R.string.btn_ad_skip_settings)
        }
        adSkipStatus.text = buildString {
            append(if (adSkipAccOn) {
                getString(R.string.ad_skip_status_on)
            } else {
                getString(R.string.ad_skip_status_off)
            }).append('\n')
            if (!adSkipUserOn) {
                append(getString(R.string.ad_skip_switch_off)).append('\n')
            }
            if (!adSkipAccOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append(getString(R.string.ad_skip_restricted_hint))
            }
        }
    }

    // ---------- 广告跳过 ----------

    /** 检测无障碍服务是否已在系统设置中开启 */
    private fun isAccessibilityServiceEnabled(serviceClass: Class<*>): Boolean {
        val expected = "$packageName/${serviceClass.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // ---------- 权限引导 ----------

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        overlayLauncher.launch(intent)
    }

    private fun openBatterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:$packageName".toUri()
        )
        batteryLauncher.launch(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------- 服务启停 ----------

    private fun toggleService() {
        if (ScreenOffService.isRunning) {
            prefs.edit { putBoolean(KEY_USER_STOPPED, true) }
            stopService(Intent(this, ScreenOffService::class.java))
        } else {
            prefs.edit { putBoolean(KEY_USER_STOPPED, false) }
            startScreenOffService()
        }
        btnService.postDelayed({ updateUi() }, 300)
    }

    /** 权限满足且用户未主动停止过时，自动拉起服务（保证回到桌面/视频时悬浮球在） */
    private fun maybeAutoStartService() {
        if (ScreenOffService.isRunning) return
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasBattery = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        if (!hasOverlay || !hasBattery) return
        if (prefs.getBoolean(KEY_USER_STOPPED, false)) return
        startScreenOffService()
    }

    private fun startScreenOffService() {
        val intent = Intent(this, ScreenOffService::class.java)
        startForegroundService(intent)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_USER_STOPPED = "user_stopped"
    }
}
