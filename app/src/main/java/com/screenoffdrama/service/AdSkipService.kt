package com.screenoffdrama.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK

/**
 * YouTube 广告自动跳过 + 播放控制（无障碍服务）
 *
 * - 广告跳过：检测"跳过广告"按钮并模拟点击
 * - 自动恢复：检测 YouTube 被暂停时自动恢复播放
 * - 与 ScreenOffService 配合：息屏模式下 YouTube 全屏，广告按钮完整可见
 *
 * 说明：所有检测均在本地完成，不读取、不存储、不传输任何用户数据。
 */
class AdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkipService"
        const val KEY_AD_SKIP_ENABLED = "ad_skip_enabled"

        private const val TARGET_PACKAGE = "com.google.android.youtube"
        private const val SKIP_RESET_DELAY_MS = 1000L
        private const val MAX_SCAN_NODES = 600

        /** 广告跳过按钮文案 */
        private val SKIP_TEXTS = listOf("跳过广告", "跳过", "Skip Ad", "Skip ads", "Skip")

        /** 广告跳过按钮资源ID */
        private val SKIP_RES_ID_KEYWORDS = listOf(
            "skip_ad_button", "skip_ad_button_view", "ytv_skip_ad", "skip_ad"
        )

        /** 播放/暂停按钮资源ID */
        private val PLAY_PAUSE_RES_IDS = listOf(
            "player_control_play_pause_replay_button",
            "play_button",
            "pause_button"
        )

        fun isUserEnabled(context: Context): Boolean {
            return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_AD_SKIP_ENABLED, true)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isSkipping = false
    private var isResumingPlay = false

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
                if (isSkipping) return

                // 1. 尝试跳过广告
                val root = rootInActiveWindow
                if (root != null && tryClickSkip(root)) return

                for (window in windows) {
                    val windowRoot = window.root ?: continue
                    if (tryClickSkip(windowRoot)) return
                }

                // 2. 检测是否被暂停，自动恢复播放
                if (!isResumingPlay) {
                    checkAndResumePlayback(root)
                }
            }
        }
    }

    override fun onInterrupt() {}

    // ---------- 广告跳过 ----------

    private fun tryClickSkip(root: AccessibilityNodeInfo): Boolean {
        val skipNode = findSkipNode(root)
        if (skipNode != null) {
            Log.d(TAG, "找到跳过按钮: text=${skipNode.text}, resId=${skipNode.viewIdResourceName}")

            // 优先直接点击节点本身
            if (skipNode.isClickable && skipNode.isEnabled) {
                if (skipNode.performAction(ACTION_CLICK)) {
                    Log.d(TAG, "直接点击跳过按钮成功")
                    markSkipping()
                    return true
                }
            }
            // 向上找2层可点击祖先
            val clickable = findClickableSelfOrAncestor(skipNode, 2)
            if (clickable != null && clickable.isEnabled) {
                if (clickable.performAction(ACTION_CLICK)) {
                    Log.d(TAG, "点击祖先节点跳过成功")
                    markSkipping()
                    return true
                }
            }
        }

        // L4: 关闭赞助商广告
        val sponsorAdNode = findSponsoredAdCloseButton(root)
        if (sponsorAdNode != null) {
            Log.d(TAG, "找到赞助商广告关闭按钮")
            if (sponsorAdNode.performAction(ACTION_CLICK)) {
                Log.d(TAG, "关闭赞助商广告成功")
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

            // L2：资源ID命中
            val resId = node.viewIdResourceName
            if (resId != null && SKIP_RES_ID_KEYWORDS.any { resId.contains(it, ignoreCase = true) }) {
                return node
            }

            // L1：文本命中
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && matchesSkipText(text)) {
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
            if (contentDesc == "关闭广告" && node.isClickable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ---------- 自动恢复播放 ----------

    /**
     * 检测 YouTube 是否被暂停，如果是则自动点击播放按钮恢复播放。
     * 仅在息屏模式下生效（ScreenOffService.isRunning）。
     */
    private fun checkAndResumePlayback(root: AccessibilityNodeInfo?) {
        if (!ScreenOffService.isRunning) return
        if (root == null) return

        // 查找播放/暂停按钮
        val playBtn = findPlayPauseButton(root) ?: return

        // 检查按钮的 content-desc 或 text 判断当前状态
        val desc = playBtn.contentDescription?.toString() ?: ""
        val text = playBtn.text?.toString() ?: ""

        // YouTube 暂停时按钮显示"播放"或"Play"，正在播放时显示"暂停"或"Pause"
        val isPaused = desc.contains("播放") || desc.contains("Play") ||
                text.contains("播放") || text.contains("Play")

        if (isPaused && playBtn.isEnabled) {
            Log.d(TAG, "检测到 YouTube 被暂停，自动恢复播放")
            isResumingPlay = true
            playBtn.performAction(ACTION_CLICK)
            handler.postDelayed({ isResumingPlay = false }, 2000)
        }
    }

    /** 查找播放/暂停按钮 */
    private fun findPlayPauseButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++

            val resId = node.viewIdResourceName ?: ""
            if (PLAY_PAUSE_RES_IDS.any { resId.contains(it, ignoreCase = true) }) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ---------- 工具方法 ----------

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
    }
}
