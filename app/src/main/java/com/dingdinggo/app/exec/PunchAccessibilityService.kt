package com.dingdinggo.app.exec

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
        /** 历史事件缓存的容量上限（足够同时保留极速打卡连续两条顶部横幅及其后页面变化） */
        private const val MAX_RECENT = 80

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

    /**
     * 最近捕获到的与应用相关的文本，环形保留一小段，便于判定"打卡成功"。
     *
     * ## 必须线程安全
     *
     * 写方是 onAccessibilityEvent（主线程），读方是 PunchEngine 的
     * verifyResult / readCurrentPageText（Dispatchers.IO 协程）。
     * 原来是裸 ArrayDeque，跨线程读写会 ConcurrentModificationException，
     * 而那个异常会被 PunchEngine 的 catch(Throwable) 吞成"执行异常"，
     * 表现为"莫名其妙打卡失败"。
     *
     * CopyOnWriteArrayList 在这个量级（<=32 条、写入频率是 UI 事件）
     * 完全够用，读路径零锁。
     */
    private val recentTexts = java.util.concurrent.CopyOnWriteArrayList<String>()

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

        // 汇总本事件里所有文本，作为"界面快照"（覆盖普通文本、Notification/Toast 横幅及弹窗容器子树）
        val builder = StringBuilder()
        for (i in 0 until event.text.size) {
            val t = event.text[i]?.toString().orEmpty()
            if (t.isNotBlank()) builder.append(t).append(' ')
        }
        event.contentDescription?.toString()?.let {
            if (it.isNotBlank()) builder.append(it).append(' ')
        }

        // 顶部横幅 / Toast / 系统通知事件（TYPE_NOTIFICATION_STATE_CHANGED）文本通常落在 Notification extras 里
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notif = event.parcelableData as? android.app.Notification
            if (notif != null) {
                val extras = notif.extras
                listOf(
                    extras?.getCharSequence(android.app.Notification.EXTRA_TITLE),
                    extras?.getCharSequence(android.app.Notification.EXTRA_TEXT),
                    extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT),
                    extras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT),
                    notif.tickerText,
                ).forEach { cs ->
                    val s = cs?.toString().orEmpty().trim()
                    if (s.isNotEmpty()) builder.append(s).append(' ')
                }
            }
        }

        // 钉钉内顶部浮层横幅（第一条被第二条覆盖并分先后消失）触发窗口变化时，文本常在子 TextView 中
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val src = runCatching { event.source }.getOrNull()
            if (src != null) {
                val sub = collectBoundedSourceText(src, maxDepth = 6, maxNodes = 32)
                if (sub.isNotEmpty()) builder.append(sub).append(' ')
            }
        }

        val snapshot = builder.toString().trim()
        if (snapshot.isNotEmpty()) {
            _lastScreenText.value = snapshot
            val last = recentTexts.lastOrNull()
            if (last != snapshot) {
                recentTexts.add(snapshot)
                while (recentTexts.size > MAX_RECENT) recentTexts.removeAt(0)
            }
        }
    }

    /** 有界收集事件源节点文本，避免遗漏顶部横幅容器子节点文案 */
    private fun collectBoundedSourceText(
        root: AccessibilityNodeInfo,
        maxDepth: Int,
        maxNodes: Int,
    ): String {
        val sb = StringBuilder()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > maxDepth || visited >= maxNodes) return
            visited++
            val own = nodeOwnLabel(node)
            if (own.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(own)
            }
            for (i in 0 until node.childCount) {
                if (visited >= maxNodes) break
                walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
            }
        }
        walk(root, 0)
        return sb.toString().trim()
    }

    /** 最近若干条界面/横幅文本快照列表（按时间先后） */
    fun recentTextLines(): List<String> = recentTexts.toList()

    /** 最近若干条界面文本，合并成一个字符串用于判定 */
    fun recentTextBlob(): String = recentTexts.joinToString(" ")

    /** 清空历史快照，避免上一次打卡的"打卡成功"污染本次判定 */
    fun resetTextHistory() {
        recentTexts.clear()
        _lastScreenText.value = ""
    }

    /**
     * 只清"历史事件缓存"，保留当前窗口文本。
     *
     * verifyResult 在点击之后调用它：点击之前的界面事件（可能是上一页残留的
     * "网络异常" / "打卡成功"）不该参与本次判定，但当前窗口仍然要能读到。
     */
    fun resetEventHistoryOnly() {
        recentTexts.clear()
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
        val roots = dingTalkRoots()
        if (roots.isEmpty()) return null

        val allNodes = ArrayList<AccessibilityNodeInfo>()
        for (root in roots) {
            collectTreePreOrder(root, allNodes, depth = 0)
        }

        // 第一轮：精确相等（先用系统索引，再用全树 DFS 覆盖 contentDescription / UCWebView 虚拟节点）
        for (candidate in candidates) {
            for (root in roots) {
                val found = runCatching {
                    root.findAccessibilityNodeInfosByText(candidate)
                }.getOrNull() ?: emptyList()
                for (node in found) {
                    if (!hasRenderableBounds(node)) continue
                    val label = nodeOwnLabel(node)
                    if (label == candidate || label.replace("\n", "") == candidate) {
                        clickableAncestorOrSelf(node)?.let { return it }
                    }
                }
            }
            for (node in allNodes) {
                if (!hasRenderableBounds(node)) continue
                val label = nodeOwnLabel(node)
                if (label == candidate || label.replace("\n", "") == candidate) {
                    clickableAncestorOrSelf(node)?.let { return it }
                }
            }
        }

        // 第二轮：包含匹配（仅长候选，允许按钮内附带时间/日期/外勤副标题）
        for (candidate in candidates) {
            if (candidate.length < 4) continue
            for (root in roots) {
                val found = runCatching {
                    root.findAccessibilityNodeInfosByText(candidate)
                }.getOrNull() ?: emptyList()
                for (node in found) {
                    if (!hasRenderableBounds(node)) continue
                    val text = nodeOwnLabel(node)
                    if (!text.contains(candidate)) continue
                    if (!isPlausibleTarget(text, candidate)) continue
                    clickableAncestorOrSelf(node)?.let { return it }
                }
            }
            for (node in allNodes) {
                if (!hasRenderableBounds(node)) continue
                val text = nodeOwnLabel(node)
                if (!text.contains(candidate)) continue
                if (!isPlausibleTarget(text, candidate)) continue
                clickableAncestorOrSelf(node)?.let { return it }
            }
        }

        // 第三轮：后序遍历可点击容器的子树聚合文本（应对 H5 把 "上班"+"打卡" 拆进多个子 span 的情况）
        val postOrder = ArrayList<AccessibilityNodeInfo>()
        for (root in roots) {
            collectTreePostOrder(root, postOrder, depth = 0)
        }
        for (candidate in candidates) {
            if (candidate.length < 4) continue
            for (node in postOrder) {
                if (!node.isClickable || !hasRenderableBounds(node) || isOversizedContainer(node)) continue
                val combined = collectSubtreeLabel(node, maxLen = 64)
                if (combined.contains(candidate) && isPlausibleTarget(combined, candidate)) {
                    return node
                }
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
     * 同时放宽长度阈值到 48 字，允许 H5 按钮携带时间/日期/外勤副标题。
     */
    private fun isPlausibleTarget(nodeText: String, candidate: String): Boolean {
        val compact = nodeText.replace("\n", " ").trim()
        val maxLen = maxOf(48, candidate.length * 4)
        if (compact.length > maxLen) return false

        val rejectSuffixes = listOf(
            "记录", "详情", "统计", "设置", "小助手", "助手",
            "申请", "审批", "规则", "说明", "帮助", "更新打卡",
        )
        for (suffix in rejectSuffixes) {
            // 例："打卡记录" 含 "打卡" —— 但它是记录，不是按钮
            if (compact.contains(suffix) && !candidate.contains(suffix)) return false
        }
        return true
    }

    private fun nodeOwnLabel(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val text = runCatching { node.text?.toString() }.getOrNull().orEmpty().trim()
        if (text.isNotEmpty()) return text
        val desc = runCatching { node.contentDescription?.toString() }.getOrNull().orEmpty().trim()
        if (desc.isNotEmpty()) return desc
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val hint = runCatching { node.hintText?.toString() }.getOrNull().orEmpty().trim()
            if (hint.isNotEmpty()) return hint
        }
        return ""
    }

    /** 安全读取节点文本（stale node 时不抛异常） */
    private fun safeText(node: AccessibilityNodeInfo?): String = nodeOwnLabel(node)

    /** 安全判断节点是否可见 */
    private fun safeVisible(node: AccessibilityNodeInfo?): Boolean =
        runCatching { node?.isVisibleToUser == true }.getOrDefault(false)

    private fun hasRenderableBounds(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val rect = android.graphics.Rect()
        val hasRect = runCatching { node.getBoundsInScreen(rect); true }.getOrDefault(false)
        if (safeVisible(node)) {
            return !hasRect || (rect.width() > 0 && rect.height() > 0)
        }
        // UCWebView 虚拟节点常返回 isVisibleToUser=false，但拥有屏幕内有效坐标
        return hasRect &&
            rect.width() > 4 &&
            rect.height() > 4 &&
            rect.right > 0 &&
            rect.bottom > 0 &&
            rect.top < 3600 &&
            rect.left < 2000
    }

    private fun isOversizedContainer(node: AccessibilityNodeInfo): Boolean {
        val cls = runCatching { node.className?.toString() }.getOrNull().orEmpty()
        if (cls.endsWith("WebView") || cls.endsWith("ScrollView") ||
            cls.endsWith("ListView") || cls.endsWith("RecyclerView")
        ) {
            return true
        }
        val rect = android.graphics.Rect()
        if (runCatching { node.getBoundsInScreen(rect); true }.getOrDefault(false)) {
            if (rect.width() > 1000 && rect.height() > 1400) return true
        }
        return false
    }

    private fun dingTalkRoots(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val seen = LinkedHashSet<AccessibilityNodeInfo>()
        val active = rootNodeOrNull()
        if (active != null && active.packageName?.toString() == DingTalkUi.PACKAGE) {
            seen.add(active)
            result.add(active)
        }
        val allWindows = runCatching { windows }.getOrNull()
        if (allWindows != null) {
            for (win in allWindows) {
                val root = runCatching { win.root }.getOrNull() ?: continue
                if (root.packageName?.toString() == DingTalkUi.PACKAGE && seen.add(root)) {
                    result.add(root)
                }
            }
        }
        if (result.isEmpty() && active != null) {
            result.add(active)
        }
        return result
    }

    private fun collectTreePreOrder(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (node == null || depth > 40 || out.size >= 1200) return
        out.add(node)
        for (i in 0 until node.childCount) {
            collectTreePreOrder(node.getChild(i), out, depth + 1)
        }
    }

    private fun collectTreePostOrder(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (node == null || depth > 40 || out.size >= 1200) return
        for (i in 0 until node.childCount) {
            collectTreePostOrder(node.getChild(i), out, depth + 1)
        }
        out.add(node)
    }

    private fun collectSubtreeLabel(node: AccessibilityNodeInfo?, maxLen: Int): String {
        val sb = StringBuilder()
        fun walk(cur: AccessibilityNodeInfo?, depth: Int) {
            if (cur == null || depth > 6 || sb.length >= maxLen) return
            val own = nodeOwnLabel(cur)
            if (own.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(own)
            }
            for (i in 0 until cur.childCount) {
                walk(cur.getChild(i), depth + 1)
            }
        }
        walk(node, 0)
        return sb.toString().trim()
    }

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
            if (hasRenderableBounds(node)) {
                clickableAncestorOrSelf(node)?.let { return it }
            }
        }
        return null
    }

    /** 节点自身不可点击时向上找可点击祖先（跳过 WebView 等超大滚动容器） */
    private fun clickableAncestorOrSelf(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && hasRenderableBounds(cur) && !isOversizedContainer(cur)) return cur
            cur = cur.parent
            depth++
        }
        // 整条链都不可点击（或只有大容器可点击）时，返回原始节点按自身坐标点击
        return node?.takeIf { hasRenderableBounds(it) }
    }

    /** 点击节点；preferGesture=true 时优先按屏幕中心坐标派发手势点击（更适合 H5/UCWebView） */
    fun clickNode(node: AccessibilityNodeInfo, preferGesture: Boolean = false): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val hasBounds = rect.width() > 0 && rect.height() > 0

        if (!preferGesture && node.isClickable) {
            val ok = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (ok) return true
        }

        if (hasBounds) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            return GestureExecutor.tap(cx, cy)
        }

        if (preferGesture && node.isClickable) {
            return runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        }
        return false
    }

    /** 按文本点击；找不到返回 false */
    fun clickByText(candidates: List<String>, preferGesture: Boolean = false): Boolean {
        val node = findClickableByText(candidates) ?: return false
        return clickNode(node, preferGesture)
    }

    /** 读取当前所有钉钉窗口内的文本（兼容 H5 弹窗/浮层多窗口） */
    fun readAllText(): String {
        val roots = dingTalkRoots()
        if (roots.isEmpty()) return ""
        val sb = StringBuilder()
        for (root in roots) {
            collectText(root, sb, 0)
        }
        return sb.toString()
    }

    /**
     * 按**行**读取当前钉钉窗口文本（覆盖多窗口浮层与 WebView 虚拟树）：每一行节点单独成段，行内用空格连接。
     *
     * @return 每一行一个元素；行内文本用空格连接
     */
    fun readTextLines(): List<String> {
        val roots = dingTalkRoots()
        if (roots.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        for (root in roots) {
            collectLines(root, lines, 0)
        }
        return lines.filter { it.isNotBlank() }
    }

    /**
     * 收集文本行。
     *
     * 判定"一行"的口径：以**叶子容器**为单位 ——
     * 一个节点如果有子节点，就继续往下走；只有没有子节点的节点才贡献文本。
     */
    private fun collectLines(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 40) return

        if (node.childCount == 0) {
            val t = runCatching { node.text?.toString() }.getOrNull().orEmpty().trim()
            val d = runCatching { node.contentDescription?.toString() }.getOrNull().orEmpty().trim()
            val merged = listOf(t, d).filter { it.isNotEmpty() }.distinct().joinToString(" ")
            if (merged.isNotEmpty()) out.add(merged)
            return
        }

        // 有子节点：把本节点自己可能带的 text/contentDescription 先记下来，再递归
        val selfT = runCatching { node.text?.toString() }.getOrNull().orEmpty().trim()
        val selfD = runCatching { node.contentDescription?.toString() }.getOrNull().orEmpty().trim()
        val self = listOf(selfT, selfD).filter { it.isNotEmpty() }.distinct().joinToString(" ")
        if (self.isNotEmpty()) out.add(self)
        for (i in 0 until node.childCount) {
            collectLines(node.getChild(i), out, depth + 1)
        }
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

    /** 上滑手势（用于解锁及列表滑动；起点避开屏下指纹区域 ~640,2100 与中部通知列表） */
    fun swipeUp(): Boolean = GestureExecutor.swipe(300f, 2350f, 300f, 1050f, 240)

    /** 返回键 */
    fun pressBack(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
}