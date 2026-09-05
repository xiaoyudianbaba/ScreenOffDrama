package com.screenoffdrama.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 全屏纯黑覆盖层
 *
 * - TYPE_APPLICATION_OVERLAY：不抢焦点，底层视频 App 不会 onPause
 * - 右上角显示半透明退出按钮，点击后退出息屏模式
 * - 覆盖层延伸到状态栏和导航栏区域，视觉上全屏
 */
class BlackOverlayManager(
    private val context: Context,
    private val onExitClick: (() -> Unit)? = null
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val density = context.resources.displayMetrics.density
        val buttonSize = (32 * density).toInt()
        val margin = (12 * density).toInt()

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            // 尝试隐藏系统栏（对 overlay 可能无效，但不影响）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { v, insets ->
                    v.setPadding(0, 0, 0, 0)
                    insets
                }
            }
        }

        // 右上角半透明圆形退出按钮
        val exitBtn = TextView(context).apply {
            text = "X"
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { onExitClick?.invoke() }
        }

        // 获取状态栏和导航栏高度
        val statusBarHeight = getStatusBarHeight()
        val navBarHeight = getNavBarHeight()

        // 按钮放在状态栏下方
        val btnParams = FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = statusBarHeight + margin
            marginEnd = margin * 2
        }
        container.addView(exitBtn, btnParams)

        // 使用全屏尺寸，覆盖状态栏和导航栏区域
        val bounds = windowManager.maximumWindowMetrics.bounds
        val overlayHeight = bounds.height() + statusBarHeight + navBarHeight

        val lp = WindowManager.LayoutParams(
            bounds.width(),
            overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = -statusBarHeight  // 从状态栏上方开始，覆盖状态栏
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // 尝试通过 systemUiVisibility 隐藏系统栏
        container.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        windowManager.addView(container, lp)
        overlayView = container
    }

    fun remove() {
        overlayView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        overlayView = null
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun getNavBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }
}
