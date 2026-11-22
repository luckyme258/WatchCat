package com.luckyme.watchcat.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用列表判定层验收表（P2）。
 *
 * 这里每条用例都对应一条"写错就会出事"的规则：
 *   · 默认全可见（fail-open），否则用户什么都没配就把应用搞崩；
 *   · 系统身份/自身永不隐藏（自杀保护）；
 *   · 白名单模式空集=不隐藏（一次写错不该把目标应用的所有依赖藏掉）；
 *   · bootId 与本次开机不一致=授权失效（L10.1 的"重启后旧授权一律无效"）。
 */
class PkgPolicyTest {

    private val p = PkgPolicy
    private val selfPkg = "com.example.target"
    private val selfUid = 10200
    private val boot = "boot-aaa"

    private fun hide(vararg names: String, armed: Boolean = true, bootId: String = boot) =
        p.parse(
            buildString {
                append("version=1\n")
                append("armed=").append(if (armed) "yes" else "no").append('\n')
                append("bootId=").append(bootId).append('\n')
                append("mode=hide\n")
                append("hide=").append(names.joinToString(",")).append('\n')
            }
        )

    @Test
    fun emptyPolicyIsNeverEnabled() {
        val snap = p.parse(null)
        assertFalse(p.enabled(snap, boot))
        assertFalse(p.isHidden("com.other.app", null, selfPkg, selfUid, snap))
    }

    @Test
    fun armedRequiresMatchingBootId() {
        val snap = hide("com.other.app")
        assertTrue(p.enabled(snap, boot))
        assertFalse(p.enabled(snap, "boot-bbb"))
    }

    @Test
    fun armedWithoutListIsNotEnabled() {
        val snap = p.parse("version=1\narmed=yes\nbootId=$boot\nmode=hide\nhide=\n")
        assertFalse(p.enabled(snap, boot))
    }

    @Test
    fun hideListHidesOnlyListedPackage() {
        val snap = hide("com.other.app")
        assertTrue(p.isHidden("com.other.app", null, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("com.third.app", null, selfPkg, selfUid, snap))
    }

    @Test
    fun selfPackageAndSelfUidAreNeverHidden() {
        val snap = hide(selfPkg)
        assertFalse("自己的包被隐藏会让应用第一次自检就崩", p.isHidden(selfPkg, null, selfPkg, selfUid, snap))

        val snapUid = hide("com.sibling.app")
        assertFalse(p.isHidden("com.sibling.app", selfUid, selfPkg, selfUid, snapUid))
    }

    @Test
    fun systemAppsAndFrameworkAreNeverHidden() {
        val snap = p.parse("version=1\narmed=yes\nbootId=$boot\nmode=show\nshow=com.example.target\n")
        assertFalse(p.isHidden("android", null, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("com.android.systemui", 1000, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("com.android.settings", 1000, selfPkg, selfUid, snap))
    }

    @Test
    fun showModeHidesUnlistedButEmptyShowMeansNothingHidden() {
        val snap = p.parse("version=1\narmed=yes\nbootId=$boot\nmode=show\nshow=com.keep.app\n")
        assertFalse(p.isHidden("com.keep.app", null, selfPkg, selfUid, snap))
        assertTrue(p.isHidden("com.other.app", null, selfPkg, selfUid, snap))

        val empty = p.parse("version=1\narmed=yes\nbootId=$boot\nmode=show\nshow=\n")
        assertFalse(
            "白名单为空时必须退化成不隐藏",
            p.isHidden("com.other.app", null, selfPkg, selfUid, empty),
        )
    }

    @Test
    fun parseIgnoresBrokenLinesAndComments() {
        val snap = p.parse(
            """
            # 注释
            version=1
            armed=yes
            bootId=$boot
            mode=hide
            hide=com.a,,com.b
            unknown=whatever
            broken-line-without-equals
            """.trimIndent()
        )
        assertEquals(setOf("com.a", "com.b"), snap.hide)
        assertTrue(p.enabled(snap, boot))
    }
}
