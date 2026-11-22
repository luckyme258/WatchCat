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
    fun parseIgnoresBrokenLinesAndComments() {        val snap = p.parse(
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

    // ---- ROM 特征包层（2026-11-22 新增）----

    @Test
    fun romLayerMustBeExplicitlyOptedIn() {
        // 只写 armed+bootId，不写 rom=yes：ROM 层不生效，空名单也不启用（默认不生效，L10.1）
        val snap = p.parse("version=1\narmed=yes\nbootId=$boot\nmode=hide\nhide=\n")
        assertFalse(snap.rom)
        assertFalse(p.enabled(snap, boot))
        assertFalse(p.isHidden("org.lineageos.updater", 1000, selfPkg, selfUid, snap))

        // 显式 rom=yes：即使用户名单为空也算"有东西可藏"
        val romOnly = p.parse("version=1\narmed=yes\nbootId=$boot\nrom=yes\n")
        assertTrue(p.enabled(romOnly, boot))
        assertFalse("换一次开机就该失效", p.enabled(romOnly, "boot-bbb"))
    }

    @Test
    fun romLayerHidesLineagePackagesEvenThoughTheyAreSystemUid() {
        val snap = p.parse("version=1\narmed=yes\nbootId=$boot\nrom=yes\nhide=\n")
        assertTrue("LOS 系统应用常是 uid<10000，ROM 层必须排在系统应用保护之前",
            p.isHidden("org.lineageos.updater", 1000, selfPkg, selfUid, snap))
        assertTrue(p.isHidden("org.lineageos.settingsoverlay.fuxi", 1000, selfPkg, selfUid, snap))
        assertTrue(p.isHidden("lineageos.platform", 1000, selfPkg, selfUid, snap))
        assertTrue(p.isHidden("android.overlay.lineage", 1000, selfPkg, selfUid, snap))
    }

    @Test
    fun romLayerNeverTouchesAospCoreOrSelf() {
        val snap = p.parse("version=1\narmed=yes\nbootId=$boot\nrom=yes\nhide=\n")
        assertFalse(p.isHidden("android", null, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("com.android.systemui", 1000, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("com.android.settings", 1000, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("com.android.webview", 1000, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("org.lineageos.updater", null, "org.lineageos.updater", selfUid, snap))
    }

    @Test
    fun romNoTurnsLayerOff() {
        val snap = p.parse("version=1\narmed=yes\nbootId=$boot\nrom=no\nhide=com.other.app\n")
        assertTrue(p.isHidden("com.other.app", null, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("org.lineageos.updater", 1000, selfPkg, selfUid, snap))
    }

    @Test
    fun explicitShowOverridesRomLayer() {
        // 用户在白名单里点名要看到的包，用户意图高于内建 ROM 规则
        val snap = p.parse("version=1\narmed=yes\nbootId=$boot\nmode=show\nrom=yes\nshow=org.lineageos.updater\n")
        assertFalse(p.isHidden("org.lineageos.updater", 1000, selfPkg, selfUid, snap))
    }

    // ---- 「只看基础」：暴露集合语义（2026-11-22 新增，方案 3 的默认姿态）----

    @Test
    fun baseSetModeExposesOnlyListedThirdParty() {
        val snap = p.parse(
            "version=1\narmed=yes\nbootId=$boot\nmode=show\n" +
                "show=com.android.settings,com.miui.home,com.google.android.inputmethod.latin\n",
        )
        // 基础集里的第三方（桌面/输入法）可见
        assertFalse(p.isHidden("com.miui.home", 10200, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("com.google.android.inputmethod.latin", 10189, selfPkg, selfUid, snap))
        // 集外第三方一律看不到
        assertTrue(p.isHidden("com.tencent.mm", 10181, selfPkg, selfUid, snap))
        assertTrue(p.isHidden("com.xunmeng.pinduoduo", 10201, selfPkg, selfUid, snap))
        // 系统应用（uid<10000）本来就有自杀保护，写了没写都一样可见
        assertFalse(p.isHidden("com.android.webview", 1000, selfPkg, selfUid, snap))
        assertFalse(p.isHidden("com.google.android.gms", 1000, selfPkg, selfUid, snap))
        // 调用者自己永远可见
        assertFalse(p.isHidden(selfPkg, selfUid, selfPkg, selfUid, snap))
    }

    @Test
    fun emptyShowSetStaysFailOpenAndNotEnabled() {
        // show 为空 = fail-open（全可见）。App 侧必须补基础集，钩子侧不能"空集隐藏"，否则把依赖全藏崩应用
        val snap = p.parse("version=1\narmed=yes\nbootId=$boot\nmode=show\nshow=\n")
        assertFalse("空白名单不算启用，别给每个进程白挂 60+ 钩子", p.enabled(snap, boot))
        assertFalse(p.isHidden("com.tencent.mm", 10181, selfPkg, selfUid, snap))
    }
}
