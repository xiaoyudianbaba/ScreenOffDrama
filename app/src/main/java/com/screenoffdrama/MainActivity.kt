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
import android.widget.ImageView
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
 * Material Design 3 风格
 */
class MainActivity : ComponentActivity() {

    private lateinit var btnMainAction: Button

    // 状态栏图标
    private lateinit var ivServiceDot: ImageView
    private lateinit var ivOverlayDot: ImageView
    private lateinit var ivBatteryDot: ImageView
    private lateinit var ivNotificationDot: ImageView
    private lateinit var ivAdSkipDot: ImageView
    private lateinit var ivAdSkipIcon: ImageView

    // 状态文本
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvNotificationStatus: TextView
    private lateinit var tvAdSkipStatus: TextView
    private lateinit var tvAllReadyHint: TextView

    // 广告跳过
    private lateinit var switchAdSkip: Switch
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvAccessibilityHint: TextView
    private lateinit var btnAdSkipSettings: Button

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

        // 核心按钮
        btnMainAction = findViewById(R.id.btnMainAction)

        // 状态栏
        ivServiceDot = findViewById(R.id.ivServiceDot)
        ivOverlayDot = findViewById(R.id.ivOverlayDot)
        ivBatteryDot = findViewById(R.id.ivBatteryDot)
        ivNotificationDot = findViewById(R.id.ivNotificationDot)
        ivAdSkipDot = findViewById(R.id.ivAdSkipDot)
        ivAdSkipIcon = findViewById(R.id.ivAdSkipIcon)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        tvAdSkipStatus = findViewById(R.id.tvAdSkipStatus)
        tvAllReadyHint = findViewById(R.id.tvAllReadyHint)

        // 广告跳过
        switchAdSkip = findViewById(R.id.switchAdSkip)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvAccessibilityHint = findViewById(R.id.tvAccessibilityHint)
        btnAdSkipSettings = findViewById(R.id.btnAdSkipSettings)

        // 点击事件
        btnMainAction.setOnClickListener { toggleService() }

        switchAdSkip.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.edit { putBoolean(AdSkipService.KEY_AD_SKIP_ENABLED, isChecked) }
            updateUi()
        }
        btnAdSkipSettings.setOnClickListener { openAccessibilitySettings() }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        maybeAutoStartService()
        btnMainAction.postDelayed({ updateUi() }, 500)
        btnMainAction.postDelayed({ updateUi() }, 1500)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Log.d(TAG, "PiP mode changed: $isInPictureInPictureMode")

        if (isInPictureInPictureMode) {
            hideUiForPip()
        } else {
            showUiFromPip()
        }
    }

    private fun hideUiForPip() {
        btnMainAction.visibility = android.view.View.GONE
    }

    private fun showUiFromPip() {
        btnMainAction.visibility = android.view.View.VISIBLE
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

        // ===== 核心按钮文字 =====
        btnMainAction.text = if (serviceRunning) {
            getString(R.string.btn_service_stop)
        } else {
            getString(R.string.btn_service_start)
        }
        btnMainAction.isEnabled = hasOverlay && hasBattery

        // ===== 状态栏 =====
        // 服务状态
        ivServiceDot.setImageResource(
            if (serviceRunning) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_gray
        )
        tvServiceStatus.text = if (serviceRunning) "运行中" else "未运行"
        tvServiceStatus.setTextColor(if (serviceRunning) getColor(R.color.success) else getColor(R.color.on_surface_variant))

        // 悬浮窗权限
        ivOverlayDot.setImageResource(if (hasOverlay) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_gray)
        tvOverlayStatus.text = if (hasOverlay) "已开启" else "未开启"

        // 电池优化
        ivBatteryDot.setImageResource(if (hasBattery) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_gray)
        tvBatteryStatus.text = if (hasBattery) "已忽略（无限制）" else "未忽略"

        // 通知权限
        ivNotificationDot.setImageResource(if (hasNotification) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_gray)
        tvNotificationStatus.text = if (hasNotification) "已开启" else "未开启"

        // 广告跳过
        val adSkipEnabled = adSkipAccOn && adSkipUserOn
        ivAdSkipDot.setImageResource(if (adSkipEnabled) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_gray)
        ivAdSkipIcon.setImageResource(if (adSkipEnabled) R.drawable.bg_circle_badge else R.drawable.bg_circle_badge_error)

        val skipCount = AdSkipService.getSkipCount(this)
        tvAdSkipStatus.text = if (adSkipEnabled) {
            "开启 ｜ 累计跳过：$skipCount 次"
        } else if (adSkipUserOn && !adSkipAccOn) {
            "无障碍未开启 ｜ 累计跳过：$skipCount 次"
        } else {
            "未开启 ｜ 累计跳过：$skipCount 次"
        }

        // 全部就绪提示
        val allReady = serviceRunning && hasOverlay && hasBattery && hasNotification && adSkipEnabled
        tvAllReadyHint.visibility = if (allReady) android.view.View.VISIBLE else android.view.View.GONE

        // ===== 广告跳过区域 =====
        updatingUi = true
        switchAdSkip.isChecked = adSkipUserOn
        updatingUi = false

        val accOn = adSkipAccOn
        btnAdSkipSettings.isEnabled = !accOn
        btnAdSkipSettings.text = if (accOn) {
            getString(R.string.btn_ad_skip_settings_done)
        } else {
            getString(R.string.btn_ad_skip_settings)
        }

        tvAccessibilityStatus.text = if (accOn) {
            getString(R.string.ad_skip_status_on)
        } else {
            getString(R.string.ad_skip_status_off)
        }
        tvAccessibilityStatus.setBackgroundResource(
            if (accOn) R.drawable.bg_chip_success else R.drawable.bg_chip_warning
        )
        tvAccessibilityStatus.setTextColor(
            if (accOn) getColor(R.color.on_success_container) else getColor(R.color.on_warning_container)
        )

        // 显示跳过记录
        val skipLog = AdSkipService.getSkipLog(this)
        if (skipLog.isNotBlank()) {
            tvAccessibilityHint.text = "跳过记录：\n$skipLog"
        } else {
            tvAccessibilityHint.text = getString(R.string.accessibility_service_description)
        }
    }

    // ---------- 广告跳过 ----------
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
        btnMainAction.postDelayed({ updateUi() }, 300)
    }

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