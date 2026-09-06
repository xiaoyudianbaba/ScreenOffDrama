package com.screenoffdrama.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * YouTube 广告自动跳过（无障碍服务）
 *
 * - 与 ScreenOffService 相互独立、并行运行，由系统绑定，不受后台启动限制影响
 * - 监听 YouTube 窗口状态/内容变化，自动检测"跳过广告"按钮并模拟点击
 * - 连续广告天然支持：第一段被跳过 → YouTube 刷新界面加载第二段 → 触发新事件 → 再次检测点击
 * - 多策略检测：
 *   L1 文本匹配（"跳过广告 / 跳过 / Skip Ad / Skip"）——标准 UI 广告，最快
 *   L2 资源ID匹配（skip_ad_button / ytv_skip_ad 等）——最稳定，适配各版本
 *   L3 父节点遍历——按钮本身不可点击时，向上找可点击祖先再点击
 *   L4 赞助商广告关闭——检测"赞助商广告"并点击关闭按钮
 * - 防重复：点击后置 isSkipping 标记，1 秒后重置，避免同一广告被反复点击导致循环
 *
 * 说明：所有检测均在本地完成，不读取、不存储、不传输任何用户数据。
 */
class AdSkipService : AccessibilityService() {

    companion object {
        /** 主界面广告跳过开关的 SharedPreferences 键 */
        const val KEY_AD_SKIP_ENABLED = "ad_skip_enabled"

        private const val TARGET_PACKAGE = "com.google.android.youtube"
        private const val SKIP_RESET_DELAY_MS = 1000L

        /** 遍历节点上限，防御异常大窗口拖垮性能 */
        private const val MAX_SCAN_NODES = 600

        /** L1 文本匹配关键词：YouTube 广告跳过按钮常见文案（中英文） */
        private val SKIP_TEXTS = listOf("跳过广告", "跳过", "Skip Ad", "Skip ads", "Skip")

        /** L2 资源ID关键词：YouTube 各版本常见的跳过按钮 view id */
        private val SKIP_RES_ID_KEYWORDS = listOf(
            "skip_ad_button",
            "skip_ad_button_view",
            "ytv_skip_ad",
            "skip_ad"
        )

        /** L4 赞助商广告相关文本 */
        private val SPONSORED_AD_TEXTS = listOf("赞助商广告", "Sponsored", "sponsored")

        /** 主界面是否已开启广告跳过（供 MainActivity 状态显示复用） */
        fun isUserEnabled(context: Context): Boolean {
            return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_AD_SKIP_ENABLED, true)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 防重复标记：点击成功后置 true，1 秒后复位 */
    private var isSkipping = false

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 独立开关：用户在主界面关闭广告跳过后，服务仍运行但不执行任何检测
        if (!prefs.getBoolean(KEY_AD_SKIP_ENABLED, true)) return
        if (isSkipping) return
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 首先尝试从活动窗口检测
                val root = rootInActiveWindow
                if (root != null && tryClickSkip(root)) return
                
                // 如果活动窗口没有找到，尝试所有窗口（包括PiP小窗）
                for (window in windows) {
                    val windowRoot = window.root ?: continue
                    if (tryClickSkip(windowRoot)) return
                }
            }
        }
    }

    override fun onInterrupt() {
        // 系统中断（如无障碍被关闭）时无需特殊处理
    }

    /**
     * 在窗口节点树中查找"跳过广告"节点并点击。
     * 查找优先级：L2 资源ID → L1 文本 → L4 赞助商广告；点击处理：L3 向上找可点击祖先。
     * @return 是否成功找到并点击了跳过按钮
     */
    private fun tryClickSkip(root: AccessibilityNodeInfo): Boolean {
        // L1/L2/L3: 尝试点击标准跳过按钮
        val skipNode = findSkipNode(root)
        if (skipNode != null) {
            // 优先直接点击节点本身
            if (skipNode.isClickable && skipNode.isEnabled) {
                if (skipNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    markSkipping()
                    return true
                }
            }
            // 仅向上找2层可点击祖先（避免找到视频播放器容器）
            val clickable = findClickableSelfOrAncestor(skipNode, 2)
            if (clickable != null && clickable.isEnabled) {
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    markSkipping()
                    return true
                }
            }
        }

        // L4: 尝试关闭赞助商广告
        val sponsorAdNode = findSponsoredAdCloseButton(root)
        if (sponsorAdNode != null) {
            if (sponsorAdNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                markSkipping()
                return true
            }
        }

        return false
    }

    /** BFS 遍历窗口树查找跳过按钮，优先 L2 资源ID，其次 L1 文本 */
    private fun findSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++

            // L2：资源ID命中（最稳定，误判最少）
            val resId = node.viewIdResourceName
            if (resId != null &&
                SKIP_RES_ID_KEYWORDS.any { resId.contains(it, ignoreCase = true) }
            ) {
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

    /**
     * L4: 关闭赞助商广告卡片。
     * 只在 engagement_panel 区域内搜索 content-desc="关闭广告" 的按钮，
     * 避免误点视频播放器区域的元素导致暂停。
     */
    private fun findSponsoredAdCloseButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 先找到 engagement_panel 容器
        val panel = findNodeByIdSuffix(root, "engagement_panel") ?: return null

        // 在面板内搜索关闭按钮
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(panel)
        var visited = 0

        while (queue.isNotEmpty() && visited < 100) {
            val node = queue.removeFirst()
            visited++

            val contentDesc = node.contentDescription?.toString()?.trim() ?: ""

            // 只匹配"关闭广告"，不匹配宽泛的"关闭"/"Close"/"X"
            if (contentDesc == "关闭广告" && node.isClickable) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 按 resource-id 后缀查找节点 */
    private fun findNodeByIdSuffix(root: AccessibilityNodeInfo, suffix: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++

            val resId = node.viewIdResourceName ?: ""
            if (resId.endsWith(suffix, ignoreCase = true)) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 在节点树中查找包含指定文本的节点 */
    private fun findNodeWithText(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++

            val nodeText = node.text?.toString()?.trim() ?: ""
            if (nodeText.isNotEmpty() && texts.any { nodeText.contains(it, ignoreCase = true) }) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 精确命中关键词，或命中带倒计时的按钮文案（如 "Skip Ad 5""跳过广告"） */
    private fun matchesSkipText(text: String): Boolean {
        val t = text.trim()
        if (SKIP_TEXTS.any { t.equals(it, ignoreCase = true) }) return true
        // 倒计时文案（如 "Skip Ad 5"）也命中；避免误伤 "Skip intro / Skipping" 等
        return t.contains("Skip Ad", ignoreCase = true) || t.contains("跳过广告")
    }

    /** 向上（含自身）查找可点击节点，限制层数防止误点视频播放器 */
    private fun findClickableSelfOrAncestor(
        node: AccessibilityNodeInfo,
        maxDepth: Int = 3
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** 点击成功后的防重复标记，1 秒后复位 */
    private fun markSkipping() {
        isSkipping = true
        handler.postDelayed({ isSkipping = false }, SKIP_RESET_DELAY_MS)
    }
}
