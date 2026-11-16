package com.dingdinggo.app.exec

/**
 * 极简 uiautomator XML 解析器。
 *
 * 为什么不用 XmlPullParser 依赖之外的东西：这个解析器只需要回答一个问题——
 * "在 UI 层级里找到某个文本的节点，并给出它的屏幕中心坐标"。
 * 用正则足够，且避免引入额外依赖。
 *
 * uiautomator dump 出来的节点形如：
 * <node index="0" text="工作" resource-id="" class="android.widget.TextView"
 *       package="com.alibaba.android.rimet" content-desc=""
 *       checkable="false" checked="false" clickable="true" enabled="true"
 *       focusable="true" focused="false" scrollable="false" long-clickable="false"
 *       password="false" selected="false"
 *       bounds="[120,2100][240,2200]" />
 */
object UiHierarchy {

    /** 一个可点击节点的最小信息 */
    data class Node(
        val text: String,
        val resourceId: String,
        val contentDesc: String,
        val clickable: Boolean,
        val bounds: Bounds,
    ) {
        val centerX: Int get() = bounds.centerX
        val centerY: Int get() = bounds.centerY
    }

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private val NODE_REGEX = Regex("<node\\b[^>]*>")
    private val ATTR_REGEX = Regex("([\\w-]+)=\"([^\"]*)\"")

    /**
     * 在 XML 中查找第一个匹配候选文本的节点。
     *
     * 匹配优先级：
     * 1. text 完全相等；
     * 2. content-desc 完全相等；
     * 3. text 包含；
     * 4. content-desc 包含。
     *
     * 只返回有非零 bounds 的节点（过滤掉不可见的）。
     */
    fun findNode(xml: String, candidates: List<String>): Node? {
        val nodes = parseNodes(xml)

        // 精确匹配（text / content-desc）永远最高优先级，且不受长度限制。
        // 这一层是可靠的：只有字面完全相同才算命中。
        for (candidate in candidates) {
            nodes.firstOrNull { it.text == candidate && it.bounds.width > 0 && it.bounds.height > 0 }
                ?.let { return it }
        }
        for (candidate in candidates) {
            nodes.firstOrNull {
                it.contentDesc == candidate && it.bounds.width > 0 && it.bounds.height > 0
            }?.let { return it }
        }

        // 包含匹配加"节点文本长度上限"约束，避免用短候选误命中消息列表里的长句子。
        //
        // ## 为什么不能简单地"禁掉短候选"
        //
        // 生产候选里确实有短词兜底（"考勤"/"上班"/"下班"），它们必须保留
        // 包含能力，否则考勤页按钮文案一变（例如"上班 打卡"中间带空格、
        // 或"上班打卡（外勤）"）就完全定位不到，直接漏卡。禁用它们
        // 等于用"漏打卡"换"点错"，更糟。
        //
        // ## 真正区分"按钮"与"推送正文"的是节点文本的**长度**
        //
        // 目标控件（按钮/入口）的文本总是**短**的：「上班打卡」4 字、
        // 「打卡」2 字。而误命中的推送正文是**长**文本：
        // 「考勤打卡: 11-11 考勤全部正常」十几个字。
        //
        // 所以约束为：只有**节点自身文本足够短**时才允许被包含匹配命中。
        // 这样"考勤"仍能命中「考勤打卡」按钮（4 字），却不会命中那条
        // 长推送 —— 既保住导航能力，又堵住事故路径。
        for (candidate in candidates) {
            nodes.firstOrNull {
                it.text.contains(candidate) &&
                    it.bounds.width > 0 && it.bounds.height > 0 &&
                    it.text.length <= MAX_CONTAINS_NODE_TEXT_LEN
            }?.let { return it }
        }
        for (candidate in candidates) {
            nodes.firstOrNull {
                it.contentDesc.contains(candidate) &&
                    it.bounds.width > 0 && it.bounds.height > 0 &&
                    it.contentDesc.length <= MAX_CONTAINS_NODE_TEXT_LEN
            }?.let { return it }
        }

        return null
    }

    /**
     * 参与"包含匹配"的**节点文本**最长长度。
     *
     * 按钮/入口类控件的标签通常 ≤8 个字符；超过这个长度基本是正文、
     * 推送、消息预览，绝不可能是我们要点的按钮。
     *
     * 取 10 留一点余量（如「上班 打卡」带空格、「上下班打卡」）。
     */
    private const val MAX_CONTAINS_NODE_TEXT_LEN = 10

    /** 解析出所有节点 */
    fun parseNodes(xml: String): List<Node> {
        val result = ArrayList<Node>()
        for (m in NODE_REGEX.findAll(xml)) {
            val raw = m.value
            val attrs = HashMap<String, String>()
            for (a in ATTR_REGEX.findAll(raw)) {
                attrs[a.groupValues[1]] = a.groupValues[2]
            }
            val bounds = parseBounds(attrs["bounds"]) ?: continue
            result.add(
                Node(
                    text = attrs["text"].orEmpty(),
                    resourceId = attrs["resource-id"].orEmpty(),
                    contentDesc = attrs["content-desc"].orEmpty(),
                    clickable = attrs["clickable"] == "true",
                    bounds = bounds,
                )
            )
        }
        return result
    }

    /** 解析 bounds="[l,t][r,b]" */
    private fun parseBounds(raw: String?): Bounds? {
        if (raw == null) return null
        val nums = Regex("-?\\d+").findAll(raw).map { it.value.toIntOrNull() ?: 0 }.toList()
        if (nums.size < 4) return null
        return Bounds(nums[0], nums[1], nums[2], nums[3])
    }

    /** 把整页文本拼出来，用于结果判定 */
    fun allText(xml: String): String =
        parseNodes(xml).joinToString(" ") { it.text + " " + it.contentDesc }
}