package com.screenoffdrama.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * YouTube 广告自动跳过 + 播放保活（无障碍服务）
 *
 * 优先级：广告跳过 > 播放保活
 * 广告跳过点击后设置 isSkipping 标记，1.5 秒内不执行任何操作（包括播放保活），
 * 避免跳过广告后的短暂暂停状态触发误恢复。
 */
class AdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkipService"
        const val KEY_AD_SKIP_ENABLED = "ad_skip_enabled"
        private const val TARGET_PACKAGE = "com.google.android.youtube"
        private const val SKIP_RESET_DELAY_MS = 2000L
        private const val MAX_SCAN_NODES = 800

        private val SKIP_TEXTS = listOf("跳过广告", "跳过", "Skip Ad", "Skip ads", "Skip")
        private val SKIP_RES_ID_KEYWORDS = listOf(
            "skip_ad_button", "skip_ad_button_view", "ytv_skip_ad", "skip_ad"
        )

        /** 广告跳过记录 SharedPreferences 键 */
        private const val PREFS_AD_SKIP_RECORDS = "ad_skip_records"
        private const val KEY_SKIP_COUNT = "skip_count"
        private const val KEY_SKIP_LOG = "skip_log"

        fun isUserEnabled(context: Context): Boolean {
            return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_AD_SKIP_ENABLED, true)
        }

        fun getSkipCount(context: Context): Int {
            return context.getSharedPreferences(PREFS_AD_SKIP_RECORDS, Context.MODE_PRIVATE)
                .getInt(KEY_SKIP_COUNT, 0)
        }

        fun getSkipLog(context: Context): String {
            return context.getSharedPreferences(PREFS_AD_SKIP_RECORDS, Context.MODE_PRIVATE)
                .getString(KEY_SKIP_LOG, "") ?: ""
        }

        fun clearRecords(context: Context) {
            context.getSharedPreferences(PREFS_AD_SKIP_RECORDS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isSkipping = false
    private var lastResumeTime = 0L

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!prefs.getBoolean(KEY_AD_SKIP_ENABLED, true)) return
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 跳过冷却期内不做任何操作
                if (isSkipping) return

                val root = rootInActiveWindow

                // 优先级1：尝试跳过广告
                if (root != null && tryClickSkip(root)) return
                for (window in windows) {
                    val windowRoot = window.root ?: continue
                    if (tryClickSkip(windowRoot)) return
                }

                // 优先级2：息屏模式下检测暂停并恢复（仅在跳过冷却期后）
                if (ScreenOffService.isRunning && !isSkipping) {
                    checkAndResumePlayback(root)
                }
            }
        }
    }

    override fun onInterrupt() {}

    // ==================== 广告跳过 ====================

    private fun tryClickSkip(root: AccessibilityNodeInfo): Boolean {
        val skipNode = findSkipNode(root)
        if (skipNode != null) {
            Log.e(TAG, "找到跳过按钮: text='${skipNode.text}', resId='${skipNode.viewIdResourceName}'")

            // 优先直接点击节点本身
            if (skipNode.isClickable && skipNode.isEnabled) {
                if (skipNode.performAction(ACTION_CLICK)) {
                    Log.e(TAG, "直接点击跳过按钮成功")
                    markSkipping()
                    return true
                }
            }

            // 向上找可点击祖先（限制3层）
            val clickable = findClickableSelfOrAncestor(skipNode, 3)
            if (clickable != null && clickable.isEnabled) {
                if (clickable.performAction(ACTION_CLICK)) {
                    Log.e(TAG, "点击祖先节点跳过成功")
                    markSkipping()
                    return true
                }
            }

            // 兜底：强制点击
            if (skipNode.isEnabled) {
                if (skipNode.performAction(ACTION_CLICK)) {
                    Log.e(TAG, "强制点击跳过按钮成功")
                    markSkipping()
                    return true
                }
            }
        }

        // L4: 赞助商广告关闭
        val sponsorAdNode = findSponsoredAdCloseButton(root)
        if (sponsorAdNode != null) {
            if (sponsorAdNode.performAction(ACTION_CLICK)) {
                Log.e(TAG, "关闭赞助商广告成功")
                markSkipping()
                return true
            }
        }

        return false
    }

    private fun findSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++

            val resId = node.viewIdResourceName
            if (resId != null && SKIP_RES_ID_KEYWORDS.any { resId.contains(it, ignoreCase = true) }) {
                return node
            }

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && matchesSkipText(text)) {
                return node
            }

            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && matchesSkipText(desc)) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findSponsoredAdCloseButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val panel = findNodeByIdSuffix(root, "engagement_panel") ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(panel)
        var visited = 0
        while (queue.isNotEmpty() && visited < 100) {
            val node = queue.removeFirst()
            visited++
            val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
            if (contentDesc == "关闭广告" && node.isClickable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ==================== 播放保活 ====================

    /**
     * 检测 YouTube 是否被暂停，如果是则自动恢复播放。
     * 通过查找播放/暂停按钮的 content-desc 判断状态：
     * - 暂停时按钮显示"播放" / "Play"
     * - 播放中显示"暂停" / "Pause"
     */
    private fun checkAndResumePlayback(root: AccessibilityNodeInfo?) {
        if (root == null) return

        // 防抖：两次恢复至少间隔 3 秒
        val now = System.currentTimeMillis()
        if (now - lastResumeTime < 3000) return

        val playBtn = findPlayPauseButton(root) ?: return

        val desc = playBtn.contentDescription?.toString() ?: ""
        val text = playBtn.text?.toString() ?: ""

        // 暂停状态：按钮提示"播放"
        val isPaused = desc.contains("播放") || desc.contains("Play") ||
                text.contains("播放") || text.contains("Play")

        if (isPaused && playBtn.isEnabled) {
            Log.e(TAG, "检测到 YouTube 暂停 (desc='$desc'), 自动恢复播放")
            lastResumeTime = now
            playBtn.performAction(ACTION_CLICK)
        }
    }

    private fun findPlayPauseButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++

            val resId = node.viewIdResourceName ?: ""
            if (resId.contains("play_pause") || resId.contains("play_button") || resId.contains("pause_button")) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ==================== 工具方法 ====================

    private fun findNodeByIdSuffix(root: AccessibilityNodeInfo, suffix: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++
            val resId = node.viewIdResourceName ?: ""
            if (resId.endsWith(suffix, ignoreCase = true)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun matchesSkipText(text: String): Boolean {
        val t = text.trim()
        if (SKIP_TEXTS.any { t.equals(it, ignoreCase = true) }) return true
        return t.contains("Skip Ad", ignoreCase = true) || t.contains("跳过广告")
    }

    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo, maxDepth: Int = 3): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun markSkipping() {
        isSkipping = true
        handler.postDelayed({ isSkipping = false }, SKIP_RESET_DELAY_MS)
        recordSkip()
    }

    /** 记录广告跳过：次数+1，追加时间戳日志（最多保留20条） */
    private fun recordSkip() {
        val prefs = getSharedPreferences(PREFS_AD_SKIP_RECORDS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_SKIP_COUNT, 0) + 1
        val timeStr = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "$timeStr #$count\n"

        val existingLog = prefs.getString(KEY_SKIP_LOG, "") ?: ""
        // 只保留最近20条
        val lines = existingLog.lines().filter { it.isNotBlank() }.takeLast(19)
        val newLog = (lines + logEntry).joinToString("\n")

        prefs.edit()
            .putInt(KEY_SKIP_COUNT, count)
            .putString(KEY_SKIP_LOG, newLog)
            .apply()

        Log.e(TAG, ">>> 广告跳过 #$count @ $timeStr")
    }
}
