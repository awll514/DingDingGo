package com.redmi.autoattend

import com.redmi.autoattend.exec.DingTalkUi
import com.redmi.autoattend.exec.UiHierarchy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uiautomator XML 解析与文本信号判定的单元测试。
 *
 * 这两块是"打卡能不能成"的关键：
 * - 解析错 -> root 通道点不到按钮；
 * - 判定错 -> 把失败当成功，比不打卡更糟。
 */
class UiHierarchyTest {

    private val sampleXml = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
        <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
              package="com.alibaba.android.rimet" content-desc=""
              clickable="false" bounds="[0,0][1080,2400]">
          <node index="0" text="工作" resource-id="com.alibaba.android.rimet:id/tab_work"
                class="android.widget.TextView" package="com.alibaba.android.rimet"
                content-desc="" clickable="true" bounds="[432,2280][648,2380]" />
          <node index="1" text="考勤打卡" resource-id=""
                class="android.widget.TextView" package="com.alibaba.android.rimet"
                content-desc="" clickable="true" bounds="[100,600][380,720]" />
          <node index="2" text="上班打卡" resource-id="com.alibaba.android.rimet:id/clock_btn"
                class="android.widget.TextView" package="com.alibaba.android.rimet"
                content-desc="" clickable="true" bounds="[540,1600][540,1600]" />
          <node index="3" text="打卡成功" resource-id=""
                class="android.widget.TextView" package="com.alibaba.android.rimet"
                content-desc="" clickable="false" bounds="[400,1200][680,1300]" />
        </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun parseNodes_extractsAllNodes() {
        val nodes = UiHierarchy.parseNodes(sampleXml)
        assertEquals(5, nodes.size)
    }

    @Test
    fun findNode_matchesExactText() {
        val node = UiHierarchy.findNode(sampleXml, listOf("考勤打卡"))
        assertNotNull(node)
        assertEquals("考勤打卡", node!!.text)
    }

    @Test
    fun findNode_returnsCorrectCenterCoordinate() {
        val node = UiHierarchy.findNode(sampleXml, listOf("考勤打卡"))!!
        // bounds [100,600][380,720] -> center (240, 660)
        assertEquals(240, node.centerX)
        assertEquals(660, node.centerY)
    }

    @Test
    fun findNode_prefersExactOverContains() {
        // "打卡" 是 "上班打卡" 和 "打卡成功" 的子串。
        // 候选里同时给了 "上班打卡" 和 "打卡"：
        // "上班打卡" 唯一的节点是零面积（不可点），会被跳过；
        // 于是退到精确匹配 "打卡"——样例里没有精确等于 "打卡" 的节点；
        // 最后走包含匹配，命中第一个含 "打卡" 的有效节点 "考勤打卡"。
        //
        // 这个行为是有意为之：零面积节点在真机上点不到，必须跳过而不是死等它。
        val node = UiHierarchy.findNode(sampleXml, listOf("上班打卡", "打卡"))
        assertNotNull(node)
        assertEquals("考勤打卡", node!!.text)
    }

    @Test
    fun findNode_fallsBackToContains() {
        val node = UiHierarchy.findNode(sampleXml, listOf("考勤"))
        assertNotNull(node)
        assertEquals("考勤打卡", node!!.text)
    }

    @Test
    fun findNode_skipsZeroSizedBounds() {
        // "上班打卡" 的 bounds 是零面积（[540,1600][540,1600]），
        // 精确匹配时应被跳过；但因为它也是唯一候选，contains 也会跳过，
        // 最终返回 null
        val node = UiHierarchy.findNode(sampleXml, listOf("上班打卡"))
        assertNull(node)
    }

    @Test
    fun findNode_returnsNullForUnknownText() {
        assertNull(UiHierarchy.findNode(sampleXml, listOf("完全不存在的文字")))
    }

    @Test
    fun allText_collectsVisibleText() {
        val text = UiHierarchy.allText(sampleXml)
        assertTrue(text.contains("工作"))
        assertTrue(text.contains("考勤打卡"))
        assertTrue(text.contains("打卡成功"))
    }

    @Test
    fun parseBounds_handlesNegativeCoords() {
        val xml = "<node text=\"x\" bounds=\"[-10,-20][30,40]\" clickable=\"true\" />"
        val nodes = UiHierarchy.parseNodes(xml)
        assertEquals(1, nodes.size)
        assertEquals(10, nodes[0].centerX)  // (-10+30)/2
        assertEquals(10, nodes[0].centerY)  // (-20+40)/2
    }

    // ---------- 文本信号判定 ----------

    @Test
    fun containsSuccess_detectsCommonSignals() {
        assertNotNull(DingTalkUi.containsSuccess("打卡成功"))
        assertNotNull(DingTalkUi.containsSuccess("更新成功"))
        assertNull(DingTalkUi.containsSuccess("请点击打卡"))
    }

    /**
     * 「已打卡」不属于「打卡成功」。
     *
     * 这是一个语义上的重要区分：
     * - "打卡成功" = **本次操作**刚刚成功，是成功的唯一依据；
     * - "已打卡"   = **这个时段之前**就已经打过了，本次什么都没做。
     *
     * 早先两者混在 SUCCESS_SIGNALS 里，导致"我明明没打，但因为页面上
     * 写着上班已打卡，就被判定成本次成功"。现在拆开了。
     */
    @Test
    fun alreadyDoneIsNotSuccess() {
        assertNull(
            "已打卡不能当作本次打卡成功",
            DingTalkUi.containsSuccess("今日已打卡"),
        )
        assertNotNull(DingTalkUi.containsAlreadyDone("今日已打卡"))
    }

    @Test
    fun containsFailure_detectsOutOfRange() {
        assertNotNull(DingTalkUi.containsFailure("不在打卡范围"))
        assertNotNull(DingTalkUi.containsFailure("网络异常，请重试"))
        assertNull(DingTalkUi.containsFailure("打卡成功"))
    }

    @Test
    fun containsAlreadyDone_detects() {
        assertNotNull(DingTalkUi.containsAlreadyDone("今日已打卡"))
    }

    @Test
    fun successSignalsDoNotOverlapWithFailureSignals() {
        // 保证一个文本不会同时被判定成成功和失败
        for (success in DingTalkUi.SUCCESS_SIGNALS) {
            for (failure in DingTalkUi.FAILURE_SIGNALS) {
                assertTrue(
                    "信号冲突: " + success + " vs " + failure,
                    !success.contains(failure) && !failure.contains(success),
                )
            }
        }
    }
}