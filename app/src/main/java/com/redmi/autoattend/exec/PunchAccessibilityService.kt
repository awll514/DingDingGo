package com.redmi.autoattend.exec

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍执行通道。
 *
 * 职责：
 * 1. 提供一个"按文本/按 id 查找并点击"的能力给执行引擎；
 * 2. 缓存最近见到过的界面文本，供结果判定；
 * 3. 把"是否在线"暴露给 UI 做保活健康度展示。
 *
 * 设计约束：
 * - 本服务只服务钉钉包名（见 accessibility_service_config.xml），不去监听其它应用，
 *   既省电也避免隐私争议；
 * - 所有查找都返回第一个可点击的可见节点，找不到返回 null，由调用方决定重试或降级。
 */
class PunchAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: PunchAccessibilityService? = null

        /** 无障碍服务是否已连接 */
        fun isReady(): Boolean = instance != null

        /** 拿当前实例，未连接返回 null */
        fun get(): PunchAccessibilityService? = instance
    }

    /** 最近一次从钉钉界面读到的完整文本快照，用于结果判定 */
    private val _lastScreenText = MutableStateFlow("")
    val lastScreenText: StateFlow<String> = _lastScreenText.asStateFlow()

    /** 最近捕获到的与应用相关的文本，环形保留一小段，便于判定"打卡成功" */
    private val recentTexts = ArrayDeque<String>(32)

    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        // 无需处理：每次交互都是主动拉取，不依赖中断回调
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()
        if (pkg != null) _currentPackage.value = pkg

        if (pkg != DingTalkUi.PACKAGE) return

        // 汇总本事件里所有文本，作为"界面快照"
        val builder = StringBuilder()
        for (i in 0 until event.text.size) {
            val t = event.text[i]?.toString().orEmpty()
            if (t.isNotBlank()) builder.append(t).append(' ')
        }
        event.contentDescription?.toString()?.let {
            if (it.isNotBlank()) builder.append(it).append(' ')
        }

        val snapshot = builder.toString().trim()
        if (snapshot.isNotEmpty()) {
            _lastScreenText.value = snapshot
            recentTexts.addLast(snapshot)
            while (recentTexts.size > 32) recentTexts.removeFirst()
        }
    }

    /** 最近若干条界面文本，合并成一个字符串用于判定 */
    fun recentTextBlob(): String = recentTexts.joinToString(" ")

    /** 清空历史快照，避免上一次打卡的"打卡成功"污染本次判定 */
    fun resetTextHistory() {
        recentTexts.clear()
        _lastScreenText.value = ""
    }

    // ---------- 节点查找与操作 ----------

    /** 取当前窗口的根节点 */
    fun rootNodeOrNull(): AccessibilityNodeInfo? =
        runCatching { rootInActiveWindow }.getOrNull()

    /**
     * 按候选文本列表查找一个可点击节点。
     *
     * ## 匹配策略（重要，这里踩过大坑）
     *
     * findAccessibilityNodeInfosByText 是**包含匹配**，不是相等匹配。
     * 这意味着用 "工作" 去查，会命中"工作通知"、"工作台"、"汇报工作"
     * 等一大堆节点——而且返回顺序不确定。
     *
     * 之前就是因为这个，导航时先点到了消息列表里的"工作通知"，
     * 又点到了"汇报工作"，导致打卡流程点错地方。
     *
     * 现在的策略严格分两轮：
     *
     * **第一轮：精确相等**。只接受 text == candidate 的节点。
     * 这一轮能解决 99% 的情况，而且绝对不会误伤。
     *
     * **第二轮：包含匹配**，但只对**长度 >= 4 的候选**开放。
     * 理由：短词（"工作"、"打卡"、"打卡"）包含匹配的误伤率极高，
     * 长词（"考勤打卡"、"上班打卡"）则足够特异。
     *
     * 无论如何，返回前都会跳过明显不是目标的节点（见 isPlausibleTarget）。
     */
    fun findClickableByText(candidates: List<String>): AccessibilityNodeInfo? {
        val root = rootNodeOrNull() ?: return null

        // 第一轮：精确相等
        for (candidate in candidates) {
            val found = runCatching {
                root.findAccessibilityNodeInfosByText(candidate)
            }.getOrNull() ?: continue

            for (node in found) {
                if (!safeVisible(node)) continue
                // findAccessibilityNodeInfosByText 是包含匹配，
                // 这里再严格校验一次文本真的相等
                if (safeText(node) == candidate) {
                    clickableAncestorOrSelf(node)?.let { return it }
                }
            }
        }

        // 第二轮：包含匹配（仅长候选）
        for (candidate in candidates) {
            if (candidate.length < 4) continue
            val found = runCatching {
                root.findAccessibilityNodeInfosByText(candidate)
            }.getOrNull() ?: continue

            for (node in found) {
                if (!safeVisible(node)) continue
                val text = safeText(node)
                if (!text.contains(candidate)) continue
                if (!isPlausibleTarget(text, candidate)) continue
                clickableAncestorOrSelf(node)?.let { return it }
            }
        }

        return null
    }

    /**
     * 过滤掉明显不是目标的包含匹配结果。
     *
     * 典型误伤：
     * - 查"打卡"命中"打卡记录"/"查看详情"/"打卡详情"；
     * - 查"考勤"命中"考勤统计"/"考勤小助手"。
     *
     * 规则：候选词如果出现在"明显是描述性/跳转性"的短语里，就跳过。
     */
    private fun isPlausibleTarget(nodeText: String, candidate: String): Boolean {
        // 目标文本不该比候选长太多（长 2 倍通常说明是整段描述）
        if (nodeText.length > candidate.length * 2 + 4) return false

        val rejectSuffixes = listOf(
            "记录", "详情", "统计", "设置", "小助手", "助手",
            "申请", "审批", "规则", "说明", "帮助",
        )
        for (suffix in rejectSuffixes) {
            // 例："打卡记录" 含 "打卡" —— 但它是记录，不是按钮
            if (nodeText.contains(suffix) && !candidate.contains(suffix)) return false
        }
        return true
    }

    /** 安全读取节点文本（stale node 时不抛异常） */
    private fun safeText(node: AccessibilityNodeInfo?): String =
        runCatching { node?.text?.toString() ?: "" }.getOrDefault("")

    /** 安全判断节点是否可见 */
    private fun safeVisible(node: AccessibilityNodeInfo?): Boolean =
        runCatching { node?.isVisibleToUser == true }.getOrDefault(false)

    /** 按 resourceId 查找可点击节点（辅助手段） */
    fun findClickableById(idSuffix: String): AccessibilityNodeInfo? {
        val root = rootNodeOrNull() ?: return null
        val full = if (idSuffix.startsWith(DingTalkUi.ID_PREFIX)) {
            idSuffix
        } else {
            DingTalkUi.ID_PREFIX + idSuffix
        }
        val found = root.findAccessibilityNodeInfosByViewId(full) ?: return null
        for (node in found) {
            if (node.isVisibleToUser) {
                clickableAncestorOrSelf(node)?.let { return it }
            }
        }
        return null
    }

    /** 节点自身不可点击时向上找可点击祖先 */
    private fun clickableAncestorOrSelf(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isVisibleToUser) return cur
            cur = cur.parent
            depth++
        }
        // 整条链都不可点击时，返回原始节点（上层会退化成按坐标点击）
        return node?.takeIf { it.isVisibleToUser }
    }

    /** 点击节点；失败返回 false */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // 优先 ACTION_CLICK
        if (node.isClickable) {
            val ok = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (ok) return true
        }

        // 退化为坐标点击
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            return GestureExecutor.tap(cx, cy)
        }
        return false
    }

    /** 按文本点击；找不到返回 false */
    fun clickByText(candidates: List<String>): Boolean {
        val node = findClickableByText(candidates) ?: return false
        return clickNode(node)
    }

    /** 读取当前窗口里所有可见文本 */
    fun readAllText(): String {
        val root = rootNodeOrNull() ?: return ""
        val sb = StringBuilder()
        collectText(root, sb, 0)
        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 40) return
        node.text?.toString()?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb, depth + 1)
        }
    }

    /** 当前前台包名（来自 root 节点） */
    fun foregroundPackage(): String? {
        val root = rootNodeOrNull() ?: return null
        return root.packageName?.toString()
    }

    /** 上滑手势（用于解锁） */
    fun swipeUp(): Boolean = GestureExecutor.swipe(540f, 1800f, 540f, 600f, 300)

    /** 返回键 */
    fun pressBack(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
}