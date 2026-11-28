package com.luckyme.watchcat.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧配置 → 单份隐藏名单的合并规则（0.6.0 迁移）。
 *
 * 这里锁的是三条"迁错就丢数据"的规则：
 *   · hide 名单必须取并集并去重（应用宝 8 个 + 淘宝 3 个，交叠的那个只能算一次）；
 *   · `armed=yes` 的白名单目标不能并入 hide（它的 show 集合里是基础集，并进 hide 会把桌面/输入法藏掉），
 *     必须进 paused；
 *   · `armed=no` 的 show 目标本来就全放行，忽略，别在 UI 上吓用户。
 */
class LegacyMergeTest {

    private val boot = "boot-aaa"

    private fun hide(vararg names: String, armed: Boolean = true) = buildString {
        append("version=1\n")
        append("armed=").append(if (armed) "yes" else "no").append('\n')
        append("bootId=").append(boot).append('\n')
        append("mode=hide\n")
        append("hide=").append(names.joinToString(",")).append('\n')
    }

    private fun show(vararg names: String, armed: Boolean = true) = buildString {
        append("version=1\n")
        append("armed=").append(if (armed) "yes" else "no").append('\n')
        append("bootId=").append(boot).append('\n')
        append("mode=show\n")
        append("show=").append(names.joinToString(",")).append('\n')
    }

    @Test
    fun hideListsAreMergedAndDeduplicated() {
        val r = LegacyMerge.merge(
            mapOf(
                "com.tencent.android.qqdownloader" to hide("com.ss.android.ugc.aweme", "com.tencent.mm"),
                "com.taobao.taobao" to hide("com.tencent.mm", "com.eg.android.AlipayGphone"),
                "com.example.empty" to hide(),
            ),
        )
        assertEquals(
            setOf("com.ss.android.ugc.aweme", "com.tencent.mm", "com.eg.android.AlipayGphone"),
            r.hide,
        )
        assertTrue("全是 hide 模式，不该有暂停项", r.paused.isEmpty())
    }

    @Test
    fun armedShowTargetIsPausedInsteadOfMerged() {
        val r = LegacyMerge.merge(
            mapOf(
                "com.mfcloudcalculate.networkdisk" to show("com.android.settings", "com.miui.home"),
                "com.taobao.taobao" to hide("com.tencent.mm"),
            ),
        )
        assertEquals("白名单集合绝不能并进 hide", setOf("com.tencent.mm"), r.hide)
        assertEquals(listOf("com.mfcloudcalculate.networkdisk"), r.paused)
    }

    @Test
    fun disarmedShowTargetIsIgnored() {
        // armed=no 的 show 模式本来就全放行，等于什么都没藏
        val r = LegacyMerge.merge(mapOf("com.github.zhihuplus.lite" to show("com.a", "com.b", armed = false)))
        assertTrue(r.hide.isEmpty())
        assertTrue("不该吓用户说它被暂停了", r.paused.isEmpty())
    }

    @Test
    fun emptyAndBrokenTextsAreIgnored() {
        val r = LegacyMerge.merge(
            mapOf(
                "a" to "",
                "b" to null,
                "c" to "garbage-without-equals",
                "d" to hide("com.tencent.mm"),
            ),
        )
        assertEquals(setOf("com.tencent.mm"), r.hide)
    }
}
