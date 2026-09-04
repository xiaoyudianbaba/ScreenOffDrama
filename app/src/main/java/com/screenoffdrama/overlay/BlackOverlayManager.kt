package com.screenoffdrama.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
        val buttonSize = (48 * density).toInt()
        val margin = (16 * density).toInt()

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 右上角半透明圆形退出按钮
        val exitBtn = TextView(context).apply {
            text = "X"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66FFFFFF)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { onExitClick?.invoke() }
        }

        val btnParams = FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = margin
            marginEnd = margin
        }
        container.addView(exitBtn, btnParams)

        // 取全屏物理尺寸（含状态栏/导航栏/挖孔区域），保证黑屏真正覆盖整个屏幕
        val bounds = windowManager.maximumWindowMetrics.bounds
        val lp = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 允许覆盖到挖孔/状态栏区域（否则顶部会被系统栏避让）
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        windowManager.addView(container, lp)
        overlayView = container
    }

    fun remove() {
        overlayView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        overlayView = null
    }
}
