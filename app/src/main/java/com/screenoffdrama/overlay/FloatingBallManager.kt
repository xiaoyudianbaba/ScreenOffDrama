package com.screenoffdrama.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.screenoffdrama.R
import kotlin.math.abs

/**
 * 可拖动悬浮球
 *
 * - TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE：悬浮在其他应用之上且不抢焦点
 * - 点击 → 回调（进入息屏）；拖动 → 松手后吸附到最近的屏幕边缘
 */
class FloatingBallManager(
    private val context: Context,
    private val onClick: () -> Unit
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var ballView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    
    // 自动隐藏相关状态
    private var isHidden = false
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideToEdge() }
    
    private companion object {
        const val AUTO_HIDE_DELAY_MS = 3000L
        const val EDGE_HIDE_WIDTH_DP = 4
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (ballView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val size = dp(56)
        val ball = ImageView(context).apply {
            setImageResource(R.drawable.ic_ball)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = context.getString(R.string.ball_desc)
        }

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(240)
        }

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0
        var downY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragged = false

        ball.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 如果处于隐藏状态，先恢复显示
                    if (isHidden) {
                        showFromEdge()
                        return@setOnTouchListener true
                    }
                    downX = lp.x
                    downY = lp.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragged = false
                    cancelAutoHideTimer()
                }

                MotionEvent.ACTION_MOVE -> {
                    lp.x = downX + (event.rawX - downRawX).toInt()
                    lp.y = downY + (event.rawY - downRawY).toInt()
                    if (abs(event.rawX - downRawX) > touchSlop ||
                        abs(event.rawY - downRawY) > touchSlop
                    ) {
                        dragged = true
                    }
                    runCatching { windowManager.updateViewLayout(v, lp) }
                }

                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        snapToEdge(lp)
                        runCatching { windowManager.updateViewLayout(v, lp) }
                        startAutoHideTimer()
                    } else {
                        onClick()
                    }
                }

                else -> Unit
            }
            true
        }

        windowManager.addView(ball, lp)
        ballView = ball
        layoutParams = lp
        startAutoHideTimer()
    }

    /** 吸附到最近的左/右边缘，并把纵向位置限制在屏幕范围内 */
    private fun snapToEdge(lp: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        if (lp.x + lp.width / 2 < screenWidth / 2) {
            lp.x = 0
        } else {
            lp.x = screenWidth - lp.width
        }
        lp.y = lp.y.coerceIn(0, screenHeight - lp.height)
    }
    
    // ---------- 自动隐藏逻辑 ----------
    
    private fun startAutoHideTimer() {
        cancelAutoHideTimer()
        autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }
    
    private fun cancelAutoHideTimer() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
    }
    
    private fun hideToEdge() {
        val lp = layoutParams ?: return
        val ball = ballView ?: return
        
        // 根据当前吸附位置，将悬浮球收缩到边缘
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val edgeWidth = dp(EDGE_HIDE_WIDTH_DP)
        
        if (lp.x == 0) {
            // 左侧吸附，向左收缩
            lp.x = -lp.width + edgeWidth
        } else {
            // 右侧吸附，向右收缩
            lp.x = screenWidth - edgeWidth
        }
        
        isHidden = true
        runCatching { windowManager.updateViewLayout(ball, lp) }
    }
    
    private fun showFromEdge() {
        val lp = layoutParams ?: return
        val ball = ballView ?: return
        
        // 恢复到完整显示位置
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        
        if (lp.x < screenWidth / 2) {
            // 左侧，恢复到左边缘
            lp.x = 0
        } else {
            // 右侧，恢复到右边缘
            lp.x = screenWidth - lp.width
        }
        
        isHidden = false
        runCatching { windowManager.updateViewLayout(ball, lp) }
        startAutoHideTimer()
    }

    fun hide() {
        cancelAutoHideTimer()
        ballView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        ballView = null
        layoutParams = null
        isHidden = false
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
