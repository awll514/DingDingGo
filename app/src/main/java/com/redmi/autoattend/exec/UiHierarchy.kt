package com.redmi.autoattend.exec

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

        for (candidate in candidates) {
            nodes.firstOrNull { it.text == candidate && it.bounds.width > 0 && it.bounds.height > 0 }
                ?.let { return it }
        }
        for (candidate in candidates) {
            nodes.firstOrNull {
                it.contentDesc == candidate && it.bounds.width > 0 && it.bounds.height > 0
            }?.let { return it }
        }
        for (candidate in candidates) {
            nodes.firstOrNull {
                it.text.contains(candidate) && it.bounds.width > 0 && it.bounds.height > 0
            }?.let { return it }
        }
        for (candidate in candidates) {
            nodes.firstOrNull {
                it.contentDesc.contains(candidate) && it.bounds.width > 0 && it.bounds.height > 0
            }?.let { return it }
        }
        return null
    }

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