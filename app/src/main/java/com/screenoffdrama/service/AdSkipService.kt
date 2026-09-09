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
 */
class AdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkipService"
        const val KEY_AD_SKIP_ENABLED = "ad_skip_enabled"
        private const val TARGET_PACKAGE = "com.google.android.youtube"
        private const val SKIP_RESET_DELAY_MS = 1500L
        private const val MAX_SCAN_NODES = 800

        private val SKIP_TEXTS = listOf("跳过广告", "跳过", "Skip Ad", "Skip ads", "Skip")
        private val SKIP_RES_ID_KEYWORDS = listOf(
            "skip_ad_button", "skip_ad_button_view", "ytv_skip_ad", "skip_ad"
        )

        fun isUserEnabled(context: Context): Boolean {
            return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_AD_SKIP_ENABLED, true)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isSkipping = false

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!prefs.getBoolean(KEY_AD_SKIP_ENABLED, true)) return
        if (isSkipping) return
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 尝试跳过广告
                val root = rootInActiveWindow
                if (root != null) {
                    Log.d(TAG, "事件触发: type=${event.eventType}, 尝试检测跳过按钮")
                    if (tryClickSkip(root)) return
                }

                // 活动窗口没找到，尝试所有窗口（包括 PiP）
                for (window in windows) {
                    val windowRoot = window.root ?: continue
                    if (tryClickSkip(windowRoot)) return
                }
            }
        }
    }

    override fun onInterrupt() {}

    // ---------- 广告跳过 ----------

    private fun tryClickSkip(root: AccessibilityNodeInfo): Boolean {
        // L1/L2/L3: 标准跳过按钮
        val skipNode = findSkipNode(root)
        if (skipNode != null) {
            Log.d(TAG, "找到跳过按钮: text='${skipNode.text}', resId='${skipNode.viewIdResourceName}', clickable=${skipNode.isClickable}")

            // 优先直接点击节点本身
            if (skipNode.isClickable && skipNode.isEnabled) {
                if (skipNode.performAction(ACTION_CLICK)) {
                    Log.d(TAG, "直接点击跳过按钮成功")
                    markSkipping()
                    return true
                }
                Log.w(TAG, "直接点击失败，尝试祖先节点")
            }

            // 向上找可点击祖先（限制3层）
            val clickable = findClickableSelfOrAncestor(skipNode, 3)
            if (clickable != null && clickable.isEnabled) {
                Log.d(TAG, "找到可点击祖先: resId='${clickable.viewIdResourceName}', class=${clickable.className}")
                if (clickable.performAction(ACTION_CLICK)) {
                    Log.d(TAG, "点击祖先节点跳过成功")
                    markSkipping()
                    return true
                }
                Log.w(TAG, "点击祖先也失败")
            }

            // 最后尝试：对节点本身执行 ACTION_CLICK（即使 isClickable=false）
            if (skipNode.isEnabled) {
                if (skipNode.performAction(ACTION_CLICK)) {
                    Log.d(TAG, "强制点击节点成功")
                    markSkipping()
                    return true
                }
            }
        }

        // L4: 赞助商广告关闭
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

            // L2: 资源ID
            val resId = node.viewIdResourceName
            if (resId != null && SKIP_RES_ID_KEYWORDS.any { resId.contains(it, ignoreCase = true) }) {
                return node
            }

            // L1: 文本
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && matchesSkipText(text)) {
                return node
            }

            // L1b: contentDescription
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
