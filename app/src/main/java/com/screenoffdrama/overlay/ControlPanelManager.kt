package com.screenoffdrama.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout

/**
 * 息屏模式管理页
 *
 * - 从黑色覆盖层滑动触发后显示
 * - 全屏半透明覆盖层，包含退出按钮
 * - TYPE_APPLICATION_OVERLAY 在所有 App 之上，即使底层视频 App 全屏也能覆盖
 */
class ControlPanelManager(
    private val context: Context,
    private val onExitScreenOff: () -> Unit
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var panelView: LinearLayout? = null

    @SuppressLint("SetTextI18n")
    fun show() {
        if (panelView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val density = context.resources.displayMetrics.density

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(0, (60 * density).toInt(), 0, (40 * density).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val exitBtn = Button(context).apply {
            text = "退出息屏"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFFF4444.toInt())
            setPadding((32 * density).toInt(), (20 * density).toInt(), (32 * density).toInt(), (20 * density).toInt())
            setOnClickListener {
                onExitScreenOff()
            }
        }
        layout.addView(exitBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (40 * density).toInt()
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        windowManager.addView(layout, lp)
        panelView = layout
    }

    fun hide() {
        panelView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        panelView = null
    }
}
